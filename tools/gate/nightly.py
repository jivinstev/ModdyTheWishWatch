"""A split run of the tier: `plan` shares the classes among parallel jobs, `merge` makes their results one verdict and
one run record (docs/GATE_B.md, "The nightly full tier").

The merge is where completeness is proved. Each job already checked that its server ran everything it registered;
here the jobs together must have run every class of the tier, each with as many tests as its source declares, and no
test twice. A job that left no result, a class nobody ran, or a count that does not add up turns the run red, whatever
the tests said: a green number that is a smaller number is the failure this exists to catch.
"""
from __future__ import annotations

import json
import os
from typing import Dict, List, Optional

import gatelib as g
import history


def plan(shard: str, jobs: int, costs: Optional[Dict[str, float]] = None, tier: Optional[Dict[str, dict]] = None) -> dict:
    tier = tier if tier is not None else g.scan_tier()
    classes = g.tier_classes(tier, shard)
    split = g.split_classes(classes, jobs, costs)
    out = []
    for i in range(len(split)):
        cls, deny = g.job_filter(split, i)
        weight = sum((costs or {}).get(c, 2.0 * max(1, classes[c]["tests"])) for c in split[i])
        out.append({"index": i, "classes": cls, "notClasses": deny, "planned": split[i],
                    "estimateSeconds": round(weight)})
    return {"schema": 1, "shard": shard, "jobs": out,
            "tierClasses": {c: classes[c]["tests"] for c in sorted(classes)},
            "tierTests": sum(v["tests"] for v in classes.values()),
            "measured": sorted(c for c in classes if c in (costs or {}))}


def plan_cmd(a) -> int:
    costs = history.latest_costs(a.history) if a.history else {}
    p = plan(a.shard, a.jobs, costs)
    if a.out:
        g.write_json(a.out, p)
    matrix = {"include": [{"job": j["index"]} for j in p["jobs"]]}
    print(json.dumps(matrix))
    for j in p["jobs"]:
        print("gate plan: job %d: %d classes, about %d s%s" % (
            j["index"], len(j["planned"]), j["estimateSeconds"],
            " (and every class no other job names)" if j["index"] == 0 else ""), file=_stderr())
    return 0


def _stderr():
    import sys
    return sys.stderr


def merge(plan_: dict, jobs: List[Optional[dict]], register: Dict[str, dict], kind: str, run_url: str = "") -> dict:
    """One run record from a split run. `jobs[i]` is job i's job.json, or None when it left none."""
    problems: List[str] = []
    tests: List[dict] = []
    seen: Dict[str, int] = {}
    known = list(plan_["tierClasses"])
    job_rows = []
    commits = set()
    trees = set()
    excluded: List[str] = []
    for i, planned in enumerate(plan_["jobs"]):
        j = jobs[i] if i < len(jobs) else None
        if j is None:
            problems.append("job %d left no result (cancelled, out of time, or crashed before it wrote one)" % i)
            job_rows.append({"index": i, "missing": True})
            continue
        commits.add(j.get("commit"))
        trees.add(j.get("tree"))
        excluded += j.get("filter", {}).get("exclude", [])
        for p in j.get("problems", []):
            problems.append("job %d: %s" % (i, p))
        job_rows.append({"index": i, "label": j.get("label"), "wallSeconds": j.get("wallSeconds"),
                         "registered": j.get("registered"), "executed": j.get("executed"), "result": j.get("result"),
                         "retries": j.get("retries", [])})
        for r in j.get("tests", []):
            key = r["name"].lower()
            if key in seen:
                problems.append("%s ran in job %d and in job %d" % (r["name"], seen[key], i))
                continue
            seen[key] = i
            tests.append(dict(r, job=i))
    if len(commits) > 1 or len(trees) > 1:
        problems.append("the jobs tested different commits: %s" % ", ".join(sorted(str(c) for c in commits)))

    # every class of the tier ran, with as many tests as its source declares (less any the flake register left out)
    excluded_l = {e.lower() for e in excluded}
    ran: Dict[str, int] = {}
    for t in tests:
        c = g.test_class(t["id"], known)
        ran[c] = ran.get(c, 0) + 1
    for c, declared in plan_["tierClasses"].items():
        want = declared - sum(1 for e in excluded_l if e.split(".", 1)[0] == c.lower())
        got = ran.get(c, 0)
        if got != want and not any(j is None for j in jobs):
            problems.append("%s: its source declares %d test(s) and %d ran" % (c, want, got))
    unknown = sorted(c for c in ran if c not in plan_["tierClasses"])
    if unknown:
        problems.append("ran classes the source scan does not know (fix gatelib.scan_tier): %s" % ", ".join(unknown))

    known_flaky = {e["test"].lower(): e for e in register.values()} if register else {}
    for t in tests:
        e = known_flaky.get(t["name"].lower())
        if e is not None and t["outcome"] == "failed":
            t["outcome"] = "known-flaky"
            t["register"] = e.get("issue")

    n = {k: sum(1 for t in tests if t["outcome"] == k) for k in ("passed", "flaky", "failed", "skipped", "known-flaky")}
    failed = [t for t in tests if t["required"] and t["outcome"] in ("failed", "skipped")]
    verdict = "red" if problems or failed else "green"
    first = next((j for j in jobs if j), {}) or {}
    return {
        "schema": 1,
        "kind": kind,
        "shard": plan_["shard"],
        "commit": first.get("commit"),
        "tree": first.get("tree"),
        "startedUtc": min((j.get("startedUtc") or "~") for j in jobs if j) if any(jobs) else None,
        "runUrl": run_url,
        # full-tier evidence: the whole ci,default tier, every class run, nothing left out but the register's tests
        "fullTier": plan_["shard"] == g.FULL_TIER and not problems
        and all(e.lower() in known_flaky for e in excluded_l),
        "excluded": sorted(set(excluded)),
        "jobs": job_rows,
        "counts": dict(n, total=len(tests), tierTests=plan_["tierTests"]),
        "tests": sorted(tests, key=lambda t: t["name"].lower()),
        "problems": problems,
        "verdict": verdict,
    }


