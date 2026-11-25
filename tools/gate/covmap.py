"""The coverage map: which production methods each GameTest class executes (docs/GATE_B.md, "The coverage map").

A mapping run is Gate B with the JaCoCo agent on (`-Pcovagent`, `-Pcovmap`): `testing/gt/CoverageMap` gives every
GameTest class batches of its own, so no two classes run at once, and dumps the agent's data each time the running
class changes. `CovmapAnalyze.java` turns one job's dumps into the methods each UNIT (a GameTest class, or the
server's own `__boot__`, `__idle__` and `__shutdown__`) executed. This module builds the map from those, merges a
partial run into the last map, says what a run DROPPED, and checks two maps of one commit agree.

The map (`covmap/map.json.gz` on the gate-data branch):
  commit, tree, built, kind (full | partial)
  methods  [id, ...]                        every normalised method (`pkg/Class#name`), sorted; units index into it
  where    {id index: [file, [[first, last], ...]]}  the source file and line ranges of each method, at `commit`
  files    {path: blob}                     each production source file's blob at `commit` (line ranges hold only
                                            for a file whose blob is still this one)
  ambient  [index, ...]                     what runs with no test of ours running: start-up, the idle window,
                                            shutdown. A change to one of these may touch every test.
  units    {GameTestClass: {"m": [index, ...], "commit": c}}   what that class ran, beyond the ambient methods
Costs live beside it (`covmap/costs.json`): seconds per class under coverage, the rounded median of the last runs.
"""
from __future__ import annotations

import os
import statistics
import subprocess
import time
from typing import Dict, Iterable, List, Optional, Set, Tuple

import gatelib as g

PSEUDO = ("__boot__", "__idle__", "__shutdown__")
UNSTABLE = os.path.join(g.ROOT, "corpus", "gate-covmap-unstable.json")
COST_SAMPLES = 5


def analyze(classes_dir: str, exec_dir: str, out_json: str, jars: List[str]) -> dict:
    """Run CovmapAnalyze.java (single-file source launch) over one job's dumps."""
    java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"
    src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "CovmapAnalyze.java")
    cp = os.pathsep.join(jars)
    subprocess.run([java, "-cp", cp, src, classes_dir, exec_dir, out_json], check=True)
    return g.read_json(out_json)


def job_units(analysis: dict) -> Tuple[Dict[str, Set[str]], Set[str], Dict[str, dict]]:
    """(units, ambient, method table) from one job's analysis. A Bedrock-style `Class#case` unit keeps its own name."""
    units: Dict[str, Set[str]] = {}
    ambient: Set[str] = set()
    for unit, methods in analysis["units"].items():
        if unit in PSEUDO or "#__" in unit:
            ambient.update(methods)
        else:
            units[unit] = set(methods)
    return units, ambient, analysis["methods"]


def build(jobs: List[dict], commit: str, tree: str, files: Dict[str, str], kind: str,
          previous: Optional[dict] = None, remap: Iterable[str] = ()) -> dict:
    """One map from the jobs' analyses. A PARTIAL build keeps the previous map's units it did not re-map."""
    units: Dict[str, Set[str]] = {}
    unit_commit: Dict[str, str] = {}
    ambient: Set[str] = set()
    table: Dict[str, dict] = {}
    for j in jobs:
        u, a, t = job_units(j)
        ambient |= a
        table.update(t)
        for k, v in u.items():
            units.setdefault(k, set()).update(v)
            unit_commit[k] = commit
    if kind == "partial" and previous:
        old = decode(previous)
        ambient |= old["ambient"]
        remapped = set(remap) | set(units)
        for k, v in old["units"].items():
            if k not in remapped:
                units[k] = v
                unit_commit[k] = previous["units"][k].get("commit", previous.get("commit"))
    for k in units:
        units[k] -= ambient
    return encode(units, unit_commit, ambient, table, commit, tree, files, kind)


