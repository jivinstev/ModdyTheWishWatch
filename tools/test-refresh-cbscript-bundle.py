#!/usr/bin/env python3
"""Tests for tools/refresh-cbscript-bundle.sh. Hermetic: no network, no real compiler.

    python3 tools/test-refresh-cbscript-bundle.py

The script always works on the checkout it sits in, so each test copies it into a throwaway "Moddy" tree (tools/)
and vendors from a tiny local fixture compiler repo through CBSCRIPT_DIR. Needs bash, git and diff, as the script
itself does.
"""
import hashlib
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "refresh-cbscript-bundle.sh"
REPO_URL = "https://github.com/jivinstev/moddy-cbscript"
OUT = "third_party/moddy-cbscript"
PKG = "java/src/main/java/com/moddingfromamod/cbscript"

FIXTURE = {
    "LICENSE": "Mozilla Public License Version 2.0\n==================================\n",
    "NOTICE.md": "# CBScript, used with permission\n",
    "README.md": "# the fork's README: not vendored\n",
    "CBSCRIPT_LESSONS.md": "# lessons\n",
    "compile_once.py": "# the Python compiler: not vendored\n",
    PKG + "/CbScript.java": "package com.moddingfromamod.cbscript;\npublic final class CbScript {}\n",
    PKG + "/parse/Tables.java": "package com.moddingfromamod.cbscript.parse;\nfinal class Tables {}\n",
    "java/src/main/resources/com/moddingfromamod/cbscript/parse/tables.txt": "state 0\n",
    "java/src/test/java/com/moddingfromamod/cbscript/SomeTest.java": "// a test: never vendored\n",
    "java/THIRD_PARTY.md": "# fdlibm notice\n",
    "java/build.sh": "# not vendored\n",
    "Scripts/lib.cblib": "# a library\n",
    "Scripts/blocks.json": "{}\n",
    "Scripts/demo.cbscript": "# a demo world script: never vendored\n",
}

EXPECTED = [
    "LICENSE", "NOTICE.md", "README.md", "THIRD_PARTY.md",
    "src/main/java/com/moddingfromamod/cbscript/CbScript.java",
    "src/main/java/com/moddingfromamod/cbscript/parse/Tables.java",
    "src/main/resources/com/moddingfromamod/cbscript/cbscript-lessons.md",
    "src/main/resources/com/moddingfromamod/cbscript/lib/lib.cblib",
    "src/main/resources/com/moddingfromamod/cbscript/parse/tables.txt",
]


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


def files_of(root):
    return sorted(str(p.relative_to(root)).replace(os.sep, "/") for p in root.rglob("*") if p.is_file())


def tree_sha256(root):
    """What CbScriptBundleSourceTest computes: every file's path and bytes, sorted by path."""
    h = hashlib.sha256()
    for rel in files_of(root):
        h.update(rel.encode("utf-8") + b"\0")
        h.update((root / rel).read_bytes() + b"\0")
    return h.hexdigest()


class RefreshBundleTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        missing = [t for t in ("bash", "git", "diff") if shutil.which(t) is None]
        if missing:
            raise RuntimeError("refresh-cbscript-bundle needs " + ", ".join(missing) + " on PATH, and so does its test")

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        tmp = pathlib.Path(self._tmp.name)
        self.moddy = tmp / "moddy"
        (self.moddy / "tools").mkdir(parents=True)
        shutil.copy2(SCRIPT, self.moddy / "tools" / SCRIPT.name)
        self.tree = self.moddy / OUT
        self.source_file = self.moddy / "src/main/resources/tools/cbscript.SOURCE"
        self.source_file.parent.mkdir(parents=True)
        self.fixture = tmp / "cbscript"
        self.fixture.mkdir()
        write_tree(self.fixture, FIXTURE)
        git(self.fixture, "init", "-q", "-b", "mc/test")
        git(self.fixture, "add", "-A")
        git(self.fixture, "commit", "-q", "-m", "fixture")

    def tearDown(self):
        self._tmp.cleanup()

    def run_tool(self, *args, cbscript_dir=None):
        env = dict(os.environ)
        env.pop("CBSCRIPT_REF", None)
        env["CBSCRIPT_DIR"] = str(cbscript_dir or self.fixture)
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

    def test_vendors_the_java_compiler_its_libraries_lessons_and_notices_only(self):
        self.ok()
        self.assertEqual(files_of(self.tree), EXPECTED,
                         "the Java sources and resources, the .cblib libraries, the lessons and the notices; no Python, "
                         "no tests, no demo scripts")
        self.assertEqual((self.tree / "src/main/java/com/moddingfromamod/cbscript/CbScript.java").read_text(),
                         FIXTURE[PKG + "/CbScript.java"])
        self.assertIn("Do not edit by hand", (self.tree / "README.md").read_text())

    def test_source_names_the_commit_and_the_tree(self):
        self.ok()
        self.assertEqual(self.source(), {
            "repo": REPO_URL,
            "branch": "mc/test",
            "commit": git(self.fixture, "rev-parse", "HEAD"),
            "license": "MPL-2.0",
            "tree": OUT,
            "tree-sha256": tree_sha256(self.tree),
            "local": "true",
        })

    def test_the_same_commit_gives_the_same_tree(self):
        self.ok()
        first, first_source = tree_sha256(self.tree), self.source_file.read_text()
        self.ok()
        self.assertEqual(tree_sha256(self.tree), first, "a re-vendor at the same commit moved the tree")
        self.assertEqual(self.source_file.read_text(), first_source)

    def test_a_file_gone_upstream_is_gone_here(self):
        self.ok()
        git(self.fixture, "rm", "-q", PKG + "/parse/Tables.java")
        git(self.fixture, "commit", "-q", "-m", "gone")
        self.ok()
        self.assertNotIn("src/main/java/com/moddingfromamod/cbscript/parse/Tables.java", files_of(self.tree))

    def test_a_dirty_checkout_is_refused(self):
        self.ok()
        before = tree_sha256(self.tree)
        (self.fixture / PKG / "CbScript.java").write_text("// uncommitted\n")
        r = self.run_tool()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("uncommitted changes", r.stderr)
        self.assertEqual(tree_sha256(self.tree), before, "a refused vendor must leave the tree alone")

    def test_not_a_checkout_is_refused(self):
        plain = pathlib.Path(self._tmp.name) / "plain"
        write_tree(plain, FIXTURE)
        r = self.run_tool(cbscript_dir=plain)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("not a git checkout", r.stderr)

    def test_a_checkout_with_no_java_compiler_is_refused(self):
        git(self.fixture, "rm", "-rq", "java/src/main/java")
        git(self.fixture, "commit", "-q", "-m", "no java")
        r = self.run_tool()
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertIn("no Java compiler", r.stderr)

    def test_source_only_names_a_matching_tree_and_refuses_a_mismatch(self):
        self.ok()
        built = tree_sha256(self.tree)
        self.source_file.unlink()
        r = self.ok("--source-only")                                # GREEN: the tree IS this commit
        self.assertIn("file for file", r.stdout)
        self.assertEqual(tree_sha256(self.tree), built, "--source-only never rewrites the tree")
        self.assertEqual(self.source()["tree-sha256"], built)
        good_source = self.source_file.read_text()

        (self.fixture / PKG / "CbScript.java").write_text("// newer\n")     # RED: a newer commit the tree is not
        git(self.fixture, "commit", "-q", "-am", "newer")
        r = self.run_tool("--source-only")
        self.assertEqual(r.returncode, 5, r.stdout + r.stderr)
        self.assertIn("is NOT", r.stderr)
        self.assertEqual(self.source_file.read_text(), good_source, "a refused --source-only must not write SOURCE")
        self.assertEqual(tree_sha256(self.tree), built)

    def test_a_licence_that_is_not_mpl_is_refused(self):
        (self.fixture / "LICENSE").write_text("All rights reserved\n")
        git(self.fixture, "commit", "-q", "-am", "licence")
        r = self.run_tool()
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertIn("MPL-2.0", r.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
