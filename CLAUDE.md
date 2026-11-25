# Working on Moddy the Wish Watch

Guidance for coding agents (and people) contributing to this repository. Read it before changing code; the
[README](README.md) says what the mod is for players, and [`docs/EXTENDING.md`](docs/EXTENDING.md) is the reference for
building on it without changing its code.

## What this is

Moddy is a **single-player NeoForge mod for Minecraft 1.21.8**: a wish watch that makes what a player wishes for (a
creature, a building, an item, a picture, an event, a rule for the world) inside the game. Three tiers:

- **The Builder**: the player picks ready-made parts from button chests and tunes them. No AI, no network.
- **The Apprentice**: the player types a wish in their own words and a local, deterministic matcher (plus a small
  bundled sentence model) ranks Builder makes that fit. No AI service, no network.
- **The Magician** (optional, **off by default**): wishes are granted by a model the player chose and set up
  themselves, through Claude Code or Muse Code. While it is off, no AI program is started and no model is called.

Moddy is made for children. Everything a player reads or types goes through the safety layers described below.

## Target and versions

- **One target: Minecraft 1.21.8**, NeoForge and Java 21. The exact versions are pinned in `versions/1.21.8.properties`,
  `gradle.properties` and `build.gradle`; read them rather than trusting any number in prose.
- Spell data components, registry ids and command syntax **the 1.21.8 way**. An older spelling is refused by the game
  at load or at run time, often silently.
- Always use NeoForge APIs (`net.neoforged.*`, never `net.minecraftforge.*`) and the current Minecraft names
  (`ResourceLocation.fromNamespaceAndPath`/`parse`, never `new ResourceLocation(...)`).

## Layout

- `src/main/java/com/moddingfromamod/`
  - `ModdingFromAModMod.java`: the `@Mod` class. **Mod event bus** = registration and setup; **`NeoForge.EVENT_BUS`** =
    gameplay (commands, ticks, entity and player events). Client-only wiring is guarded by
    `FMLEnvironment.dist == Dist.CLIENT`.
  - `wish/` the wish object, its lifecycle and the player's commands; `wishbuilder/` and `builder/` the Builder;
    `apprentice/` the typed-wish matcher; `agent/`, `worker/`, `marshal/` the Magician's agent and its workers;
    `tools/` the tools the agent calls; `safety/` the child-safety layers; `config/` the settings; `ui/` the menu kit;
    `plugin/` how plug-ins are found; `world/` world modules; `datapack/` the bundled CBScript compiler's wrapper;
    `provenance/` what each wish records about how it was made.
  - `example/ExampleLogic.java`: **the pure-helper pattern** (below).
  - `test/`, `testing/`: the GameTests and their harness. They never ship in the jar (the build checks the finished
    jar for them).
- `src/test/java/...`: Gate A, plain JUnit.
- `src/main/resources/`: `META-INF/neoforge.mods.toml` (templated from `gradle.properties`), `assets/`, `data/` (incl.
  the `empty_test` GameTest structure), `lessons/` (what the Magician's agent reads before it builds each kind of
  thing), the starter library and the Builder's presets.
- `corpus/`: small public data sets the tests read.
- `tools/`: `store-release.sh` (builds the release jar), `precompile-ready-mades.sh` (rebuilds the precompiled
  Builder and Apprentice powers), `fetch-apprentice-model.sh` (the Apprentice's pinned sentence
  model), `secret-scan.py` (refuses a committed credential), `leak-scan.py`, `licence-scan.py` (refuses third-party
  code off the licence allow-list; its rules in `licence-rules.json`), and their tests.
- `art/`: source art for icons.

## Build and test

```bash
./gradlew build                                     # Gate A: compile, unit tests, the jar in build/libs/
./gradlew runGameTestServer -Pshard=ci              # Gate B, the quick screen every pull request runs
./gradlew runGameTestServer -Pshard=ci,default      # Gate B, the full default tier (the nightly runs it on main)
python3 tools/gate/gate.py run --shard ci --out /tmp/gt   # Gate B as CI runs it: counted, a failure retried alone
./gradlew runClient                                 # play it in a development client
```

