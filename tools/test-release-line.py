#!/usr/bin/env python3
"""Tests .github/workflows/release-line.yml: its port-only path list, and its check run on fake commits.

The check's own shell script is taken out of the workflow and run in a scratch repository, so what is tested is what
CI runs. Run: python3 tools/test-release-line.py
"""
import os
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKFLOW = os.path.join(ROOT, ".github", "workflows", "release-line.yml")

# Must match MMVU's round records (jivinstev/mc-mod-version-upgrade issue 354): what the weekly round commits straight
# to a release line is the known-issues ledger (under .port/), the README Ports block, and the regenerated Port CI
# workflows. Change this list and MMVU's together.
EXPECTED_PORT_ONLY = [".github/workflows/port-ci*.yml", ".port/*", "README.md"]


def workflow_text():
    with open(WORKFLOW, encoding="utf-8") as f:
        return f.read()


def port_only_patterns(text):
    block = re.search(r"# port-only paths: begin\n(.*?)# port-only paths: end", text, re.S)
    if not block:
        raise AssertionError("release-line.yml has no '# port-only paths: begin/end' block")
    return re.findall(r"^\s*([^\s*]\S*)\)(?: ;;)?$", block.group(1), re.M)


def check_script(text):
    m = re.search(r"- name: Every release-line commit's patch is on main\n\s+run: \|\n(.*)", text, re.S)
    lines = m.group(1).split("\n")
    indent = len(lines[0]) - len(lines[0].lstrip())
    out = []
    for line in lines:
        if line.strip() and len(line) - len(line.lstrip()) < indent:
            break
        out.append(line[indent:])
    return "\n".join(out)


def git(cwd, *args):
    env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.invalid",
               GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.invalid")
    return subprocess.run(["git", *args], cwd=cwd, env=env, check=True, capture_output=True, text=True).stdout


class PortOnlyList(unittest.TestCase):
    def test_list_matches_round_records(self):
        self.assertEqual(EXPECTED_PORT_ONLY, port_only_patterns(workflow_text()))


class CheckOnFakeCommits(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.origin = os.path.join(self.tmp.name, "origin.git")
        self.work = os.path.join(self.tmp.name, "work")
        git(self.tmp.name, "init", "-q", "--bare", "-b", "main", self.origin)
        git(self.tmp.name, "init", "-q", "-b", "main", self.work)
        git(self.work, "remote", "add", "origin", self.origin)
        self.write("src/A.java", "class A {}\n")
        self.write(".github/workflows/port-ci.yml", "name: Port CI\n")
        self.write("README.md", "# Mod\n<!-- ports:begin -->\nold\n<!-- ports:end -->\nend\n")
        self.commit("base")
        git(self.work, "push", "-q", "origin", "main")
        git(self.work, "checkout", "-q", "-b", "neoforge-1.21.8")

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, path, content):
        full = os.path.join(self.work, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(content)

    def commit(self, msg):
        git(self.work, "add", "-A")
        git(self.work, "commit", "-q", "-m", msg)

    def run_check(self):
        summary = os.path.join(self.tmp.name, "summary")
        r = subprocess.run(["bash", "-c", check_script(workflow_text())], cwd=self.work, capture_output=True,
                           text=True, env=dict(os.environ, GITHUB_STEP_SUMMARY=summary))
        return r.returncode, r.stdout + r.stderr

    def test_port_ci_only_commit_passes(self):
        self.write(".github/workflows/port-ci.yml", "name: Port CI\n# kit v94\n")
        self.commit("regenerate port-ci.yml")
        code, out = self.run_check()
        self.assertEqual(0, code, out)

    def test_port_dir_and_readme_block_pass(self):
        self.write(".port/KNOWN-ISSUES.json", "{}\n")
        self.write("README.md", "# Mod\n<!-- ports:begin -->\nnew\n<!-- ports:end -->\nend\n")
        self.commit("round records")
        code, out = self.run_check()
        self.assertEqual(0, code, out)

    def test_readme_outside_block_fails(self):
        self.write("README.md", "# Mod changed\n<!-- ports:begin -->\nold\n<!-- ports:end -->\nend\n")
        self.commit("readme prose")
        code, out = self.run_check()
        self.assertEqual(1, code, out)

    def test_java_change_fails(self):
        self.write("src/A.java", "class A { int x; }\n")
        self.commit("hotfix on the release line alone")
        code, out = self.run_check()
        self.assertEqual(1, code, out)
        self.assertIn("::error::", out)

    def test_port_ci_mixed_with_java_fails(self):
        self.write(".github/workflows/port-ci.yml", "name: Port CI\n# kit v94\n")
        self.write("src/A.java", "class A { int x; }\n")
        self.commit("port-ci and code")
        code, out = self.run_check()
        self.assertEqual(1, code, out)

    def test_java_cherry_picked_from_main_passes(self):
        git(self.work, "checkout", "-q", "main")
        self.write("src/A.java", "class A { int x; }\n")
        self.commit("fix on main")
        git(self.work, "push", "-q", "origin", "main")
        fix = git(self.work, "rev-parse", "HEAD").strip()
        git(self.work, "checkout", "-q", "neoforge-1.21.8")
        self.write(".port/KNOWN-ISSUES.json", "{}\n")
        self.commit("records first")
        git(self.work, "cherry-pick", fix)
        code, out = self.run_check()
        self.assertEqual(0, code, out)


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
