#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# refresh-cbscript-bundle.sh — rebuild src/main/resources/tools/cbscript.zip from moddy-cbscript.
#
#   ./tools/refresh-cbscript-bundle.sh                 master of https://github.com/jivinstev/moddy-cbscript
#   ./tools/refresh-cbscript-bundle.sh --ref <branch|commit>
#   CBSCRIPT_REF=<branch|commit> ./tools/refresh-cbscript-bundle.sh
#   CBSCRIPT_DIR=<a git checkout> ./tools/refresh-cbscript-bundle.sh   (secondary: a fix not pushed yet)
#   ./tools/refresh-cbscript-bundle.sh --source-only [--ref …]  name the commit the SHIPPED zip already is
#                                                              (refuses unless every file matches)
#
# Writes the zip and, beside it, src/main/resources/tools/cbscript.SOURCE. A new zip changes the
# precompiled ready-mades' fingerprint: rebuild them in the same PR (`./tools/precompile-ready-mades.sh
# --store`, PrecompiledFingerprintTest says so).
#
# WHY THIS EXISTS. A real wish does NOT use a CBScript checkout on this machine. CbScriptCompiler
# resolves, in order: the `.cbscript.dir` property, CBSCRIPT_DIR, then
# ToolDeploy.ensureExtracted("cbscript") — a copy unpacked from INSIDE THE JAR, which is the
# self-contained path a child's game actually takes. So a fix landed in the compiler's repo reaches dev
# runs and not him until this runs.
#
# Measured 2026-08-19: the bundled zip still carried mcfunction.py from 3 August, two weeks after
# the fork had moved on, and there was no script to refresh it — it had been done by hand, so it
# was only as current as the last time somebody remembered.
#
# THE BUNDLE NAMES ITS SOURCE. It used to be built from whatever checkout sat on someone's
# disk (a personal checkout), so nobody could say which compiler commit shipped. Now it is
# FETCHED from the public repo at a branch or commit, and `tools/cbscript.SOURCE`, BESIDE the zip (so
# recording it never moves the zip's bytes), records the repo, the branch, the resolved commit and the
# licence, plus the sha256 of the zip it describes (`zip-sha256`, so Gate A can tell, offline, that the
# SOURCE belongs to the zip beside it); CbScriptBundleSourceTest (Gate A) fails without it. A CBSCRIPT_DIR build records that checkout's HEAD and says `local: true`, and
# refuses a checkout with uncommitted changes, which no commit describes.
#
# The zip is DETERMINISTIC (sorted entries, fixed times): the same commit gives the same bytes, so
# re-running at an unchanged commit does not move the precompiled store's fingerprint.
#
# ToolDeploy keys its .version marker on a sha256 of the zip, so a refreshed bundle re-extracts by
# itself on the next launch. Nothing else has to be told.
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
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) echo "refresh-cbscript-bundle: unknown argument $1" >&2; exit 2 ;;
    esac
done
OUT="src/main/resources/tools/cbscript.zip"
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
[ -r "$SRC/compile_once.py" ] || { echo "refresh-cbscript-bundle: no compile_once.py in $SRC" >&2; exit 2; }
LICENSE_ID=""
if head -1 "$SRC/LICENSE" 2>/dev/null | grep -q 'Mozilla Public License Version 2.0'; then
    LICENSE_ID="MPL-2.0"
fi
[ -n "$LICENSE_ID" ] || { echo "refresh-cbscript-bundle: $SRC/LICENSE is not the MPL-2.0 this bundle has always shipped under; check the licence before bundling" >&2; exit 3; }

# THE ENTRY LIST IS DERIVED FROM THE BUNDLE BEING REPLACED, not written down here.
#
# A hand-curated list is a second copy of a fact, and it drifted immediately: mine omitted
# selector_definition (the compiler cannot import without it) and Scripts/ (which holds the .cblib
# libraries every real script imports, alongside the fork author's own demo worlds — so it can be
# neither taken wholesale nor skipped). Reading the current bundle means the list is whatever
# shipped last time, plus anything new named below.
# A pipe into a function runs it in a SUBSHELL, so anything it appends to ENTRIES is discarded and
# the array is still unset afterwards — `set -u` then kills the script. Read from a process
# substitution instead, which keeps the loop in this shell. (macOS ships bash 3.2, so no mapfile.)
ENTRIES=()
while IFS= read -r line; do
    [ -n "$line" ] && ENTRIES+=("$line")
done < <(unzip -l "$OUT" | awk 'NR>3 && NF>=4 {print $4}' | grep -v '/$' \
         | awk -F/ '{print $1}' | sort -u)
