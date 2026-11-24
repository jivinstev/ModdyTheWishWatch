#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# record-client.sh — FILM a client test, instead of photographing it.
#
#     ./tools/record-client.sh vanillawalk                      # film the walk take
#     FILM_OUT=/tmp/walk.mp4 ./tools/record-client.sh vanillawalk
#
# It runs tools/client-test.sh under its own Xvfb display and records that display with ffmpeg. A film
# that marks a window ($SIG/film-start.epoch, film-stop.epoch: the rig films do) has it cut out into
# $FILM_OUT; the whole session recording is always written. Needs ffmpeg and Xvfb.
#
# ⚠ In a container whose Gradle daemon cannot fetch Minecraft's version manifest, pass GRADLE_ARGS=--offline:
# otherwise the run dies at configuration with "(The run type 'client' was not found)", reported here as
# "NO RESULT", which reads as a broken recorder and is not one.
#
# ⚠ THIS DOES NOT GO THROUGH THE GAME. It grabs the X server the client is drawing into, so it records what
# the screen showed, at the framerate the screen showed it. A photograph cannot answer a question about
# MOVEMENT, and a rig film is about movement.
#
# HOW THE CLOCKS LINE UP, which is the only hard part.
#
# The recorder is a separate process from the client and neither can see the other, so
# "start filming when the creature spawns" cannot be a function call. Instead:
#
#   1. we record the WHOLE session, continuously, from before the client boots;
#   2. the client test writes $SIG/film-start.epoch (and film-stop.epoch) at the moment
#      it begins its hold — plain milliseconds since the epoch;
#   3. afterwards we subtract our own capture-start epoch and cut that window out.
#
# Recording continuously and cutting later, rather than starting ffmpeg on the marker, is
# deliberate: ffmpeg takes an unpredictable half-second to open the display and start
# capturing, and a fire breath is 1.5 seconds long. Starting on the marker would clip the
# head of the very event being filmed, and it would do it INVISIBLY — the film would come
# back looking like a creature that simply did not breathe.
#
# Exit code is the CLIENT TEST'S, not ffmpeg's: a film of a failed run is still a failed run.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

MODE="${1:-vanillawalk}"
MOD_ID="$(sed -n 's/^mod_id=//p' gradle.properties | head -1)"
SLOT="${SLOT:-$MODE}"
SIG="${SIG:-/tmp/${MOD_ID}-${SLOT}}"
FPS="${FPS:-30}"
SCREEN="${XVFB_SCREEN:-1280x720x24}"
GEOM="${SCREEN%x*}"                      # 1280x720x24 -> 1280x720

# ⚠ THE RECORDINGS MUST NOT LIVE IN $SIG. client-test.sh opens with `rm -rf "$SIG"` to clear the
# previous run's evidence, and it does that AFTER this script has already started ffmpeg — so a
# session file written there is unlinked out from under the encoder. It does not fail: ffmpeg holds
# the file descriptor and keeps writing happily to a deleted inode, `ls` shows an empty directory,
# and the run completes and reports its verdict as normal. Measured the first time this was run —
# a whole wish was granted, and filmed, and the recording existed only as
# `/proc/<pid>/fd/4 -> /tmp/moddingfromamod-film/session.mp4 (deleted)`, which cannot be recovered
# because an mp4's moov atom is written on close and the close had nowhere to go.
FILM_DIR="${FILM_DIR:-/tmp/${MOD_ID}-${SLOT}-recordings}"
FILM_OUT="${FILM_OUT:-$FILM_DIR/film.mp4}"
RAW="$FILM_DIR/session.mp4"

command -v ffmpeg >/dev/null 2>&1 || {
    echo "record-client: ffmpeg is not installed." >&2
    echo "record-client:   apt-get update && apt-get install -y ffmpeg" >&2
    exit 2
}
command -v Xvfb >/dev/null 2>&1 || {
    echo "record-client: Xvfb is not installed." >&2
    echo "record-client:   apt-get update && apt-get install -y xvfb libgl1-mesa-dri" >&2
    exit 2
}

# ── OUR OWN DISPLAY, on a number we choose ───────────────────────────────────────────────
#
# client-test.sh wraps itself in `xvfb-run -a` when DISPLAY is empty, and `-a` means "pick
# any free display number" — which nothing else can then discover. A recorder has to name
# the display it is grabbing, so we take one ourselves and EXPORT it; client-test.sh's own
# branch is gated on an EMPTY DISPLAY, so exporting one is precisely how a caller opts out
# of it without editing that file.
pick_display() {
    local n
    for n in $(seq 99 130); do
        [ -e "/tmp/.X${n}-lock" ] && continue
        echo "$n"
        return 0
    done
    return 1
}
DISPLAY_NUM="$(pick_display)" || { echo "record-client: no free X display" >&2; exit 2; }

