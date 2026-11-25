# Redstone wishes: the plan

**Status: in progress on the long-lived `redstone-wishes` branch. Nothing here is on `main` yet.** Every push to that
branch builds a test jar (`.github/workflows/redstone-jar.yml`; download it from the run's Artifacts). The branch merges
to `main` as one pull request once the bar in §1 is met.

## 1. The bar

A wish engine that only sort of works is worse than none, so redstone ships only when it is measurably dependable:

- **Hit rate.** At least **60%** of an evaluation set of realistic wishes pass the rubric (§5). The set is written the
  way children ask, hard wishes included. It is measured first on full-sentence wishes, then again on short, misspelt
  proxies of the same wishes. Both must reach the bar.
- **No accidental damage.** A wish that works but breaks something else is a failure, not a pass (§5, side effects).
- **Rung 2 at least** (§6): the door, lamp, trap and machine mechanisms an ordinary wish asks for, not only one demo.
- **Proof a person can trust:** each milestone comes with films of the wish working in a real client, and a jar to try.

The evaluation set and its graded runs are kept outside this repository, because they include words a player typed.
This repository holds the mechanism, its tests and this plan.

## 2. What the agent decides, and what it does not

The agent decides **what** (which mechanism, which controls, roughly how big, whether hidden) and **where** (the
`at: "<thing>:<spot>"` places, the one placement grammar). Everything geometric is the tool's job, made by pure,
unit-tested code: block positions, facings, wire routes, repeater spacing, timing, clearances and casing. A model left to
reason about quasi-connectivity or which way a repeater points gets it wrong often enough to sink the bar, so it is never
asked to.

One tool, `build_mechanism`:
- **mechanism:** a closed list, starting with `piston_door`, `iron_door`, `gate`, `lamps`, `piston_wall`, `hidden_stairs`,
  `trap_floor`, `dispensers`, `note_blocks`, `fountain`.
- **size**, where it applies.
- **at:** where the mechanism goes.
- **controls:** a list of `{kind: lever|button|pressure_plate|tripwire|daylight|target|trapped_chest, at}`.
- **hidden:** boolean, default true.
- **options:** a few per mechanism (items to load into dispensers, notes to play).

It answers with what it built and what it proved (§4). New mechanisms join the list; the tool never takes free-form
block lists.

## 3. Geometry rules the planner enforces

Each rule is a unit test in the pure planner (`redstone/`), stated in Minecraft terms:

- **Signal strength.** Dust carries 15 blocks. The planner puts a repeater at most every 14 dust and never relies on
  strength 1.
- **Direction.** A repeater or comparator faces its output. A torch inverts and burns out above 8 toggles in 60 game
  ticks, so the planner never uses a torch in a clock.
- **Support.** Dust and repeaters sit on a full, opaque, solid block (not glass, leaves, slabs on the bottom half, or
  anything that can be pushed away). Water and lava must never reach dust: the planner keeps fluids out of the wire's
  footprint and its neighbours.
- **Isolation.** Wire runs one block apart from other redstone, other mechanisms' blocks and the player's own redstone,
  so nothing powers anything it shouldn't (cross-talk).
- **Climbing.** Vertical runs use a dust staircase on solid steps where there is room, a torch tower (alternating torch
  and block) or, where only one column fits, stacked observers. Each choice is fixed by the space, not by the model.
- **Pistons.** A piston pushes at most 12 blocks. It cannot push obsidian, bedrock, block entities (chests, furnaces,
  beds…) or anything the player asked to keep. A sticky piston drops its block on a 1-tick pulse, so door circuits use a
  repeater of delay ≥ 2. A piston is also powered from the position above it (quasi-connectivity), so the planner keeps
  stray power away from that position and never depends on it.
- **Door shape.** Opposing sticky-piston columns, 2 tall (3 for a 3×3, with a sequencer), the measured norm (66% of real
  piston doors close from both sides). Upper pistons are powered from a strongly-powered block or a repeater behind them,
  never by quasi-connectivity alone.
- **Space and casing.** Mechanism blocks behind a wall are cased in the wall's own material, so a hidden mechanism reads as
  a slightly thicker wall or pillar. The tool reports every block it replaced or added outside the opening.
- **Reach and loading.** Controls and mechanism stay within one loaded area (about 100 blocks). Longer runs are refused
  with a reason, not built half-loaded.
- **Clocks.** Any always-on clock counts against a per-world cap (value to be set by measuring tick cost, §8). Over the
  cap, the mechanism is built switched off and the tool says which cap stopped it.

## 4. Build, then prove

The tool:
1. resolves the places (`PlaceFinder`);
2. plans in pure code;
3. checks the plan against the world (protected blocks, fluids, block entities, room);
4. places every block through the block journal, so the whole thing can be undone;
5. **proves it**: fires each control with a real block update (never by writing a powered state, which powers nothing),
   lets the game tick, and checks each output reached its expected state. It then fires the control back and checks the
   outputs returned, twice over.

A failed proof is reported to the agent with the step that failed. The tool takes the mechanism back down, so a broken
machine never stays in the world.

## 5. The rubric each wish result is graded on

The grader runs on the saved world after the grant. It is independent of the tool's own proof: it reloads, fires the
controls itself, and compares the world with a snapshot taken before the wish.

**Function (all four needed to pass):**

| | check | how it is measured |
|---|---|---|
| F1 | built | the tool succeeded, and the mechanism's blocks are present |
| F2 | works | firing each control moves every output to the wished state, and firing it back restores the rest state; 3 cycles |
| F3 | is what was wished | the named place, the control kinds and their places, the size, the count, "hidden" if asked, the items loaded |
| F4 | usable | a player can reach every control and pass through every opened way (walk-through check) |

**Side effects (any HARD one fails the wish):**

| | check | severity |
|---|---|---|
| S1 | player blocks destroyed or replaced outside the reported footprint | HARD above 0 non-natural blocks |
| S2 | the building's own function broken: a doorway or room no longer reachable, a floor holed under a path, windows lost | HARD |
| S3 | the environment: fire spread, fluid flowing past where intended, TNT or traps going off by themselves, mobs or animals killed that the wish didn't target | HARD |
| S4 | cost: an always-on clock not asked for, or tick time over budget | HARD if not asked for |
| S5 | litter: dropped items, leftover piston heads or moving blocks, visible wire when "hidden" was asked | SOFT, counted |
| S6 | danger to the player: trapped, suffocating, a pit in a path that isn't a wished trap | HARD |

**Verdict:** PASS = F1–F4 and no HARD side effect. PARTIAL = built and works but F3/F4/SOFT issues. FAIL = anything
else. The hit rate counts PASS only.

## 6. Rungs

| rung | mechanisms and controls | exit |
|---|---|---|
| R0 | 2×2 flush piston door in an existing wall; levers in front and inside; hidden wire | GameTests for each placement case, plus one real grant of a door wish, filmed |
| R1 | iron door, gates, lamps (switches and daylight), piston wall (bookshelf door), trap floor, arrow and firework dispensers, note blocks (piano, doorbell), fountain, water lift | a GameTest per mechanism and control, and the evaluation set's R1 wishes measured |
| R2 | 3×3 door with a sequencer, hidden stairs, piston bridge, minecart station, magma grinder, sugar cane farm, furnace array, alarm (clock, cap), short note songs, TNT trap and cannon | **≥ 60% on the full-sentence set** |
| R2b | short, misspelt proxies of the same wishes; routing between redstone and the other wish kinds | ≥ 60% on proxies |
| R3 | combination lock, item sorter, slot machine, chicken cooker, roller coaster, datapack-assisted parts (who may open, scores) | measured |
| later | multi-stop lift, mob and iron farms, flying machines, computers | kept on the list |

## 7. Evidence of every run

- Each real grant is archived with its tool calls, its proof report and the grader's verdict.
- Each milestone gets films from a real client under Xvfb (`tools/record-client.sh`): the wish being typed, the
  mechanism appearing, the control being used, the door moving. They go into a review page.

## 8. Open questions

- **The clock cap's value.** It will be set from a measurement of tick time per clock on a typical machine, with a
  recommendation.
- **Safety.** The safety layers run on every redstone wish as on any other. A refusal on an evaluation wish is reported
  and the refusal stands.
