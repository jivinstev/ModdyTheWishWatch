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
# Films ONE Minecraft version does not run, by that version (gradle.properties' minecraft_version on the branch that
# runs them), each with the reason: a version's declared subset of the set above. Every version branch runs this
# file's set through the one .port/1.21.8/gate-c.json (Port CI takes the nearest lower version's file), so a film
# that cannot run on a version is named here, on main, and reaches that branch with the next merge up. Empty means
# every version runs every film; docs/RELEASING.md ("Gate C on every version") says which ran where.
RIG_FILMS_NOT_ON = {
}
# The films a TARGETED run always runs, whatever changed (tools/gate-c-rig.sh --targeted, Port CI's Gate C on every
# version, .port/<mc>/gate-c.json): a real client draws and photographs a creature (vanillastage), a rig walks with every
# part held to its body (vanillawalk, the drift meter), and a rig falls and spins as it dies (vanilladeath, the spin
# rule). About 2.5 minutes together; a change to the rig itself runs every film.
RIG_FILMS_CORE = ("vanillastage", "vanillawalk", "vanilladeath")
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


def minecraft_version(repo=REPO):
    """This checkout's Minecraft version (gradle.properties), or "" without one."""
    try:
        text = open(os.path.join(repo, "gradle.properties"), encoding="utf-8").read()
    except OSError:
        return ""
    m = re.search(r"(?m)^minecraft_version=(\S+)", text)
    return m.group(1) if m else ""


def not_on(mc=None, repo=REPO):
    """{film: reason} this Minecraft version leaves out (RIG_FILMS_NOT_ON)."""
    return dict(RIG_FILMS_NOT_ON.get(minecraft_version(repo) if mc is None else mc, {}))


def rig_films(repo=REPO, mc=None, every_version=False):
    """The testmodes tools/gate-c-rig.sh runs on this checkout's Minecraft version (or `mc`), in scoreboard order.
    every_version: the whole set, before any version's leave-outs."""
    text = open(os.path.join(repo, SCOREBOARD), encoding="utf-8").read()
    skip = {} if every_version else not_on(mc, repo)
    out = []
    for m in re.findall(r"GateC:([a-z0-9]+)", text) + list(RIG_FILMS_EXTRA):
        if m not in out and m not in RIG_FILMS_NOT_RUN and m not in skip:
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


def targeted(paths, repo=REPO, mc=None):
    """(films, reasons): what a targeted run runs for a change to `paths`. Every film when a path is one the films
    measure in common (the rig, the harness, the sculpt tool, the scripts, the scoreboard); a film when only its own
    client test changed; and RIG_FILMS_CORE always. In set order, this version's leave-outs left out."""
    films = rig_films(repo, mc)
    own = {}
    for m in films:
        c = film_class(m, repo)
        if c:
            own[c] = m
    pick, why, every = set(), [], False
    for p in paths:
        if not RIG_PATHS.match(p):
            continue
        if p in own:
            pick.add(own[p])
            why.append(f"{own[p]}: its client test changed ({p})")
        elif not every:
            every = True
            why.append(f"every film: {p} is something every film measures")
    if every:
        return films, why
    for m in RIG_FILMS_CORE:
        if m in films and m not in pick:
            pick.add(m)
            why.append(f"{m}: the core set, run on every change")
    return [m for m in films if m in pick], why


