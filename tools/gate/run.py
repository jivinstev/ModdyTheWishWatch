"""`gate.py run`: one Gate B job: boot a GameTest server on a share of the tier, check it ran what it had, retry each
failed test once by itself, and write the job's result (docs/GATE_B.md, "One job").

The rules it keeps (CLAUDE.md, Gate B):
  * the verdict comes from the log's `N required tests passed|failed` line and the server's own run record, never
    from Gradle's exit code (a server that never ran the tests exits 0);
  * the server must have run every test it registered, and every class this job was given must have run;
  * a failed required test is retried ONCE, by itself (`-Pgametests=Class.method`), and is `flaky` if it then passes;
  * a test that skipped is counted and reported, never a pass.
"""
from __future__ import annotations

import glob
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from typing import Dict, List, Optional

import gatelib as g

# above this many failures, the retries share one server instead of one each (each boot costs a minute or two)
MAX_ALONE = 8

# --no-python: the directory of stub `python`/`python3` scripts put first on the game's PATH, or None. A player's game
# never starts Python (the datapack compiler is in the jar: NoPythonTest, NoPythonGameTest), so a Gate B run proves it:
# a stub records being started and fails, and the job is red if one was.
NO_PYTHON: Optional[str] = None


def no_python_stubs(out_dir: str) -> str:
    """Write the stubs (POSIX shells; on Windows the run says it cannot check) and return their directory."""
    d = os.path.join(out_dir, "no-python")
    os.makedirs(d, exist_ok=True)
    log = os.path.join(out_dir, "python-started")
    if os.path.exists(log):
        os.remove(log)
    for n in ("python", "python3"):
        path = os.path.join(d, n)
        g.write_text(path, '#!/bin/sh\necho "$0 $*" >> "%s"\nexit 127\n' % log)
        os.chmod(path, 0o755)
    return d


def python_started(out_dir: str) -> List[str]:
    log = os.path.join(out_dir, "python-started")
    return [ln for ln in g.read_text(log).splitlines() if ln.strip()] if os.path.exists(log) else []


def gradle_cmd(shard: str, rundir: str, home: str, classes: List[str], not_classes: List[str],
               exclude: List[str], extra: List[str]) -> List[str]:
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    cmd = [wrapper, "runGameTestServer", "--console=plain", "-Pshard=" + shard, "-Prundir=" + rundir,
           "-Ptestprops=home=" + home]
    if classes:
        cmd.append("-Pgametests=" + ",".join(classes))
    if not_classes:
        cmd.append("-Pgatenotclasses=" + ",".join(not_classes))
    if exclude:
        cmd.append("-Pgateexclude=" + ",".join(exclude))
    return cmd + list(extra)


# What a failed DOWNLOAD looks like in Gradle's output (docs/GATE_B.md, "Downloads in CI"): a dependency it could not
# resolve or fetch, the Mojang asset download (`:downloadAssets`), a busy or broken server (429, 5xx), a timeout or a
# dropped connection. Only these are worth running again: the same build would fail the same way for anything else.
DOWNLOAD = re.compile(
    r"Task :downloadAssets FAILED"
    r"|Execution failed for task ':downloadAssets'"
    r"|Could not resolve "
    r"|Could not GET "
    r"|Could not HEAD "
    r"|not found in any of the following sources"
    r"|[Ss]tatus code (?:429|5\d\d)"
    r"|HTTP/\S+ (?:429|5\d\d)"
    r"|\b(?:429 Too Many Requests|50[234] (?:Bad Gateway|Service Unavailable|Gateway Time-?out))"
    r"|(?:Read|[Cc]onnect) timed out|SocketTimeoutException|ConnectTimeoutException"
    r"|Connection reset|Remote host terminated the handshake|Premature end of Content-Length")

# ... and what says the build got past its downloads and failed on its own terms: never retried for a download
NOT_DOWNLOAD = re.compile(
    r"Task :compile\w* FAILED|Compilation failed|: error: |There were failing tests|Task :test FAILED")

DOWNLOAD_RETRY_SECONDS = 60


def download_failure(log_text: str) -> Optional[str]:
    """The line that says this attempt failed for want of a download before any GameTest server ran, or None.

    None when the server started (a GameTest's own failure is the retry rule's business), when the build failed to
    compile or a test failed, or when nothing in the log is a download."""
    if "gt-adapter:" in log_text or g.RESULT_LINE.search(log_text) or NOT_DOWNLOAD.search(log_text):
        return None
    for ln in log_text.splitlines():
        if DOWNLOAD.search(ln):
            return ln.strip()[:300]
    return None


def never_started(log_text: str) -> bool:
    """The attempt failed before any GameTest server ran, for want of a download: worth running once more."""
    return download_failure(log_text) is not None


