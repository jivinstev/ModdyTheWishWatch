"""Gate B's shared, Minecraft-free helpers: what the GameTest tier holds, how a run's records read, and the verdict.

Everything here is pure (files and strings in, values out) so `test_gate.py` can pin it without a game server.
Python 3.9+, standard library only, every file read and written as UTF-8 (a contributor on Windows or macOS runs these).
See docs/GATE_B.md.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import re
import subprocess
from typing import Dict, Iterable, List, Optional, Tuple

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAVA_ROOT = os.path.join("src", "main", "java")

# GateShards: the shard names -Pshard takes, and the namespace each is
SHARDS = {"default": "moddingfromamod", "ci": "moddingfromamod_ci"}
FULL_TIER = "ci,default"

# the synthetic tests a coverage-mapping run adds (testing/gt/CoverageMap: its idle window, each class's settle
# window): not tests of the tier
COVMAP_SYNTHETIC = "covmapidle."

# the one line a GameTest server prints when it finished (CLAUDE.md: never anchor on "All")
RESULT_LINE = re.compile(r"([0-9]+) required tests (passed|failed)")
FOUND_LINE = re.compile(r"gt-adapter: ([0-9]+) tests found")

# Paths no GameTest reads: a change only here leaves Gate B's verdict for a commit standing (docs/GATE_B.md,
# "Which tree a result belongs to"). A DENY list on purpose: forgetting a path here costs a re-run, while an
# allow list that forgot one would let a release reuse a run of different code.
NOT_GATE_B = ("docs/", ".github/", "art/", "src/test/", "tools/client-validation/", ".port/")
NOT_GATE_B_TOP = re.compile(r"^[^/]+\.md$")


# ---------------------------------------------------------------------------------------------- files

def read_text(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def write_text(path: str, text: str) -> None:
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def read_json(path: str):
    if path.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            return json.load(f)
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path: str, value, compress: bool = False) -> None:
    text = json.dumps(value, indent=1, sort_keys=True, ensure_ascii=False) + "\n"
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    if compress:
        # mtime=0: the same record always compresses to the same bytes
        with open(path, "wb") as raw:
            with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as f:
                f.write(text.encode("utf-8"))
    else:
        write_text(path, text)


def find_file(directory: str, name: str) -> Optional[str]:
    """`name` in `directory` or below it: a downloaded artifact keeps the paths it was uploaded with, rooted wherever
    upload-artifact found their common ancestor, so a job's job.json may be a level or two down."""
    best = None
    for dirpath, _dirs, files in os.walk(directory):
        if name in files:
            cand = os.path.join(dirpath, name)
            if best is None or cand.count(os.sep) < best.count(os.sep):
                best = cand
    return best


def git(*args: str, cwd: str = ROOT) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE).stdout.decode("utf-8").strip()


# ---------------------------------------------------------------------------------------------- the tier

_HOLDER = re.compile(r"@GameTestHolder\(\s*(?:(?:[\w.]*\.)?GateShards\.(\w+)|(?:[\w.]*\.)?ModdingFromAModMod\.MODID"
                     r"|\"([\w_]+)\")\s*\)")
_TEST = re.compile(r"@GameTest\b(?!Holder)")


def strip_java(text: str, strings: bool = True) -> str:
    """A Java file without comments (and, by default, string literals): a test named in a comment is not a test."""
    out, i = [], 0
    pattern = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])\'', re.S)
    for m in pattern.finditer(text):
        tok = m.group(0)
        if tok.startswith(("//", "/*")):
            out.append(text[i:m.start()])
            out.append(" ")
        else:
            out.append(text[i:m.start()])
            out.append(('""' if tok.startswith('"') else "' '") if strings else tok)
        i = m.end()
    out.append(text[i:])
    return "".join(out)


def holder_namespace(code: str) -> Optional[str]:
    """The namespace a @GameTestHolder file declares, or None when it holds no tests."""
    m = _HOLDER.search(code)
    if not m:
        return None
    if m.group(1):
        return {"CI": SHARDS["ci"], "DEFAULT": SHARDS["default"]}.get(m.group(1), "GateShards." + m.group(1))
    return m.group(2) or SHARDS["default"]


def scan_tier(root: str = ROOT) -> Dict[str, dict]:
    """Every GameTest class in the source: {simpleName: {"ns", "tests", "path"}}. Read from source, so it needs no build.

    A server is the authority on what it registered; this is the independent count a run is checked against.
    """
    out: Dict[str, dict] = {}
    base = os.path.join(root, JAVA_ROOT)
    for dirpath, _dirs, files in os.walk(base):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            text = read_text(path)
            if "@GameTestHolder" not in text:
                continue
            code = strip_java(text)
            ns = holder_namespace(strip_java(text, strings=False))
            if ns is None:
                continue
            cls = name[:-len(".java")]
            out[cls] = {"ns": ns, "tests": len(_TEST.findall(code)),
                        "path": os.path.relpath(path, root).replace(os.sep, "/")}
    return out


def scan_sources(sources: Dict[str, str]) -> Dict[str, dict]:
    """scan_tier over file texts by repo path (a commit's, read through git), not the work tree."""
    out: Dict[str, dict] = {}
    for path, text in sources.items():
        if not path.endswith(".java") or "@GameTestHolder" not in text:
            continue
        ns = holder_namespace(strip_java(text, strings=False))
        if ns is None:
            continue
        cls = os.path.basename(path)[:-len(".java")]
        out[cls] = {"ns": ns, "tests": len(_TEST.findall(strip_java(text))), "path": path}
    return out


