#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# validate.sh — scripted validation of a PRE-RELEASE Moddy jar in a real Minecraft install, filmed.
#
#     tools/client-validation/validate.sh install [TAG]     # once per pre-release: the jar, ECA, TLS, the carrier
#     tools/client-validation/validate.sh run [NAME...]     # each playtest, with a video of the game window
#     tools/client-validation/validate.sh list
#
# What it is: MMVU's real-install + playtest tools (github.com/jivinstev/mc-mod-version-upgrade) pointed at the
# public repo's pre-release jar (jivinstev/ModdyTheWishWatch, release TAG, default the newest), with the companion
# mods a player would add — Epic Core API (ECA) and The Last Sword (TLS) for the same Minecraft version — and MMVU's
# Gate C carrier, which runs each `.playtest` in playtests/ step by step in the real client and grades it. Nothing
# here builds (no Gradle dev client): it tests the jar players get.
#
# Environment:
#   MMVU=<path>            MMVU checkout (default ~/dev/mc-mod-version-upgrade); needs the window-recorder --video
#   MC=1.21.8              Minecraft version          NEOFORGE=21.8.57   NeoForge version (the jar's build)
#   LABEL=moddy-prerelease instance name (instances/<LABEL>-<MC>/ in the Launcher folder; never the player's own)
#   OUT=<dir>              reports and videos (default <repo>/out/client-validation, git-ignored)
#   FFMPEG=<path>          for the 720p review copy (default: ffmpeg on PATH; without one, only the .mov)
#   NO_VIDEO=1             key frames only
#   CARRIER_JAR=<jar>      a carrier built from MMVU's source (gatec-carrier.py prepare + gradlew build) instead of
#                          the instance's released one -- needed until a release carries @type and @click item
#   MODDY_HOME=<dir>       Moddy's home for the runs (default ~/.mmvu/real-install/<LABEL>/moddy-home). A run of the
#                          whole set starts it empty, like a new player; naming playtests keeps it (the Magician
#                          playtests build on 03's settings and 04's check).
#
# Exit status of `run`: 0 when every playtest passed, 1 otherwise. Each playtest gets
#   $OUT/<name>-<time>/  report.md, report.html, 1.21.8/frames/*.png, 1.21.8/playtest-1.21.8.mov
#   $OUT/<name>.mp4      a 720p H.264 copy of the recording (the review copy)
# A locked screen is fine (the recording is of the game window); another user holding the console is not.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
MMVU="${MMVU:-$HOME/dev/mc-mod-version-upgrade}"
MC="${MC:-1.21.8}"
NEOFORGE="${NEOFORGE:-21.8.57}"
LABEL="${LABEL:-moddy-prerelease}"
OUT="${OUT:-$REPO/out/client-validation}"
PUBLIC_REPO="jivinstev/ModdyTheWishWatch"
WORK="$HOME/.mmvu/real-install/$LABEL"
PLAN="$WORK/plan.json"
# Moddy's own folder for these runs (settings, the Magician check, players), never the player's ~/.moddingfromamod.
MODDY_HOME="${MODDY_HOME:-$WORK/moddy-home}"

die() { echo "validate: $*" >&2; exit 2; }
[ -f "$MMVU/tools/playtest.py" ] || die "no MMVU checkout at $MMVU (set MMVU=...)"

ffmpeg_bin() {
  if [ -n "${FFMPEG:-}" ]; then echo "$FFMPEG"; return; fi
  if command -v ffmpeg >/dev/null; then command -v ffmpeg; return; fi
  echo ""
}

instance_dir() {
  python3 -c "import json,sys; p=json.load(open(sys.argv[1])); print([v['instance'] for v in p['versions'] if v['mc']==sys.argv[2]][0])" "$PLAN" "$MC"
}

# A 1280x720 game window: the recording is the window, so this is the video's frame (2x on a Retina display).
set_window_size() {
  local opt; opt="$(instance_dir)/options.txt"
  [ -f "$opt" ] || printf 'overrideWidth:1280\noverrideHeight:720\n' > "$opt"
  sed -i '' -e 's/^overrideWidth:.*/overrideWidth:1280/' -e 's/^overrideHeight:.*/overrideHeight:720/' "$opt"
  grep -q '^overrideWidth:' "$opt" || echo 'overrideWidth:1280' >> "$opt"
  grep -q '^overrideHeight:' "$opt" || echo 'overrideHeight:720' >> "$opt"
}

