#!/usr/bin/env bash
# One redstone evaluation case, end to end, with no client: a fresh world, the setup (what the child already has),
# the wish granted by the real agent (HeadlessWishProbe), then a SECOND boot of the same saved world that grades it
# against the rubric (redstone/RubricGrader, docs/REDSTONE.md §5) and writes verdict.json.
#
#   tools/redstone-wish.sh CASE.json OUTDIR
#
# CASE.json: {"wish": "...", "setup": "<setup tool calls, relative to the case>", "building": "<id>",
#             "controls": [{"kind": "lever", "side": "outside", "maxDist": 12}], "onIs": "closed"}
# OUTDIR gets: result (the probe's verdict), before.json (the snapshot), wish.log, grade.log, verdict.json.
set -euo pipefail
case_file=$(realpath "$1"); out=$(realpath -m "$2"); mkdir -p "$out"
here=$(cd "$(dirname "$0")/.." && pwd); cd "$here"
rundir=${RS_RUNDIR:-rsWish}
wish=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["wish"])' "$case_file")
setup=$(python3 -c 'import json,sys,os; c=json.load(open(sys.argv[1])); print(os.path.join(os.path.dirname(sys.argv[1]), c["setup"]))' "$case_file")
w64=$(printf '%s' "$wish" | base64 | tr -d '\n')
rm -rf "run/$rundir/world" "$out/result" "$out/verdict.json"
echo "== wish: $wish"
timeout "${RS_WISH_TIMEOUT:-1100}" ./gradlew runServer -Prundir="$rundir" --console=plain \
  -Ptestprops="agent=on,headlesswish=$w64,headlesswishsig=$out,headlesswishsetup=$setup,headlesswishtimeout=900" \
  > "$out/wish.log" 2>&1 || true
head -1 "$out/result" 2>/dev/null || { echo "no result: see $out/wish.log"; exit 2; }
[ -f "$out/before.json" ] || { echo "no snapshot was taken: see $out/wish.log"; exit 2; }
# the case the grader reads, beside the snapshot
python3 - "$case_file" "$out/case.json" <<'PY'
import json,sys
c=json.load(open(sys.argv[1])); c["snapshot"]="before.json"; json.dump(c,open(sys.argv[2],"w"),indent=1)
PY
timeout 600 ./gradlew runServer -Prundir="$rundir" --console=plain \
  -Ptestprops="rsgrade=$out/case.json,rsgradeout=$out" > "$out/grade.log" 2>&1 || true
[ -f "$out/verdict.json" ] && python3 -c 'import json,sys; v=json.load(open(sys.argv[1])); print("== verdict:", v["verdict"]); [print("  -",n) for n in v.get("notes",[])]' "$out/verdict.json" \
  || { echo "no verdict: see $out/grade.log"; exit 2; }