def tier_classes(tier: Dict[str, dict], shard: str) -> Dict[str, dict]:
    """The classes of a -Pshard list (`ci`, `default`, `ci,default`, `all`)."""
    names = list(SHARDS) if shard.strip() in ("", "all") else [s.strip() for s in shard.split(",") if s.strip()]
    unknown = [n for n in names if n not in SHARDS]
    if unknown:
        raise ValueError("unknown shard %s; expected any of %s" % (",".join(unknown), ", ".join(SHARDS)))
    wanted = {SHARDS[n] for n in names}
    return {c: v for c, v in tier.items() if v["ns"] in wanted}


# ---------------------------------------------------------------------------------------------- trees

def gate_relevant(path: str) -> bool:
    return not (path.startswith(NOT_GATE_B) or NOT_GATE_B_TOP.match(path))


def gate_tree_of_listing(listing: Iterable[Tuple[str, str]]) -> str:
    """A hash of the (path, blob) pairs Gate B can read. Two commits with the same one test the same code."""
    h = hashlib.sha1()
    for path, blob in sorted(listing):
        if gate_relevant(path):
            h.update(("%s %s\n" % (blob, path)).encode("utf-8"))
    return h.hexdigest()


def gate_tree(rev: str = "HEAD", root: str = ROOT) -> str:
    rows = []
    for line in git("ls-tree", "-r", "--full-tree", rev, cwd=root).splitlines():
        meta, path = line.split("\t", 1)
        rows.append((path, meta.split()[2]))
    return gate_tree_of_listing(rows)


# ---------------------------------------------------------------------------------------------- records

def ours(test_id: str) -> bool:
    """A test of this mod's shards (not the vanilla server's own `minecraft:always_pass`)."""
    ns, _, local = test_id.partition(":")
    return ns in SHARDS.values() and not local.startswith(COVMAP_SYNTHETIC)


def test_class(test_id: str, known: Iterable[str]) -> str:
    """`moddingfromamod_ci:mcpgametest.handshake` -> `McpGameTest` (a test id is lower case; the class is not)."""
    local = test_id.split(":", 1)[-1]
    lower = local.split(".", 1)[0]
    for c in known:
        if c.lower() == lower:
            return c
    return lower


def test_method(test_id: str) -> str:
    return test_id.split(":", 1)[-1].split(".", 1)[-1]


def short_name(test_id: str, known: Iterable[str]) -> str:
    """The `Class.method` a person reads and `-Pgametests` takes."""
    return "%s.%s" % (test_class(test_id, known), test_method(test_id))


def parse_log(text: str) -> dict:
    """What a GameTest server's log says about its run."""
    results = RESULT_LINE.findall(text)
    found = FOUND_LINE.findall(text)
    return {
        "result": ("%s required tests %s" % results[-1]) if results else None,
        "required": int(results[-1][0]) if results else None,
        "passed": bool(results) and results[-1][1] == "passed" and "required tests failed" not in text,
        "found": int(found[-1]) if found else None,
        "oom": "OutOfMemoryError" in text,
    }


def costs_from_records(records: List[dict], known: Iterable[str]) -> Dict[str, float]:
    """Seconds each class held a server for, read from run records (the latest record a class appears in wins).

    Tests of one batch run at once, so a class costs about the sum over its batches of the slowest test in each.
    """
    known = list(known)
    out: Dict[str, float] = {}
    for rec in records:
        per: Dict[Tuple[str, str], float] = {}
        for t in rec.get("tests", []):
            key = (test_class(t["name"], known), t.get("batch") or "?")
            per[key] = max(per.get(key, 0.0), float(t.get("seconds") or 0.0))
        seen: Dict[str, float] = {}
        for (cls, _b), s in per.items():
            seen[cls] = seen.get(cls, 0.0) + s
        out.update(seen)
    return out


# ---------------------------------------------------------------------------------------------- splitting

def split_classes(classes: Dict[str, dict], jobs: int, costs: Optional[Dict[str, float]] = None) -> List[List[str]]:
    """Share the classes among `jobs` servers so each takes about the same time (longest first, to the lightest).

    Deterministic: the same classes and costs give the same split. A class with no measured cost is weighed by its
    test count. Job 0 is run as "every class EXCEPT the other jobs'", so a class this list never saw still runs.
    """
    costs = costs or {}
    jobs = max(1, min(jobs, len(classes) or 1))
    per_test = 2.0
    weight = {c: float(costs.get(c, per_test * max(1, v.get("tests", 1)))) + 1.0 for c, v in classes.items()}
    order = sorted(classes, key=lambda c: (-weight[c], c))
    loads = [0.0] * jobs
    out: List[List[str]] = [[] for _ in range(jobs)]
    for c in order:
        i = min(range(jobs), key=lambda j: (loads[j], j))
        out[i].append(c)
        loads[i] += weight[c]
    return [sorted(x) for x in out]


def job_filter(split: List[List[str]], index: int) -> Tuple[List[str], List[str]]:
    """(gametests, notclasses) for one job: job 0 runs every class but the others' (so nothing falls between jobs)."""
    if index == 0:
        others = sorted({c for i, part in enumerate(split) if i != 0 for c in part})
        return [], others
    return list(split[index]), []
