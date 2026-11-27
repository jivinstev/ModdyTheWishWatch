#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# client-test.sh — run an automated REAL-CLIENT test (Gate C), unattended.
#
# Launches a dev client (`./gradlew runClient -Pboottest -Ptestmode=<mode>`) that drives itself through a
# scenario (a class in src/main/java/com/moddingfromamod/gatec/ that reads `moddingfromamod.testmode`),
# writes PASS/FAIL to a signal dir, and quits. Prints the verdict and exits with an ACCURATE code:
#
#     0 = PASS        1 = FAIL        2 = setup error        3 = no result / timeout
#
# WHY a client and not a headless GameTest: some bugs only exist on a real client (rendering, sounds,
# GUIs) or only in SINGLEPLAYER, where the client and integrated server share one process.
#
# Usage (from the repo root):
#     ./tools/client-test.sh <mode>                      # e.g. vanillawalk
#     TESTPROPS="a=1,b=2" ./tools/client-test.sh <mode>  # extra knobs -> -Dmoddingfromamod.a=1, ...
#     SMOKEJARS="/abs/other-mod.jar" ./tools/client-test.sh <mode>   # boot another mod too
#     GRADLE_ARGS=--offline ./tools/client-test.sh <mode>            # extra Gradle flags
#
# PARALLEL RUNS: each gets its own SLOT (defaults to the mode), which gives it its own signal directory
# ($SIG, default /tmp/moddingfromamod-<slot>) and its own client working directory (run/client-<slot>).
# Two runs of the SAME mode need SLOT=a / SLOT=b. TIMEOUT (seconds, default 900) kills the whole run.
#
# On LINUX with no DISPLAY (CI, a cloud VM) the client runs under Xvfb with Mesa's llvmpipe software
# rasteriser (OpenGL 4.5 core; Minecraft needs 3.2), and on 26.x through Mesa's EGL (SDL needs it, below).
# Install: apt-get install -y xvfb libgl1-mesa-dri libegl1 libegl-mesa0.
# Anywhere else it uses the display you have. Never touches your real game install.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 2

MODE="${1:-${TESTMODE:-}}"
[ -z "$MODE" ] && { echo "client-test: name a testmode, e.g. ./tools/client-test.sh vanillawalk" >&2; exit 2; }
MOD_ID="$(sed -n 's/^mod_id=//p' gradle.properties | head -1)"
[ -z "$MOD_ID" ] && { echo "client-test: could not read mod_id from gradle.properties" >&2; exit 2; }
SLOT="${SLOT:-$MODE}"
SIG="${SIG:-/tmp/${MOD_ID}-${SLOT}}"
RUNDIR="${RUNDIR:-client-${SLOT}}"
TIMEOUT="${TIMEOUT:-900}"

# ⚠ QUOTE ANY VALUE CONTAINING SPACES: TESTPROPS="a=b,c=d e". Unquoted, the shell reads the rest as a
# command and TESTPROPS ends up unset, so the run silently uses every default and still passes or fails
# plausibly.
EXTRA=()
[ -n "${TESTPROPS:-}" ] && EXTRA+=("-Ptestprops=$TESTPROPS")
[ -n "${SMOKEJARS:-}" ] && EXTRA+=("-Psmokejars=$SMOKEJARS")
# Both names are accepted: an unset variable expands to nothing, so a flag passed under the other name
# would be dropped in silence.
# shellcheck disable=SC2206
[ -n "${GRADLE_EXTRA:-}" ] && EXTRA+=(${GRADLE_EXTRA})
# shellcheck disable=SC2206
[ -n "${GRADLE_ARGS:-}" ] && EXTRA+=(${GRADLE_ARGS})

rm -rf "$SIG"; mkdir -p "$SIG"
echo "client-test: mod=$MOD_ID mode=$MODE"
echo "client-test: log -> $SIG/run.log (timeout ${TIMEOUT}s)"