def encode(units: Dict[str, Set[str]], unit_commit: Dict[str, str], ambient: Set[str], table: Dict[str, dict],
           commit: str, tree: str, files: Dict[str, str], kind: str) -> dict:
    used: Set[str] = set(ambient)
    for v in units.values():
        used |= v
    used |= set(table)
    methods = sorted(used)
    index = {m: i for i, m in enumerate(methods)}
    where = {}
    for m, t in table.items():
        where[str(index[m])] = [t.get("file", ""), [list(r) for r in t.get("lines", [])]]
    return {
        "schema": 1, "commit": commit, "tree": tree, "kind": kind,
        "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "methods": methods,
        "where": where,
        "files": dict(sorted(files.items())),
        "ambient": sorted(index[m] for m in ambient),
        "units": {k: {"m": sorted(index[m] for m in units[k]), "commit": unit_commit.get(k, commit)}
                  for k in sorted(units)},
    }


def decode(m: dict) -> dict:
    """The map with method names in place of indices: {"units": {cls: set}, "ambient": set, "where": {id: (file, ranges)}}."""
    names = m["methods"]
    return {
        "units": {k: {names[i] for i in v["m"]} for k, v in m["units"].items()},
        "ambient": {names[i] for i in m["ambient"]},
        "where": {names[int(i)]: (w[0], [tuple(r) for r in w[1]]) for i, w in m["where"].items()},
    }


def dropped(previous: Optional[dict], current: dict, tier: Iterable[str]) -> List[str]:
    """Classes the previous map placed and this one does not, although they are still in the tier: never forgotten
    silently. (A class that left the tier is not dropped; it is gone.)"""
    if not previous:
        return []
    tier = set(tier)
    return sorted(k for k in previous["units"] if k not in current["units"] and k in tier)


def unplaced(m: Optional[dict], tier: Iterable[str]) -> List[str]:
    """Tier classes the map has no entry for: they always run (and a partial mapping run maps them)."""
    placed = set(m["units"]) if m else set()
    return sorted(c for c in tier if c not in placed)


# Timer noise two maps of one commit may show (measured on the whole tier, four times, locally and on GitHub's
# runners). Per class: at most NOISE_METHODS methods, or under 1 - NOISE_AGREEMENT of the class's methods (a
# server-tick service whose period falls inside one run's window for the class). Beyond that, the noise is SPREAD, not
# a property of a class: two full maps put a different handful of classes over the per-class allowance every time (9
# locally, 6 on GitHub, none in common), so per run up to NOISE_CLASSES of the tier's classes (at least
# NOISE_CLASSES_MIN) may go over it, by name, while the maps agree on at least NOISE_TOTAL of all class-method entries.
# The ambient sets may differ by NOISE_AMBIENT of their size. A broken mapping process is far outside all of these:
# with the batch order random (fixed by GameTestBatchOrderMixin), 264 of 360 classes went over, at 94% agreement.
# A class that keeps going over is listed in corpus/gate-covmap-unstable.json with what was measured.
NOISE_METHODS = 5
NOISE_AGREEMENT = 0.95
NOISE_CLASSES = 0.02
NOISE_CLASSES_MIN = 3
NOISE_TOTAL = 0.99
NOISE_AMBIENT = 0.005


def agreement(x: Set[str], y: Set[str]) -> float:
    return len(x & y) / float(max(1, len(x | y)))


