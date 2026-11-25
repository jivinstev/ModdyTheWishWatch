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

## The coverage map

For every GameTest class of the tier, which production METHODS it executes. Targeted Gate B reads it to run only the
GameTests a change can reach (below). `.github/workflows/covmap.yml` builds it on GitHub's runners.

**How it is built.** A mapping run is Gate B with the JaCoCo agent on (`-Pcovagent`, `-Pcovmap`; `./gradlew
covmapTools` fetches the agent and core jars into `build/covmap-tools/`). `testing/gt/CoverageMap` then gives every
class batches of its own, so no two classes run at once, removes what the previous class left in the world, and dumps
the agent's data each time the running class changes. Two things make the result the same every run:

- **The batches run in a fixed order.** The game groups tests into batches through a hash map keyed by identity, so
  the order of batches differs in every JVM. On a mapping run only, `testing/mixin/GameTestBatchOrderMixin` sorts them
  by environment name: the idle window first, then each class's batches together, class after class. (Measured before
  it: two maps of one commit disagreed for 264 of 360 classes, because what a class left running landed in a
  different neighbour each run.)
- **Each class has a settle window.** After its last batch, a synthetic test of its own waits 40 ticks and 1.5 s, so
  what the class left running (a listener it registered, a make on a worker thread, a poll it started) counts as its
  own, not as whichever class runs next. `tools/gate/CovmapAnalyze.java` (run as a single-file program
with the JaCoCo core jars, never compiled into the mod) turns each job's dumps into the methods each class executed,
and a table of every method's source file and line ranges. `gate.py covmap-merge` builds the map from the jobs.

- **A method's id** is `package/Class#name`, with what a recompile renumbers folded away: a lambda counts as the method
  that holds it, an anonymous class is `Outer$anon`, overloads share a name. Folding only makes a method bigger, so a
  change can only select more.
- **Ambient methods** are what runs with no test of ours running: start-up, a 400-tick idle window, shutdown. They are
  kept apart from every class's set; a change to one of them runs the whole tier.
- **Weekly, FULL**: every class, mapped twice by two replicas of the same split (the determinism check, below).
  **Daily, PARTIAL**: only the classes the map has not placed, and those whose own source file changed since the map
  recorded it, merged into the current map. Skipped when there are none. Six runners share a run.
- **Its pass/fail is not a Gate B result.** Coverage slows the server and the batches differ, so a test that fails
  under coverage still maps; what counts is that every class ran and was placed. The nightly full tier is the verdict.
- **A class the run DROPPED** (the previous map placed it, this one did not) is reported in the run's summary and in
  the `covmap-red` issue, never silently forgotten. It is unplaced until a run places it again, and an unplaced class
  runs on every pull request.
- **A failed refresh** keeps the previous map and opens or updates the one issue labelled `covmap-red`; the next good
  refresh closes it.

**Where it lives, and why there.** On the data-only branch `gate-map`: one commit holding `map.json.gz`,
`costs.json` and the last run's `report.md`, replaced (force-pushed) by every refresh. Every reader fetches it by name,
`git fetch --depth=1 origin gate-map`, and nothing waits on a pull request. It is not on `gate-data`, whose records
are kept forever, because a map is replaced daily: a year of maps there would be most of every clone. It is not a
release asset, because it would sit among the mod's releases a player browses, and Port CI reads those.

**What it records.** The commit and gate tree it was built at, the method table, each production file's blob at that
commit (line ranges hold only for a file whose blob is still that one), the ambient set, and each class's methods with
the commit that class was last mapped at. Costs are kept beside it (`costs.json`): the seconds each class held a
server for under coverage, the rounded median of its last five measurements (wall clock varies by runner; method sets
do not).

**How stale it can be.** A class's methods are at most a week old (the weekly full run), or a day for a class whose own
test file changed (the daily partial run). A production file that changed since the map recorded it is taken whole: its
line numbers no longer hold, so every class that reached any of its methods is selected. A map older than ten days
selects the whole tier. A missing map selects the whole tier. Every way a map can be wrong or old makes a selection
LARGER, never blind; and the nightly full tier still runs everything.