cmd_install() {
  local tag="${1:-}"
  local jars="$WORK/moddy-jar"
  mkdir -p "$jars"
  if [ -z "$tag" ]; then
    tag="$(gh release list -R "$PUBLIC_REPO" --limit 20 --json tagName,isPrerelease -q '[.[]|select(.tagName|endswith("mc'"$MC"'"))][0].tagName')"
    [ -n "$tag" ] || die "no release for Minecraft $MC on $PUBLIC_REPO"
  fi
  echo "validate: Moddy $tag from $PUBLIC_REPO"
  rm -f "$jars"/*.jar
  # gh downloads the release jar (it works with a signed-in account wherever the repo is visible); --jar installs it.
  gh release download "$tag" -R "$PUBLIC_REPO" -p '*.jar' -D "$jars" --clobber
  local jar; jar="$(ls "$jars"/*.jar | head -1)"
  echo "$tag" > "$WORK/moddy-tag.txt"
  python3 "$MMVU/tools/real-install.py" plan --fork jivinstev/EpicCoreAPI --fork jivinstev/TheLastSword \
    --mc "$MC" --prerelease --variant cf --jar "$MC=$jar" --neoforge "$MC=$NEOFORGE" --with-carrier --label "$LABEL"
  python3 "$MMVU/tools/real-install.py" install --plan "$PLAN"
  set_window_size
}

cmd_list() { ls "$HERE/playtests" | sed -n 's/\.playtest$//p'; }

cmd_run() {
  [ -f "$PLAN" ] || die "not installed: run '$0 install' first"
  set_window_size
  mkdir -p "$OUT"
  local names=("$@")
  if [ ${#names[@]} -eq 0 ]; then names=($(cmd_list)); rm -rf "$MODDY_HOME"; fi
  mkdir -p "$MODDY_HOME"
  local ff; ff="$(ffmpeg_bin)"
  local video=(--video); [ -n "${NO_VIDEO:-}" ] && video=()
  local fails=0 summary=""
  for n in "${names[@]}"; do
    local script="$HERE/playtests/$n.playtest"
    [ -f "$script" ] || die "no playtest $script"
    local rep; rep="$OUT/$n-$(date +%Y%m%d-%H%M%S)"
    echo "━━ $n"
    local rc=0
    caffeinate -d -i -u python3 "$MMVU/tools/playtest.py" run "$script" --plan "$PLAN" --mc "$MC" \
      ${video[@]+"${video[@]}"} --timeout "${TIMEOUT:-900}" --report "$rep" \
      --prop "moddingfromamod.home=$MODDY_HOME" ${CARRIER_JAR:+--carrier-jar "$MC=$CARRIER_JAR"} || rc=$?
    local mov="$rep/$MC/playtest-$MC.mov"
    if [ -f "$mov" ] && [ -n "$ff" ]; then
      "$ff" -v error -y -i "$mov" -vf "scale=-2:720:flags=lanczos,fps=30" -c:v libx264 -preset slow -crf 26 \
        -pix_fmt yuv420p -movflags +faststart -an "$OUT/$n.mp4" && echo "   video: $OUT/$n.mp4"
    fi
    [ $rc -eq 0 ] && summary+="PASS  $n"$'\n' || { summary+="FAIL  $n  ($rep/report.md)"$'\n'; fails=$((fails+1)); }
  done
  echo; echo "Moddy $(cat "$WORK/moddy-tag.txt" 2>/dev/null || echo '?') on Minecraft $MC"; printf '%s' "$summary"
  [ $fails -eq 0 ]
}

case "${1:-}" in
  install) shift; cmd_install "$@" ;;
  run) shift; cmd_run "$@" ;;
  list) cmd_list ;;
  *) sed -n '3,8p' "$0"; exit 2 ;;
esac