- **Gate A** is `./gradlew build`: compile, every unit test (`gate-a.gradle` configures the `test` task: one retry of a
  failed test, recorded, and the ambient API keys removed so a test that needs one fails here rather than in CI), and
  the licence checks (`licenceRegister`: `tools/licence-scan.py --tree`; `licenceCheck`: every library the jar nests).
- **Gate B** boots a headless dedicated server and runs every `@GameTest` in the chosen shards. `-Pshard` takes a
  comma-separated list (`ci`, `default`, or `all`); `-Pgametests=ClassA,ClassB` runs only those classes
  (`ClassA.method` one test), `-Pgatenotclasses=…` every class but those. Gate B needs
  Python 3 with `ply` (`pip install ply`) for the datapack compiler; without it every datapack test fails with a
  message that blames the catalogue, not the missing package.
- **Gate C** is a real client. Anything a player can see or hear needs it; a clean compile and green headless tests do
  not prove a texture, a sound, a menu or a creative tab. On Linux without a display, `xvfb-run` works with software
  rendering.
- `store_jar=true` in `gradle.properties` makes `./gradlew build` produce the release jar (versioned
  `<mod_version>+mc<minecraft_version>`, the Apprentice model bundled, fetched by `tools/fetch-apprentice-model.sh`).
  `-Pstore_jar=false` builds a plain jar without the 86 MiB model download.
- Where a new GameTest goes is checked by `GateShardsTest` (Gate A): it fails by file name and says which annotation
  to use. A test in no shard would never run while the gate stays green.

**CI** (`.github/workflows/ci.yml`): the leak, secret and licence scans, Gate A and Gate B's `ci` shard on every pull request; a pull request
merges on those three green. A pull request that only touches `tools/client-validation/` or `.github/port-publish/` (nothing a
build or test reads) runs the scan alone; Gate A and Gate B show as skipped. Docs are not in that list: tests read them.

**Nightly** (`.github/workflows/nightly.yml`, [`docs/GATE_B.md`](docs/GATE_B.md)): the whole `ci,default` tier on
`main` every night, split across four runners by `tools/gate/gate.py`, skipped when `main`'s gate tree already had a
green night. Each job checks its server ran every test it registered and retries a failed test once, by itself
(`flaky` if it then passes); the verdict checks every class of the tier ran with as many tests as its source declares.
A red night opens or updates the one issue labelled `nightly-red`; a green night closes it. Each run is recorded, one
file, on the data-only `gate-data` branch; `python3 tools/gate/gate.py report --data <checkout>` prints the flaky tests
(passed and failed at one tree), the slowest classes and the trend. Known-flaky tests are listed, each with its issue, in
`corpus/gate-flakes.json`.

**Releases** (`.github/workflows/port-ci.yml`, generated by the Port CI kit; do not edit it by hand). The release line
is the `neoforge-1.21.8` branch. A tag `moddingfromamod-<version>-mc1.21.8` (or running the workflow with
`release = true`) builds, gates and cuts a GitHub **pre-release** with the jar. A person plays the pre-release; then
**promote** (`promote = <tag>`, `played_by = <login>`) makes it a full Release, and **publish** uploads a promoted
release to Modrinth and CurseForge (`.github/port-publish.json`). Nothing is published that a person has not played.
`tools/store-release.sh <version>` builds and checks the same jar locally.

**`main` is the upstream of a weekly version ladder** ([`docs/RELEASING.md`](docs/RELEASING.md)).
- **Every integrate is a new version.** Whoever takes `main` into `neoforge-1.21.8` (a worker mid-week, or the weekly
  round) does it the same way:
  1. If `main` names no version past the newest `moddingfromamod-*-mc1.21.8` release, open a `Version X.Y.Z: …` PR on
     `main` that changes only `mod_version`, to the next patch.
  2. Merge `main` into `neoforge-1.21.8` with a merge commit titled `Merge main into neoforge-1.21.8: X.Y.Z, …`.
  3. Port CI releases 1.21.8.

  A mid-week integrate starts no cascade, so 1.21.8 may lead the other version branches.