def gradle_error(log_text: str, most: int = 25) -> str:
    """Gradle's own account of a failed build: the lines from "What went wrong" to "* Try" (or a compile error)."""
    lines = log_text.splitlines()
    for i, ln in enumerate(lines):
        if "What went wrong" in ln:
            out = []
            for x in lines[i:i + most]:
                if x.startswith("* Try"):
                    break
                out.append(x)
            return "\n".join(out).rstrip()
    errs = [ln for ln in lines if ": error:" in ln]
    return "\n".join(errs[:most])


def attempt(name: str, out_dir: str, shard: str, classes: List[str], not_classes: List[str], exclude: List[str],
            timeout_min: float, extra: List[str], sleep=time.sleep) -> dict:
    """One server, run ONCE more (after DOWNLOAD_RETRY_SECONDS) only when Gradle failed before it started for want of a
    download. A compile failure, a server that started and failed, or a gate problem is never re-run here: that is the
    retry rule's business, or a real red. The retry is recorded on the attempt (`downloadRetry`), and the first
    attempt's log is kept beside it (`<name>-download-failed.log`)."""
    a = _attempt(name, out_dir, shard, classes, not_classes, exclude, timeout_min, extra)
    why = download_failure(g.read_text(os.path.join(out_dir, a["logFile"])))
    if why is None:
        return a
    kept = name + "-download-failed.log"
    os.replace(os.path.join(out_dir, a["logFile"]), os.path.join(out_dir, kept))
    print("gate: %s: Gradle failed for want of a download (%s); running it once more in %d s"
          % (name, why, DOWNLOAD_RETRY_SECONDS), flush=True)
    sleep(DOWNLOAD_RETRY_SECONDS)
    b = _attempt(name, out_dir, shard, classes, not_classes, exclude, timeout_min, extra)
    b["downloadRetry"] = {"why": why, "firstLog": kept, "firstSeconds": a["seconds"]}
    return b


def _attempt(name: str, out_dir: str, shard: str, classes: List[str], not_classes: List[str], exclude: List[str],
             timeout_min: float, extra: List[str]) -> dict:
    """One server. Returns {"log": parsed log, "record": the server's run record or None, "timedOut", "seconds"}."""
    home = os.path.abspath(os.path.join(out_dir, name + "-home"))
    shutil.rmtree(home, ignore_errors=True)
    os.makedirs(home, exist_ok=True)
    log_path = os.path.join(out_dir, name + ".log")
    # its own run directory (CLAUDE.md: two servers in one directory die on session.lock and still say BUILD SUCCESSFUL)
    rundir = "gate-%s-%s" % (os.path.basename(os.path.normpath(out_dir)), name)
    cmd = gradle_cmd(shard, rundir, home, classes, not_classes, exclude, extra)
    print("gate: %s: %s" % (name, " ".join(c if len(c) < 200 else c[:200] + "...(%d chars)" % len(c) for c in cmd)),
          flush=True)
    started = time.time()
    timed_out = False
    with open(log_path, "w", encoding="utf-8", errors="replace") as log:
        kw = {"start_new_session": True} if os.name != "nt" else {}
        env = dict(os.environ)
        if NO_PYTHON:
            env["PATH"] = NO_PYTHON + os.pathsep + env.get("PATH", "")
        proc = subprocess.Popen(cmd, cwd=g.ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, **kw)
        assert proc.stdout is not None
        deadline = started + timeout_min * 60
        for raw in proc.stdout:
            line = raw.decode("utf-8", errors="replace")
            log.write(line)
            if g.RESULT_LINE.search(line) or "FAILED" in line or "BUILD " in line:
                sys.stdout.write(line)
                sys.stdout.flush()
            if time.time() > deadline:
                timed_out = True
                break
        if timed_out:
            try:
                if os.name != "nt":
                    os.killpg(proc.pid, signal.SIGKILL)
                else:
                    proc.kill()
            except OSError:
                pass
        proc.wait()
    text = g.read_text(log_path)
    if "gt-adapter:" not in text:
        # the server never started: say what Gradle said, or the job's log is all a person has
        why = gradle_error(text)
        if why:
            print("gate: %s: Gradle failed before the server started:\n%s" % (name, why), flush=True)
    records = sorted(glob.glob(os.path.join(home, "gate-runs", "*.json")))
    record = g.read_json(records[-1]) if records else None
    return {"log": g.parse_log(text), "record": record, "timedOut": timed_out,
            "seconds": round(time.time() - started, 1), "logFile": os.path.basename(log_path)}


def expected_classes(tier: Dict[str, dict], classes: List[str], not_classes: List[str]) -> List[str]:
    if classes:
        return sorted({c.split(".", 1)[0] for c in classes})
    deny = {c.lower() for c in not_classes}
    return sorted(c for c in tier if c.lower() not in deny)