[ "${#ENTRIES[@]}" -gt 0 ] || { echo "refresh-cbscript-bundle: could not read $OUT" >&2; exit 3; }

# …plus EVERY module at the fork's root, so a file added to the compiler since the last bundle
# cannot be left out. A new module that nothing lists is a module that never ships, and the symptom
# is an ImportError in a child's game.
#
# DERIVED, not a list to remember. This used to name `nbt_case.py` by hand, which is a second copy
# of a fact and drifted the moment the next module landed: `attribute_ids.py` was written, hooked
# into mcfunction.py, tested and committed to the fork, and would have shipped nothing — the bundle
# would have unpacked cleanly and then failed to import, in a child's game, on his first wish.
# The bundle already carries every other root .py, so "all of them" is what it meant all along.
#
# test_*.py is excluded: tests are not part of the tool, and shipping them would rewrite the zip's
# sha (and re-extract on every launch) whenever a test changes.
while IFS= read -r extra; do
    [ -z "$extra" ] && continue
    case " ${ENTRIES[*]:-} " in *" $extra "*) ;; *) ENTRIES+=("$extra") ;; esac
done < <(ls -1 "$SRC"/*.py 2>/dev/null | xargs -n1 basename | grep -v '^test_')

# …plus the LESSONS. CbScriptLessons reads <cbscriptDir>/CBSCRIPT_LESSONS.md, and cbscript_help
# appends it to the primer — but ToolDeploy prefers this bundle over any checkout, and the list above
# only ever carried code. So every agent on the bundled path was served the primer with NO lessons,
# while the lessons file said it was "auto-loaded into the wish agent". Measured 2026-09-22 (the
# Opus 5.5 eval): the commonest first-draft failures on BOTH models — `scores={x=1..}` in a selector,
# `type=minecraft:cow` — were rules the lessons already had. ToolDeployTest pins that it ships.
case " ${ENTRIES[*]:-} " in *" CBSCRIPT_LESSONS.md "*) ;; *) ENTRIES+=("CBSCRIPT_LESSONS.md") ;; esac


TMP="$WORK/bundle"
mkdir -p "$TMP"
for e in "${ENTRIES[@]}"; do
    if [ -e "$SRC/$e" ]; then
        cp -R "$SRC/$e" "$TMP/"
    elif [ "$e" != "ply" ] && [ "$e" != "parsetab.py" ]; then
        echo "  (skipping absent $e)"
    fi
done

# ply IS VENDORED, and is not in the checkout — it lives in the fork's .venv. The bundle has to
# carry it because the extracted copy runs against whatever python the child's machine has, with no
# virtualenv and no pip. Leaving it out produces a bundle that unpacks cleanly and then cannot parse
# a single script: 154 files down to 102, and the first real wish fails on an import.
# `|| true` is load-bearing under `set -o pipefail`: with no .venv, `ls` exits 2, the
# pipeline inherits it, and the script dies HERE — before the fallback on the next line
# can run. That made the fallback unreachable for every fresh clone and every cloud
# session (no .venv is ever created there), and the symptom was a bare `exit 2` with no
# message at all, which reads as "the script is broken" rather than "no virtualenv".
PLY="$(ls -d "$SRC"/.venv/lib/python*/site-packages/ply 2>/dev/null | head -1 || true)"
if [ -z "$PLY" ]; then
    PLY="$(python3 -c 'import ply,os;print(os.path.dirname(ply.__file__))' 2>/dev/null || true)"
fi
[ -n "$PLY" ] && [ -d "$PLY" ] || { echo "refresh-cbscript-bundle: cannot find ply to vendor" >&2; exit 3; }
cp -R "$PLY" "$TMP/ply"

# Scripts/ comes through the derived list, but only its LIBRARIES may ship — the fork keeps its
# author's demo worlds in there too, and one of them is a Minecraft save.
if [ -d "$TMP/Scripts" ]; then
    find "$TMP/Scripts" -mindepth 1 -maxdepth 1 \
        ! -name '*.cblib' ! -name 'blocks.json' -exec rm -rf {} + 2>/dev/null || true
    LIBS=$(ls -1 "$TMP/Scripts" 2>/dev/null | wc -l | tr -d ' ')
    [ "$LIBS" -gt 0 ] || { echo "refresh-cbscript-bundle: no .cblib libraries survived" >&2; exit 3; }
fi
# __pycache__ is machine-specific and would change the sha on every rebuild, re-extracting for no