- **The weekly round** (MMVU keep-current) runs the plan issue on Tuesday (Actions, no model) and the round on
  Wednesday morning Pacific (a Claude scheduled task).
  - The plan offers "take `main`" as a box, ticked by default; the round then bumps the version and integrates the
    same way, by itself.
  - Either way, the round carries `neoforge-1.21.8` up the other version branches and pre-releases them.
- **A hotfix:** a pair of pull requests, `main` first, then `neoforge-1.21.8`, with the version bump.
- **Promotion:** only the owner's `/promote`, after playing the build.

Never cascade by hand. The plan workflow and the round task are installed only by MMVU's Setup, never edited by hand.

**A fix lands on `main` first.** `main` holds every change; `neoforge-1.21.8` is `main` at a release point, and a
higher `neoforge-<mc>` branch holds only its port's own changes plus `main` merged upward. A hotfix the release line
needs before the next release point is made on `main` and **cherry-picked down** in the same change, never committed to
the release line alone. `.github/workflows/release-line.yml` refuses a push or pull request that brings
`neoforge-1.21.8` a commit with no equivalent on `main` (each commit not reachable from `main` is matched by patch-id against `main`, so a merge of `main` or a
cherry-pick passes); the one exception is Port CI's own install-manifest refresh.

## Building on it: extension points

Read [`docs/EXTENDING.md`](docs/EXTENDING.md). In short: a plug-in is a `com.moddingfromamod.plugin.ModdyPlugin`
listed in `META-INF/services/` (this repo ships that list empty), and it fills documented hooks: `WishCharge` (an
economy), `WorldModuleType` (a custom world), `WorldRuleType` (a rule type of its own in a world's rules file), `ChildBranch` (a new `/moddy` verb) and `SettingsOverride` (a server's
locked settings). Hooks marked "An internal seam" are not for building on. A new hook the core reads gets its section
or internal-seam row in `docs/EXTENDING.md` in the same change, and so does a new setting or server-file key:
`ExtensionPointDocTest` (Gate A) fails one without it.

## Coding rules

- **Put gameplay logic in Minecraft-free helper classes and unit-test them.** If a decision can be made without
  importing Minecraft (math, geometry, scaling, scoring, state machines, timing, parsing), it belongs in a pure class
  with a Gate A test; the Minecraft-facing code only carries out the result. `example/ExampleLogic.java` is the model.
- **Register on the right bus.** Content and setup go on the mod event bus through `DeferredRegister`; commands and
  gameplay handlers on `NeoForge.EVENT_BUS`. The wrong bus compiles and silently does nothing.
- **Client-only classes must never load on a dedicated server**: guard them with `Dist.CLIENT`.
- **A stock client must work.** The mod runs on the integrated server of a player's game; nothing it shows may need a
  client-side mod beyond itself, and the core adapts to a client only through the documented hooks.
