package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.marshal.ArtifactOwner;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.mob.Creatures;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>His creature, changed in place — never a second one, never his brother's by accident</b>
 *.
 *
 * <ul>
 *   <li>Reshaping a creature that is not his (the library's starter dragon) makes HIS copy.
 *       {@code sculpt_creature} then treated the copy as a new creature and spawned it: "give my
 *       dragon horns" left him two dragons, the old one unchanged. The one in front of him must
 *       become his copy instead.</li>
 *   <li>"The dragon" means HIS dragon when he has one near, even with another child's standing
 *       nearer — the creature a wish spawned is stamped with that child ({@code mfamby_<id>}).</li>
 * </ul>
 *
 * <p>The oracle is the world: how many dragons stand near him, and which one wears his copy.
 * Written without the new code's API so the same file runs on {@code main} for the red line.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class HisCreatureGameTest {

    private static final String DRAGON = "pet_dragon";

    @GameTest(template = "empty_test", batch = "g1009_reshape", timeoutTicks = 600)
    public static void reshapingTheStarterDragonChangesHisAndSpawnsNoSecond(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-g1009-horns");
        UUID wish = UUID.randomUUID();
        Marshal.global().submit(wish, child.getUUID(), "give my dragon horns");
        String worker = "gt-g1009-horns-" + wish;
        Marshal.global().assign(worker, wish);
        String forkId = ArtifactOwner.forkKey(DRAGON, child.getUUID().toString());
        Set<UUID> already = uuids(level, DRAGON);
        List<LivingEntity> mine = new ArrayList<>();
        AgentContext.with(worker, () -> WishExecutor.runOne(server, level, child.position(),
                child, "wishmob spawn " + DRAGON));
        for (LivingEntity c : Creatures.carriersOf(level, DRAGON)) {
            if (!already.contains(c.getUUID())) {
                mine.add(c);
            }
        }
        helper.assertTrue(mine.size() == 1, Component.literal("expected his one dragon, got " + mine.size()));
        int partsBefore = displays(level, mine.get(0));

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(worker, () -> {
            // On the head (y 12-20, z -16..-8), overlapping it: the first version hung them in the
            // air at y 30-38 over the body, which an earlier issue's one-piece check rightly sends back.
            JsonObject horns = JsonParser.parseString("""
                {"id":"pet_dragon","start_from":"pet_dragon","parts":[
                  {"name":"hornL","from":[-4,19,-11],"to":[-2,26,-9],"colour":"red"},
                  {"name":"hornR","from":[2,19,-11],"to":[4,26,-9],"colour":"red"}]}
                """).getAsJsonObject();
            reply.set(new SculptCreatureTool().call(horns));
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("the sculpt has not answered yet"));
            helper.assertFalse(reply.get().isError(), Component.literal("the sculpt failed: " + reply.get().text()));
            List<LivingEntity> near = new ArrayList<>();
            for (String id : new String[] {DRAGON, forkId}) {
                for (LivingEntity c : Creatures.carriersOf(level, id)) {
                    if (!already.contains(c.getUUID()) && c.distanceTo(child) < 32) {
                        near.add(c);
                    }
                }
            }
            helper.assertTrue(near.size() == 1, Component.literal("#1009: he had one dragon and now has "
                    + near.size() + " near him — the reshape spawned a second. It said: "
                    + reply.get().text()));
            LivingEntity his = near.get(0);
            helper.assertTrue(Creatures.carriersOf(level, forkId).contains(his),
                    Component.literal("his one dragon does not wear his copy '" + forkId + "': tags " + his.getTags()
                            + ", fork registered " + (com.moddingfromamod.species.SpeciesRegistry
                            .get(forkId) != null) + ". It said: " + reply.get().text()));
            helper.assertTrue(displays(level, his) > partsBefore, Component.literal("his dragon has "
                    + displays(level, his) + " parts, as before the horns (" + partsBefore + ")"));
            helper.assertTrue(his.getTags().contains(tagOf(child)), Component.literal("his dragon lost the stamp "
                    + "that says it was spawned for him: " + his.getTags()));
            tidy(level, already, DRAGON, forkId);
            Marshal.global().finish(wish);
            MockPlayerNetwork.remove(child);
        });
    }

    @GameTest(template = "empty_test", batch = "g1009_mine", timeoutTicks = 600)
    public static void theDragonMeansHisEvenWithAnotherNearer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper, "mfam-g1009-alice");
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper, "mfam-g1009-bob");
        UUID wa = UUID.randomUUID();
        UUID wb = UUID.randomUUID();
        Marshal.global().submit(wa, alice.getUUID(), "make the dragon glow gold");
        Marshal.global().submit(wb, bob.getUUID(), "a dragon");
        String workerA = "gt-g1009-a-" + wa;
        String workerB = "gt-g1009-b-" + wb;
        Marshal.global().assign(workerA, wa);
        Marshal.global().assign(workerB, wb);
        String forkId = ArtifactOwner.forkKey(DRAGON, alice.getUUID().toString());
        Set<UUID> already = uuids(level, DRAGON);
        // Hers, eight blocks off (spawned by her wish, as production does, which also puts the
        // library's design into this world); his, three blocks off — nearer to her.
        AgentContext.with(workerA, () -> WishExecutor.runOne(server, level, alice.position(),
                alice, "wishmob spawn " + DRAGON));
        LivingEntity hers = null;
        for (LivingEntity c : Creatures.carriersOf(level, DRAGON)) {
            if (!already.contains(c.getUUID())) {
                hers = c;
            }
        }
        helper.assertTrue(hers != null, Component.literal("her dragon did not stand up"));
        hers.teleportTo(alice.getX() + 8, alice.getY(), alice.getZ());
        LivingEntity his = AgentContext.with(workerB, () -> CreatureSpawn.spawn(level, DRAGON,
                alice.getX() + 3, alice.getY(), alice.getZ(), 0f, EntitySpawnReason.COMMAND));
        helper.assertTrue(his != null, Component.literal("his dragon did not stand up"));
        UUID hisId = his.getUUID();
        net.minecraft.world.phys.Vec3 herSpot = hers.position();

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(workerA, () -> reply.set(new ModifyMobTool().call(JsonParser.parseString(
                "{\"target\":\"pet_dragon\",\"scope\":\"one\",\"glow\":\"gold\"}")
                .getAsJsonObject())));

        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("modify_mob has not answered yet"));
            helper.assertFalse(reply.get().isError(), Component.literal("modify_mob: " + reply.get().text()));
            var forked = Creatures.carriersOf(level, forkId);
            helper.assertTrue(forked.size() == 1, Component.literal("her copy is worn by " + forked.size()
                    + " dragons: " + reply.get().text()));
            LivingEntity bobs = (LivingEntity) level.getEntity(hisId);
            helper.assertTrue(bobs != null && Creatures.carriersOf(level, DRAGON).contains(bobs),
                    Component.literal("#1009: 'the dragon' changed BOB's dragon, the nearer one — not hers"));
            helper.assertTrue(forked.get(0).position().distanceTo(herSpot) < 3, Component.literal("the dragon that "
                    + "became her copy is not the one she spawned"));
            tidy(level, already, DRAGON, forkId);
            Marshal.global().finish(wa);
            Marshal.global().finish(wb);
            MockPlayerNetwork.remove(alice);
            MockPlayerNetwork.remove(bob);
        });
    }

    // ===================== helpers =====================

    /** The spawned-by stamp, spelled out here so this file also compiles on {@code main}. */
    private static String tagOf(ServerPlayer p) {
        return "mfamby_" + p.getUUID().toString().replace("-", "").substring(0, 8);
    }

    private static Set<UUID> uuids(ServerLevel level, String id) {
        Set<UUID> out = new HashSet<>();
        for (LivingEntity c : Creatures.carriersOf(level, id)) {
            out.add(c.getUUID());
        }
        return out;
    }

    private static int displays(ServerLevel level, LivingEntity carrier) {
        String own = DisplayRig.RIG_OWNER_PREFIX + carrier.getUUID();
        int n = 0;
        for (var e : level.getEntities().getAll()) {
            if (e instanceof net.minecraft.world.entity.Display && !e.isRemoved()
                    && e.getTags().contains(own)) {
                n++;
            }
        }
        return n;
    }

    private static void offThread(String worker, Runnable work) {
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                work.run();
            } finally {
                AgentContext.unbind();
            }
        }, "g1009-gametest");
        t.setDaemon(true);
        t.start();
    }

    private static void tidy(ServerLevel level, Set<UUID> keep, String... ids) {
        for (String id : ids) {
            for (LivingEntity c : Creatures.carriersOf(level, id)) {
                if (!keep.contains(c.getUUID())) {
                    Creatures.remove(level, c);
                }
            }
        }
    }
}