# parsetab.py is the parser table ply GENERATES the first time the compiler is imported; the repo does not
# commit it, so a fetched checkout has none. Generate it here, in the bundle, with the vendored ply, so a
# child's first wish does not have to (and cannot fail to) write it. Same ply, same grammar: same table
# (measured against the bundle this replaced: byte for byte).
( cd "$TMP" && PYTHONDONTWRITEBYTECODE=1 python3 -c "import compile_once" >/dev/null 2>&1 ) || true
rm -f "$TMP/parser.out"
[ -s "$TMP/parsetab.py" ] || { echo "refresh-cbscript-bundle: the compiler did not generate parsetab.py" >&2; exit 4; }

# __pycache__ is machine-specific and would change the sha on every rebuild, re-extracting for no
# reason on every launch.
find "$TMP" -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true
find "$TMP" -name '*.pyc' -delete 2>/dev/null || true

# Where the bundle came from, BESIDE the zip so recording it never changes the zip's bytes (the
# precompiled store fingerprints them). CbScriptBundleSourceTest reads these keys. zip-sha256 is the
# sha256 of the zip this SOURCE describes (the rebuilt one, or with --source-only the shipped one): the
# test cannot fetch the repo, but it can check that nobody replaced the zip without this file.
zip_sha256() {
    python3 -I -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$OUT"
}
write_source() {
    {
        echo "repo: $REPO_URL"
        echo "branch: $BRANCH"
        echo "commit: $COMMIT"
        echo "license: $LICENSE_ID"
        echo "zip-sha256: $(zip_sha256)"
        if [ "$LOCAL" = true ]; then
            echo "local: true"
        fi
    } > "$SOURCE_OUT"
}

if [ "$SOURCE_ONLY" = true ]; then
    # Name the commit the SHIPPED zip already is, without rebuilding it: only when every file matches.
    SHIPPED="$WORK/shipped"
    mkdir -p "$SHIPPED"
    unzip -q "$OUT" -d "$SHIPPED"
    if ! diff -r "$SHIPPED" "$TMP" >"$WORK/diff" 2>&1; then
        echo "refresh-cbscript-bundle: the shipped $OUT is NOT $REPO_URL @ $COMMIT; differences:" >&2
        head -20 "$WORK/diff" >&2
        echo "  (re-bundle without --source-only to ship that commit, or pick the --ref it was built from)" >&2
        exit 5
    fi
    write_source
    echo "the shipped $OUT is $REPO_URL @ $BRANCH ($COMMIT), file for file; wrote $SOURCE_OUT"
    exit 0
fi

# Deterministic: fixed times, sorted entries, no extra attributes.
find "$TMP" -exec touch -h -t 198001010000 {} +
rm -f "$OUT"
( cd "$TMP" && find . -mindepth 1 | sed 's#^\./##' | LC_ALL=C sort | zip -qX -@ "$OLDPWD/$OUT" )
write_source

echo "refreshed $OUT from $REPO_URL @ $BRANCH ($COMMIT)$([ "$LOCAL" = true ] && echo ", the local checkout $SRC"); wrote $SOURCE_OUT"
unzip -l "$OUT" | tail -2
# PROVE THE BUNDLE CAN RUN. A bundle that unpacks and then cannot import is the failure this
# script exists to prevent, and it is invisible until a child makes a wish.
#
# An IMPORT check, not a compile of some sample script. The first version compiled a hand-written
# three-liner and the KNOWN-GOOD bundle failed it — a syntax error in my probe, which would have
# condemned a good bundle. The second used a real script from this repo and that failed on the good
# bundle too, because those spike scripts are fragments rather than standalone programs. Importing
# is the thing that actually distinguishes a working bundle from a broken one: it catches a missing
# ply, a missing library, and any syntax error in the compiler itself, and it cannot be wrong about
# a script it never reads.
CHECK="$(mktemp -d)" || exit 1
unzip -q "$OUT" -d "$CHECK"
if ( cd "$CHECK" && python3 -c "import compile_once" >/dev/null 2>&1 ); then
    echo "  ✓ the bundled compiler imports cleanly (deps vendored, no syntax errors)"
else
    echo "  ✗ THE BUNDLED COMPILER CANNOT IMPORT — do not ship this:" >&2
    ( cd "$CHECK" && python3 -c "import compile_once" 2>&1 | tail -4 ) >&2
    rm -rf "$CHECK"; exit 4
fi
rm -rf "$CHECK"
echo "verify a fix is really in there, e.g.:  unzip -p $OUT attribute_ids.py | head -3"