- **A player sees two commands: `/wish` and `/moddy`. Never add a top-level command.** A player's verb is a branch of
  `/moddy` (a plug-in adds one through `ChildBranch`); `WishChatGameTest` lists every root a permission-0 player can
  use and fails on anything else. Every new command, ours or a new Minecraft version's, also needs a row in the agent's
  `CommandGuard` (may the agent's `run_command` use it?); `CommandPolicyGameTest` fails one without.
- **Every menu is a PICK (`ui/PickPage`, a button chest) or a TUNE (`ui/TuneDialog`, one notice dialog), opened through
  `ui/MenuKit`.** `MenuMappingTest` fails a dialog or a clickable chest built any other way.
- **The typed-wish tier is the Magician.** Player-facing text says Apprentice and Magician, never "Wish Wizard"
  (`MagicianNameTest`). A wizard a player wishes for is content and keeps its name.
- **A Builder or Apprentice make is local only: no model and no web call.** Every make, the Apprentice's reading of
  typed words and the re-wish reader run inside `marshal/LocalOnly`, which refuses and records any such call (only
  the hand-off to the Magician steps outside); `BuilderMakeGameTest` and `ApprenticeGameTest` require none. Code that opens an HTTP connection, starts a process or calls a model asks `LocalOnly.refuse(…)` first or
  says in `LocalOnlyCoverageTest` why it can never run inside a make (Gate A fails it otherwise).
- **Mechanisms are general.** Never special-case a particular wish, character or noun in engine code to make one
  example pass; fix the mechanism or the lesson so every similar wish gets it.
- **A wish appears where it was aimed, locked when it was made**, never recomputed from where the player stands at
  build time. A place the player named ("on my roof") goes through the one `at: "<thing>:<spot>"` grammar
  (`place/At`, `place/PlaceResolver`, `place/PlaceFinder`); a new placing tool takes `At.describe` in its schema and
  resolves through `PlaceFinder`, never a second grammar or coordinates the agent worked out.
- **Every persisted wish records its provenance**: a new wish kind that persists an artifact also publishes its
  manifest (`ProvenancePublisher.publish`) and is added to `ProvenanceContract.PUBLISHING_KINDS`.
- **Agent tool schemas** have no root `anyOf`/`oneOf` (`ToolSchemaRootTest`): some CLIs silently drop such a tool.
- **Every mixin has a row in `docs/MIXINS.md`** (target, injection, purpose, where it is needed; `MixinTableTest`
  fails a mixin without one, and a row without a mixin), because the mod runs beside the player's other mods. **No
  `@Redirect` and no `@Overwrite`**: wrap a call with `@WrapOperation`, or use a narrow `@Inject`/`@Modify*`. A mixin
  only GameTests need goes in `testing/mixin/` and `moddingfromamod.gametest.mixins.json`, which the dev runs register
  and the jar never carries.
- **Never take a compile-time dependency on an optional mod.** Declare it optional, resolve its content by
  `ResourceLocation` at run time, gate it on `ModList.get().isLoaded(...)`, and keep it in its own package.
- **Don't keep an old code path "for back-compat"** unless someone outside the repo holds the old shape and cannot be
  changed (a player's save, a stock client). Otherwise migrate and delete. A stored shape that changes is versioned,
  and a store refuses what it cannot read rather than returning empty.

## Running it with the Magician

The Magician is off in every fresh install and every development run. To try it from this repo (`./gradlew runClient`,
or the jar from `build/libs/` in a NeoForge 1.21.8 install):

1. Install **Claude Code** or **Muse Code** and sign in to your subscription (or set `ANTHROPIC_API_KEY` /
   `MUSE_API_KEY` in the environment Minecraft starts from; nothing else reads a key).
2. In game, open the wish watch and press **✪ Turn on the Magician** (the host only; it opens `/moddy magician`).
3. Press the button for your subscription: **Use my Claude subscription**, **Use my Muse Code subscription**, or **Use
   API keys** (shown only when a key is set). One press sets the four model choices, checks the program is installed
   and signed in, runs the Magician check (every safety grader, one forced refusal and one small test wish, about 3
   minutes) and turns the Magician on, with progress on the action bar and one result line; a failure says the one
   thing to do. The setup also checks for a working **Python 3** (`datapack/PythonCheck`: the Windows Store stub and
   Python 2 are recognised, then a tiny compile proves the bundled compiler runs); Python missing never blocks turning
   the Magician on, it adds one result line saying what to install, and a power wished without it gets one clear tool
   error (`PythonCheck.agentMessage`). **Choose models myself** keeps the per-choice pages (each model, how it is
   paid for, fallbacks); a change there re-checks the programs at once and says the Magician is off until **Turn on
   (about 3 minutes)** passes.

Where it lives: the choices in `~/.moddingfromamod/settings.json`, the passed check in `magician-check.json` beside it,
tied to the exact four choices. A restart needs no new check; changing any choice turns the Magician off until the check
passes again. The code is `config/MagicianSetup` (the pages and the flow), `config/MagicianPresets` (the one-button
choices and the words of each failure, pure) and `config/MagicianCheck` (the check).

For development runs:

- **A test run keeps settings in memory and never starts the agent**: the automated client test
  (`-Dmoddingfromamod.boottest=true`), the GameTest server and JUnit. `-Dmoddingfromamod.forceRealRun=true` makes a run
  count as real again; `-Dmoddingfromamod.agent=on` forces the wish pool for your own run (`-Ptestprops="agent=on"` on
  a Gradle run). Neither reaches a player.
- **Point a dev run at its own home** so it never touches your own settings or check: `-Dmoddingfromamod.home=<dir>`
  (`./gradlew runClient -Ptestprops="home=/tmp/moddy-dev"`).
- With the Magician off nothing looks for a program until the host presses a button in the setup
  (`MagicianOffTest`, `MagicianOneButtonGameTest`); keep it that way.

## Safety

There is no switch that turns the safety layer off, for a player or a server file. It has four layers, reached from a
small number of methods; grep for `SafetyLexicon.shared()` and `Safety*.sweep|decide` outside `safety/` before you
trust a claim that something is or is not covered:

- **input**: the player's wish text (`WishService.submit`: the lexicon at once, the model grade before a worker takes
  the wish; a re-aimed wish is graded too);
- **plan**: the agent's plan (`SafetyPlanGate`, on `plan_wish`);
- **text**: every child-visible argument of an agent tool call (`SafetyTextSweep`);
- **datapack**: the words a compiled datapack will say at run time (`SafetyDatapackSweep`, in `CbScriptCompiler`).

A new tool argument a player will read, or a new way for words to enter a wish, is wired to one of these in the same
change.

## Models (Magician mode only)

- The player picks models from one table, `config/SupportedModels`, in the Magician setup (the watch's ✪ tile, or
  `/moddy magician`); nothing else can be picked. A dedicated server sets the same picks in `config/moddingfromamod-server.toml`.
- **Every helper call follows the player's helper pick** (`config/HelperRoute`): safety grading, routing, lookups and
  the picture describer call only the provider, and only the way of paying, the player chose (`magician.cheapModel`,
  `cheapMode`, `cheapFallback`). A job never reaches a provider because its key happens to be in the environment. A new
  helper job is a `HelperRoute.Job` and asks the route; it never decides for itself.
- **API keys come only from environment variables** (`ANTHROPIC_API_KEY`, `MUSE_API_KEY`); never a setting, a file or a
  log line.
- Don't raise the model's effort, add a model, or change a default model without discussing it in an issue first:
  each one changes what a player pays.
- With Magician off, no CLI probe, no worker and no model call (`MagicianOffTest`).

## Traps that cost contributors time

- **Wait on the log, not the process.** `runGameTestServer` exits 0 even when it never ran the tests. Wait for the
  line `[0-9]+ required tests (passed|failed)` and do not anchor on `All` (only the passing line has it). A
  `pgrep -f pattern` wait loop matches its own shell and never ends.
- **A GameTest server ticks as fast as it can.** Ticks are not 50 ms: 120 ticks can pass in a fifth of a second. Put
  timing that a person experiences in real seconds in a unit test, and let the GameTest prove the wiring.
- **GameTests in the same batch run at the same time and share every static.** Hand a component its clock and its
  sink rather than asserting on a singleton. Batches themselves run one after another.
- **Prefer a plain daemon thread with its own try/catch** for anything whose job is to speak when nothing else does: a
  `ScheduledExecutorService` task that throws dies silently inside its `Future`.
- **A deterministic test failure against source that visibly has the fix is usually build state.** Rule it out first:
  `rm -rf build/classes && ./gradlew cleanTest test --no-build-cache`, and `javap -p -c` the class to see what really
  ran.
- **Don't compile while a gate is running** (it rewrites `build/resources` under the running server), and **don't edit
  a shell script while it runs** (bash reads it by byte offset).
- **The CBScript compiler ships bundled** in the jar (`tools/cbscript.zip`); a version-specific id or spelling belongs
  in the compiler, not in a lesson.
  Rebuild it with `./tools/refresh-cbscript-bundle.sh --ref <branch|commit>`, which also writes `tools/cbscript.SOURCE`.
- **Builder and Apprentice powers are precompiled** (`datapack/PrecompiledPacks`, `datapack/ReadyMadeSkeleton`): every
  template is compiled at build time with sentinels where a player's values go, and Java puts the values back, so no
  player needs Python for them. Changed a template in `effects/` or `items/`, a catalogue, the bundled compiler, or how
  a tool renders a ready-made? Run `./tools/precompile-ready-mades.sh` and commit `src/main/resources/precompiled/`:
  `PrecompiledFingerprintTest` (Gate A) and `ReadyMadePrecompileGameTest` (Gate B, every Builder power made with
  Python off) fail until you do. A value the compiler would rewrite (a selector argument, an old component spelling)
  stays literal in the skeleton, so it is a different precompiled pack, never a wrong one (`PrecompiledEquivalenceTest`).
- **A rule tested only where it is defined is not tested where it runs.** Drive the real entry point (the tool the
  agent calls, the real command), and check that a guard's enabling condition is actually written by shipped code.
- **When Minecraft or another mod silently does nothing, read the bytecode** (`javap`, a decompiler) before a third
  hypothesis.
- **A camera bug does not throw**: a screenshot of the wrong thing passes. Look at images you capture, and hash them.
- **A green number can be a smaller number.** A test that skips, lands in no shard, or never ran reads as a pass. Check
  the count.

## Pull requests

**A pull request is the only way a change reaches `main`**, the maintainers' own included: fork it (or branch, with
write access), open a pull request against `main`. CI runs the leak scan, Gate A and Gate B's `ci` shard on it, and a
maintainer reviews and merges it. Nobody pushes to `main` directly, and nothing arrives from anywhere else.

