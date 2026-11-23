#!/usr/bin/env python3
"""secret-scan.py — no API key or token ever lands in a repository.

    python3 tools/secret-scan.py --tree              # every tracked file (ci-static, the public repo's CI)
    python3 tools/secret-scan.py --staged            # what the index adds (the pre-commit hook)
    python3 tools/secret-scan.py --commits REV...    # what these commits add, messages included (the pre-push hook):
                                                     #   git rev-list REV..., e.g. -- TIP --not --remotes=origin
    python3 tools/secret-scan.py --history           # every commit reachable from any ref (a one-time audit)
    python3 tools/secret-scan.py PATH...             # just these files
    python3 tools/secret-scan.py --claude-hook       # a Claude Code PreToolUse event on stdin (.claude/settings.json)
    python3 tools/secret-scan.py --live-vars         # which environment variables the live-value guard holds

    --no-live      shapes only (never needed in a hook; for measuring)     --summary   counts by kind and file
    --repo DIR     the repository git modes read (default: this one)

Exit 0 clean, 1 a finding, 2 the scan could not run (a hook then refuses: it never passes what it did not read).

Two checks, both from tools/secret_patterns.py, the one library of what a credential looks like:
  * SHAPES: a provider's token format (ghp_, AIza, sk-ant-, a PEM key with its body, ...), a long random value
    assigned to *_KEY / *_TOKEN / *_SECRET, a literal Bearer, a JWT, and base64 that decodes to any of them. A line
    holding `secret-scan: allow` is skipped (a documented fake); a value that looks made up is not a finding.
  * LIVE VALUES: the real secrets in THIS environment (GCP_SA_KEY_B64 and the key inside it, MUSE_API_KEY, every
    *_KEY/*_TOKEN/*_SECRET/*_PASSWORD with a long value), as exact substrings. No allowlist applies.

A FINDING NEVER PRINTS WHAT IT MATCHED: not the value, not a masked prefix. It says the file, the line, the kind and
the length, or for a live value the NAME of the variable. A scanner that echoed a key into a CI log would be the leak.

The Claude Code hook (--claude-hook) blocks a tool call (exit 2, the reason on stderr) when:
  * any string in the tool's input carries a LIVE value: Bash, Write, Edit, MultiEdit, NotebookEdit and every MCP
    tool. Live values only, because a shape there would block writing this scanner's own tests, a doc about token
    formats, or a grep for `ghp_`; a shape is caught at `git commit` instead.
  * an mcp__github__ write tool's input carries a SHAPE too: those publish straight to GitHub with no git hook between.
It never blocks on its own failure (a bad event, a crash): it says so on stderr and lets the call through, because a
guard that broke every Edit would be switched off. The git hooks are the gate; this is the earlier, cheaper catch.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
MAX_BYTES = 40_000_000
SKIP_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ogg", ".mp3", ".wav", ".mp4", ".webm", ".mov", ".jar", ".zip",
            ".gz", ".tgz", ".xz", ".bz2", ".7z", ".nbt", ".mca", ".dat", ".class", ".ttf", ".otf", ".woff", ".woff2",
            ".ico", ".pdf", ".bin", ".onnx", ".safetensors", ".npy", ".pt"}


def _lib():
    ns = {"__name__": "_secret_patterns", "__file__": str(HERE / "secret_patterns.py")}
    exec(compile((HERE / "secret_patterns.py").read_text(encoding="utf-8"), str(HERE / "secret_patterns.py"), "exec"),
         ns)                                    # exec, never import: the repo's rule (stale bytecode, CLAUDE.md)
    return ns


LIB = _lib()


class Scanner:
    def __init__(self, live=True, shapes=True):
        self.guard = LIB["LiveGuard"]() if live else None
        self.shapes = shapes

    def text(self, text, where, linemap=None):
        """[(where, line, kind, what)] for TEXT. LINEMAP maps the text's line i (1-based) to a real line number."""
        out = []
        fix = (lambda n: linemap[n - 1] if 0 < n <= len(linemap) else 0) if linemap else (lambda n: n)
        if self.shapes:
            for line, rid, desc, length in LIB["find_shapes"](text):
                out.append((where, fix(line), rid, f"{desc} ({length} chars)"))
        if self.guard:
            for line, var in self.guard.find(text):
                out.append((where, fix(line), "LIVE", f"the live value of ${var}"))
        return out