def compare(a: dict, b: dict, unstable: Iterable[str] = (), notes: Optional[List[str]] = None) -> List[str]:
    """Two maps of the SAME commit must agree on every class's methods, but for timer noise (see NOISE_*) and the listed
    timing-dependent classes. Returns the problems (the refresh fails); the classes that went over the per-class
    allowance within the run's allowance are appended to `notes`, by name. Costs and build times are never compared:
    wall clock varies by runner."""
    problems = []
    if a.get("commit") != b.get("commit"):
        problems.append("the maps are of different commits (%s, %s)" % (a.get("commit"), b.get("commit")))
    da, db = decode(a), decode(b)
    allowed = set(unstable)
    over = []
    entries = differing = 0
    for k in sorted(set(da["units"]) | set(db["units"])):
        x, y = da["units"].get(k), db["units"].get(k)
        if x is None or y is None:
            if k not in allowed:
                problems.append("%s is in only one map" % k)
            continue
        entries += len(x | y)
        differing += len(x ^ y)
        if k in allowed:
            continue
        diff = x ^ y
        if len(diff) > NOISE_METHODS and agreement(x, y) < NOISE_AGREEMENT:
            over.append("%s: %d method(s) only in the first, %d only in the second, agreement %.3f (e.g. %s)" % (
                k, len(x - y), len(y - x), agreement(x, y), sorted(diff)[0]))
    total = 1.0 - differing / float(max(1, entries))
    room = max(NOISE_CLASSES_MIN, int(NOISE_CLASSES * len(da["units"])))
    if over and (len(over) > room or total < NOISE_TOTAL):
        problems += over
        problems.append("%d class(es) over the per-class allowance (at most %d may be), agreement %.4f overall "
                        "(at least %.2f must be)" % (len(over), room, total, NOISE_TOTAL))
    elif over and notes is not None:
        notes += over
    amb = da["ambient"] ^ db["ambient"]
    if len(amb) > max(NOISE_METHODS, NOISE_AMBIENT * len(da["ambient"] | db["ambient"])):
        problems.append("the ambient sets differ by %d method(s)" % len(amb))
    return problems


