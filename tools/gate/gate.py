#!/usr/bin/env python3
"""Gate B's tools, one command (docs/GATE_B.md).

    python3 tools/gate/gate.py run --shard ci,default [--classes A,B] [--not-classes C] --out DIR
                                                       one server: run, check the count, retry a failure by itself
    python3 tools/gate/gate.py plan --shard ci,default --jobs 4 [--history DIR]
                                                       share the tier among parallel jobs (a GitHub matrix)
    python3 tools/gate/gate.py merge --kind nightly JOBDIR...   one verdict and one run record from a split run's jobs
    python3 tools/gate/gate.py should-run --data DIR    has the code changed since the last green nightly?
    python3 tools/gate/gate.py record --data DIR [--publish] RECORD...   store run records on the gate-data branch
    python3 tools/gate/gate.py issue --record R --report MD   open, update or close the nightly-red issue
    python3 tools/gate/gate.py report --data DIR       flaky tests, the slowest classes, the trend
    python3 tools/gate/gate.py select --base origin/main [--map MAP]   targeted Gate B: the classes a change reaches
    python3 tools/gate/gate.py covmap-plan|covmap-merge|covmap-compare   the coverage map (docs/GATE_B.md)
    python3 tools/gate/gate.py tree [REV]              the gate-relevant tree of a commit (what a result belongs to)

Python 3.9+, standard library only.
"""
from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import gatelib as g  # noqa: E402


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="gate.py", description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("run", help="one Gate B job")
    r.add_argument("--shard", default=g.FULL_TIER)
    r.add_argument("--classes", help="comma-separated GameTest classes (or Class.method) to run")
    r.add_argument("--classes-file")
    r.add_argument("--not-classes", help="run every class EXCEPT these")
    r.add_argument("--not-classes-file")
    r.add_argument("--exclude", help="Class.method tests to leave out")
    r.add_argument("--flake-register", help="leave out the register's tests (corpus/gate-flakes.json)")
    r.add_argument("--no-retry", dest="retry", action="store_false")
    r.add_argument("--timeout", type=float, default=80.0, help="minutes one server may take")
    r.add_argument("--kind", default="local")
    r.add_argument("--label", default="")
    r.add_argument("--out", required=True)
    r.add_argument("--gradle-arg", action="append", help="one more argument for Gradle")
    r.add_argument("--plan", help="a plan from `gate.py plan`: run its job --job")
    r.add_argument("--job", type=int, default=0)
    r.add_argument("--covmap-tools", help="a coverage-mapping run: the directory `./gradlew covmapTools` filled")
    r.add_argument("--no-python", action="store_true",
                   help="run the game with python and python3 replaced by stubs that fail; red if one was started")

    pl = sub.add_parser("plan", help="share the tier among parallel jobs")
    pl.add_argument("--shard", default=g.FULL_TIER)
    pl.add_argument("--jobs", type=int, default=4)
    pl.add_argument("--history", help="a gate-data checkout: weigh classes by their last measured cost")
    pl.add_argument("--out", help="write the plan here (JSON); the matrix goes to stdout")

    m = sub.add_parser("merge", help="one verdict from a split run's jobs")
    m.add_argument("--kind", default="nightly")
    m.add_argument("--plan", required=True)
    m.add_argument("--run-url", default="")
    m.add_argument("--register", default=os.path.join(g.ROOT, "corpus", "gate-flakes.json"))
    m.add_argument("--out", required=True, help="the merged run record (JSON)")
    m.add_argument("--report", help="a Markdown report of the run")
    m.add_argument("jobs", nargs="+", help="each job's job.json, or the directory holding it")

    sr = sub.add_parser("should-run", help="has the code changed since the last green full run? (prints true/false)")
    sr.add_argument("--data", required=True, help="a checkout of the gate-data branch (may be empty)")
    sr.add_argument("--kind", default="nightly")

    rc = sub.add_parser("record", help="store a run record on a gate-data checkout, and push it with --publish")
    rc.add_argument("--data", required=True)
    rc.add_argument("--publish", action="store_true")
    rc.add_argument("--harvest", metavar="REPO", help="also collect the pull request runs' records (owner/repo)")
    rc.add_argument("--days", type=int, default=3)
    rc.add_argument("records", nargs="*")

    iss = sub.add_parser("issue", help="open, update or close the one issue that says a scheduled run is red")
    iss.add_argument("--record", required=True)
    iss.add_argument("--report", required=True)
    iss.add_argument("--label", default="nightly-red")
    iss.add_argument("--title", default="Nightly: the full GameTest tier is red")
    iss.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    iss.add_argument("--dry-run", action="store_true", help="print what it would do")

    rp = sub.add_parser("report", help="flaky tests, the slowest classes and the trend, from the stored runs")
    rp.add_argument("--data", required=True, help="a checkout of the gate-data branch")
    rp.add_argument("--top", type=int, default=15)

    cp = sub.add_parser("covmap-plan", help="which classes a coverage-mapping run maps, shared among jobs")
    cp.add_argument("--mode", choices=["full", "partial"], default="full")
    cp.add_argument("--map", help="the current map (map.json.gz); absent, a partial run maps everything")
    cp.add_argument("--jobs", type=int, default=6)
    cp.add_argument("--replicas", type=int, default=1, help="map each share this many times (2: the determinism check)")
    cp.add_argument("--costs", help="costs.json: weigh classes by their measured cost under coverage")
    cp.add_argument("--out", required=True)

    cmg = sub.add_parser("covmap-merge", help="build the map from a mapping run's jobs")
    cmg.add_argument("--plan", required=True)
    cmg.add_argument("--map", help="the previous map, if any")
    cmg.add_argument("--costs", help="the previous costs.json, if any")
    cmg.add_argument("--out", required=True, help="a directory: map.json.gz, costs.json, report.md, problems.json")
    cmg.add_argument("--run-url", default="")
    cmg.add_argument("jobs", nargs="+", help="each job's directory (job.json, analysis.json)")

    cc = sub.add_parser("covmap-compare", help="two maps of one commit: equal but for costs and the listed classes")
    cc.add_argument("a")
    cc.add_argument("b")

    se = sub.add_parser("select", help="targeted Gate B: the GameTest classes a change can reach, and why")
    se.add_argument("--base", required=True, help="the branch the change goes into (its merge base is the base)")
    se.add_argument("--head", default="HEAD")
    se.add_argument("--map", help="the coverage map (map.json.gz); absent, the whole tier")
    se.add_argument("--out", help="the selection, JSON")
    se.add_argument("--classes-out", help="the classes for --classes-file (empty: the whole tier)")

    bt = sub.add_parser("backtest", help="the selection over past pull requests")
    bt.add_argument("--prs", required=True, help="JSON: [{n, title, base, head}]")
    bt.add_argument("--map", help="the coverage map")
    bt.add_argument("--reds", help="JSON: [{pr, test}] tests that went red because of that pull request")
    bt.add_argument("--now", type=float, help="the clock the map's age is judged by (seconds)")
    bt.add_argument("--out")

    t = sub.add_parser("tree", help="the gate-relevant tree of a commit")
    t.add_argument("rev", nargs="?", default="HEAD")

    a = p.parse_args(argv)
    if a.cmd == "run":
        import run
        return run.run_job(a)
    if a.cmd == "plan":
        import nightly
        return nightly.plan_cmd(a)
    if a.cmd == "merge":
        import nightly
        return nightly.merge_cmd(a)
    if a.cmd == "should-run":
        import history
        last = history.last_green_full(history.load_records(a.data), (a.kind,))
        tree = g.gate_tree()
        if last and last.get("tree") == tree:
            print("false")
            print("gate: the gate-relevant tree %s had a green %s run at %s (%s); nothing to do" % (
                tree[:12], a.kind, (last.get("commit") or "?")[:12], last.get("startedUtc")), file=sys.stderr)
        else:
            print("true")
            print("gate: tree %s %s" % (tree[:12], "is new since the last green %s run (%s)" % (
                a.kind, (last.get("commit") or "?")[:12]) if last else "has no green %s run on record" % a.kind),
                file=sys.stderr)
        return 0
    if a.cmd == "record":
        import history
        for f in a.records:
            print("gate-data: " + history.store(a.data, g.read_json(f)))
        if a.harvest:
            for rel in history.harvest(a.data, a.harvest, days=a.days):
                print("gate-data: (a pull request run) " + rel)
        if a.publish:
            history.publish(a.data, "Record %d run(s)%s" % (len(a.records), " and the pull requests' runs" if a.harvest else ""))
        return 0
    if a.cmd == "issue":
        import issues
        return issues.sync(a)
    if a.cmd == "report":
        import history
        print(history.report(history.load_records(a.data), a.top))
        return 0
    if a.cmd in ("covmap-plan", "covmap-merge", "covmap-compare"):
        import covmap
        return {"covmap-plan": covmap.plan_cmd, "covmap-merge": covmap.merge_cmd,
                "covmap-compare": covmap.compare_cmd}[a.cmd](a)
    if a.cmd == "select":
        import selection
        return selection.select_cmd(a)
    if a.cmd == "backtest":
        import selection
        return selection.backtest_cmd(a)
    if a.cmd == "tree":
        print(g.gate_tree(a.rev))
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
