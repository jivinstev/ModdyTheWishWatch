"""The release gate: a release needs a green FULL-TIER run of the code it releases (docs/RELEASING.md, docs/GATE_B.md).

`evidence` looks for one in the run history (the gate-data branch): a run of kind nightly or release, green, that ran
the whole ci,default tier (`fullTier`: every class, no class filter, nothing left out but the flake register's tests),
at the same gate tree as the commit being released. A targeted run, a `ci`-only run, a pull request's run, a run of
other code: none of them is evidence.

`release-gate` is what Port CI's release runs (its optional release-gate hook, `.github/port-release-gate.json`): if
there is evidence it passes at once; otherwise it starts the full tier itself (the Nightly workflow, dispatched on the
release branch with kind=release: split across runners, the flake register's tests left out and named), waits for it,
and passes only if that run is green for this very tree. A refusal says why in one line.

It also needs the PLATFORM SMOKE of the same tree (`.github/workflows/platform-smoke.yml`, docs/RELEASING.md): a
`platform` record on gate-data whose required legs (PLATFORM_REQUIRED: the release jar, the Windows x64 and the Linux
arm64 server legs) all succeeded. macOS and the Windows client are reported, never required. The same reuse rule: a
green record of this gate tree (a weekly run on main, an earlier release attempt) passes at once; without one the gate
dispatches the smoke on the release ref, alongside the tier, and waits for both.
"""
from __future__ import annotations

import calendar
import json
import os
import subprocess
import time
from typing import Callable, Dict, Iterable, List, Optional, Tuple

import gatelib as g
import history

EVIDENCE_KINDS = ("nightly", "release")


def tree_now(rec: dict) -> Optional[str]:
    """The record's gate tree by TODAY's definition (gatelib.NOT_GATE_B), recomputed from its commit when that commit is
    in this clone: a record written before the definition changed still names the code it tested."""
    commit = rec.get("commit")
    if commit:
        try:
            return g.gate_tree(commit)
        except Exception:  # noqa: BLE001 - not fetched here: the tree it recorded
            pass
    return rec.get("tree")


def is_evidence(rec: dict, tree: str, register: Dict[str, dict],
                tree_of: Callable[[dict], Optional[str]] = lambda r: r.get("tree")) -> Optional[str]:
    """None when `rec` is full-tier evidence for `tree`, else why not (one line)."""
    if rec.get("kind") not in EVIDENCE_KINDS:
        return "a %s run is not a full-tier run" % rec.get("kind")
    if tree_of(rec) != tree:
        return "a run of other code (gate tree %s)" % (tree_of(rec) or "?")[:12]
    if rec.get("shard") != g.FULL_TIER:
        return "ran the %s shard(s), not the whole tier" % rec.get("shard")
    if not rec.get("fullTier"):
        return "did not run the whole tier (a class filter, a missing job, or a short count)"
    if rec.get("verdict") != "green":
        return "was red"
    stray = [e for e in rec.get("excluded", []) if e.lower() not in register]
    if stray:
        return "left out tests that are not on the flake register: %s" % ", ".join(stray)
    return None


def find_evidence(records: Iterable[dict], tree: str, register: Dict[str, dict],
                  tree_of: Callable[[dict], Optional[str]] = lambda r: r.get("tree")) -> Optional[dict]:
    best = None
    for r in records:
        if r.get("kind") in EVIDENCE_KINDS and is_evidence(r, tree, register, tree_of) is None:
            best = r
    return best


PLATFORM_KIND = "platform"
# The legs a release needs green, by job name in platform-smoke.yml. Everything else the smoke runs is reported only.
PLATFORM_REQUIRED = ("release jar", "server (windows-latest)", "server (ubuntu-24.04-arm)")


def is_platform_evidence(rec: dict, tree: str,
                         tree_of: Callable[[dict], Optional[str]] = lambda r: r.get("tree")) -> Optional[str]:
    """None when `rec` is a platform smoke whose required legs all passed for `tree`, else why not (one line)."""
    if rec.get("kind") != PLATFORM_KIND:
        return "a %s run is not a platform smoke" % rec.get("kind")
    if tree_of(rec) != tree:
        return "a platform smoke of other code (gate tree %s)" % (tree_of(rec) or "?")[:12]
    legs = rec.get("legs") or {}
    bad = ["%s %s" % (n, legs.get(n) or "did not run") for n in PLATFORM_REQUIRED if legs.get(n) != "success"]
    if bad:
        return "a required platform leg was not green: " + ", ".join(bad)
    return None


