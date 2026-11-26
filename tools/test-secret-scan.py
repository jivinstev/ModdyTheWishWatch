#!/usr/bin/env python3
"""Tests for tools/secret_patterns.py and tools/secret-scan.py. Hermetic: every token is
synthesised at run time from a hash (so this file is not itself a hit), every repo is a temp dir, and every "live"
secret is a made-up value in a made-up environment.

    python3 tools/test-secret-scan.py

Each shape is RED on a synthetic credential and GREEN on its near-misses (one character short, a placeholder, a
variable reference); the live guard finds a value wrapped, truncated or inside a decoded service-account key; no
output ever carries a value; and the git hooks refuse a commit and a push that add one.
"""
import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCAN = HERE / "secret-scan.py"
NS = {"__name__": "_sp", "__file__": str(HERE / "secret_patterns.py")}
exec(compile((HERE / "secret_patterns.py").read_text(encoding="utf-8"), "secret_patterns.py", "exec"), NS)
find_shapes, looks_fake, LiveGuard = NS["find_shapes"], NS["looks_fake"], NS["LiveGuard"]

ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"


def rnd(n, alphabet=ALNUM, seed="s"):
    """N characters that look minted: drawn from a hash stream, re-seeded until nothing in them reads as a fake."""
    k = 0
    while True:
        out, i = [], 0
        while len(out) < n:
            for b in hashlib.sha256(f"{seed}/{k}/{i}".encode()).digest():
                out.append(alphabet[b % len(alphabet)])
            i += 1
        s = "".join(out[:n])
        mixed = any(c.isdigit() for c in alphabet) and any(c.isalpha() for c in alphabet)
        if not looks_fake(s) and (not mixed or (any(c.isdigit() for c in s) and any(c.isalpha() for c in s))):
            return s
        k += 1


def ids(text):
    return sorted({rid for _l, rid, _d, _n in find_shapes(text)})


P = "-"  # glue, so no token below appears literally in this file
CASES = {   # rule id: (a synthetic credential in context, [near-misses that must stay green])
    "private-key": (f"-----BEGIN RSA PRIVATE KEY{P}----\n{rnd(64, ALNUM + '+/')}\n",
                    [f"-----BEGIN RSA PRIVATE KEY{P}---- is the header", f"-----BEGIN PUBLIC KEY{P}----\n" + rnd(64)]),
    "gcp-service-account": ('"private_key_id": "' + rnd(40, "0123456789abcdef") + '"',
                            ['"private_key_id": "' + rnd(39, "0123456789abcdef") + '"']),
    "google-api-key": ("key=AI" + "za" + rnd(35, ALNUM + "_-"), ["AI" + "za" + rnd(20)]),
    "google-oauth-access": ("ya" + "29." + rnd(60, ALNUM + "_-"), ["ya" + "29.short"]),
    "google-oauth-refresh": ("1/" + "/0" + rnd(60, ALNUM + "_-"), ["https:/" + "/0example.com/x"]),
    "google-client-secret": ("GOCSPX" + "-" + rnd(28), ["GOCSPX" + "-" + rnd(20)]),
    "aws-access-key": ("AK" + "IA" + rnd(16, "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"),
                       ["AK" + "IAIOSFODNN7EXAMPLE", "AK" + "IA" + rnd(15, "ABCDEFGHIJKLMNOPQRSTUVWXYZ")]),
    "aws-secret-key": ("aws_secret_access_key = " + rnd(40, ALNUM + "/+"), ["aws_secret_access_key = ${AWS_SECRET}"]),
    "github-token": ("gh" + "p_" + rnd(36), ["gh" + "p_" + rnd(30), "gh" + "p_" + "x" * 36]),
    "github-pat": ("github" + "_pat_" + rnd(82, ALNUM + "_"), ["github" + "_pat_" + rnd(20)]),
    "anthropic-key": ("sk-" + "ant-api03-" + rnd(90, ALNUM + "_-"), ["sk-" + "ant-..."]),
    "openai-key": ("sk-" + "proj-" + rnd(120, ALNUM + "_-"), ["sk-" + "proj-" + rnd(20)]),
    "muse-key": ("LL" + "M_" + rnd(16) + "_" + rnd(27), ["LL" + "M_" + rnd(6) + "_x"]),
    "slack-token": ("xo" + "xb-" + rnd(12, "0123456789") + "-" + rnd(24), ["xo" + "xb-short"]),
    "slack-webhook": ("https://hooks.slack" + ".com/services/T" + rnd(9, "ABCDEFGHJ0123456789") + "/B"
                      + rnd(9, "ABCDEFGHJ0123456789") + "/" + rnd(24), ["https://hooks.slack" + ".com/services/"]),
    "stripe-key": ("sk" + "_live_" + rnd(24), ["sk" + "_test_" + rnd(24)]),
    "telegram-token": ("bot " + rnd(10, "0123456789") + ":A" + "A" + rnd(33, ALNUM + "_-"), ["12:34"]),
    "discord-token": ("M" + rnd(23, ALNUM) + "." + rnd(6) + "." + rnd(27), ["M.a.b"]),
    "discord-webhook": ("https://discord" + ".com/api/webhooks/" + rnd(18, "0123456789") + "/" + rnd(68, ALNUM + "_-"),
                        ["https://discord" + ".com/api/webhooks/{id}/{token}"]),
    "modrinth-token": ("mr" + "p_" + rnd(36), ["mr" + "p_" + rnd(10)]),
    "curseforge-key": ("$2" + "a$10$" + rnd(53, ALNUM + "./"), ["$2" + "a$10$" + rnd(30)]),
    "curseforge-token": ("CURSEFORGE_TOKEN=" + "-".join(rnd(n, "0123456789abcdef", seed=str(n)) for n in (8, 4, 4, 4, 12)),
                         ["build_id = " + "-".join(rnd(n, "0123456789abcdef", seed=str(n)) for n in (8, 4, 4, 4, 12))]),
    "npm-token": ("np" + "m_" + rnd(36), ["np" + "m_" + rnd(10)]),
    "pypi-token": ("py" + "pi-AgEIcHlwaS5vcmc" + rnd(60, ALNUM + "_-"), ["py" + "pi-AgE"]),
    "jwt": ("ey" + "J" + rnd(20, ALNUM + "_-") + ".ey" + "J" + rnd(30, ALNUM + "_-") + "." + rnd(43, ALNUM + "_-"),
            ["ey" + "Jhbg.eyJ.sig"]),
    "bearer": ("Authorization: Bear" + "er " + rnd(40, ALNUM + "._-"),
               ["Authorization: Bear" + "er $TOKEN", "Authorization: Bear" + "er ${MUSE_API_KEY}"]),
    "assigned-secret": ("MY_SERVICE_API_KEY=" + rnd(32), ["MY_SERVICE_API_KEY=$MY_SERVICE_API_KEY",
                        "monkey = " + rnd(32), "TOKEN=runGameTestServer-waiter-abc12345", "password = hunter2",
                        'secret = "' + base64.b64encode(b"secret-for-tests").decode() + '"']),
}