- Run Gate A and the Gate B shard that covers what you changed before opening a PR; describe what you ran.
- **Link only issues and pull requests in this repository**: a `#` number in a commit, a comment or a doc means this
  repository's tracker.
- **Sync commits are history.** `main` holds older commits titled "Moddy the Wish Watch: sync", with a `Sync-from:`
  trailer: the project's changes from before every change came through a pull request. No new ones are made; read them
  as ordinary commits.
- Keep a change to one concern. A new wish kind, tool, setting or hook brings its tests and its doc rows with it.
- Code is MPL-2.0 and art and presets CC BY 4.0 ([LICENSE](LICENSE), [LICENSE-ASSETS.md](LICENSE-ASSETS.md)). "Moddy"
  is a trademark; a fork's name follows [TRADEMARKS.md](TRADEMARKS.md).
- **Never commit a secret, an API key, or anything a player typed.** Refer to a key only by its variable
  (`$ANTHROPIC_API_KEY`), never its value. `python3 tools/secret-scan.py --tree` (or `--staged`) checks for token shapes
  and for the live values of the keys in your environment, and never prints a value; CI runs it too. A test that needs a
  fake token builds it by concatenation so the file is not a hit. If a real key ever reaches a commit, rotate it first.
  Never bypass a refusal with `--no-verify`.
