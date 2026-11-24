#!/usr/bin/env python3
"""Tests for tools/refresh-cbscript-bundle.sh. Hermetic: no network, no real compiler, no real ply.

    python3 tools/test-refresh-cbscript-bundle.py

The script always works on the checkout it sits in, so each test copies it into a throwaway "Moddy" tree
(tools/ + a seed src/main/resources/tools/cbscript.zip, whose top-level names are the entry list the script
derives) and builds from a tiny local fixture compiler repo through CBSCRIPT_DIR, with a stand-in ply on
PYTHONPATH. Needs bash, git, zip and unzip, as the script itself does.
"""
import hashlib
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "refresh-cbscript-bundle.sh"
REPO_URL = "https://github.com/jivinstev/moddy-cbscript"

# What the bundle being replaced held (the script derives its entry list from the top-level names).
SEED = ["compile_once.py", "pkg/__init__.py", "pkg/mod.py", "Scripts/lib.cblib", "ply/__init__.py",
        "parsetab.py", "requirements.txt"]

FIXTURE = {
    "LICENSE": "Mozilla Public License Version 2.0\n==================================\n",
    # The real compiler's import makes ply write parsetab.py beside it; the stand-in does the same.
    "compile_once.py": "import ply.yacc\nimport helper\nimport pkg.mod\nply.yacc.yacc(__file__)\n",
    "helper.py": "VALUE = 1\n",
    "test_helper.py": "import helper\n",                     # a test: never ships
    "pkg/__init__.py": "",
    "pkg/mod.py": "X = 2\n",
    "Scripts/lib.cblib": "# a library\n",
    "Scripts/blocks.json": "{}\n",
    "Scripts/demo.cbscript": "# a demo world script: never ships\n",
    "requirements.txt": "ply\n",
    "CBSCRIPT_LESSONS.md": "# lessons\n",
    "README.md": "# not in the bundle\n",
}

FAKE_PLY = {
    "ply/__init__.py": "__version__ = 'fixture'\n",
    "ply/yacc.py": (
        "import os\n"
        "def yacc(caller):\n"
        "    out = os.path.join(os.path.dirname(os.path.abspath(caller)), 'parsetab.py')\n"
        "    if not os.path.exists(out):\n"
        "        with open(out, 'w') as f:\n"
        "            f.write('# parsetab.py (fixture)\\n_tabversion = \"fixture\"\\n')\n"
    ),
}

EXPECTED = ["CBSCRIPT_LESSONS.md", "Scripts", "Scripts/blocks.json", "Scripts/lib.cblib", "compile_once.py",
            "helper.py", "parsetab.py", "pkg", "pkg/__init__.py", "pkg/mod.py", "ply", "ply/__init__.py",
            "ply/yacc.py", "requirements.txt"]


def write_tree(root, files):
    for rel, text in files.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)


