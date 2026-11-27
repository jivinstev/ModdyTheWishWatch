"""Targeted Gate B: which GameTest classes a change can reach (docs/GATE_B.md, "Targeted Gate B").

Pure and deterministic: a list of changed files (with their hunks), the coverage map and the tier in, a selection and
the reason for every class out. The rules, in order:

  * the `ci` shard, always: no pull request runs less than it did before;
  * every class the map has not placed (a new test, or one a mapping run could not place);
  * a changed GameTest class itself;
  * the GameTest classes that NAME a changed class (its simple name, as a word, in their source);
  * every class the map saw execute a changed METHOD. A hunk inside a method's lines is that method; a hunk outside
    every method (a field, a constant, an import, a new method, a signature) is the whole FILE, plus every file that
    names the class (a compile-time constant is copied into the classes that use it, which never execute its file);
    a file whose lines changed since the map recorded it is the whole file;
  * the WHOLE TIER for anything the map cannot see: build configuration, mixins, resources the game reads by path
    (data/, lessons/, the starter library; a client asset in assets/ is read only by a source that names it),
    the GameTest harness (what decides which tests run and how), the bundled datapack compiler, a change to code that
    runs with no test running (start-up, the idle tick, shutdown; a static initialiser instead reaches its file and
    every file naming its class), an unknown path, and no map (or one older than MAX_AGE_DAYS).

A path no GameTest reads (docs/, .github/, art/, src/test/, tools/ but the compiler bundle, third_party/'s
licence texts, the gate tools' own corpus/gate-* files, the port ledger .port/, top-level *.md) selects
nothing beyond `ci`. A missing or stale map only ever makes the selection LARGER.
"""
from __future__ import annotations

import calendar
import os
import re
import time
from typing import Dict, Iterable, List, Optional, Set, Tuple

import gatelib as g

MAX_AGE_DAYS = 10
JAVA = g.JAVA_ROOT.replace(os.sep, "/") + "/"
# the GameTest HARNESS: what decides which tests run and how (registration, the filter, batches, timing, the watchdog,
# the test-only mixins). A change here can change any test's run, so it is the whole tier. The tests' own helpers
# (plots, mock players, fixtures) are ordinary code the map sees.
HARNESS = (JAVA + "com/moddingfromamod/testing/gt/", JAVA + "com/moddingfromamod/testing/mixin/")
HARNESS_FILES = tuple(JAVA + "com/moddingfromamod/testing/%s.java" % c for c in (
    "GameTestClassFilter", "GateShards", "GateBTimings", "StallWatchdog", "HarnessBoot"))
TIER_FILES = ("build.gradle", "settings.gradle", "gradle.properties", "test-kit.gradle", "gradlew", "gradlew.bat")
TIER_PREFIXES = ("gradle/", "versions/", "src/main/resources/", "tools/cbscript", "src/generated/")
NOTHING_FILES = ("gate-a.gradle", ".gitignore", ".gitattributes", "LICENSE")
NOTHING_PREFIXES = ("docs/", ".github/", "art/", "src/test/", "tools/", "third_party/", "corpus/gate-", ".port/")

HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", re.M)


class Change:
    """One changed file: its status (A, M, D, R), its path at the base and at the head, and the base-side line ranges
    of its hunks (an insertion is the two lines it sits between)."""

    def __init__(self, status: str, old: Optional[str], new: Optional[str], hunks: Optional[List[Tuple[int, int]]] = None,
                 lines: Optional[List[str]] = None):
        self.status, self.old, self.new, self.hunks = status, old, new, hunks or []
        self.lines = lines or []   # the changed lines themselves, removed and added, without their +/- mark

    @property
    def path(self) -> str:
        return self.new or self.old or ""


def parse_hunks(diff: str) -> List[Tuple[int, int]]:
    out = []
    for m in HUNK.finditer(diff):
        start, count = int(m.group(1)), int(m.group(2) if m.group(2) is not None else 1)
        out.append((start, start + 1) if count == 0 else (start, start + count - 1))
    return out


