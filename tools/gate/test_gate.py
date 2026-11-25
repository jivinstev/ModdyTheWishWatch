#!/usr/bin/env python3
"""The gate tools' own tests (docs/GATE_B.md). No game server, no network: `python3 tools/gate/test_gate.py`."""
from __future__ import annotations

import os
import shutil
import sys
import tempfile
import types
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import gatelib as g  # noqa: E402
import history  # noqa: E402
import issues  # noqa: E402
import nightly  # noqa: E402
import run  # noqa: E402
import covmap  # noqa: E402
import selection  # noqa: E402
import release  # noqa: E402


def write(root: str, rel: str, text: str) -> None:
    g.write_text(os.path.join(root, rel), text)


HOLDER = """package x;
@GameTestHolder(%s)
public class %s {
    /** a {@code @GameTest} in a comment is not a test */
    // @GameTest
    String s = "@GameTest";
%s}
"""


def holder(ns: str, cls: str, n: int) -> str:
    return HOLDER % (ns, cls, "".join("    @GameTest(template = \"empty_test\")\n    public static void t%d(H h) {}\n" % i
                                      for i in range(n)))


class TierScan(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        j = os.path.join("src", "main", "java", "x")
        write(self.root, os.path.join(j, "AGameTest.java"), holder("GateShards.CI", "AGameTest", 2))
        write(self.root, os.path.join(j, "BGameTest.java"), holder("ModdingFromAModMod.MODID", "BGameTest", 3))
        write(self.root, os.path.join(j, "CGameTest.java"), holder("\"moddingfromamod\"", "CGameTest", 1))
        write(self.root, os.path.join(j, "Helper.java"), "class Helper { /* @GameTestHolder(GateShards.CI) */ }\n")

    def tearDown(self):
        shutil.rmtree(self.root)

    def test_counts_only_real_tests_and_reads_every_spelling_of_a_shard(self):
        t = g.scan_tier(self.root)
        self.assertEqual(sorted(t), ["AGameTest", "BGameTest", "CGameTest"], "a holder in a comment is no holder")
        self.assertEqual(t["AGameTest"], {"ns": "moddingfromamod_ci", "tests": 2, "path": "src/main/java/x/AGameTest.java"})
        self.assertEqual(t["BGameTest"]["ns"], "moddingfromamod")
        self.assertEqual(t["CGameTest"]["ns"], "moddingfromamod")
        self.assertEqual(t["BGameTest"]["tests"], 3, "comments and string literals are not tests")

    def test_a_shard_list_picks_its_namespaces(self):
        t = g.scan_tier(self.root)
        self.assertEqual(sorted(g.tier_classes(t, "ci")), ["AGameTest"])
        self.assertEqual(sorted(g.tier_classes(t, "ci,default")), ["AGameTest", "BGameTest", "CGameTest"])
        self.assertEqual(sorted(g.tier_classes(t, "all")), ["AGameTest", "BGameTest", "CGameTest"])
        with self.assertRaises(ValueError):
            g.tier_classes(t, "trains")


class Splitting(unittest.TestCase):
    CLASSES = {"A": {"tests": 30}, "B": {"tests": 1}, "C": {"tests": 10}, "D": {"tests": 10}, "E": {"tests": 2}}

    def test_every_class_lands_in_exactly_one_job_and_the_split_is_deterministic(self):
        s1 = g.split_classes(self.CLASSES, 3)
        s2 = g.split_classes(dict(reversed(list(self.CLASSES.items()))), 3)
        self.assertEqual(s1, s2, "the same classes give the same split, whatever order they came in")
        flat = [c for part in s1 for c in part]
        self.assertEqual(sorted(flat), sorted(self.CLASSES))
        self.assertEqual(len(flat), len(set(flat)))

    def test_measured_costs_outweigh_test_counts(self):
        s = g.split_classes(self.CLASSES, 2, {"B": 1000.0})
        self.assertIn(["B"], s, "a class measured at 1000 s takes a job to itself")

    def test_job_zero_takes_every_class_the_others_were_not_given(self):
        split = [["A"], ["B", "C"], ["D"]]
        self.assertEqual(g.job_filter(split, 0), ([], ["B", "C", "D"]))
        self.assertEqual(g.job_filter(split, 1), (["B", "C"], []))
        self.assertEqual(g.job_filter([["A", "B"]], 0), ([], []), "one job is the unfiltered tier")

    def test_never_more_jobs_than_classes(self):
        self.assertEqual(len(g.split_classes({"A": {"tests": 1}}, 4)), 1)


class Trees(unittest.TestCase):
    def test_docs_and_workflows_do_not_change_the_gate_tree_and_code_does(self):
        base = [("src/main/java/A.java", "1"), ("build.gradle", "2"), ("docs/X.md", "3"), ("README.md", "4")]
        h = g.gate_tree_of_listing(base)
        self.assertEqual(h, g.gate_tree_of_listing(base[:2] + [("docs/X.md", "9"), ("README.md", "9"),
                                                              (".github/workflows/ci.yml", "9"),
                                                              ("src/test/java/T.java", "9")]))
        self.assertNotEqual(h, g.gate_tree_of_listing([("src/main/java/A.java", "5")] + base[1:]))
        self.assertNotEqual(h, g.gate_tree_of_listing(base + [("corpus/new.json", "6")]))
        self.assertNotEqual(h, g.gate_tree_of_listing(base + [("src/main/resources/data/x.json", "6")]))
        self.assertTrue(g.gate_relevant("docs.gradle"), "only the docs/ directory, not a name that starts with it")


LOG_OK = "x\ngt-adapter: 3 tests found\n[Server thread/INFO]: All 4 required tests passed :)\n"
LOG_RED = "gt-adapter: 3 tests found\n[Server thread/INFO]: 1 required tests failed :(\n"


def rec(names, failed=(), registered=None, vanilla=True):
    tests = [{"name": n, "passed": n not in failed, "required": True, "seconds": 1.0, "batch": "b"} for n in names]
    if vanilla:
        tests.append({"name": "minecraft:always_pass", "passed": True, "required": False, "seconds": 0.0})
    return {"tests": tests, "registered": len(names) if registered is None else registered,
            "startedUtc": "2026-11-25T09:23:00Z"}


IDS = ["moddingfromamod:agametest.t0", "moddingfromamod:agametest.t1", "moddingfromamod:bgametest.t0"]


class OneAttempt(unittest.TestCase):
    def test_a_log_tells_finished_from_not(self):
        self.assertEqual(g.parse_log(LOG_OK)["required"], 4)
        self.assertTrue(g.parse_log(LOG_OK)["passed"])
        self.assertFalse(g.parse_log(LOG_RED)["passed"])
        self.assertIsNone(g.parse_log("BUILD SUCCESSFUL")["result"], "no result line: the server never ran the tests")

    def test_a_clean_attempt_has_no_problem_and_the_vanilla_test_is_not_ours(self):
        a = {"log": g.parse_log(LOG_OK), "record": rec(IDS), "timedOut": False}
        self.assertEqual(run.check_attempt(a, ["AGameTest", "BGameTest"], ["AGameTest", "BGameTest"]), [])

    def test_a_server_that_ran_less_than_it_registered_or_skipped_a_class_is_not_trusted(self):
        a = {"log": g.parse_log(LOG_OK), "record": rec(IDS[:2], registered=3), "timedOut": False}
        p = run.check_attempt(a, ["AGameTest", "BGameTest"], ["AGameTest", "BGameTest"])
        self.assertTrue(any("registered 3 tests and ran 2" in x for x in p), p)
        self.assertTrue(any("BGameTest" in x for x in p), "a class this job was given that ran nothing is named")

    def test_a_red_logs_number_counts_the_failures(self):
        a = {"log": g.parse_log("1 required tests failed"), "record": rec(IDS, failed=[IDS[0]]), "timedOut": False}
        self.assertEqual(run.check_attempt(a, [], ["AGameTest", "BGameTest"]), [])
        a = {"log": g.parse_log("2 required tests failed"), "record": rec(IDS, failed=[IDS[0]]), "timedOut": False}
        self.assertEqual(len(run.check_attempt(a, [], ["AGameTest", "BGameTest"])), 1)

    @unittest.skipIf(os.name == "nt", "the stubs are POSIX shell scripts")
    def test_the_no_python_stubs_fail_and_say_they_were_started(self):
        import subprocess
        d = tempfile.mkdtemp()
        try:
            stubs = run.no_python_stubs(d)
            env = dict(os.environ, PATH=stubs + os.pathsep + os.environ.get("PATH", ""))
            out = subprocess.run(["python3", "-c", "print(1)"], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(out.returncode, 127)
            self.assertEqual(len(run.python_started(d)), 1)
            self.assertIn("python3 -c print(1)", run.python_started(d)[0])
        finally:
            shutil.rmtree(d)

    def test_a_failed_build_says_what_gradle_said(self):
        log = "x\n* What went wrong:\nCould not resolve all artifacts\n> 429\n\n* Try:\n> --stacktrace\n"
        self.assertEqual(run.gradle_error(log), "* What went wrong:\nCould not resolve all artifacts\n> 429")
        self.assertIn("error: cannot find symbol", run.gradle_error("A.java:3: error: cannot find symbol\n"))

    def test_only_a_run_that_never_started_for_want_of_a_download_is_run_again(self):
        self.assertTrue(run.never_started("> Could not resolve all artifacts\nReceived status code 429 from server"))
        self.assertFalse(run.never_started("gt-adapter: 3 tests found\nCould not GET x"), "a server that ran is not")
        self.assertFalse(run.never_started("error: cannot find symbol"), "a compile error is not a download")

    def test_no_result_line_no_record_or_no_test_is_a_problem(self):
        a = {"log": g.parse_log("BUILD SUCCESSFUL"), "record": None, "timedOut": True}
        p = run.check_attempt(a, ["AGameTest"], ["AGameTest"])
        self.assertTrue(any("never printed" in x for x in p))
        self.assertTrue(any("time limit" in x for x in p))
        self.assertTrue(any("no run record" in x for x in p))
        a = {"log": g.parse_log(LOG_OK), "record": rec([], registered=0), "timedOut": False}
        self.assertTrue(any("no test of ours" in x for x in run.check_attempt(a, [], [])))

    def test_the_classes_a_job_must_run(self):
        tier = {"AGameTest": {}, "BGameTest": {}, "CGameTest": {}}
        self.assertEqual(run.expected_classes(tier, ["AGameTest.t0", "CGameTest"], []), ["AGameTest", "CGameTest"])
        self.assertEqual(run.expected_classes(tier, [], ["agametest"]), ["BGameTest", "CGameTest"])


class Retry(unittest.TestCase):
    """run_job with the server replaced: a failure is retried once, by itself, and flaky only if it then passes."""

    def drive(self, first_failed, retry_passes, n_fail_names=None):
        names = n_fail_names or IDS
        calls = []

        def fake_attempt(name, out_dir, shard, classes, not_classes, exclude, timeout, extra):
            calls.append((name, list(classes)))
            if name == "run":
                return {"log": g.parse_log(LOG_OK.replace("4 required", "%d required" % (len(names) + 1))),
                        "record": rec(names, failed=first_failed), "timedOut": False, "seconds": 1.0,
                        "logFile": "run.log"}
            ids = [i for i in names if g.short_name(i, ["AGameTest", "BGameTest"]).lower() in
                   {c.lower() for c in classes}]
            return {"log": g.parse_log(LOG_OK), "record": rec(ids, failed=[i for i in ids if i not in retry_passes],
                                                              vanilla=False),
                    "timedOut": False, "seconds": 1.0, "logFile": name + ".log"}

        out = tempfile.mkdtemp()
        old_attempt, old_scan, old_tree, old_commit = run.attempt, g.scan_tier, g.gate_tree, run._commit
        run.attempt = fake_attempt
        g.scan_tier = lambda root=g.ROOT: {"AGameTest": {"ns": "moddingfromamod", "tests": 2},
                                           "BGameTest": {"ns": "moddingfromamod", "tests": 1}}
        g.gate_tree = lambda rev="HEAD", root=g.ROOT: "tree"
        run._commit = lambda: "abc"
        try:
            a = types.SimpleNamespace(shard="ci,default", classes=None, classes_file=None, not_classes=None, no_python=False,
                                      not_classes_file=None, exclude=None, flake_register=None, out=out, retry=True,
                                      timeout=1, kind="nightly", label="job-0", gradle_arg=None, plan=None, job=0)
            code = run.run_job(a)
            return code, g.read_json(os.path.join(out, "job.json")), calls
        finally:
            run.attempt, g.scan_tier, g.gate_tree, run._commit = old_attempt, old_scan, old_tree, old_commit
            shutil.rmtree(out)

    def test_a_failure_that_passes_alone_is_flaky_and_the_job_is_green(self):
        code, res, calls = self.drive([IDS[0]], [IDS[0]])
        self.assertEqual(code, 0)
        self.assertEqual(calls[1], ("retry-0", ["AGameTest.t0"]), "the retry runs that one test and nothing else")
        row = next(r for r in res["tests"] if r["id"] == IDS[0])
        self.assertEqual(row["outcome"], "flaky")
        self.assertEqual(row["retry"], {"ran": True, "passed": True, "seconds": 1.0, "error": None, "alone": True})

    def test_a_failure_that_fails_alone_too_is_red(self):
        code, res, _ = self.drive([IDS[0]], [])
        self.assertEqual(code, 1)
        self.assertEqual(res["verdict"], "red")
        self.assertEqual(next(r for r in res["tests"] if r["id"] == IDS[0])["outcome"], "failed")

    def test_each_failure_is_retried_alone_until_there_are_too_many(self):
        _, _, calls = self.drive(IDS[:2], [])
        self.assertEqual([c[1] for c in calls[1:]], [["AGameTest.t0"], ["AGameTest.t1"]])
        many = ["moddingfromamod:agametest.t%d" % i for i in range(run.MAX_ALONE + 1)]
        _, res, calls = self.drive(many, many, many)
        self.assertEqual(len(calls), 2, "past MAX_ALONE failures, one server retries them together")
        self.assertTrue(all(r["outcome"] == "flaky" and r["retry"]["alone"] is False for r in res["tests"]))


PLAN = {"shard": "ci,default", "tierClasses": {"AGameTest": 2, "BGameTest": 1}, "tierTests": 3,
        "jobs": [{"index": 0, "classes": [], "notClasses": ["BGameTest"]},
                 {"index": 1, "classes": ["BGameTest"], "notClasses": []}]}


def job(label, rows, problems=(), exclude=()):
    return {"label": label, "commit": "c1", "tree": "t1", "startedUtc": "2026-11-25T09:23:00Z", "wallSeconds": 60,
            "registered": len(rows), "executed": len(rows), "result": "x", "problems": list(problems),
            "filter": {"exclude": list(exclude)},
            "tests": [{"name": g.short_name(i, ["AGameTest", "BGameTest"]), "id": i, "required": True,
                       "outcome": o, "seconds": 1.0, "batch": "b"} for i, o in rows]}


class Merge(unittest.TestCase):
    def jobs(self, a_rows=None, b_rows=None):
        a_rows = a_rows if a_rows is not None else [(IDS[0], "passed"), (IDS[1], "passed")]
        b_rows = b_rows if b_rows is not None else [(IDS[2], "passed")]
        return [job("job-0", a_rows), job("job-1", b_rows)]

    def test_a_complete_green_run_is_full_tier_evidence(self):
        r = nightly.merge(PLAN, self.jobs(), {}, "nightly")
        self.assertEqual((r["verdict"], r["fullTier"], r["problems"]), ("green", True, []))
        self.assertEqual(r["counts"]["total"], 3)

    def test_a_flake_reads_green_and_is_counted(self):
        r = nightly.merge(PLAN, self.jobs([(IDS[0], "flaky"), (IDS[1], "passed")]), {}, "nightly")
        self.assertEqual((r["verdict"], r["counts"]["flaky"]), ("green", 1))

    def test_a_missing_job_a_short_class_or_a_test_run_twice_is_red(self):
        r = nightly.merge(PLAN, [self.jobs()[0], None], {}, "nightly")
        self.assertEqual(r["verdict"], "red")
        self.assertFalse(r["fullTier"])
        self.assertTrue(any("job 1 left no result" in p for p in r["problems"]))
        r = nightly.merge(PLAN, self.jobs([(IDS[0], "passed")]), {}, "nightly")
        self.assertTrue(any("AGameTest: its source declares 2" in p for p in r["problems"]), r["problems"])
        r = nightly.merge(PLAN, self.jobs(None, [(IDS[2], "passed"), (IDS[0], "passed")]), {}, "nightly")
        self.assertTrue(any("ran in job 0 and in job 1" in p for p in r["problems"]))

    def test_a_job_problem_is_the_runs_problem(self):
        js = self.jobs()
        js[1]["problems"] = ["the server registered 2 tests and ran 1"]
        self.assertEqual(nightly.merge(PLAN, js, {}, "nightly")["verdict"], "red")

    def test_a_failure_is_red_unless_the_register_knows_the_test(self):
        js = self.jobs([(IDS[0], "failed"), (IDS[1], "passed")])
        self.assertEqual(nightly.merge(PLAN, js, {}, "nightly")["verdict"], "red")
        reg = {"agametest.t0": {"test": "AGameTest.t0", "issue": "#1"}}
        r = nightly.merge(PLAN, self.jobs([(IDS[0], "failed"), (IDS[1], "passed")]), reg, "nightly")
        self.assertEqual((r["verdict"], r["counts"]["known-flaky"]), ("green", 1))

    def test_a_run_that_left_out_a_test_not_in_the_register_is_not_full_tier(self):
        js = [job("job-0", [(IDS[1], "passed")], exclude=["AGameTest.t0"]), job("job-1", [(IDS[2], "passed")])]
        r = nightly.merge(PLAN, js, {}, "release")
        self.assertFalse(r["fullTier"])
        reg = {"agametest.t0": {"test": "AGameTest.t0", "issue": "#1"}}
        r = nightly.merge(PLAN, [job("job-0", [(IDS[1], "passed")], exclude=["AGameTest.t0"]),
                                 job("job-1", [(IDS[2], "passed")])], reg, "release")
        self.assertEqual((r["verdict"], r["fullTier"]), ("green", True), r["problems"])

    def test_the_merge_finds_each_jobs_result_however_deep_its_artifact_put_it(self):
        d = tempfile.mkdtemp()
        try:
            g.write_json(os.path.join(d, "gate-job-0", "gate-job", "job.json"), job("job-0", [(IDS[0], "passed")]))
            self.assertEqual(g.find_file(os.path.join(d, "gate-job-0"), "job.json"),
                             os.path.join(d, "gate-job-0", "gate-job", "job.json"))
            self.assertIsNone(g.find_file(os.path.join(d, "gate-job-0"), "other.json"))
        finally:
            shutil.rmtree(d)

    def test_the_report_names_what_failed(self):
        r = nightly.merge(PLAN, self.jobs([(IDS[0], "failed"), (IDS[1], "passed")]), {}, "nightly")
        self.assertIn("`AGameTest.t0`", nightly.report(r))


def stored(tree, outcome, started, kind="nightly", verdict="green", name="AGameTest.t0", full=True):
    return {"kind": kind, "tree": tree, "commit": "c-" + tree, "startedUtc": started, "verdict": verdict,
            "fullTier": full, "tests": [{"name": name, "id": "moddingfromamod:" + name.lower(), "outcome": outcome,
                                         "seconds": 2.0, "batch": "b"}]}


class History(unittest.TestCase):
    def test_a_test_that_passed_and_failed_at_one_tree_is_flaky_and_across_trees_is_not(self):
        runs = [stored("t1", "passed", "1"), stored("t2", "failed", "2")]
        self.assertEqual(history.flaky(runs), {}, "a fail after a code change is a regression, not a flake")
        runs.append(stored("t2", "passed", "3"))
        self.assertEqual(sorted(history.flaky(runs)), ["AGameTest.t0"])
        self.assertEqual(history.flaky(runs)["AGameTest.t0"]["trees"], ["t2"])
        self.assertIn("AGameTest.t0", history.flaky([stored("t9", "flaky", "1")]), "failed then passed alone")

    def test_a_renamed_test_keeps_its_history(self):
        ren = {"oldgametest": "NewGameTest", "newgametest.a": "NewGameTest.b"}
        self.assertEqual(history.rename("OldGameTest.x", ren), "NewGameTest.x")
        self.assertEqual(history.rename("OldGameTest.a", ren), "NewGameTest.b", "renames chain")
        self.assertEqual(history.rename("Other.a", ren), "Other.a")

    def test_the_last_green_full_run(self):
        runs = [stored("t1", "passed", "1"), stored("t2", "failed", "2", verdict="red"),
                stored("t3", "passed", "3", kind="pr"), stored("t4", "passed", "4", full=False)]
        self.assertEqual(history.last_green_full(runs, ("nightly",))["tree"], "t1")

    def test_records_store_one_file_each_and_read_back(self):
        d = tempfile.mkdtemp()
        try:
            r = dict(stored("t1", "passed", "2026-11-25T09:23:41.5Z"), commit="0123456789abcdef")
            rel = history.store(d, r)
            self.assertEqual(rel, "runs/2026-11/20261125T092341-nightly-0123456789.json.gz")
            self.assertEqual(history.store(d, r), rel, "the same record, the same file")
            self.assertEqual(len(history.load_records(d)), 1)
            self.assertTrue(os.path.exists(os.path.join(d, "README.md")))
        finally:
            shutil.rmtree(d)

    def test_a_pull_requests_run_is_recorded_as_one_and_is_never_full_tier_evidence(self):
        j = job("pr-7", [(IDS[0], "flaky")])
        rec = history.pr_record(j, {"id": 99, "html_url": "u", "pull_requests": [{"number": 7}]})
        self.assertEqual((rec["kind"], rec["label"], rec["fullTier"], rec["runUrl"]), ("pr", "pr7", False, "u"))
        self.assertIn("AGameTest.t0", history.flaky([dict(rec, tree="t1")]), "its flakes count")

    def test_the_report_names_flakes_not_in_the_register(self):
        runs = [stored("t2", "failed", "2"), stored("t2", "passed", "3")]
        self.assertIn("NOT IN THE REGISTER", history.report(runs, register={}))
        self.assertIn("register: #7", history.report(runs, register={"agametest.t0": {"issue": "#7"}}))


class TheRegister(unittest.TestCase):
    def test_the_committed_register_is_well_formed_and_every_row_names_its_issue(self):
        reg = g.read_json(history.REGISTER)
        tier = g.scan_tier()
        for row in reg["tests"]:
            self.assertRegex(row["test"], r"^\w+\.\w+$")
            self.assertIn(row["test"].split(".")[0], tier, "a register row names a GameTest class that exists")
            self.assertRegex(row.get("issue", ""), r"^#\d+$", "a row names its issue in this repository")
            self.assertTrue(row.get("seen"), "a row says where it was seen to pass and fail at one commit")

    def test_the_committed_renames_are_well_formed(self):
        for old, new in g.read_json(history.RENAMES)["renames"].items():
            self.assertRegex(old, r"^\w+(\.\w+)?$")
            self.assertRegex(new, r"^\w+(\.\w+)?$")


class Issue(unittest.TestCase):
    def test_one_issue_opens_on_red_updates_while_red_and_closes_on_green(self):
        self.assertEqual(issues.plan_action("red", []), "open")
        self.assertEqual(issues.plan_action("red", [5]), "update")
        self.assertEqual(issues.plan_action("green", [5]), "close")
        self.assertEqual(issues.plan_action("green", []), "nothing")


class ThisTree(unittest.TestCase):
    def test_the_real_tier_scans_and_plans(self):
        p = nightly.plan("ci,default", 4)
        self.assertGreater(p["tierTests"], 1000)
        self.assertIn("McpGameTest", p["tierClasses"])
        planned = [c for j in p["jobs"] for c in j["planned"]]
        self.assertEqual(sorted(planned), sorted(p["tierClasses"]))


# a tiny world: two production files, three GameTest classes, and a map of which test ran what
J = "src/main/java/"
TIER3 = {"AGameTest": {"ns": "moddingfromamod_ci", "tests": 2, "path": J + "x/AGameTest.java"},
         "BGameTest": {"ns": "moddingfromamod", "tests": 3, "path": J + "x/BGameTest.java"},
         "CGameTest": {"ns": "moddingfromamod", "tests": 4, "path": J + "x/CGameTest.java"},
         "DGameTest": {"ns": "moddingfromamod", "tests": 5, "path": J + "y/DGameTest.java"}}
SOURCES = {J + "x/Foo.java": "class Foo { static final int K = 1; void a() {} void b() {} }",
           J + "x/Bar.java": "class Bar { int k = Foo.K; void c() {} } // Baz in a comment",
           J + "x/Baz.java": "class Baz { void tick() {} }",
           J + "x/AGameTest.java": "class AGameTest {}", J + "x/BGameTest.java": "class BGameTest { Bar b; }",
           J + "x/CGameTest.java": "class CGameTest {}", J + "y/DGameTest.java": "class DGameTest {}"}
TABLE = {"x/Foo#<clinit>": {"file": "x/Foo.java", "lines": [[3, 3]]}, "x/Foo#a": {"file": "x/Foo.java", "lines": [[10, 20]]}, "x/Foo#b": {"file": "x/Foo.java", "lines": [[30, 40]]},
         "x/Bar#c": {"file": "x/Bar.java", "lines": [[5, 9]]}, "x/Baz#tick": {"file": "x/Baz.java", "lines": [[3, 4]]}}
BLOBS = {"x/Foo.java": "f1", "x/Bar.java": "b1", "x/Baz.java": "z1"}


def a_map(built="2026-11-25T00:00:00Z"):
    analysis = {"methods": TABLE, "units": {"__boot__": ["x/Foo#<clinit>"], "__idle__": ["x/Baz#tick"], "AGameTest": ["x/Foo#a"],
                                             "CGameTest": ["x/Foo#b", "x/Baz#tick"], "BGameTest": ["x/Bar#c"]}}
    m = covmap.build([analysis], "c1", "t1", BLOBS, "full")
    m["built"] = built
    return m


NOW = 1795600000.0   # 2026-11-25


def pick(changes, cmap="default", blobs=None):
    cm = a_map() if cmap == "default" else cmap
    return selection.Selector(TIER3, SOURCES, cm, blobs if blobs is not None else BLOBS, now=NOW).select(changes)


class Selection(unittest.TestCase):
    def test_a_change_inside_a_method_selects_the_tests_that_ran_it_and_ci(self):
        s = pick([selection.Change("M", J + "x/Foo.java", J + "x/Foo.java", [(32, 33)])])
        self.assertEqual(s["mode"], "targeted")
        self.assertEqual(sorted(s["classes"]), ["AGameTest", "CGameTest", "DGameTest"])
        self.assertIn("runs Foo#b", s["classes"]["CGameTest"])
        self.assertEqual(s["classes"]["AGameTest"], ["the ci shard"], "only as ci: it never ran Foo#b")
        self.assertEqual(s["classes"]["DGameTest"], ["the map has not placed it"])

    def test_a_change_outside_every_method_is_the_whole_file(self):
        s = pick([selection.Change("M", J + "x/Foo.java", J + "x/Foo.java", [(1, 2)], ["import x.Y;"])])
        self.assertIn("reaches Foo (a change outside its methods)", s["classes"]["CGameTest"])
        self.assertNotIn("BGameTest", s["classes"], "an import is not a constant: Foo's users are not reached")

    def test_a_changed_constant_reaches_every_file_that_names_its_class(self):
        s = pick([selection.Change("M", J + "x/Foo.java", J + "x/Foo.java", [(1, 1)],
                                   ["    static final int K = 1;", "    static final int K = 2;"])])
        self.assertIn("reaches Bar, which names Foo (a constant changed)", s["classes"]["BGameTest"],
                      "a copied constant reaches its users, which never execute Foo")

    def test_a_file_changed_since_the_map_is_the_whole_file_without_the_maps_commit(self):
        s = pick([selection.Change("M", J + "x/Foo.java", J + "x/Foo.java", [(32, 33)])],
                 blobs=dict(BLOBS, **{"x/Foo.java": "f2"}))
        self.assertTrue(any("since the map" in w for w in s["classes"]["AGameTest"]))

    def test_with_the_maps_commit_a_hunk_is_carried_back_through_the_drift(self):
        # since the map, 5 lines were inserted at the top of Foo: line 37 now is line 32 then, inside Foo#b
        drift = lambda path: [(1, 0, 2, 5)]  # noqa: E731
        s = selection.Selector(TIER3, SOURCES, a_map(), dict(BLOBS, **{"x/Foo.java": "f2"}), now=NOW,
                               drift=drift).select([selection.Change("M", J + "x/Foo.java", J + "x/Foo.java", [(37, 38)])])
        self.assertIn("runs Foo#b", s["classes"]["CGameTest"])
        self.assertEqual(s["classes"]["AGameTest"], ["the ci shard"], "not the whole file: Foo#a is not reached")

    def test_drift_maps_lines_moved_and_changed(self):
        drift = [(10, 2, 10, 5), (30, 0, 33, 4)]   # 2 lines became 5 at 10; 4 lines inserted after 29
        self.assertEqual(selection.to_map_lines(5, 5, drift), (5, 5), "above every hunk: unmoved")
        self.assertEqual(selection.to_map_lines(12, 12, drift), (10, 11), "inside a changed hunk: its whole old range")
        self.assertEqual(selection.to_map_lines(20, 20, drift), (17, 17), "below it: moved by its growth")
        self.assertEqual(selection.to_map_lines(34, 34, drift), (30, 31), "an inserted line: the lines it sits between")
        self.assertEqual(selection.to_map_lines(40, 40, drift), (33, 33))

    def test_a_version_bump_selects_nothing_but_another_property_is_the_tier(self):
        bump = selection.Change("M", "gradle.properties", "gradle.properties", [(15, 15)],
                                ["mod_version=1.0.10", "mod_version=1.0.11"])
        self.assertEqual(pick([bump])["mode"], "targeted")
        other = selection.Change("M", "gradle.properties", "gradle.properties", [(3, 3)], ["neo_version=1", "neo_version=2"])
        self.assertEqual(pick([other])["mode"], "tier")

    def test_code_that_runs_with_no_test_running_is_the_whole_tier(self):
        s = pick([selection.Change("M", J + "x/Baz.java", J + "x/Baz.java", [(3, 3)])])
        self.assertEqual(s["mode"], "tier")
        self.assertTrue(any("no test running" in w for w in s["why"]))

    def test_tests_that_name_a_changed_class_run_and_a_comment_is_not_a_name(self):
        s = pick([selection.Change("A", None, J + "x/Bar2.java")])
        self.assertNotIn("BGameTest", [c for c, w in s["classes"].items() if "names Bar2" in w])
        s = pick([selection.Change("M", J + "x/Bar.java", J + "x/Bar.java", [(6, 6)])])
        self.assertIn("names Bar", s["classes"]["BGameTest"])
        s = pick([selection.Change("M", J + "x/Baz.java", J + "x/Baz.java", [])], cmap=None)
        self.assertNotIn("names Baz", s["classes"].get("BGameTest", []), "Bar names Baz only in a comment")

    def test_a_static_initialiser_run_at_start_up_reaches_its_file_and_what_names_its_class(self):
        s = pick([selection.Change("M", J + "x/Foo.java", J + "x/Foo.java", [(3, 3)], ["static int[] T = {1};"])])
        self.assertEqual(s["mode"], "targeted", "not the whole tier")
        self.assertIn("reaches Foo (its static initialiser changed)", s["classes"]["CGameTest"])
        self.assertIn("reaches Bar, which names Foo (a static initialiser changed)", s["classes"]["BGameTest"])

    def test_a_tests_own_helper_is_code_the_map_sees_and_the_runner_is_the_harness(self):
        helper = J + "com/moddingfromamod/testing/TestPlots.java"
        self.assertEqual(pick([selection.Change("M", helper, helper, [(1, 1)])])["mode"], "targeted")
        for p in [J + "com/moddingfromamod/testing/gt/TestHygiene.java", J + "com/moddingfromamod/testing/GateShards.java",
                  J + "com/moddingfromamod/testing/mixin/GameTestBatchOrderMixin.java"]:
            self.assertEqual(pick([selection.Change("M", p, p, [(1, 1)])])["mode"], "tier", p)

    def test_what_the_map_cannot_see_is_the_whole_tier(self):
        for p in ["build.gradle", "src/main/resources/data/moddingfromamod/x.json", "gradle.properties",
                  J + "com/moddingfromamod/mixin/FooMixin.java", J + "com/moddingfromamod/testing/gt/GameTestAdapter.java",
                  "tools/cbscript.zip", "somewhere/new.txt"]:
            self.assertEqual(pick([selection.Change("M", p, p)])["mode"], "tier", p)

    def test_docs_tools_and_unit_tests_select_only_ci_and_the_unplaced(self):
        for p in ["docs/GATE_B.md", "README.md", ".github/workflows/ci.yml", "tools/gate/gate.py",
                  "src/test/java/x/FooTest.java", "corpus/gate-flakes.json", "gate-a.gradle", "art/icon.png"]:
            s = pick([selection.Change("M", p, p)])
            self.assertEqual((s["mode"], sorted(s["classes"])), ("targeted", ["AGameTest", "DGameTest"]), p)

    def test_no_map_or_an_old_map_is_the_whole_tier(self):
        c = [selection.Change("M", J + "x/Foo.java", J + "x/Foo.java", [(32, 33)])]
        self.assertEqual(pick(c, cmap=None)["mode"], "tier")
        self.assertEqual(pick(c, cmap=a_map(built="2026-10-01T00:00:00Z"))["mode"], "tier")

    def test_a_changed_gametest_runs_itself_and_a_new_file_reaches_its_package(self):
        s = pick([selection.Change("M", J + "x/CGameTest.java", J + "x/CGameTest.java", [])])
        self.assertIn("its own file changed", s["classes"]["CGameTest"])
        s = pick([selection.Change("A", None, J + "x/Qux.java")])
        self.assertEqual((s["mode"], sorted(s["classes"])), ("targeted", ["AGameTest", "DGameTest"]),
                         "a new class is reached through the changed code that names it, not its whole package")
        found = selection.Selector(TIER3, dict(SOURCES, **{J + "x/Hook.java": "@EventBusSubscriber class Hook {}"}),
                                   a_map(), BLOBS, now=NOW).select([selection.Change("A", None, J + "x/Hook.java")])
        self.assertEqual(found["mode"], "tier", "a class the game finds by itself")
        client = selection.Selector(TIER3, dict(SOURCES, **{J + "x/Cl.java": "@EventBusSubscriber(value = Dist.CLIENT) class Cl {}"}),
                                    a_map(), BLOBS, now=NOW).select([selection.Change("A", None, J + "x/Cl.java")])
        self.assertEqual(client["mode"], "targeted", "a GameTest server never loads a client-only subscriber")

    def test_a_client_asset_is_read_only_by_a_source_that_names_it(self):
        p = "src/main/resources/assets/moddingfromamod/items/wish_egg.json"
        self.assertEqual(pick([selection.Change("M", p, p)])["mode"], "targeted")
        self.assertEqual(pick([selection.Change("M", "src/main/resources/lessons/creature.md",
                                                "src/main/resources/lessons/creature.md")])["mode"], "tier")

    def test_the_selection_is_deterministic(self):
        c = [selection.Change("M", J + "x/Foo.java", J + "x/Foo.java", [(1, 2), (32, 33)])]
        self.assertEqual(pick(c), pick(list(c)))

    def test_hunks_read_from_a_zero_context_diff(self):
        d = "@@ -10,3 +10,4 @@\n@@ -40,0 +44,2 @@\n@@ -50 +55 @@\n"
        self.assertEqual(selection.parse_hunks(d), [(10, 12), (40, 41), (50, 50)])


class CoverageMap(unittest.TestCase):
    def test_ambient_methods_leave_every_unit_and_a_round_trip_keeps_the_sets(self):
        d = covmap.decode(a_map())
        self.assertEqual(d["units"]["CGameTest"], {"x/Foo#b"}, "Baz#tick ran with no test running")
        self.assertEqual(d["ambient"], {"x/Baz#tick", "x/Foo#<clinit>"})
        self.assertEqual(d["where"]["x/Foo#a"], ("x/Foo.java", [(10, 20)]))

    def test_a_partial_map_keeps_what_it_did_not_remap(self):
        prev = a_map()
        part = covmap.build([{"methods": TABLE, "units": {"BGameTest": ["x/Foo#a"]}}], "c2", "t2", BLOBS, "partial",
                            prev, ["BGameTest"])
        d = covmap.decode(part)
        self.assertEqual(d["units"]["BGameTest"], {"x/Foo#a"})
        self.assertEqual(d["units"]["CGameTest"], {"x/Foo#b"})
        self.assertEqual(part["units"]["CGameTest"]["commit"], "c1", "a kept unit says when it was mapped")

    def test_a_dropped_class_is_reported_and_one_that_left_the_tier_is_not(self):
        prev, cur = a_map(), a_map()
        del cur["units"]["BGameTest"]
        self.assertEqual(covmap.dropped(prev, cur, TIER3), ["BGameTest"])
        self.assertEqual(covmap.dropped(prev, cur, {"AGameTest": {}}), [])
        self.assertEqual(covmap.unplaced(cur, TIER3), ["BGameTest", "DGameTest"])

    def test_two_maps_of_one_commit_must_agree_but_for_the_listed_classes(self):
        a, b = a_map(), a_map()
        self.assertEqual(covmap.compare(a, b), [])
        other = covmap.build([{"methods": TABLE, "units": {"AGameTest": ["x/Foo#a", "x/Bar#c"],
                                                             "CGameTest": ["x/Foo#b"], "BGameTest": ["x/Bar#c"],
                                                             "__idle__": ["x/Baz#tick"]}}], "c1", "t1", BLOBS, "full")
        self.assertEqual(covmap.compare(a, other), [], "one method is timer noise")
        many = {"x/M%d#m" % i: {"file": "x/M%d.java" % i, "lines": [[1, 2]]} for i in range(8)}
        wide = covmap.build([{"methods": dict(TABLE, **many), "units": {
            "AGameTest": ["x/Foo#a"] + sorted(many), "CGameTest": ["x/Foo#b"], "BGameTest": ["x/Bar#c"],
            "__idle__": ["x/Baz#tick"]}}], "c1", "t1", BLOBS, "full")
        self.assertEqual(len(covmap.compare(a, wide)), 1, "eight more methods of nine is not noise")
        self.assertEqual(covmap.compare(a, wide, ["AGameTest"]), [], "unless the class is listed")
        self.assertLess(covmap.noise(a, wide)["agree"], 1.0)

    def test_costs_keep_the_last_samples_and_their_rounded_median(self):
        c = None
        for s in (10.4, 30.0, 20.2, 99.0, 21.0, 22.0):
            c = covmap.costs_update(c, {"AGameTest": s})
        self.assertEqual(c["classes"]["AGameTest"]["samples"], [30.0, 20.2, 99.0, 21.0, 22.0])
        self.assertEqual(c["classes"]["AGameTest"]["median"], 22)

    def test_a_partial_run_maps_the_unplaced_and_the_changed(self):
        prev = a_map()
        blobs = dict(BLOBS, **{"x/CGameTest.java": "new"})
        prev["files"] = dict(BLOBS, **{"x/CGameTest.java": "old", "x/AGameTest.java": "a", "x/BGameTest.java": "b"})
        blobs.update({"x/AGameTest.java": "a", "x/BGameTest.java": "b"})
        self.assertEqual(covmap.to_remap("partial", prev, TIER3, blobs), ["CGameTest", "DGameTest"])
        self.assertEqual(covmap.to_remap("full", prev, TIER3, blobs), sorted(TIER3))


def full(tree, verdict="green", kind="nightly", fulltier=True, shard="ci,default", excluded=()):
    return {"kind": kind, "tree": tree, "commit": "c-" + tree, "verdict": verdict, "fullTier": fulltier,
            "shard": shard, "excluded": list(excluded), "runUrl": "u"}


class ReleaseEvidence(unittest.TestCase):
    REG = {"agametest.t0": {"test": "AGameTest.t0", "issue": "#1"}}

    def test_only_a_green_full_tier_run_of_the_same_code_is_evidence(self):
        self.assertIsNone(release.is_evidence(full("T"), "T", {}))
        self.assertIsNone(release.is_evidence(full("T", kind="release"), "T", {}))
        for rec, why in [(full("U"), "other code"), (full("T", verdict="red"), "red"),
                         (full("T", kind="pr"), "not a full-tier"), (full("T", shard="ci"), "shard"),
                         (full("T", fulltier=False), "whole tier"), (full("T", kind="covmap"), "not a full-tier")]:
            self.assertIn(why, release.is_evidence(rec, "T", {}) or "", rec)

    def test_a_record_names_its_code_by_todays_definition_of_the_tree(self):
        # written when the tree still counted .port/: recomputed from its commit, it covers the same code today
        old = full("OLD")
        self.assertIsNotNone(release.is_evidence(old, "T", {}))
        self.assertIsNone(release.is_evidence(old, "T", {}, tree_of=lambda r: "T"))

    def test_a_run_may_leave_out_only_the_registers_tests(self):
        self.assertIsNone(release.is_evidence(full("T", excluded=["AGameTest.t0"]), "T", self.REG))
        self.assertIn("not on the flake register",
                      release.is_evidence(full("T", excluded=["BGameTest.t0"]), "T", self.REG))

    def drive(self, before, after, run):
        calls = {"dispatch": 0}
        recs = [before, after]
        old_tree = g.gate_tree
        g.gate_tree = lambda rev="HEAD", root=g.ROOT: "T"
        clock = [1000.0]
        try:
            code = release.gate("o/r", "HEAD", "neoforge-1.21.8", "/nowhere", 60,
                                dispatch=lambda: calls.__setitem__("dispatch", calls["dispatch"] + 1),
                                latest_run=lambda since: run, refetch=lambda: recs.pop(0) if recs else [],
                                sleep=lambda s: clock.__setitem__(0, clock[0] + s), now=lambda: clock[0])
        finally:
            g.gate_tree = old_tree
        return code, calls["dispatch"]

    def test_a_covering_run_releases_without_running_the_tier(self):
        self.assertEqual(self.drive([full("T")], [], None), (0, 0))

    def test_no_covering_run_runs_the_tier_and_releases_only_if_it_is_green(self):
        done = {"status": "completed", "conclusion": "success", "html_url": "u"}
        self.assertEqual(self.drive([full("U")], [full("T", kind="release")], done), (0, 1))
        red = {"status": "completed", "conclusion": "failure", "html_url": "u"}
        self.assertEqual(self.drive([], [full("T", kind="release", verdict="red")], red), (1, 1))

    def test_a_targeted_or_ci_run_never_stands_in_for_the_tier(self):
        done = {"status": "completed", "conclusion": "success", "html_url": "u"}
        self.assertEqual(self.drive([full("T", kind="pr"), full("T", shard="ci")], [], done), (1, 1))

    def test_a_tier_that_does_not_finish_in_time_refuses(self):
        self.assertEqual(self.drive([], [], {"status": "in_progress", "html_url": "u"}), (1, 1))

if __name__ == "__main__":
    unittest.main(verbosity=1)