def check_attempt(a: dict, expect: List[str], known: List[str]) -> List[str]:
    """What makes an attempt untrustworthy: it never finished, ran less than it registered, or skipped a class."""
    problems = []
    log, rec = a["log"], a["record"]
    if a["timedOut"]:
        problems.append("the job's time limit ended the server before it finished")
    if log["oom"]:
        problems.append("the server ran out of memory (OutOfMemoryError)")
    if log["result"] is None:
        problems.append("the server never printed `N required tests passed|failed`: it did not finish its tests")
    if rec is None:
        problems.append("the server wrote no run record")
        return problems
    every = rec.get("tests", [])
    tests = [t for t in every if g.ours(t["name"])]   # the vanilla server adds minecraft:always_pass to every run
    registered = rec.get("registered", -1)
    if not tests:
        problems.append("the server ran no test of ours at all")
    if log["found"] is not None and registered not in (-1, log["found"]):
        problems.append("the log found %d tests and the record says %d were registered" % (log["found"], registered))
    if registered >= 0 and len(tests) != registered:
        problems.append("the server registered %d tests and ran %d" % (registered, len(tests)))
    # "All N required tests passed" counts every test run (the vanilla one too); "N required tests failed" counts
    # the failures. Either way the record must say the same.
    if log["required"] is not None:
        want = len(every) if log["passed"] else sum(1 for t in tests if t.get("required", True) and not t.get("passed"))
        if log["required"] != want:
            problems.append("the log says `%s` and the record has %d" % (log["result"], want))
    ran = {g.test_class(t["name"], known) for t in tests}
    missing = [c for c in expect if c not in ran]
    if missing:
        problems.append("%d class(es) this job was given ran no test: %s" % (len(missing), ", ".join(missing)))
    return problems


def run_job(args) -> int:
    tier_all = g.scan_tier()
    tier = g.tier_classes(tier_all, args.shard)
    known = sorted(tier_all)
    classes = _list(args.classes, args.classes_file)
    not_classes = _list(args.not_classes, args.not_classes_file)
    if getattr(args, "plan", None):
        job = g.read_json(args.plan)["jobs"][args.job]
        classes, not_classes = list(job["classes"]), list(job["notClasses"])
        args.label = args.label or "job-%d" % args.job
    exclude = _list(args.exclude, None)
    if args.flake_register:
        exclude = sorted(set(exclude) | set(load_register_names(args.flake_register)))
    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)
    expect = expected_classes(tier, classes, not_classes)
    extra = list(args.gradle_arg or [])
    global NO_PYTHON
    NO_PYTHON = no_python_stubs(out_dir) if getattr(args, "no_python", False) and os.name != "nt" else None
    covmap = getattr(args, "covmap_tools", None)
    if covmap:
        # a coverage-mapping run (docs/GATE_B.md, "The coverage map"): the JaCoCo agent on, every class in batches of
        # its own; its pass/fail is not a Gate B verdict, so nothing is retried
        tools = os.path.abspath(covmap)
        agent = sorted(glob.glob(os.path.join(tools, "org.jacoco.agent-*-runtime.jar")))
        if not agent:
            raise SystemExit("gate: no JaCoCo agent in %s (./gradlew covmapTools)" % tools)
        extra += ["-Pcovagent=" + agent[-1], "-Pcovmap=" + os.path.join(out_dir, "exec")]
        shutil.rmtree(os.path.join(out_dir, "exec"), ignore_errors=True)
        args.retry = False

    first = attempt("run", out_dir, args.shard, classes, not_classes, exclude, args.timeout, extra)
    attempts = [("run", first)]
    problems = check_attempt(first, expect, known)
    rec = first["record"] or {}
    rows = []
    for t in rec.get("tests", []):
        if not g.ours(t["name"]):
            continue
        rows.append({"name": g.short_name(t["name"], known), "id": t["name"], "seconds": t.get("seconds"),
                     "batch": t.get("batch"), "required": t.get("required", True),
                     "outcome": "skipped" if t.get("skipped") else ("passed" if t.get("passed") else "failed"),
                     "error": t.get("error")})
    failed = [r for r in rows if r["outcome"] == "failed" and r["required"]]
    retries = []
    if failed and args.retry:
        groups = [[r] for r in failed] if len(failed) <= MAX_ALONE else [failed]
        for i, group in enumerate(groups):
            names = [r["name"] for r in group]
            a = attempt("retry-%d" % i, out_dir, args.shard, names, [], [], args.timeout, extra)
            attempts.append(("retry-%d" % i, a))
            got = {}
            for t in (a["record"] or {}).get("tests", []):
                got[g.short_name(t["name"], known).lower()] = t
            retries.append({"tests": names, "alone": len(group) == 1, "log": a["logFile"],
                            "result": a["log"]["result"], "seconds": a["seconds"]})
            for r in group:
                t = got.get(r["name"].lower())
                if t is None:
                    r["retry"] = {"ran": False}
                    continue
                r["retry"] = {"ran": True, "passed": bool(t.get("passed")), "seconds": t.get("seconds"),
                              "error": t.get("error"), "alone": len(group) == 1}
                if t.get("passed"):
                    r["outcome"] = "flaky"
    download_retries = [dict(a["downloadRetry"], attempt=n) for n, a in attempts if a.get("downloadRetry")]
    started = python_started(out_dir) if NO_PYTHON else []
    if started:
        problems.append("the game started Python (a player's game never does): %s" % "; ".join(started[:3]))
    result = {
        "schema": 1,
        "kind": args.kind,
        "label": args.label,
        "shard": args.shard,
        "commit": _commit(),
        "tree": g.gate_tree(),
        "startedUtc": rec.get("startedUtc"),
        "wallSeconds": first["seconds"],
        "filter": {"classes": classes, "notClasses": not_classes, "exclude": exclude},
        "expectedClasses": expect,
        "registered": rec.get("registered"),
        "executed": len(rows),
        "result": first["log"]["result"],
        "tests": rows,
        "retries": retries,
        "problems": problems,
        "noPython": bool(NO_PYTHON),
        # each server Gradle failed to start for want of a download and that ran once more (docs/GATE_B.md)
        "downloadRetries": download_retries,
    }
    result["verdict"] = job_verdict(result)
    if covmap:
        import covmap as cm
        jars = sorted(glob.glob(os.path.join(os.path.abspath(covmap), "*.jar")))
        jars = [j for j in jars if "agent" not in os.path.basename(j)]
        try:
            analysis = cm.analyze(os.path.join(g.ROOT, "build", "classes", "java", "main"),
                                  os.path.join(out_dir, "exec"), os.path.join(out_dir, "analysis.json"), jars)
            placed = sorted(u for u in analysis["units"] if u not in cm.PSEUDO and "#__" not in u)
            result["covmap"] = {"units": placed, "notPlaced": [c for c in expect if c not in placed]}
        except Exception as e:  # noqa: BLE001 - said in the result, and the merge refuses a job without its analysis
            result["problems"].append("the coverage analysis failed: %s" % e)
        # under coverage a failed test still maps; the verdict is whether the job produced a usable map
        result["verdict"] = "red" if result["problems"] else "green"
    g.write_json(os.path.join(out_dir, "job.json"), result)
    print(summary(result))
    return 0 if result["verdict"] == "green" else 1


