package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.builder.BuildGeometry;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.BuildShape;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.provenance.ProvenanceContext;
import com.moddingfromamod.provenance.ProvenancePublisher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Build a real structure out of composable shapes.
 *
 * <p>Exists because the alternative was watching an agent emit forty {@code fill} commands with
 * hand-computed corners. It got the arithmetic wrong often enough that a "castle" came out as a
 * lopsided box — and a child asking for *"a castle that floats on the water with a golem guarding
 * the door"* deserves better than that. Describing a keep, four towers, four roofs and a courtyard
 * as shapes is something an AI does reliably.
 *
 * <p>Every plan is <b>saved under its id</b>, which is the part that matters for the long game: a
 * learned wish template can then re-run it with {@code wishbuild <id>}, so the second time the
 * child asks for that castle it goes up instantly and offline.
 */
public final class BuildStructureTool implements McpTool {

    @Override
    public String name() {
        return "build_structure";
    }

    @Override
    public String description() {
        return "Build a real building out of shapes — far better than writing fill commands by "
                + "hand. Give it a list of shapes (box, cylinder, sphere, pyramid), each with an "
                + "offset from the child, a size, and a block. Shapes are placed IN ORDER, so a "
                + "later shape overwrites an earlier one: that is how you make a doorway (build the "
                + "wall, then a small box of minecraft:air through it) and how a roof sits on a "
                + "tower. Use hollow=true for walls and rooms, or you get a solid lump. The plan is "
                + "SAVED under its id, so add_template can later run 'wishbuild <id>' to put the "
                + "same building up instantly with no agent. Build big and impressive — this is a "
                + "child's wish, not a shed."
                + " ⚠ For a PICTURE, a FACE or a LOGO on a wall, use paint_surface, NOT coloured "
                + "blocks: a block face is about nine pixels, so a character built out of blocks "
                + "comes out as a coloured square.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        // A STATUS THAT RIDES ALONG WITH THE WORK. Calling set_status separately costs a
        // full model round trip -- ~4s, and 12% of one measured session -- for a tool that
        // executes in under 8ms. Passed here it is shown the moment the call arrives, in the
        // agent's own words, for nothing.
        Schemas.prop(s, "status", "string",
                "A SHORT kid-facing line saying what you are doing, shown the instant this "
                        + "call arrives, e.g. 'Building your castle'. Pass it EVERY time: it is "
                        + "free here, whereas a separate set_status costs the child a wait.");
        Schemas.prop(s, "id", "string",
                "Short unique name, e.g. 'floating_castle'. Re-use it to replace that building.");
        Schemas.prop(s, "name", "string", "What to call it to the child, e.g. 'Your Floating Castle'.");
        Schemas.prop(s, "shapes", "array",
                "The building. Each item: {kind: box|cylinder|sphere|pyramid, dx, dy, dz (offset "
                        + "from the child, dy is up), width, height, depth (each 1 to "
                        + BuildShape.MAX_DIM + "), block (any block id, with its state if it has one: "
                        + "\"minecraft:ladder[facing=south]\"), hollow (true/false), "
                        + "anchor: \"corner\" (default) or \"centre\", name (optional but worth "
                        + "giving — \"keep\", \"roof\", \"west_tower\" — because it is how a "
                        + "later wish changes THAT part instead of rebuilding everything)}. "
                        + "dx/dz are the shape's LOW CORNER unless you set anchor:\"centre\". "
                        + "This matters most when one shape goes inside another: a 44-wide wall "
                        + "and a 40-wide air cylinder BOTH at dx:0,dz:0 are NOT concentric — they "
                        + "line up along one edge and the air eats two of the walls. Either use "
                        + "anchor:\"centre\" on both, or offset the inner one by half the "
                        + "difference (dx:2,dz:2 here). Centring is horizontal only: dy is always the "
                        + "base, so a 19-wide hollow sphere round a 17-wide air sphere goes ONE "
                        + "block lower (dy:-22 around dy:-21), or the air eats its bottom.");
        Schemas.prop(s, "start_from", "string",
                "TO CHANGE A BUILDING THAT ALREADY EXISTS, put its id here (usually the same as "
                        + "'id') and send ONLY the shapes that change. Everything you do not "
                        + "mention is kept. A shape whose 'name' matches one in the building "
                        + "REPLACES it where it stands; a new name is added on top. Call "
                        + "inspect_build first to see the names. This is how you add a dome, a "
                        + "gate or a tower — building a SECOND structure beside theirs is almost "
                        + "never what the child meant.");
        Schemas.prop(s, "as_new", "boolean",
                "Pass TRUE only when he asked for a SECOND, separate building in this wish (a house "
                        + "AND a tower). Without it, a second building from nothing in the same "
                        + "wish is refused: to redo or fix the one you already built, send its SAME "
                        + "id — it is rebuilt where it stands and he keeps one, never two.");
        Schemas.prop(s, "remove", "array",
                "With start_from: names of the building's shapes to delete, e.g. [\"roof\"].");
        Schemas.prop(s, "spots", "array",
                "NAMED SPOTS where things go IN or ON this building — a boss on his THRONE, a "
                        + "dragon on the ROOF, a baker at her COUNTER, a knight at the "
                        + "GATE. Each item: {name, dx, dy, dz, facing (north|south|east|west, "
                        + "optional)} — the SAME offsets as the shapes, naming the block the "
                        + "creature's FEET stand in (one ABOVE the seat or floor block). Then put a "
                        + "creature there with at:\"<this id>:<spot name>\" on sculpt_creature / "
                        + "define_creature, or `wishmob spawn <id> at <this id>:<spot>`. Every "
                        + "building also has a 'front' spot outside its entrance without asking. "
                        + "A REAL mob (summon) or anything else goes on a spot with run_command "
                        + "`wishspot <this id>:<spot> run summon minecraft:… ~ ~ ~` (~ ~ ~ IS the "
                        + "spot) — never at coordinates worked out by hand. "
                        + "Spots are kept on an edit; send one with the same name to move it.");
        Schemas.prop(s, "remove_spots", "array",
                "Names of spots to delete, e.g. [\"throne\"].");
        Schemas.prop(s, "offsetY", "integer",
                "Lift the whole thing up or down FROM the spot he aimed at, e.g. 30 to make it "
                        + "float in the sky. Default 0. "
                        + "Above 0 it is left floating exactly there; otherwise it is made to meet "
                        + "the ground for you (a footing down a slope, an island on water, set down "
                        + "off a treetop), so never build foundations of your own.");
        Schemas.prop(s, "at", "string", com.moddingfromamod.place.At.describe("the NEW building",
                "beside it (next to it, never across its doorway)",
                "A side — beside, behind, front, north/east/south/west — puts it OUTSIDE the thing on that "
                        + "side, a block of air between the walls (\"a stable next to my house\" → "
                        + "at:\"<house id>:beside\"). around, top and inside CENTRE it there: \"a wall round "
                        + "my castle\" → at:\"<castle>:around\" (make it wider than the castle — inspect_build "
                        + "says how wide), \"a lighthouse on my castle\" → at:\"<castle>:top\". Only for a NEW "
                        + "building; changing one rebuilds it where it stands."));
        Schemas.prop(s, "offsetForward", "integer",
                "Only when the wish has NO aimed spot: put it this many blocks in front of the "
                        + "child. Default 12. Ignored when he aimed somewhere — it is built where he "
                        + "aimed. Leave it out.");
        Schemas.prop(s, "build", "boolean", "Build it right now (default true). False = just save.");
        // Said here as well as in the lessons, because this is the field that creates the
        // dark space in the first place.

        Schemas.prop(s, "note", "string", ProvenanceContext.NOTE_HINT);
        return Schemas.required(s, "id", "shapes", "note");
    }