def changed_lines(diff: str) -> List[str]:
    return [ln[1:] for ln in diff.splitlines() if ln[:1] in "+-" and not ln.startswith(("+++", "---"))]


def parse_drift(diff: str) -> List[Tuple[int, int, int, int]]:
    """A -U0 diff from the map's commit (a) to the merge base (b) as (a_start, a_count, b_start, b_count) hunks."""
    out = []
    for m in HUNK.finditer(diff):
        out.append((int(m.group(1)), int(m.group(2) if m.group(2) is not None else 1),
                    int(m.group(3)), int(m.group(4) if m.group(4) is not None else 1)))
    return out


def to_map_lines(lo: int, hi: int, drift: List[Tuple[int, int, int, int]]) -> Tuple[int, int]:
    """A merge-base line range in the map's line numbers. A line the drift left alone moves by the drift above it; a
    line the drift changed stands for the whole changed range it came from (so the methods it may touch only grow)."""
    def one(b: int) -> Tuple[int, int]:
        offset = 0
        for a_s, a_c, b_s, b_c in drift:
            b_end = b_s + b_c - 1 if b_c else b_s - 1
            if b < (b_s if b_c else b_s + 1):
                break
            if b_c and b_s <= b <= b_end:
                return (a_s, a_s + max(a_c, 1) - 1) if a_c else (a_s, a_s + 1)
            offset = (a_s + a_c) - (b_s + b_c)
        return (b + offset, b + offset)
    return one(lo)[0], one(hi)[1]


# a class the game or NeoForge finds by itself, with no code naming it
DISCOVERED = re.compile(r"@EventBusSubscriber\b|@Mod\b|@Mixin\b")
# ...unless only a game client finds it: a GameTest server never loads a Dist.CLIENT subscriber
CLIENT_ONLY = re.compile(r"@EventBusSubscriber\s*\([^)]*Dist\.CLIENT")
CONSTANT = re.compile(r"\bstatic\s+final\s+[\w<>\[\], ?]+\s+\w+\s*=")


def _pkg(path: str) -> str:
    return path[len(JAVA):] if path.startswith(JAVA) else path


def _simple(path: str) -> str:
    return os.path.basename(path)[:-len(".java")] if path.endswith(".java") else os.path.splitext(os.path.basename(path))[0]


