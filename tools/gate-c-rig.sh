#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# gate-c-rig.sh — run every RIG FILM (the atom rig's Gate C client tests), in order.
#
#     ./tools/gate-c-rig.sh                     # every film
#     ./tools/gate-c-rig.sh --only vanillawalklook,vanillahair
#     ./tools/gate-c-rig.sh --no-retry          # one attempt per film
#     ./tools/gate-c-rig.sh --list              # what it would run, and nothing else
#
# WHY: docs/VANILLA_RIG_SCOREBOARD.md marks rows VERIFIED against `GateC:<testmode>` films, and nothing ran
# those films unless a person launched one by hand, so an intermittent film went unseen for days.
# `python3 tools/rig-films.py --needed` says when a change needs this; CI's `rig-films` job runs it then.
#
# THE SET is read off the scoreboard (`tools/rig-films.py --list`): every film a row names, plus the
# extras, minus the paid agent film.
#
# FLAKE POLICY: a film that fails is run ONCE more. Failed twice = FAIL; failed then passed = FLAKY, and
# FLAKY is not a pass — the exit is non-zero and it says to run the film on the base branch to tell a
# flake it inherited from one it made.
#
# Exit: 0 all passed · 1 a film FAILED or was FLAKY · 2 a film could not run (setup, no result, timeout)
# — the gate is broken, which is not a finding about the rig.
#
# Cost: ~3–5 min a film on a 4-vCPU machine (client boot included), ~35–50 min the set. Results and logs
# go to $GATE_C_OUT (default /tmp/moddingfromamod-gate-c-rig).
# ⚠ Never run it beside a Gate B or a build in the same checkout: these films are timing oracles, and CPU
# contention is exactly the kind of noise they are built to reject.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 2

ONLY=""
RETRY=1
LIST=0
while [ $# -gt 0 ]; do
    case "$1" in
        --only) ONLY="${2:-}"; shift 2 ;;
        --only=*) ONLY="${1#--only=}"; shift ;;
        --no-retry) RETRY=0; shift ;;
        --list) LIST=1; shift ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "gate-c-rig: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

MC_T="$(sed -n 's/^minecraft_version=//p' gradle.properties | head -1)"
MOD_ID="$(sed -n 's/^mod_id=//p' gradle.properties | head -1)"

FILMS=()
while read -r mode _cls; do
    [ -n "$mode" ] || continue
    if [ -n "$ONLY" ] && ! printf ',%s,' "$ONLY" | grep -q ",$mode,"; then
        continue
    fi
    FILMS+=("$mode")
done < <(python3 tools/rig-films.py --list)

