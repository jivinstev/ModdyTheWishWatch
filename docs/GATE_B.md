# Gate B on GitHub: the nightly full tier, run records and flakes

Gate B is the GameTest suite: a headless game server runs every `@GameTest` of the shards it is given (CLAUDE.md,
"Build and test"). This page says how it runs on GitHub's runners, beyond the `ci` shard every pull request gets: what
runs every night, where each run's result is kept, and how a flake is told from a regression. The tools are
`tools/gate/` (one command, `python3 tools/gate/gate.py`, Python 3.9+ and the standard library only), and their own
tests are `python3 tools/gate/test_gate.py`, which Gate A's CI job runs.

## One job: `gate.py run`

`gate.py run` boots ONE GameTest server on a share of the tier and refuses to trust a number it cannot account for:

- **The verdict comes from the server, not from Gradle.** A GameTest server exits 0 even when it never ran the tests,
  so the job reads the log's `N required tests passed|failed` line and the run record the server writes
  (`testing/GateBTimings`: every test, its seconds, batch and error, and how many tests the server registered).
- **It ran what it had.** The server must have run every test it registered (`gt-adapter: N tests found`), the log's
  count must agree with the record's, and every class the job was given must have run at least one test. A job that
  ran nothing is red, whatever the exit code.
- **A failed test is retried once, by itself.** `-Pgametests=Class.method` boots a server with that one test
  (`testing/GameTestClassFilter`). If it passes alone, the test is `flaky` in the record and the job stays green; if it
  fails again, it is `failed`. Past eight failures (a broken build, not eight flakes) the retries share one server.
- **A test that skips is counted and reported, never a pass.** Nothing in the tier can skip today; the record keeps
  the column so a future skip is a red `skipped`, not a quiet green.
- The vanilla server adds its own `minecraft:always_pass` to every run; it is not ours and is not counted.

The job writes `job.json` (the result above, with the commit and the gate tree) next to the server's logs.

```bash
python3 tools/gate/gate.py run --shard ci --out /tmp/gate-ci                     # the ci shard, as CI runs it
python3 tools/gate/gate.py run --classes McpGameTest,WishGameTest --out /tmp/gt  # two classes of the tier
python3 tools/gate/gate.py run --classes McpGameTest.toolsAreAdvertised --out /tmp/gt   # one test
```

## The nightly full tier

`.github/workflows/nightly.yml` runs the whole `ci,default` tier on `main` every night (09:23 UTC), split across
parallel runners so each fits comfortably inside a job's time limit. One server would take most of an hour.

1. **Plan.** If `main`'s gate tree (below) already had a green night, the run stops here: nothing new to test.
   Otherwise `gate.py plan` shares the tier's classes among four runners, longest first to the least loaded, weighing
   each class by what it cost in the last full runs on record (or by its test count, before there are any). The same
   classes and costs always give the same split.
2. **Tier.** Each runner runs its share with `gate.py run`. Job 0 is given "every class EXCEPT the other jobs'"
   (`-Pgatenotclasses`), so a class the plan has never heard of (a GameTest added since the scan) still runs.
3. **Verdict.** `gate.py merge` reads every job's result and proves completeness: every class of the tier ran, each
   with as many tests as its source declares (`gatelib.scan_tier` counts the `@GameTest` methods of every
   `@GameTestHolder` class, comments and strings ignored), and no test ran twice. A job that left no result, a class
   that ran short, a test that failed twice: red. The report is the run's summary page.

**Where to look:** the run's summary page (Actions → Nightly), the `nightly-red` issue when it is red, and the run
history below. Run it by hand from Actions → Nightly → Run workflow (`force` runs even when nothing changed; `jobs`
changes the split).

A pull request that changes `nightly.yml` or `tools/gate/` runs the same workflow as a dry run: the whole tier, its
verdict as the check, but no record and no issue.

### When a night is red

The verdict job opens ONE issue labelled `nightly-red` (it creates the label the first time), naming the commit, the
failing tests and the run. While the nights stay red it updates that issue's body to the latest report and comments
with the new commit; the next green night comments and closes it. Nothing else is needed: a red night is visible
without anyone looking at Actions.

A failure that passed alone is `flaky`, not red. A failure of a test on the flake register is `known-flaky`: reported,
not red, because its issue already says so.

## Which tree a result belongs to

A result belongs to the code it tested, not to a commit message. `gate.py tree [REV]` hashes the `(path, blob)` pairs
of every file Gate B can read: everything except `docs/`, `.github/`, `art/`, `src/test/`, `tools/client-validation/`
and the top-level `*.md` files (`gatelib.NOT_GATE_B`). It is a deny list on purpose: a path wrongly left in costs a
re-run, while an allow list that forgot one would let a stale green stand for code it never ran. A commit that changes
only docs has the same gate tree as its parent, so its night is skipped.

## Run history

Every nightly run is recorded as ONE file on the data-only `gate-data` branch:
`runs/<YYYY-MM>/<started>-<kind>-<commit>.json.gz`. One file a run means two workflows recording at once never touch
the same file; the loser of a push race rebases and pushes again. The branch is never merged anywhere and needs no pull
request: it is data the workflows write with their own `GITHUB_TOKEN`.

A record holds the commit, the gate tree, the run's URL, each job's counts, and every test: its `Class.method` name,
seconds, batch, outcome (`passed`, `flaky`, `failed`, `known-flaky`, `skipped`), error, and its retry if it had one.

Read it from any checkout of the branch:

```bash
git fetch origin gate-data && git worktree add /tmp/gate-data FETCH_HEAD
python3 tools/gate/gate.py report --data /tmp/gate-data
```

The report answers three questions without re-running anything:

- **Is this test flaky?** A test that passed AND failed at one gate tree is flaky, by definition: the code did not
  change, the verdict did. A `flaky` outcome is both at once (it failed, then passed alone). A failure after a code
  change is not a flake, however often the test failed before. The report marks each flaky test that is not on the
  register.
- **What does it cost?** The slowest classes, by the median seconds each held a server for over the full runs (tests
  of one batch run at once, so a class costs the sum over its batches of the slowest test in each).
- **How is it trending?** Tests failing more often, and tests more than half again slower, in the last seven runs
  against the seven before.

**A renamed test keeps its history:** add `"OldClass.oldMethod": "NewClass.newMethod"` (or a whole class,
`"OldGameTest": "NewGameTest"`) to `corpus/gate-renames.json` in the pull request that renames it.

## The flake register

`corpus/gate-flakes.json` is the short, committed list of tests known to be flaky, each with its issue:

```json
{"test": "SomeGameTest.someMethod", "issue": "#123", "seen": "passed and failed at <tree> (runs/2026-12/...)"}
```

- **Add a row only** after `gate.py report` shows the test passed AND failed at one gate tree (a clean commit, nothing
  changed between the two results), and with an issue for it in this repository.
- **Remove the row** in the pull request that fixes the test.
- A release run leaves the register's tests out (`gate.py run --flake-register corpus/gate-flakes.json`) and its record
  names them (`excluded`), so its green says exactly what it covered. The nightly still runs them, and reports a
  failure of one as `known-flaky` rather than red: that is how a fix is seen to hold.
- `test_gate.py` checks every row names a real GameTest class, an issue and where it was seen.