def job_verdict(result: dict) -> str:
    if result["problems"]:
        return "red"
    for r in result["tests"]:
        if r["required"] and r["outcome"] in ("failed", "skipped"):
            return "red"
    return "green"


def summary(result: dict) -> str:
    rows = result["tests"]
    n = {k: sum(1 for r in rows if r["outcome"] == k) for k in ("passed", "flaky", "failed", "skipped")}
    lines = ["gate: %s %s: %s -- %d tests: %d passed, %d flaky (passed on the retry), %d failed, %d skipped" % (
        result["kind"], result["label"] or "", result["verdict"].upper(), len(rows), n["passed"], n["flaky"],
        n["failed"], n["skipped"])]
    for d in result.get("downloadRetries") or []:
        lines.append("  %s %s: Gradle failed for want of a download (%s) and ran once more" % (
            "passed on a download retry --" if result["verdict"] == "green" else "download retry --", d["attempt"],
            d["why"]))
    for r in rows:
        if r["outcome"] in ("failed", "flaky", "skipped"):
            lines.append("  %-7s %s%s" % (r["outcome"], r["name"], ("  -- " + r["error"]) if r.get("error") else ""))
    for p in result["problems"]:
        lines.append("  PROBLEM %s" % p)
    return "\n".join(lines)


def load_register_names(path: str) -> List[str]:
    reg = g.read_json(path)
    return sorted(e["test"] for e in reg.get("tests", []))


def _list(raw: Optional[str], path: Optional[str]) -> List[str]:
    items: List[str] = []
    if raw:
        items += [x.strip() for x in raw.split(",") if x.strip()]
    if path:
        items += [x.strip() for x in g.read_text(path).replace(",", "\n").splitlines() if x.strip()]
    return items


def _commit() -> Optional[str]:
    try:
        return g.git("rev-parse", "HEAD")
    except Exception:  # noqa: BLE001 - a tarball has no git; the record says so rather than guessing
        return None
