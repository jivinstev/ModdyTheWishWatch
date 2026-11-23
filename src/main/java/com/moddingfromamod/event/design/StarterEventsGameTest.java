package com.moddingfromamod.event.design;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Optional;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.event.StagedEvent;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.WishCatalog;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.wish.WishTemplates;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The three canned events, set off from a child's own words, really happen — and undo gives the
 * world back</b> (E6).
 *
 * <p>{@code StarterReplayGameTest} proves every starter's command SUCCEEDS; a {@code moddyevent stage}
 * that staged an event which then changed nothing succeeds too. So each test here goes on to look at
 * the world: the wave pours water and takes it all away again by itself, the meteor falls, lands on
 * dry ground and leaves a crater that undo fills, and the chickens come down and undo takes them.
 * Each runs in its own far lane with its chunks forced, on the flat test world's grass.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class StarterEventsGameTest {

    private static final String BATCH = "starterevents";
    private static final int LANE_Z = 108_000;
    private static final int REACH = 128;

    /** Stand a mock child on the grass at {@code x}, facing south, and run the starter his words pick. */
    private static List<String> wish(GameTestHelper helper, int x, String starterId, ServerPlayer[] childOut) {
        ServerPlayer child = stand(helper, x, childOut);
        WishTemplates live = WishCatalog.templates();
        WishTemplates.Known starter = live.known().stream().filter(k -> k.id().equals(starterId))
                .findFirst().orElse(null);
        helper.assertTrue(starter != null, Component.literal("the starter " + starterId + " is not served"));
        Optional<WishTemplates.Confident> m = live.matchConfident(starter.phrase());
        helper.assertTrue(m.isPresent() && m.get().templateId().equals(starterId),
                Component.literal("his own words \"" + starter.phrase() + "\" were served by "
                        + m.map(WishTemplates.Confident::templateId).orElse("nothing")));
        return run(helper, child, m.get().plan().commands(), starterId);
    }

    /**
     * An event the library keeps that is no longer a starter (took the tidal wave off the
     * reel): its own command, as a granted wish would run it.
     */
    private static List<String> stage(GameTestHelper helper, int x, String design, ServerPlayer[] childOut) {
        return run(helper, stand(helper, x, childOut), List.of("moddyevent stage " + design), design);
    }

    private static ServerPlayer stand(GameTestHelper helper, int x, ServerPlayer[] childOut) {
        ServerLevel level = helper.getLevel();
        for (int cx = (x - REACH) >> 4; cx <= (x + REACH) >> 4; cx++) {
            for (int cz = (LANE_Z - REACH) >> 4; cz <= (LANE_Z + REACH) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        BlockPos ground = EventBuild.ground(level, x, LANE_Z);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        child.snapTo(x + 0.5, ground.getY() + 1, LANE_Z + 0.5, 0f, 10f);
        childOut[0] = child;
        return child;
    }

    private static List<String> run(GameTestHelper helper, ServerPlayer child, List<String> commands, String what) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Vec3 at = child.position();
        List<String> ids = new java.util.ArrayList<>();
        for (String cmd : commands) {
            WishExecutor.Result r = WishExecutor.runOne(server, level, at, child, cmd);
            helper.assertTrue(r.success(), Component.literal("`" + cmd + "` failed: " + r.joined()));
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("\\(([a-z0-9_]+)\\)").matcher(r.joined());
            while (id.find()) {
                ids.add(id.group(1));
            }
        }
        helper.assertTrue(!ids.isEmpty(), Component.literal(what + " staged no event it could name"));
        return ids;
    }

    private static int count(ServerLevel level, BlockPos centre, int r, int dy0, int dy1,
                             java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> is) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x += 2) {
            for (int z = -r; z <= r; z += 2) {
                for (int y = dy0; y <= dy1; y++) {
                    if (is.test(level.getBlockState(p.set(centre.getX() + x, centre.getY() + y, centre.getZ() + z)))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 2400)
    public static void theTidalWaveRollsInCarriesAndGoesAwayByItself(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int x = 91_200;
        ServerPlayer[] child = new ServerPlayer[1];
        String id = stage(helper, x, "tidal_wave", child).get(0);
        BlockPos centre = EventBuild.ground(level, x, LANE_Z);
        int[] peak = {0};
        int[] ahead = {0};
        int[] behind = {0};
        helper.succeedWhen(() -> {
            StagedEvent e = EventDirector.get(server, id);
            if (e != null && e.age() < 40) {
                // He faces south (+z): the wave must be born out there, where he is looking, and come in.
                ahead[0] = Math.max(ahead[0], count(level, centre.offset(0, 0, 70), 30, 0, 20, s -> s.is(Blocks.WATER)));
                behind[0] = Math.max(behind[0], count(level, centre.offset(0, 0, -40), 30, 0, 20, s -> s.is(Blocks.WATER)));
            }
            if (e != null) {
                peak[0] = Math.max(peak[0], e.changed());
                helper.fail(Component.literal("still rolling (" + e.phase() + ", " + e.changed() + " blocks changed)"));
            }
            helper.assertTrue(peak[0] > 1_000, Component.literal("the wave poured only " + peak[0] + " blocks of water"));
            helper.assertTrue(ahead[0] > 0 && behind[0] == 0, Component.literal("the wave was not born out ahead of him coming in "
                    + "(water sampled ahead " + ahead[0] + ", behind " + behind[0] + ")"));
            int left = count(level, centre, 120, -2, 30, s -> s.is(Blocks.WATER));
            helper.assertTrue(left == 0, Component.literal("the wave left " + left + " water blocks behind (sampled)"));
            MockPlayerNetwork.remove(child[0]);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 2400)
    public static void theMeteorFallsOnLandLeavesACraterAndUndoFillsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int x = 91_600;
        ServerPlayer[] child = new ServerPlayer[1];
        String fall = wish(helper, x, "starter_meteor", child).get(0);
        BlockPos centre = EventBuild.ground(level, x, LANE_Z);
        String[] crater = {null};
        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            if (crater[0] == null) {
                helper.assertTrue(EventDirector.get(server, fall) == null, Component.literal("the meteor is still falling"));
                for (StagedEvent e : EventDirector.all(server)) {
                    if (e.id().startsWith("meteor_crater_")) {
                        crater[0] = e.id();
                    }
                }
                helper.assertTrue(crater[0] != null, Component.literal("the meteor landed on grass and no crater followed "
                        + "(a splash would mean it thought it hit water)"));
            }
            StagedEvent c = EventDirector.get(server, crater[0]);
            if (!undone[0]) {
                helper.assertTrue(c != null && c.changed() > 200, Component.literal("the crater is still being dug: "
                        + (c == null ? "gone" : c.changed() + " blocks")));
                helper.assertTrue(c.phase() != com.moddingfromamod.event.EventPhase.RUNNING,
                        Component.literal("the crater is still being dug (" + c.changed() + " blocks)"));
                undone[0] = EventDirector.undo(server, crater[0]);
                helper.fail(Component.literal("undoing the crater"));
            }
            helper.assertTrue(c == null, Component.literal("the crater's undo is still putting the ground back"));
            int burnt = count(level, centre, 40, -12, 4,
                    s -> s.is(Blocks.BLACKSTONE) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.COBBLED_DEEPSLATE));
            helper.assertTrue(burnt == 0, Component.literal("undo left " + burnt + " crater blocks (sampled)"));
            MockPlayerNetwork.remove(child[0]);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void itRainsChickensAndUndoTakesThemAway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int x = 92_000;
        ServerPlayer[] child = new ServerPlayer[1];
        String id = wish(helper, x, "starter_chicken_rain", child).get(0);
        AABB area = new AABB(x - REACH, -64, LANE_Z - REACH, x + REACH, 200, LANE_Z + REACH);
        int[] most = {0};
        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            int now = level.getEntitiesOfClass(Chicken.class, area).size();
            most[0] = Math.max(most[0], now);
            StagedEvent e = EventDirector.get(server, id);
            if (!undone[0]) {
                helper.assertTrue(e != null && e.phase() != com.moddingfromamod.event.EventPhase.RUNNING,
                        Component.literal("chickens still falling (" + now + " so far)"));
                helper.assertTrue(most[0] >= 10, Component.literal("only " + most[0] + " chickens came down"));
                int cap = DesignSizes.skyCreatures(com.moddingfromamod.event.EventScale.LITTLE);
                helper.assertTrue(most[0] <= cap, Component.literal(most[0] + " chickens came down; a little shower drops " + cap + " at most"));
                undone[0] = EventDirector.undo(server, id);
                helper.fail(Component.literal("undoing the chicken rain"));
            }
            helper.assertTrue(EventDirector.get(server, id) == null, Component.literal("the undo is still running"));
            helper.assertTrue(now == 0, Component.literal(now + " chickens are still here after undo"));
            MockPlayerNetwork.remove(child[0]);
        });
    }
}