def noise(a: dict, b: dict) -> dict:
    """How far two maps of one commit agree: the numbers the determinism check is judged by."""
    da, db = decode(a), decode(b)
    entries = diffs = 0
    per = []
    for k in set(da["units"]) & set(db["units"]):
        x, y = da["units"][k], db["units"][k]
        entries += len(x | y)
        diffs += len(x ^ y)
        per.append(agreement(x, y))
    per.sort()
    return {"classes": len(per), "entries": entries, "differing": diffs,
            "agree": 1.0 - diffs / float(max(1, entries)), "medianClass": per[len(per) // 2] if per else 1.0,
            "differingClasses": sum(1 for p in per if p < 1.0)}


def costs_update(old: Optional[dict], seconds: Dict[str, float]) -> dict:
    """Keep the last COST_SAMPLES measured seconds of each class and its rounded median."""
    out = {"schema": 1, "classes": {}}
    prev = (old or {}).get("classes", {})
    for cls in sorted(set(prev) | set(seconds)):
        samples = list(prev.get(cls, {}).get("samples", []))
        if cls in seconds:
            samples = (samples + [round(seconds[cls], 1)])[-COST_SAMPLES:]
        out["classes"][cls] = {"samples": samples, "median": round(statistics.median(samples)) if samples else None}
    return out


def source_blobs(rev: str = "HEAD", root: str = g.ROOT) -> Dict[str, str]:
    """Each production Java file's blob at `rev`, by its package path (what CovmapAnalyze calls a method's file)."""
    out = {}
    prefix = g.JAVA_ROOT.replace(os.sep, "/") + "/"
    for line in g.git("ls-tree", "-r", "--full-tree", rev, "--", prefix, cwd=root).splitlines():
        meta, path = line.split("\t", 1)
        if path.endswith(".java"):
            out[path[len(prefix):]] = meta.split()[2]
    return out


def load_unstable(path: str = UNSTABLE) -> List[str]:
    if not os.path.exists(path):
        return []
    return [r["class"] for r in g.read_json(path).get("classes", [])]


# ---------------------------------------------------------------------------------------------- the commands

def to_remap(mode: str, previous: Optional[dict], tier: Dict[str, dict], blobs: Dict[str, str]) -> List[str]:
    """Which classes a mapping run maps. FULL: all. PARTIAL: those the map has not placed, and those whose own source
    file changed since the map recorded it (a test that changed may reach different code)."""
    if mode == "full" or not previous:
        return sorted(tier)
    out = set(unplaced(previous, tier))
    files = previous.get("files", {})
    for cls, v in tier.items():
        rel = v["path"][len(g.JAVA_ROOT.replace(os.sep, "/")) + 1:]
        if files.get(rel) != blobs.get(rel):
            out.add(cls)
    return sorted(out)


def plan_cmd(a) -> int:
    import json
    import nightly
    tier = g.tier_classes(g.scan_tier(), g.FULL_TIER)
    previous = g.read_json(a.map) if a.map and os.path.exists(a.map) else None
    costs_doc = g.read_json(a.costs) if a.costs and os.path.exists(a.costs) else {}
    costs = {c: v["median"] for c, v in costs_doc.get("classes", {}).items() if v.get("median") is not None}
    classes = to_remap(a.mode, previous, tier, source_blobs())
    sub = {c: tier[c] for c in classes}
    if not sub:
        p = {"schema": 1, "mode": a.mode, "shard": g.FULL_TIER, "jobs": [], "tierClasses": {}, "tierTests": 0}
    else:
        p = nightly.plan(g.FULL_TIER, a.jobs, costs, tier=sub)
        if a.mode == "partial" or len(sub) < len(tier):
            # a partial run maps exactly its classes: no job takes "everything else"
            for j in p["jobs"]:
                j["classes"], j["notClasses"] = list(j["planned"]), []
        p["mode"] = a.mode
    p["replicas"] = max(1, a.replicas)
    g.write_json(a.out, p)
    matrix = {"include": [{"job": j["index"], "replica": r} for r in range(p["replicas"]) for j in p["jobs"]]}
    print(json.dumps(matrix))
    print("covmap plan: %s, %d class(es) to map in %d job(s) x %d" % (a.mode, len(sub), len(p["jobs"]), p["replicas"]),
          file=__import__("sys").stderr)
    return 0


def merge_cmd(a) -> int:
    p = g.read_json(a.plan)
    previous = g.read_json(a.map) if a.map and os.path.exists(a.map) else None
    old_costs = g.read_json(a.costs) if a.costs and os.path.exists(a.costs) else None
    replicas: Dict[int, List[Optional[dict]]] = {}
    jobs_meta: Dict[int, List[Optional[dict]]] = {}
    problems: List[str] = []
    for d in a.jobs:
        # an artifact keeps the paths it was uploaded with, so look below each directory
        jf = g.find_file(d, "job.json") if os.path.isdir(d) else None
        if not jf:
            continue
        af = os.path.join(os.path.dirname(jf), "analysis.json")
        j = g.read_json(jf)
        idx = int(str(j.get("label", "job-0-r0")).split("-")[1])
        rep = int(str(j.get("label", "job-0-r0")).split("-r")[-1]) if "-r" in str(j.get("label")) else 0
        replicas.setdefault(rep, [None] * len(p["jobs"]))
        jobs_meta.setdefault(rep, [None] * len(p["jobs"]))
        jobs_meta[rep][idx] = j
        if os.path.exists(af):
            replicas[rep][idx] = g.read_json(af)
    n_rep = p.get("replicas", 1)
    for r in range(n_rep):
        for i in range(len(p["jobs"])):
            if r not in replicas or replicas[r][i] is None:
                problems.append("replica %d job %d left no analysis" % (r, i))
    tier = g.tier_classes(g.scan_tier(), g.FULL_TIER)
    commit = g.git("rev-parse", "HEAD")
    tree = g.gate_tree()
    blobs = source_blobs()
    planned = sorted({c for j in p["jobs"] for c in j["planned"]})
    maps = []
    for r in sorted(replicas):
        got = [x for x in replicas[r] if x is not None]
        maps.append(build(got, commit, tree, blobs, p.get("mode", "full"), previous, planned))
    union = None
    if len(maps) > 1:
        # the published map is the union of the replicas: a method one run saw a class execute stays in its set
        every = [x for r in sorted(replicas) for x in replicas[r] if x is not None]
        union = build(every, commit, tree, blobs, p.get("mode", "full"), previous, planned)
    if not maps:
        problems.append("no job produced an analysis: no map")
        g.write_json(os.path.join(a.out, "problems.json"), {"problems": problems, "publish": False})
        g.write_json(os.path.join(a.out, "record.json"), _record(problems, a.run_url))
        g.write_text(os.path.join(a.out, "report.md"), "**The coverage map refresh failed.**\n\n" +
                     "\n".join("- " + x for x in problems) + "\n")
        print("\n".join(problems))
        return 1
    m = maps[0]
    measured = []
    timer_noise: List[str] = []
    for r, other in enumerate(maps[1:], start=1):
        for x in compare(m, other, load_unstable(), timer_noise):
            problems.append("determinism: replica 0 and replica %d differ: %s" % (r, x))
        measured.append(noise(m, other))
    if union is not None:
        m = union
    # costs: seconds each class held a server for under coverage, from every replica's records
    seconds: Dict[str, List[float]] = {}
    for rep in jobs_meta.values():
        for j in rep:
            if not j:
                continue
            per = g.costs_from_records([{"tests": [dict(t, name=t["id"]) for t in j.get("tests", [])]}], list(tier))
            for c, s in per.items():
                seconds.setdefault(c, []).append(s)
    costs = costs_update(old_costs, {c: statistics.median(v) for c, v in seconds.items()})
    # a map with a job missing is not published: the previous map stays, and the issue says why
    missing = [x for x in problems if "left no analysis" in x]
    gone = dropped(previous, m, tier)
    if gone:
        problems.append("the mapping run DROPPED %d class(es) the previous map placed: %s (they run on every pull "
                        "request until a run places them again)" % (len(gone), ", ".join(gone)))
    not_placed = [c for c in planned if c not in m["units"]]
    still = unplaced(m, tier)
    m["unplaced"] = still
    g.write_json(os.path.join(a.out, "map.json.gz"), m, compress=True)
    g.write_json(os.path.join(a.out, "costs.json"), costs)
    publish = not missing
    g.write_json(os.path.join(a.out, "problems.json"), {"problems": problems, "publish": publish})
    g.write_json(os.path.join(a.out, "record.json"), _record(problems, a.run_url))
    size = os.path.getsize(os.path.join(a.out, "map.json.gz"))
    lines = ["**Coverage map %s** at `%s` (gate tree `%s`)%s" % (
        m["kind"], commit[:12], tree[:12], (" -- [the run](%s)" % a.run_url) if a.run_url else ""), "",
        "%d of the tier's %d classes placed; %d methods known, %d ambient (start-up, idle, shutdown); "
        "map %d KB gzipped." % (len(m["units"]), len(tier), len(m["methods"]), len(m["ambient"]), size // 1024)]
    for n in measured:
        lines.append("\nDeterminism: the replicas agree on %.2f%% of %d class-method entries; %d of %d classes differ "
                     "at all, median agreement %.4f." % (100 * n["agree"], n["entries"], n["differingClasses"],
                                                        n["classes"], n["medianClass"]))
    if timer_noise:
        lines.append("\nOver the per-class allowance this time, within the run's (timer noise; a class that keeps "
                     "appearing here belongs in corpus/gate-covmap-unstable.json):")
        lines += ["- " + x for x in timer_noise]
    if not_placed:
        lines.append("\nThis run mapped but did not place (no test of theirs ran under coverage): %s" %
                     ", ".join(not_placed))
    if still:
        lines.append("\nUnplaced, so they run on every pull request: %s" % ", ".join(still))
    if not publish:
        lines.append("\n**Not published**: %d mapping job(s) left no analysis, and a map without their classes is "
                     "not one to select by. The previous map stays." % len(missing))
    if problems:
        lines += ["", "### Problems", ""] + ["- " + x for x in problems]
    g.write_text(os.path.join(a.out, "report.md"), "\n".join(lines) + "\n")
    print("\n".join(lines))
    return 1 if problems else 0


def _record(problems: List[str], run_url: str) -> dict:
    """What gate.py issue reads: red when the refresh had a problem."""
    commit = g.git("rev-parse", "HEAD")
    return {"verdict": "red" if problems else "green", "commit": commit, "runUrl": run_url, "problems": problems,
            "counts": {}, "tests": []}


def compare_cmd(a) -> int:
    problems = compare(g.read_json(a.a), g.read_json(a.b), load_unstable())
    for x in problems:
        print(x)
    print("covmap compare: %s" % ("SAME (but for costs and the listed timing-dependent classes)" if not problems
                                  else "%d difference(s)" % len(problems)))
    return 1 if problems else 0
