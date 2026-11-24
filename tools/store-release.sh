#!/usr/bin/env bash
# THE STORE JAR: build the jar a maintainer uploads to CurseForge / Modrinth, from the PUBLIC tree only, and check it.
#
#   ./tools/store-release.sh 1.0.0+mc1.21.8 [OUTDIR]
#   ./tools/store-release.sh --check-mixins JAR     # only the mixin checks, on a jar already built
#
# Where it builds: this checkout (the release line, neoforge-1.21.8), which must be clean -- a store jar is a commit,
# never a working directory.
#
# What goes in, beyond the public build: the Apprentice's sentence model (D13: model.onnx + vocab.txt, ~90 MB,
# sha-pinned by tools/fetch-apprentice-model.sh, which fills $MODDY_APPRENTICE_MODEL_CACHE once) at apprentice/model/,
# and the licence files at META-INF/. ONNX Runtime and its natives are jarJar'd by every build.
#
# What it checks on the FINISHED jar (and refuses the jar on any miss):
#   the leak scan over the tree; the jar's own NEVER_SHIP and
#   dangling-reference checks (the jar task); no plug-in listed in META-INF/services; the mixin checks (no private
#   mixin config, class or name in neoforge.mods.toml, no GameTest-only config or harness, no unlisted mixin class); the
#   licence files and the model's licence present; the model present with its pinned hashes; ONNX Runtime nested with
#   each of its four native sets (linux-x64, linux-aarch64, osx-aarch64, win-x64) and no *.dSYM/ debug bundle;
#   neoforge.mods.toml carrying the version asked for.
# Then it prints the jar's path, size and sha256, and writes them beside the jar (<jar>.sha256, store-release.json).
#
# Exit codes: 0 a checked jar; 1 a check or the build failed; 2 usage, a missing tool, or a dirty checkout.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-}"
OUT="${2:-$ROOT/build/store-release}"
say() { printf 'store-release: %s\n' "$*"; }
fail() { say "FAIL — $*"; exit 1; }

# The mixin checks, on a finished jar. Refuses: the private mixin config or neoforge.mods.toml naming
# it; the GameTest-only config (*.gametest.mixins.json) or the test harness (com/moddingfromamod/testing/); a mixin
# class no config the jar registers lists (a private mixin left behind, whatever its name); and, where this repo's
# private config is at hand, any class it lists.
mixin_checks() {
    local jar="$1" priv="$ROOT/house/src/main/resources/moddingfromamod.private.mixins.json" why
    [ -f "$priv" ] || priv=""
    why="$(python3 -I - "$jar" "$priv" <<'PY'
import json, re, sys, zipfile
jar, priv = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(jar)
names = set(z.namelist())
bad = []
if "moddingfromamod.private.mixins.json" in names:
    bad.append("the jar carries the private mixin config")
toml = z.read("META-INF/neoforge.mods.toml").decode()
configs = re.findall(r'^config *= *"([^"]+)"', toml, re.M)
bad += ["neoforge.mods.toml names the mixin config %s" % c for c in configs if "private" in c or "gametest" in c]
bad += ["the jar carries the GameTest-only mixin config %s" % n for n in names if n.endswith(".gametest.mixins.json")]
if any(n.startswith("com/moddingfromamod/testing/") for n in names):
    bad.append("the jar carries the test harness (com/moddingfromamod/testing/), the GameTest mixins among it")
listed = set()
for c in configs:
    if c not in names:
        bad.append("neoforge.mods.toml names %s, which the jar does not carry" % c)
        continue
    j = json.loads(z.read(c))
    pkg = j["package"].replace(".", "/")
    for lst in ("mixins", "client", "server"):
        listed |= {pkg + "/" + m.replace(".", "/") + ".class" for m in j.get(lst, [])}
orphans = sorted(n for n in names if re.fullmatch(r"com/moddingfromamod/(house/)?mixin/[^/$]+\.class", n) and n not in listed)
if orphans:
    bad.append("mixin classes no registered config lists (a private mixin left in the jar?): " + ", ".join(orphans))
if priv:
    j = json.load(open(priv))
    pkg = j["package"].replace(".", "/")
    here = sorted(m for lst in ("mixins", "client", "server") for m in j.get(lst, [])
                  if pkg + "/" + m.replace(".", "/") + ".class" in names)
    if here:
        bad.append("the jar carries private mixins: " + ", ".join(here))
print("; ".join(bad))
PY
)" || { say "FAIL — could not read the jar's mixins ($jar)"; return 1; }
    [ -z "$why" ] || { say "FAIL — $why"; return 1; }
    say "mixins clean: $(unzip -p "$jar" META-INF/neoforge.mods.toml | grep -cE '^config *=') config(s) registered, no private or test-only mixin"
}