class Selector:
    def __init__(self, tier: Dict[str, dict], sources: Dict[str, str], cmap: Optional[dict],
                 base_blobs: Dict[str, str], now: Optional[float] = None, drift=None):
        """`tier`: gatelib.scan_tier at the head. `sources`: every src/main/java file's text at the head, by repo path.
        `cmap`: the coverage map (or None). `base_blobs`: each production file's blob at the merge base, by package
        path (covmap.source_blobs), to tell whether the map's line numbers still hold for a file."""
        import covmap
        self.tier = tier
        # what a file NAMES is read from its code and string literals, never its comments
        self.sources = {p: g.strip_java(t, strings=False) for p, t in sources.items()}
        self.cmap = cmap
        self.base_blobs = base_blobs
        self.decoded = covmap.decode(cmap) if cmap else None
        self.reasons: Dict[str, List[str]] = {}
        self.tier_reasons: List[str] = []
        self.now = now if now is not None else time.time()
        # drift(path) -> the -U0 hunks from the map's commit to the merge base for one file, or None when unknown
        self.drift = drift
        if self.decoded:
            by_file: Dict[str, Set[str]] = {}
            for mid, (f, _r) in self.decoded["where"].items():
                by_file.setdefault(f, set()).add(mid)
            self.methods_of_file = by_file
            reach: Dict[str, Set[str]] = {}
            for unit, ms in self.decoded["units"].items():
                for mid in ms:
                    reach.setdefault(mid, set()).add(unit.split("#", 1)[0])
            self.reach = reach

    # ------------------------------------------------------------------ helpers

    def add(self, cls: str, why: str) -> None:
        if cls in self.tier:
            self.reasons.setdefault(cls, [])
            if why not in self.reasons[cls]:
                self.reasons[cls].append(why)

    def whole_tier(self, why: str) -> None:
        if why not in self.tier_reasons:
            self.tier_reasons.append(why)

    def namers(self, simple: str, gametests_only: bool) -> List[str]:
        """Source files (repo paths) that name `simple` as a word, other than its own file."""
        word = re.compile(r"\b%s\b" % re.escape(simple))
        out = []
        for path, text in self.sources.items():
            if _simple(path) == simple:
                continue
            if gametests_only and _simple(path) not in self.tier:
                continue
            if simple in text and word.search(text):
                out.append(path)
        return sorted(out)

    def reach_methods(self, methods: Iterable[str], why: str) -> None:
        for mid in methods:
            for cls in sorted(self.reach.get(mid, ())):
                self.add(cls, why)

    def file_level(self, pkgpath: str, why: str) -> None:
        self.reach_methods(sorted(self.methods_of_file.get(pkgpath, ())), why)

    # ------------------------------------------------------------------ the rules

    def java(self, c: Change) -> None:
        path = c.path
        simple = _simple(path)
        if simple in self.tier and c.status != "D":
            self.add(simple, "its own file changed")
        for p in self.namers(simple, gametests_only=True):
            self.add(_simple(p), "names %s" % simple)
        if not self.decoded:
            return
        pkg_old = _pkg(c.old) if c.old else None
        if c.status == "A":
            # nothing executed it yet. Code reaches a new class only by naming it, and that code changed in this same
            # diff (it could not have named the class before), so its own hunks carry the selection; the tests that
            # name it are above. What the diff cannot show is the framework finding the class by itself.
            text = self.sources.get(path, "")
            if DISCOVERED.search(text) and not CLIENT_ONLY.search(text):
                self.whole_tier("%s is a new class the game finds by itself (%s)" % (
                    path, DISCOVERED.search(text).group(0)))
            return
        if c.status in ("D", "R") or pkg_old is None:
            self.file_level(pkg_old or _pkg(path), "reaches %s (%s)" % (simple, "deleted" if c.status == "D" else "renamed"))
            return
        hunks = list(c.hunks)
        if self.cmap.get("files", {}).get(pkg_old) != self.base_blobs.get(pkg_old):
            # the file changed between the map's commit and the merge base: carry the hunks back through that drift
            drift = self.drift(c.old) if self.drift else None
            if drift is None:
                self.file_level(pkg_old, "reaches %s (changed since the map, and the map's commit is not here)" % simple)
                return
            hunks = [to_map_lines(lo, hi, drift) for lo, hi in hunks]
        methods: Set[str] = set()
        outside = False
        ranges = [(mid, r) for mid in self.methods_of_file.get(pkg_old, ()) for r in self.decoded["where"][mid][1]]
        for lo, hi in hunks:
            inside = [mid for mid, (f, l) in ranges if f <= lo and hi <= l]
            if inside:
                methods.update(inside)
            else:
                outside = True
                methods.update(mid for mid, (f, l) in ranges if not (hi < f or l < lo))
        ambient = sorted(m for m in methods if m in self.decoded["ambient"])
        static_init = [m for m in ambient if m.endswith("#<clinit>")]
        others = [m for m in ambient if m not in static_init]
        if others:
            self.whole_tier("%s changes code that runs with no test running (%s)" % (
                path, ", ".join(a.split("/")[-1] for a in others[:3])))
            return
        if static_init:
            # a static initialiser runs at start-up, but what it sets is seen only by code that runs this class or
            # names it: the whole file, and every file that names the class
            self.file_level(pkg_old, "reaches %s (its static initialiser changed)" % simple)
            for p in self.namers(simple, gametests_only=False):
                if p.startswith(JAVA):
                    self.file_level(_pkg(p), "reaches %s, which names %s (a static initialiser changed)" % (
                        _simple(p), simple))
            methods -= set(static_init)
        for mid in sorted(methods):
            self.reach_methods([mid], "runs %s" % mid.split("/")[-1])
        if outside:
            self.file_level(pkg_old, "reaches %s (a change outside its methods)" % simple)
        if any(CONSTANT.search(ln) for ln in c.lines):
            # a compile-time constant is copied into every class that uses it, which then never executes this file
            for p in self.namers(simple, gametests_only=False):
                if p.startswith(JAVA):
                    self.file_level(_pkg(p), "reaches %s, which names %s (a constant changed)" % (_simple(p), simple))

    def named_file(self, c: Change, tier_if_unnamed: bool) -> None:
        """A data file is read by the sources that name it (its file name, without the extension)."""
        stem = os.path.splitext(os.path.basename(c.path))[0]
        if stem.endswith(".json"):
            stem = stem[:-5]
        named = self.namers(stem, gametests_only=False)
        if not named:
            if tier_if_unnamed:
                self.whole_tier("%s is read by no source that names it" % c.path)
            return
        for p in named:
            if _simple(p) in self.tier:
                self.add(_simple(p), "names %s" % os.path.basename(c.path))
            elif self.decoded and p.startswith(JAVA):
                self.file_level(_pkg(p), "reaches %s, which reads %s" % (_simple(p), os.path.basename(c.path)))
        if not self.decoded and any(not (_simple(p) in self.tier) for p in named):
            self.whole_tier("%s is read by production code and there is no coverage map" % c.path)

    def change(self, c: Change) -> None:
        p = c.path
        if p in NOTHING_FILES or (p.endswith(".md") and "/" not in p):
            return
        if p == "gradle.properties" and c.lines and all(
                ln.strip().startswith("mod_version=") or not ln.strip() for ln in c.lines):
            return   # a version bump: no code changes
        if p.startswith("src/main/resources/assets/"):
            self.named_file(c, tier_if_unnamed=False)
            return
        if p in TIER_FILES or p.startswith(TIER_PREFIXES) or p.endswith(".mixins.json"):
            self.whole_tier("%s (build, resources or the datapack compiler: the map cannot see them)" % p)
            return
        if p.startswith(NOTHING_PREFIXES):
            return
        if p.startswith(JAVA) and p.endswith(".java"):
            if any((x.startswith(HARNESS) or x in HARNESS_FILES) and _simple(x) not in self.tier
                   for x in (c.old, c.new) if x):
                self.whole_tier("%s is the GameTest harness" % p)
                return
            if any("/mixin/" in x for x in (c.old, c.new) if x):
                self.whole_tier("%s is a mixin" % p)
                return
            self.java(c)
            return
        if p.startswith("corpus/"):
            self.named_file(c, tier_if_unnamed=True)
            return
        self.whole_tier("%s: a path the selection has no rule for" % p)

    def select(self, changes: List[Change]) -> dict:
        if self.cmap is None:
            self.whole_tier("there is no coverage map")
        else:
            built = self.cmap.get("built", "")
            try:
                age = (self.now - calendar.timegm(time.strptime(built, "%Y-%m-%dT%H:%M:%SZ"))) / 86400.0
            except ValueError:
                age = 1e9
            if age > MAX_AGE_DAYS:
                self.whole_tier("the coverage map is %.0f days old (more than %d)" % (age, MAX_AGE_DAYS))
        for c in changes:
            self.change(c)
        for cls, v in self.tier.items():
            if v["ns"] == g.SHARDS["ci"]:
                self.add(cls, "the ci shard")
        if self.cmap is not None:
            placed = set(self.cmap.get("units", {}))
            for cls in sorted(self.tier):
                if cls not in placed:
                    self.add(cls, "the map has not placed it")
        total = sum(v["tests"] for v in self.tier.values())
        if self.tier_reasons:
            chosen = sorted(self.tier)
            return {"mode": "tier", "why": self.tier_reasons, "classes": {c: self.reasons.get(c, ["the whole tier"])
                                                                         for c in chosen},
                    "tests": total, "tierTests": total}
        tests = sum(self.tier[c]["tests"] for c in self.reasons)
        return {"mode": "targeted", "why": [], "classes": {c: self.reasons[c] for c in sorted(self.reasons)},
                "tests": tests, "tierTests": total}


