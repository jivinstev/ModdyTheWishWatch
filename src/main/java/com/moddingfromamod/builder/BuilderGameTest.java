package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for the Builder: the claims only a running server can settle.
 *
 * <p>Gate A proves the geometry. What it cannot prove is that the blocks actually reach the world,
 * that a building is <b>drip-fed rather than placed in one tick</b>, and that a plan saved to disk
 * rebuilds identically — which is the whole basis for a learned template being able to re-run it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BuilderGameTest {

    private static BuildPlan plan(String json) {
        return BuildPlan.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    /** Blocks really land, and the hollow room really is hollow. */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void aBuildingActuallyAppears(GameTestHelper helper) {
        BuildPlan hut = plan("""
            {"id":"gt_hut","name":"Hut","shapes":[
              {"kind":"box","width":5,"height":4,"depth":5,
               "block":"minecraft:stone_bricks","hollow":true}]}
            """);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1));
        Builder.submit(helper.getLevel(), anchor, hut);

        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBusy(), Component.literal("waiting for the build to finish"));
            helper.assertTrue(
                    helper.getLevel().getBlockState(anchor).is(Blocks.STONE_BRICKS),
                    Component.literal("the corner should be wall"));
            helper.assertTrue(
                    helper.getLevel().getBlockState(anchor.offset(2, 2, 2)).isAir(),
                    Component.literal("the inside of a hollow building must be a ROOM, not solid rock"));
        });
    }

    /**
     * <b>The one that matters.</b> A big build must be spread across ticks, not dumped in one.
     * Placing half a million blocks in a single tick freezes the server for seconds and can OOM a
     * modest heap — the reason this class exists at all rather than a plain loop.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void abigBuildIsSpreadAcrossTicksNotDumpedAtOnce(GameTestHelper helper) {
        // Comfortably more than one tick's budget, so it MUST take more than one tick.
        int side = 40;
        BuildPlan big = plan("""
            {"id":"gt_big","name":"Big","shapes":[
              {"kind":"box","width":%d,"height":%d,"depth":%d,"block":"minecraft:stone"}]}
            """.formatted(side, side, side));
        helper.assertTrue(big.volume() > Builder.BLOCKS_PER_TICK,
                Component.literal("the fixture must exceed one tick's budget or this test proves nothing"));
        // Build high ABOVE the test plots. A 40-cube is far bigger than this test's own structure, so
        // at ground level it spills into whatever plot is next door — and GameTest lays batches out in
        // X/Z, so which plot that is shifts whenever anyone adds a test. It landed on the hollow-hut
        // test and filled its room with solid stone. A Y offset cannot collide with a neighbour.
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).above(120);
        Builder.submit(helper.getLevel(), anchor, big);

        // Still queued right after submitting: nothing was placed synchronously.
        helper.assertTrue(Builder.isBusy(),
                Component.literal("submit() must QUEUE the work, not place it inline on the calling tick"));
        helper.succeedWhen(() -> helper.assertFalse(Builder.isBusy(), Component.literal("waiting for the big build")));
    }

    /** An over-budget plan is refused outright rather than half-built. */
    @GameTest(template = "empty_test")
    public static void anOversizedPlanIsRefused(GameTestHelper helper) {
        StringBuilder shapes = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            shapes.append(i == 0 ? "" : ",")
                  .append("{\"kind\":\"box\",\"width\":96,\"height\":96,\"depth\":96}");
        }
        BuildPlan huge = plan("{\"id\":\"gt_huge\",\"shapes\":[" + shapes + "]}");
        String result = Builder.submit(helper.getLevel(),
                helper.absolutePos(new BlockPos(1, 1, 1)), huge);
        helper.assertTrue(result.contains("too big"), Component.literal("should refuse, said: " + result));
        // Deliberately NOT asserting on global busy-ness: other tests in this batch have builds in
        // flight, and this test's claim is about the refusal, not about the shared queue.
        helper.succeed();
    }

    /** A block id nobody has must not leave the building full of holes. */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void anUnknownBlockFallsBackAndSaysSo(GameTestHelper helper) {
        BuildPlan odd = plan("""
            {"id":"gt_odd","name":"Odd","shapes":[
              {"kind":"box","width":2,"height":2,"depth":2,"block":"minecraft:not_a_real_block"}]}
            """);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1));
        String result = Builder.submit(helper.getLevel(), anchor, odd);
        helper.assertTrue(result.contains("didn't recognise"),
                Component.literal("the agent must be told, not left guessing: " + result));
        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBusy(), Component.literal("waiting"));
            helper.assertFalse(helper.getLevel().getBlockState(anchor).isAir(),
                    Component.literal("a bad block id must fall back to something solid, not leave a hole"));
        });
    }

    /**
     * A saved plan must rebuild identically — this is the entire basis for a learned template being
     * able to run {@code wishbuild <id>} and get the same castle.
     */
    // ITS OWN BATCH: BuildRegistry.load() clears every plan in memory and reads back only what the
    // shelves hold — and a GameTest plan with no owner is never written there. So a reload mid-batch
    // deleted a neighbour's plan between its put and its use ("no saved design under that id",
    // BuildingPhotoGameTest, twice in four runs on 2026-09-27).
    @GameTest(template = "empty_test", batch = "buildreload")
    public static void aSavedPlanSurvivesAndRebuildsTheSame(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        BuildPlan original = plan("""
            {"id":"gt_saved","name":"Saved Castle","shapes":[
              {"kind":"cylinder","width":9,"height":12,"depth":9,
               "block":"minecraft:stone_bricks","hollow":true},
              {"kind":"pyramid","dy":12,"width":9,"height":5,"depth":9,
               "block":"minecraft:deepslate_tiles"}]}
            """);
        BuildRegistry.put(server, original);
        BuildRegistry.load(server);   // exactly what happens on the next world load

        BuildPlan back = BuildRegistry.get("gt_saved");
        helper.assertTrue(back != null, Component.literal("the building should have been written to the world"));
        helper.assertTrue(back.volume() == original.volume(),
                Component.literal("a reloaded building must be the SAME size (" + back.volume()
                        + " vs " + original.volume() + ")"));
        helper.assertTrue(back.shapes().size() == 2 && back.shapes().get(0).hollow(),
                Component.literal("and keep its shapes, including hollow-ness"));
        helper.succeed();
    }
}
