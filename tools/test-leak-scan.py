#!/usr/bin/env python3
"""Tests for tools/leak-scan.py. Hermetic: every forbidden list and file is a fixture.

    python3 tools/test-leak-scan.py

Each rule is driven RED (a planted leak is found) and GREEN (its look-alike is not), because a scan that
finds nothing proves nothing until it has been seen to find something.
"""
import contextlib
import io
import json
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
SCAN = HERE / "leak-scan.py"


def _load():
    # Exec the source rather than import it: this repo's convention (bytecode caches can outlive an edit).
    ns = {"__name__": "_leak_scan", "__file__": str(SCAN)}
    exec(compile(SCAN.read_text(), str(SCAN), "exec"), ns)
    return ns


LS = _load()


# Real-SHAPED values, assembled at run time so this file is not itself a leak the scan would report.
EMAIL = "someone.real" + "@" + "gmail.com"
IP = ".".join(["34", "12", "200", "7"])
BUCKET = "gs:" + "//acme-prod-logs"
KEY = ("-----BEGIN RSA " + "PRIVATE KEY-----\n"           # a header WITH a body: a header alone is a doc
       + __import__("base64").b64encode(__import__("hashlib").sha512(b"fixture").digest()).decode())
ROBIN = "Rob" + "in"


class Fixture:
    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def file(self, rel, text):
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        return rel

    def scan(self, rels, forbidden_text=None, show=False):
        f = None
        if forbidden_text is not None:
            lst = self.root / "private" / "forbidden.txt"
            lst.parent.mkdir(parents=True, exist_ok=True)
            lst.write_text(forbidden_text)
            f = LS["Forbidden"].load(lst)
        return LS["scan"](rels, f, root=self.root, show=show)


class BuiltInRules(unittest.TestCase):

    def setUp(self):
        self.fx = Fixture()

    def tearDown(self):
        self.fx.tmp.cleanup()

    def cats(self, text, name="a.java"):
        return [c for _r, _n, c, _w in self.fx.scan([self.fx.file(name, text)])]

    def test_an_email_is_found_and_a_placeholder_is_not(self):
        self.assertEqual(self.cats("mail me at " + EMAIL), ["email"])
        self.assertEqual(self.cats("bob@parents.example and noreply@anthropic.com and x@example.com"), [])

    def test_an_email_in_a_licence_notice_is_attribution(self):
        self.assertEqual(self.cats("Copyright (c) Kevin <kevina" + "@users.sourceforge.net>", "words.NOTICE.txt"), [])
        self.assertEqual(self.cats("Copyright (c) Kevin <kevina" + "@users.sourceforge.net>", "third_party/scowl.LICENSE.txt"), [])
        self.assertEqual(self.cats("Copyright (c) Kevin <kevina" + "@users.sourceforge.net>", "third_partyish/notes.txt"), ["email"])

    def test_a_routable_ip_is_found_and_a_private_or_version_one_is_not(self):
        self.assertEqual(self.cats("door at " + IP + " today"), ["ip"])
        for benign in ("127.0.0.1", "10.0.0.4", "192.168.1.20", "1.2.3.4", "198.51.100.3", "100.64.0.1",
                       "neoforge 21.8.57", "version 1.21.8.0.1"):
            self.assertEqual(self.cats(benign), [], benign)

    def test_a_bucket_url_is_found_and_a_placeholder_is_not(self):
        self.assertEqual(self.cats("gsutil cp x " + BUCKET + "/x"), ["bucket"])
        self.assertEqual(self.cats("gs://bucket/x gs://example-thing"), [])

    def test_telegram_and_secrets(self):
        self.assertEqual(self.cats("token 123456789:" + "A" * 35), ["telegram"])
        self.assertEqual(self.cats(KEY), ["secret"])
        self.assertEqual(self.cats("-----BEGIN RSA " + "PRIVATE KEY----- is the header a doc may name"), [])
        self.assertEqual(self.cats("x = 'gh" + "p_" + "aB3dE6gH9jK2mN5pQ8sT1vW4yZ7bC0eF3hJ6'"), ["secret"])

    def test_a_live_secret_value_is_refused_and_never_echoed(self):
        live = __import__("hashlib").sha256(b"live").hexdigest()[:20] + "Zq9" + "Xw" * 6
        LS["_LIVE"] = LS["SECRETS"]["LiveGuard"]({"SOME_API_KEY": live})
        try:
            found = self.fx.scan([self.fx.file("a.txt", "x " + live + "\n")], show=True)
        finally:
            LS["_LIVE"] = None
        self.assertEqual([c for _r, _n, c, _w in found], ["secret"])
        self.assertIn("$SOME_API_KEY", found[0][3])
        self.assertNotIn(live, repr(found))

    def test_a_child_data_file_is_found_by_its_name(self):
        rel = self.fx.file("src/main/resources/corpus/real-wishes.jsonl", "{}\n")
        self.assertEqual([c for _r, _n, c, _w in self.fx.scan([rel])], ["child-data"])
        rel = self.fx.file("corpus/wish-runs/2026-01-01-x/manifest.json", "{}\n")
        self.assertEqual([c for _r, _n, c, _w in self.fx.scan([rel])], ["child-data"])

    def test_what_matched_is_masked_unless_asked(self):
        rel = self.fx.file("a.txt", EMAIL)
        (_r, _n, _c, what), = self.fx.scan([rel])
        self.assertNotIn("someone.real", what)
        (_r, _n, _c, what), = self.fx.scan([rel], show=True)
        self.assertIn(EMAIL, what)