# ---------------------------------------------------------------------------------------------- git

def changes_between(base: str, head: str, root: str = g.ROOT) -> List[Change]:
    """The changes from the merge base of `base` and `head` to `head` (what the pull request itself changed: main's
    commits merged into it are not its changes, a file both sides changed is)."""
    mb = g.git("merge-base", base, head, cwd=root)
    out = []
    for line in g.git("diff", "--name-status", "-M", mb, head, cwd=root).splitlines():
        parts = line.split("\t")
        st = parts[0][0]
        if st == "R" or st == "C":
            c = Change("R", parts[1], parts[2])
        elif st == "A":
            c = Change("A", None, parts[1])
        elif st == "D":
            c = Change("D", parts[1], None)
        else:
            c = Change("M", parts[1], parts[1])
        if c.status == "M" and (c.path.endswith(".java") or c.path == "gradle.properties"):
            d = g.git("diff", "-U0", mb, head, "--", c.path, cwd=root)
            c.hunks, c.lines = parse_hunks(d), changed_lines(d)
        out.append(c)
    return out


def sources_at(rev: str, root: str = g.ROOT) -> Dict[str, str]:
    """Every src/main/java file's text at `rev` (read through git, so it is the commit's, not the work tree's)."""
    import subprocess
    paths = [p for p in g.git("ls-tree", "-r", "--name-only", rev, "--", JAVA, cwd=root).splitlines()
             if p.endswith(".java")]
    out: Dict[str, str] = {}
    proc = subprocess.run(["git", "cat-file", "--batch"], cwd=root, input="".join("%s:%s\n" % (rev, p) for p in paths)
                          .encode("utf-8"), stdout=subprocess.PIPE, check=True)
    data, i = proc.stdout, 0
    for p in paths:
        nl = data.index(b"\n", i)
        size = int(data[i:nl].split()[2])
        out[p] = data[nl + 1:nl + 1 + size].decode("utf-8", "replace")
        i = nl + 1 + size + 1
    return out


