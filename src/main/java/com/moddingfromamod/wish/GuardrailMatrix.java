package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The P1–P8 guardrail matrix, as a table the build readsrather than a grid a person reads.</b>
 *
 * <p>{@code docs/WISH_BUILD_GUARDRAILS.md} carries a ✅/⚠️/⬜ grid of every creation principle
 * against every wish class. It is a good document and it has one structural weakness: <b>nothing
 * fills in a new column.</b> Add a fifth wish class tomorrow and the grid still renders perfectly,
 * with that class simply absent — which reads, to anyone skimming, as "no known gaps".
 *
 * <p>That is the same failure is about. A rule recorded only in prose is true in the document
 * and unenforced in the product. {@link ReplayContract} already proves the executable shape works
 * for the five replay obligations: a Java table read by a real gate, so a missing obligation fails
 * <b>by name</b> instead of surfacing months later. This is that shape, applied to the principles.
 *
 * <p><b>What this enforces, and what it deliberately does not.</b> It cannot check that P3 is
 * actually upheld in the code — that is what the guardrail GameTests are for, and the gate map in
 * the doc says which gate covers which principle. What it enforces is <b>declaration</b>: every
 * wish class states, for every principle, that it holds, is not applicable (with a reason), or is a
 * known gap (with an issue to track it). Silence is not an option, and an unexplained gap is not an
 * option. A new wish class cannot ship with an empty column, because the build fails.
 *
 * <p><b>Adding a wish class?</b> Add it to {@link ReplayContract#ALL} and add its column here. The
 * gate will tell you if you forget the second half.
 */
public final class GuardrailMatrix {

    /** How a wish class stands against one principle. */
    public enum Status {
        /** Upheld, and there is a gate that says so. */
        HOLDS,
        /** Genuinely does not apply to this kind of thing — {@link Cell#note} says why. */
        NOT_APPLICABLE,
        /** A real, known gap. Must cite a tracking issue, or it is just a shrug. */
        GAP
    }

    /**
     * One cell of the matrix.
     *
     * @param status what the state is
     * @param note   why it does not apply, or what the gap is. Required for everything but HOLDS —
     *               a bare ⬜ in the document is exactly the thing that stops being re-examined.
     * @param issue  the tracking issue number for a GAP, or 0
     */
    public record Cell(Status status, String note, int issue) {

        public static Cell holds() {
            return new Cell(Status.HOLDS, "", 0);
        }

        public static Cell na(String why) {
            return new Cell(Status.NOT_APPLICABLE, why, 0);
        }

        public static Cell gap(String what, int issue) {
            return new Cell(Status.GAP, what, issue);
        }
    }

    /** One creation principle. The prose lives in the doc; this is its identity and scope. */
    public record Principle(String id, String name) {
    }

    public static final List<Principle> PRINCIPLES = List.of(
            new Principle("P1", "Navigation ≠ Creation"),
            new Principle("P2", "Iterate in place; keep the good one"),
            new Principle("P3", "Robust materialization (no silent loss)"),
            new Principle("P4", "Locked placement"),
            new Principle("P5", "Removable / undoable"),
            new Principle("P6", "Persistence hygiene"),
            new Principle("P7", "Resource / performance safety"),
            new Principle("P8", "Persistence honesty"),
            new Principle("R1", "An edit verb the agent actually reaches"),
            new Principle("R2", "Grade the edit on the edit"),
            new Principle("R3", "Persist the resolved artifact; re-derive its summary"),
            new Principle("R4", "Grade structure, not only colour"),
            new Principle("R5", "Requested change vs. accidental change"),
            new Principle("R6", "The agent can read what the thing currently is"),
            new Principle("R7", "A NEW axis survives an edit that never mentions it"),
            new Principle("CS", "Child-safe content"));

    /**
     * What every class except creatures says on every R principle.
     *
     * <p>Not "we think it is broken" — <b>unmeasured</b>, which is the same cell. The creature
     * ladder is the only refinement measurement that exists, and three of the four defects it
     * found were invisible to a green gate, so inspection is not evidence here.
     *
     * @param analogue the class's own version of the R4 question, so the cell says something
     *                 concrete rather than repeating the principle back
     */
    private static Cell unmeasured(String analogue) {
        return Cell.gap("unmeasured — no refinement ladder for this class. The R4 question here "
                + "is whether " + analogue, 149);
    }

    /**
     * What a class says on R7 when nothing sweeps its merge.
     *
     * <p>R7 is not "we think it is broken" either — it is that the merge is a <b>whitelist</b>, so
     * an axis added later is dropped by default and the drop is silent. A class with no sweep
     * cannot know; it can only know that the axes somebody remembered are covered.
     */
    private static Cell noSweep(String axes) {
        return Cell.gap("unmeasured — no sweep over this class's merge, so a new axis is covered "
                + "only by whoever remembers to extend it. The axes today are " + axes, 149);
    }

    /**
     * <b>CS — what every class says, because the three layers are wish-class-agnostic.</b>
     *
     * <p>The child-safety layer ({@code docs/CHILD_SAFETY.md}) sits at two choke points that every
     * wish of every class passes through and one that every tool call passes through: the INPUT
     * grade in {@code WishService.submit}, the PLAN grade in {@code McpProtocol}'s {@code
     * tools/call}, and {@code SafetyTextSweep} over every child-visible tool argument. None of them
     * knows or cares which kind of thing is being made, which is why this row is one sentence
     * repeated and not six different ones — and why the cell says so rather than being a bare
     * {@code holds()} that reads as "somebody checked this class specifically".
     *
     * <p>⚠ <b>The datapacks class is the exception and does not use this helper</b>, which is the
     * point rather than an omission: it has a FOURTH surface no other class has — text an
     * mcfunction prints at runtime, long after any tool call — so {@code SafetyDatapackSweep} at
     * {@code CbScriptCompiler.compile} is class-specific by nature and its cell says what it
     * actually covers. Every other class's child-visible text is an argument the agent passes.
     *
     * <p><b>The shipped default is ON</b> (plan §8, flipped on 2026-09-11 after one day
     * of shadow measured 0.0% false positives on the real wishes): the layers grade, log and act.
     * The base rate came before the refusal, which was the rule — and it is a fact about the whole
     * layer rather than a property of any one class, so it is recorded here in every cell instead
     * of being six copies of the same sentence.
     *
     * @param surface this class's own child-visible text, named, so the cell says something
     *                concrete rather than repeating the principle back
     */
    private static Cell childSafe(String surface) {
        return new Cell(Status.HOLDS,
                "input grade, plan grade and the child-visible text sweep are all wish-class "
                        + "agnostic, so this class is covered by construction rather than by "
                        + "anybody having checked it. Its own swept surface is " + surface
                        // Was "Shipped default is SHADOW ... enforced only when it is flipped
                        // on", which stopped being true the moment it was (2026-09-11).
                        // ChildSafetyConfig's own javadoc calls this the one kind of stale comment
                        // worth fixing on sight, because a reader who trusts it reaches the
                        // OPPOSITE conclusion from the code.
                        + ". ⚠ Shipped default is ON (plan §8, flipped on 2026-09-11 "
                        + "after one day of shadow measured 0.0% false positives on the real wishes "
                        + "typed in play): graded, logged and acted on.", 0);
    }

    /**
     * The matrix: wish class → principle id → cell.
     *
     * <p>Keys are the {@code feature()} names in {@link ReplayContract#ALL}, so the two tables are
     * joinable and a class present in one but not the other is a build failure.
     */
    public static final Map<String, Map<String, Cell>> MATRIX = buildMatrix();

    private GuardrailMatrix() {
    }

    private static Map<String, Map<String, Cell>> buildMatrix() {
        Map<String, Map<String, Cell>> m = new LinkedHashMap<>();

        // "trains" and "machines" had columns here until Create went; both are parked,
        // with their measurements, on branch parked/create-trains-mc1.21.1.
        m.put("buildings", column(
                Cell.holds(),
                Cell.holds(),
                Cell.holds(),
                Cell.holds(),
                Cell.holds(),
                Cell.holds(),
                Cell.holds(),
                Cell.gap("a hand-built one-off grant is not detected, logged or communicated", 1),
                // MEASURED, and then fixed. The buildings ladder (six cells, n=3 per arm)
                // found the failure is PROLIFERATION rather than the creatures' rebuild: with no
                // edit verb, "put a dome on top of it" built a SECOND building and left the castle
                // alone. Refinements that landed on the thing: 3 of 4 for the worker who built it,
                // 0 of 4 for anyone else. Fixed by build_structure start_from + named shapes,
                // inspect_build, and BuildDrift.
                Cell.holds(),   // R1 — start_from with named shapes; BuildEditGameTest
                Cell.holds(),   // R2 — clamp notes grade the SENT shapes, budget the merged whole
                Cell.holds(),   // R3 — the MERGED plan is persisted; a second edit inherits it
                Cell.holds(),   // R4 — BuildDrift, keyed on the footprint, proven wired
                Cell.holds(),   // R5 — a demolition he asked for is not drift; "taller" is not
                Cell.holds(),  // R6 — inspect_build reports palette, size and shapes by name
                noSweep("shapes, palette and anchor"),
                childSafe("build_structure's `name` and finish_building's `say`")));

        m.put("creatures", column(
                Cell.holds(),
                Cell.holds(),
                Cell.na("a creature is spawned, not block-placed, so there is no brittle-block "
                        + "or silent-assembly failure mode to guard"),
                Cell.holds(),
                Cell.holds(),
                Cell.holds(),
                Cell.na("one spawned creature is not heavy work; the time-slicing guard is for "
                        + "block builds"),
                Cell.gap("a hand-built one-off grant is not detected, logged or communicated", 1),
                // The only class with a refinement measurement behind it. Every cell below was a
                // real defect on 2026-08-14, found by the ladder in docs/WISH_CONTEXT_PLAN.md, and
                // none of them was visible to a green gate.
                Cell.holds(),
                Cell.holds(),
                Cell.holds(),
                Cell.holds(),
                Cell.holds(),
                Cell.holds(),
                // R7 — creatures found this THREE times before it had a name (a colour
                // edit re-deciding every part, then rotation, then the palette itself);
                // nothing sweeps modify_mob's merge, so the fourth axis is uncovered.
                noSweep("parts, palette, rotation, scale and behaviour"),
                childSafe("define_creature's, sculpt_creature's and modify_mob's `name` — the "
                        + "three verbs that put a label over a creature's head")));

        // ITEMS, MEASURED OVER FIVE ROUNDS (docs/REFINEMENT_LADDERS.md class 2). The class
        // failed R1-R4 in a way none of the others did: an item could be MADE and nothing else —
        // no list, no inspect, no edit, no remove — and its identity was a slug of its own name and
        // effects, so every edit was a new item by construction. Measured before: refinements that
        // LANDED equalled EXTRA items exactly, in both arms; a child asking for one wand ended a
        // five-rung ladder holding three. Measured after: zero extra items, both arms, n=3 each.
        m.put("items", column(
                Cell.na("an item is in his hand, not somewhere in the world to navigate to"),
                Cell.holds(),   // P2 — start_from keeps the one he has
                Cell.na("an item is given whole by one command or not at all"),
                Cell.na("an item has no position"),
                // MEASURED AND STILL OPEN. MadeKind has BUILD, CREATURE, CONTRAPTION, TRAIN and
                // DATAPACK and no ITEM, and there is no remove_item — so an item made by a wish is
                // not recorded as made by it and cannot be taken back. MadeKind's own doc says a
                // kind recorded but never reversed makes the [undo] button lie.
                Cell.gap("an item cannot be removed or undone: no MadeKind.ITEM, no remove_item", 160),
                Cell.holds(),   // P6 — ItemSpecStore persists the design and survives a restart
                Cell.na("an item is one datapack of a few hundred bytes; the time-slicing guard "
                        + "exists for builds that run to hundreds of thousands of blocks"),
                Cell.gap("a hand-built one-off grant is not detected, logged or communicated", 1),
                Cell.holds(),   // R1 — make_item start_from; ItemEditGameTest drives the real tool
                Cell.holds(),   // R2 — params merge per effect AND per key, so an edit grades the edit
                Cell.holds(),   // R3 — ItemSpecStore holds the resolved design; the next edit inherits
                // R4 — BOTH HALVES OF THE BEHAVIOUR, and it took the item-effects class to notice
                // there were two. ItemDrift grades the effect LIST (a wand that stopped doing
                // lightning) and a parameter block emptied out. It deliberately ignores a CHANGED
                // value, because a changed value is what a refinement is — which left the numbers
                // ungraded, and the numbers are half of what an item effect IS. EffectDrift grades
                // those. Both proven wired through call() by ItemCritiqueWiringTest.
                Cell.holds(),
                // R5 — a removal he asked for is not drift, and "…too" is not; and now a number
                // rewritten on a wish that never mentioned that effect reads differently from the
                // one he asked to change. Before EffectDrift this cell claimed to tell a requested
                // change from an accidental one while the commonest accidental change on this class
                // — a burn quietly going back to 8 — was invisible to every gate.
                Cell.holds(),
                Cell.holds(),  // R6 — inspect_item reports name, icon, colour, count and effects
                // R7 — HOLDS, and it is the only class where it does.
                // EveryShapeSurvivesARefinementTest sweeps every shape x every effect from
                // the LIVE catalogue against an edit that only renames the item, so the
                // next shape and the next effect are covered the moment they exist. Written
                // after three axes shipped this bug one at a time: variants (— "make
                // them all give you way more speed" deleted three of four flavours),
                // properties (a colour edit put the LARGE cup back to stacking to 64) and
                // parts ("make it blue" took the sprinkles off the cone). Seen red by
                // deleting inherit's parts clause.
                Cell.holds(),
                // The only class whose swept surface is more than one field: a set of flavours is
                // a dozen names in his inventory, and SafetyTextSweep reads variants[].name for
                // exactly that reason. The plan's own §3 list has neither.
                childSafe("make_item's `name` AND every `variants[].name` in a set")));

        // APPLIANCES — MACHINES YOU FILL. An item on the machine shape, so every edit
        // principle is the item's own machinery and stands where items stand. What differs is that
        // it STANDS in the world once he puts it down, so placement and undo are answered here.
        Map<String, Cell> appliances = new LinkedHashMap<>(m.get("items"));
        appliances.put("P1", Cell.na("he puts the machine down himself, where he stands; the wish "
                + "never has to find a place for it"));
        appliances.put("P4", Cell.na("the wish hands over a machine and HE places it with a click, so "
                + "there is no anchor for the wish to lock; with make_item's at (#1379) it is put at the "
                + "place he NAMED, resolved by PlaceFinder from that thing's own position"));
        appliances.put("P5", Cell.holds());   // remove_item forgets it AND takes every placed one down
        appliances.put("P7", Cell.na("a machine is a three-block body and a few hundred bytes of "
                + "datapack; its once-a-second check iterates its own anchors, never blocks"));
        m.put("appliances", appliances);

        // PICTURES — a class (replay, fork) and in ReplayContract.ALL. The R cells that say "unmeasured" are honest: no picture refinement
        // ladder has been run, so nothing grades an edit against the picture it changed.
        m.put("pictures", column(
                // P1 — list_paintings says where each one hangs, so "take me to my picture" is a
                // tp; nothing in paint_surface builds a copy to travel to.
                Cell.holds(),
                Cell.holds(),   // P2 — start_from edits his own in place; another's is FORKED
                // P3 — a picture sent with more shapes than a recipe keeps is REFUSED
                // (PaintSurfaceTool.tooManyShapes), and an edit at the cap drops the oldest shapes
                // rather than the edit (PictureEdit.layer). Both used to be a silent cut.
                Cell.holds(),
                Cell.holds(),   // P4 — no coordinates means a new board at his LOCKED focus
                // P5 — remove_painting, and undo of a hung picture takes its board down and gives
                // back what the board cleared (PaintRecord.board)
                Cell.holds(),
                Cell.holds(),   // P6 — taught as /wishpaint <id>, which re-reads the recipe store
                // P7 — capped (PanelGeometry.MAX_SPAN, PaintRecipe.MAX_RECTS) but NOT spread over
                // ticks: PicturesHarness measured the 16x16 worst case at 1910 ms in one tick, and
                // The colour memo brought it to 169-269 ms. Still over a tick;.
                Cell.gap("the biggest picture is hung in one tick (169-269 ms measured); the tiles "
                        + "are not spread over ticks", 1129),
                Cell.gap("a hand-built one-off grant is not detected, logged or communicated", 1),
                Cell.holds(),   // R1 — paint_surface start_from; PictureEditTest
                unmeasured("a picture edit is graded on the shapes sent or on the whole picture"),
                Cell.holds(),   // R3 — PictureStore keeps the resolved recipe; the next edit layers on it
                unmeasured("a change that repaints the whole canvas reads as an edit"),
                unmeasured("\"make the sun bigger\" that also recolours the sky is noticed"),
                Cell.holds(),   // R6 — inspect_painting reads the stored recipe back
                noSweep("shapes, canvas size, width and height"),
                childSafe("paint_surface's `status` — the words shown while it paints")));
        // EFFECT BLOCKS. An item on the magic_block shape: the edit principles are the
        // item's, and placement is his, like a machine. What it adds is a thing that acts on the
        // world BY ITSELF, so performance is answered here and holds: every reach is capped at 16,
        // a fill at 3 and air-only, the body is never a block entity, and the clock iterates its
        // own anchors, never blocks.
        Map<String, Cell> effectBlocks = new LinkedHashMap<>(m.get("items"));
        effectBlocks.put("P1", Cell.na("he puts the block down himself, where he stands"));
        effectBlocks.put("P4", Cell.na("the wish hands over a block and HE places it with a click, so "
                + "there is no anchor for the wish to lock; with make_item's at (#1379) it is put at the "
                + "place he NAMED, resolved by PlaceFinder from that thing's own position"));
        effectBlocks.put("P5", Cell.holds());   // remove_item takes every placed one AND every fill back
        effectBlocks.put("P7", Cell.holds());   // MagicBlock caps + BlockLook's no-block-entity rule
        m.put("effect_blocks", effectBlocks);

        // STAGED EVENTS (E5). A design kept per world and set off where he aimed; every block
        // it touches is journaled, which is what makes P5 and P3 hold rather than hope. The R cells
        // that say "unmeasured" are honest: no event refinement ladder has been run yet.
        m.put("events", column(
                Cell.gap("list_events does not say where a crater or a flood that stays is, so "
                        + "\"take me to the crater\" has no answer yet", 1047),
                Cell.holds(),   // P2 — the SAME name replaces the design in place (EventDesigns.put)
                // P3 — over its size's budget it is REFUSED block by block and the journal counts
                // them; nothing is silently cut
                Cell.holds(),
                Cell.holds(),   // P4 — staged at the locked focus, or at the place he named (at)
                Cell.holds(),   // P5 — remove_event: the journal puts back every block it touched
                Cell.holds(),   // P6 — taught as /moddyevent stage <name>, which re-reads the design
                Cell.holds(),   // P7 — EventScale caps blocks per event AND per tick (D2: 250k / 8k)
                Cell.gap("a hand-built one-off grant is not detected, logged or communicated", 1),
                Cell.holds(),   // R1 — inspect_event, then stage_event under the same name
                unmeasured("an event edit is graded on the field changed or on the whole design"),
                Cell.holds(),   // R3 — EventDesigns keeps the parsed design; inspect_event reads it back
                unmeasured("a change of motion reads as an edit rather than a new event"),
                unmeasured("\"make the wave bigger\" that also changes what it is made of is noticed"),
                Cell.holds(),   // R6 — inspect_event gives the design back as stage_event arguments
                noSweep("motion, size, material, catch, blocks, then and at_zero"),
                childSafe("stage_event's `title` — the big words across his screen")));

        // DATAPACKS, MEASURED 2026-08-19 (docs/REFINEMENT_LADDERS.md class 4). Arm B went 0.67 to
        // 1.000 sd 0.00, matching arm A exactly, over three conditions: a read-back tool (fixed the
        // invisible duplicate pack and nothing else), the rules listed unasked in the handover (no
        // movement at all), and finally refuse-once on a lossy rewrite (closed it). The order
        // matters — the two information fixes are what proved this was a GENERATION problem rather
        // than a knowledge one, which is the only reason a refusal is justified here.
        m.put("datapacks", column(
                Cell.na("a datapack is not a thing in the world, so there is nothing to navigate to"),
                Cell.holds(),
                Cell.na("compiled and loaded whole or not at all — no partial materialization"),
                Cell.na("a datapack has no position"),
                Cell.holds(),
                Cell.holds(),
                Cell.gap("a datapack that tanks tick-rate is not automatically detected and "
                        + "disabled, so a young player cannot pull back from it", 2),
                Cell.holds(),
                // R1 — re-authoring the SAME feature_id edits in place; a new id was the rebuild,
                // and an invisible one. ended_in_rebuild 1 -> 0 once the read-back existed.
                Cell.holds(),
                // R2 — the gate grades what the new script DROPPED against the old one, per marker
                // (resource ids, command verbs, functions), not the whole file as a diff.
                Cell.holds(),
                // R3 — DatapackStore holds the compiled source and it IS the artifact; packSha
                // moved on every rung that changed the behaviour and never otherwise.
                Cell.holds(),
                // R4 — DatapackDrift, and it REFUSES once rather than only warning. Two
                // information fixes were measured first and neither moved retention, which is what
                // earned the refusal: the agent already knew what was there.
                Cell.holds(),
                // R5 — a removal he asked for is not drift. The word list is deliberately narrow
                // and there is a test recording what that costs: "take the exploding off" is
                // refused once, because a false refusal costs one redirect and a missed report
                // costs him a wish he cannot see is gone.
                Cell.holds(),
                // R6 — list_datapacks returns the script a feature currently runs. Nothing did
                // before: no caller of DatapackStore.sourceFor existed anywhere.
                Cell.holds(),
                Cell.na("a datapack refinement re-authors the source; there is no merged "
                        + "payload of independent axes for a new one to be dropped from"),
                // CS — CLOSED by CS-D4 (§12 decision 10). This was the one real GAP in the matrix:
                // every other class's child-visible text is an ARGUMENT the agent passes, which
                // SafetyTextSweep reads at the choke point, and a datapack's is neither — CBScript
                // compiles to mcfunction that says its words again every time the trigger fires,
                // days after the wish, when there is no tool call to sweep and no grader awake.
                //
                // ⚠ The gap text used to say closing it meant "sweeping the COMPILED output in
                // author_datapack", and BOTH halves were wrong. The output only exists if the
                // compile succeeded (so a sweep of it could not refuse one, only unpick a
                // persisted pack); most drafts never compile, which is the whole premise of the
                // mined corpus; and the compiler re-splices a string across JSON segments at every
                // colour marker, so a phrase that is contiguous in the source can be split in the
                // output where whole-word matching cannot see it. And every compile goes through
                // CbScriptCompiler.compile, so the tool is the second-best place by one hook.
                new Cell(Status.HOLDS,
                        "SafetyDatapackSweep runs the lexicon over the SOURCE inside "
                        + "CbScriptCompiler.compile, before the subprocess — so a CLEARLY term "
                        + "refuses the compile as an ordinary CompileResult failure naming the "
                        + "line, nothing is written, and the child reads nothing. Four channels, "
                        + "derived from the language rather than from prose: the chat verbs "
                        + "(say/tellraw/title/tell/msg/w/me/teammsg/tm, through execute … run), "
                        + "the display-name verbs (bossbar/scoreboard/team, quoted parts only "
                        + "because their tails are identifiers), CBScript's own "
                        + "tell/title/subtitle/actionbar statements, and the readable NBT and "
                        + "component members (CustomName, custom_name, item_name, lore, messages, "
                        + "pages, title, description, text, translate). Identifiers are "
                        + "deliberately NOT swept — the lexicon treats `_` as a word boundary, so "
                        + "`meth_timer` matches `meth` and a sweep of every literal would refuse a "
                        + "wish over a variable name. VERIFIED FIRST, the condition on the "
                        + "chunk: 60,646 readable snippets over 5,187 real sources, 0 hits, and "
                        + "SafetyDatapackSweepTest replays the committed corpus on every build.",
                        0)));

        return java.util.Collections.unmodifiableMap(m);
    }

    /** Positional helper so a column reads as a column — the order is {@link #PRINCIPLES}. */
    private static Map<String, Cell> column(Cell... cells) {
        if (cells.length != PRINCIPLES.size()) {
            throw new IllegalArgumentException("a column must have exactly " + PRINCIPLES.size()
                    + " cells, one per principle; got " + cells.length);
        }
        Map<String, Cell> col = new LinkedHashMap<>();
        for (int i = 0; i < cells.length; i++) {
            col.put(PRINCIPLES.get(i).id(), cells[i]);
        }
        return col;
    }

    /**
     * Everything wrong with the matrix right now, or empty if it is complete and honest.
     *
     * @param wishClasses the wish classes that actually exist — pass
     *                    {@code ReplayContract.ALL.stream().map(Replayable::feature).toList()}
     */
    public static List<String> violations(List<String> wishClasses) {
        List<String> problems = new ArrayList<>();

        for (String kind : wishClasses) {
            Map<String, Cell> col = MATRIX.get(kind);
            if (col == null) {
                problems.add(kind + ": no column in the guardrail matrix. A new wish class must "
                        + "state where it stands on every principle — an absent column reads as "
                        + "\"no known gaps\" to anyone skimming, which is how a class ships "
                        + "without a removal path or a performance cap. See "
                        + "docs/WISH_BUILD_GUARDRAILS.md and add one.");
                continue;
            }
            for (Principle p : PRINCIPLES) {
                Cell cell = col.get(p.id());
                if (cell == null) {
                    problems.add(kind + " / " + p.id() + " (" + p.name() + "): not declared");
                    continue;
                }
                if (cell.status() != Status.HOLDS && cell.note().isBlank()) {
                    problems.add(kind + " / " + p.id() + " (" + p.name() + "): marked "
                            + cell.status() + " with no reason. A bare \"N/A\" is the cell that "
                            + "stops being re-examined; say why.");
                }
                if (cell.status() == Status.GAP && cell.issue() <= 0) {
                    problems.add(kind + " / " + p.id() + " (" + p.name() + "): a known GAP with no "
                            + "tracking issue — that is a shrug, not a plan. File one and cite it.");
                }
            }
        }

        // A column for a class that no longer exists is stale rather than dangerous, but it makes
        // the matrix lie about coverage, so it is still reported.
        for (String kind : MATRIX.keySet()) {
            if (!wishClasses.contains(kind)) {
                problems.add(kind + ": has a guardrail column but is not a real wish class any "
                        + "more (not in ReplayContract.ALL) — the matrix is claiming coverage of "
                        + "something that does not exist");
            }
        }
        return problems;
    }

    /** The open gaps, for a report or a doc regeneration. */
    public static List<String> openGaps() {
        List<String> out = new ArrayList<>();
        MATRIX.forEach((kind, col) -> col.forEach((pid, cell) -> {
            if (cell.status() == Status.GAP) {
                out.add(kind + " / " + pid + ": " + cell.note() + " (issue #" + cell.issue() + ")");
            }
        }));
        return out;
    }
}