def report(rec: dict) -> str:
    """The Markdown a person reads: the job summary and the nightly-red issue's body."""
    c = rec["counts"]
    lines = ["**%s** at `%s` (gate tree `%s`)%s" % (
        rec["verdict"].upper(), (rec.get("commit") or "?")[:12], (rec.get("tree") or "?")[:12],
        (" -- [the run](%s)" % rec["runUrl"]) if rec.get("runUrl") else ""), "",
        "%d tests of the tier's %d: %d passed, %d flaky (failed, then passed alone), %d failed, %d skipped, "
        "%d known-flaky failures (the register)." % (c["total"], c["tierTests"], c["passed"], c["flaky"], c["failed"],
                                                     c["skipped"], c["known-flaky"])]
    bad = [t for t in rec["tests"] if t["outcome"] in ("failed", "skipped")]
    if bad:
        lines += ["", "### Failing", ""]
        for t in bad:
            retry = t.get("retry") or {}
            how = " (failed again alone)" if retry.get("ran") and not retry.get("passed") else (
                " (its retry did not run)" if retry and not retry.get("ran") else "")
            lines.append("- `%s`%s%s" % (t["name"], how, (": " + _one_line(t["error"])) if t.get("error") else ""))
    flaky = [t for t in rec["tests"] if t["outcome"] in ("flaky", "known-flaky")]
    if flaky:
        lines += ["", "### Flaky this run", ""]
        for t in flaky:
            lines.append("- `%s`%s" % (t["name"], (" (register: %s)" % t["register"]) if t.get("register") else
                                       " (passed alone: a flake if `gate.py report` agrees)"))
    if rec["problems"]:
        lines += ["", "### The run itself is not trustworthy", ""]
        lines += ["- %s" % p for p in rec["problems"]]
    lines += ["", "| job | tests | result | minutes |", "|---|---|---|---|"]
    for j in rec["jobs"]:
        if j.get("missing"):
            lines.append("| %d | - | no result | - |" % j["index"])
        else:
            lines.append("| %d | %s | %s | %.0f |" % (j["index"], j.get("executed"), j.get("result") or "none",
                                                     (j.get("wallSeconds") or 0) / 60.0))
    return "\n".join(lines) + "\n"


def _one_line(s: str) -> str:
    s = " ".join(str(s).split())
    return s if len(s) < 240 else s[:237] + "..."


def merge_cmd(a) -> int:
    p = g.read_json(a.plan)
    jobs: List[Optional[dict]] = [None] * len(p["jobs"])
    for path in a.jobs:
        f = g.find_file(path, "job.json") if os.path.isdir(path) else path
        if not f or not os.path.exists(f):
            continue
        j = g.read_json(f)
        idx = _job_index(j, len(jobs))
        if idx is not None:
            jobs[idx] = j
    reg = history.load_register(a.register) if a.register and os.path.exists(a.register) else {}
    rec = merge(p, jobs, reg, a.kind, a.run_url)
    g.write_json(a.out, rec)
    text = report(rec)
    if a.report:
        g.write_text(a.report, text)
    print(text)
    return 0 if rec["verdict"] == "green" else 1


def _job_index(j: dict, n: int) -> Optional[int]:
    label = str(j.get("label") or "")
    if label.startswith("job-"):
        try:
            i = int(label[4:])
            return i if 0 <= i < n else None
        except ValueError:
            return None
    return None
