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
   (`tools/gate-c-rig.sh`, every film, declared in `.port/1.21.8/gate-c.json`), run in a real client on the full
   environment; the release's `gates-full.md` records the Gate C row. A version branch above 1.21.8 inherits that file
   until it has its own.

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

## Promotion

Only the owner promotes, with `/promote`, and only builds a person has played. That covers a mid-week 1.21.8 release
too. `played_by` must be the GitHub login of whoever played the build: Port CI refuses a promotion without it. Don't
promote by unticking "pre-release" in the GitHub UI. That publishes too, but it records nobody as having played it.

## A hotfix

A hotfix is a **pair of pull requests: `main` first, then `neoforge-1.21.8`** carrying the same commits.
`release-line.yml` refuses a commit on `neoforge-1.21.8` that has no equal on `main`, so the `main` one comes first.
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
  it by hand. `.port/1.21.8/gate-c.json` names its Gate C (the rig films); that file is ours to edit.
- Moddy-Worlds is the second repository in the same plan: one issue, one round, one `/promote`. It follows the same
  rule with its own `main` and its own 1.21.8 line.