def _git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, check=True).stdout


def read_text(path: pathlib.Path):
    """A file's text, or None for a binary, a media file or anything over MAX_BYTES (named by the caller)."""
    if path.suffix.lower() in SKIP_EXT:
        return None
    try:
        if not path.is_file() or path.stat().st_size > MAX_BYTES:
            return None
        with open(path, "rb") as fh:
            head = fh.read(8192)
            if b"\0" in head:
                return None
            data = head + fh.read()
    except OSError:
        return None
    return data.decode("utf-8", "replace")


def scan_files(scanner, root, rels):
    out = []
    for rel in rels:
        text = read_text(pathlib.Path(root) / rel)
        if text is not None:
            out.extend(scanner.text(text, rel))
    return out


# ── patches: what a commit (or the index) ADDS ──────────────────────────────────────────────

_HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def _patch_added(lines):
    """{path: ([text], [real line numbers])} for the added lines of a -U0 patch."""
    files, cur, nxt = {}, None, 0
    for line in lines:
        if line.startswith("diff --git "):
            cur = None
        elif line.startswith("+++ "):
            cur = line[6:] if line.startswith("+++ b/") else None
            if cur is not None:
                files.setdefault(cur, ([], []))
        elif line.startswith("@@"):
            m = _HUNK.match(line)
            nxt = int(m.group(1)) if m else 0
        elif cur is not None and line.startswith("+"):
            files[cur][0].append(line[1:])
            files[cur][1].append(nxt)
            nxt += 1
    return files


def scan_patch(scanner, files, prefix=""):
    out = []
    for path, (texts, nums) in files.items():
        if texts and pathlib.PurePath(path).suffix.lower() not in SKIP_EXT:
            out.extend(scanner.text("\n".join(texts) + "\n", prefix + path, nums))
    return out


def scan_staged(scanner, repo):
    diff = _git(repo, "diff", "--cached", "--no-color", "--no-renames", "--no-ext-diff", "--no-textconv", "-U0",
                "--diff-filter=ACMRT").decode("utf-8", "replace")
    return scan_patch(scanner, _patch_added(diff.splitlines()))


SEP_COMMIT, SEP_BODY, SEP_END = "\x01", "\x02", "\x03"


def scan_log(scanner, repo, revs, progress=False):
    """Stream `git log -p` over REVS: every non-merge commit's added lines and every commit's message and author.
    Returns (commits scanned, findings). One process for the whole range, so a full history is minutes, not hours."""
    # No --diff-filter here: on `git log` it DROPS every commit with no matching change (a merge, an empty commit, a
    # deletion), and their messages went unscanned. A deleted file's "+++ /dev/null" adds nothing anyway.
    cmd = ["git", "-C", str(repo), "log", "--no-color", "--no-renames", "--no-ext-diff", "--no-textconv", "-p", "-U0",
           f"--format={SEP_COMMIT}%H{SEP_BODY}%an <%ae>%n%cn <%ce>%n%B{SEP_END}", *revs]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    findings, count, sha, patch, msg, in_msg = [], 0, None, [], [], False

    def flush():
        if sha is None:
            return
        short = sha[:12] + ":"
        findings.extend(scanner.text("\n".join(msg) + "\n", short + "COMMIT_MESSAGE"))
        findings.extend(scan_patch(scanner, _patch_added(patch), short))

    for raw in proc.stdout:
        line = raw.decode("utf-8", "replace").rstrip("\n")
        if line.startswith(SEP_COMMIT):
            flush()
            count += 1
            if progress and count % 500 == 0:
                print(f"secret-scan: {count} commits...", file=sys.stderr)
            head, _, rest = line[1:].partition(SEP_BODY)
            sha, patch, msg, in_msg = head, [], [], True
            line = rest
        if in_msg:
            if SEP_END in line:
                msg.append(line.split(SEP_END, 1)[0])
                in_msg = False
            else:
                msg.append(line)
            continue
        patch.append(line)
    flush()
    err = proc.stderr.read().decode("utf-8", "replace")
    if proc.wait() != 0:
        raise RuntimeError(f"git log failed: {err.strip()}")
    return count, findings


