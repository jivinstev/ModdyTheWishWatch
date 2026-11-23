#!/usr/bin/env python3
"""leak-scan.py — fail when a public file carries private work.

    python3 tools/leak-scan.py                       # the files docs/public-repo/split.json marks public
    python3 tools/leak-scan.py --tree                # EVERY tracked file (the public repo's own CI)
    python3 tools/leak-scan.py --forbidden FILE      # add the private forbidden list (else $MODDY_FORBIDDEN_FILE,
                                                     # else ~/.moddy/forbidden.txt, else built-in rules only)
    python3 tools/leak-scan.py --show                # print what matched (default: masked)
    python3 tools/leak-scan.py PATH...               # scan just these files
    python3 tools/leak-scan.py --dir DIR             # every file under DIR (a materialised public tree, the mirror)
    python3 tools/leak-scan.py --commits REV...      # what these commits ADD: added lines, file names, messages and
                                                     # author/committer (git rev-list REV..., e.g. TIP --not --remotes);
                                                     # the public clone's pre-push hook (tools/public-hooks/)

Exit 0 clean, 1 findings, 2 a named forbidden list is missing or malformed.

TWO KINDS OF RULE, and the split between them is the design (modelled on MMVU's check-no-ip.py and its
FORBIDDEN_NAMES_FILE):

* BUILT-IN rules live here and are safe to publish, because they name SHAPES, never secrets: an email
  address, a routable IPv4 literal, a GCS bucket URL, a Telegram bot token, every credential shape in
  tools/secret_patterns.py (and the live secret values of the environment the scan runs in), a file
  that is a child's-data corpus by its name (real-wishes.jsonl, an agent action log, a wish-run archive,
  a transcript). The public repo's CI runs these with no list at all.
* The FORBIDDEN LIST is private and is never committed to the public tree, because the list itself would
  leak: the cloud project and bucket names, our servers' addresses and names, family names, non-public
  hostnames, ops-tool identifiers, the private repo's issue links, and -- by reference, never
  copied -- the sentences a child typed (`phrases:`). It lives in the private repo (tools/leak-forbidden.txt)
  or at ~/.moddy/forbidden.txt, and the private repo's CI checks the public-bound tree against it.

A FINDING NEVER ECHOES WHAT IT MATCHED unless --show is given: a scan that printed a child's words or a
family name into a CI log would be the leak it exists to stop. Phrases are never printed even with
--show; a finding names the list entry by number instead.

FORBIDDEN LIST FORMAT, one entry per line (blank lines and `#` comments ignored):

    [category]               every entry below is reported under this name (default: forbidden)
    term                     a literal, matched case-insensitively at word boundaries
    re:REGEX                 a Python regex, case-insensitive
    phrases:PATH[:FIELD]     every FIELD (default `prompt`) of a JSONL file is a forbidden PHRASE, matched
                             after folding case and punctuation; only phrases of 4+ words are used, so a
                             common "make a castle" cannot fire everywhere. PATH is relative to the list.
    path:GLOB                no public file may live at a path matching GLOB (fnmatch, repo-relative)
    minwords:N               phrases loaded AFTER this line use N words as their minimum (default 4)
    allow:REGEX              a match whose text matches REGEX is not a finding (an allowlist, any category)
    skip:GLOB                files matching GLOB are not scanned
    except:GLOB              this SECTION's rules do not fire in files matching GLOB (every other section's still
                             do): a named, reviewable list of the files a section is not yet clean in, never a
                             blanket skip that would also hide a secret or a child's words there
"""
import argparse
import fnmatch
import ipaddress
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAX_BYTES = 2_000_000          # a larger file is data, scanned for paths only (and said so)
MIN_PHRASE_WORDS = 4


# ── which files are public ───────────────────────────────────────────────────────────────────

def tracked_files(root=ROOT):
    out = subprocess.run(["git", "ls-files", "-z"], cwd=root, capture_output=True, check=True).stdout
    return [p for p in out.decode("utf-8", "replace").split("\0") if p]