- **Third-party code and text.** Nothing written by someone else is copied, ported, translated or closely paraphrased
  into this repository unless its licence is on the allow-list: MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, MPL-2.0,
  ISC, Zlib, the Unlicense, CC0 / public domain; CC BY 4.0 for art and presets only. Anything else stops the work and
  goes to the owner, with the source and its licence, **before** a commit: a copyleft licence, a share-alike,
  non-commercial or no-derivatives Creative Commons licence, a font licence, "all rights reserved", no licence at all,
  or code whose origin you do not know (a Stack Overflow answer is share-alike). Reading copyleft code to understand a
  problem is fine; writing ours from it is a port. **Pushing a branch to this public repository publishes it**, draft
  or not. What we keep gets a row in [THIRD_PARTY.md](THIRD_PARTY.md) (source, licence, why) in the same pull request,
  and its licence text travels with it (`third_party/`, and a bundled component's row in
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)); a licence held for the owner's decision reads `PENDING #<issue>`.
  `tools/licence-scan.py` checks this at commit and push (`./tools/public-hooks/install.sh`), in CI (the tree and
  every commit's added lines), and in Gate A (`licenceRegister`, and `licenceCheck` over every library the jar nests).
  A line that only names a licence carries `licence-scan: allow <reason>`; a file that discusses licences goes in the
  `exempt` list of `tools/licence-rules.json` with its reason. Widening the allow-list is the owner's decision.
