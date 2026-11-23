package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.tools.BuildStructureTool;
import com.moddingfromamod.agent.tools.InspectBuildTool;
import com.moddingfromamod.agent.tools.RemoveBuildTool;
import com.moddingfromamod.agent.tools.TakeMeToTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.vanilla.SpotSeat;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishSource;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Where a building stands survives a restart </b> — through the four tools a child's
 * wish reaches it by: {@code remove_build}, {@code take_me_to}, {@code inspect_build} and an edit
 * that must rebuild IN PLACE, plus its named spot.
 *
 * <p>Two ways to run it, one test:
 * <ul>
 *   <li><b>default</b> (every Gate B): builds through the real {@code build_structure}, then reads
 *   each position back from DISK past the in-memory mirror ({@code BuildRegistry.savedPlacementOnDisk},
 *   the same row-reading {@code load} does) — what a restart would find — and drives the four tools.
 *   It never clears the registry: its neighbours read it (LEARNINGS, the per-test-seam rule).</li>
 *   <li><b>a REAL restart</b>: {@code -Ptestprops=restartphase=build} builds and writes down what
 *   it built; the process exits; a second {@code runGameTestServer} over the same world with
 *   {@code restartphase=check} and {@code -Pkeepworld} (or the gate wipes the world first) drives
 *   the four tools against what the first boot left on disk — a fresh JVM, nothing in memory.
 *   Measured RED with the record's position not read back at load (the pre-#1376 behaviour):
 *   "after a restart gt_restart_castle is at null". The recipe is in docs/WISH_STORES.md §21.</li>
 * </ul>
 *
 * <p>Its own batch: it moves a mock child 400 blocks and back, which a neighbour would feel.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BuildPositionRestartGameTest {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("mfam-restart-1376");

    /** One child across both boots, so an edit is HIS edit (a stranger's would fork it). */
    static final UUID CHILD = UUID.fromString("13761376-0000-4000-8000-000000001376");
    static final String WORKER = "gt-restart-1376";
    static final String CASTLE = "gt_restart_castle", TOWER = "gt_restart_tower", SHED = "gt_restart_shed";
    static final String PROOF = "restart-proof-1376.json";

    @GameTest(template = "empty_test", batch = "build_restart", timeoutTicks = 4000)
    public static void aBuildingsPositionSurvivesARestart(GameTestHelper helper) {
        String phase = System.getProperty(ModdingFromAModMod.MODID + ".restartphase", "").trim();
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper, "restart_kid", CHILD);
        Path proof = server.getWorldPath(LevelResource.ROOT).resolve(PROOF);
        AtomicReference<String> failed = new AtomicReference<>();
        AtomicReference<String> done = new AtomicReference<>();
        offThread(() -> {
            try {
                JsonObject before;
                if ("check".equals(phase)) {
                    // A REAL restart: this process never built anything; what the last one did is on disk.
                    before = JsonParser.parseString(Files.readString(proof, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                } else {
                    before = buildAll(server, mock);
                    if ("build".equals(phase)) {
                        Files.writeString(proof, before.toString(), StandardCharsets.UTF_8);
                        done.set("built and written down: " + before);
                        return;
                    }
                    // What a restart would find: each position as it is ON DISK.
                    for (String id : new String[]{CASTLE, TOWER, SHED}) {
                        var saved = AgentBridge.onServer(() -> BuildRegistry.savedPlacementOnDisk(server, id));
                        long at = before.get(id).getAsLong();
                        if (saved == null || saved.removed()
                                || BlockPos.asLong(saved.x(), saved.y(), saved.z()) != at
                                || !server.overworld().dimension().location().toString().equals(saved.dim())) {
                            throw new AssertionError("#1376: " + id + " was put up at "
                                    + BlockPos.of(at).toShortString() + " and the disk says " + saved);
                        }
                    }
                }
                done.set(checkAll(server, mock, before));
            } catch (Throwable t) {
                failed.set(t.toString());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(failed.get() == null, Component.literal("#1376: " + failed.get()));
            helper.assertTrue(done.get() != null, Component.literal("waiting for the tools"));
            LOG.info("[restart-1376] phase={} {}",
                    phase.isEmpty() ? "in-process" : phase, done.get());
            MockPlayerNetwork.remove(mock);
        });
    }

    /** Three buildings through the real tool, apart; returns where each went. */
    static JsonObject buildAll(MinecraftServer server, ServerPlayer mock) throws Exception {
        Wish wish = bind(server, mock, "a castle, a tower and a shed");
        JsonObject at = new JsonObject();
        try {
            for (String id : new String[]{CASTLE, TOWER, SHED}) {
                String said = AgentContext.with(WORKER, () -> new BuildStructureTool().call(
                        args(BuildSpotsGameTest.hallJson(id).replaceFirst("\\{",
                                "{\"as_new\":true,\"offsetForward\":" + forwardOf(id) + ",\"offsetY\":30,")))
                        .text());
                if (!said.contains("Saved as '" + id + "'")) {
                    throw new AssertionError("build_structure did not build " + id + ": " + said);
                }
            }
            waitBuilt(CASTLE, TOWER, SHED);
            for (String id : new String[]{CASTLE, TOWER, SHED}) {
                Long a = AgentBridge.onServer(() -> BuildRegistry.anchorOf(id));
                if (a == null) {
                    throw new AssertionError(id + " has no anchor right after it was built");
                }
                at.addProperty(id, a);
            }
            return at;
        } finally {
            Marshal.global().finish(wish.id());
        }
    }

    /** Each building its own lane, so removing one never touches another. */
    static int forwardOf(String id) {
        return CASTLE.equals(id) ? 14 : TOWER.equals(id) ? 34 : 54;
    }

    /** After the restart: every tool finds what it found before. Returns a line for the log. */
    static String checkAll(MinecraftServer server, ServerPlayer mock, JsonObject before) throws Exception {
        long castle = before.get(CASTLE).getAsLong(), tower = before.get(TOWER).getAsLong(),
                shed = before.get(SHED).getAsLong();
        StringBuilder log = new StringBuilder();
        // 0 · the store itself
        for (String id : new String[]{CASTLE, TOWER, SHED}) {
            Long now = AgentBridge.onServer(() -> BuildRegistry.anchorOf(id));
            boolean standing = AgentBridge.onServer(() -> BuildRegistry.standing(id));
            if (now == null || now != before.get(id).getAsLong() || !standing) {
                throw new AssertionError("#1376: after a restart " + id + " is at " + now
                        + " (standing " + standing + "), it was put up at " + before.get(id).getAsLong());
            }
        }
        Wish wish = bind(server, mock, "after the restart");
        try {
            // 1 · inspect_build says where it stands
            BlockPos c = BlockPos.of(castle);
            String inspected = AgentContext.with(WORKER, () -> new InspectBuildTool()
                    .call(args("{\"id\":\"" + CASTLE + "\"}")).text());
            String where = "standing at " + c.getX() + "," + c.getY() + "," + c.getZ();
            if (!inspected.contains(where)) {
                throw new AssertionError("inspect_build after a restart must say '" + where + "': " + inspected);
            }
            log.append("inspect_build ok; ");
            // 2 · the named spot is standing
            SpotSeat.Place p = AgentBridge.onServer(() -> SpotSeat.place(server, CASTLE + ":throne"));
            if (p.why() != null || Math.abs(p.x() - (c.getX() + 5.5)) > 0.01) {
                throw new AssertionError("the castle's throne after a restart: " + p);
            }
            log.append("spot ok; ");
            // 3 · take_me_to finds it
            AgentBridge.onServer(() -> {
                com.moddingfromamod.compat.Teleports.player(mock, (ServerLevel) mock.level(),
                        c.getX() + 400.5, c.getY() + 10, c.getZ() + 400.5, 0f, 0f);
                return null;
            });
            String took = AgentContext.with(WORKER, () -> new TakeMeToTool()
                    .call(args("{\"what\":\"" + CASTLE + "\"}")).text());
            BlockPos landed = AgentBridge.onServer(mock::blockPosition);
            if (!took.startsWith("Took him to") || Math.abs(landed.getX() - c.getX()) > 1
                    || Math.abs(landed.getZ() - c.getZ()) > 1) {
                throw new AssertionError("take_me_to after a restart landed him at "
                        + landed.toShortString() + " for a castle at " + c.toShortString() + ": " + took);
            }
            log.append("take_me_to ok; ");
            // 4 · an edit rebuilds IN PLACE — he is 400 blocks away, so his aim is not where it stood
            AgentBridge.onServer(() -> {
                com.moddingfromamod.compat.Teleports.player(mock, (ServerLevel) mock.level(),
                        c.getX() + 400.5, c.getY() + 10, c.getZ() + 400.5, 0f, 0f);
                return null;
            });
            String edited = AgentContext.with(WORKER, () -> new BuildStructureTool().call(args(
                    BuildSpotsGameTest.hallJson(TOWER).replace("\"Throne Hall\"", "\"Taller Hall\"")
                            .replace("\"height\":7", "\"height\":9"))).text());
            Long towerNow = AgentBridge.onServer(() -> BuildRegistry.anchorOf(TOWER));
            if (!edited.contains("Rebuilt in place") || towerNow == null || towerNow != tower) {
                throw new AssertionError("an edit after a restart must rebuild in place at "
                        + BlockPos.of(tower).toShortString() + ", it is at "
                        + (towerNow == null ? "nowhere" : BlockPos.of(towerNow).toShortString()) + ": " + edited);
            }
            waitBuilt(TOWER);
            log.append("edit in place ok; ");
            // 5 · remove_build clears it, in its own world
            String removed = AgentContext.with(WORKER, () -> new RemoveBuildTool()
                    .call(args("{\"id\":\"" + SHED + "\"}")).text());
            if (!removed.startsWith("Removed '" + SHED + "'") || removed.contains("cleared 0 block")) {
                throw new AssertionError("remove_build after a restart: " + removed);
            }
            boolean standing = AgentBridge.onServer(() -> BuildRegistry.standing(SHED));
            String again = AgentContext.with(WORKER, () -> new RemoveBuildTool()
                    .call(args("{\"id\":\"" + SHED + "\"}")).text());
            if (standing || !again.contains("taken down")) {
                throw new AssertionError("a removed building is not standing: " + standing + " / " + again);
            }
            log.append("remove_build ok (" + removed + ")");
            // tidy the two still standing
            BuildPlan cp = BuildRegistry.get(CASTLE), tp = BuildRegistry.get(TOWER);
            AgentBridge.onServer(() -> {
                ServerLevel lv = server.overworld();
                Builder.clearBuild(lv, BlockPos.of(castle), cp);
                Builder.clearBuild(lv, BlockPos.of(tower), tp);
                BuildRegistry.markRemoved(server, CASTLE);
                BuildRegistry.markRemoved(server, TOWER);
                return null;
            });
            return log.toString();
        } finally {
            Marshal.global().finish(wish.id());
        }
    }

    // ===================== a building saved BEFORE says so, never guesses ===================

    @GameTest(template = "empty_test", batch = "build_restart_legacy", timeoutTicks = 400)
    public static void aBuildingSavedBeforePositionsSaysItDoesNotKnowWhere(GameTestHelper helper) {
        String id = "gt_restart_legacy";
        MinecraftServer server = helper.getLevel().getServer();
        BuildRegistry.put(server, BuildPlan.fromJson(args(BuildSpotsGameTest.hallJson(id))));
        AtomicReference<String> removed = new AtomicReference<>();
        AtomicReference<String> inspected = new AtomicReference<>();
        offThread(() -> {
            removed.set(new RemoveBuildTool().call(args("{\"id\":\"" + id + "\"}")).text());
            inspected.set(new InspectBuildTool().call(args("{\"id\":\"" + id + "\"}")).text());
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(removed.get() != null && inspected.get() != null, Component.literal("waiting"));
            helper.assertTrue(removed.get().contains("I don't know where '" + id + "' is any more"),
                    Component.literal("remove_build must say it does not know, not guess: " + removed.get()));
            helper.assertTrue(inspected.get().contains("not standing here: I don't know where"),
                    Component.literal("inspect_build must say so too: " + inspected.get()));
        });
    }

    // ===================== helpers =====================

    private static void waitBuilt(String... ids) throws Exception {
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until) {
            boolean busy = AgentBridge.onServer(() -> {
                for (String id : ids) {
                    if (Builder.isBuilding(id)) {
                        return true;
                    }
                }
                return false;
            });
            if (!busy) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("still building after 60 s: " + String.join(", ", ids));
    }

    private static Wish bind(MinecraftServer server, ServerPlayer mock, String text) {
        Wish wish = WishBook.get(server).add(Wish.create(text, WishSource.TYPED,
                System.currentTimeMillis(), mock.getUUID(), "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), mock.getUUID(), wish.text());
        Marshal.global().assign(WORKER, wish.id());
        return wish;
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void offThread(Runnable work) {
        Thread t = new Thread(work, "restart-1376-gametest");
        t.setDaemon(true);
        t.start();
    }
}
