package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>The working builds WORK</b>, tested where they run: a shipped world's own menus pressed ("A building", then the
 * type: no size, nothing outside), {@link BuilderMake#run} through the REAL {@code build_structure}, and then the WORLD
 * is read, never a verdict:
 * <ul>
 *   <li>the farm plot's soil is farmland and STAYS farmland by its water: every farmland is random-ticked and comes
 *       back fully wet (moisture 7). The control, a farmland with no water and no crop, dries back to dirt;</li>
 *   <li>the animal pen's grass is closed in by its fence, with a gate: walking out from the middle over open ground
 *       never leaves the pen, and every step inside is on grass;</li>
 *   <li>the tree nursery's saplings stand on dirt, in daylight enough to grow (light 9 and more);</li>
 *   <li>the monster cave's room is dark enough for monsters: block light and sky light both 0 at its centre, where
 *       {@code Monster.isDarkEnoughToSpawn} says yes. The control: its doorway, open to the sky, is lit.</li>
 * </ul>
 * That they stand and can be walked into is {@code WalkThroughCorpusTest} (every Builder preset) and
 * {@code WalkThroughGameTest}; that they come at one size is {@code BuilderSizes.FIXED}.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderWorkingBuildsGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderWorkingBuildsGameTest() {
    }

    static final List<String> TYPES = List.of("farm_plot", "animal_pen", "tree_nursery", "monster_cave");

    @GameTest(template = "empty_test", batch = "builderWorkingBuilds", timeoutTicks = 8000)
    public static void theWorkingBuildsWorkAsVanillaDoes(GameTestHelper helper) {
        BuilderAllowlist allow = BuilderSuppliesGameTest.shippedWorldBuilder();
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtworking");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, child);
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;   // the pillar's rise is BuilderMakeGameTest's
        MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        MinecraftServer server = helper.getLevel().getServer();
        var who = BuilderMake.Who.of(child);
        Map<String, String> made = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        BuilderMakeGameTest.takeDownWhenDone(helper, () -> List.copyOf(made.values()));
        AtomicReference<CompletableFuture<?>> chain = new AtomicReference<>(CompletableFuture.completedFuture(null));
        int[] step = {0};
        for (String type : TYPES) {
            // Each make on its own ground: a make lands where he faces, so four in a row from one spot stand on
            // each other (the cave's stone over the farm's soil). He steps 40 blocks east before each.
            int east = 40 * step[0]++;
            // The menus as he meets them: the type goes straight to the dialog, and the dialog asks no size.
            BuilderFlow f = new BuilderFlow(allow, LibraryPresets.SHIPPED);
            f.press("building");
            BuilderFlow.Outcome o = f.press("buildings/" + type);
            if (!o.say().isEmpty() || f.step() != BuilderFlow.Step.TUNE) {
                wrong.add(type + ": the menus stopped at " + f.step() + " " + o.say());
                continue;
            }
            if (f.tune().keys().contains("size")) {
                wrong.add(type + ": the dialog offers a size, and scaling it stops it working");
            }
            BuilderSpec spec = f.spec(Map.of());
            chain.set(chain.get().thenComposeAsync(x -> server.submit(() -> child.setPos(at.getX() + 0.5 + east, at.getY(),
                    at.getZ() + 0.5))).thenComposeAsync(x -> once(server, who, spec, allow)).thenAcceptAsync(res -> {
                if (res.outcome() != BuilderMake.Outcome.MADE || res.madeId() == null || !res.calls().contains("build_structure")) {
                    wrong.add(type + ": " + res.outcome() + " " + res.calls() + " " + res.detail());
                } else {
                    made.put(type, res.madeId());
                }
            }));
        }
        CompletableFuture<?> all = chain.get();
        ServerLevel level = helper.getLevel();
        boolean[] drained = {false};
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making: " + made));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw"));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("\n", wrong)));
            if (!drained[0]) {
                com.moddingfromamod.builder.Builder.drainNow(level);   // every queued block placed, now
                drained[0] = true;
            }
            List<String> bad = new ArrayList<>();
            for (String type : TYPES) {
                String id = made.get(type);
                BuildPlan plan = BuildRegistry.get(id);
                Long anchor = BuildRegistry.anchorOf(id);
                if (plan == null || anchor == null) {
                    bad.add(type + ": no plan or anchor for '" + id + "'");
                    continue;
                }
                String why = switch (type) {
                    case "farm_plot" -> farm(level, plan, BlockPos.of(anchor));
                    case "animal_pen" -> pen(level, plan, BlockPos.of(anchor));
                    case "tree_nursery" -> nursery(level, plan, BlockPos.of(anchor));
                    default -> cave(level, plan, BlockPos.of(anchor));
                };
                if (why != null) {
                    bad.add(type + ": " + why);
                }
            }
            helper.assertTrue(bad.isEmpty(), Component.literal(String.join("\n", bad)));
            LOGGER.info("builder working builds GT: {} work as vanilla does", made.keySet());
        });
    }

    /** The plan's blocks as they finally stand, local position → block id (a later shape over an earlier one wins). */
    static Map<BlockPos, String> cells(BuildPlan plan) {
        Map<BlockPos, String> out = new HashMap<>();
        plan.forEachBlock((x, y, z, block) -> out.put(new BlockPos(x, y, z), block.replaceFirst("\\[.*", "")));
        return out;
    }

    static List<BlockPos> where(BuildPlan plan, BlockPos anchor, String block) {
        List<BlockPos> out = new ArrayList<>();
        cells(plan).forEach((p, b) -> {
            if (b.equals(block)) {
                out.add(anchor.offset(p));
            }
        });
        return out;
    }

    static String farm(ServerLevel level, BuildPlan plan, BlockPos anchor) {
        List<BlockPos> soil = where(plan, anchor, "minecraft:farmland");
        if (soil.size() < 20) {
            return "only " + soil.size() + " farmland in its plan";
        }
        for (BlockPos q : soil) {
            if (!level.getBlockState(q).is(Blocks.FARMLAND)) {
                return "its soil at " + q.toShortString() + " is " + level.getBlockState(q).getBlock();
            }
            for (int i = 0; i < 3; i++) {   // vanilla's own random tick: wet next to water, dry (then dirt) without
                level.getBlockState(q).randomTick(level, q, level.getRandom());
            }
            BlockState s = level.getBlockState(q);
            if (!s.is(Blocks.FARMLAND) || s.getValue(FarmlandBlock.MOISTURE) != FarmlandBlock.MAX_MOISTURE) {
                return "its soil at " + q.toShortString() + " did not stay wet farmland by its water (" + s + ")";
            }
        }
        // its wheat is RIPE as it stands, so a child can harvest it at once (to feed two cows).
        List<BlockPos> wheat = where(plan, anchor, "minecraft:wheat");
        if (wheat.isEmpty()) {
            return "no wheat in its plan";
        }
        for (BlockPos q : wheat) {
            BlockState w = level.getBlockState(q);
            if (!(w.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) || !crop.isMaxAge(w)) {
                return "its wheat at " + q.toShortString() + " is not ripe (" + w + ")";
            }
        }
        // The control, which the bug would also be true of if the check could not tell: a dry farmland with no crop.
        BlockPos dry = anchor.offset(0, 0, -20);
        level.setBlockAndUpdate(dry.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(dry, Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(dry.above(), Blocks.AIR.defaultBlockState());
        for (int i = 0; i < 3; i++) {
            if (level.getBlockState(dry).is(Blocks.FARMLAND)) {
                level.getBlockState(dry).randomTick(level, dry, level.getRandom());
            }
        }
        boolean dried = !level.getBlockState(dry).is(Blocks.FARMLAND);
        level.setBlockAndUpdate(dry, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(dry.below(), Blocks.AIR.defaultBlockState());
        if (!dried) {
            return "control: a farmland with no water stayed farmland, so the check cannot tell";
        }
        for (BlockPos q : where(plan, anchor, "minecraft:wheat")) {
            if (!level.getBlockState(q).is(Blocks.WHEAT)) {
                return "no wheat planted at " + q.toShortString();
            }
        }
        return null;
    }

    static String pen(ServerLevel level, BuildPlan plan, BlockPos anchor) {
        List<BlockPos> grass = where(plan, anchor, "minecraft:grass_block");
        List<BlockPos> gates = new ArrayList<>();
        for (BlockPos q : where(plan, anchor, "minecraft:oak_fence_gate")) {
            if (level.getBlockState(q).is(BlockTags.FENCE_GATES)) {
                gates.add(q);
            }
        }
        if (gates.size() != 1) {
            return gates.size() + " gate(s) in its fence, not one";
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos g : grass) {
            minX = Math.min(minX, g.getX());
            maxX = Math.max(maxX, g.getX());
            minZ = Math.min(minZ, g.getZ());
            maxZ = Math.max(maxZ, g.getZ());
        }
        int y = gates.get(0).getY();
        BlockPos start = new BlockPos((minX + maxX) / 2, y, (minZ + maxZ) / 2);
        // Walk out from the middle over open ground (the gate shut, as he leaves it): never past the fence.
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>(List.of(start));
        while (!todo.isEmpty()) {
            BlockPos q = todo.pop();
            if (!seen.add(q)) {
                continue;
            }
            if (q.getX() <= minX - 1 || q.getX() >= maxX + 1 || q.getZ() <= minZ - 1 || q.getZ() >= maxZ + 1
                    || seen.size() > 400) {
                return "an animal walks out of it at " + q.toShortString();
            }
            if (!level.getBlockState(q.below()).is(Blocks.GRASS_BLOCK)) {
                return "inside it at " + q.toShortString() + " stands on " + level.getBlockState(q.below()).getBlock();
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = q.relative(d);
                BlockState s = level.getBlockState(n);
                if (!s.is(BlockTags.FENCES) && !s.is(BlockTags.FENCE_GATES) && s.getCollisionShape(level, n).isEmpty()) {
                    todo.add(n);
                }
            }
        }
        return seen.size() < 25 ? "only " + seen.size() + " blocks of grass inside its fence" : null;
    }

    static String nursery(ServerLevel level, BuildPlan plan, BlockPos anchor) {
        List<BlockPos> saplings = where(plan, anchor, "minecraft:oak_sapling");
        if (saplings.size() < 4) {
            return "only " + saplings.size() + " saplings in its plan";
        }
        for (BlockPos q : saplings) {
            if (!level.getBlockState(q).is(BlockTags.SAPLINGS)) {
                return "no sapling at " + q.toShortString() + " (" + level.getBlockState(q).getBlock() + ")";
            }
            if (!level.getBlockState(q.below()).is(BlockTags.DIRT)) {
                return "the sapling at " + q.toShortString() + " stands on " + level.getBlockState(q.below()).getBlock();
            }
            int light = level.getRawBrightness(q, 0);   // by day: a sapling grows at 9 and more
            if (light < 9) {
                return "the sapling at " + q.toShortString() + " has light " + light + ", and it needs 9 to grow";
            }
        }
        return null;
    }

    static String cave(ServerLevel level, BuildPlan plan, BlockPos anchor) {
        var room = plan.shapes().stream().filter(s -> s.name().equals("dark_room")).findFirst().orElse(null);
        var door = plan.shapes().stream().filter(s -> s.name().equals("doorway")).findFirst().orElse(null);
        if (room == null || door == null) {
            return "its plan has no dark_room or no doorway";
        }
        BlockPos outside = anchor.offset(door.dx(), door.dy(), door.dz() - 1);
        int lit = level.getBrightness(LightLayer.SKY, outside);
        if (lit < 10) {
            return "control: outside its doorway sky light is " + lit + ", so the light has not been worked out yet";
        }
        for (int dy = 0; dy < 2; dy++) {
            BlockPos c = anchor.offset(room.dx() + room.width() / 2, room.dy() + dy, room.dz() + room.depth() / 2);
            if (!level.getBlockState(c).isAir()) {
                return "its centre " + c.toShortString() + " is " + level.getBlockState(c).getBlock();
            }
            int sky = level.getBrightness(LightLayer.SKY, c);
            int block = level.getBrightness(LightLayer.BLOCK, c);
            if (sky != 0 || block != 0) {
                return "its centre " + c.toShortString() + " has sky light " + sky + " and block light " + block
                        + ": too light for monsters";
            }
            if (dy == 0 && !net.minecraft.world.entity.monster.Monster.isDarkEnoughToSpawn(level, c, level.getRandom())) {
                return "a monster may not spawn at its centre " + c.toShortString();
            }
        }
        return null;
    }

    private static CompletableFuture<BuilderMake.Result> once(MinecraftServer server, BuilderMake.Who who, BuilderSpec spec,
                                                              BuilderAllowlist allow) {
        CompletableFuture<BuilderMake.Result> r = new CompletableFuture<>();
        server.execute(() -> BuilderMake.run(server, who, spec, allow, protocol(), false, false)
                .whenComplete((res, err) -> {
                    if (err != null) {
                        r.completeExceptionally(err);
                    } else {
                        r.complete(res);
                    }
                }));
        return r;
    }

    private static volatile McpProtocol protocol;

    private static McpProtocol protocol() {
        if (protocol == null) {
            synchronized (BuilderWorkingBuildsGameTest.class) {
                if (protocol == null) {
                    protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
            }
        }
        return protocol;
    }
}