def find_platform_evidence(records: Iterable[dict], tree: str,
                           tree_of: Callable[[dict], Optional[str]] = lambda r: r.get("tree")) -> Optional[dict]:
    best = None
    for r in records:
        if r.get("kind") == PLATFORM_KIND and is_platform_evidence(r, tree, tree_of) is None:
            best = r
    return best


def platform_record(run: dict, jobs: List[dict], commit: str, tree: str) -> dict:
    """The gate-data record of one platform smoke run: each job's conclusion by name (the verdict is the reader's:
    is_platform_evidence, against PLATFORM_REQUIRED)."""
    legs = {j["name"]: j.get("conclusion") for j in jobs if j.get("name") != "record"}
    green = all(legs.get(n) == "success" for n in PLATFORM_REQUIRED)
    return {"schema": 1, "kind": PLATFORM_KIND, "commit": commit, "tree": tree,
            "startedUtc": run.get("run_started_at") or run.get("created_at"), "runUrl": run.get("html_url"),
            "ref": run.get("head_branch"), "legs": legs, "required": list(PLATFORM_REQUIRED),
            "verdict": "green" if green else "red"}


def evidence_cmd(a) -> int:
    tree = g.gate_tree(a.rev)
    reg = history.load_register()
    recs = history.load_records(a.data)
    ev = find_evidence(recs, tree, reg, tree_now)
    if ev:
        print("gate evidence: a green full-tier %s run at %s covers gate tree %s (%s)" % (
            ev["kind"], (ev.get("commit") or "?")[:12], tree[:12], ev.get("runUrl", "")))
        return 0
    near = [r for r in recs if r.get("kind") in EVIDENCE_KINDS and tree_now(r) == tree]
    why = "; ".join(sorted({is_evidence(r, tree, reg, tree_now) or "" for r in near})) or "no run of this code is on record"
    print("gate evidence: NONE for gate tree %s: %s" % (tree[:12], why))
    return 3


# ---------------------------------------------------------------------------------------------- the gate