case "$VERSION" in
    -h|--help) sed -n '2,27p' "$0"; exit 0 ;;
    "") sed -n '2,27p' "$0" >&2; exit 2 ;;
    # Only the mixin checks, on a jar already built: its self-check.
    --check-mixins) [ -f "${2:-}" ] || { echo "store-release: --check-mixins JAR" >&2; exit 2; }; mixin_checks "$2"; exit $? ;;
esac
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+\+mc[0-9][0-9.]*$ ]] || { echo "store-release: version must look like 1.0.0+mc1.21.8 (D25), got $VERSION" >&2; exit 2; }
for t in python3 unzip sha256sum; do command -v "$t" >/dev/null || { echo "store-release: needs $t" >&2; exit 2; }; done
MODEL_CACHE="${MODDY_APPRENTICE_MODEL_CACHE:-$HOME/.cache/moddy/apprentice-model}"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"

TREE="$ROOT"
if [ -d "$ROOT/.git" ] && [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=normal)" ]; then
    echo "store-release: REFUSED: this checkout has uncommitted changes; a store jar is built from a commit" >&2
    exit 2
fi
FARGS=()
SOURCE="checkout $(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null) at $(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null)"

python3 "$ROOT/tools/leak-scan.py" "${FARGS[@]}" --summary --dir "$TREE" >"$OUT/leak-scan.log" 2>&1 \
    || { cat "$OUT/leak-scan.log"; fail "the tree carries private work (tools/leak-scan.py)"; }
say "leak scan clean ($(tail -1 "$OUT/leak-scan.log" | sed 's/^leak-scan: //'))"

bash "$ROOT/tools/fetch-apprentice-model.sh" "$MODEL_CACHE" || fail "could not get the sentence model (tools/fetch-apprentice-model.sh)"

cd "$TREE" || exit 2
LOG="$OUT/build.log"
say "building the jar (version $VERSION) in $TREE"
if ! ./gradlew --console=plain ${GRADLE_ARGS:-} jar -Pmod_version="$VERSION" -PbundleApprenticeModel="$MODEL_CACHE" >"$LOG" 2>&1; then
    grep -E "error:|What went wrong|must never ship|shipped classes" -A3 "$LOG" | head -40
    fail "the build failed; full log: $LOG"
fi
JAR="$TREE/build/libs/moddingfromamod-$VERSION.jar"
[ -f "$JAR" ] || fail "no jar at $JAR"

# ── checks on the finished jar ──
LIST="$(unzip -Z1 "$JAR")"
has() { grep -qxF "$1" <<<"$LIST"; }
for f in META-INF/LICENSE META-INF/NOTICE.md META-INF/THIRD_PARTY_NOTICES.md META-INF/LICENSE-ASSETS.md \
         apprentice/sentence-model.NOTICE.txt apprentice/sentence-model.LICENSE.txt apprentice/onnxruntime.NOTICE.txt \
         apprentice/english-words.NOTICE.txt licenses/twelvemonkeys.LICENSE.txt licenses/cbscript.NOTICE.txt \
         META-INF/TRADEMARKS.md apprentice/model/model.onnx apprentice/model/vocab.txt apprentice/layer.json; do
    has "$f" || fail "the jar has no $f"
