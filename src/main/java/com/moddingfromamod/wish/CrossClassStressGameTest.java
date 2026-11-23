package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.BuildShape;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Two wishes in one world.</b>
 *
 * <p>Every gate in this repo builds ONE artifact in an EMPTY plot, and real play never looks like
 * that. A castle gets a train station beside it; a machine goes inside the building that was put up
 * a minute ago; the same name gets used twice because a seven-year-old calls everything "castle".
 * The seams between wish classes are therefore completely untested, and seams are where anchor
 * collisions, footprint overlap, name reuse and interleaved time-sliced builds live.
 *
 * <p>These are deliberately the <i>cheap</i> cross-class checks — no Create, no agent, no client —
 * so they can run in the ordinary gate rather than being a thing someone remembers to do.
 */
@GameTestHolder("moddingfromamod")
public class CrossClassStressGameTest {

    /** A small solid box, so "did it survive?" is a simple block check. */
    private static BuildPlan boxPlan(String id, int w, int h, int d, String block) {
        return new BuildPlan(id, id, List.of(
                new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, w, h, d, block, false)));
    }

    /**
     * <b>The same name for two different kinds of thing.</b>
     *
     * <p>A child names everything the same. "Castle" is a building; ten minutes later "castle" is
     * also the creature that guards it. If the stores collide, the second wish silently destroys the
     * first — and the failure surfaces as "my castle disappeared", which nobody would connect to a
     * creature. (Until the second thing was a Create machine.)
     */
    @GameTest(template = "empty_test", timeoutTicks = 300, batch = "crossclass")
    public void thesameNameInTwoWishClassesDoesNotCollide(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String shared = "castle";

        BuildPlan building = boxPlan(shared, 3, 3, 3, "minecraft:stone_bricks");
        BuildRegistry.put(server, building);

        com.google.gson.JsonObject c = new com.google.gson.JsonObject();
        c.addProperty("id", shared);
        c.addProperty("name", "Castle Guard");
        c.addProperty("body", "HUMANOID");
        c.addProperty("texture", Species.DEFAULT_TEXTURE);
        c.addProperty("scale", 1.0);
        c.addProperty("health", 20.0);
        c.addProperty("base", "WILD");
        c.add("powers", new com.google.gson.JsonArray());
        Species creature = Species.fromJson(c);
        if (creature == null) {
            helper.fail(Component.literal("the test's own creature did not parse, so this checks nothing"));
            return;
        }
        com.moddingfromamod.testing.SpeciesForTest.put(helper, creature);

        BuildPlan backBuilding = BuildRegistry.get(shared);

        if (backBuilding == null) {
            helper.fail(Component.literal("the BUILDING called '" + shared + "' vanished when a CREATURE was saved "
                    + "under the same name. A child names everything the same; the stores must be "
                    + "separate or one wish quietly destroys another."));
            return;
        }
        if (!SpeciesRegistry.has(shared)) {
            helper.fail(Component.literal("the CREATURE called '" + shared + "' was not saved beside the building of "
                    + "the same name"));
            return;
        }
        if (backBuilding.shapes().isEmpty()) {
            helper.fail(Component.literal("the building survived by name but lost its shapes"));
            return;
        }
        System.out.println("CROSS-CLASS: a building and a creature can share the name '" + shared
                + "' without one destroying the other ✓");
        helper.succeed();
    }

    /**
     * <b>Removing one artifact must not remove the one standing inside it.</b>
     *
     * <p>Guardrail P5 says removal is non-destructive — "clears only the creation's own blocks,
     * never terrain". A machine placed inside a building is not terrain, but it is just as much
     * not-yours: taking the building away must leave the machine standing, or "put my crusher in
     * the castle" becomes a way to lose the crusher.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "crossclass")
    public void removingABuildingLeavesTheMachineInsideIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 2, 1));

        // A hollow room, and a machine block standing in the middle of it.
        BuildPlan room = new BuildPlan("shell", "Shell", List.of(
                new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 5, 4, 5,
                        "minecraft:stone_bricks", true)));
        BuildRegistry.put(server, room);
        Builder.submit(level, anchor, room);

        BlockPos machineAt = anchor.offset(2, 1, 2);

        helper.runAfterDelay(40, () -> {
            // Placed AFTER the room is up, exactly as "now put a machine in it" would.
            level.setBlock(machineAt, Blocks.IRON_BLOCK.defaultBlockState(), 3);
            if (!level.getBlockState(anchor).is(Blocks.STONE_BRICKS)) {
                helper.fail(Component.literal("the room never went up, so this proves nothing"));
                return;
            }
            Builder.clearBuild(level, anchor, room);
        });

        helper.runAfterDelay(120, () -> {
            if (level.getBlockState(anchor).is(Blocks.STONE_BRICKS)) {
                helper.fail(Component.literal("the room was not removed, so the interesting half never happened"));
                return;
            }
            if (!level.getBlockState(machineAt).is(Blocks.IRON_BLOCK)) {
                helper.fail(Component.literal("removing the BUILDING also destroyed the MACHINE standing inside it. "
                        + "P5 says removal clears only the creation's own blocks — a machine put "
                        + "inside a castle is not part of the castle, and losing it to \"get rid "
                        + "of the castle\" is exactly the orphan-avoidance rule eating a real "
                        + "creation."));
                return;
            }
            System.out.println("CROSS-CLASS: removing a building left the machine inside it "
                    + "standing ✓");
            helper.succeed();
        });
    }

    /**
     * <b>Two time-sliced builds queued at once must both come out whole.</b>
     *
     * <p>{@code Builder} drains a shared job queue at {@code BLOCKS_PER_TICK}, so two wishes
     * granted seconds apart are interleaved block-by-block rather than run one after the other.
     * That is fine if and only if their footprints are disjoint — and nothing has ever checked it.
     * A real mod repo learned the equivalent lesson the hard way and now has slice-equivalence tests
     * for every big builder.
     */
    @GameTest(template = "empty_test", timeoutTicks = 500, batch = "crossclass")
    public void twoBuildsQueuedTogetherBothComeOutWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos b = helper.absolutePos(new BlockPos(1, 2, 8));

        BuildPlan first = boxPlan("stress_a", 4, 3, 4, "minecraft:stone_bricks");
        BuildPlan second = boxPlan("stress_b", 4, 3, 4, "minecraft:oak_planks");

        // Queued in the SAME tick, which is what "grant two wishes quickly" produces.
        Builder.submit(level, a, first);
        Builder.submit(level, b, second);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(Builder.isBusy(),
                        Component.literal("the builder was still draining after 8 seconds")))
                .thenExecute(() -> {
            int missingA = countMissing(level, a, 4, 3, 4, Blocks.STONE_BRICKS);
            int missingB = countMissing(level, b, 4, 3, 4, Blocks.OAK_PLANKS);
            if (missingA > 0 || missingB > 0) {
                helper.fail(Component.literal("two builds queued together did NOT both come out whole: "
                        + missingA + " block(s) missing from the first, " + missingB
                        + " from the second. The builder drains one shared queue across ticks, so "
                        + "granting two wishes seconds apart interleaves them — and a wish that "
                        + "comes out with holes reads to a child as a broken build, not as a "
                        + "scheduling bug."));
                return;
            }
            System.out.println("CROSS-CLASS: two time-sliced builds queued together both came out "
                    + "whole ✓");
            helper.succeed();
        });
    }

    /**
     * <b>Rebuilding the same id twice leaves ONE artifact, not two.</b>
     *
     * <p>Guardrail P2. Checked here at a MOVED anchor, which is the case that actually bites: "make
     * it bigger" grants the same id somewhere else, and if the old footprint is not cleared the
     * child ends up with two, one of which is the wrong one.
     */
    @GameTest(template = "empty_test", timeoutTicks = 500, batch = "crossclass")
    public void rebuildingAtANewSpotDoesNotLeaveTheOldOneStanding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos first = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos moved = helper.absolutePos(new BlockPos(1, 2, 9));

        BuildPlan plan = boxPlan("mover", 3, 3, 3, "minecraft:stone_bricks");
        BuildRegistry.put(server, plan);
        Builder.submit(level, first, plan);

        helper.runAfterDelay(60, () -> {
            if (!level.getBlockState(first).is(Blocks.STONE_BRICKS)) {
                helper.fail(Component.literal("the first copy never went up"));
                return;
            }
            // The P2 obligation: clear the previous one, then put it up in its new place.
            Builder.clearBuild(level, first, plan);
            Builder.submit(level, moved, plan);
        });

        helper.runAfterDelay(180, () -> {
            boolean oldStanding = level.getBlockState(first).is(Blocks.STONE_BRICKS);
            boolean newStanding = level.getBlockState(moved).is(Blocks.STONE_BRICKS);
            if (!newStanding) {
                helper.fail(Component.literal("the rebuilt copy is not at its new spot"));
                return;
            }
            if (oldStanding) {
                helper.fail(Component.literal("rebuilding at a new spot left the OLD copy standing — the child now "
                        + "has two, and one of them is the version they rejected (guardrail P2)"));
                return;
            }
            System.out.println("CROSS-CLASS: rebuilding at a new spot left exactly one copy ✓");
            helper.succeed();
        });
    }

    /** How many blocks of an expected solid box are missing. */
    private static int countMissing(ServerLevel level, BlockPos origin, int w, int h, int d,
                                    net.minecraft.world.level.block.Block expected) {
        int missing = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    if (!level.getBlockState(origin.offset(x, y, z)).is(expected)) {
                        missing++;
                    }
                }
            }
        }
        return missing;
    }
}