class TheForbiddenList(unittest.TestCase):

    def setUp(self):
        self.fx = Fixture()

    def tearDown(self):
        self.fx.tmp.cleanup()

    def test_a_term_matches_whole_words_case_insensitively(self):
        lst = "[names]\n" + ROBIN + "\n"
        hit = self.fx.file("a.java", 'new Player("' + ROBIN.upper() + '");')
        miss = self.fx.file("b.java", ROBIN.lower() + "ia trees and " + ROBIN + "son")
        self.assertEqual([c for _r, _n, c, _w in self.fx.scan([hit], lst)], ["names"])
        self.assertEqual(self.fx.scan([miss], lst), [])

    def test_a_regex_an_allow_and_a_skip(self):
        lst = "[hosts]\nre:\\b(?!www\\.)[a-z]+\\.acme\\.test\\b\nallow:^ok\\.acme\nskip:docs/*\n"
        a = self.fx.file("a.java", "join admin.acme.test or www.acme.test or ok.acme.test")
        b = self.fx.file("docs/x.md", "admin.acme.test")
        found = self.fx.scan([a, b], lst)
        self.assertEqual([(r, c) for r, _n, c, _w in found], [("a.java", "hosts")])

    def test_an_except_holds_back_ONE_section_and_no_other(self):
        lst = "[brands]\nre:\\bmegacorp\\b\nexcept:data/*\n[hosts]\nre:\\bintra\\.acme\\.test\\b\n"
        a = self.fx.file("a.java", "megacorp")
        b = self.fx.file("data/rows.jsonl", "megacorp at intra.acme.test")
        found = self.fx.scan([a, b], lst)
        self.assertEqual(sorted((r, c) for r, _n, c, _w in found),
                         [("a.java", "brands"), ("data/rows.jsonl", "hosts")],
                         "the excepted file still answers to every other section")

    def test_phrases_are_matched_folded_and_NEVER_printed(self):
        self.fx.file("private/wishes.jsonl",
                     json.dumps({"prompt": "Build me a GIANT purple dragon castle!"}) + "\n"
                     + json.dumps({"prompt": "make a cow"}) + "\n")
        lst = "[child-words]\nphrases:wishes.jsonl\n"
        rel = self.fx.file("T.java", '"build me a giant purple   dragon castle"')
        found = self.fx.scan([rel], lst, show=True)
        self.assertEqual(len(found), 1)
        self.assertNotIn("dragon", found[0][3], "a phrase was printed")
        # "make a cow" is three words: below the minimum, so a common sentence cannot fire everywhere.
        self.assertEqual(self.fx.scan([self.fx.file("U.java", "make a cow")], lst), [])

    def test_minwords_raises_the_phrase_floor(self):
        self.fx.file("private/w.jsonl", json.dumps({"prompt": "make it rain chickens"}) + "\n")
        rel = self.fx.file("T.java", "make it rain chickens")
        self.assertEqual(len(self.fx.scan([rel], "phrases:w.jsonl\n")), 1)
        self.assertEqual(self.fx.scan([rel], "minwords:6\nphrases:w.jsonl\n"), [])

    def test_a_path_rule(self):
        lst = "path:secret/*\n"
        rel = self.fx.file("secret/notes.txt", "nothing")
        self.assertEqual([c for _r, _n, c, _w in self.fx.scan([rel], lst)], ["forbidden"])

    def test_a_bad_regex_or_missing_phrase_file_is_an_error_not_a_quiet_skip(self):
        for bad in ("re:(unclosed\n", "phrases:nope.jsonl\n"):
            lst = self.fx.root / "private" / "bad.txt"
            lst.parent.mkdir(parents=True, exist_ok=True)
            lst.write_text(bad)
            with self.assertRaises(ValueError):
                LS["Forbidden"].load(lst)

    def test_a_named_list_that_is_missing_exits_2(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            rc = LS["main"](["--forbidden", str(self.fx.root / "absent.txt"), "README.md"])
        self.assertEqual(rc, 2)
        self.assertIn("refusing", err.getvalue())


class WhichFilesArePublic(unittest.TestCase):
    """One definition of the public cut: in this repo the scan reads tools/public-tree.py's list."""

    def test_the_default_set_is_public_trees_list(self):
        tree = HERE / "public-tree.py"
        if not tree.exists():
            self.skipTest("the public repo has no public-tree.py: the default set is every tracked file")
        ns = {"__name__": "_pt", "__file__": str(tree)}
        exec(compile(tree.read_text(), str(tree), "exec"), ns)
        self.assertEqual(sorted(LS["public_files"]()), sorted(ns["public_paths"]()[0]))
        for p in ("tools/leak-forbidden.txt", "corpus/safety-heldout.jsonl", "corpus/safety-floor-heldout.json",
                  "src/main/resources/corpus/real-wishes.jsonl", "docs/public-repo/split.json"):
            self.assertIs(ns["decide"](p, ns["load_split"]())[0], False, p)


class StructuralMarkers(unittest.TestCase):
    """The private side's SHAPE is safe to publish as a rule, so the public CI runs it with no list."""

    def test_private_paths_are_findings_and_their_neighbours_are_not(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            red = ["src/main/java/com/moddingfromamod/isle/Isle.java", "src/test/java/com/moddingfromamod/ops/T.java",
                   "src/private/java/X.java", "docs/public-repo/SERVER_CONFIG.md", "tools/leak-forbidden.txt",
                   "docs/public-repo/split.json", "src/main/resources/moddingfromamod.private.mixins.json"]
            green = ["src/main/java/com/moddingfromamod/wish/Isle.java", "src/main/java/com/moddingfromamod/config/C.java",
                     "docs/EXTENDING.md", "src/main/resources/moddingfromamod.mixins.json"]
            for rel in red + green:
                (root / rel).parent.mkdir(parents=True, exist_ok=True)
                (root / rel).write_text("x\n")
            found = {f[0] for f in LS["scan"](red + green, root=root) if f[2] == "private-work"}
            self.assertEqual(found, set(red))


class PrivateListRules(unittest.TestCase):
    """The REAL private list, section by section: each red sample is found under its section and each green one is
    not. The samples live beside the list, in the private tools/leak-forbidden-cases.json, because a sample names what
    must not leak; both are absent from the public repo, so this skips there."""

    LIST, CASES = HERE / "leak-forbidden.txt", HERE / "leak-forbidden-cases.json"

    def setUp(self):
        if not (self.LIST.exists() and self.CASES.exists()):
            self.skipTest("no private forbidden list here")
        self.fb = LS["Forbidden"].load(self.LIST)
        self.cases = {k: v for k, v in json.loads(self.CASES.read_text()).items() if not k.startswith("_")}

    def cats(self, text):
        with tempfile.TemporaryDirectory() as d:
            (pathlib.Path(d) / "X.java").write_text(text + "\n")
            return {c for _r, _n, c, _w in LS["scan"](["X.java"], self.fb, root=pathlib.Path(d))}

    def test_every_section_with_samples_finds_its_red_and_passes_its_green(self):
        self.assertTrue(self.cases)
        for section, s in self.cases.items():
            self.assertTrue(s["red"] and s["green"], section)
            for red in s["red"]:
                self.assertIn(section, self.cats(red), f"{section}: a red sample was not found")
            for green in s["green"]:
                self.assertNotIn(section, self.cats(green), f"{section}: a green sample was found")


if __name__ == "__main__":
    unittest.main(verbosity=1 if "-q" in sys.argv else 2)