done
grep -q "Mozilla Public License" <(unzip -p "$JAR" META-INF/LICENSE) || fail "META-INF/LICENSE is not the MPL-2.0 text"
grep -q "Apache License" <(unzip -p "$JAR" apprentice/sentence-model.LICENSE.txt) || fail "the model's licence is not the Apache-2.0 text"
want_model=$(grep -oE 'MODEL_SHA256=[0-9a-f]+' "$ROOT/tools/fetch-apprentice-model.sh" | cut -d= -f2)
want_vocab=$(grep -oE 'VOCAB_SHA256=[0-9a-f]+' "$ROOT/tools/fetch-apprentice-model.sh" | cut -d= -f2)
[ "$(unzip -p "$JAR" apprentice/model/model.onnx | sha256sum | cut -d' ' -f1)" = "$want_model" ] || fail "the jar's model.onnx is not the pinned file"
[ "$(unzip -p "$JAR" apprentice/model/vocab.txt | sha256sum | cut -d' ' -f1)" = "$want_vocab" ] || fail "the jar's vocab.txt is not the pinned file"
unzip -p "$JAR" apprentice/layer.json | grep -q "$want_model" || fail "the jar's layer.json was trained on a different model"
if grep -q '^META-INF/services/' <<<"$LIST"; then
    plugins="$(for s in $(grep '^META-INF/services/' <<<"$LIST"); do unzip -p "$JAR" "$s" | sed 's/#.*//' | grep -v '^[[:space:]]*$'; done)"
    [ -z "$plugins" ] || fail "the jar lists services (a public jar ships no plug-in): $(tr '\n' ' ' <<<"$plugins")"
fi
mixin_checks "$JAR" || exit 1
unzip -p "$JAR" META-INF/neoforge.mods.toml | grep -qE "^version *= *\"$(sed 's/[.+]/\\&/g' <<<"$VERSION")\"" \
    || fail "neoforge.mods.toml does not carry version $VERSION"
ORT="$(grep -E '^META-INF/jarjar/onnxruntime-[0-9.]+\.jar$' <<<"$LIST" | head -1)"
[ -n "$ORT" ] || fail "ONNX Runtime is not nested in the jar"
ORTLIST="$(unzip -p "$JAR" "$ORT" > "$OUT/.ort.jar" && unzip -Z1 "$OUT/.ort.jar"; rm -f "$OUT/.ort.jar")"
for os in win osx linux; do grep -qE "^ai/onnxruntime/native/${os}-" <<<"$ORTLIST" || fail "ONNX Runtime has no $os natives"; done
# every native set keeps its libraries, and no native debug bundle (*.dSYM/) rides along
for lib in linux-x64/libonnxruntime.so linux-aarch64/libonnxruntime.so osx-aarch64/libonnxruntime.dylib win-x64/onnxruntime.dll; do
    grep -qxF "ai/onnxruntime/native/$lib" <<<"$ORTLIST" || fail "ONNX Runtime has no $lib"
done
grep -q '\.dSYM/' <<<"$ORTLIST" && fail "ONNX Runtime still carries native debug bundles (*.dSYM/)"
NATIVES="$(grep -oE '^ai/onnxruntime/native/[a-z]+-[a-z0-9_]+/' <<<"$ORTLIST" | sort -u | sed 's#ai/onnxruntime/native/##; s#/$##' | tr '\n' ' ')"

NAME="moddythewishwatch-$VERSION.jar"
cp "$JAR" "$OUT/$NAME"
SIZE=$(stat -c %s "$OUT/$NAME")
SHA=$(sha256sum "$OUT/$NAME" | cut -d' ' -f1)
echo "$SHA  $NAME" >"$OUT/$NAME.sha256"
MB=$(python3 -c "print(f'{$SIZE/1048576:.1f}')")
python3 - "$OUT/store-release.json" <<EOF
import json, sys
json.dump({"version": "$VERSION", "jar": "$NAME", "bytes": $SIZE, "mib": $MB, "sha256": "$SHA",
           "source": "$SOURCE", "onnxruntime": "${ORT##*/}", "natives": "$NATIVES".split(),
           "limits": {"modrinth_mib": 500, "curseforge": "unconfirmed (general cap 2 GB)"}},
          open(sys.argv[1], "w"), indent=2)
EOF
say "PASS — $OUT/$NAME"
say "  ${MB} MiB ($SIZE bytes), sha256 $SHA"
say "  from the $SOURCE; ONNX Runtime ${ORT##*/} natives: $NATIVES"
exit 0