class Shapes(unittest.TestCase):
    def test_every_rule_has_a_case(self):
        self.assertEqual(sorted(r.id for r in NS["RULES"]), sorted(CASES))

    def test_each_shape_is_red_and_its_near_misses_green(self):
        for rid, (hit, misses) in CASES.items():
            with self.subTest(rule=rid):
                self.assertIn(rid, ids(hit), f"{rid} missed its synthetic credential")
                for miss in misses:
                    self.assertNotIn(rid, ids(miss), f"{rid} fired on a near-miss")

    def test_fakes_are_not_findings(self):
        for fake in ["gh" + "p_" + "EXAMPLE" + rnd(29), "sk-" + "ant-" + "x" * 40, "mr" + "p_" + "0123456789" * 4,
                     "MY_API_KEY=" + "your-api-key-goes-here-1234", "AK" + "IAIOSFODNN7EXAMPLE"]:
            with self.subTest(fake=fake[:12]):
                self.assertEqual(ids(fake), [])

    def test_the_allow_marker_skips_only_its_line(self):
        tok = "gh" + "p_" + rnd(36)
        self.assertEqual(ids(f"{tok}  # secret-scan" + ": allow"), [])
        self.assertEqual(ids(f"{tok}  # secret-scan" + f": allow\n{tok}\n"), ["github-token"])

    def test_base64_that_decodes_to_a_key_is_found(self):
        inner = json.dumps({"type": "service_account", "private_key_id": rnd(40, "0123456789abcdef"),
                            "private_key": f"-----BEGIN PRIVATE KEY{P}----\n" + rnd(200, ALNUM + "+/") + "\n"})
        blob = base64.b64encode(inner.encode()).decode()
        self.assertTrue(any(r.startswith("base64:") for r in ids("X=" + blob)))

    def test_a_finding_carries_no_matched_text(self):
        tok = "gh" + "p_" + rnd(36)
        for f in find_shapes(f"a\nb {tok}\n"):
            self.assertEqual(f[0], 2)
            self.assertNotIn(tok, repr(f))