Xvfb ":${DISPLAY_NUM}" -screen 0 "$SCREEN" -nolisten tcp >"/tmp/xvfb-${DISPLAY_NUM}.log" 2>&1 &
XVFB_PID=$!
export DISPLAY=":${DISPLAY_NUM}"
export LIBGL_ALWAYS_SOFTWARE=1           # client-test.sh only sets this inside its own branch

FFMPEG_PID=""
cleanup() {
    [ -n "$FFMPEG_PID" ] && kill -INT "$FFMPEG_PID" 2>/dev/null
    [ -n "$FFMPEG_PID" ] && wait "$FFMPEG_PID" 2>/dev/null
    kill "$XVFB_PID" 2>/dev/null
}
trap cleanup EXIT INT TERM

# Wait for the X server to actually accept connections. Racing it makes ffmpeg exit
# immediately with "Cannot open display", which then looks like a recorder bug.
for _ in $(seq 1 50); do
    [ -e "/tmp/.X${DISPLAY_NUM}-lock" ] && break
    sleep 0.1
done
sleep 0.5
echo "record-client: display $DISPLAY (${GEOM}) — Xvfb pid $XVFB_PID"

mkdir -p "$SIG" "$FILM_DIR"
rm -f "$RAW" "$FILM_OUT" "$SIG/film-windows.tsv" "$FILM_DIR"/film-[0-9]*.mp4

# ultrafast + yuv420p: the encoder shares 4 vCPUs with a software-rendered Minecraft, so
# encoding cost comes straight out of the framerate being filmed. yuv420p because anything
# else will not play in a browser, which is where this is going.
ffmpeg -loglevel warning -y \
    -f x11grab -framerate "$FPS" -video_size "$GEOM" -i "$DISPLAY" \
    -c:v libx264 -preset ultrafast -crf 26 -pix_fmt yuv420p -flush_packets 1 \
    "$RAW" >"$FILM_DIR/ffmpeg.log" 2>&1 &
FFMPEG_PID=$!

# CAPTURE STARTS WHEN THE FILE STARTS GROWING, not when the process starts. The gap is
# ffmpeg opening the display and negotiating the encoder, and it is the whole reason the
# window is cut afterwards rather than started on demand.
# ⚠ 40s, NOT 10. Nothing is on screen yet when ffmpeg attaches — the client has not drawn a
# frame — so x264 has a static black grab to chew on and buffers before it writes anything. On a
# loaded 4-vCPU box that took longer than the old 10s ceiling and the run died with "ffmpeg never
# started capturing", which reads as a broken recorder rather than a slow first frame.
# -flush_packets above makes the first bytes appear promptly; this makes the wait long enough to
# see them either way.
CAP_T0=""
for _ in $(seq 1 400); do
    if [ -s "$RAW" ]; then
        CAP_T0="$(date +%s%3N)"
        break
    fi
    kill -0 "$FFMPEG_PID" 2>/dev/null || break
    sleep 0.1
done
if [ -z "$CAP_T0" ]; then
    echo "record-client: ffmpeg never started capturing — see $FILM_DIR/ffmpeg.log" >&2
    sed -n '1,20p' "$FILM_DIR/ffmpeg.log" >&2
    exit 2
fi
echo "$CAP_T0" > "$FILM_DIR/capture-start.epoch"
echo "record-client: capturing $DISPLAY at ${FPS}fps -> $RAW"

# ── the run itself ───────────────────────────────────────────────────────────────────────
SLOT="$SLOT" SIG="$SIG" ./tools/client-test.sh "$MODE"
RC=$?

# Finalise the mp4: SIGINT makes ffmpeg write the moov atom. Killing it outright leaves an
# unplayable file, which is a miserable way to lose a 6-minute run.
kill -INT "$FFMPEG_PID" 2>/dev/null
for _ in $(seq 1 100); do
    kill -0 "$FFMPEG_PID" 2>/dev/null || break
    sleep 0.1
done
FFMPEG_PID=""

if [ ! -s "$RAW" ]; then
    echo "record-client: no session recording was produced" >&2
    exit "${RC:-3}"
fi
echo "record-client: session recording $(du -h "$RAW" | cut -f1) -> $RAW"

# ── cut every marked window out ──────────────────────────────────────────────────────────
#
# One line per film: startMs, stopMs, label. APPENDED by the rig, because the agent may
# photograph several times in one wish and which take is the good one is not knowable while
# it is being shot. Cutting all of them and choosing afterwards costs a few seconds of
# encoding; guessing costs the run.
WINDOWS="$SIG/film-windows.tsv"