def drift_provider(map_commit: Optional[str], merge_base: str, root: str = g.ROOT):
    """drift(path): the -U0 hunks from the map's commit to the merge base, or None when the map's commit is not in this
    clone (then a file that changed since the map is taken whole)."""
    if not map_commit:
        return None
    try:
        g.git("cat-file", "-e", map_commit + "^{commit}", cwd=root)
    except Exception:  # noqa: BLE001 - not fetched: the selection falls back to whole files, never to less
        return None

    def drift(path: str) -> Optional[List[Tuple[int, int, int, int]]]:
        return parse_drift(g.git("diff", "-U0", map_commit, merge_base, "--", path, cwd=root))
    return drift


def report(sel: dict) -> str:
    lines = []
    if sel["mode"] == "tier":
        lines.append("Gate B selection: THE WHOLE TIER (%d tests), because:" % sel["tierTests"])
        lines += ["  - " + w for w in sel["why"]]
    else:
        lines.append("Gate B selection: %d of the tier's %d tests (%.0f%%), %d classes:" % (
            sel["tests"], sel["tierTests"], 100.0 * sel["tests"] / max(1, sel["tierTests"]), len(sel["classes"])))
        for cls, why in sel["classes"].items():
            lines.append("  %-48s %s" % (cls, "; ".join(why[:4]) + (" (+%d more)" % (len(why) - 4) if len(why) > 4 else "")))
    return "\n".join(lines) + "\n"


