#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# precompile-ready-mades.sh — rebuild the precompiled ready-made packs.
#
#   ./tools/precompile-ready-mades.sh            record + compile (after a template, catalogue or tool change)
#   ./tools/precompile-ready-mades.sh --store    recompile the skeletons the store holds (a compiler-only change)
#
# WHY: a Builder or Apprentice power is a catalogue template filled with the player's values and compiled by the
# bundled CBScript compiler, which is Python. Most players have no Python, so every power the Builder offers is
# compiled HERE, at build time, with sentinels where the player's values go, and the mod puts the values back in
# Java (datapack/PrecompiledPacks, datapack/ReadyMadeSkeleton). Then nobody needs Python except for the Magician's
# own free-form powers.
#
# WHEN: PrecompiledFingerprintTest (Gate A) failed — a template (effects/*.cbs, items/*.cbs), a catalogue or the
# bundled compiler (tools/cbscript.zip) changed — or ReadyMadePrecompileGameTest (Gate B) could not make a Builder
# power without Python (a tool's rendering changed: the give line, a derived parameter). Commit what it writes:
# src/main/resources/precompiled/{ready-mades.json.gz,manifest.json}.
# (The bundled compiler itself is rebuilt by tools/refresh-cbscript-bundle.sh; run --store after it.)
#
# WHAT: 1. ReadyMadePrecompileGameTest with precompiled.record=DIR and Python ON: every Builder power is made through
#          the real Builder and every skeleton it asks for is written to DIR (a few minutes);
#       2. ./gradlew precompileReadyMades -Pskeletons=DIR: each skeleton through the real compiler, into the store;
#       3. ReadyMadePrecompileGameTest twice: with precompiled=verify (Python ON, every pack built both ways and
#          compared file for file), then Python OFF (every power must be made from the store alone).
#
# NEEDS: python3 with ply (the compiler), and nothing else running a Gradle build in this checkout.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "${1:-}" = "--store" ]; then
    ./gradlew precompileReadyMades -Pskeletons=store -q
    exit 0
fi

DIR="$(mktemp -d "${TMPDIR:-/tmp}/ready-made-skeletons.XXXXXX")" || exit 1
LOG1="$DIR.record.log"
LOG2="$DIR.check.log"
echo "precompile-ready-mades: 1/3 recording every Builder power's skeleton into $DIR (log $LOG1)"
# The coverage test, and every Builder and Apprentice GameTest: whatever ready-made one of them makes is kept too.
TESTS="ReadyMadePrecompileGameTest,$(ls src/main/java/com/moddingfromamod/wishbuilder/Builder*GameTest.java \
    src/main/java/com/moddingfromamod/*/Apprentice*GameTest.java | xargs -n1 basename | sed 's/\.java$//' | paste -sd, -)"
./gradlew runGameTestServer -Pgametests="$TESTS" -Ptestprops="precompiled.record=$DIR" >"$LOG1" 2>&1 || true
N="$(find "$DIR" -name '*.cbscript' | wc -l | tr -d ' ')"
if [ "$N" = "0" ]; then
    echo "precompile-ready-mades: no skeleton was recorded; see $LOG1" >&2
    exit 1
fi
echo "precompile-ready-mades: 2/3 compiling $N skeleton(s)"
./gradlew precompileReadyMades -Pskeletons="$DIR" -q
check() {   # $1 = log, $2 = extra testprops (may be empty), $3 = what it checks
    ./gradlew runGameTestServer -Pgametests=ReadyMadePrecompileGameTest ${2:+-Ptestprops="$2"} >"$1" 2>&1 || true
    if grep -Eq '[0-9]+ required tests? passed' "$1" && ! grep -Eq 'required tests? failed' "$1"; then
        echo "precompile-ready-mades: $3: OK"
    else
        echo "precompile-ready-mades: $3: FAILED; see $1" >&2
        grep -E 'Builder powers could not be made|not the compiler' "$1" | head -3 | cut -c1-600 >&2 || true
        exit 1
    fi
}
echo "precompile-ready-mades: 3/3 checking (logs $LOG2.*)"
check "$LOG2.verify" "precompiled=verify" "every Builder power's precompiled pack is the compiler's pack"
check "$LOG2.nopython" "" "every Builder power is made with no Python"
echo "precompile-ready-mades: done. Commit src/main/resources/precompiled/."
