"""The one issue a red scheduled run keeps open (docs/GATE_B.md, "When a night is red").

Red: open the issue labelled `--label` (or update the open one: its body becomes this run's report, and a comment
names the new commit). Green: comment and close it. Only `gh api` (REST) with the workflow's GITHUB_TOKEN.
"""
from __future__ import annotations

import json
import subprocess
from typing import List, Optional

import gatelib as g


def plan_action(verdict: str, open_numbers: List[int]) -> str:
    """What to do: `open`, `update`, `close` or `nothing`. Pure, so the rule is pinned by a test."""
    if verdict == "red":
        return "update" if open_numbers else "open"
    return "close" if open_numbers else "nothing"


def comment_text(rec: dict, action: str) -> str:
    commit = (rec.get("commit") or "?")[:12]
    url = rec.get("runUrl") or ""
    c = rec.get("counts", {})
    if action == "close":
        return "Green again at `%s`: %d tests, %d flaky. Closing. %s" % (commit, c.get("total", 0), c.get("flaky", 0), url)
    failing = [t["name"] for t in rec.get("tests", []) if t.get("outcome") in ("failed", "skipped")]
    return "Still red at `%s`: %d failing%s, %d problem(s) with the run itself. %s" % (
        commit, len(failing), (" (" + ", ".join("`%s`" % f for f in failing[:10]) + (", ..." if len(failing) > 10 else "")
                               + ")") if failing else "", len(rec.get("problems", [])), url)


def _gh(args: List[str], body: Optional[dict] = None) -> object:
    cmd = ["gh", "api"] + args
    inp = None
    if body is not None:
        cmd += ["--input", "-"]
        inp = json.dumps(body).encode("utf-8")
    out = subprocess.run(cmd, input=inp, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if out.returncode != 0:
        raise RuntimeError("gh api %s: %s" % (" ".join(args[:2]), out.stderr.decode("utf-8", "replace")))
    text = out.stdout.decode("utf-8")
    return json.loads(text) if text.strip() else None


def sync(a) -> int:
    rec = g.read_json(a.record)
    body = g.read_text(a.report)
    repo = a.repo
    if a.dry_run:
        print("gate issue (dry run): verdict %s; would %s the `%s` issue" % (
            rec["verdict"], plan_action(rec["verdict"], []) + "/update", a.label))
        return 0
    found = _gh(["-X", "GET", "repos/%s/issues" % repo, "-f", "labels=" + a.label, "-f", "state=open",
                 "-f", "per_page=100"]) or []
    numbers = sorted(i["number"] for i in found if "pull_request" not in i)
    action = plan_action(rec["verdict"], numbers)
    print("gate issue: verdict %s, open %s -> %s" % (rec["verdict"], numbers or "none", action))
    if action == "open":
        try:
            _gh(["-X", "POST", "repos/%s/labels" % repo], {"name": a.label, "color": "b60205",
                                                          "description": "A scheduled full Gate B run is red"})
        except RuntimeError:
            pass   # it exists
        made = _gh(["-X", "POST", "repos/%s/issues" % repo], {"title": a.title, "body": body, "labels": [a.label]})
        print("gate issue: opened #%s" % made["number"])
    elif action == "update":
        n = numbers[0]
        _gh(["-X", "PATCH", "repos/%s/issues/%d" % (repo, n)], {"body": body})
        _gh(["-X", "POST", "repos/%s/issues/%d/comments" % (repo, n)], {"body": comment_text(rec, "update")})
        print("gate issue: updated #%d" % n)
    elif action == "close":
        for n in numbers:
            _gh(["-X", "POST", "repos/%s/issues/%d/comments" % (repo, n)], {"body": comment_text(rec, "close")})
            _gh(["-X", "PATCH", "repos/%s/issues/%d" % (repo, n)], {"state": "closed", "state_reason": "completed"})
            print("gate issue: closed #%d" % n)
    return 0
