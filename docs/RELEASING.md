# Releasing Moddy

Moddy follows the same weekly ladder as any mod kept current with MMVU's keep-current cycle (from
`jivinstev/mc-mod-version-upgrade`). The difference is that the ladder's **upstream is our own `main`**, the way a
port's upstream is its authors' repository. `main` flows into the base of the ladder, `neoforge-1.21.8` (what production
pins and Moddy-Worlds tests against). The ladder then carries `neoforge-1.21.8` up to the newer Minecraft versions.

Anyone may integrate `main` into `neoforge-1.21.8` and cut a 1.21.8 release at any time in the week. The weekly round
does the same thing as one ticked box on its plan. Both follow the one rule below, so a version number always means
the same thing.

## The rule: every integrate is a new version

Taking `main` into `neoforge-1.21.8` always ships a new version:

1. **Bump on `main` first.** If `main` names no version past the newest `moddingfromamod-*-mc1.21.8` release, open a
   pull request on `main` titled `Version X.Y.Z: <summary>` that changes only `mod_version` in `gradle.properties`,
   to the next patch (1.0.10 → 1.0.11). Merge it once CI is green. A minor or major bump is a person's choice, made the
   same way by hand; a `main` already past the last release is taken as it is.
2. **Merge `main` into `neoforge-1.21.8`** with a merge commit titled
   `Merge main into neoforge-1.21.8: X.Y.Z, <summary>`. `release-line.yml` accepts it because every commit is on
   `main`.
3. **Port CI on `neoforge-1.21.8`** gates it and cuts the pre-release `moddingfromamod-X.Y.Z-mc1.21.8` (push the tag,
   or run the workflow with `release = true`). Its gates are Gate A, Gate B and, as Gate C, the rig films
   (`tools/gate-c-rig.sh --targeted`: the core films plus what changed since the last release, declared in
   `.port/1.21.8/gate-c.json`), run in a real client on the full environment; the release's `gates-full.md` records
   the Gate C row. Every rung declares the same in its own file ("Gate C on every version"). A set where a film failed and then passed its one retry (FLAKY, exit 3) is a pass on a release
   run only, with a `WARNING FLAKY` Gate C row in the release notes (`release_flaky_exit` in that file); a pull
   request's `rig-films` job and Port CI's push runs still count it as a failure. #171 tracks fixing the flakes.

Never release the same version twice from an integrate. Port CI's `-r2`, `-r3` names are only for a port-only fix on
a higher version branch, never for a 1.21.8 integrate.

An integrate mid-week touches 1.21.8 only and starts no cascade. `neoforge-1.21.8` can then be ahead of the other
version branches until the next weekly round carries it up.

## The weekly round

| When (Pacific) | What | Model |
|---|---|---|
| Tue 08:00 | **Plan.** `.github/workflows/weekly-plan.yml` opens one plan issue for the owner, with a section for each repository. It has a box, **ticked by default**, to take `main`'s new commits into `neoforge-1.21.8`, with the version that take ships named under it. It also lists anything already on `neoforge-1.21.8` that the higher versions haven't taken (ticked too), known issues (every open S1, then S2s up to the budget), dependency updates, and any cascade decision it needs. Its first line is the deadline. If nobody answers, the defaults apply. | none (GitHub Actions) |
| Tue to Wed | The owner can untick a box, `/decide <id> <choice>`, `/grade <ID> <S1-S4>`, or do nothing. | none |
| Wed morning | **Round.** A Claude scheduled task starts a fresh session. If the box is ticked, the round takes `main` into 1.21.8 following the rule above: the `Version X.Y.Z` PR on `main` when `main` hasn't been bumped, then its own PR into `neoforge-1.21.8`, gated, reviewed, merged and pre-released. It then carries `neoforge-1.21.8` up `neoforge-1.21.10` → `-1.21.11` → `-26.1.2` → `-26.2` → `-26.3`, gated, with a pre-release for each. It posts progress on the issue and closes it with the outcome and the week's cost row. | Opus orchestrates; MMVU's loops climb Haiku to Sonnet to Opus only on failure |
| After playing | **Promote and publish.** The owner plays the pre-releases, then comments `/promote <versions>` on the round's issue. Port CI's `promote` runs on each named branch with `played_by` set to the commenter, and its `publish` job uploads to Modrinth and CurseForge in the same run (`.github/port-publish.json`, `auto: true`). | none |

If the box is unticked, the round leaves `main` alone and only carries `neoforge-1.21.8` as it stands up the other
versions.

The round is installed with `--release-bump gradle.properties:mod_version`, which makes MMVU follow the rule above on
its own. It is allowed to push version branches, cut pre-releases and merge its own pull requests once CI is green
(`--allow push-version-branches --allow pre-release --allow merge-fork-prs`). The only pull request it opens on
`main` is the one-line version bump.

## The release gate: a green full tier for the code it ships