# CROP THE LETTERBOX. The client opens its own window at Minecraft's default 854x480 and there is
# no window manager to maximise it, so it sits in the middle of the Xvfb screen with black bars
# all round — about 55% of every recorded pixel is border. Detected rather than hardcoded, since
# the window size is the client's choice and not ours; a failed detection just means no crop.
CROP=""
if [ -s "$RAW" ] && [ -s "$WINDOWS" ]; then
    # ⚠ SAMPLE INSIDE A FILM WINDOW, and do NOT silence ffmpeg. cropdetect reports at INFO level,
    # so `-v error` swallows the very line being grepped for and the crop comes back empty — which
    # then fails as "No filters specified in the graph description", a message about filter syntax
    # rather than about verbosity. And a fixed early timestamp lands on the Gradle/loading screen,
    # which is uniformly black: cropdetect on black is meaningless. So take the first window's
    # start as the sample point.
    _sample="$(awk -v t="$CAP_T0" -F'\t' 'NR==1{printf "%.0f", ($1-t)/1000 + 3}' "$WINDOWS" 2>/dev/null)"
    CROP="$(ffmpeg -hide_banner -ss "${_sample:-30}" -i "$RAW" -frames:v 20 \
            -vf cropdetect=24:2:0 -f null - 2>&1 | grep -o 'crop=[0-9:]*' | tail -1)"
    [ -n "$CROP" ] && echo "record-client: cropping the letterbox — $CROP"
fi

if [ -s "$WINDOWS" ]; then
    n=0
    TAKES=()
    while IFS=$'\t' read -r START_MS STOP_MS LABEL; do
        [ -z "${START_MS:-}" ] && continue
        n=$((n + 1))
        OFFSET="$(awk -v s="$START_MS" -v t="$CAP_T0" 'BEGIN{o=(s-t)/1000; if(o<0)o=0; printf "%.3f", o}')"
        DUR="$(awk -v a="$START_MS" -v b="$STOP_MS" 'BEGIN{d=(b-a)/1000; if(d<=0)d=15; printf "%.3f", d}')"
        OUT="$FILM_DIR/film-${n}-$(printf '%s' "${LABEL:-take}" | tr -c 'A-Za-z0-9_-' '_').mp4"
        echo "record-client: take $n — +${OFFSET}s for ${DUR}s -> $(basename "$OUT")"
        # Re-encoded, not stream-copied: -c copy can only cut on a keyframe, and with a
        # keyframe every few seconds that silently moves the start of the clip. On a
        # 15-second window aimed at a 1.5-second fire breath, that is the difference between
        # a film of the breath and a film of a creature standing still.
        # ⚠ -nostdin IS LOAD-BEARING, and without it a second take is impossible. ffmpeg reads
        # stdin, and inside `while read` that stdin is the window list — so take 1 encodes fine and
        # then swallows every remaining line. Take 2 came out as `+0.000s for 1787000005.083s`: an
        # offset of zero and a duration of the entire Unix epoch, because START_MS had been eaten.
        # The file it produced was 48 bytes and the run still reported success.
        ffmpeg -nostdin -loglevel warning -y -ss "$OFFSET" -i "$RAW" -t "$DUR" \
            ${CROP:+-vf "$CROP"} \
            -c:v libx264 -preset veryfast -crf 24 -pix_fmt yuv420p -movflags +faststart \
            "$OUT" >>"$FILM_DIR/ffmpeg.log" 2>&1
        # A cut that produced (almost) nothing is a take that did not happen. Saying so is the
        # difference between "the film is here" and a 48-byte file nobody opens until later.
        if [ "$(stat -c %s "$OUT" 2>/dev/null || echo 0)" -lt 1024 ]; then
            echo "record-client: ⚠ take $n (${LABEL:-take}) came out EMPTY — $(basename "$OUT")" >&2
        else
            TAKES+=("$OUT")
        fi
    done < "$WINDOWS"
    # FILM_OUT is every take back to back. It used to be `cp -f` of each take in turn, so a
    # three-take run left only the LAST take there — and a README called it "the three takes".
    rm -f "$FILM_OUT"
    if [ "${#TAKES[@]}" -eq 1 ]; then
        cp -f "${TAKES[0]}" "$FILM_OUT"
    elif [ "${#TAKES[@]}" -gt 1 ]; then
        _list="$FILM_DIR/concat.txt"
        : > "$_list"
        for _t in "${TAKES[@]}"; do printf "file '%s'\n" "$_t" >> "$_list"; done
        # Re-encoded like the takes (the rule above: never stream-copy a film here).
        ffmpeg -nostdin -loglevel warning -y -f concat -safe 0 -i "$_list" \
            -c:v libx264 -preset veryfast -crf 24 -pix_fmt yuv420p -movflags +faststart \
            "$FILM_OUT" >>"$FILM_DIR/ffmpeg.log" 2>&1
    fi
    if [ -s "$FILM_OUT" ]; then
        echo "record-client: FILM $(du -h "$FILM_OUT" | cut -f1) -> $FILM_OUT  ($n take(s))"
    else
        echo "record-client: ⚠ the cuts produced nothing — see $FILM_DIR/ffmpeg.log" >&2
    fi
else
    echo "record-client: ⚠ the client never marked a film window, so there is nothing to cut.
record-client:   Was filmSeconds set? The whole session is at $RAW." >&2
fi

exit "$RC"