class Live(unittest.TestCase):
    def env(self):
        sa = {"type": "service_account", "private_key_id": rnd(40, "0123456789abcdef", seed="kid"),
              "private_key": f"-----BEGIN PRIVATE KEY{P}----\n" + "\n".join(rnd(64, ALNUM + "+/", seed=str(i))
                                                                           for i in range(20)) + "\n"}
        self.sa = sa
        return {"MY_SA_KEY_B64": base64.b64encode(json.dumps(sa).encode()).decode(),
                "MUSEISH_API_KEY": "LL" + "M_" + rnd(16, seed="m") + "_" + rnd(27, seed="n"),
                "GH_TOKEN": "proxy-injected", "GIT_CONFIG_KEY_0": "credential.helper.something.long",
                "GOOGLE_APPLICATION_CREDENTIALS": "/tmp/nowhere.json", "SHORT_TOKEN": "abc", "HOME": "/root",
                "SOME_SECRET": "/etc/secret/path/value/that/is/long"}

    def test_it_holds_only_real_looking_secrets(self):
        g = LiveGuard(self.env())
        self.assertEqual(g.variables(), ["MUSEISH_API_KEY", "MY_SA_KEY_B64"])

    def test_found_whole_truncated_wrapped_and_inside_the_decoded_key(self):
        env = self.env()
        g = LiveGuard(env)
        b64, muse = env["MY_SA_KEY_B64"], env["MUSEISH_API_KEY"]
        self.assertEqual(g.find("x " + muse), [(1, "MUSEISH_API_KEY")])
        self.assertTrue(g.find(muse[:40]), "a truncated value")
        wrapped = "\n".join(b64[i:i + 76] for i in range(0, len(b64), 76))
        self.assertTrue(g.find(wrapped), "a wrapped value")
        escaped = json.dumps(self.sa)                      # the key re-serialised, \\n escapes and all
        self.assertTrue(any(v == "MY_SA_KEY_B64" for _l, v in g.find(escaped)), "the key inside the base64")
        self.assertEqual(g.find("MUSEISH_API_KEY=$MUSEISH_API_KEY"), [])