    /**
     * <b>Tell the agent when a room came out without one of its walls.</b>
     *
     * <p>The same oracle that gates the buildings corpus, run on the real plan before it goes up.
     * It costs a millisecond and it closes the loop that is about: the agent cannot see its own
     * building, so a wall eaten by a later air shape is invisible to it and it says "done" over a
     * castle you can walk straight through the back of. Two real builds shipped exactly that way.
     *
     * <p>A note, not a refusal. An open side can be deliberate — a bandstand, a shelter, an arena
     * with a gateway — and refusing would make the honest cases unbuildable. What must not happen is
     * that nobody notices, which is the same rule as the clamp notes above.
     */
    static String geometrySuffix(BuildPlan plan) {
        return geometrySuffix(com.moddingfromamod.builder.ComposedPlan.of(plan));
    }

    static String geometrySuffix(com.moddingfromamod.builder.ComposedPlan built) {
        BuildPlan plan = built.plan();
        BuildGeometry g = BuildGeometry.of(built);
        List<String> notes = new ArrayList<>();
        for (BuildGeometry.Room room : g.rooms()) {
            List<String> missing = room.missingSides(BuildGeometry.WALL_MISSING);
            if (!missing.isEmpty()) {
                BuildShape s = plan.shapes().get(room.shapeIndex());
                notes.add("shape " + room.shapeIndex() + " (" + s.width() + "x" + s.height() + "x"
                        + s.depth() + " " + s.block() + ") has no " + String.join(" or ", missing)
                        + " wall left once the later shapes are placed");
            }
        }
        // CAN A CHILD GET IN —.
        //
        // BuildGeometry.sealedInterior() has existed, and been unit-tested, while having no caller
        // anywhere in src/main: the flood fill ran in its own test and the answer reached nobody.
        // That is this repo's own trap -- a rule tested where it is DEFINED is not tested where it
        // RUNS -- and it matters here because a sealed box passes every OTHER check on this list.
        // It has four walls (good), a solid lid (good), and BuildLighting fires precisely when a
        // building "encloses a space", so it gets lit too and reports as improved. Every signal
        // says healthy and you cannot get into it.
        BuildGeometry.Sealed sealed = g.sealedInterior();
        if (sealed.tooBig()) {
            // Said out loud rather than passed silently: an unknown reported as a pass is the
            // failure this whole check exists to remove, and it is the plans big enough to hide a
            // sealed room that skip the search.
            notes.add("it is too big to check whether the inside is reachable, so nobody has "
                    + "verified there is a way in");
        } else if (sealed.volume() >= BuildGeometry.SEALED_MIN_ROOM) {
            notes.add(sealedNote(sealed));
        }
        if (g.looksUnfinished()) {
            notes.add("it is " + g.height() + " tall on a span of " + g.span()
                    + " with a solid lid over " + Math.round(g.topCoverage() * 100)
                    + "% of its footprint, so from the air it reads as an unfinished box");
        }
        if (notes.isEmpty()) {
            return "";
        }
        return "\n\nCHECK THE SHAPE — this is what it will actually look like:\n  - "
                + String.join("\n  - ", notes)
                + "\nIf that is deliberate, say so and carry on. If not, the usual cause is that "
                + "dx/dz are a shape's LOW CORNER: two shapes with the same dx/dz and different "
                + "widths are not one inside the other. Set anchor:\"centre\" on both, or offset "
                + "the inner shape by half the difference in size.";
    }

