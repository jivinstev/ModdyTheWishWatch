#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# refresh-cbscript-bundle.sh — vendor the CBScript compiler (its Java port) from moddy-cbscript.
#
#   ./tools/refresh-cbscript-bundle.sh                 master of https://github.com/jivinstev/moddy-cbscript
#   ./tools/refresh-cbscript-bundle.sh --ref <branch|commit>
#   CBSCRIPT_REF=<branch|commit> ./tools/refresh-cbscript-bundle.sh
#   CBSCRIPT_DIR=<a git checkout> ./tools/refresh-cbscript-bundle.sh   (secondary: a fix not pushed yet)
#   ./tools/refresh-cbscript-bundle.sh --source-only [--ref …]  name the commit the VENDORED tree already is
#                                                              (refuses unless every file matches)
#
# Writes third_party/moddy-cbscript/ (the compiler's Java sources and resources, the Scripts/*.cblib libraries,
# CBSCRIPT_LESSONS.md, the licence and notices) and src/main/resources/tools/cbscript.SOURCE. A new tree changes
# the precompiled ready-mades' fingerprint: rebuild them in the same PR (`./tools/precompile-ready-mades.sh --store`,
# PrecompiledFingerprintTest says so).
#
# WHY. The mod compiles every CBScript datapack with this compiler. It used to bundle the Python compiler and run it
# with whatever Python 3 the player had, which is why the Magician needed Python. moddy-cbscript now has a Java
# port held byte for byte to the Python compiler by its parity corpus (its README, "Two compilers"), so the mod
# compiles in-process, with no Python anywhere. The build compiles third_party/moddy-cbscript/src/main/java into the
# mod's jar (build.gradle), and the bundled libraries load from the classpath.
#
# THE TREE NAMES ITS SOURCE. tools/cbscript.SOURCE records the repo, the branch, the resolved commit and the licence,
# plus tree-sha256, the sha256 of the vendored tree (every file's path and bytes, sorted), so Gate A
# (CbScriptBundleSourceTest) can tell offline that the SOURCE describes the tree beside it. A CBSCRIPT_DIR build
# records that checkout's HEAD, says `local: true`, and refuses a checkout with uncommitted changes.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_URL="https://github.com/jivinstev/moddy-cbscript"
REF="${CBSCRIPT_REF:-master}"
SOURCE_ONLY=false
while [ $# -gt 0 ]; do
    case "$1" in
        --ref) REF="${2:?--ref needs a branch or commit}"; shift 2 ;;
        --ref=*) REF="${1#--ref=}"; shift ;;
        --source-only) SOURCE_ONLY=true; shift ;;
        -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "refresh-cbscript-bundle: unknown argument $1" >&2; exit 2 ;;
    esac
done
OUT="third_party/moddy-cbscript"
SOURCE_OUT="src/main/resources/tools/cbscript.SOURCE"
WORK="$(mktemp -d)" || exit 1
trap 'rm -rf "$WORK"' EXIT

LOCAL=false
if [ -n "${CBSCRIPT_DIR:-}" ]; then
    SRC="$CBSCRIPT_DIR"
    LOCAL=true
    git -C "$SRC" rev-parse --verify -q HEAD >/dev/null \
        || { echo "refresh-cbscript-bundle: $SRC is not a git checkout, so no commit could name it" >&2; exit 2; }
    [ -z "$(git -C "$SRC" status --porcelain --untracked-files=no)" ] \
        || { echo "refresh-cbscript-bundle: $SRC has uncommitted changes; commit them first (the bundle records a commit)" >&2; exit 2; }
    COMMIT="$(git -C "$SRC" rev-parse HEAD)"
    BRANCH="$(git -C "$SRC" rev-parse --abbrev-ref HEAD)"
else
    SRC="$WORK/src"
    git init -q "$SRC"
    git -C "$SRC" fetch -q --depth 1 "$REPO_URL" "$REF" \
        || { echo "refresh-cbscript-bundle: could not fetch $REF from $REPO_URL (github.com reachable?)" >&2; exit 2; }
    git -C "$SRC" -c advice.detachedHead=false checkout -q FETCH_HEAD
    COMMIT="$(git -C "$SRC" rev-parse HEAD)"
    BRANCH="$REF"
fi
[ -d "$SRC/java/src/main/java/com/moddingfromamod/cbscript" ] \
    || { echo "refresh-cbscript-bundle: $SRC has no Java compiler (java/src/main/java/com/moddingfromamod/cbscript)" >&2; exit 2; }