A release needs a **green run of the whole GameTest tier (`ci,default`) for the exact code it releases**: the same
gate tree (`python3 tools/gate/gate.py tree`, every file Gate B reads), not just the same branch. A pull request's
targeted run, a `ci`-only run, a run that left out a class or a test not on the flake register: none of them counts
(`tools/gate/release.py`, pinned by `test_gate.py`).

`python3 tools/gate/gate.py release-gate` is the check. It looks in the run history (the `gate-data` branch,
docs/GATE_B.md) for a green full-tier run, nightly or release, of the release commit's gate tree, and passes at once if
there is one: a release cut the morning after a green night reuses that night. Otherwise it **runs the tier itself**: it
starts the Nightly workflow on the release's branch or tag with `kind = release` (split across four runners, the flake
register's tests left out and named in its record), waits for it, and passes only if that run is green for this very
tree. It refuses, with one line saying why, when the run is red or does not finish in time.

Port CI runs it before it cuts a pre-release, through the kit's optional release-gate hook (MMVU
jivinstev/mc-mod-version-upgrade#306): `.github/port-release-gate.json` names the script and its arguments, and the kit
generates a `release-gate` job (`contents: read`, `actions: write`, never the release job's write access) that runs it
with the commit being released and its ref in `GATE_SHA` and `GATE_REF` (on a `from_run` release too). The release
needs that job to succeed: a non-zero exit, or a skipped gate, refuses it. A fork without the file is generated exactly
as before. The file is read when `tools/port-ci.py` generates the workflow, so `port-ci.yml` gains the job at the next
regeneration with a kit that has the hook; until then, run the check by hand before `release = true` (Actions →
Nightly → Run workflow with `kind = release` on `neoforge-1.21.8`, or `GH_TOKEN=… python3 tools/gate/gate.py
release-gate` at the release commit) and do not release on a refusal.

**The ladder rungs (1.21.10 → 26.3) need their own full tier.** Each rung is different code: its port's changes and a
different Minecraft. A green night on `main` says nothing about a test that fails only there (the two GameTests that
failed only on 26.1.2's full run, #40, are an example). The rungs already carry `main`'s files merged upward, so each
rung's release runs the same check on its own commit, by the same hook. Public runners make it free in money; the
cost is wall time: the first full tier on `main` (1421 tests on four runners) took 15.5 minutes from start to verdict,
so a rung with no covering run waits about that long before its pre-release. A rung whose tier is red is not
pre-released. A test that is flaky on a rung goes on the flake register with its
issue, like any other.

## Gate C on every version

**Every rung declares its own Gate C: the targeted rig films.** `.port/<mc>/gate-c.json` (1.21.8, 1.21.10, 1.21.11,
26.1.2, 26.2, 26.3, all on `main` and carried up by the merge) runs `tools/gate-c-rig.sh --targeted`, as Port CI's
Gate C, in a real client on the full environment. Port CI takes a rung's own file, else the nearest lower version's,
so a new rung inherits the one below until it gets its own. A targeted run is:

- **the core set, always** (`RIG_FILMS_CORE` in `tools/rig-films.py`: `vanillastage`, a creature drawn and
  photographed; `vanillawalk`, a walk with every part held to its body; `vanilladeath`, the fall and spin), about 2.5
  minutes;
- **plus what changed since the rung's last release** (the newest `moddingfromamod-*-mc<mc>` tag below HEAD): a
  film whose own client test changed runs, and a change to anything every film measures (the rig, the harness, the
  sculpt tool, the creature lesson, the scoreboard, the scripts: `RIG_PATH_RULES`) runs **every** film. With no
  release below HEAD, every film runs.

`python3 tools/rig-films.py --targeted [BASE]` prints the selection and why; `./tools/gate-c-rig.sh --targeted
[BASE] --list` shows what a run would run. The pull request job (`rig-films` in `ci.yml`) still runs every film when a
change touches what they measure. A film that fails is run once more and FLAKY is a pass only on a release run
(`release_flaky_exit`), as before.

**The set is decided per version, in one place.** `tools/rig-films.py` takes the films the scoreboard names, minus
`RIG_FILMS_NOT_RUN` (never run anywhere) and minus `RIG_FILMS_NOT_ON[<minecraft_version>]`: a version's declared
subset, each film there with its reason. `--list` on a branch prints that branch's set and names every film it leaves
out; `--check` refuses an entry that is not a film or gives no reason. A film is left out only when it **cannot run**
on a version (a harness that has nothing to measure there). A film that runs and fails on a rung is a finding about
that rung's port, and goes to an issue, never into this table to turn a release green. The table is empty today.

**What the films needed to run on 26.x**, all fixed on `main` so every rung gets them with the merge up (proved on
`neoforge-26.3` with `main`'s rig scripts, 2026-11-26):

| 26.x broke | Why | The fix (works on every version) |
|---|---|---|
| no window: `Couldn't find matching GLX visual` | 26.x opens its window with SDL3, which asks for an sRGB-capable framebuffer; Xvfb's software GLX has none | `tools/client-test.sh` sets `SDL_VIDEO_FORCE_EGL=1` (GLFW never reads it), and warns without Mesa's EGL (`apt-get install -y libegl1 libegl-mesa0`) |
| every photograph: `the GPU readback did not complete` | a fenced task's fence now waits for the frame's command-encoder submit | `gatec/Screenshots` submits the encoder when it has `submit()` (26.x), and does nothing more on 1.21.x |
| the walking films' drift meter: `NoSuchFieldException: interpolationData` | `InterpolationHandler` became an interface; a display's ease lives on `LinearInterpolationHandler` | `gatec/RigDriftMeter` finds the fields on the handler's own class hierarchy, by the names each version uses |
| `vanillaclips` row 5.2: `getAttackAnim advanced` | its "vanilla's number is always 0" is 1.21.x bytecode: 26.x advances every living entity's swing | the row asks the running game whether `updateSwingTime` exists, and only then requires 0 |

What ran where is in the table below; a rung's own release records its Gate C row in its `gates-full.md`.

| Version | Rig films | Where it was proved |
|---|---|---|
| 1.21.8 | all 13; the core set 154 s | Port CI, every release (`.port/1.21.8/gate-c.json`); this change locally |
| 26.3 | all 13: 12 pass, `vanillahair` flaky (failed then passed; 2 of 3 more runs passed, 1 hung); the core set 149 s | locally, `neoforge-26.3` + this change, Xvfb + llvmpipe (2026-11-26) |
| 1.21.10, 1.21.11, 26.1.2, 26.2 | not yet run | the first release after `main` is merged up runs them |

To run the films on a rung by hand: check out `neoforge-<v>`, install that version's Java (26.x needs 25:
`.port/<v>/branch.properties`, `java_version`), `apt-get install -y xvfb libgl1-mesa-dri libegl1 libegl-mesa0`, and
`GRADLE_ARGS=-Pstore_jar=false ./tools/gate-c-rig.sh`. A rung's client runs in `build/mc<v>/run`
(`.port/branch.gradle`), so its options never reach another version's client.

## Promotion

Only the owner promotes, with `/promote`, and only builds a person has played. That covers a mid-week 1.21.8 release
too. `played_by` must be the GitHub login of whoever played the build: Port CI refuses a promotion without it. Don't
promote by unticking "pre-release" in the GitHub UI. That publishes too, but it records nobody as having played it.

## A hotfix

A hotfix is a **pair of pull requests: `main` first, then `neoforge-1.21.8`** carrying the same commits.
`release-line.yml` refuses a commit on `neoforge-1.21.8` that has no equal on `main`, so the `main` one comes first.
Two kinds of commit are exempt: Port CI's install-manifest refresh, and the weekly round's records, which change
only `.port/KNOWN-ISSUES.json` and/or the README's Ports block (between the `ports` markers).
Bump the version on `main` in the same pair (rule step 1), then cut the 1.21.8 release. The fix reaches the other
versions with the next weekly round; if it can't wait, the owner presses **Run now** on the round task at
https://claude.ai/code/routines. Never fix the higher version branches by hand.

## Cost

Each round writes one ledger row:

- the plan issue, and the version carried up;
- branches moved;
- input, output and cache tokens for each model, sub-agents included;
- dollars at MMVU's `tools/model-prices.tsv`;
- Port CI wall-minutes and job-minutes;
- how many owner taps it took.

The row is posted as the issue's closing comment and copied to the pinned "Weekly ladder ledger" issue. The baseline is
the cost ledger of the manual 1.0.10 ladder, on #56. The repository is public, so its Actions minutes are free; they
are recorded so the size of each week is visible. The ledger is kept in issues, not as a file on a release branch,
because `release-line.yml` would refuse that commit.

## Where it lives

- `.github/workflows/weekly-plan.yml` and `.github/weekly-plan.json`: the plan. Installed by MMVU's
  `weekly-plan.py install` (the workflow is on `main` too, since GitHub runs a schedule only from the default branch).
  Never edit them by hand: re-run the install.
- The round: a scheduled task on the owner's claude.ai account, made from MMVU's `weekly-plan.py round-task`. The
  owner attaches both repositories to it (claude.ai/code/routines → the task → Edit → "Select a repository" under
  Instructions), because a scheduled session can't attach one itself.
- `.github/workflows/port-ci.yml`: gates, pre-release, promote and publish. Generated by the Port CI kit; don't edit
  it by hand. `.port/1.21.8/gate-c.json` names its Gate C (the rig films); that file is ours to edit, and every
  higher rung inherits it ("Gate C on every version"). `tools/rig-films.py` holds each version's declared subset.
- `.github/port-release-gate.json`: the command Port CI's release-gate hook runs before a pre-release
  (`tools/gate/gate.py release-gate`); `.github/workflows/nightly.yml` with `kind = release` is the tier it starts.
- Moddy-Worlds is the second repository in the same plan: one issue, one round, one `/promote`. It follows the same
  rule with its own `main` and its own 1.21.8 line.