def git(cwd, *args):
    return subprocess.run(
        ["git", "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "-c", "core.hooksPath=/dev/null",
         "-c", "commit.gpgsign=false", *args],
        cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class RefreshBundleTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        missing = [t for t in ("bash", "git", "zip", "unzip") if shutil.which(t) is None]
        if missing:
            raise RuntimeError("refresh-cbscript-bundle needs " + ", ".join(missing) + " on PATH, and so does its test")

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        tmp = pathlib.Path(self._tmp.name)
        self.moddy = tmp / "moddy"
        (self.moddy / "tools").mkdir(parents=True)
        shutil.copy2(SCRIPT, self.moddy / "tools" / SCRIPT.name)
        self.zip = self.moddy / "src/main/resources/tools/cbscript.zip"
        self.source_file = self.zip.with_name("cbscript.SOURCE")
        self.zip.parent.mkdir(parents=True)
        with zipfile.ZipFile(self.zip, "w") as z:
            for name in SEED:
                z.writestr(name, "# seed\n")
        self.fixture = tmp / "cbscript"
        self.fixture.mkdir()
        write_tree(self.fixture, FIXTURE)
        git(self.fixture, "init", "-q", "-b", "mc/test")
        git(self.fixture, "add", "-A")
        git(self.fixture, "commit", "-q", "-m", "fixture")
        self.ply = tmp / "fakeply"
        write_tree(self.ply, FAKE_PLY)

    def tearDown(self):
        self._tmp.cleanup()

    def run_tool(self, *args, cbscript_dir=None):
        env = dict(os.environ)
        env.pop("CBSCRIPT_REF", None)
        env["CBSCRIPT_DIR"] = str(cbscript_dir or self.fixture)
        env["PYTHONPATH"] = str(self.ply)
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        return subprocess.run(["bash", str(self.moddy / "tools" / SCRIPT.name), *args],
                              cwd=self.moddy, env=env, capture_output=True, text=True)

    def ok(self, *args):
        r = self.run_tool(*args)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return r

    def source(self):
        out = {}
        for line in self.source_file.read_text().splitlines():
            k, _, v = line.partition(":")
            out[k.strip()] = v.strip()
        return out

    def test_layout_order_and_fixed_metadata(self):
        self.ok()
        with zipfile.ZipFile(self.zip) as z:
            infos = z.infolist()
            self.assertEqual([i.filename.rstrip("/") for i in infos], EXPECTED,
                             "the runtime files only (no tests, no demo scripts, no README), sorted")
            for i in infos:
                self.assertEqual(i.date_time, (1980, 1, 1, 0, 0, 0), i.filename)
                self.assertEqual(i.extra, b"", i.filename + " carries extra metadata")
            self.assertEqual(z.read("helper.py").decode(), FIXTURE["helper.py"])
            self.assertEqual(z.read("ply/yacc.py").decode(), FAKE_PLY["ply/yacc.py"], "ply is vendored")
            self.assertIn(b"fixture", z.read("parsetab.py"), "parsetab.py is generated into the bundle")

    def test_source_names_the_commit_and_the_zip(self):
        self.ok()
        s = self.source()
        self.assertEqual(s, {
            "repo": REPO_URL,
            "branch": "mc/test",
            "commit": git(self.fixture, "rev-parse", "HEAD"),
            "license": "MPL-2.0",
            "zip-sha256": sha256(self.zip),
            "local": "true",
        })

    def test_the_same_commit_gives_the_same_bytes(self):
        self.ok()
        first, first_source = self.zip.read_bytes(), self.source_file.read_text()
        self.ok()
        self.assertEqual(self.zip.read_bytes(), first, "a rebuild at the same commit moved the zip's bytes")
        self.assertEqual(self.source_file.read_text(), first_source)

    def test_a_dirty_checkout_is_refused(self):
        self.ok()
        before = self.zip.read_bytes()
        (self.fixture / "helper.py").write_text("VALUE = 'uncommitted'\n")
        r = self.run_tool()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("uncommitted changes", r.stderr)
        self.assertEqual(self.zip.read_bytes(), before, "a refused build must leave the zip alone")

    def test_not_a_checkout_is_refused(self):
        plain = pathlib.Path(self._tmp.name) / "plain"
        write_tree(plain, FIXTURE)
        r = self.run_tool(cbscript_dir=plain)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("not a git checkout", r.stderr)

    def test_source_only_names_a_matching_zip_and_refuses_a_mismatch(self):
        self.ok()
        built = self.zip.read_bytes()
        self.source_file.unlink()
        r = self.ok("--source-only")                                # GREEN: the zip IS this commit
        self.assertIn("file for file", r.stdout)
        self.assertEqual(self.zip.read_bytes(), built, "--source-only never rewrites the zip")
        self.assertEqual(self.source()["zip-sha256"], sha256(self.zip))
        good_source = self.source_file.read_text()

        (self.fixture / "helper.py").write_text("VALUE = 3\n")     # RED: a newer commit the zip is not
        git(self.fixture, "commit", "-q", "-am", "newer")
        r = self.run_tool("--source-only")
        self.assertEqual(r.returncode, 5, r.stdout + r.stderr)
        self.assertIn("is NOT", r.stderr)
        self.assertEqual(self.source_file.read_text(), good_source, "a refused --source-only must not write SOURCE")
        self.assertEqual(self.zip.read_bytes(), built)

    def test_a_licence_that_is_not_mpl_is_refused(self):
        (self.fixture / "LICENSE").write_text("All rights reserved\n")
        git(self.fixture, "commit", "-q", "-am", "licence")
        r = self.run_tool()
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertIn("MPL-2.0", r.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
