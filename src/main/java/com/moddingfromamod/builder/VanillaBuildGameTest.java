package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.BuildStructureTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Buildings on a client with no mod: the claims that need a running world.</b>
 *
 * <p>Brief:.
 * Results: {@code docs/VANILLA_BUILDING_SPIKE_RESULTS.md}. Companion:
 * {@code VanillaBuildParityTest}, which settles the same programme's pure claims in milliseconds.
 *
 * <h2>Why the world half cannot be skipped</h2>
 * The pure suite proves that <b>no plan names a non-vanilla block</b> and that <b>no build-path
 * class sends a payload</b>. Neither is the same claim as <b>"the blocks that end up in the world
 * are ones a stock client can render"</b>, and the difference is the whole of §4's most productive
 * rule: <i>a test that proves a mechanism says nothing about the wiring.</i> A plan is a list of
 * strings; what a child sees is whatever {@code ServerLevel.setBlock} actually put there, after the
 * lighting backstop has added shapes the plan never had and after {@code Builder.resolve} has
 * substituted for anything it could not look up.
 *
 * <h2>Every call is made off the server thread, and that is the production path</h2>
 * {@code AgentBridge.onServer} refuses to run on the server thread ("that would deadlock"), and the
 * real agent reaches this tool from an HTTP thread. So the background thread plus {@code succeedWhen}
 * polling below is not a workaround — driving {@link Builder} directly instead would test everything
 * except whether the tool uses it, which is exactly the gap §3.C.14 says to close. Copied
 * deliberately from {@code BuildEditGameTest}, which paid for the pattern.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class VanillaBuildGameTest {

    /**
     * Its OWN BATCH, for the same reason {@code BuildEditGameTest} has one.
     *
     * <p>These tests drive the real tool from a background thread, and the tool writes
     * {@code ProvenanceContext.note}, which is a process-global {@code static volatile}. Sharing a
     * batch let those threads clear a sibling's design note mid-assertion, and it failed as a
     * DATAPACK test complaining about manifest intent — a message with nothing about buildings in
     * it. Batches run sequentially; the tests inside one run together.
     */
    private static final String BATCH = "vanillaBuild";

    /** The only namespace a stock 1.21.1 client can render. */
    private static final String VANILLA = "minecraft";

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Run one real tool call off the server thread; the result lands in the reference. */
    private static AtomicReference<ToolResult> callAsync(String json) {
        AtomicReference<ToolResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                out.set(new BuildStructureTool().call(args(json)));
            } catch (RuntimeException e) {
                out.set(ToolResult.error("threw: " + e));
            }
        }, "vanilla-build-gametest");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /**
     * <b>The fixture, and it is shared with {@code VanillaBuildParityTest} on purpose.</b>
     *
     * <p>A hall with a doorway, sized so the lighting backstop really fires — which is what puts a
     * block <i>the agent never named</i> into the census, and therefore the only reason the census
     * covers all three sources rather than two. The precondition is asserted in the PURE suite
     * ({@code theSharedFixtureReallyTriggersTheLightingBackstop}, milliseconds) as well as here, so
     * a fixture that stops triggering it is caught by a unit test rather than by a red Gate B whose
     * message is about lighting and whose subject is namespaces. It has already been caught once:
     * a 9×6×9 hall with the same doorway produces <b>zero</b> lanterns, because
     * {@code BuildLighting.enclosed} rejects every sampled spot on the doorway's own ray and a small
     * room has nothing else to sample.
     *
     * <p>It keeps its doorway rather than being a sealed box. records that 13 of the 19 real
     * buildings enclose a room with no way in; that is a live quality defect on every path and not
     * this spike's to fix, but a fixture that reproduces it would make the census a measurement of
     * the bug.
     */
    static String hut(String id, String block) {
        return ("""
            {"id":"$ID","name":"Hall","note":"vanilla-compat spike fixture","shapes":[
              {"kind":"box","width":15,"height":8,"depth":15,"block":"$BLOCK",
               "hollow":true,"name":"walls"},
              {"kind":"box","dx":7,"dy":1,"dz":0,"width":1,"height":2,"depth":1,
               "block":"minecraft:air","name":"doorway"}]}
            """).replace("$ID", id).replace("$BLOCK", block);
    }

    /**
     * The world the building went up in, as the registry recorded it.: a NEW build goes to the
     * child's level, and in a full run "the child" is whichever mock is first in the player list — once
     * one in another world, so the census of the overworld read "0 of 788 placed" for 600 ticks.
     */
    private static ServerLevel builtIn(GameTestHelper helper, String id) {
        return com.moddingfromamod.agent.ArtifactLevel.resolve(helper.getLevel().getServer(),
                BuildRegistry.dimensionOf(id));
    }

    /** What the tool really built, read back out of the world at the anchor it recorded. */
    private record Census(int blocks, TreeMap<String, Integer> byNamespace, List<String> foreign,
                          int modBlockEntities) {
    }

    /**
     * How many positions this plan should end up holding a block at.
     *
     * <p>Counted from the plan rather than from {@link BuildPlan#volume()} because a plan's own air
     * shapes (a doorway, a hollowed core) are positions it owns and deliberately leaves empty. A
     * "wait until the world holds `volume()` blocks" would therefore never be satisfied, and the
     * test would spend its whole timeout waiting for a building that was already finished.
     *
     * <p>⚠ It is an UPPER bound on distinct positions rather than an exact count: later shapes
     * overwrite earlier ones, so two shapes crossing the same square are counted once here only
     * because the sink is deduplicated below. That is enough for a "has it finished" gate, which is
     * all it is used for; the census itself is exact.
     */
    private static int expectedPlacements(BuildPlan plan) {
        java.util.Set<Long> solid = new java.util.HashSet<>();
        java.util.Set<Long> air = new java.util.HashSet<>();
        plan.forEachBlock((x, y, z, block) -> {
            long key = BlockPos.asLong(x, y, z);
            if ("minecraft:air".equals(block) || "air".equals(block)) {
                air.add(key);
                solid.remove(key);
            } else {
                air.remove(key);
                solid.add(key);
            }
        });
        return solid.size();
    }

    /**
     * Read every position this plan owns and report what is actually there.
     *
     * <p>Scanning the plan's own positions rather than a guessed bounding box is what makes this
     * exact: it can neither miss a block the plan placed nor accuse the build of terrain that was
     * already there. Air is skipped — a doorway is a position the plan owns and deliberately leaves
     * empty, and counting it as a placement would make the census a measure of the fixture.
     */
    private static Census censusOf(ServerLevel level, BlockPos anchor, BuildPlan plan) {
        TreeMap<String, Integer> byNamespace = new TreeMap<>();
        List<String> foreign = new java.util.ArrayList<>();
        int[] blocks = {0};
        int[] modBes = {0};
        plan.forEachBlock((x, y, z, block) -> {
            BlockPos at = anchor.offset(x, y, z);
            BlockState state = level.getBlockState(at);
            if (state.isAir()) {
                return;
            }
            blocks[0]++;
            String ns = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace();
            byNamespace.merge(ns, 1, Integer::sum);
            if (!VANILLA.equals(ns) && foreign.size() < 8) {
                foreign.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + at);
            }
            var be = level.getBlockEntity(at);
            if (be != null && !VANILLA.equals(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).getNamespace())) {
                modBes[0]++;
            }
        });
        return new Census(blocks[0], byNamespace, foreign, modBes[0]);
    }

    /**
     * <b>THE ROW THE CLASSIFICATION TURNS ON.</b> A building put up by the real tool contains
     * nothing but blocks a stock client already has.
     *
     * <p>Reported as a census rather than a boolean, because "no mod blocks" and "no mod blocks out
     * of 400 placements across three namespace-eligible sources" are different claims and only the
     * second says the measurement had anything to look at. The three sources it covers, which is
     * why the fixture is shaped the way it is: the blocks the AGENT named, the doorway air it named,
     * and the lantern {@link BuildLighting} hangs by itself in a room that would otherwise be dark.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = BATCH)
    public static void aRealBuildingContainsOnlyBlocksAStockClientCanRender(GameTestHelper helper) {
        AtomicReference<ToolResult> made = callAsync(hut("gt_vanilla_hut", "minecraft:stone_bricks"));
        helper.succeedWhen(() -> {
            helper.assertTrue(made.get() != null, Component.literal("waiting for the build tool"));
            helper.assertFalse(made.get().isError(),
                    Component.literal("the tool refused the fixture: " + made.get().text()));
            BuildPlan plan = BuildRegistry.get("gt_vanilla_hut");
            helper.assertTrue(plan != null, Component.literal("the building should have been saved"));
            Long anchor = BuildRegistry.anchorOf("gt_vanilla_hut");
            helper.assertTrue(anchor != null, Component.literal("the building should have recorded where it went"));

            ServerLevel level = builtIn(helper, "gt_vanilla_hut");
            Census c = censusOf(level, BlockPos.of(anchor), plan);
            // Wait for the drip-feed rather than racing it: Builder places 8k blocks a tick, and a
            // half-placed building would report a census that is true and useless.
            helper.assertTrue(c.blocks() >= expectedPlacements(plan),
                    Component.literal("waiting for the build to finish placing (" + c.blocks() + " of "
                            + expectedPlacements(plan) + " placed)"));

            helper.assertTrue(c.foreign().isEmpty(),
                    Component.literal("a building contains a block a stock client cannot render, so buildings are "
                            + "not path 1: " + c.foreign()));
            helper.assertTrue(c.modBlockEntities() == 0,
                    Component.literal("a building placed " + c.modBlockEntities() + " mod block entities; a stock "
                            + "client has no such type and cannot receive its update packet"));
            // The lighting backstop must really have fired, or a third of the claim is untested.
            helper.assertTrue(plan.shapes().stream()
                            .anyMatch(s -> s.name() != null && s.name().startsWith("light_")),
                    Component.literal("the fixture must trigger the lighting backstop, or this census never sees a "
                            + "block the agent did not name"));
            System.out.println("[vanilla-buildings] gt_vanilla_hut: " + c.blocks()
                    + " blocks placed, namespaces " + c.byNamespace());
        });
    }

    /**
     * <b>Verify the world scan to FAIL (§3.C.11).</b> ...and record the one door the contract
     * genuinely leaves open.
     *
     * <p>The census above is a green tick over a building that happens to be clean. This drives the
     * same real tool with a plan naming <b>the one block this mod registers</b> and asserts the scan
     * sees it — so a later refactor that broke the namespace read would fail here rather than
     * turning the row above into a rubber stamp.
     *
     * <p><b>It is also the finding.</b> {@code build_structure}'s {@code block} field is documented
     * as "any block id" and {@link Builder} resolves any key in the block registry, so a mod block
     * in a building is <i>permitted</i> even though the 19 real builds contain none. That is the
     * difference between the grep this spike was asked to replace and the contract §2 rule 1 says to
     * enumerate from. <b>Not fixed here:</b> narrowing the field would take an affordance away from
     * a modded server to serve a stock one, which is a product decision, and §0.5 forbids making it
     * per-path.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = BATCH)
    public static void namingAModBlockReallyPlacesOneAndTheScanSeesIt(GameTestHelper helper) {
        AtomicReference<ToolResult> made =
                callAsync(hut("gt_vanilla_modblock", "moddingfromamod:paint_panel"));
        helper.succeedWhen(() -> {
            helper.assertTrue(made.get() != null, Component.literal("waiting for the build tool"));
            BuildPlan plan = BuildRegistry.get("gt_vanilla_modblock");
            helper.assertTrue(plan != null, Component.literal("the building should have been saved"));
            Long anchor = BuildRegistry.anchorOf("gt_vanilla_modblock");
            helper.assertTrue(anchor != null, Component.literal("the building should have recorded where it went"));

            ServerLevel level = builtIn(helper, "gt_vanilla_modblock");
            Census c = censusOf(level, BlockPos.of(anchor), plan);
            helper.assertTrue(c.blocks() >= expectedPlacements(plan),
                    Component.literal("waiting for the build to finish placing (" + c.blocks() + " of "
                            + expectedPlacements(plan) + ")"));
            helper.assertFalse(c.foreign().isEmpty(),
                    Component.literal("the world scan did not see a mod block in a building made ENTIRELY of them, "
                            + "so the census in the sibling test cannot fail and proves nothing. "
                            + "Namespaces seen: " + c.byNamespace()));
            System.out.println("[vanilla-buildings] gt_vanilla_modblock: namespaces "
                    + c.byNamespace() + " — the contract permits this, no real build does it");

            // Leave the world as we found it. A GameTest world is reused and never reset, so a
            // block entity left behind outlives every future run — and this one is the mod block
            // the sibling test asserts the ABSENCE of.
            Builder.clearBuild(level, BlockPos.of(anchor), plan);
        });
    }

    /**
     * <b>The negative (§3.C.15): a building touches only its own positions.</b>
     *
     * <p>Buildings have no route to switch off, so "the route is off when disabled" has no meaning
     * here — the negative that does is that an ordinary vanilla world is left alone. A marker is
     * placed one block outside the plan's footprint before the build and read back afterwards;
     * without it, "no mod blocks in the building" would be equally consistent with a builder that
     * had quietly cleared half the map.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = BATCH)
    public static void aBuildLeavesTheWorldAroundItAlone(GameTestHelper helper) {
        AtomicReference<ToolResult> made = callAsync(hut("gt_vanilla_around", "minecraft:stone_bricks"));
        AtomicReference<BlockPos> marker = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertTrue(made.get() != null, Component.literal("waiting for the build tool"));
            BuildPlan plan = BuildRegistry.get("gt_vanilla_around");
            helper.assertTrue(plan != null, Component.literal("the building should have been saved"));
            Long anchor = BuildRegistry.anchorOf("gt_vanilla_around");
            helper.assertTrue(anchor != null, Component.literal("the building should have recorded where it went"));
            ServerLevel level = builtIn(helper, "gt_vanilla_around");

            if (marker.get() == null) {
                // Two blocks clear of a 9-wide hut on every axis, so it is unambiguously outside.
                marker.set(BlockPos.of(anchor).offset(-2, 0, -2));
                level.setBlockAndUpdate(marker.get(), Blocks.GOLD_BLOCK.defaultBlockState());
            }
            Census c = censusOf(level, BlockPos.of(anchor), plan);
            helper.assertTrue(c.blocks() >= expectedPlacements(plan),
                    Component.literal("waiting for the build to finish placing (" + c.blocks() + " of "
                            + expectedPlacements(plan) + ")"));
            helper.assertTrue(level.getBlockState(marker.get()).is(Blocks.GOLD_BLOCK),
                    Component.literal("the build overwrote a block outside its own footprint at " + marker.get()
                            + ", so 'only its own positions' is not true"));
            level.setBlockAndUpdate(marker.get(), Blocks.AIR.defaultBlockState());
        });
    }
}