def public_files(root=ROOT):
    """The files the public repo gets. In this (private) repo that is tools/public-tree.py's list, the one definition
    of the public cut; in the public repo itself, which has no public-tree.py, it is every tracked file."""
    tree = pathlib.Path(root) / "tools" / "public-tree.py"
    if not tree.exists():
        return tracked_files(root)
    ns = {"__name__": "_public_tree", "__file__": str(tree)}
    exec(compile(tree.read_text(encoding="utf-8"), str(tree), "exec"), ns)   # exec, never import (stale bytecode)
    pub, _gaps = ns["public_paths"]()
    return pub


DIR_SKIP = {".git", "build", "run", ".gradle", "logs"}


def dir_files(d):
    """Every file under D, repo-relative, except git and build state."""
    d = pathlib.Path(d)
    out = []
    for dirpath, dirnames, filenames in os.walk(d):
        if pathlib.Path(dirpath) == d:
            dirnames[:] = [x for x in dirnames if x not in DIR_SKIP]
        for f in filenames:
            out.append(os.path.relpath(os.path.join(dirpath, f), d).replace(os.sep, "/"))
    return sorted(out)


# ── rules ────────────────────────────────────────────────────────────────────────────────────

class Rule:
    def __init__(self, category, kind, pattern, label, check=None):
        self.category, self.kind, self.pattern, self.label, self.check = category, kind, pattern, label, check


def _routable_ipv4(m):
    """A real address someone could reach: not loopback, private, link-local, documentation or a
    version number that happens to have four parts (those are skipped by the lookarounds)."""
    try:
        ip = ipaddress.IPv4Address(m.group(0))
    except ValueError:
        return False
    doc = any(ip in ipaddress.ip_network(n) for n in ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24",
                                                      "100.64.0.0/10"))   # + carrier-grade NAT
    if str(ip) in ("1.2.3.4", "4.3.2.1", "8.8.8.8", "1.1.1.1"):          # the placeholders everyone types
        return False
    return not (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast
                or ip.is_unspecified or ip.is_reserved or doc or str(ip).startswith("0."))


#: Placeholders that are not addresses or buckets anyone owns.
_SAFE_EMAIL = re.compile(r"(@example\.(com|org|net)$|\.example$|\.invalid$|\.test$|^noreply@anthropic\.com$|@users\.noreply\.github\.com$"
                         r"|^git@github\.com$|^noreply@|^you@|^user@|^name@|^someone@)", re.I)
_SAFE_BUCKET = re.compile(r"^gs://(bucket|b|c|x|example[a-z0-9._-]*|my-[a-z0-9._-]+|your-[a-z0-9._-]+)/?$", re.I)

BUILTIN = [
    Rule("email", "re", re.compile(r"(?<![\w.+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b"),
         "an email address", check=lambda m: not _SAFE_EMAIL.search(m.group(0))),
    Rule("ip", "re", re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.]*\d)"),
         "a routable IPv4 address", check=_routable_ipv4),
    Rule("bucket", "re", re.compile(r"gs://[a-z0-9][a-z0-9._-]*"),
         "a GCS bucket URL", check=lambda m: not _SAFE_BUCKET.match(m.group(0))),
    Rule("telegram", "re", re.compile(r"\b\d{8,10}:[A-Za-z0-9_-]{35}\b|api\.telegram\.org/bot\d|\bt\.me/[A-Za-z0-9_]{4,}"),
         "a Telegram bot token or link"),
]


def _secret_lib():
    """tools/secret_patterns.py, the one library of what a credential looks like (shared with tools/secret-scan.py).
    Exec'd, never imported (stale bytecode). Its findings carry no matched text, so nothing here can echo a key."""
    src = pathlib.Path(__file__).resolve().parent / "secret_patterns.py"
    ns = {"__name__": "_secret_patterns", "__file__": str(src)}
    exec(compile(src.read_text(encoding="utf-8"), str(src), "exec"), ns)
    return ns


SECRETS = _secret_lib()
_LIVE = None


def live_guard():
    """The live secret values of this environment, loaded once (in memory only; see secret_patterns.LiveGuard)."""
    global _LIVE
    if _LIVE is None:
        _LIVE = SECRETS["LiveGuard"]()
    return _LIVE

