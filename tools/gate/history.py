"""Gate B's run history and the flake register (docs/GATE_B.md, "Run history" and "The flake register").

Every recorded run is ONE FILE on the data-only `gate-data` branch, `runs/<YYYY-MM>/<started>-<kind>-<commit>.json.gz`,
so two workflows that record at once never edit the same file: a push that loses the race rebases and pushes again.
Nothing here needs the network except `publish`; a reader works on any checkout of the branch.

Three questions, answered from the stored runs without re-running anything:
  * Is this test flaky?  It passed AND failed at one gate tree (in one run, a failure that passed alone, or across
    runs of the same code). By definition: the code did not change, the verdict did.
  * What does it cost?   The median of its recorded seconds; a class's cost is what it held a server for.
  * How is it trending?  Its failure rate and median seconds over the recent runs against the runs before them.
"""
from __future__ import annotations

import os
import re
import statistics
import subprocess
import time
from typing import Dict, Iterable, List, Optional, Tuple

import gatelib as g

DATA_BRANCH = "gate-data"
REGISTER = os.path.join(g.ROOT, "corpus", "gate-flakes.json")
RENAMES = os.path.join(g.ROOT, "corpus", "gate-renames.json")
FULL_KINDS = ("nightly", "release", "full")


# ---------------------------------------------------------------------------------------------- storing

