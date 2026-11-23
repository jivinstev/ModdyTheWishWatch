package com.moddingfromamod.wish.stress;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.builder.BuildGeometry;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildShape;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.wish.WishCorpus;
import net.minecraft.core.BlockPos;

/**
 * Stresses {@code kind=buildings}: shapes a child actually asked for, from a castle with a
 * drawbridge to a runway to a flat-topped mesa.
 *
 * <p>Deliberately checks the caps as well as the successes. A cap that changes the wish without
 * saying so is the defect that shipped 96-block towers to a child who asked for 150.
 */
public final class BuildingsHarness implements StressHarness {

    @Override
    public String kind() {
        return "buildings";
    }

    @Override
    public int settleTicks() {
        return 60;   // the builder is time-sliced at 8000 blocks/tick
    }

    @Override
    public Outcome run(Context ctx, WishCorpus.Case c) {
        Outcome.Builder out = new Outcome.Builder();
        JsonObject json = specAsPlanJson(c);
        BuildPlan plan = BuildPlan.fromJson(json);
        if (plan == null) {
            return new Outcome(List.of("could not build a plan from the case spec"), List.of());
        }

        // ── the cap cases: the wish must be changed LOUDLY or not at all
        List<BuildPlan.Requested> asked = BuildPlan.shapesAsRequested(json);
        boolean expectsRefusal = c.flag("refused");
        boolean expectsClampNote = c.flag("clampReported");
        if (expectsRefusal) {
            return out.require(Invariants.NO_SILENT_CAPS, plan.droppedShapes(asked),
                    "this case asks for more shapes than the limit and must be REFUSED — silently "
                            + "dropping the last shapes means the roof and the detail vanish while "
                            + "the agent reports success").build();
        }
        if (expectsClampNote) {
            return out.require(Invariants.NO_SILENT_CAPS, !plan.clampNotes(asked).isEmpty(),
                    "this case asks for something past a limit and was quietly changed instead of "
                            + "reported: asking for a 150-block tower and silently building 96 "
                            + "reads to a child as being ignored").build();
        }

        // ── geometry: a hollow room must actually be a room, and a tower must not be a lidded box
        BuildGeometry geometry = BuildGeometry.of(plan);
        out.checked(Invariants.BUILDINGS_HAVE_FOUR_WALLS);
        for (BuildGeometry.Room room : geometry.rooms()) {
            List<String> missing = room.missingSides(BuildGeometry.WALL_MISSING);
            if (!missing.isEmpty()) {
                out.problem(Invariants.BUILDINGS_HAVE_FOUR_WALLS + ": shape " + room.shapeIndex()
                        + " is a room with no " + String.join(" or ", missing) + " wall — a child "
                        + "walks round the back of it and there is nothing there. (A doorway is "
                        + "fine; these sides are below " + BuildGeometry.WALL_MISSING
                        + " coverage, and on real output the gap between a door and a missing "
                        + "wall is 0.70 to 0.43.)");
            }
        }
        out.require(Invariants.BUILDINGS_ARE_FINISHED, !geometry.looksUnfinished(),
                "this is " + geometry.height() + " tall on a span of " + geometry.span()
                        + " with a solid lid across "
                        + Math.round(geometry.topCoverage() * 100) + "% of its footprint — from "
                        + "the air it reads as an unfinished box rather than a building");

        // ── it lands, it comes back up, and it spares what it did not place
        List<BlockPos> footprint = footprint(plan, ctx.anchor());
        if (footprint.isEmpty()) {
            return out.problem("the plan places no blocks at all").build();
        }
        BlockPos witnessAt = ctx.anchor().offset(-2, -1, -2);
        Invariants.Witness witness = Invariants.witness(ctx.level(), witnessAt);

        String submitted = Builder.submit(ctx.level(), ctx.anchor(), plan);
        Builder.drainNow(ctx.level());   // the harness cannot wait for ticks; drain deterministically

        if (!plan.fitsBudget()) {
            // Over the block budget is a legitimate refusal — but it must SAY so. A build that
            // quietly does nothing is indistinguishable, to a child, from one that broke.
            return out.require(Invariants.NO_SILENT_CAPS,
                    submitted != null && submitted.toLowerCase(java.util.Locale.ROOT)
                            .contains("too big"),
                    "a build of " + plan.volume() + " blocks is over the " + BuildPlan.MAX_BLOCKS
                            + " limit and was not refused in words; it just did not happen")
                    .build();
        }

        int placed = Invariants.occupied(ctx.level(), footprint);
        out.require(Invariants.MATERIALISES, placed > 0,
                "nothing was placed anywhere in the plan's own footprint");

        Builder.clearBuild(ctx.level(), ctx.anchor(), plan);
        int left = Invariants.occupied(ctx.level(), footprint);
        out.require(Invariants.P5_REMOVES_CLEAN, left == 0,
                left + " of " + footprint.size() + " block(s) survived removal — orphans "
                        + "accumulate forever");
        out.require(Invariants.P5_NON_DESTRUCTIVE, witness.survived(), witness.complaint());

        // tidy the witness so the next case sees a clean world
        ctx.level().setBlock(witnessAt, net.minecraft.world.level.block.Blocks.AIR
                .defaultBlockState(), 3);
        return out.build();
    }

    private static List<BlockPos> footprint(BuildPlan plan, BlockPos anchor) {
        List<BlockPos> out = new ArrayList<>();
        for (BuildShape s : plan.shapes()) {
            s.forEachBlock((x, y, z) -> out.add(anchor.offset(x, y, z)));
        }
        return out;
    }

    private static JsonObject specAsPlanJson(WishCorpus.Case c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", "stress_" + c.id());
        o.addProperty("name", c.id());
        if (c.spec().has("shapes")) {
            o.add("shapes", c.spec().get("shapes"));
        }
        return o;
    }
}