#: Files that ARE a child's data or derived from it, by name. Safe to publish: a name says nothing.
BUILTIN_PATHS = [
    ("child-data", "*real-wishes*.jsonl", "a corpus of sentences a child typed"),
    ("child-data", "*real-tool-calls*.jsonl", "the agent's real tool calls on a child's wishes"),
    ("child-data", "*real-builds*.jsonl", "buildings made for a child's real wishes"),
    ("child-data", "*safety-heldout*", "the private held-out safety set"),
    ("child-data", "*one-off-wishes*", "a wish log"),
    ("child-data", "*agent-actions*.jsonl", "an agent action log"),
    ("child-data", "corpus/wish-runs/*", "a wish-run archive"),
    ("child-data", "*transcript*.jsonl", "a transcript"),
    ("child-data", "*grown-up-notes*", "grown-up notes about a child"),
    # Structural markers of the PRIVATE side: safe to publish, because they name the private half's shape,
    # never its content. A public tree that holds one of these was cut wrongly.
    ("private-work", "src/private/*", "the private source root"),
    *[("private-work", f"src/*/java/com/moddingfromamod/{pkg}/*", f"the private package {pkg}")
      for pkg in ("client", "isle", "bank", "identity", "ops", "eval", "judge", "spike")],
    ("private-work", "*moddingfromamod.private.mixins.json", "the private mixin config"),
    ("private-work", "docs/public-repo/SERVER_CONFIG.md", "what the private servers set on top of the mod"),
    ("private-work", "docs/public-repo/split.json", "the private split's own map"),
    ("private-work", "*leak-forbidden*", "the private forbidden list"),
]


def _norm(text):
    return " " + re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", text.lower())).strip() + " "


class Forbidden:
    def __init__(self):
        self.rules, self.phrases, self.paths, self.allow, self.skip = [], [], [], [], []
        self.excepts = {}                              # category -> [glob]: that section's rules skip these files

    @classmethod
    def load(cls, path):
        f = cls()
        path = pathlib.Path(path)
        category = "forbidden"
        minwords = MIN_PHRASE_WORDS
        for n, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            label = f"{path.name}:{n}"
            m = re.fullmatch(r"\[([a-z0-9_-]+)\]", line)
            if m:
                category = m.group(1)
            elif line.startswith("re:"):
                try:
                    f.rules.append(Rule(category, "re", re.compile(line[3:], re.I), label))
                except re.error as e:
                    raise ValueError(f"{label}: bad regex: {e}")
            elif line.startswith("minwords:"):
                try:
                    minwords = int(line[len("minwords:"):])
                except ValueError:
                    raise ValueError(f"{label}: minwords wants a number")
            elif line.startswith("phrases:"):
                spec = line[len("phrases:"):]
                src, _, field = spec.partition(":")
                src_path = (path.parent / src).resolve()
                if not src_path.exists():
                    raise ValueError(f"{label}: no such file for phrases: {src}")
                for k, row in enumerate(src_path.read_text(encoding="utf-8").splitlines(), 1):
                    if not row.strip():
                        continue
                    try:
                        val = json.loads(row).get(field or "prompt")
                    except (ValueError, AttributeError):
                        continue
                    if isinstance(val, str) and len(_norm(val).split()) >= minwords:
                        f.phrases.append((category, _norm(val), f"{label} ({src_path.name} row {k})"))
            elif line.startswith("path:"):
                f.paths.append((category, line[5:], label))
            elif line.startswith("allow:"):
                f.allow.append(re.compile(line[6:], re.I))
            elif line.startswith("skip:"):
                f.skip.append(line[5:])
            elif line.startswith("except:"):
                f.excepts.setdefault(category, []).append(line[len("except:"):])
            else:
                f.rules.append(Rule(category, "re", re.compile(r"(?<![A-Za-z0-9])" + re.escape(line)
                                                               + r"(?![A-Za-z0-9])", re.I), label))
        return f


def find_forbidden_list(explicit):
    """--forbidden, else $MODDY_FORBIDDEN_FILE, else ~/.moddy/forbidden.txt; None = built-in rules only.
    A list that is NAMED and missing is an error, never a quiet fall-back to fewer rules."""
    for candidate, named in ((explicit, True), (os.environ.get("MODDY_FORBIDDEN_FILE"), True),
                             (str(pathlib.Path.home() / ".moddy" / "forbidden.txt"), False)):
        if not candidate:
            continue
        if pathlib.Path(candidate).exists():
            return pathlib.Path(candidate)
        if named:
            raise FileNotFoundError(candidate)
    return None


