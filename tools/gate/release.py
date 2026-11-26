"""The release gate: a release needs a green FULL-TIER run of the code it releases (docs/RELEASING.md, docs/GATE_B.md).

`evidence` looks for one in the run history (the gate-data branch): a run of kind nightly or release, green, that ran
the whole ci,default tier (`fullTier`: every class, no class filter, nothing left out but the flake register's tests),
at the same gate tree as the commit being released. A targeted run, a `ci`-only run, a pull request's run, a run of
other code: none of them is evidence.

`release-gate` is what Port CI's release runs (its optional release-gate hook, `.github/port-release-gate.json`): if
there is evidence it passes at once; otherwise it starts the full tier itself (the Nightly workflow, dispatched on the
release branch with kind=release: split across runners, the flake register's tests left out and named), waits for it,
and passes only if that run is green for this very tree. A refusal says why in one line.
"""
from __future__ import annotations

import calendar
import json
import os
import subprocess
import time
from typing import Callable, Dict, Iterable, List, Optional

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


def gate(repo: str, rev: str, ref: str, data_dir: str, wait_min: float,
         dispatch: Callable[[], None], latest_run: Callable[[float], Optional[dict]],
         refetch: Callable[[], List[dict]], sleep: Callable[[float], None] = time.sleep,
         now: Callable[[], float] = time.time,
         tree_of: Callable[[dict], Optional[str]] = lambda r: r.get("tree")) -> int:
    """The decision, with the GitHub calls handed in (so a test can pin it). 0 = release, 1 = refuse."""
    tree = g.gate_tree(rev)
    reg = history.load_register()
    ev = find_evidence(refetch(), tree, reg, tree_of)
    if ev:
        print("release gate: PASS, a green full-tier %s run at %s covers this tree (%s)" % (
            ev["kind"], (ev.get("commit") or "?")[:12], ev.get("runUrl", "")))
        return 0
    print("release gate: no full-tier run covers gate tree %s; running the whole tier on %s" % (tree[:12], ref))
    started = now()
    dispatch()
    run = None
    deadline = started + wait_min * 60
    while now() < deadline:
        sleep(30)
        run = latest_run(started)
        if run and run.get("status") == "completed":
            break
    if not run or run.get("status") != "completed":
        print("release gate: REFUSED, the full tier did not finish in %d minutes (%s)" % (
            wait_min, (run or {}).get("html_url", "no run started")))
        return 1
    ev = find_evidence(refetch(), tree, reg, tree_of)
    if ev:
        print("release gate: PASS, the full tier ran green for this tree (%s)" % run.get("html_url", ""))
        return 0
    print("release gate: REFUSED, the full tier is not green for this tree: %s (%s)" % (
        run.get("conclusion"), run.get("html_url", "")))
    return 1


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

    return gate(repo, a.rev, ref, a.data, a.wait, dispatch, latest_run, refetch, tree_of=tree_now)