def select_cmd(a) -> int:
    import json
    import covmap
    cmap = g.read_json(a.map) if a.map and os.path.exists(a.map) else None
    head = a.head
    mb = g.git("merge-base", a.base, head)
    srcs = sources_at(head)
    tier = g.tier_classes(g.scan_sources(srcs), g.FULL_TIER)
    sel = Selector(tier, srcs, cmap, covmap.source_blobs(mb), drift=drift_provider(cmap and cmap.get("commit"), mb)) \
        .select(changes_between(a.base, head))
    sel["base"], sel["mergeBase"], sel["head"] = a.base, mb, g.git("rev-parse", head)
    sel["map"] = {"commit": cmap.get("commit"), "built": cmap.get("built")} if cmap else None
    if a.out:
        g.write_json(a.out, sel)
    if a.classes_out:
        g.write_text(a.classes_out, "" if sel["mode"] == "tier" else ",".join(sel["classes"]))
    print(report(sel))
    return 0


def backtest_cmd(a) -> int:
    """The policy over past pull requests: what fraction of the tier each would have run, and whether it would have
    selected the tests that failed (docs/GATE_B.md, "Targeted Gate B")."""
    import covmap
    prs = g.read_json(a.prs)
    cmap = g.read_json(a.map) if a.map else None
    reds = {}
    if a.reds:
        for r in g.read_json(a.reds):
            reds.setdefault(str(r["pr"]), []).append(r)
    rows = []
    for pr in sorted(prs, key=lambda p: p["n"]):
        try:
            mb = g.git("merge-base", pr["base"], pr["head"])
            srcs = sources_at(pr["head"])
            tier = g.tier_classes(g.scan_sources(srcs), g.FULL_TIER)
            sel = Selector(tier, srcs, cmap, covmap.source_blobs(mb), now=a.now,
                           drift=drift_provider(cmap and cmap.get("commit"), mb)).select(
                changes_between(pr["base"], pr["head"]))
        except Exception as e:  # noqa: BLE001 - a PR whose commits are gone is reported, not guessed
            rows.append({"pr": pr["n"], "error": str(e)})
            continue
        caught = []
        for r in reds.get(str(pr["n"]), []):
            caught.append((r["test"], sel["mode"] == "tier" or r["test"].split(".")[0] in sel["classes"]))
        rows.append({"pr": pr["n"], "title": pr.get("title", ""), "mode": sel["mode"], "tests": sel["tests"],
                     "tier": sel["tierTests"], "why": sel["why"][:2], "reds": caught})
    ok = [r for r in rows if "error" not in r]
    frac = [r["tests"] / float(r["tier"]) for r in ok]
    print("pr    mode      tests  share  why (when the whole tier)")
    for r in rows:
        if "error" in r:
            print("#%-4s ERROR %s" % (r["pr"], r["error"][:100]))
            continue
        print("#%-4s %-8s %5d  %4.0f%%  %s" % (r["pr"], r["mode"], r["tests"], 100.0 * r["tests"] / r["tier"],
                                               "; ".join(r["why"])[:110]))
    if frac:
        import statistics
        all_reds = [x for r in ok for x in r["reds"]]
        print("\n%d pull requests: the selection ran %.0f%% of the tier's tests on average (median %.0f%%); %d of them "
              "the whole tier. Past reds selected: %d of %d." % (
                  len(ok), 100 * statistics.mean(frac), 100 * statistics.median(frac),
                  sum(1 for r in ok if r["mode"] == "tier"), sum(1 for _t, c in all_reds if c), len(all_reds)))
    if a.out:
        g.write_json(a.out, rows)
    return 0