**Determinism.** For one commit the method sets are deterministic, but for classes whose path depends on timing or
randomness. The weekly run maps every share twice and `gate.py covmap-compare` requires the two maps to agree on every
class's methods and on the ambient set; costs are not compared. The classes allowed to differ are listed, with what was
measured, in `corpus/gate-covmap-unstable.json`; any other difference fails the refresh and opens `covmap-red`.

```bash
./gradlew covmapTools
python3 tools/gate/gate.py covmap-plan --mode full --jobs 1 --out /tmp/cm/plan.json
python3 tools/gate/gate.py run --plan /tmp/cm/plan.json --job 0 --label job-0-r0 --kind covmap \
  --covmap-tools build/covmap-tools --out /tmp/cm/j0
python3 tools/gate/gate.py covmap-merge --plan /tmp/cm/plan.json --out /tmp/cm/map /tmp/cm/j0
```

## Targeted Gate B

`gate.py select --base origin/main [--head REV] --map map.json.gz` lists the GameTest classes a change can reach,
and why, one line a class (`tools/gate/selection.py`, pure: changed files and hunks, the map and the tier in, a
selection out; `test_gate.py` pins every rule). The change is what the head changed since its merge base with the base
branch, so `main`'s commits merged into a branch are not its changes, and a file both sides changed is.

What it selects:

- **the `ci` shard**, always: nothing runs less than it did before;
- **every class the map has not placed** (a new GameTest, one a mapping run could not place);
- **a changed GameTest class** itself, and **the GameTest classes that name a changed class** (its simple name as a
  word, in their code or string literals, never their comments);
- **every class the map saw execute a changed method.** A hunk inside a method's lines is that method. A hunk outside
  every method (an import, a field, a signature, a new method) is the whole file: every class that reached any of its
  methods. A changed compile-time constant (`static final … =`) also reaches every file that names its class, because
  the constant is copied into them and they never execute its file. A file that changed between the map's commit and
  the merge base has its hunks carried back through that drift (`git diff -U0 <map commit> <merge base>`): a line the
  drift left alone moves by it, a line the drift changed stands for the whole range it came from;
- **a new class** is reached only through code that names it, which changed in the same diff, so that code's hunks
  carry the selection; one the game finds by itself (`@EventBusSubscriber`, `@Mod`, `@Mixin`) runs the whole tier,
  unless only a client finds it (`Dist.CLIENT`);
- **a data file** in `corpus/` or a client asset in `src/main/resources/assets/` is read by the sources that name it
  (its file name): the GameTests among them, and what reaches the others.

**The whole tier** for anything the map cannot see: build configuration (`build.gradle`, `gradle.properties` but for
a `mod_version`-only bump, `versions/`, `gradle/`), mixins, resources the game reads by path (`data/`, `lessons/`, the
starter library, `precompiled/`), the bundled datapack compiler, the GameTest harness (`testing/gt/`, `testing/mixin/`,
the filter, the shards, the timings, the watchdog: what decides which tests run and how), a change to code that runs
with no test running (start-up, the idle tick, shutdown; a static initialiser instead reaches its file and every file
naming its class), a path with no rule, and no map or a map older than ten days.

**Nothing beyond `ci`** for a path no GameTest reads: `docs/`, `.github/`, `art/`, `src/test/` (Gate A), `tools/`
but the compiler bundle, `third_party/`'s licence texts, the gate tools' own `corpus/gate-*` files, the port ledger
`.port/`, and the top-level `*.md` files.

**Measured** (`gate.py backtest`, every pull request merged since the move, 65, against a full map of the tier):
see the pull request that switched CI to it for the numbers, and re-run it with
`gate.py backtest --prs <list> --map <map>` to see them for any set of changes.