def release_base(repo=REPO, mod_id="moddingfromamod"):
    """The newest release tag of this checkout's Minecraft version below HEAD (`<mod>-<version>-mc<mc>`), or None."""
    mc = minecraft_version(repo)
    r = subprocess.run(["git", "describe", "--tags", "--abbrev=0", "--match", f"{mod_id}-*-mc{mc}", "HEAD^"],
                       cwd=repo, capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 and r.stdout.strip() else None


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
    every = rig_films(repo, every_version=True)
    for mc, left in RIG_FILMS_NOT_ON.items():
        if not re.fullmatch(r"\d+(\.\d+)+", mc):
            problems.append(f"RIG_FILMS_NOT_ON: {mc!r} is not a Minecraft version")
        for m, why in left.items():
            if m not in every:
                problems.append(f"RIG_FILMS_NOT_ON[{mc}]: {m!r} is not a film of the set")
            if not str(why).strip():
                problems.append(f"RIG_FILMS_NOT_ON[{mc}]: {m!r} names no reason")
    for m in films + list(RIG_FILMS_NOT_RUN):
        if film_class(m, repo) is None:
            problems.append(f"rig film '{m}' is named but no client test in {FILM_DIR} claims that testmode")
    tracked = subprocess.run(["git", "ls-files"], cwd=repo, capture_output=True, text=True).stdout.splitlines()
    for alt in RIG_PATH_RULES:
        if not any(re.match(alt, p) for p in tracked):
            problems.append(f"rig path rule {alt!r} matches no tracked file: a rename left it stale")
    left = not_on(repo=repo)
    print(f"rig-films: {len(films)} films ({', '.join(films)}); not run: {', '.join(RIG_FILMS_NOT_RUN)}"
          + (f"; not on Minecraft {minecraft_version(repo)}: {', '.join(left)}" if left else ""))
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
    # a targeted run: core always; a film's own test adds that film; a shared rig path runs them all
    every_film = rig_films()
    core = [m for m in every_film if m in RIG_FILMS_CORE]
    walk_cls = film_class("vanillahair")
    for what, paths, want in [
        ("nothing the films measure: the core", ["README.md"], core),
        ("a film's own test: the core and that film", [walk_cls],
         [m for m in every_film if m in set(core) | {"vanillahair"}]),
        ("the rig itself: every film", ["src/main/java/com/moddingfromamod/vanilla/DisplayRig.java"], every_film),
        ("the script: every film", ["tools/gate-c-rig.sh"], every_film)]:
        got, _ = targeted(paths)
        ok = got == want
        bad += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} targeted: {what} -> {got}")
    for m in RIG_FILMS_CORE:
        if m not in every_film:
            bad += 1
            print(f"  FAIL a core film is not in the set: {m}")
    # a version's leave-outs leave that version's set only, and say why
    every = rig_films(every_version=True)
    saved = dict(RIG_FILMS_NOT_ON)
    try:
        RIG_FILMS_NOT_ON.clear()
        RIG_FILMS_NOT_ON["9.9"] = {every[0]: "a reason"}
        cases = [("9.9", every[0] not in rig_films(mc="9.9") and len(rig_films(mc="9.9")) == len(every) - 1),
                 ("another version", rig_films(mc="9.8") == every),
                 ("every_version", rig_films(mc="9.9", every_version=True) == every),
                 ("not_on", not_on("9.9") == {every[0]: "a reason"} and not_on("9.8") == {})]
    finally:
        RIG_FILMS_NOT_ON.clear()
        RIG_FILMS_NOT_ON.update(saved)
    for what, ok in cases:
        bad += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} RIG_FILMS_NOT_ON: {what}")
    print("✅ rig-films self-test passed" if not bad else f"❌ rig-films self-test: {bad} failure(s)")
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--needed", nargs="?", const="origin/main", metavar="BASE")
    ap.add_argument("--paths", nargs="*")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--targeted", nargs="?", const="release", metavar="BASE",
                    help="the films a targeted run runs for this branch against BASE (default: the newest release "
                         "tag of this Minecraft version below HEAD; none, every film), as --list prints them")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.list:
        for m in rig_films():
            print(m, film_class(m) or "?")
        for m, why in not_on().items():
            print(f"rig-films: {m} is not run on Minecraft {minecraft_version()}: {why}", file=sys.stderr)
        return 0
    if a.targeted is not None:
        if a.paths is not None:
            films, why = targeted(a.paths)
        else:
            base = release_base() if a.targeted == "release" else a.targeted
            if base is None:
                films, why = rig_films(), [f"every film: no {minecraft_version()} release below HEAD to compare with"]
            else:
                films, why = targeted(changed_paths(base))
                why.insert(0, f"against {base}")
        for m in films:
            print(m, film_class(m) or "?")
        for w in why:
            print(f"rig-films: {w}", file=sys.stderr)
        for m, r in not_on().items():
            print(f"rig-films: {m} is not run on Minecraft {minecraft_version()}: {r}", file=sys.stderr)
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
