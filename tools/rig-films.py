#!/usr/bin/env python3
"""rig-films.py -- which rig films exist, and does this change need them?

The atom rig (src/main/java/com/moddingfromamod/vanilla/) is proven by Gate C client tests: a real client under Xvfb
filming a creature and judging what it drew by measurement. docs/VANILLA_RIG_SCOREBOARD.md marks rows VERIFIED against
them (`GateC:<testmode>`). Nothing runs them unless something asks, so this is the rule that asks:

    python3 tools/rig-films.py --list                 # the films tools/gate-c-rig.sh runs: "<testmode> <class>"
    python3 tools/rig-films.py --needed [BASE]        # `rig-films` or `none` for this branch against BASE
                                                      # (default origin/main), and the paths that decided it
    python3 tools/rig-films.py --needed --paths a b   # the same for an explicit list of paths
    python3 tools/rig-films.py --check                # every film resolves to a client test, every path rule
                                                      # still matches a real file (exit 1 otherwise)
    python3 tools/rig-films.py --self-test

THE SET IS READ OFF THE SCOREBOARD, not listed: every `GateC:<testmode>` a row names, so a new VERIFIED row brings its
film into the gate in the same edit, and a film nobody leans on any more drops out. Plus RIG_FILMS_EXTRA (films that
prove a rig property and name no row), minus RIG_FILMS_NOT_RUN (named, deliberately not run, each with its reason).

The films are REQUIRED when a change touches what they measure (RIG_PATHS). CI runs them in their own job on a pull
request whose paths match (.github/workflows/ci.yml, `rig-films`), never on every pull request: the set is ~35-50 min.
"""
import argparse
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCOREBOARD = "docs/VANILLA_RIG_SCOREBOARD.md"
FILM_DIR = "src/main/java/com/moddingfromamod/gatec"
RIG_FILMS_EXTRA = ("vanillascale",)
# Films a scoreboard row names that the gate deliberately does NOT run, each with the reason.
RIG_FILMS_NOT_RUN = {
    "vanillawish": "a paid real-agent wish through the Magician (Gate D), not a rig film: run it by hand",
}
# One alternative per line, so --check can ask each whether it still matches a real file.
RIG_PATH_RULES = (
    r"src/main/java/com/moddingfromamod/vanilla/",
    r"src/main/java/com/moddingfromamod/gatec/",
    r"src/main/java/com/moddingfromamod/runtime/(Creature)?Sculpt[A-Za-z]*\.java$",
    r"src/main/java/com/moddingfromamod/agent/tools/SculptCreatureTool\.java$",
    r"src/main/resources/lessons/creature\.md$",
    r"src/main/resources/starter-library/species/",
    r"docs/VANILLA_RIG_SCOREBOARD\.md$",
    r"tools/(client-test\.sh|gate-c-rig\.sh|rig-drift\.py|rig-films\.py)$",
)
RIG_PATHS = re.compile("^(" + "|".join(RIG_PATH_RULES) + ")")


def rig_films(repo=REPO):
    """The testmodes tools/gate-c-rig.sh runs, in scoreboard order."""
    text = open(os.path.join(repo, SCOREBOARD), encoding="utf-8").read()
    out = []
    for m in re.findall(r"GateC:([a-z0-9]+)", text) + list(RIG_FILMS_EXTRA):
        if m not in out and m not in RIG_FILMS_NOT_RUN:
            out.append(m)
    return out


def film_class(mode, repo=REPO):
    """The client test claiming `mode` (a class whose source holds the literal "<mode>"), or None."""
    base = os.path.join(repo, FILM_DIR)
    for n in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        if n.endswith(".java") and f'"{mode}"' in open(os.path.join(base, n), encoding="utf-8").read():
            return f"{FILM_DIR}/{n}"
    return None


def needed(paths):
    """`rig-films` when any path is one the films measure, else `none`; and the paths that did it."""
    hit = [p for p in paths if RIG_PATHS.match(p)]
    return ("rig-films" if hit else "none"), hit


def changed_paths(base):
    """Every path this branch changed against the merge base with `base` (both sides of a rename)."""
    mb = subprocess.run(["git", "merge-base", base, "HEAD"], cwd=REPO, capture_output=True, text=True)
    if mb.returncode != 0:
        sys.exit(f"rig-films: no merge base with {base} (fetch it first)")
    out = subprocess.run(["git", "diff", "--name-only", "--no-renames", mb.stdout.strip(), "HEAD"],
                         cwd=REPO, capture_output=True, text=True, check=True).stdout
    return [p for p in out.splitlines() if p]


def check(repo=REPO):
    problems = []
    films = rig_films(repo)
    for m in films + list(RIG_FILMS_NOT_RUN):
        if film_class(m, repo) is None:
            problems.append(f"rig film '{m}' is named but no client test in {FILM_DIR} claims that testmode")
    tracked = subprocess.run(["git", "ls-files"], cwd=repo, capture_output=True, text=True).stdout.splitlines()
    for alt in RIG_PATH_RULES:
        if not any(re.match(alt, p) for p in tracked):
            problems.append(f"rig path rule {alt!r} matches no tracked file: a rename left it stale")
    print(f"rig-films: {len(films)} films ({', '.join(films)}); not run: {', '.join(RIG_FILMS_NOT_RUN)}")
    return problems


def self_test():
    cases = [
        (["src/main/java/com/moddingfromamod/vanilla/DisplayRig.java"], "rig-films"),
        (["src/main/java/com/moddingfromamod/gatec/VanillaWalkClientTest.java"], "rig-films"),
        (["src/main/java/com/moddingfromamod/runtime/CreatureSculpt.java"], "rig-films"),
        (["src/main/java/com/moddingfromamod/runtime/SculptKits.java"], "rig-films"),
        (["src/main/java/com/moddingfromamod/agent/tools/SculptCreatureTool.java"], "rig-films"),
        (["src/main/resources/lessons/creature.md"], "rig-films"),
        (["src/main/resources/starter-library/species/dragon/body.json"], "rig-films"),
        (["docs/VANILLA_RIG_SCOREBOARD.md"], "rig-films"),
        (["README.md", "src/main/java/com/moddingfromamod/wish/WishService.java"], "none"),
        (["src/main/resources/lessons/build.md"], "none"),
        (["src/main/java/com/moddingfromamod/runtime/CreatureFiles.java"], "none"),
        ([], "none"),
    ]
    bad = 0
    for paths, want in cases:
        got, _ = needed(paths)
        ok = got == want
        bad += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {paths or '[]'} -> {got} (wanted {want})")
    films = rig_films()
    for m in RIG_FILMS_NOT_RUN:
        if m in films:
            bad += 1
            print(f"  FAIL a film in RIG_FILMS_NOT_RUN is in the set: {m}")
    for m in RIG_FILMS_EXTRA:
        if m not in films:
            bad += 1
            print(f"  FAIL an extra film is missing from the set: {m}")
    print("✅ rig-films self-test passed" if not bad else f"❌ rig-films self-test: {bad} failure(s)")
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--needed", nargs="?", const="origin/main", metavar="BASE")
    ap.add_argument("--paths", nargs="*")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.list:
        for m in rig_films():
            print(m, film_class(m) or "?")
        return 0
    if a.check:
        problems = check()
        for p in problems:
            print(f"rig-films: {p}", file=sys.stderr)
        return 1 if problems else 0
    if a.needed is not None or a.paths is not None:
        verdict, hit = needed(a.paths if a.paths is not None else changed_paths(a.needed))
        print(verdict)
        for p in hit:
            print(f"  because {p}", file=sys.stderr)
        return 0
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