class Cli(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="secret-scan-test-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_scan(self, *args, env=None, cwd=None, input=None):
        base = {k: v for k, v in os.environ.items() if not NS["_LIVE_NAME"].search(k)}   # only our fake secrets
        return subprocess.run([sys.executable, str(SCAN), *args], capture_output=True, text=True, input=input,
                              cwd=cwd, env={**base, **(env or {})})

    def git(self, repo, *args, env=None, check=True):
        base = {k: v for k, v in os.environ.items() if not NS["_LIVE_NAME"].search(k)}
        r = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True,
                           env={**base, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.com",
                                "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.com", **(env or {})})
        if check and r.returncode:
            raise AssertionError(f"git {args}: {r.stdout}{r.stderr}")
        return r

    def test_files_and_the_value_is_never_printed(self):
        live = "LL" + "M_" + rnd(16, seed="c") + "_" + rnd(27, seed="d")
        f = self.tmp / "a.txt"
        f.write_text("ok\nhere " + live + "\n")
        r = self.run_scan(str(f), env={"FOO_API_KEY": live})
        self.assertEqual(r.returncode, 1)
        self.assertIn("$FOO_API_KEY", r.stdout)
        self.assertIn("[muse-key]", r.stdout)
        self.assertNotIn(live, r.stdout + r.stderr)
        self.assertNotIn(live[:12], r.stdout + r.stderr)
        f.write_text("clean\n")
        self.assertEqual(self.run_scan(str(f), env={"FOO_API_KEY": live}).returncode, 0)

    def test_the_claude_hook(self):
        live = rnd(40, seed="hook")
        cases = [("Write", {"content": "k=" + live}, 2), ("Write", {"content": "k=$FOO_TOKEN"}, 0),
                 ("Bash", {"command": "echo " + live[:36]}, 2), ("Edit", {"new_string": "gh" + "p_" + rnd(36)}, 0),
                 ("mcp__github__push_files", {"files": [{"content": "gh" + "p_" + rnd(36)}]}, 2),
                 ("mcp__github__get_me", {"q": "gh" + "p_" + rnd(36)}, 0)]
        for tool, inp, want in cases:
            with self.subTest(tool=tool, want=want):
                r = self.run_scan("--claude-hook", input=json.dumps({"tool_name": tool, "tool_input": inp}),
                                  env={"FOO_TOKEN": live})
                self.assertEqual(r.returncode, want, r.stderr)
                self.assertNotIn(live[:20], r.stderr)
        r = self.run_scan("--claude-hook", input="not json")
        self.assertEqual(r.returncode, 0, "a broken event never blocks")

    def test_the_git_hooks_refuse_a_commit_and_a_push(self):
        repo, bare = self.tmp / "repo", self.tmp / "bare.git"
        (repo / "tools").mkdir(parents=True)
        for f in ("secret-scan.py", "secret_patterns.py"):
            shutil.copy2(HERE / f, repo / "tools" / f)
        # This repo's hooks; the public tree ships the same refusals as tools/public-hooks (leak-scan.py holds the
        # secret rules too), so there the test drives those.
        hooks = "git-hooks" if (HERE / "git-hooks").is_dir() else "public-hooks"
        shutil.copytree(HERE / hooks, repo / "tools" / hooks)
        if hooks == "public-hooks":
            for f in ("leak-scan.py", "licence-scan.py", "licence-rules.json"):   # the hooks run these too
                shutil.copy2(HERE / f, repo / "tools" / f)
        self.git(self.tmp, "init", "-q", "-b", "main", str(repo))
        self.git(self.tmp, "init", "-q", "--bare", str(bare))
        self.git(repo, "remote", "add", "origin", str(bare))
        r = subprocess.run(["bash", f"tools/{hooks}/install.sh"], cwd=repo, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.git(repo, "add", "-A")
        self.git(repo, "commit", "-qm", "the scanner")
        self.git(repo, "push", "-q", "origin", "main")
        tok = "gh" + "p_" + rnd(36, seed="push")
        (repo / "conf.py").write_text("x = 1\nTOKEN_VALUE = '" + tok + "'\n")
        self.git(repo, "add", "conf.py")
        r = self.git(repo, "commit", "-qm", "adds a token", check=False)
        self.assertNotEqual(r.returncode, 0, "pre-commit let a token through")
        self.assertIn("conf.py:2: [github-token]", r.stderr)
        self.assertNotIn(tok, r.stdout + r.stderr)
        self.git(repo, "commit", "-qm", "adds a token", "--no-verify")
        (repo / "conf.py").write_text("x = 1\n")
        self.git(repo, "commit", "-qam", "deletes it again")
        r = self.git(repo, "push", "-q", "origin", "main", check=False)
        self.assertNotEqual(r.returncode, 0, "pre-push let a token in the history through")
        if hooks == "git-hooks":   # the public pre-push names the commit, not the line
            self.assertIn("conf.py:2: [github-token]", r.stderr)
        self.assertNotIn(tok, r.stdout + r.stderr)
        self.git(repo, "reset", "-q", "--hard", "HEAD~2")
        self.git(repo, "commit", "-q", "--allow-empty", "-m", "mentions " + tok, "--no-verify")
        r = self.git(repo, "push", "-q", "origin", "main", check=False)
        self.assertNotEqual(r.returncode, 0, "pre-push let a token in a commit MESSAGE through")
        if hooks == "git-hooks":
            self.assertIn("COMMIT_MESSAGE", r.stderr)
        self.assertNotIn(tok, r.stdout + r.stderr)
        self.git(repo, "reset", "-q", "--hard", "HEAD~1")
        (repo / "ok.txt").write_text("nothing secret\n")
        self.git(repo, "add", "ok.txt")
        self.git(repo, "commit", "-qm", "clean")
        self.git(repo, "push", "-q", "origin", "main")
        # A commit the remote ALREADY publishes (as a web merge on main is, its metadata naming the account that
        # merged it) is not refused again when another branch takes it in: this push does not publish it.
        mail = "maintainer" + "@" + "mail-host.com"
        who = {"GIT_AUTHOR_EMAIL": mail, "GIT_COMMITTER_EMAIL": mail}
        self.git(repo, "checkout", "-q", "-b", "release", "main~1")
        self.git(repo, "push", "-q", "origin", "release")
        self.git(repo, "checkout", "-q", "main")
        self.git(repo, "commit", "-q", "--allow-empty", "-m", "merged on the web", "--no-verify", env=who)
        r = self.git(repo, "push", "-q", "origin", "main", check=False)
        self.assertNotEqual(r.returncode, 0, "control: pre-push let a commit naming an email through")
        self.assertNotIn(mail, r.stdout + r.stderr)
        self.git(repo, "push", "-q", "--no-verify", "origin", "main")   # what the website does, with no hook
        self.git(repo, "checkout", "-q", "release")
        self.git(repo, "merge", "-q", "--no-edit", "main")
        r = self.git(repo, "push", "-q", "origin", "release", check=False)
        self.assertEqual(r.returncode, 0, "pre-push refused a commit the remote already publishes: " + r.stderr)
        # ...while a commit NEW to the remote, on top of it, is still scanned and refused
        self.git(repo, "commit", "-q", "--allow-empty", "-m", "new here", "--no-verify", env=who)
        r = self.git(repo, "push", "-q", "origin", "release", check=False)
        self.assertNotEqual(r.returncode, 0, "pre-push let a new commit naming an email through")
        self.assertNotIn(mail, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