# ── the scan ─────────────────────────────────────────────────────────────────────────────────

def mask(text):
    text = text.strip()
    return text[:1] + "…" + f"({len(text)} chars)" if len(text) > 1 else "…"


#: Licence and notice files credit upstream authors by address; that is attribution, not a leak.
#: A third-party licence or notice names its authors: an email there is attribution. third_party/ holds only those.
_ATTRIBUTION = re.compile(r"(^|/)(NOTICE|THIRD_PARTY|LICEN[CS]E|AUTHORS|CREDITS)[^/]*$|\.NOTICE\.txt$|(^|/)third_party/", re.I)


def scan(paths, forbidden=None, root=ROOT, show=False):
    """[(path, line, category, what)] for every finding. `what` is masked unless show."""
    forbidden = forbidden or Forbidden()
    rules = BUILTIN + forbidden.rules
    path_rules = BUILTIN_PATHS + forbidden.paths
    findings = []
    for rel in paths:
        if any(fnmatch.fnmatch(rel, g) for g in forbidden.skip):
            continue
        for category, glob, label in path_rules:
            if fnmatch.fnmatch(rel, glob) or fnmatch.fnmatch(rel.rsplit("/", 1)[-1], glob):
                findings.append((rel, 0, category, f"this file is {label}" if category == "child-data"
                                 else f"a forbidden path ({label})"))
        p = pathlib.Path(root) / rel
        try:
            if not p.is_file() or p.stat().st_size > MAX_BYTES:
                continue
            data = p.read_bytes()
        except OSError:
            continue
        if b"\0" in data[:8192]:
            continue                                   # binary
        text = data.decode("utf-8", "replace")
        excepted = {c for c, globs in forbidden.excepts.items() if any(fnmatch.fnmatch(rel, g) for g in globs)}
        for n, line in enumerate(text.splitlines(), 1):
            for r in rules:
                if r.category in excepted:
                    continue
                if r.category == "email" and r in BUILTIN and _ATTRIBUTION.search(rel):
                    continue
                for m in r.pattern.finditer(line):
                    if r.check and not r.check(m):
                        continue
                    if any(a.search(m.group(0)) for a in forbidden.allow):
                        continue
                    what = m.group(0) if show else mask(m.group(0))
                    findings.append((rel, n, r.category, f"{r.label}: {what}"))
        for n, rid, desc, length in SECRETS["find_shapes"](text):      # the shared secret shapes (secret_patterns)
            findings.append((rel, n, "secret", f"{desc} [{rid}] ({length} chars)"))
        for n, var in live_guard().find(text):                         # and this environment's real values
            findings.append((rel, n, "secret", f"the live value of ${var}"))
        if forbidden.phrases:
            folded = _norm(text)
            for category, phrase, label in forbidden.phrases:
                if phrase in folded:
                    findings.append((rel, 0, category, f"a forbidden phrase, list entry {label}"))
    return findings


EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"


def _git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, check=True).stdout


def commit_added(repo, sha):
    """{path: added text} for one commit against its first parent (the empty tree for a root commit), plus its
    message and author/committer under the pseudo-path COMMIT_MESSAGE. A deleted file adds nothing; a binary file is
    named (its path is scanned) with no text."""
    parents = _git(repo, "rev-list", "--parents", "-n1", sha).decode().split()
    base = parents[1] if len(parents) > 1 else EMPTY_TREE
    added, cur = {}, None
    diff = _git(repo, "diff", "--no-color", "--no-renames", "--no-ext-diff", "-U0", "--diff-filter=ACMRT", base, sha)
    for line in diff.decode("utf-8", "replace").splitlines():
        if line.startswith("+++ "):
            cur = line[6:] if line.startswith("+++ b/") else None
            if cur is not None:
                added.setdefault(cur, [])
        elif line.startswith("Binary files ") and " and b/" in line:
            added.setdefault(line.rsplit(" and b/", 1)[1].rsplit(" differ", 1)[0], [])
        elif cur is not None and line.startswith("+"):
            added[cur].append(line[1:])
    meta = _git(repo, "show", "-s", "--format=%an <%ae>%n%cn <%ce>%n%B", sha).decode("utf-8", "replace")
    out = {p: "\n".join(v) + ("\n" if v else "") for p, v in added.items()}
    out["COMMIT_MESSAGE"] = meta
    return out