def gh_json(args: List[str], body: Optional[dict] = None):
    cmd = ["gh", "api"] + args
    inp = json.dumps(body).encode("utf-8") if body is not None else None
    if body is not None:
        cmd += ["--input", "-"]
    out = subprocess.run(cmd, input=inp, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if out.returncode != 0:
        raise RuntimeError("gh api %s: %s" % (" ".join(args[:3]), out.stderr.decode("utf-8", "replace").strip()))
    text = out.stdout.decode("utf-8")
    return json.loads(text) if text.strip() else None


def fetch_data(dest: str) -> None:
    """A fresh checkout of the gate-data branch (empty when there is none yet)."""
    if os.path.isdir(dest):
        subprocess.run(["git", "worktree", "remove", "--force", dest], cwd=g.ROOT)
    if subprocess.run(["git", "fetch", "--quiet", "origin", history.DATA_BRANCH], cwd=g.ROOT).returncode == 0:
        subprocess.run(["git", "worktree", "add", "--quiet", "--detach", dest, "FETCH_HEAD"], cwd=g.ROOT, check=True)
    else:
        os.makedirs(dest, exist_ok=True)


class Need:
    """One thing a release needs: evidence in the records, or a workflow run that makes it."""

    def __init__(self, what: str, find: Callable[[List[dict]], Optional[dict]], dispatch: Callable[[], None],
                 latest_run: Callable[[float], Optional[dict]]):
        self.what, self.find, self.dispatch, self.latest_run = what, find, dispatch, latest_run
        self.run: Optional[dict] = None


def gate(repo: str, rev: str, ref: str, data_dir: str, wait_min: float,
         dispatch: Callable[[], None], latest_run: Callable[[float], Optional[dict]],
         refetch: Callable[[], List[dict]], sleep: Callable[[float], None] = time.sleep,
         now: Callable[[], float] = time.time,
         tree_of: Callable[[dict], Optional[str]] = lambda r: r.get("tree"),
         platform: Optional[Tuple[Callable[[], None], Callable[[float], Optional[dict]]]] = None) -> int:
    """The decision, with the GitHub calls handed in (so a test can pin it). 0 = release, 1 = refuse.
    `platform` (its dispatch and latest_run): also require the platform smoke of this tree, run alongside the tier."""
    tree = g.gate_tree(rev)
    reg = history.load_register()
    needs = [Need("the full tier", lambda recs: find_evidence(recs, tree, reg, tree_of), dispatch, latest_run)]
    if platform is not None:
        needs.append(Need("the platform smoke", lambda recs: find_platform_evidence(recs, tree, tree_of), *platform))
    recs = refetch()
    missing = []
    for n in needs:
        ev = n.find(recs)
        if ev:
            print("release gate: %s is covered for gate tree %s by the %s run at %s (%s)" % (
                n.what, tree[:12], ev["kind"], (ev.get("commit") or "?")[:12], ev.get("runUrl", "")))
        else:
            missing.append(n)
    if not missing:
        print("release gate: PASS")
        return 0
    started = now()
    for n in missing:
        print("release gate: nothing covers %s for gate tree %s; running it on %s" % (n.what, tree[:12], ref))
        n.dispatch()
    deadline = started + wait_min * 60
    while now() < deadline:
        sleep(30)
        for n in missing:
            if not (n.run and n.run.get("status") == "completed"):
                n.run = n.latest_run(started)
        if all(n.run and n.run.get("status") == "completed" for n in missing):
            break
    late = [n for n in missing if not (n.run and n.run.get("status") == "completed")]
    if late:
        print("release gate: REFUSED, %s did not finish in %d minutes (%s)" % (
            " and ".join(n.what for n in late), wait_min,
            ", ".join((n.run or {}).get("html_url", "no run started") for n in late)))
        return 1
    recs = refetch()
    red = [n for n in missing if not n.find(recs)]
    if red:
        print("release gate: REFUSED, %s not green for this tree: %s" % (
            " and ".join(n.what for n in red),
            "; ".join("%s (%s)" % (n.run.get("conclusion"), n.run.get("html_url", "")) for n in red)))
        return 1
    print("release gate: PASS, %s ran green for this tree (%s)" % (
        " and ".join(n.what for n in missing), ", ".join(n.run.get("html_url", "") for n in missing)))
    return 0


def release_gate_cmd(a) -> int:
    repo, ref = a.repo, a.ref

    def dispatch() -> None:
        gh_json(["-X", "POST", "repos/%s/actions/workflows/%s/dispatches" % (repo, a.workflow)],
                {"ref": ref, "inputs": {"force": "true", "kind": "release"}})

    def latest_run(since: float) -> Optional[dict]:
        runs = gh_json(["-X", "GET", "repos/%s/actions/workflows/%s/runs" % (repo, a.workflow),
                        "-f", "event=workflow_dispatch", "-f", "branch=" + ref, "-f", "per_page=10"]) or {}
        for r in runs.get("workflow_runs", []):
            created = calendar.timegm(time.strptime(r["created_at"], "%Y-%m-%dT%H:%M:%SZ"))
            if created >= since - 120:
                return r
        return None

    def refetch() -> List[dict]:
        fetch_data(a.data)
        return history.load_records(a.data)

    def runs_of(workflow: str) -> Callable[[float], Optional[dict]]:
        def latest(since: float) -> Optional[dict]:
            runs = gh_json(["-X", "GET", "repos/%s/actions/workflows/%s/runs" % (repo, workflow),
                            "-f", "event=workflow_dispatch", "-f", "branch=" + ref, "-f", "per_page=10"]) or {}
            for r in runs.get("workflow_runs", []):
                created = calendar.timegm(time.strptime(r["created_at"], "%Y-%m-%dT%H:%M:%SZ"))
                if created >= since - 120:
                    return r
            return None
        return latest

    platform = None
    if a.platform_workflow:
        def dispatch_platform() -> None:
            gh_json(["-X", "POST", "repos/%s/actions/workflows/%s/dispatches" % (repo, a.platform_workflow)],
                    {"ref": ref, "inputs": {"ref": a.rev, "client": "false"}})
        platform = (dispatch_platform, runs_of(a.platform_workflow))

    return gate(repo, a.rev, ref, a.data, a.wait, dispatch, latest_run, refetch, tree_of=tree_now, platform=platform)


def record_platform_cmd(a) -> int:
    """platform-smoke.yml's last job: its run's legs as a `platform` record (gate.py record publishes it)."""
    repo, run_id = a.repo, a.run_id
    run = gh_json(["repos/%s/actions/runs/%s" % (repo, run_id)])
    jobs = (gh_json(["repos/%s/actions/runs/%s/jobs?per_page=100" % (repo, run_id)]) or {}).get("jobs", [])
    rec = platform_record(run, jobs, a.commit, g.gate_tree(a.commit))
    g.write_json(a.out, rec)
    legs = rec["legs"]
    print("platform smoke of %s (gate tree %s): %s -- %s" % (a.commit[:12], rec["tree"][:12], rec["verdict"].upper(),
          ", ".join("%s %s" % (k, v) for k, v in sorted(legs.items()))))
    return 0