# ── HEADLESS LINUX: run the client under a virtual X server ──────────────────────────────────
# Gated on BOTH uname=Linux AND an empty DISPLAY: a desktop keeps its own X server and its real GPU, and
# a caller that exports a DISPLAY (tools/record-client.sh does, to film it) opts out.
XVFB=()
if [ "$(uname)" = "Linux" ] && [ -z "${DISPLAY:-}" ]; then
    if command -v xvfb-run >/dev/null 2>&1; then
        export LIBGL_ALWAYS_SOFTWARE=1    # without it Mesa may try a DRI device that does not exist
        # 26.x opens its window with SDL3 (FML's early display, then the game), which asks for an sRGB-capable
        # framebuffer; Xvfb's software GLX offers no such visual, so the client dies at once with "Couldn't find
        # matching GLX visual". Through EGL on the same X display it gets one. GLFW (1.21.x) never reads this, so it
        # is set for every version: one script, every version branch (docs/RELEASING.md, "Gate C on every version").
        export SDL_VIDEO_FORCE_EGL="${SDL_VIDEO_FORCE_EGL:-1}"
        if ! { ldconfig -p 2>/dev/null | grep -q 'libEGL_mesa\.so'; }; then
            # A warning, not an exit: a 1.21.x client (GLFW) never needs EGL. A 26.x client without it cannot open
            # its window, and the verdict below names the cause.
            echo "client-test: WARNING — Mesa's EGL is not installed; a 26.x client cannot open its window without it." >&2
            echo "client-test:   apt-get install -y libegl1 libegl-mesa0" >&2
        fi
        XVFB=(xvfb-run -a -s "-screen 0 ${XVFB_SCREEN:-1280x720x24}")
        echo "client-test: headless Linux — client runs under Xvfb (llvmpipe software GL)"
    else
        # LOUD, not a skip: a Gate C that quietly did not run reads as a pass.
        echo "client-test: headless Linux (no DISPLAY) and xvfb-run is not installed." >&2
        echo "client-test:   apt-get update && apt-get install -y xvfb libgl1-mesa-dri libegl1 libegl-mesa0" >&2
        exit 2
    fi
fi
# No sound card in a container: OpenAL's null driver keeps the client from failing to open a device.
export ALSOFT_DRIVERS="${ALSOFT_DRIVERS:-null}"

# Keep a macOS display awake for the whole run (a slept display fails GLFW's monitor lookup).
CAFFEINATE=""; command -v caffeinate >/dev/null 2>&1 && CAFFEINATE="caffeinate -dimsu"

# --max-workers=2: a --no-daemon run re-resolves its dependencies, and a parallel fetch against a cold
# cache can be rate-limited (HTTP 429) by Maven Central, which then surfaces as an unrelated NeoGradle error.
$CAFFEINATE ${XVFB[@]+"${XVFB[@]}"} ./gradlew runClient \
    -Pboottest "-Ptestmode=$MODE" "-Psigdir=$SIG" "-Prundir=$RUNDIR" \
    ${EXTRA[@]+"${EXTRA[@]}"} --no-daemon --max-workers=2 --console=plain > "$SIG/run.log" 2>&1 &
GRADLE_PID=$!
# ON TIMEOUT, KILL THE WHOLE TREE (the wrapper, Xvfb, Gradle and the client JVM), collected BEFORE
# anything is killed, because an orphan is reparented to init and pgrep -P can no longer find it.
_ct_tree() {
    echo "$1"
    for _c in $(pgrep -P "$1" 2>/dev/null); do _ct_tree "$_c"; done
}
_ct_kill_tree() {
    local pids
    pids="$(_ct_tree "$1")"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null
    sleep "${CT_KILL_GRACE:-10}"
    # shellcheck disable=SC2086
    kill -9 $pids 2>/dev/null
    return 0
}
( sleep "$TIMEOUT"; touch "$SIG/timeout"; _ct_kill_tree "$GRADLE_PID" ) &
WATCHDOG=$!
disown "$WATCHDOG" 2>/dev/null || true
wait "$GRADLE_PID" 2>/dev/null
kill "$WATCHDOG" 2>/dev/null
touch "$SIG/done"

echo "──────────────── client-test RESULT ────────────────"
RC=3
if [ -f "$SIG/result" ]; then
    cat "$SIG/result"
    grep -q '^PASS' "$SIG/result" && RC=0 || RC=1
    # NORUN: the film could not run (its stage never settled, ClientHarness.couldNotRun) -- a rig fault, never judged
    grep -q '^NORUN' "$SIG/result" && RC=2
else
    echo "NO RESULT — the client exited before reporting (crash during startup?)"
    grep -iE 'primary monitor|GLFW|GLX visual|Exception in thread|Caused by:|FATAL|Mixin apply failed' "$SIG/run.log" 2>/dev/null | head -5
    grep -q "Couldn't find matching GLX visual" "$SIG/run.log" 2>/dev/null \
        && echo "(an SDL client under Xvfb without EGL: SDL_VIDEO_FORCE_EGL=1 and apt-get install -y libegl1 libegl-mesa0)"
fi
[ -f "$SIG/timeout" ] && { echo "TIMEOUT after ${TIMEOUT}s (client never finished)"; RC=3; }
echo "log: $SIG/run.log"
echo "────────────────────────────────────────────────────"
exit $RC