LICENSE_ID=""
if head -1 "$SRC/LICENSE" 2>/dev/null | grep -q 'Mozilla Public License Version 2.0'; then
    LICENSE_ID="MPL-2.0"
fi
[ -n "$LICENSE_ID" ] || { echo "refresh-cbscript-bundle: $SRC/LICENSE is not the MPL-2.0 this bundle has always shipped under; check the licence before bundling" >&2; exit 3; }

# The tree: the compiler's sources and resources (parser tables), the libraries `import name` reads (only the
# .cblib files: Scripts/ also holds the fork author's demo worlds), the lessons the agent is served
# (CbScriptLessons), and the licence and notices that travel with the code.
TREE="$WORK/tree"
mkdir -p "$TREE/src/main/resources/com/moddingfromamod/cbscript/lib"
cp -R "$SRC/java/src/main/java" "$TREE/src/main/java"
cp -R "$SRC/java/src/main/resources/." "$TREE/src/main/resources/"
cp "$SRC"/Scripts/*.cblib "$TREE/src/main/resources/com/moddingfromamod/cbscript/lib/"
LIBS=$(ls -1 "$TREE/src/main/resources/com/moddingfromamod/cbscript/lib" | wc -l | tr -d ' ')
[ "$LIBS" -gt 0 ] || { echo "refresh-cbscript-bundle: no .cblib libraries in $SRC/Scripts" >&2; exit 3; }
cp "$SRC/CBSCRIPT_LESSONS.md" "$TREE/src/main/resources/com/moddingfromamod/cbscript/cbscript-lessons.md"
cp "$SRC/LICENSE" "$TREE/LICENSE"
cp "$SRC/NOTICE.md" "$TREE/NOTICE.md"
if [ -f "$SRC/java/THIRD_PARTY.md" ]; then
    cp "$SRC/java/THIRD_PARTY.md" "$TREE/THIRD_PARTY.md"
fi
cat > "$TREE/README.md" <<EOF
# Vendored: the CBScript compiler (Java)

Do not edit by hand. This is \`java/\` of $REPO_URL at the commit named in
\`src/main/resources/tools/cbscript.SOURCE\`, written by \`tools/refresh-cbscript-bundle.sh\`. Fix the compiler in
moddy-cbscript (where its parity corpus holds it to the Python compiler) and re-run that script.
EOF

tree_sha256() {
    python3 -I -c '
import hashlib, os, sys
root = sys.argv[1]
paths = []
for d, _, fs in os.walk(root):
    for f in fs:
        paths.append(os.path.relpath(os.path.join(d, f), root).replace(os.sep, "/"))
h = hashlib.sha256()
for p in sorted(paths):
    h.update(p.encode("utf-8")); h.update(b"\0")
    h.update(open(os.path.join(root, p), "rb").read()); h.update(b"\0")
print(h.hexdigest())' "$1"
}
write_source() {
    {
        echo "repo: $REPO_URL"
        echo "branch: $BRANCH"
        echo "commit: $COMMIT"
        echo "license: $LICENSE_ID"
        echo "tree: $OUT"
        echo "tree-sha256: $(tree_sha256 "$OUT")"
        if [ "$LOCAL" = true ]; then
            echo "local: true"
        fi
    } > "$SOURCE_OUT"
}

if [ "$SOURCE_ONLY" = true ]; then
    if ! diff -r "$OUT" "$TREE" >"$WORK/diff" 2>&1; then
        echo "refresh-cbscript-bundle: the vendored $OUT is NOT $REPO_URL @ $COMMIT; differences:" >&2
        head -20 "$WORK/diff" >&2
        echo "  (re-vendor without --source-only to ship that commit, or pick the --ref it was taken from)" >&2
        exit 5
    fi
    write_source
    echo "the vendored $OUT is $REPO_URL @ $BRANCH ($COMMIT), file for file; wrote $SOURCE_OUT"
    exit 0
fi

rm -rf "$OUT"
mkdir -p "$(dirname "$OUT")"
cp -R "$TREE" "$OUT"
write_source
echo "vendored $OUT from $REPO_URL @ $BRANCH ($COMMIT)$([ "$LOCAL" = true ] && echo ", the local checkout $SRC"); wrote $SOURCE_OUT"
echo "  $(find "$OUT/src/main/java" -name '*.java' | wc -l | tr -d ' ') Java sources, $LIBS libraries"
