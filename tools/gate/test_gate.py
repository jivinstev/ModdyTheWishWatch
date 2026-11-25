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
            a = types.SimpleNamespace(shard="ci,default", classes=None, classes_file=None, not_classes=None,
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


if __name__ == "__main__":
    unittest.main(verbosity=1)
