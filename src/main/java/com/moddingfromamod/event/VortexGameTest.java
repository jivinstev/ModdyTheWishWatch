package com.moddingfromamod.event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.event.design.EventBuild;
import com.moddingfromamod.event.design.EventDesign;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The vortex in a real world</b> (docs/EVENTS_PLAN.md §16). Each test stages a DESIGN through
 * {@link EventBuild#stage} (the same path {@code stage_event}, a rule, an item and the Builder take) on a platform of
 * its own, high in the sky over forced chunks, so the ground under it is exactly what the test put there.
 *
 * <p>What it proves is the size table: a dust devil cannot lift a sheep and a tornado can, a tornado lifts a boat but
 * not a horse; each size tears up its own kind of block and no further, never a chest; a whirlpool sinks a boat and
 * spits it out; a hurricane brings its lightning; and every time, the displays go and the world comes back.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class VortexGameTest {

    private static final String BATCH = "vortex";
    /** One blow from a MEDIUM wrecking event (EventBuild: LITTLE 2, MEDIUM 3, BIG 4, GIGANTIC 6; strength 1.0 caught). */
    private static final float MEDIUM_BLOW = 3f;
    private static final AtomicInteger LANE = new AtomicInteger();
    /** The platform's floor: high, so nothing of the world's own terrain is near it. */
    private static final int FLOOR = 200;

    private static BlockPos lane() {
        return new BlockPos(130_000 + LANE.incrementAndGet() * 300, FLOOR, 130_000);
    }

    private static void force(ServerLevel level, BlockPos o, int r, boolean on) {
        for (int cx = (o.getX() - r) >> 4; cx <= (o.getX() + r) >> 4; cx++) {
            for (int cz = (o.getZ() - r) >> 4; cz <= (o.getZ() + r) >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    /** Force the lane's chunks for THIS test, and let them go when it ends — passed, failed or timed out. */
    private static void force(GameTestHelper helper, BlockPos o, int r) {
        force(helper.getLevel(), o, r, true);
        MockPlayerNetwork.afterTest(helper, () -> force(helper.getLevel(), o, r, false));
    }

    /**
     *, the vortex leak: an event a test stages ENDS with the test, whatever happened — its world given back, its displays and
     * creatures gone, the event forgotten. A wrecker is KEPT when it passes, and a KEPT event sat in the store; its
     * owner is empty, and for two minutes every later test's wish photograph went to film it.
     */
    private static String endsWithTheTest(GameTestHelper helper, String id) {
        MockPlayerNetwork.afterTest(helper, () -> EventDirector.endForTest(helper.getLevel().getServer(), id));
        return id;
    }

    /** A grass platform of radius {@code r} at {@link #FLOOR}, with air above it. */
    private static void platform(ServerLevel level, BlockPos o, int r, BlockState floor) {
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                level.setBlock(o.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(o.offset(x, 0, z), floor, 2);
                for (int y = 1; y < 50; y++) {
                    level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static EventDesign design(String json) {
        EventDesign.Parsed p = EventDesign.parse(json);
        if (!p.ok()) {
            throw new IllegalStateException(p.errors().toString());
        }
        return p.design();
    }

    private static <T extends Mob> T mob(ServerLevel level, EntityType<T> type, BlockPos at) {
        T m = type.create(level, EntitySpawnReason.COMMAND);
        m.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        // NOT NoAI: a NoAI mob never travels, so a velocity set on it moves nothing (LivingEntity.aiStep), and the
        // test would read "the tornado lifted nothing". A wander is a block or two; the bars below are well over it.
        level.addFreshEntity(m);
        return m;
    }

    private static int displaysOf(ServerLevel level, BlockPos o, String id) {
        String tag = EventDirector.entityTag(id);
        return level.getEntities((Entity) null, new AABB(o).inflate(80), e -> e instanceof Display
                && e.getTags().contains(tag)).size();
    }

    /** It has played out: gone (it gave the world back) or KEPT (a wrecker keeps its damage until undo). */
    private static boolean ended(ServerLevel level, String id) {
        StagedEvent e = EventDirector.get(level.getServer(), id);
        return e == null || e.phase() == EventPhase.KEPT;
    }

    /** Stage {@code d} on the platform's middle, as if he were standing south of it looking north. */
    private static String stage(GameTestHelper helper, EventDesign d, BlockPos o) {
        EventBuild.Result r = EventBuild.stage(helper.getLevel(), d, o, 180f, null, "", 0);
        if (!r.ok()) {
            throw new IllegalStateException(r.error());
        }
        return endsWithTheTest(helper, r.id());
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900, randomTicks = false)
    public static void aTornadoLiftsPeopleAndBoatsButOnlyTugsAtAHorse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 40);
        platform(level, o, 24, Blocks.GRASS_BLOCK.defaultBlockState());
        BlockPos at = o.above();
        var sheep = mob(level, EntityType.SHEEP, at.offset(1, 0, 0));
        var chicken = mob(level, EntityType.CHICKEN, at.offset(-1, 0, 1));
        var horse = mob(level, EntityType.HORSE, at.offset(0, 0, -2));
        Entity boat = EntityType.OAK_BOAT.create(level, EntitySpawnReason.COMMAND);
        boat.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 2.5, 0, 0);
        level.addFreshEntity(boat);
        double[] rose = new double[4];
        Entity[] who = {sheep, chicken, horse, boat};
        double[] start = {sheep.getY(), chicken.getY(), horse.getY(), boat.getY()};
        String id = stage(helper, design("{\"name\":\"t\",\"motion\":\"vortex\",\"size\":\"medium\",\"path\":\"stay\","
                + "\"spares\":[\"creatures\"]}"), o);
        int[] peakDisplays = {0};
        helper.onEachTick(() -> {
            for (int i = 0; i < who.length; i++) {
                if (who[i].isAlive()) {
                    rose[i] = Math.max(rose[i], who[i].getY() - start[i]);
                }
            }
            peakDisplays[0] = Math.max(peakDisplays[0], displaysOf(level, o, id));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(level, id), Component.literal("the tornado has passed"));
            helper.assertTrue(peakDisplays[0] >= 20, Component.literal("it was drawn: " + peakDisplays[0] + " displays"));
            helper.assertTrue(displaysOf(level, o, id) == 0, Component.literal("every display has gone"));
            helper.assertTrue(rose[0] > 3, Component.literal("a medium tornado lifts a sheep: " + rose[0]));
            helper.assertTrue(rose[1] > 3, Component.literal("and a chicken: " + rose[1]));
            helper.assertTrue(rose[3] > 3, Component.literal("and a boat: " + rose[3]));
            helper.assertTrue(rose[2] < 1.5, Component.literal("but only tugs at a horse: " + rose[2]));
            helper.assertTrue(sheep.isAlive() && chicken.isAlive() && horse.isAlive(),
                    Component.literal("nothing it threw died of the landing"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600, randomTicks = false)
    public static void aDustDevilLiftsAChickenButNotASheep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 30);
        platform(level, o, 16, Blocks.GRASS_BLOCK.defaultBlockState());
        BlockPos at = o.above();
        var sheep = mob(level, EntityType.SHEEP, at.offset(1, 0, 0));
        var chicken = mob(level, EntityType.CHICKEN, at.offset(-1, 0, 0));
        double[] rose = new double[2];
        double s0 = sheep.getY(), c0 = chicken.getY();
        String id = stage(helper, design("{\"name\":\"dd\",\"motion\":\"vortex\",\"size\":\"little\",\"path\":\"stay\","
                + "\"material\":\"sand\",\"spares\":[\"creatures\"]}"), o);
        helper.onEachTick(() -> {
            rose[0] = Math.max(rose[0], sheep.getY() - s0);
            rose[1] = Math.max(rose[1], chicken.getY() - c0);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(level, id), Component.literal("it has passed"));
            helper.assertTrue(rose[1] > 2, Component.literal("a dust devil lifts a chicken: " + rose[1]));
            helper.assertTrue(rose[0] < 1.2, Component.literal("but cannot lift a sheep: " + rose[0]));
        });
    }

    /** Each size tears up its own kind of block and no further; never a chest; all of it comes back. */
    private static void tears(GameTestHelper helper, String size, List<BlockState> takes, List<BlockState> leaves) {
        tears(helper, size, takes, leaves, true);
    }

    /**
     * Each size tears up its own kind of block and no further; never a chest. WRECKING (the default) leaves what it
     * tore until undo, and undo gives it all back; SPARING his builds takes only what the world grew and gives it
     * back as it passes.
     */
    private static void tears(GameTestHelper helper, String size, List<BlockState> takes, List<BlockState> leaves,
                              boolean wrecks) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 40);
        platform(level, o, 24, Blocks.GRASS_BLOCK.defaultBlockState());
        Map<BlockPos, BlockState> placed = new HashMap<>();
        List<BlockPos> shouldGo = new ArrayList<>();
        List<BlockPos> shouldStay = new ArrayList<>();
        // A ring of things right under its foot, one column each, so every one is in reach.
        List<BlockState> all = new ArrayList<>(takes);
        all.addAll(leaves);
        all.add(Blocks.CHEST.defaultBlockState());
        int[][] spots = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int k = 0; k < all.size(); k++) {
            BlockPos p = o.offset(spots[k][0], 1, spots[k][1]);
            level.setBlock(p, all.get(k), 3);
            placed.put(p, level.getBlockState(p));
            (k < takes.size() ? shouldGo : shouldStay).add(p);
        }
        boolean[] went = new boolean[all.size()];
        List<BlockPos> order = new ArrayList<>(placed.keySet());
        String id = stage(helper, design("{\"name\":\"tear\",\"motion\":\"vortex\",\"size\":\"" + size
                + "\",\"path\":\"stay\",\"seconds\":20" + (wrecks ? "" : ",\"spares\":[\"builds\"]") + "}"), o);
        helper.onEachTick(() -> {
            for (int i = 0; i < order.size(); i++) {
                went[i] |= !level.getBlockState(order.get(i)).equals(placed.get(order.get(i)));
            }
        });
        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(level, id), Component.literal("it has passed"));
            String how = (wrecks ? "a wrecking " : "a safe ") + size + " vortex";
            for (int i = 0; i < order.size(); i++) {
                BlockPos p = order.get(i);
                String what = placed.get(p).getBlock().getName().getString();
                if (shouldGo.contains(p)) {
                    helper.assertTrue(went[i], Component.literal(how + " tears up " + what));
                } else {
                    helper.assertTrue(!went[i], Component.literal(how + " never tears up " + what));
                }
                if (wrecks && shouldGo.contains(p) && !undone[0]) {
                    helper.assertTrue(!level.getBlockState(p).equals(placed.get(p)),
                            Component.literal(what + " stays torn up after a wrecker passes, until undo"));
                }
            }
            if (wrecks && !undone[0]) {
                undone[0] = EventDirector.undo(level.getServer(), id);
                helper.assertTrue(false, Component.literal("undo asked; waiting for it to give the world back"));
            }
            for (BlockPos p : order) {
                helper.assertTrue(level.getBlockState(p).equals(placed.get(p)), Component.literal(
                        placed.get(p).getBlock().getName().getString() + " is back"
                                + (wrecks ? " after undo" : " when it passes") + ", not " + level.getBlockState(p)));
            }
            helper.assertTrue(displaysOf(level, o, id) == 0, Component.literal("every display has gone"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200, randomTicks = false)
    public static void aLittleWreckerTearsUpFlowersAndSandButNotLogs(GameTestHelper helper) {
        tears(helper, "little", List.of(Blocks.POPPY.defaultBlockState(), Blocks.SAND.defaultBlockState()),
                List.of(Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()));
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200, randomTicks = false)
    public static void aBigWreckerTearsUpHisHouseButNeverAChestAndUndoBuildsItBack(GameTestHelper helper) {
        tears(helper, "big", List.of(Blocks.SAND.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(),
                Blocks.OAK_PLANKS.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState()), List.of());
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1400, randomTicks = false)
    public static void aBigOneThatSparesHisBuildsTakesOnlyWhatTheWorldGrew(GameTestHelper helper) {
        tears(helper, "big", List.of(Blocks.POPPY.defaultBlockState(), Blocks.SAND.defaultBlockState(),
                Blocks.OAK_LOG.defaultBlockState()), List.of(Blocks.OAK_PLANKS.defaultBlockState(),
                Blocks.STONE_BRICKS.defaultBlockState(), Blocks.GLASS.defaultBlockState()), false);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1400, randomTicks = false)
    public static void aGiganticSafeOneStillNeverTouchesHisBuild(GameTestHelper helper) {
        tears(helper, "gigantic", List.of(Blocks.OAK_LOG.defaultBlockState()), List.of(Blocks.OAK_PLANKS.defaultBlockState(),
                Blocks.STONE_BRICKS.defaultBlockState()), false);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900, randomTicks = false)
    public static void aWhirlpoolSinksABoatThenSpitsItOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 30);
        platform(level, o, 16, Blocks.STONE.defaultBlockState());
        // A pond five deep.
        for (int x = -14; x <= 14; x++) {
            for (int z = -14; z <= 14; z++) {
                for (int y = 0; y > -5; y--) {
                    level.setBlock(o.offset(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
        Entity boat = EntityType.OAK_BOAT.create(level, EntitySpawnReason.COMMAND);
        boat.snapTo(o.getX() + 3.5, o.getY() + 1, o.getZ() + 0.5, 0, 0);
        level.addFreshEntity(boat);
        double y0 = boat.getY();
        double[] lowest = {0}, highest = {0}, turned = {0};
        double[] lastA = {Math.atan2(boat.getZ() - o.getZ() - 0.5, boat.getX() - o.getX() - 0.5)};
        String id = stage(helper, design("{\"name\":\"wp\",\"motion\":\"vortex\",\"size\":\"medium\",\"shape\":\"drain\"}"),
                o);
        helper.onEachTick(() -> {
            lowest[0] = Math.min(lowest[0], boat.getY() - y0);
            highest[0] = Math.max(highest[0], boat.getY() - y0);
            double a = Math.atan2(boat.getZ() - o.getZ() - 0.5, boat.getX() - o.getX() - 0.5);
            double da = Math.atan2(Math.sin(a - lastA[0]), Math.cos(a - lastA[0]));
            turned[0] += Math.abs(da);
            lastA[0] = a;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(level, id), Component.literal("the whirlpool has gone"));
            helper.assertTrue(turned[0] > Math.PI * 2, Component.literal("the boat went round: " + turned[0] + " rad"));
            helper.assertTrue(lowest[0] < -1, Component.literal("and was sucked down: " + lowest[0]));
            helper.assertTrue(highest[0] > 1, Component.literal("and spat out: " + highest[0]));
            helper.assertTrue(level.getBlockState(o).is(Blocks.WATER), Component.literal("the pond is still a pond"));
            helper.assertTrue(displaysOf(level, o, id) == 0, Component.literal("every display has gone"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900, randomTicks = false)
    public static void aHurricaneBringsLightningAndLeavesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 40);
        platform(level, o, 20, Blocks.GRASS_BLOCK.defaultBlockState());
        var chicken = mob(level, EntityType.CHICKEN, o.offset(5, 1, 0));
        double c0 = chicken.getX();
        java.util.Set<java.util.UUID> bolts = new java.util.HashSet<>();
        double[] blown = {0};
        String id = stage(helper, design("{\"name\":\"h\",\"motion\":\"vortex\",\"size\":\"little\",\"shape\":\"spiral\"}"), o);
        boolean[] rained = {false};
        boolean before = level.isRaining();
        helper.onEachTick(() -> {
            rained[0] |= level.isRaining() != before;
            level.getEntities((Entity) null, new AABB(o).inflate(30), e -> e instanceof LightningBolt)
                    .forEach(e -> bolts.add(e.getUUID()));
            blown[0] = Math.max(blown[0], Math.hypot(chicken.getX() - c0, chicken.getZ() - o.getZ() - 0.5));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(level, id), Component.literal("the hurricane has passed"));
            helper.assertTrue(bolts.size() >= 2, Component.literal("lightning struck its eye wall: " + bolts.size()));
            helper.assertTrue(blown[0] > 2, Component.literal("the wind blew a chicken about: " + blown[0]));
            helper.assertTrue(displaysOf(level, o, id) == 0, Component.literal("every display has gone"));
            helper.assertTrue(level.getBlockState(o).is(Blocks.GRASS_BLOCK), Component.literal("visual lightning burns nothing"));
            helper.assertTrue(!rained[0], Component.literal("the server's weather never changed: the storm's rain is "
                    + "sent to the players near it, so no other island's sky does (a shared world: we all share one sky)"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900, randomTicks = false)
    public static void aCycloneOfCatsLeavesItsCatsAndGivesTheGroundBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 40);
        platform(level, o, 24, Blocks.GRASS_BLOCK.defaultBlockState());
        BlockPos poppy = o.above();
        level.setBlock(poppy, Blocks.POPPY.defaultBlockState(), 3);
        String id = stage(helper, design("{\"name\":\"cats\",\"motion\":\"vortex\",\"size\":\"medium\",\"path\":\"stay\","
                + "\"drop\":\"cat\",\"spares\":[\"builds\"]}"), o);
        String tag = EventDirector.entityTag(id);
        helper.succeedWhen(() -> {
            var ev = EventDirector.get(level.getServer(), id);
            helper.assertTrue(ev == null || ev.phase() == EventPhase.KEPT, Component.literal("it has spun down"));
            helper.assertTrue(displaysOf(level, o, id) == 0, Component.literal("every display has gone"));
            int cats = level.getEntities((Entity) null, new AABB(o).inflate(60), e -> e.getType() == EntityType.CAT
                    && e.getTags().contains(tag)).size();
            helper.assertTrue(cats >= 20, Component.literal("a cyclone FULL of cats (asked for: way more cats), landed and "
                    + "stayed: " + cats));
            helper.assertTrue(level.getBlockState(poppy).is(Blocks.POPPY), Component.literal("the poppy it tore up is back"));
            // And undo takes the cats away.
            EventDirector.undo(level.getServer(), id);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900, randomTicks = false)
    public static void aWreckingTornadoBattersWhatItCatchesButNeverHisPet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 40);
        platform(level, o, 24, Blocks.GRASS_BLOCK.defaultBlockState());
        var cow = mob(level, EntityType.COW, o.offset(1, 1, 0));
        var wolf = mob(level, EntityType.WOLF, o.offset(-1, 1, 0));
        var owner = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        wolf.tame(owner);
        float wolfFull = wolf.getHealth();
        float cowFull = cow.getHealth();
        float[] cowLowest = {cowFull};
        float[] wolfLowest = {wolfFull};
        String id = stage(helper, design("{\"name\":\"b\",\"motion\":\"vortex\",\"size\":\"medium\",\"path\":\"stay\"}"), o);
        helper.onEachTick(() -> {
            cowLowest[0] = Math.min(cowLowest[0], cow.isAlive() ? cow.getHealth() : 0f);
            wolfLowest[0] = Math.min(wolfLowest[0], wolf.isAlive() ? wolf.getHealth() : 0f);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(level, id), Component.literal("it has passed"));
            // DAMAGE DEALT, not a health reached. A medium vortex deals one 3-point blow on each 20th tick the
            // cow is in its funnel (EventBuild: `caughtBy.every(20)`, Effects.batter). HOW MANY such ticks it is caught
            // on is the cow's flight -- thrown out, floating down, walking, panicking -- which draws on the level's
            // random that every concurrent test shares: alone it is caught twice (10 -> 4, five runs of five); in a
            // release smoke, once (10 -> 7.0). Two blows was never the claim; "it batters what it catches" is.
            helper.assertTrue(cowFull - cowLowest[0] >= MEDIUM_BLOW, Component.literal("a wrecking tornado batters the cow it "
                    + "sucks up (by design: it may kill) -- at least one " + MEDIUM_BLOW + "-point blow: lowest " + cowLowest[0]
                    + " of " + cowFull));
            helper.assertTrue(wolf.isAlive() && wolfLowest[0] >= wolfFull, Component.literal("but never hurts his pet: "
                    + "lowest " + wolfLowest[0] + " of " + wolfFull));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900, randomTicks = false)
    public static void aWreckingWaveSmashesHisWallLeavesItSmashedAndUndoRebuildsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 80);
        for (int x = -4; x <= 70; x++) {
            for (int z = -14; z <= 14; z++) {
                level.setBlock(o.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(o.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int y = 1; y < 14; y++) {
                    level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        List<BlockPos> wall = new ArrayList<>();
        for (int z = -2; z <= 2; z++) {
            for (int y = 1; y <= 2; y++) {
                BlockPos p = o.offset(20, y, z);
                level.setBlock(p, Blocks.STONE_BRICKS.defaultBlockState(), 3);
                wall.add(p);
            }
        }
        // Yaw -90 faces +X: the wave rolls east, through the wall.
        EventBuild.Result r = EventBuild.stage(level, design("{\"name\":\"ww\",\"motion\":\"moving_front\","
                + "\"size\":\"little\",\"material\":\"water\"}"), o, -90f, null, "", 0);
        String id = endsWithTheTest(helper, r.id());
        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(ended(level, id), Component.literal("the wave has passed"));
            if (!undone[0]) {
                long smashed = wall.stream().filter(p -> level.getBlockState(p).isAir()).count();
                helper.assertTrue(smashed == wall.size(), Component.literal("a wrecking wave smashed his wall and it "
                        + "stays smashed: " + smashed + " of " + wall.size()));
                helper.assertTrue(wall.stream().noneMatch(p -> level.getFluidState(p).isSource()),
                        Component.literal("its water went with it"));
                undone[0] = EventDirector.undo(level.getServer(), id);
                helper.assertTrue(false, Component.literal("undo asked"));
            }
            for (BlockPos p : wall) {
                helper.assertTrue(level.getBlockState(p).is(Blocks.STONE_BRICKS), Component.literal("undo rebuilt " + p));
            }
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900, randomTicks = false)
    public static void anUndoMidSpinTakesItAllAway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 40);
        platform(level, o, 24, Blocks.GRASS_BLOCK.defaultBlockState());
        List<BlockPos> logs = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            BlockPos p = o.offset(k % 3 - 1, 1, k / 3 - 1);
            level.setBlock(p, Blocks.OAK_LOG.defaultBlockState(), 3);
            logs.add(p);
        }
        String id = stage(helper, design("{\"name\":\"u\",\"motion\":\"vortex\",\"size\":\"big\",\"path\":\"stay\"}"), o);
        boolean[] undone = {false};
        helper.runAfterDelay(80, () -> {
            undone[0] = EventDirector.undo(level.getServer(), id);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(undone[0], Component.literal("undo was asked"));
            helper.assertTrue(ended(level, id), Component.literal("it is gone"));
            helper.assertTrue(displaysOf(level, o, id) == 0, Component.literal("every display went with it"));
            for (BlockPos p : logs) {
                helper.assertTrue(level.getBlockState(p).is(Blocks.OAK_LOG), Component.literal("the log at " + p + " is back"));
            }
        });
    }

    // ──, the vortex leak: nothing of an event outlives its test, or reaches another child's wish ─────────────────────────

    /**
     * An event with no owner (a command with nobody near, a GameTest) is nobody's wish: the wish camera films a
     * child's OWN event, never it. Before the fix every ownerless event was everybody's for two minutes, and this
     * batch's tornadoes were filmed by the next batches' wishes (six seconds each, holding the camera) until
     * {@code OverlappingWishesGameTest} ran out of wall clock and the tests after it failed too.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600, randomTicks = false)
    public static void anOwnerlessVortexIsNeverFilmedForAChildsWish(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 30);
        platform(level, o, 16, Blocks.GRASS_BLOCK.defaultBlockState());
        String nobodys = stage(helper, design("{\"name\":\"own0\",\"motion\":\"vortex\",\"size\":\"little\","
                + "\"path\":\"stay\",\"spares\":[\"builds\",\"creatures\"]}"), o);
        EventBuild.Result his = EventBuild.stage(level, design("{\"name\":\"own1\",\"motion\":\"vortex\","
                + "\"size\":\"little\",\"path\":\"stay\",\"spares\":[\"builds\",\"creatures\"]}"),
                o.offset(10, 0, 0), 180f, null, "gt_vortex_owner", 0);
        helper.assertTrue(his.ok(), Component.literal("his vortex was staged: " + his.error()));
        endsWithTheTest(helper, his.id());
        var server = level.getServer();
        long window = 120_000;
        StagedEvent forHim = EventDirector.latestFocused(server, "gt_vortex_owner", window);
        helper.assertTrue(forHim != null && forHim.id().equals(his.id()), Component.literal(
                "his photograph films his own vortex, not " + (forHim == null ? "nothing" : forHim.id())));
        StagedEvent forAnother = EventDirector.latestFocused(server, "gt_vortex_someone_else", window);
        helper.assertTrue(forAnother == null, Component.literal("another child's photograph films neither nobody's "
                + "event nor his: it went to " + (forAnother == null ? "" : forAnother.id()
                + (forAnother.id().equals(nobodys) ? " (the ownerless one)" : ""))));
        helper.assertTrue(EventDirector.latestFocused(server, null, window) != null,
                Component.literal("a photograph with no child behind it still finds an event"));
        helper.succeed();
    }

    /** A test's event ends WITH it: {@link EventDirector#endForTest} gives the world back and forgets it, at once. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900, randomTicks = false)
    public static void anEventEndedForItsTestLeavesNothingBehind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 40);
        platform(level, o, 24, Blocks.GRASS_BLOCK.defaultBlockState());
        List<BlockPos> logs = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            BlockPos p = o.offset(k % 3 - 1, 1, k / 3 - 1);
            level.setBlock(p, Blocks.OAK_LOG.defaultBlockState(), 3);
            logs.add(p);
        }
        String id = stage(helper, design("{\"name\":\"end\",\"motion\":\"vortex\",\"size\":\"big\",\"path\":\"stay\","
                + "\"drop\":\"cat\"}"), o);
        String tag = EventDirector.entityTag(id);
        boolean[] ended = {false};
        helper.succeedWhen(() -> {
            if (!ended[0]) {
                helper.assertTrue(logs.stream().anyMatch(p -> !level.getBlockState(p).is(Blocks.OAK_LOG)),
                        Component.literal("waiting for it to tear up a log"));
                helper.assertTrue(displaysOf(level, o, id) > 0, Component.literal("waiting for it to be drawn"));
                helper.assertTrue(!level.getEntities((Entity) null, new AABB(o).inflate(80),
                        e -> e.getType() == EntityType.CAT && e.getTags().contains(tag)).isEmpty(),
                        Component.literal("waiting for its first cat"));
                EventDirector.endForTest(level.getServer(), id);
                ended[0] = true;
            }
            helper.assertTrue(EventDirector.get(level.getServer(), id) == null, Component.literal("it is forgotten"));
            helper.assertTrue(displaysOf(level, o, id) == 0, Component.literal("every display went with it"));
            helper.assertTrue(level.getEntities((Entity) null, new AABB(o).inflate(80), e -> e.getTags().contains(tag))
                    .isEmpty(), Component.literal("and every cat it brought"));
            for (BlockPos p : logs) {
                helper.assertTrue(level.getBlockState(p).is(Blocks.OAK_LOG), Component.literal("the log at " + p + " is back"));
            }
            helper.assertTrue(EventDirector.latestFocused(level.getServer(), null, 120_000) == null
                    || !EventDirector.latestFocused(level.getServer(), null, 120_000).id().equals(id),
                    Component.literal("no camera can find it"));
        });
    }

    /**
     * A hurricane undone mid-spin gives him his sky back. Its rain is SENT to the players near it, so before the fix an
     * undo (which drops the motion without its ending) left his sky raining until he relogged.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600, randomTicks = false)
    public static void anUndoneHurricaneGivesHimHisSkyBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(helper, o, 40);
        platform(level, o, 20, Blocks.GRASS_BLOCK.defaultBlockState());
        var him = MockPlayerNetwork.inLevel(helper, "gt_vortex_sky");
        MockPlayerNetwork.afterTest(helper, () -> MockPlayerNetwork.remove(him));
        him.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);   // watching from the eye, never caught
        him.snapTo(o.getX() + 0.5, o.getY() + 1, o.getZ() + 0.5, 0, 0);
        String id = stage(helper, design("{\"name\":\"sky\",\"motion\":\"vortex\",\"size\":\"little\",\"shape\":\"spiral\"}"), o);
        StagedEvent staged = EventDirector.get(level.getServer(), id);
        var vm = (com.moddingfromamod.event.motion.VortexMotion)
                ((com.moddingfromamod.event.catching.CatchingMotion) staged.motion).inner();
        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            if (level.isRaining()) {
                return;   // real rain: the storm sends none, so there is nothing to give back
            }
            if (!undone[0]) {
                helper.assertTrue(vm.playersInWeather() > 0, Component.literal("waiting for its rain to reach him"));
                undone[0] = EventDirector.undo(level.getServer(), id);
            }
            helper.assertTrue(vm.playersInWeather() == 0, Component.literal("undo gave him his own sky back: "
                    + vm.playersInWeather() + " still rained on"));
        });
    }
}