echo "gate-c-rig: Minecraft $MC_T — ${#FILMS[@]} film(s): ${FILMS[*]:-none}"
[ "$LIST" = 1 ] && exit 0
if [ ${#FILMS[@]} -eq 0 ]; then
    echo "gate-c-rig: nothing to run" >&2
    exit 2
fi

COMMIT="$(git rev-parse HEAD 2>/dev/null || true)"
DIRTY=false
[ -n "$(git status --porcelain --untracked-files=no 2>/dev/null)" ] && DIRTY=true
OUT="${GATE_C_OUT:-/tmp/${MOD_ID}-gate-c-rig}"
rm -rf "$OUT"; mkdir -p "$OUT"

# One attempt: film -> rc, seconds, first line of its verdict. Each film its own SLOT and SIG so a
# stale result from another film can never be read as this one's.
# PART DRIFT: the walking films also run the client's drift meter (src/main/java/com/moddingfromamod/gatec/RigDriftMeter), and
# tools/rig-drift.py fails the film when any part of a rig is drawn off its body (a foot off its leg). The rigidity
# oracle inside vanillawalklook once PASSED a send cadence while 456 of 463 frames were out (it excused them as delivery
# splits); this check fails it (tools/test-rig-drift.py holds the red arm). It also judges SPIN: parts that, while turning,
# are drawn still for over half a game tick and then move again (the death film's fall froze that way under lag),
# which is why the death film is one of them.
DRIFT_FILMS=" vanillawalk vanillawalklook vanilladeath "
attempt() {
    local mode="$1" round="$2" sig="/tmp/${MOD_ID}-rig-${1}" t0 rc props="${TESTPROPS:-}" drift=""
    t0=$(date +%s)
    case "$DRIFT_FILMS" in *" $mode "*)
        drift="/tmp/${MOD_ID}-rig-${mode}-drift"; rm -rf "$drift"
        props="${props:+$props,}rigdrift=$drift" ;;
    esac
    SLOT="rig-$mode" SIG="$sig" TESTMODE="$mode" TESTPROPS="$props" \
        "${GATE_C_CLIENT:-./tools/client-test.sh}" "$mode" > "$OUT/$mode.$round.log" 2>&1
    rc=$?
    cp "$sig/result" "$OUT/$mode.$round.result" 2>/dev/null || true
    if [ "$rc" = 0 ] && [ -n "$drift" ] && { [ -d "$drift" ] || [ -z "${GATE_C_CLIENT:-}" ]; }; then
        if ! python3 tools/rig-drift.py "$drift" > "$OUT/$mode.$round.drift" 2>&1; then
            rc=1
            printf 'FAIL — part drift: %s\n' "$(grep -m1 -E 'FAIL|NO-DATA' "$OUT/$mode.$round.drift" | sed 's/^ *//' | cut -c1-300)" \
                > "$OUT/$mode.$round.result"
        fi
    fi
    printf '%s\t%s\t%s\n' "$rc" "$(( $(date +%s) - t0 ))" \
        "$(head -1 "$OUT/$mode.$round.result" 2>/dev/null | cut -c1-400)"
}

declare -A FIRST SECOND
: > "$OUT/round1.tsv"
for mode in "${FILMS[@]}"; do
    echo "gate-c-rig: $mode …"
    IFS=$'\t' read -r rc secs verdict < <(attempt "$mode" 1)
    FIRST[$mode]="$rc"
    printf '%s\t%s\t%s\t%s\n' "$mode" "$rc" "$secs" "$verdict" >> "$OUT/round1.tsv"
    echo "gate-c-rig: $mode rc=$rc in ${secs}s — ${verdict:-no result}"
done

if [ "$RETRY" = 1 ]; then
        : > "$OUT/round2.tsv"
    for mode in "${FILMS[@]}"; do
        [ "${FIRST[$mode]}" = 1 ] || continue
        echo "gate-c-rig: $mode failed — running it once more (flake policy)"
        IFS=$'\t' read -r rc secs verdict < <(attempt "$mode" 2)
        SECOND[$mode]="$rc"
        printf '%s\t%s\t%s\t%s\n' "$mode" "$rc" "$secs" "$verdict" >> "$OUT/round2.tsv"
        echo "gate-c-rig: $mode (again) rc=$rc in ${secs}s — ${verdict:-no result}"
    done
fi

echo
echo "=== RIG FILMS on Minecraft $MC_T (commit ${COMMIT:0:8}$([ "$DIRTY" = true ] && echo ', DIRTY tree')) ==="
EXIT=0
for mode in "${FILMS[@]}"; do
    a="${FIRST[$mode]}"; b="${SECOND[$mode]:-}"
    case "$a:$b" in
        0:) v="PASS" ;;
        1:0) v="FLAKY — failed, then passed. Run it on the base branch to tell whose it is"; EXIT=$(( EXIT > 1 ? EXIT : 1 )) ;;
        1:1|1:) v="FAIL"; EXIT=$(( EXIT > 1 ? EXIT : 1 )) ;;
        *) v="COULD NOT RUN (rc $a${b:+/$b}) — see $OUT/$mode.*.log"; EXIT=2 ;;
    esac
    printf '  %-18s %s\n' "$mode" "$v"
done
echo "logs: $OUT"
exit "$EXIT"