    /**
     * How many doorway coordinates one note may carry before it stops being an instruction.
     *
     * <p>Not a taste call: past a handful of shut rooms the building is full of pockets, which is a
     * differently-shaped problem from "you forgot the front door", and forty coordinates would bury
     * the sentence that says so.
     */
    static final int MAX_BREACHES_SHOWN = 4;

    /**
     * <b>How many rooms are shut, and where to cut into each one.</b>
     *
     * <p>Split out and rewritten because the single-room version told the agent something untrue,
     * and the transcript of it doing as it was told is. A real lighthouse reported
     * <i>"SEALED — 742 blocks … the thinnest spot is the single block at -1,1,-4 — a 1x2 hole there
     * would open it"</i>. The agent cut a 3x4x8 doorway through exactly that wall on its next call,
     * six seconds later, and was answered with <i>"SEALED — 619 blocks … the thinnest spot is
     * -1,6,-3"</i>. It was never one room: 742 was the sum over a stack of them and the doorway
     * opened 123 of it. The wish ended with the building still shut.
     *
     * <p>So the old note was wrong in the specific way this repo keeps re-finding — not a missing
     * check but a <b>claim made past what the check knew</b>, the same shape as and as the
     * sibling defect in {@link com.moddingfromamod.builder.BuildLighting} that this class's own
     * comment already names: <i>"the defect is the CLAIM, not the light"</i>.
     *
     * <p>Adoption was never the problem. Across every real firing on record, an agent that retried
     * cut a doorway — 3 times out of 3. What it could not do was know it had to cut several, so it
     * opened one room per round trip and then stopped.
     */
    static String sealedNote(BuildGeometry.Sealed sealed) {
        // Count and list on the SAME bar. Counting every pocket while listing only the ones over
        // SEALED_MIN_ROOM said "13 SEPARATE rooms" about a lighthouse whose 13th was a two-block
        // void in the quantisation of a cylinder wall — my own version of the overclaim this
        // method exists to remove, caught by reading a live run rather than by a test.
        List<BuildGeometry.Sealed.Room> rooms = sealed.rooms().stream()
                .filter(r -> r.volume() >= BuildGeometry.SEALED_MIN_ROOM)
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("SEALED — ").append(sealed.volume())
          .append(" block").append(sealed.volume() == 1 ? "" : "s")
          .append(" of room inside have no way in");
        if (rooms.size() > 1) {
            // The count FIRST, because it is the fact that changes what the agent does: one hole is
            // not a fix, and the old note read as though it were.
            sb.append(", in ").append(rooms.size()).append(" SEPARATE rooms");
            if (sealed.stackedStoreys()) {
                // The commonest shape in the whole archive, and the one the old advice was worst
                // for. Nine of the fourteen sealed real builds are towers whose rooms are the gaps
                // between decorative bands: a doorway in the outside wall of storey three leaves
                // the other seven exactly as they were, so naming a doorway would send the agent
                // to fix the wrong thing seven more times.
                sb.append(" STACKED ONE ABOVE ANOTHER — that is a tower with no stairs, not a "
                        + "building with no door. A shape drawn flat across a hollow tower is a "
                        + "FLOOR, even a decorative band. Cut a hole through each floor and put the "
                        + "ladder AGAINST THE WALL OF THE HOLE, minecraft:ladder[facing=…] "
                        + "pointing away from that wall (a ladder in the middle of a hole has "
                        + "nothing to hang on), or leave a gap at one side of every band");
            } else {
                sb.append(" — a doorway into one does not open the others");
            }
        }
        // Every room gets its own doorway, so a whole building can be opened in ONE call rather
        // than one call per room.
        int shown = 0;
        for (BuildGeometry.Sealed.Room r : rooms) {
            if (shown >= MAX_BREACHES_SHOWN) {
                sb.append(". (More rooms than that are shut — open these and build again to see "
                        + "the rest)");
                break;
            }
            shown++;
            sb.append(shown == 1 ? ". " : "; ");
            if (rooms.size() > 1) {
                sb.append("the ").append(r.volume()).append("-block one: ");
            }
            int[] b = r.breach();
            if (b == null) {
                sb.append("every wall around it is more than one block thick, so a doorway means "
                        + "cutting through more than the outer shell");
            } else {
                sb.append("thinnest at the single block ").append(b[0]).append(',').append(b[1])
                  .append(',').append(b[2])
                  .append(" — a 1x2 hole of minecraft:air there opens it");
            }
        }
        return sb.toString();
    }

    /**
     * <b>Everything the agent is told about the building it just made, assembled in one place.</b>
     *
     * <p>Extracted as a named function for the reason the creature version records, which was
     * nearly missed there: <b>a gate is not wired just because its class exists and its own unit
     * tests pass.</b> {@link com.moddingfromamod.runtime.gate.BuildDrift} is silent whenever
     * nothing was lost, which is every healthy build, so a live run proves nothing about whether it
     * is connected — and the obvious check, grepping a client's {@code run.log} for its headline,
     * returns zero either way because {@code run.log} carries no tool results at all. Unwiring the
     * creature shape gate left {@code ShapeDriftTest} 6/6 green.
     *
     * <p>So the assembly is a function and {@code BuildCritiqueWiringTest} asserts the warning
     * really reaches this string.
     *
     * <p><b>The structure first, then the geometry.</b> Same order and same reason as the creature
     * critique: the geometry note is about walls and lids, and it was green throughout the ladder
     * runs in which the castle was abandoned entirely.
     *
     * @param before the building as it stood before this call; null for a creation
     * @param after  the RESOLVED building — base and diff merged, never the shapes sent
     */
    static String critiqueFor(BuildPlan before, BuildPlan after) {
        return critiqueFor(before, after, com.moddingfromamod.builder.ComposedPlan.of(after));
    }

