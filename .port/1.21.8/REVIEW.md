# Review: Minecraft 1.21.8 (version branch neoforge-1.21.8)

An independent review by a FRESH agent that was given only this version's changes: every file that differs
from the parent branch (?) at their merge-base (`git diff <merge-base>..neoforge-1.21.8`), read against the parent's
file, its STUBS.json and .port/1.21.8/renames.tsv. One row per STUBS.json entry, per `changed` file, per
resource group (`resources`: a directory with the digest of its changed files -- an edit makes it a new
row) and for renames.tsv (`renames`, with its digest); the files the rename rows alone made, the build
settings tools/fork-branches.py derives and added helper classes need no row; a file deleted here is a `deleted`
row unless drops.txt or a mixin_remove key accounts for it. Verdict `ok` (the change keeps
the behaviour, or the difference is right and the note says why) or `not-ok`. A hook item of the behaviour
inventory is accounted for only by a row whose note names its id, or by an `item` row for the id; a
reviewer may add `missing` (a file that should exist) and `shared` (a shared helper) rows. `reviewer` names
the reviewing agent, distinct from the fixer; `commit` is the commit reviewed (`git rev-parse --short HEAD`).
tools/fork-port.py adds a `?` row for every item it finds unreviewed; it never changes a verdict.

- reviewer: fresh agent (subagent), not the fixer
- date: 2026-11-24
- commit: 7f5e22c

| kind | file | verdict | note |
|---|---|---|---|
| changed | test-kit.gradle | ok | Diffed 7f5e22c against 5363bf7: the only change to test-kit.gradle is a new, stand-alone `testsJar` Jar task appended after the existing testKitJar (applied via gate-a.gradle:312, not build.gradle directly). It writes to build/core-tests/ with classifier `coretests`, never build/libs (which port-ci.yml:215-225 and tools/store-release.sh release), is not wired into assemble/build/check, adds no configuration, artifact, dependency, run config or processResources/jarJar/minecraft change. The shipped `jar` and `jarJar` (build.gradle:733-765) still apply SHIP_EXCLUDES (corpus/**, test/**, testing/**, *GameTest classes, *.gametest.mixins.json) and jarJar's doLast NEVER_SHIP check on the finished jar is unchanged, so GameTests, the harness and the corpus stay out of the player's jar. No player-visible behaviour change. |