def record_path(rec: dict) -> str:
    started = (rec.get("startedUtc") or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    stamp = re.sub(r"[^0-9T]", "", started.split(".")[0])[:15]
    month = "%s-%s" % (stamp[0:4], stamp[4:6])
    commit = (rec.get("commit") or "nocommit")[:10]
    label = re.sub(r"[^a-z0-9-]", "", str(rec.get("label") or "").lower())
    return "runs/%s/%s-%s-%s%s.json.gz" % (month, stamp, rec.get("kind", "run"), commit, ("-" + label) if label else "")


DATA_README = """# gate-data

Data only, written by the workflows; never merged anywhere. See docs/GATE_B.md on the default branch.

- `runs/<YYYY-MM>/<started>-<kind>-<commit>.json.gz`: one Gate B run each (nightly, release, pull request), never
  edited after it is written. Read them with `python3 tools/gate/gate.py report --data <a checkout of this branch>`.
"""


def store(data_dir: str, rec: dict) -> str:
    """Write a run record into a checkout of gate-data (idempotent: the same record lands on the same path)."""
    if not os.path.exists(os.path.join(data_dir, "README.md")):
        g.write_text(os.path.join(data_dir, "README.md"), DATA_README)
    rel = record_path(rec)
    g.write_json(os.path.join(data_dir, rel), rec, compress=True)
    return rel


def publish(data_dir: str, message: str, attempts: int = 5) -> None:
    """Commit what `store` wrote and push it, rebasing over a concurrent writer's push. Files never collide."""
    def run(*a: str) -> subprocess.CompletedProcess:
        return subprocess.run(["git", *a], cwd=data_dir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    run("add", "-A")
    if run("diff", "--cached", "--quiet").returncode == 0:
        print("gate-data: nothing new to record")
        return
    c = run("-c", "user.name=github-actions[bot]", "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
            "commit", "-q", "-m", message)
    if c.returncode != 0:
        raise RuntimeError("gate-data commit failed: " + c.stdout.decode("utf-8", "replace"))
    for i in range(attempts):
        p = run("push", "origin", "HEAD:refs/heads/" + DATA_BRANCH)
        if p.returncode == 0:
            print("gate-data: recorded (%s)" % message)
            return
        time.sleep(2 * (i + 1))
        run("pull", "--rebase", "-q", "origin", DATA_BRANCH)
    raise RuntimeError("gate-data: could not push after %d attempts" % attempts)


# ---------------------------------------------------------------------------------------------- reading

def load_records(data_dir: str) -> List[dict]:
    """Every stored run, oldest first, with renamed tests under their current names."""
    out = []
    base = os.path.join(data_dir, "runs")
    if not os.path.isdir(base):
        return out
    for dirpath, _d, files in os.walk(base):
        for f in files:
            if f.endswith(".json.gz") or f.endswith(".json"):
                try:
                    out.append(g.read_json(os.path.join(dirpath, f)))
                except (OSError, ValueError) as e:
                    print("gate-data: skipping unreadable %s: %s" % (f, e))
    out.sort(key=lambda r: (r.get("startedUtc") or "", r.get("kind") or ""))
    renames = load_renames()
    if renames:
        for r in out:
            for t in r.get("tests", []):
                t["name"] = rename(t["name"], renames)
    return out


def load_renames(path: str = RENAMES) -> Dict[str, str]:
    """old `Class.method` (or `Class`) -> new, lower-cased keys: a test renamed keeps its history."""
    if not os.path.exists(path):
        return {}
    raw = g.read_json(path).get("renames", {})
    return {k.lower(): v for k, v in raw.items()}


def rename(name: str, renames: Dict[str, str], depth: int = 0) -> str:
    if depth > 10 or not renames:
        return name
    low = name.lower()
    if low in renames:
        return rename(renames[low], renames, depth + 1)
    cls, _, method = name.partition(".")
    if cls.lower() in renames and method:
        return rename(renames[cls.lower()] + "." + method, renames, depth + 1)
    return name


def latest_costs(data_dir: str) -> Dict[str, float]:
    """Each class's seconds from the most recent full runs (what the planner weighs a class by)."""
    recs = [r for r in load_records(data_dir) if r.get("kind") in FULL_KINDS][-3:]
    known = list(g.scan_tier())
    return g.costs_from_records(recs, known)


def last_green_full(records: Iterable[dict], kinds: Tuple[str, ...] = FULL_KINDS) -> Optional[dict]:
    best = None
    for r in records:
        if r.get("kind") in kinds and r.get("verdict") == "green" and r.get("fullTier"):
            best = r
    return best


def load_register(path: str = REGISTER) -> Dict[str, dict]:
    """The flake register: {lower Class.method: row}. Each row names its issue (docs/GATE_B.md)."""
    if not os.path.exists(path):
        return {}
    return {e["test"].lower(): e for e in g.read_json(path).get("tests", [])}


# ---------------------------------------------------------------------------------------------- the three questions

def outcomes(records: List[dict]) -> Dict[str, List[Tuple[str, str, str, float]]]:
    """test -> [(tree, startedUtc, outcome, seconds)], one row per recorded run, oldest first."""
    out: Dict[str, List[Tuple[str, str, str, float]]] = {}
    for r in records:
        for t in r.get("tests", []):
            out.setdefault(t["name"], []).append(
                (r.get("tree") or "?", r.get("startedUtc") or "", t.get("outcome", "?"), float(t.get("seconds") or 0.0)))
    return out


def flaky(records: List[dict]) -> Dict[str, dict]:
    """Tests that passed AND failed at one gate tree. A `flaky` outcome is both at once (failed, then passed alone)."""
    out: Dict[str, dict] = {}
    for name, rows in outcomes(records).items():
        by_tree: Dict[str, set] = {}
        for tree, _s, outcome, _sec in rows:
            res = by_tree.setdefault(tree, set())
            if outcome in ("flaky", "known-flaky"):
                res.update(("passed", "failed"))
            elif outcome in ("passed", "failed"):
                res.add(outcome)
        trees = sorted(t for t, res in by_tree.items() if {"passed", "failed"} <= res)
        if trees:
            fails = sum(1 for r in rows if r[2] in ("failed", "flaky", "known-flaky"))
            out[name] = {"trees": trees, "runs": len(rows), "failures": fails, "last": rows[-1][1]}
    return out


def class_costs(records: List[dict]) -> Dict[str, dict]:
    """Per class: median seconds it held a server for, over the full runs that ran it."""
    per: Dict[str, List[float]] = {}
    known = list(g.scan_tier())
    for r in records:
        if r.get("kind") not in FULL_KINDS:
            continue
        for cls, sec in g.costs_from_records([r], known).items():
            per.setdefault(cls, []).append(sec)
    return {c: {"median": statistics.median(v), "runs": len(v), "last": v[-1]} for c, v in per.items()}


def trend(records: List[dict], window: int = 7) -> Dict[str, dict]:
    """Per test: failure rate and median seconds in the last `window` runs against the `window` before them."""
    out: Dict[str, dict] = {}
    for name, rows in outcomes(records).items():
        recent, before = rows[-window:], rows[-2 * window:-window]
        if not before:
            continue

        def rate(rs):
            return sum(1 for r in rs if r[2] in ("failed", "flaky", "known-flaky")) / float(len(rs))

        def med(rs):
            return statistics.median([r[3] for r in rs])
        out[name] = {"failRecent": rate(recent), "failBefore": rate(before), "secRecent": med(recent),
                     "secBefore": med(before)}
    return out


def report(records: List[dict], top: int = 15, register: Optional[Dict[str, dict]] = None) -> str:
    register = register if register is not None else load_register()
    lines = ["%d recorded runs (%s .. %s)" % (len(records), records[0].get("startedUtc", "?") if records else "-",
                                              records[-1].get("startedUtc", "?") if records else "-"), ""]
    fl = flaky(records)
    lines.append("FLAKY: passed and failed at one gate tree (%d)" % len(fl))
    for name, f in sorted(fl.items(), key=lambda kv: (-kv[1]["failures"], kv[0])):
        reg = register.get(name.lower())
        lines.append("  %-70s %d failure(s) in %d run(s), %d tree(s)%s" % (
            name, f["failures"], f["runs"], len(f["trees"]), ("  register: " + reg.get("issue", "?")) if reg else
            "  NOT IN THE REGISTER"))
    stale = sorted(k for k in register if k not in {n.lower() for n in fl})
    if stale:
        lines.append("  in the register but never seen flaky in these runs: " + ", ".join(stale))
    lines += ["", "SLOWEST CLASSES: median seconds a class holds a server for (full runs)"]
    costs = class_costs(records)
    for cls, c in sorted(costs.items(), key=lambda kv: -kv[1]["median"])[:top]:
        lines.append("  %8.1f s  %-50s (%d runs, last %.1f s)" % (c["median"], cls, c["runs"], c["last"]))
    lines += ["", "TRENDING: the last 7 runs against the 7 before them"]
    tr = trend(records)
    worse = sorted(((n, t) for n, t in tr.items() if t["failRecent"] > t["failBefore"]),
                   key=lambda kv: -(kv[1]["failRecent"] - kv[1]["failBefore"]))[:top]
    slower = sorted(((n, t) for n, t in tr.items() if t["secBefore"] > 1 and t["secRecent"] > 1.5 * t["secBefore"]),
                    key=lambda kv: -(kv[1]["secRecent"] - kv[1]["secBefore"]))[:top]
    lines += ["  failing more: %s %.0f%% (was %.0f%%)" % (n, 100 * t["failRecent"], 100 * t["failBefore"])
              for n, t in worse] or ["  failing more: none"]
    lines += ["  slower:       %s %.1f s (was %.1f s)" % (n, t["secRecent"], t["secBefore"]) for n, t in slower] \
        or ["  slower:       none"]
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------------------------- pull request runs

ARTIFACT = "gate-run-record"


def pr_record(job: dict, run: dict) -> dict:
    """A pull request's Gate B job result as a stored record: the job's own rows, said to be a PR run."""
    rec = dict(job)
    prs = run.get("pull_requests") or []
    rec["kind"] = "pr"
    rec["label"] = "pr%s" % prs[0]["number"] if prs else "run%s" % run.get("id")
    rec["runUrl"] = run.get("html_url", "")
    rec["fullTier"] = False   # a pull request's run is never full-tier evidence, whatever it selected
    return rec


def harvest(data_dir: str, repo: str, workflow: str = "ci.yml", days: int = 3) -> List[str]:
    """Store the Gate B record of every pull request run of `workflow` that finished in the last `days` days.

    A pull request's token cannot write gate-data (and a fork's never could), so the PR job only uploads its result as
    an artifact (`gate-run-record`) and the nightly, which can write, collects them. Idempotent: a record lands on the
    same path however often it is collected.
    """
    import io
    import json
    import zipfile
    since = time.strftime("%Y-%m-%d", time.gmtime(time.time() - days * 86400))

    def gh(path: str) -> bytes:
        out = subprocess.run(["gh", "api", "-H", "Accept: application/vnd.github+json", path],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if out.returncode != 0:
            raise RuntimeError("gh api %s: %s" % (path, out.stderr.decode("utf-8", "replace")))
        return out.stdout

    stored = []
    page = 1
    while True:
        runs = json.loads(gh("repos/%s/actions/workflows/%s/runs?status=completed&created=%%3E%%3D%s&per_page=100&page=%d"
                             % (repo, workflow, since, page)).decode("utf-8")).get("workflow_runs", [])
        for run in runs:
            if run.get("event") != "pull_request":
                continue
            arts = json.loads(gh("repos/%s/actions/runs/%d/artifacts" % (repo, run["id"])).decode("utf-8"))
            for art in arts.get("artifacts", []):
                if art.get("name") != ARTIFACT or art.get("expired"):
                    continue
                z = zipfile.ZipFile(io.BytesIO(gh("repos/%s/actions/artifacts/%d/zip" % (repo, art["id"]))))
                names = sorted((n for n in z.namelist() if n.split("/")[-1] == "job.json"), key=lambda n: n.count("/"))
                if not names:
                    continue
                job = json.loads(z.read(names[0]).decode("utf-8"))
                stored.append(store(data_dir, pr_record(job, run)))
        if len(runs) < 100:
            break
        page += 1
    return stored