# ── the Claude Code PreToolUse hook ─────────────────────────────────────────────────────────

_GITHUB_WRITE = re.compile(r"^mcp__github__(create|update|push|add|merge|fork|delete|request|resolve|issue_write|"
                           r"sub_issue_write|pull_request_review_write)", re.I)


def _strings(obj):
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from _strings(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from _strings(v)


def claude_hook(stdin) -> int:
    try:
        event = json.load(stdin)
        tool = str(event.get("tool_name", ""))
        text = "\n".join(_strings(event.get("tool_input", {})))
        scanner = Scanner(live=True, shapes=bool(_GITHUB_WRITE.match(tool)))
        found = scanner.text(text, tool)
    except Exception as e:                          # never let the guard itself break a session
        print(f"secret-scan: the PreToolUse guard could not run ({type(e).__name__}); not blocking", file=sys.stderr)
        return 0
    if not found:
        return 0
    kinds = sorted({what for _w, _n, _k, what in found})
    print(f"secret-scan: BLOCKED {tool}: its input carries {'; '.join(kinds)}. Refer to a secret by its variable "
          "(\"$NAME\"), never by its value, and never put a real credential in a file, a commit or a GitHub post. "
          "A documented fake goes on a line marked `secret-scan: allow` (shapes only; a live value is never allowed).",
          file=sys.stderr)
    return 2


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--tree", action="store_true")
    mode.add_argument("--staged", action="store_true")
    mode.add_argument("--commits", action="store_true")
    mode.add_argument("--history", action="store_true")
    mode.add_argument("--claude-hook", action="store_true")
    mode.add_argument("--live-vars", action="store_true")
    ap.add_argument("--no-live", action="store_true")
    ap.add_argument("--summary", action="store_true")
    ap.add_argument("--repo", default=str(ROOT))
    a = ap.parse_args(argv)

    if a.claude_hook:
        return claude_hook(sys.stdin)
    scanner = Scanner(live=not a.no_live)
    if a.live_vars:
        names = scanner.guard.variables() if scanner.guard else []
        print("live-value guard holds: " + (", ".join("$" + n for n in names) or "nothing (no secret in this env)"))
        return 0
    repo = pathlib.Path(a.repo).resolve()
    unit = "file(s)"
    try:
        if a.staged:
            findings = scan_staged(scanner, repo)
            count = len({f[0] for f in findings}) if findings else 0
            unit = "staged file(s) with findings"
        elif a.commits or a.history:
            revs = ["--all"] if a.history else [r for r in a.paths if r != "--"]
            if not revs:
                print("secret-scan: --commits needs rev-list arguments, e.g. -- TIP --not --remotes=origin",
                      file=sys.stderr)
                return 2
            count, findings = scan_log(scanner, repo, revs, progress=a.history)
            unit = "commit(s)"
        else:
            rels = a.paths or _git(repo, "ls-files", "-z").decode("utf-8", "replace").split("\0")
            rels = [r for r in rels if r]
            findings = scan_files(scanner, repo, rels)
            count = len(rels)
    except (subprocess.CalledProcessError, RuntimeError) as e:
        detail = e.stderr.decode(errors="replace").strip() if isinstance(e, subprocess.CalledProcessError) else str(e)
        print(f"secret-scan: could not run: {detail}", file=sys.stderr)
        return 2
    if a.summary:
        by = {}
        for where, _n, kind, _w in findings:
            by.setdefault(kind, set()).add(where)
        for kind in sorted(by):
            print(f"{kind}: {len(by[kind])} place(s): " + ", ".join(sorted(by[kind])[:10]))
    else:
        for where, n, kind, what in findings:
            print(f"{where}:{n}: [{kind}] {what}")
    live = ", ".join("$" + v for v in scanner.guard.variables()) if scanner.guard else "off"
    print(f"secret-scan: {count} {unit}, {len(findings)} finding(s) (live values: {live or 'none in this env'})",
          file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
