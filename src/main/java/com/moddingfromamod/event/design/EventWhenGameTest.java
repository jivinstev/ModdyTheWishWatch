package com.moddingfromamod.event.design;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.Damage;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.event.StagedEvent;
import com.moddingfromamod.rules.WorldRule;
import com.moddingfromamod.rules.WorldRules;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>When an event happens, and what follows it</b> (E4), in a real world: a design kept by
 * the command and staged by it; a world rule that stages one when a creature dies; a schedule that
 * fires once its days are banked and banks nothing when the clock is wound back; and a follow-on
 * chosen by the spot — a falling body into water raises a flood, onto land leaves a crater.
 *
 * <p>Its own batch: the schedule test moves the world's day clock.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class EventWhenGameTest {

    private static final String BATCH = "eventwhen";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger LANE = new AtomicInteger();

    private static BlockPos lane() {
        return new BlockPos(90_000 + LANE.incrementAndGet() * 400, 0, 102_000);
    }

    private static void force(ServerLevel level, BlockPos c, int r) {
        for (int cx = (c.getX() - r) >> 4; cx <= (c.getX() + r) >> 4; cx++) {
            for (int cz = (c.getZ() - r) >> 4; cz <= (c.getZ() + r) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    private static String name(String what) {
        return "test_" + what + "_" + SEQ.incrementAndGet();
    }

    private static void run(MinecraftServer server, ServerLevel level, BlockPos at, String cmd) {
        CommandSourceStack src = server.createCommandSourceStack().withLevel(level)
                .withPosition(Vec3.atCenterOf(at)).withPermission(4).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(src, cmd);
    }

    /** Every live event staged from this design. */
    private static List<StagedEvent> of(MinecraftServer server, String design) {
        return EventDirector.all(server).stream().filter(e -> e.id().startsWith(design + "_")).toList();
    }

    private static void keep(MinecraftServer server, String json) {
        EventDesign.Parsed p = EventDesign.parse(json);
        if (!p.ok()) {
            throw new IllegalStateException("a test design did not parse: " + p.errors());
        }
        EventDesigns.put(server, p.design());
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aDesignKeptByTheCommandIsStagedByItAndUndone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos o = lane();
        force(level, o, 40);
        BlockPos g = EventBuild.ground(level, o.getX(), o.getZ());
        String n = name("pool");
        run(server, level, g, "moddyevent define {\"name\":\"" + n + "\",\"motion\":\"layer\","
                + "\"size\":\"little\",\"material\":\"water\",\"afterwards\":\"until_undo\"}");
        helper.assertTrue(EventDesigns.get(server, n) != null, Component.literal("the command kept the design"));
        run(server, level, g, "moddyevent stage " + n + " at " + g.getX() + " " + g.getY() + " " + g.getZ());
        helper.assertTrue(of(server, n).size() == 1, Component.literal("the command staged it"));
        String id = of(server, n).get(0).id();
        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            if (!undone[0]) {
                helper.assertTrue(level.getBlockState(g.above()).is(Blocks.WATER), Component.literal("water on the ground"));
                run(server, level, g, "moddyevent undo " + id);
                undone[0] = true;
                helper.fail(Component.literal("undo started"));
            }
            helper.assertTrue(EventDirector.get(server, id) == null, Component.literal("the undo finished"));
            helper.assertTrue(!level.getBlockState(g.above()).is(Blocks.WATER), Component.literal("and the water is gone"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300)
    public static void aWorldRuleStagesAnEventWhenACreatureDies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos o = lane();
        force(level, o, 32);
        BlockPos g = EventBuild.ground(level, o.getX(), o.getZ());
        String n = name("boss_blast");
        keep(server, "{\"name\":\"" + n + "\",\"motion\":\"impact_stamp\",\"size\":\"little\","
                + "\"material\":\"stone\",\"core\":\"magma_block\"}");
        String rule = name("rule");
        WorldRules.put(server, WorldRule.parse(JsonParser.parseString("{\"name\":\"" + rule
                + "\",\"when\":\"mob_dies\",\"who\":\"minecraft:mooshroom\",\"do\":[\"moddyevent stage "
                + n + "\"]}").getAsJsonObject()));
        MushroomCow boss = EntityType.MOOSHROOM.create(level, EntitySpawnReason.COMMAND);
        boss.snapTo(g.getX() + 0.5, g.getY() + 1, g.getZ() + 0.5, 0, 0);
        level.addFreshEntity(boss);
        helper.runAfterDelay(5, () -> Damage.hurt(boss, level.damageSources().generic(), 1000));
        helper.succeedWhen(() -> {
            helper.assertTrue(boss.isDeadOrDying(), Component.literal("the creature died"));
            List<StagedEvent> staged = of(server, n);
            helper.assertTrue(staged.size() == 1, Component.literal("its death staged the event: "
                    + staged.size() + " (last rule error: " + WorldRules.lastError(rule) + ")"));
            WorldRules.remove(server, rule);
            EventDirector.undo(server, staged.get(0).id());
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aScheduleFiresWhenItsDaysAreBankedAndRewindingBanksNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        long was = level.getDayTime();
        String n = name("quake");
        // Effects only, no blocks: staged beside a mock in a shared plot, it must touch nothing.
        keep(server, "{\"name\":\"" + n + "\",\"motion\":\"none\",\"size\":\"little\",\"seconds\":5}");
        helper.assertTrue(EventDesigns.every(server, n, 1, child.getGameProfile().name()) == null,
                Component.literal("scheduled every day"));
        EventDesigns.Store store = EventDesigns.store(server);
        try {
            store.reloadForTest(level.registryAccess()); // as after a load: the first look only re-bases
            long t0 = 100_000L;
            level.setDayTime(t0);
            EventDesigns.tickSchedules(server);
            level.setDayTime(t0 + 12_000);
            EventDesigns.tickSchedules(server);
            helper.assertTrue(of(server, n).isEmpty(), Component.literal("half a day is not a day"));
            long banked = bank(server, n);
            level.setDayTime(t0);
            EventDesigns.tickSchedules(server);
            helper.assertTrue(bank(server, n) == banked, Component.literal("winding the clock back banked nothing: "
                    + banked + " -> " + bank(server, n)));
            store.reloadForTest(level.registryAccess());
            helper.assertTrue(bank(server, n) == banked, Component.literal("the bank survives a save and a reload"));
            level.setDayTime(t0 + 1);
            EventDesigns.tickSchedules(server); // re-base after the reload
            level.setDayTime(t0 + 1 + 12_000);
            EventDesigns.tickSchedules(server);
            helper.assertTrue(of(server, n).size() == 1, Component.literal("a whole day banked: it happened"));
            helper.assertTrue(bank(server, n) < 24_000, Component.literal("and the day was spent: " + bank(server, n)));
            level.setDayTime(t0 + 1 + 12_000 + 24_000 * 3);
            EventDesigns.tickSchedules(server);
            helper.assertTrue(of(server, n).size() == 1, Component.literal("never a second while the first is still going"));
        } finally {
            EventDesigns.stop(server, n);
            of(server, n).forEach(e -> EventDirector.undo(server, e.id()));
            level.setDayTime(was);
            MockPlayerNetwork.remove(child);
        }
        helper.succeed();
    }

    private static long bank(MinecraftServer server, String design) {
        return EventDesigns.schedules(server).stream().filter(s -> s.design().equals(design))
                .findFirst().map(EventDesigns.Schedule::banked).orElse(-1L);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900)
    public static void aFallInWaterRaisesAFloodAndOnLandLeavesACrater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos sea = lane(), land = lane();
        force(level, sea, 80);
        force(level, land, 80);
        // A pond at the sea lane, dry ground at the other.
        BlockPos sg = EventBuild.ground(level, sea.getX(), sea.getZ());
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                level.setBlock(sg.offset(dx, 0, dz), Blocks.WATER.defaultBlockState(), 3);
                level.setBlock(sg.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        String flood = name("sea_flood"), crater = name("land_crater"), rock = name("rock");
        keep(server, "{\"name\":\"" + flood + "\",\"motion\":\"layer\",\"size\":\"little\",\"material\":\"water\"}");
        keep(server, "{\"name\":\"" + crater + "\",\"motion\":\"impact_stamp\",\"size\":\"little\","
                + "\"material\":\"stone\"}");
        keep(server, "{\"name\":\"" + rock + "\",\"motion\":\"falling_body\",\"size\":\"little\","
                + "\"material\":\"shroomlight\",\"then\":[{\"if\":\"water_near\",\"design\":\"" + flood
                + "\"},{\"if\":\"on_land\",\"design\":\"" + crater + "\"}]}");
        EventDesign d = EventDesigns.get(server, rock);
        EventBuild.Result a = EventBuild.stage(level, d, sg, 0, null, "", 0);
        EventBuild.Result b = EventBuild.stage(level, d, EventBuild.ground(level, land.getX(), land.getZ()),
                0, null, "", 0);
        helper.assertTrue(a.ok() && b.ok(), Component.literal("both staged: " + a.error() + " / " + b.error()));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, a.id());
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, b.id());
        helper.succeedWhen(() -> {
            List<StagedEvent> floods = of(server, flood), craters = of(server, crater);
            helper.assertTrue(EventDirector.get(server, a.id()) == null && EventDirector.get(server, b.id()) == null,
                    Component.literal("both have landed"));
            helper.assertTrue(floods.size() == 1, Component.literal("the one in the water raised a flood: " + floods.size()));
            helper.assertTrue(craters.size() == 1, Component.literal("the one on land left a crater: " + craters.size()));
            floods.forEach(e -> EventDirector.undo(server, e.id()));
            craters.forEach(e -> EventDirector.undo(server, e.id()));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void aCountdownGoesOffAndTwoDesignsThatNameEachOtherStop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos o = lane();
        force(level, o, 16);
        String clock = name("clock"), ping = name("ping"), pong = name("pong");
        // Effects-only designs, so the chain writes nothing: what is counted is how many happen.
        keep(server, "{\"name\":\"" + ping + "\",\"motion\":\"none\",\"size\":\"little\",\"seconds\":5,"
                + "\"then\":[{\"design\":\"" + pong + "\"}]}");
        keep(server, "{\"name\":\"" + pong + "\",\"motion\":\"none\",\"size\":\"little\",\"seconds\":5,"
                + "\"then\":[{\"design\":\"" + ping + "\"}]}");
        keep(server, "{\"name\":\"" + clock + "\",\"motion\":\"countdown\",\"size\":\"little\","
                + "\"seconds\":5,\"at_zero\":\"" + ping + "\"}");
        EventBuild.Result r = EventBuild.stage(level, EventDesigns.get(server, clock),
                EventBuild.ground(level, o.getX(), o.getZ()), 0, null, "", 0);
        helper.assertTrue(r.ok(), Component.literal("the countdown started: " + r.error()));
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, r.id());
        java.util.Set<String> seen = new java.util.HashSet<>();
        helper.succeedWhen(() -> {
            of(server, ping).forEach(e -> seen.add(e.id()));
            of(server, pong).forEach(e -> seen.add(e.id()));
            long pings = seen.stream().filter(i -> i.startsWith(ping + "_")).count();
            long pongs = seen.stream().filter(i -> i.startsWith(pong + "_")).count();
            helper.assertTrue(EventDirector.get(server, r.id()) == null, Component.literal("the countdown ran out"));
            helper.assertTrue(pings >= 1, Component.literal("and at zero its design happened"));
            helper.assertTrue(pings <= 2 && pongs <= 2, Component.literal("the chain never runs past " + EventBuild.MAX_CHAIN
                    + " follow-ons: " + pings + " pings, " + pongs + " pongs"));
            helper.assertTrue(pings == 2 && pongs == 2 && of(server, ping).isEmpty() && of(server, pong).isEmpty(),
                    Component.literal("four in all, then it stops"));
        });
    }
}