    /**
     * The same, with {@code after} already composed: the call site composed it once for
     * the lighting backstop, and the geometry and the light estimate read that same building.
     */
    static String critiqueFor(BuildPlan before, BuildPlan after,
                              com.moddingfromamod.builder.ComposedPlan built) {
        built = built.as(after);
        String drift = com.moddingfromamod.runtime.gate.BuildDrift
                .between(before, after, childWordsFor());
        // then the LIGHT. Last, because it is about the rooms the shape check has just
        // confirmed exist: a roofed room with no light of its own reaching its floor is black to
        // the child at night and was black 's photograph, and the agent cannot see that.
        String geometry = geometrySuffix(built);
        return (drift.isEmpty() ? "" : "\n\n" + drift) + geometry
                + walkSuffix(after, geometry.contains("SEALED —"))
                + groundSuffix(after)
                + com.moddingfromamod.builder.DarkInterior.note(
                        com.moddingfromamod.builder.DarkInterior.of(built));
    }

    /**
     * <b>Can he WALK in?</b> The rooms a child cannot reach on foot — an upper storey with no
     * stairs, a doorway one block tall, a step too high — each with where it is. Advice, never a refusal:
     * a sealed turret may be decoration, and the note says so. Rooms the SEALED line above already named
     * are not named twice. {@code walkadvice=off} is the A/B seam (the off arm 's measurement).
     */
    static String walkSuffix(BuildPlan after, boolean sealedAlreadyTold) {
        if ("off".equalsIgnoreCase(System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".walkadvice"))) {
            return "";
        }
        return com.moddingfromamod.builder.WalkThrough.note(
                com.moddingfromamod.builder.WalkThrough.of(after), sealedAlreadyTold);
    }

    /**
     * <b>Does anything float?</b> Every lump of blocks that stands on nothing, with where it is and how far it
     * hangs — a light in mid-air, a mast a block above its deck, a stem drawn from where its middle was meant. Advice,
     * never a refusal, from the deterministic check ({@link com.moddingfromamod.builder.BuildGrounding}).
     * {@code groundadvice=off} is the A/B seam.
     */
    static String groundSuffix(BuildPlan after) {
        if ("off".equalsIgnoreCase(System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".groundadvice"))) {
            return "";
        }
        String note = com.moddingfromamod.builder.BuildGrounding.note(com.moddingfromamod.builder.BuildGrounding.fix(after));
        if (note.isEmpty()) {
            note = com.moddingfromamod.builder.BuildGrounding.note(com.moddingfromamod.builder.BuildGrounding.of(after));
        }
        return note.isEmpty() ? "" : "\n\nFLOATING —" + note;
    }

    /** The wish in the child's own words, or "" outside a run (a direct call, a gate). */
    static String childWordsFor() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? "" : run.text();
    }

    /**
     * The "it did not come out quite as you asked" note, or "" when it did.
     *
     * <p>Worth telling the AGENT rather than only logging: it is the one who has to decide whether
     * to redesign, and it is the one talking to the child. Silence here is how "make it 150 tall"
     * became a 96-tall tower that everyone called finished.
     */
    private static String clampSuffix(List<String> notes) {
        return notes.isEmpty() ? ""
                : "\n\nNOTE — it did not come out exactly as you asked:\n  - "
                        + String.join("\n  - ", notes)
                        + "\nIf that matters, redesign within the limits and build again; if it "
                        + "still looks right, tell the child what you made rather than implying it "
                        + "is what they asked for.";
    }

    /**
     * <b>Fold a {@code start_from} base under the shapes the agent sent</b>.
     *
     * <p>The buildings twin of {@code SculptCreatureTool.withBase}. Silently no-ops when no base is
     * named or the id is unknown, so a typo costs a plainer building rather than a failed wish.
     *
     * <p>Static and pure of the server so the merge is gated without a game.
     */
    static BuildPlan withBase(BuildPlan sent, JsonObject args,
                              java.util.function.Function<String, BuildPlan> lookup) {
        List<BuildShape> merged = mergedShapes(sent, args, lookup);
        return merged == null ? sent : sent.withShapes(merged);
    }

    /**
     * The merged shape list, <b>before</b> {@link BuildPlan} gets a chance to truncate it — or null
     * when there is no base to merge.
     *
     * <p>Separate from {@link #withBase} because of a silent cap this branch reintroduced and a
     * smoke run caught. {@code BuildPlan}'s compact constructor <b>truncates to MAX_SHAPES</b>, so
     * an overflow check made against the constructed plan can never fire: the list is already 64
     * long by the time it is asked. A real castle reached 64 shapes at rung 4, and the next wish —
     * <i>"put a huge lava moat all the way around the outside of it"</i> — had its shapes dropped
     * on the floor. The call returned SUCCESS, the note said the building had gone up, and nothing
     * whatsoever had changed.
     *
     * <p>That is exactly the failure {@code BuildPlan.clampNotes} exists for: <i>three of this
     * class's four caps were silent</i>. Adding a fifth would have been a poor way to honour it.
     */
    static List<BuildShape> mergedShapes(BuildPlan sent, JsonObject args,
                                         java.util.function.Function<String, BuildPlan> lookup) {
        if (sent == null || args == null || !args.has("start_from")
                || !args.get("start_from").isJsonPrimitive()) {
            return null;
        }
        String baseId = args.get("start_from").getAsString().trim()
                .toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        if (baseId.isEmpty()) {
            return null;
        }
        BuildPlan base = lookup.apply(baseId);
        if (base == null || base.shapes().isEmpty()) {
            return null;
        }
        java.util.Set<String> remove = new java.util.LinkedHashSet<>();
        if (args.has("remove") && args.get("remove").isJsonArray()) {
            for (var el : args.getAsJsonArray("remove")) {
                if (el.isJsonPrimitive()) {
                    remove.add(el.getAsString());
                }
            }
        }
        return com.moddingfromamod.builder.BuildBase.merge(base.shapes(), sent.shapes(), remove);
    }

    /**
     * <b>The building's named spots after this call</b>: what it had (its {@code
     * start_from} base, else the building of this id), with the spots sent replacing those of the
     * same name and {@code remove_spots} gone — then checked against the finished building. Pure
     * of the server, so the merge is a unit test.
     */
    static BuildPlan withSpots(BuildPlan plan, BuildPlan sent, JsonObject args, BuildPlan prior,
                               java.util.function.Function<String, BuildPlan> lookup, List<String> notes) {
        BuildPlan had = prior;
        if (inheriting(args)) {
            BuildPlan base = lookup.apply(args.get("start_from").getAsString().trim()
                    .toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
            if (base != null) {
                had = base;
            }
        }
        java.util.Set<String> remove = new java.util.LinkedHashSet<>();
        if (args != null && args.has("remove_spots") && args.get("remove_spots").isJsonArray()) {
            for (var el : args.getAsJsonArray("remove_spots")) {
                if (el.isJsonPrimitive()) {
                    remove.add(el.getAsString());
                }
            }
        }
        List<com.moddingfromamod.builder.BuildSpot> merged =
                com.moddingfromamod.builder.BuildSpots.merge(had == null ? List.of() : had.spots(),
                        sent.spots(), remove);
        return plan.withSpots(com.moddingfromamod.builder.BuildSpots.checked(plan, merged, notes));
    }

    /** What the agent is told about the building's spots: where each is, and how to use one. */
    static String spotsSuffix(BuildPlan plan, List<String> notes) {
        int[] toward = BuildRegistry.towardOf(plan.id());
        StringBuilder sb = new StringBuilder("\n\nSPOTS (where anything can be put — a creature, a thing, "
                + "an event, a building beside it, a picture: at:\"").append(plan.id()).append(":<spot>\"): ");
        boolean first = true;
        for (var s : com.moddingfromamod.builder.BuildSpots.all(plan, toward[0], toward[1])) {
            sb.append(first ? "" : "; ").append(s.name()).append(" at ").append(s.dx()).append(',')
              .append(s.dy()).append(',').append(s.dz())
              .append(s.facing().isEmpty() ? "" : " facing " + s.facing())
              .append(plan.spots().contains(s) ? "" : " (automatic)");
            first = false;
        }
        sb.append(". Anything that belongs IN, ON or BY this building goes on a spot — never at the "
                + "anchor, which is its foundations.");
        if (!notes.isEmpty()) {
            sb.append("\n  - ").append(String.join("\n  - ", notes));
        }
        return sb.toString();
    }

    /** Did this call inherit a building, rather than describe one from nothing? */
    static boolean inheriting(JsonObject args) {
        return args != null && args.has("start_from") && args.get("start_from").isJsonPrimitive()
                && !args.get("start_from").getAsString().isBlank();
    }

    @Override
    public ToolResult call(JsonObject args) {
        BuildPlan sent = BuildPlan.fromJson(args);
        if (sent == null) {
            // Lessons ride out on the refusal: this is the moment a retry is certain, and for a
            // while it was the ONE path by which a building lesson could never arrive (see
            // LessonsReachTheAgentTest — every kind needs more than one way in).
            //
            // The `start_from` half is P2, and it was earned by a real round trip. This tool's own
            // description says to "send ONLY the shapes that change", so an agent that wanted the
            // saved plan back unaltered sent start_from and an empty shapes list — a literal
            // reading of the instruction it was given. It was answered with a sentence that named
            // neither start_from nor a way forward, and spent two more calls working it out.
            return ToolResult.error("A building needs an 'id' and at least one shape."
                    + (inheriting(args)
                        ? " You passed start_from, so you are editing '"
                          + args.get("start_from").getAsString().trim()
                          + "' — send the shape(s) you want CHANGED (a new name adds, a matching "
                          + "name replaces, everything else is kept). There is nothing to send if "
                          + "nothing changes, and nothing to do either: it is already built."
                        : "")
                    + com.moddingfromamod.wish.WishLessons.appendTo("building"));
        }
        // A MACHINE ANSWERED WITH A BUILDING (smoke, 2026-09-28). "a … facktery" came back
        // as a factory-shaped hall that makes nothing ("the factory doesn't make it by itself
        // yet"), and a weaker model answered every machine wish that way: a stand to look at,
        // with nothing to put in. Asked ONCE per building id, inside a wish that names a machine,
        // and never on an edit — a building is still right when he wants a place.
        String wish = boundWishText();
        String machine = wish == null ? null : MakeItemTool.machineWord(wish);
        String planId = args.has("id") && args.get("id").isJsonPrimitive()
                ? args.get("id").getAsString().trim().toLowerCase(java.util.Locale.ROOT) : "";
        if (machine != null && !inheriting(args) && !planId.isEmpty() && ASKED_MACHINE.add(planId)) {
            return ToolResult.error("He asked for a " + machine + ". A building is the answer only "
                    + "if he wants a PLACE. If it should MAKE something he can hold or eat, make "
                    + "that with make_item: what comes out first (make_item it if it is not in the "
                    + "game yet), then a machine — shape `machine`, effect `load_and_pour` (several "
                    + "things in, sizes, into a chest) or `turn_into` (one thing in, one out). He puts "
                    + "it down and it works, and it comes with enough to try it. A building can "
                    + "still go around it afterwards. If it really is just a building, send the "
                    + "same call again.");
        }
        // NO SILENT CAPS. The block budget already refuses with a sentence; the other three caps
        // used to change the building behind everyone's back. Dropped shapes are REFUSED (the
        // building would come out visibly unfinished and the agent would say "done" anyway);
        // resized or moved shapes are reported, because the building still makes sense and the
        // agent can tell the child it was made as big as it could be.
        //
        // GRADED AGAINST WHAT WAS SENT, and that is R2 for this class. `clampNotes` compares
        // requested[i] with shapes[i] BY POSITION, so grading a two-shape edit against a merged
        // 24-shape castle would report twenty-odd shapes as "made smaller" and "moved closer", in
        // an authoritative voice, ending "redesign within the limits and build again". That is the
        // creature `MISSED` bug exactly — a correct edit reported as a catastrophe, with a rebuild
        // as the only action that satisfies the complaint — and it is what the R1 fix below would
        // have reintroduced on the class whose payload is biggest.
        List<BuildPlan.Requested> asked = BuildPlan.shapesAsRequested(args);
        if (sent.droppedShapes(asked)) {
            return ToolResult.error("That building has too many parts (" + asked.size()
                    + ", limit " + BuildPlan.MAX_SHAPES + "), and the ones over the limit are the "
                    + "LAST ones — usually the roof and the detail — so it would come out looking "
                    + "unfinished. Combine parts (one big box instead of four walls) and try "
                    + "again.");
        }
        List<String> clamped = sent.clampNotes(asked);
        int offsetY = intOr(args, "offsetY", 0);
        int forward = intOr(args, "offsetForward", 12);
        // offsetForward only places it when nothing was aimed; offsetY lifts it from the aimed spot
        // (— an offset used to throw the aim away and build on the child).
        boolean askedForward = args.has("offsetForward");
        // BY a thing he named. Grammar checked before anything is stored.
        final String at = strOr(args, "at").trim();
        if (!at.isEmpty() && com.moddingfromamod.place.At.parse(at) == null) {
            return ToolResult.error("at must read \"<thing>:<spot>\" — a building's id, a creature or me, then a "
                    + "spot (beside, around, top, …), not '" + at + "'.");
        }
        boolean buildNow = !args.has("build") || args.get("build").getAsBoolean();
        // Capture the agent's design reasoning for the manifest (best-effort; blank if omitted).
        ProvenanceContext.note(strOr(args, "note"));

        // read HERE — the worker binding is per thread, and the lambda runs on the server's
        com.moddingfromamod.marshal.WishRun run = boundRun();
        boolean asNew = args.has("as_new") && args.get("as_new").isJsonPrimitive()
                && args.get("as_new").getAsBoolean();
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                // R1 + R3: fold the base under the sent shapes, and persist the RESOLVED building.
                // Persisting the diff instead is the creature bug that shipped twice in one day —
                // the second edit then inherits a dome and no castle.
                // ASKED OF THE RAW MERGE, not of the constructed plan — BuildPlan truncates to
                // MAX_SHAPES in its compact constructor, so a check on `plan` is asking a list
                // that has already been cut and can never be too long. See mergedShapes().
                List<BuildShape> rawMerge = mergedShapes(sent, args, BuildRegistry::get);
                if (rawMerge != null && BuildPlan.written(rawMerge) > BuildPlan.MAX_SHAPES) {
                    return ToolResult.error("That change would leave '" + sent.id() + "' with "
                            + BuildPlan.written(rawMerge) + " parts, and a building can have at most "
                            + BuildPlan.MAX_SHAPES + ". Nothing has been changed. The parts over "
                            + "the limit would have been the ones you just sent, so the building "
                            + "would have looked exactly as it did before. Give the shapes you are "
                            + "adding the SAME names as parts it already has so they replace them, "
                            + "or pass 'remove' with the names of parts this change makes "
                            + "redundant — inspect_build lists them.");
                }
                BuildPlan plan = withBase(sent, args, BuildRegistry::get);
                // A BUILDING A CHILD WALKS INTO SHOULD NOT BE PITCH DARK. Nothing told the
                // agent to light an interior, and a real child had to spend a wish on it ("it
                // probably needs to be brighter inside this castle!"). Unlit rooms also spawn
                // hostile mobs, so this is not only about how it looks. Timid on purpose: only
                // when the building encloses a space and has NO light of its own, capped, never
                // replacing anything — and reported, never silent.
                //
                // asked of the building AS IT WILL STAND, composed once here and handed on to
                // the geometry and light critiques below — a light shape a later shape overwrote
                // (the castle's moat under its island) gives no light, and counting it is what
                // put an earlier issue's lanterns on the lid above the throne room.
                com.moddingfromamod.builder.ComposedPlan built =
                        com.moddingfromamod.builder.ComposedPlan.of(plan);
                List<BuildShape> lights =
                        com.moddingfromamod.builder.BuildLighting.lightsFor(built);
                if (!lights.isEmpty()) {
                    List<BuildShape> lit = new ArrayList<>(plan.shapes());
                    lit.addAll(lights);
                    plan = plan.withShapes(lit);
                    built = built.plus(plan, lights);
                }
                // A LADDER NEEDS A WALL, SAND NEEDS A FLOOR. A ladder or wall torch with no
                // facing is turned toward the wall beside it (timid and reported, like the lights);
                // then everything that cannot stand where it was put is reported before the reveal.
                com.moddingfromamod.builder.BuildSupport.Faced faced =
                        com.moddingfromamod.builder.BuildSupport.autoFace(plan);
                plan = faced.plan();
                // THE BUDGET IS A PROPERTY OF THE FINISHED BUILDING, so unlike the clamp notes it
                // is asked of the merged whole: a two-shape edit that pushes a big castle over the
                // ceiling must still be refused.
                if (!plan.fitsBudget()) {
                    // exactVolume, not volume: the latter stops counting at the ceiling, so a
                    // plan with big shapes AFTER the one that crossed reported a total far under
                    // the truth -- and "lose N more" with it.
                    return ToolResult.error("That building is too big (" + plan.exactVolume()
                            + " blocks, limit " + BuildPlan.MAX_BLOCKS + "). Use hollow shapes for "
                            + "walls — a hollow box is a room, a solid one is a rock."
                            // without naming the offender the agent shaves blind — eight
                            // consecutive refusals on one rung never got under the ceiling.
                            + plan.budgetBreakdown());
                }
                // the TNT budget, asked of the merged whole for the same reason as the
                // block budget — an edit adding a TNT core to a volcano must still be refused.
                if (!plan.fitsTntBudget()) {
                    return ToolResult.error(plan.tntRefusal());
                }
                // Capture the PRIOR build of this id BEFORE we overwrite its design. Two different
                // consumers want it, and they do NOT want it under the same conditions:
                //
                //   * P2's clear-the-old-blocks pass only makes sense when we are building now.
                //   * The DRIFT GATE wants it always. Gating it on buildNow meant a save-only call
                //     could quietly replace a castle's whole design with no warning at all —
                //     caught by the Gate-B test asserting the warning reaches the agent, which is
                //     the entire reason that test drives the real tool instead of the rule.
                BuildPlan priorPlan = BuildRegistry.get(plan.id());
                // THE SPOTS SURVIVE AN EDIT — the base's (or this id's) spots, the sent ones
                // replacing those of the same name, the removed ones gone; then each is checked
                // against the finished building (feet inside the seat are lifted onto it).
                List<String> spotNotes = new ArrayList<>();
                plan = withSpots(plan, sent, args, priorPlan, BuildRegistry::get, spotNotes);
                // ONE WISH, ONE OF IT. A rebuild under a NEW id left the first standing and
                // he had two volcanoes; refused before anything is saved or placed (NoDuplicate).
                String duplicate = run == null ? null
                        : com.moddingfromamod.marshal.NoDuplicate.rebuildRefusal(run.freshBuilds(),
                                plan.id(), inheriting(args), asNew, priorPlan != null);
                if (duplicate != null) {
                    return ToolResult.error(duplicate);
                }
                boolean fresh = priorPlan == null && !inheriting(args);
                Long priorAnchor = buildNow ? BuildRegistry.anchorOf(plan.id()) : null;
                // ADOPT WHAT THE REGISTRY STORED, not what we handed it. Refining a curated
                // or a brother's building forks it to `<id>~<his short uuid>`, and everything below
                // — the anchor, the wishbuild line, the drift critique — names an id. Keeping the
                // requested one would print a command that builds somebody else's building.
                BuildPlan stored = BuildRegistry.put(server, plan);
                if (stored != null) {
                    plan = stored;
                }
                if (fresh && run != null) {
                    run.builtFresh(plan.id());
                }
                // Publish the editable design + provenance so this build can be upgraded later.
                ProvenancePublisher.publish("build", plan.id(), "build.json",
                        plan.toStoredJson().toString());
                if (!buildNow) {
                    return ToolResult.ok("Saved building '" + plan.id() + "' ("
                            + plan.volume() + " blocks). Run it any time with wishbuild "
                            + plan.id() + "." + clampSuffix(clamped)
                            + com.moddingfromamod.builder.BuildLighting.note(lights, plan)
                            + spotsSuffix(plan, spotNotes)
                            + supportSuffix(faced, plan)
                            + critiqueFor(priorPlan, plan, built));
                }
                ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);

                // EDITING an existing building rebuilds it where it already stands. Otherwise
                // "give it crenellations" puts up a second castle next to the child, and they end
                // up with two — one of which is the old, wrong one.
                Long known = BuildRegistry.anchorOf(plan.id());
                boolean editing = known != null;

                // ...AND IN THE WORLD IT ALREADY STANDS IN. This tool is a hybrid, which is why it
                // survived the first sweep for this defect class (Phase 1): a NEW build is
                // entitled to the player's current level, but an EDIT is acting on a recorded
                // artifact and must use that artifact's own world. Using the player's level for
                // both meant building a castle in the Overworld, walking to the Nether and saying
                // "give it crenellations" cleared the castle's coordinates IN THE NETHER and put
                // the rebuilt castle there, while the real castle stood untouched and now stale.
                // Found by SourceRuleSweepTest, not by a person.
                ServerLevel level = editing
                        ? com.moddingfromamod.agent.ArtifactLevel.resolve(
                                server, BuildRegistry.dimensionOf(plan.id()))
                        : (player != null ? player.level() : server.overworld());
                // Land at the LOCKED manifest spot (where the watch aimed / conjure_at decided) so it
                // appears where it was promised, not wherever the child has since walked to.
                BlockPos locked = com.moddingfromamod.wish.WishProgress.manifestAnchor(player);
                BlockPos chosen = Builder.chooseAnchor(player, locked, forward, offsetY);
                // a NEW building put BY a thing he named stands beside / around / on it.
                BlockPos byThing = null;
                String byNote = "";
                if (!editing && !at.isEmpty()) {
                    var place = com.moddingfromamod.place.PlaceFinder.find(server, player, at, "beside");
                    int[] nb = com.moddingfromamod.builder.BuildSpots.bounds(plan);
                    if (!place.ok() || nb == null) {
                        return ToolResult.error("Saved '" + plan.id() + "' but did not put it up: it could not "
                                + "take " + at + ": " + (nb == null ? "it has no blocks" : place.why())
                                + ". Build it again with an at that stands, or none (then it goes where he aimed).");
                    }
                    var r = place.r();
                    boolean out = com.moddingfromamod.place.BuildBeside.outward(r.spot());
                    double feet = r.ring() > 0
                            ? place.feetAt(r.x() + r.ring(), r.z()) : place.feetAt(r.x(), r.z());
                    int[] a = com.moddingfromamod.place.BuildBeside.anchor(nb, r.x(), feet, r.z(),
                            place.ox(), place.oz(), out);
                    byThing = new BlockPos(a[0], a[1], a[2]);
                    level = place.level();
                    byNote = " It stands " + (out ? "by " : "at ") + r.link() + ".";
                }
                BlockPos anchor = editing ? BlockPos.of(known)
                        : byThing != null ? byThing
                        : chosen != null ? chosen
                        : Builder.fallbackAnchor(level, plan.id(), forward, offsetY);

                // P2: clear the PREVIOUS version of this building before the new one goes up, so a
                // shrunk/changed/moved rebuild leaves no orphan blocks. Non-destructive — only the old
                // plan's own positions are cleared, never terrain or anything added on top.
                if (priorPlan != null && priorAnchor != null) {
                    Builder.clearBuild(level, BlockPos.of(priorAnchor), priorPlan);
                }
                // WHAT IT STANDS ON: a footing, an island or a set-down, unless it was
                // floated on purpose — an offsetY above zero, or conjure_at's `up`. An edit keeps
                // what the building already had, and never moves.
                boolean floated = editing ? com.moddingfromamod.builder.BuildGround.wasFloated(plan.id())
                        : offsetY > 0 || (run != null && run.raised());
                // an edit of a building whose position is NOT known here (built before
                // positions were saved, or in another world) goes up where he aims — say so, so
                // the agent can tell him rather than let a second castle surprise him.
                String lostNote = !editing && priorPlan != null && priorPlan.id().equals(plan.id())
                        ? " (" + BuildRegistry.whyNotHere(plan.id()) + " So this one went up where "
                                + "he aimed; the old one, if it still stands, was not touched.)"
                        : "";
                String result = Builder.submit(level, anchor, plan, true,
                        new com.moddingfromamod.builder.BuildGround.Ground(floated, !editing), null)
                        + (editing ? " (Rebuilt in place, where it already stood."
                                + (at.isEmpty() ? "" : " Its at was not used: a change never moves it.") + ")"
                        : lostNote + (byThing != null ? byNote
                                : Builder.ignoresForward(locked, askedForward) ? Builder.FORWARD_IGNORED : ""));
                return ToolResult.ok(result + " Saved as '" + plan.id()
                        + "' — a template can rebuild it instantly with: wishbuild " + plan.id()
                        + clampSuffix(clamped)
                        + com.moddingfromamod.builder.BuildLighting.note(lights, plan)
                        + spotsSuffix(plan, spotNotes)
                        + supportSuffix(faced, plan)
                        + critiqueFor(priorPlan, plan, built));
            }, 15_000L);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * What will not stand: the support check on the RESOLVED building, plus what the
     * auto-facing did. A function, like {@link #critiqueFor}, so a test can assert the warning
     * really reaches the agent's result rather than only the class that computes it.
     */
    static String supportSuffix(com.moddingfromamod.builder.BuildSupport.Faced faced, BuildPlan plan) {
        return com.moddingfromamod.builder.BuildSupport.note(faced,
                com.moddingfromamod.builder.BuildSupport.check(plan),
                com.moddingfromamod.builder.BuildSupport.groundOnlyScaffolding(plan));
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String strOr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    /** Building ids already asked "is this a machine?" — asked once, then it goes through. */
    private static final java.util.Set<String> ASKED_MACHINE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The wish this worker is granting, or null outside one (a direct call, a replay, a test). */
    private static String boundWishText() {
        var run = boundRun();
        return run == null ? null : run.text();
    }

    /**
     * The run this worker is BOUND to, or null — never the ambient "single live run" fallback,
     * which would let a neighbour's wish refuse this call (MakeItemTool's boundWishText, measured).
     */
    static com.moddingfromamod.marshal.WishRun boundRun() {
        String worker = com.moddingfromamod.agent.AgentContext.workerId();
        if (worker == null || worker.isEmpty()) {
            return null;
        }
        return com.moddingfromamod.marshal.Marshal.global().runForWorker(worker);
    }
}
