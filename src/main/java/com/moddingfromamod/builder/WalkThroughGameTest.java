package com.moddingfromamod.builder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.builder.WalkGround.Terrain;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/**
 * <b>Doors and the walk-through, where they RUN.</b>
 *
 * <p>{@code WalkThroughTest} and {@code WalkThroughCorpusTest} prove the rules on plans. A rule tested only
 * where it is defined is not tested where it runs, so this drives the real paths: {@code build_structure}
 * puts up a house whose doors the agent got wrong and must leave real, openable doors in the world, say
 * so, and take exactly those doors down again; and every Builder preset is put up by the real
 * {@link Builder} — footing, island and all — on flat ground, a hill, a forest and the sea, and the same
 * walker then walks a snapshot of the WORLD: nothing may fall, and the biggest room must be reachable.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WalkThroughGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("moddingfromamod/walkThrough");

    /** Its own batch: it builds the whole Builder library four times over and forces chunks. */
    private static final String BATCH = "walkThrough";

    public static final List<String> PRESETS = WalkGround.PRESETS;

    /**
     * Presets whose biggest room cannot be walked into once the real Builder puts them on this ground.
     * Measured 2026-11-10 with two rows — {@code HILL/mansion} and {@code HILL/underground_base}, the
     * uphill rooms and the bunker's hatch under earth — and EMPTY, which cuts a hill out of a
     * building's footprint as if it stood on flat ground ({@link BuildClearing}).
     * A preset leaving this list is a fix to celebrate (remove it); one joining it fails.
     */
    static final java.util.Set<String> KNOWN = java.util.Set.of();

    private static final int ORIGIN_X = -26_000;
    private static final int ORIGIN_Z = 26_000;
    private static final int SPACING = 96;
    public static final int GROUND_Y = WalkGround.GROUND_Y;
    public static final int PAD = WalkGround.PAD;

    public static BuildPlan preset(String id) {
        return WalkGround.preset(id);
    }

    // ── 1. build_structure: the door rewrite reaches the world, the agent is told, take-down is exact ─

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void buildStructurePutsUpDoorsAChildCanOpenAndSaysWhereHeCannotWalk(GameTestHelper helper) {
        String id = "gt_walk_doors_house";
        // A stone house 9x9, walls hollow; an oak door shape 2 tall with no half (two bottom halves as drawn)
        // in the south wall; an iron door in the east wall with nothing to press; a plain 1x2 doorway in the
        // north wall; and an upper storey with a window and no stairs.
        String json = """
            {"id":"$ID","name":"Door House","note":"#1947 fixture","shapes":[
             {"name":"floor","kind":"box","dx":0,"dy":-1,"dz":0,"width":9,"height":1,"depth":9,"block":"minecraft:stone"},
             {"name":"walls","kind":"box","dx":0,"dy":-1,"dz":0,"width":9,"height":10,"depth":9,"block":"minecraft:stone_bricks","hollow":true},
             {"name":"mid","kind":"box","dx":1,"dy":4,"dz":1,"width":7,"height":1,"depth":7,"block":"minecraft:oak_planks"},
             {"name":"south_door","kind":"box","dx":4,"dy":0,"dz":8,"width":1,"height":2,"depth":1,"block":"minecraft:oak_door"},
             {"name":"east_door","kind":"box","dx":8,"dy":0,"dz":4,"width":1,"height":2,"depth":1,"block":"minecraft:iron_door"},
             {"name":"north_way","kind":"box","dx":4,"dy":0,"dz":0,"width":1,"height":2,"depth":1,"block":"minecraft:air"},
             {"name":"window","kind":"box","dx":4,"dy":6,"dz":0,"width":1,"height":1,"depth":1,"block":"minecraft:air"}]}
            """.replace("$ID", id);
        AtomicReference<String> reply = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                var r = new com.moddingfromamod.agent.tools.BuildStructureTool().call(
                        JsonParser.parseString(json).getAsJsonObject());
                reply.set((r.isError() ? "ERR " : "") + r.text());
            } catch (RuntimeException e) {
                reply.set("ERR threw: " + e);
            }
        }, "walk-doors-gametest");
        t.setDaemon(true);
        t.start();
        AtomicReference<Boolean> cleared = new AtomicReference<>(false);
        helper.succeedWhen(() -> {
            String r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for build_structure"));
            helper.assertTrue(!r.startsWith("ERR"), Component.literal(r));
            helper.assertTrue(r.contains("DOORS —"), Component.literal("the reply does not say what was done to the doors: " + r));
            helper.assertTrue(r.contains("WALK-THROUGH") && r.contains("nothing climbs up to it"),
                    Component.literal("the reply does not say the upper storey cannot be walked to: " + r));
            helper.assertTrue(r.contains("decoration"), Component.literal("the advice must say leaving it is fine: " + r));
            BuildPlan plan = BuildRegistry.get(id);
            Long anchor = BuildRegistry.anchorOf(id);
            helper.assertTrue(plan != null && anchor != null, Component.literal("the building was not saved"));
            ServerLevel level = helper.getLevel().getServer().overworld();
            BlockPos at = BlockPos.of(anchor);
            helper.assertTrue(!Builder.isBuilding(id), Component.literal("still going up"));
            // the oak door: lower then upper, a real door
            assertDoor(helper, level, at.offset(4, 0, 8), "oak", "the south door");
            // the iron door is wooden now: nothing near it to press
            assertDoor(helper, level, at.offset(8, 0, 4), null, "the east (once iron) door");
            helper.assertTrue(!level.getBlockState(at.offset(8, 0, 4)).is(Blocks.IRON_DOOR),
                    Component.literal("the iron door was left iron"));
            // the plain doorway in a stone wall got a spruce door
            assertDoor(helper, level, at.offset(4, 0, 0), "spruce", "the north doorway");
            // the saved design is the agent's own: no half, still iron, still an open doorway
            helper.assertTrue(plan.shapes().stream().anyMatch(s -> s.block().equals("minecraft:iron_door")),
                    Component.literal("the saved plan was rewritten; the rewrite belongs to placement only"));
            if (!cleared.get()) {
                Builder.clearBuild(level, at, plan);
                cleared.set(true);
            }
            helper.assertTrue(level.getBlockState(at.offset(4, 0, 0)).isAir()
                            && level.getBlockState(at.offset(4, 1, 0)).isAir(),
                    Component.literal("taking it down left the door it added in the north doorway"));
        });
    }

    // ── 1¼.: a one-block fault in a way in is put right as it is placed, and the agent is told ──

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void buildStructureRaisesADoorwayCutABlockTooLow(GameTestHelper helper) {
        String id = "gt_walk_repair_house";
        // The commonest real fault: hollow walls bring their own floor (he stands at dy 1), and the doorway was cut
        // two tall from the ground (dy 0..1), so at the room's floor it is one block tall.
        String json = """
            {"id":"$ID","name":"Sunk Door House","note":"#2114 fixture","shapes":[
             {"name":"yard","kind":"box","dx":-3,"dy":-1,"dz":-3,"width":15,"height":1,"depth":15,"block":"minecraft:stone"},
             {"name":"walls","kind":"box","dx":0,"dy":0,"dz":0,"width":9,"height":6,"depth":9,"block":"minecraft:stone_bricks","hollow":true},
             {"name":"lamp","kind":"box","dx":4,"dy":1,"dz":4,"width":1,"height":1,"depth":1,"block":"minecraft:lantern"},
             {"name":"way","kind":"box","dx":4,"dy":0,"dz":0,"width":1,"height":2,"depth":1,"block":"minecraft:air"}]}
            """.replace("$ID", id);
        AtomicReference<String> reply = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                var r = new com.moddingfromamod.agent.tools.BuildStructureTool().call(
                        JsonParser.parseString(json).getAsJsonObject());
                reply.set((r.isError() ? "ERR " : "") + r.text());
            } catch (RuntimeException e) {
                reply.set("ERR threw: " + e);
            }
        }, "walk-repair-gametest");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            String r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for build_structure"));
            helper.assertTrue(!r.startsWith("ERR"), Component.literal(r));
            helper.assertTrue(r.contains("I FIXED") && r.contains("raised to the room's floor"),
                    Component.literal("the reply does not say the doorway was raised: " + r));
            helper.assertTrue(!r.contains("WALK-THROUGH"),
                    Component.literal("the reply still says a room cannot be walked into: " + r));
            BuildPlan plan = BuildRegistry.get(id);
            Long anchor = BuildRegistry.anchorOf(id);
            helper.assertTrue(plan != null && anchor != null, Component.literal("the building was not saved"));
            helper.assertTrue(!Builder.isBuilding(id), Component.literal("still going up"));
            ServerLevel level = helper.getLevel().getServer().overworld();
            BlockPos at = BlockPos.of(anchor);
            // In the world: the block over the doorway at his head is open (air, or the top of the door hung there).
            BlockState head = level.getBlockState(at.offset(4, 2, 0));
            helper.assertTrue(head.isAir() || head.getBlock() instanceof DoorBlock,
                    Component.literal("the doorway's head block is still solid in the world: " + head));
            WalkThrough.Report walked = WalkThroughWorld.of(level, at, BuildFraming.bounds(plan), 6).report();
            WalkThrough ww = WalkThroughWorld.of(level, at, BuildFraming.bounds(plan), 6);
            helper.assertTrue(!walked.tooBig() && walked.unreachable().isEmpty(),
                    Component.literal("walked in the world, the room is still not reachable: " + walked.unreachable()
                            + " anchor " + at.toShortString() + "\n" + ww.layer(at.getY() - 1) + "\n" + ww.layer(at.getY())
                            + "\n" + ww.layer(at.getY() + 1) + "\n" + ww.layer(at.getY() + 2)));
            // The saved design is the agent's own: four shapes, the doorway still two tall from the ground.
            helper.assertTrue(plan.shapes().size() == 4 && plan.shapes().stream().anyMatch(s -> s.name().equals("way")
                            && s.dy() == 0 && s.height() == 2),
                    Component.literal("the saved plan was rewritten; the repair belongs to placement only"));
            // Take it down: a house left standing in the shared test world is something a later test lands on.
            Builder.clearBuild(level, at, plan);
            helper.assertTrue(level.getBlockState(at.offset(0, 1, 0)).isAir() && level.getBlockState(at.offset(-3, -1, -3)).isAir(),
                    Component.literal("taking it down left part of it standing"));
        });
    }

    private static void assertDoor(GameTestHelper helper, ServerLevel level, BlockPos lower, String wood, String what) {
        BlockState lo = level.getBlockState(lower);
        BlockState up = level.getBlockState(lower.above());
        helper.assertTrue(lo.getBlock() instanceof DoorBlock && up.getBlock() instanceof DoorBlock,
                Component.literal(what + " is not a door in the world: " + lo + " / " + up));
        helper.assertTrue(lo.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                        && up.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER,
                Component.literal(what + " has no proper halves: " + lo + " / " + up));
        if (wood != null) {
            helper.assertTrue(BlockStateParser.serialize(lo).startsWith("minecraft:" + wood + "_door"),
                    Component.literal(what + " is not " + wood + ": " + lo));
        }
    }

    // ── 1½.: a tree in a room is cleared, a tree in a courtyard is not, and both come back ───

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aTreeInARoomIsClearedAndATreeInACourtyardIsKept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BuildPlan plan = new BuildPlan("gt_room_court", "Room and Court", RoomClearingFixture.plan().shapes());
        int bx = ORIGIN_X - 400;
        int bz = ORIGIN_Z - 400;
        List<long[]> forced = new ArrayList<>();
        for (int qx = (bx - 16) >> 4; qx <= (bx + 32) >> 4; qx++) {
            for (int qz = (bz - 16) >> 4; qz <= (bz + 32) >> 4; qz++) {
                level.setChunkForced(qx, qz, true);
                forced.add(new long[]{qx, qz});
            }
        }
        paintGround(level, Terrain.FLAT, bx - 8, bx + 24, bz - 8, bz + 16, 1L);
        BlockPos at = new BlockPos(bx, GROUND_Y, bz);
        BlockPos roomTrunk = at.offset(3, 1, 3);
        BlockPos courtTrunk = at.offset(11, 1, 3);
        for (BlockPos t : List.of(roomTrunk, courtTrunk)) {
            for (int dy = 0; dy < 6; dy++) {
                level.setBlock(t.above(dy), Blocks.OAK_LOG.defaultBlockState(), 2);
            }
        }
        BlockPos chest = at.offset(1, 1, 1);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 2);
        try {
            String r = Builder.submit(level, at, plan, true, BuildGround.Ground.settle(), null);
            Builder.drainNow(level);
            Long a = BuildRegistry.anchorOf(plan.id());
            BlockPos built = a == null ? at : BlockPos.of(a);
            helper.assertTrue(built.equals(at), Component.literal("it moved: " + built.toShortString() + " — " + r));
            helper.assertTrue(r.contains("inside its rooms"), Component.literal("the agent was not told: " + r));
            for (int dy = 0; dy < 3; dy++) {
                helper.assertTrue(level.getBlockState(roomTrunk.above(dy)).isAir(),
                        Component.literal("the trunk is still in the room at " + roomTrunk.above(dy).toShortString()));
            }
            helper.assertTrue(level.getBlockState(roomTrunk.above(5)).is(Blocks.OAK_LOG),
                    Component.literal("the trunk ABOVE the roof is not the room's: it stays"));
            for (int dy = 0; dy < 6; dy++) {
                helper.assertTrue(level.getBlockState(courtTrunk.above(dy)).is(Blocks.OAK_LOG),
                        Component.literal("the courtyard's tree was touched at " + courtTrunk.above(dy).toShortString()));
            }
            helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST), Component.literal("his chest was cleared"));
            Builder.clearBuild(level, built, plan);
            BuildRegistry.markRemoved(level.getServer(), plan.id());
            for (int dy = 0; dy < 3; dy++) {
                helper.assertTrue(level.getBlockState(roomTrunk.above(dy)).is(Blocks.OAK_LOG),
                        Component.literal("taking it down did not give the tree back at " + roomTrunk.above(dy).toShortString()));
            }
            helper.succeed();
        } finally {
            for (long[] c : forced) {
                level.setChunkForced((int) c[0], (int) c[1], false);
            }
        }
    }

    // ── 2. every preset, put up by the real Builder on four kinds of ground, then walked ───────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void everyPresetStandsAndCanBeWalkedIntoOnFlatGround(GameTestHelper helper) {
        everyPreset(helper, Terrain.FLAT);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void everyPresetStandsAndCanBeWalkedIntoOnAHill(GameTestHelper helper) {
        everyPreset(helper, Terrain.HILL);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void everyPresetStandsAndCanBeWalkedIntoInAForest(GameTestHelper helper) {
        everyPreset(helper, Terrain.FOREST);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void everyPresetStandsAndCanBeWalkedIntoOnWater(GameTestHelper helper) {
        everyPreset(helper, Terrain.WATER);
    }

    /**
     * a building COMPOSED in the Builder — every type that stands on the ground, each with EVERYTHING outside it
     * at once (a multi-pick, a follow-up: the most crowded its ground can be; every subset is walked in
     * Gate A, BuilderBuildingComposableTest) — lowered by the Builder's own lowering, put up by the real
     * Builder on flat ground, then walked in the world like the presets: nothing may fall, the biggest room reachable.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void everyComposedBuildingWithSomethingOutsideStandsAndCanBeWalkedInto(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Subject> subjects = new ArrayList<>();
        List<com.moddingfromamod.wishbuilder.BuilderBuildings.Outside> outs = com.moddingfromamod.wishbuilder.BuilderBuildings.OUTSIDE
                .stream().filter(o -> !o.id().equals(com.moddingfromamod.wishbuilder.BuilderBuildings.NOTHING)).toList();
        int i = 0;
        for (com.moddingfromamod.wishbuilder.BuilderBuildings.Type t : com.moddingfromamod.wishbuilder.BuilderBuildings.TYPES) {
            if (!t.takesOutside()) {
                continue;
            }
            String outside = com.moddingfromamod.wishbuilder.BuilderBuildings.join(outs.stream().map(o -> o.id()).toList());
            var spec = new com.moddingfromamod.wishbuilder.BuilderSpec(com.moddingfromamod.wishbuilder.BuilderSpec.CURRENT,
                    com.moddingfromamod.wishbuilder.BuilderSpec.Kind.BUILDING, "", "", 2, List.of(), "", 1, "", "",
                    com.moddingfromamod.wishbuilder.BuilderBuildings.opts(t.id(), outside));
            var args = com.moddingfromamod.wishbuilder.BuilderLowering.lower(spec, "gt_composed_" + t.id(),
                    com.moddingfromamod.wishbuilder.LibraryPresets.SHIPPED).get(1).args();
            BuildPlan p = BuildPlan.fromJson(args);
            // judge() names a subject by what comes before "_gt_".
            BuildPlan own = new BuildPlan(t.id() + "_everything_gt_composed", p.name(), p.shapes(), "", p.spots());
            subjects.add(put(level, own, Terrain.FLAT, 40 + i));
            i++;
        }
        // And every simple building from parts at its tallest, its roof, door and windows taking turns, everything outside.
        List<String> roofs = List.copyOf(com.moddingfromamod.wishbuilder.BuilderHouses.ROOFS.keySet());
        List<String> doors = List.copyOf(com.moddingfromamod.wishbuilder.BuilderHouses.DOORS.keySet());
        List<String> wins = List.copyOf(com.moddingfromamod.wishbuilder.BuilderHouses.WINDOWS.keySet());
        int j = 0;
        for (com.moddingfromamod.wishbuilder.BuilderHouses.Simple sb : com.moddingfromamod.wishbuilder.BuilderHouses.SIMPLE) {
            String roof = roofs.get(j % roofs.size());
            String door = doors.get(j % doors.size());
            String win = wins.get(j % wins.size());
            String outside = com.moddingfromamod.wishbuilder.BuilderBuildings.join(outs.stream().map(o -> o.id()).toList());
            var spec = new com.moddingfromamod.wishbuilder.BuilderSpec(com.moddingfromamod.wishbuilder.BuilderSpec.CURRENT,
                    com.moddingfromamod.wishbuilder.BuilderSpec.Kind.BUILDING, "", "", 2, List.of(), "", 1, "", "",
                    com.moddingfromamod.wishbuilder.BuilderHouses.opts(sb.id(), com.moddingfromamod.wishbuilder.BuilderHouses.TALLEST,
                            roof, door, win, outside));
            var args = com.moddingfromamod.wishbuilder.BuilderLowering.lower(spec, "gt_parts_" + sb.id(),
                    com.moddingfromamod.wishbuilder.LibraryPresets.SHIPPED).get(1).args();
            BuildPlan p = BuildPlan.fromJson(args);
            BuildPlan own = new BuildPlan(sb.id() + "_" + roof + "_" + door + "_" + win + "_everything_gt_parts", p.name(),
                    p.shapes(), "", p.spots());
            subjects.add(put(level, own, Terrain.FLAT, 40 + i + j));
            j++;
        }
        helper.runAfterDelay(40, () -> {
            List<String> failures = new ArrayList<>();
            StringBuilder table = new StringBuilder();
            try {
                for (Subject s : subjects) {
                    judge(level, s, Terrain.FLAT, failures, table);
                }
            } finally {
                subjects.forEach(s -> takeDown(level, s));
            }
            LOGGER.info("walkThrough composed:\n{}", table);
            if (failures.isEmpty()) {
                helper.succeed();
            } else {
                helper.fail(Component.literal("composed buildings: " + String.join("\n", failures)));
            }
        });
    }

    private record Subject(BuildPlan plan, BlockPos aimed, BuildFraming.Bounds bounds, List<long[]> forced,
                           int x0, int x1, int z0, int z1, String reply) {
    }

    private static void everyPreset(GameTestHelper helper, Terrain terrain) {
        ServerLevel level = helper.getLevel();
        List<Subject> subjects = new ArrayList<>();
        for (int i = 0; i < PRESETS.size(); i++) {
            BuildPlan p = preset(PRESETS.get(i));
            helper.assertTrue(p != null, Component.literal("preset " + PRESETS.get(i) + " did not load"));
            BuildPlan own = new BuildPlan(p.id() + "_gt_" + terrain.name().toLowerCase(java.util.Locale.ROOT),
                    p.name(), p.shapes(), "", p.spots());
            subjects.add(put(level, own, terrain, i));
        }
        helper.runAfterDelay(40, () -> {
            List<String> failures = new ArrayList<>();
            StringBuilder table = new StringBuilder();
            try {
                for (Subject s : subjects) {
                    judge(level, s, terrain, failures, table);
                }
            } finally {
                for (Subject s : subjects) {
                    Long anchorL = BuildRegistry.anchorOf(s.plan().id());
                    BlockPos at = anchorL == null ? s.aimed() : BlockPos.of(anchorL);
                    takeDown(level, s);
                    if (terrain == Terrain.HILL) {
                        hillGivenBack(level, s, at, failures);
                    }
                }
            }
            if (terrain == Terrain.HILL && subjects.stream().noneMatch(s -> s.reply().contains("of hill out of"))) {
                failures.add("no preset on the hill was told it was cut into the hill (#1953) — "
                        + subjects.get(0).reply());
            }
            LOGGER.info("walkThrough {}:\n{}", terrain, table);
            writeTable(terrain, table.toString());
            if (failures.isEmpty()) {
                helper.succeed();
            } else {
                helper.fail(Component.literal(terrain + ": " + String.join("\n", failures)));
            }
        });
    }

    /** The ground, then the building on it the way a wish puts one up: aimed at the surface, settled. */
    private static Subject put(ServerLevel level, BuildPlan plan, Terrain terrain, int i) {
        BuildFraming.Bounds b = BuildFraming.bounds(plan);
        int cx = ORIGIN_X + i * SPACING;
        int cz = ORIGIN_Z + terrain.ordinal() * SPACING * 2;
        int x0 = cx + b.minX() - PAD;
        int x1 = cx + b.maxX() + PAD;
        int z0 = cz + b.minZ() - PAD;
        int z1 = cz + b.maxZ() + PAD;
        List<long[]> forced = new ArrayList<>();
        for (int qx = (x0 - 16) >> 4; qx <= (x1 + 16) >> 4; qx++) {
            for (int qz = (z0 - 16) >> 4; qz <= (z1 + 16) >> 4; qz++) {
                level.setChunkForced(qx, qz, true);
                forced.add(new long[]{qx, qz});
            }
        }
        paintGround(level, terrain, x0, x1, z0, z1, i * 31L + terrain.ordinal());
        BlockPos aim = new BlockPos(cx, surface(terrain, cx - x0) + 1, cz);
        if (terrain == Terrain.WATER) {
            aim = new BlockPos(cx, GROUND_Y, cz);                    // aimed at the water's surface
        }
        String r = Builder.submit(level, aim, plan, true, BuildGround.Ground.settle(), null);
        Builder.drainNow(level);
        LOGGER.info("walkThrough {} {} at {}: {}", terrain, plan.id(), aim.toShortString(), r);
        return new Subject(plan, aim, b, forced, x0, x1, z0, z1, r == null ? "" : r);
    }

    /** The ground this test stands a building on: {@link WalkGround#paintGround}. */
    public static void paintGround(ServerLevel level, Terrain terrain, int x0, int x1, int z0, int z1, long seed) {
        WalkGround.paintGround(level, terrain, x0, x1, z0, z1, seed);
    }

    public static int surface(Terrain terrain, int dx) {
        return WalkGround.surface(terrain, dx);
    }


    /** Nothing falls; then walk the world around it and compare with the plan walked on flat ground. */
    private static void judge(ServerLevel level, Subject s, Terrain terrain, List<String> failures, StringBuilder table) {
        String id = s.plan().id();
        Long anchorL = BuildRegistry.anchorOf(id);
        BlockPos at = anchorL == null ? s.aimed() : BlockPos.of(anchorL);
        BuildFraming.Bounds b = s.bounds();
        AABB box = new AABB(at.getX() + b.minX() - 4, at.getY() + b.minY() - 16, at.getZ() + b.minZ() - 4,
                at.getX() + b.maxX() + 5, at.getY() + b.maxY() + 5, at.getZ() + b.maxZ() + 5);
        List<FallingBlockEntity> falling = level.getEntitiesOfClass(FallingBlockEntity.class, box);
        if (!falling.isEmpty()) {
            failures.add(id + ": " + falling.size() + " blocks fell, e.g. " + falling.get(0).getBlockState()
                    + " at " + falling.get(0).blockPosition().toShortString());
        }
        WalkThrough.Report design = WalkThrough.of(s.plan());
        WalkThrough world = WalkThroughWorld.of(level, at, b, 6);
        WalkThrough.Report walked = world.report();
        long planRooms = design.rooms().stream().filter(WalkThrough.Room::reached).count();
        long worldRooms = walked.rooms().stream().filter(WalkThrough.Room::reached).count();
        table.append(String.format("%-28s anchor %s (aimed %s): design %d/%d rooms reachable, world %d/%d; main %s; fell %d%n",
                id, at.toShortString(), s.aimed().toShortString(), planRooms, design.rooms().size(), worldRooms,
                walked.rooms().size(), walked.main() == null ? "-" : walked.main().reached() ? "walkable" : "NOT walkable",
                falling.size()));
        String known = terrain + "/" + id.substring(0, id.indexOf("_gt_"));
        boolean broken = !walked.tooBig() && design.main() != null && design.main().reached() && !walked.mainWalkable();
        if (walked.tooBig()) {
            failures.add(id + ": too big to walk in the world");
        } else if (broken && !KNOWN.contains(known)) {
            failures.add(id + ": its biggest room (" + walked.main().describe() + ") cannot be walked into once it "
                    + "stands on " + terrain + " — " + walked.main().cause());
        } else if (!broken && KNOWN.contains(known)) {
            failures.add(known + " is walkable now: take it off KNOWN");
        }
        // in the forest a building clears the trees out of its rooms, so every room its design can be
        // walked into can be walked into among the trees too (the wizard tower was 5 of 28 against 31).
        if (terrain == Terrain.FOREST && !walked.tooBig() && worldRooms < planRooms) {
            failures.add(id + ": in the forest only " + worldRooms + " of its " + planRooms
                    + " walkable rooms can be walked into — something that grows is still in its rooms");
        }
    }

    /**
     * taking a building down off a hill puts the hill back — every cell of the ground it cut (not a
     * cell the plan itself placed, which the take-down empties as it always has) is ground again.
     */
    private static void hillGivenBack(ServerLevel level, Subject s, BlockPos at, List<String> failures) {
        java.util.Set<Long> plan = new java.util.HashSet<>();
        s.plan().forEachBlock((x, y, z, block) -> plan.add(BlockPos.asLong(at.getX() + x, at.getY() + y, at.getZ() + z)));
        for (long k : BuildDoors.of(s.plan()).cells().keySet()) {
            plan.add(BlockPos.asLong(at.getX() + ComposedPlan.kx(k), at.getY() + ComposedPlan.ky(k), at.getZ() + ComposedPlan.kz(k)));
        }
        int holes = 0;
        BlockPos first = null;
        for (int x = s.x0(); x <= s.x1(); x++) {
            for (int z = s.z0(); z <= s.z1(); z++) {
                int top = surface(Terrain.HILL, x - s.x0());
                for (int y = at.getY(); y <= top; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!plan.contains(p.asLong()) && level.getBlockState(p).isAir()) {
                        holes++;
                        if (first == null) {
                            first = p;
                        }
                    }
                }
            }
        }
        if (holes > 0) {
            failures.add(s.plan().id() + ": taking it down left " + holes + " hole(s) in the hill it cut into, e.g. "
                    + first.toShortString());
        }
    }

    private static void takeDown(ServerLevel level, Subject s) {
        try {
            Long anchorL = BuildRegistry.anchorOf(s.plan().id());
            Builder.clearBuild(level, anchorL == null ? s.aimed() : BlockPos.of(anchorL), s.plan());
            BuildRegistry.markRemoved(level.getServer(), s.plan().id());
            level.getEntitiesOfClass(FallingBlockEntity.class,
                    new AABB(s.x0() - 8, GROUND_Y - 40, s.z0() - 8, s.x1() + 8, GROUND_Y + 60, s.z1() + 8))
                    .forEach(e -> e.discard());
        } finally {
            for (long[] c : s.forced()) {
                level.setChunkForced((int) c[0], (int) c[1], false);
            }
        }
    }

    private static void writeTable(Terrain terrain, String table) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                    "moddingfromamod-walkthrough", terrain.name().toLowerCase(java.util.Locale.ROOT) + ".txt");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, table);
        } catch (Exception e) {
            LOGGER.warn("walkThrough: could not write the table: {}", e.toString());
        }
    }
}