def scan_commits(revs, forbidden=None, repo=ROOT, show=False):
    """Findings for what the commits `git rev-list REVS` ADD, each path prefixed with its short sha. Every commit is
    scanned on its own, so a leak added and then deleted again is still found: the history would carry it."""
    import tempfile
    shas = _git(repo, "rev-list", "--reverse", *revs).decode().split()
    findings = []
    with tempfile.TemporaryDirectory() as tmp:
        for sha in shas:
            work = pathlib.Path(tmp) / sha
            files = commit_added(repo, sha)
            for rel, text in files.items():
                dst = work / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_text(text, encoding="utf-8")
            for rel, n, cat, what in scan(sorted(files), forbidden, root=work, show=show):
                findings.append((f"{sha[:12]}:{rel}", n, cat, what))
    return shas, findings


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", help="scan just these (repo-relative) files")
    ap.add_argument("--tree", action="store_true", help="every tracked file, not only split.json's public ones")
    ap.add_argument("--forbidden", help="the private forbidden list (see the format above)")
    ap.add_argument("--show", action="store_true", help="print what matched (never a phrase)")
    ap.add_argument("--summary", action="store_true", help="counts by category and file only")
    ap.add_argument("--dir", help="scan every file under this directory (a materialised public tree)")
    ap.add_argument("--commits", action="store_true",
                    help="the positional arguments are `git rev-list` arguments: scan what those commits add")
    ap.add_argument("--repo", default=str(ROOT), help="the repository --commits reads (default: this one)")
    args = ap.parse_args(argv)

    try:
        listed = find_forbidden_list(args.forbidden)
        forbidden = Forbidden.load(listed) if listed else Forbidden()
    except FileNotFoundError as e:
        print(f"leak-scan: the forbidden list {e} does not exist (named by --forbidden or "
              f"$MODDY_FORBIDDEN_FILE); refusing to scan with fewer rules than asked for", file=sys.stderr)
        return 2
    except ValueError as e:
        print(f"leak-scan: {e}", file=sys.stderr)
        return 2

    if args.commits:
        if not args.paths:
            print("leak-scan: --commits needs rev-list arguments, e.g. -- TIP --not --remotes", file=sys.stderr)
            return 2
        try:
            files, findings = scan_commits(args.paths, forbidden, repo=args.repo, show=args.show)
        except subprocess.CalledProcessError as e:
            print(f"leak-scan: git failed: {e.stderr.decode(errors='replace').strip()}", file=sys.stderr)
            return 2
    else:
        root = ROOT
        if args.dir:
            root = pathlib.Path(args.dir).resolve()
            files = dir_files(root)
        elif args.paths:
            files = args.paths
        elif args.tree:
            files = tracked_files()
        else:
            files = public_files()
        findings = scan(files, forbidden, root=root, show=args.show)
    where = ("built-in rules only" if not listed else
             f"built-in rules + {len(forbidden.rules)} terms, {len(forbidden.phrases)} phrases, "
             f"{len(forbidden.paths)} paths from the forbidden list")
    if args.summary:
        by = {}
        for rel, _n, cat, _w in findings:
            by.setdefault(cat, {}).setdefault(rel, 0)
            by[cat][rel] += 1
        for cat in sorted(by):
            print(f"{cat}: {sum(by[cat].values())} in {len(by[cat])} file(s)")
            for rel, k in sorted(by[cat].items(), key=lambda kv: -kv[1])[:15]:
                print(f"    {k:5d}  {rel}")
    else:
        for rel, n, cat, what in findings:
            print(f"{rel}:{n}: [{cat}] {what}")
    unit = "commit(s)" if args.commits else "file(s)"
    print(f"leak-scan: {len(files)} {unit}, {len(findings)} finding(s) ({where})",
          file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
