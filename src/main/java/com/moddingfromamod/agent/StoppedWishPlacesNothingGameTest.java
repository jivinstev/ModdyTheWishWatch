package com.moddingfromamod.agent;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.vanilla.DisplayRig;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * <b>A stopped wish places nothing — and never in front of somebody else</b>, driven through the REAL
 * {@code sculpt_creature} on a worker whose wish has just been STOPPED, with two children online.
 *
 * <p>What it guards, measured in a release's Gate B: a Builder creature wish was stopped while its sculpt was
 * mid-call; the tool then asked {@link ActingChild#of} whom it was acting for, found no run, read that as
 * "no owner named" and answered the FIRST child online — and the creature was conjured five blocks in front of a
 * different child. {@code CurrentRunTest} pins the rule on the marshal; this is where the rule RUNS.
 *
 * <p>Bob joins first, so the old fallback would have picked him. The check counts the species ANYWHERE loaded,
 * not only near Bob: in a full Gate B the Bedrock batch's bots may be online too, and "first" could be any of them.
 * The control is the same sculpt for Alice's LIVE wish, which must stand a creature in the world — so an empty
 * world in the stopped arm is the rule working, not a sculpt that never spawns anything.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class StoppedWishPlacesNothingGameTest {

    private StoppedWishPlacesNothingGameTest() {
    }

    private static JsonObject creature(String id) {
        return JsonParser.parseString("""
            {"id":"%s","parts":[
              {"name":"body","from":[-4,0,-4],"to":[4,8,4],"colour":"orange"},
              {"name":"head","from":[-3,8,-3],"to":[3,14,3],"colour":"white"}]}
            """.formatted(id)).getAsJsonObject();
    }

    /** Every live carrier of this species anywhere in the level. */
    private static int standing(ServerLevel level, String id) {
        String tag = WishMobTags.forSpecies(id);
        int n = 0;
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.isAlive() && e.getTags().contains(DisplayRig.RIG_TAG) && e.getTags().contains(tag)) {
                n++;
            }
        }
        return n;
    }

    private static void takeAway(ServerLevel level, String id) {
        String tag = WishMobTags.forSpecies(id);
        // Collected first: reaping a rig removes its displays, and removing an entity while getAllEntities() is being
        // iterated threw ArrayIndexOutOfBoundsException from the fastutil map (Gate B, 2026-11-10).
        java.util.List<Mob> found = new java.util.ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && e.getTags().contains(DisplayRig.RIG_TAG) && e.getTags().contains(tag)) {
                found.add(m);
            }
        }
        for (Mob m : found) {
            DisplayRig.reap(level, m.getUUID());
            m.discard();
        }
    }

    /**
     * Sculpt {@code id} as the worker granting {@code owner}'s wish, off the server thread (the tool hops onto it and
     * refuses to be called from it). With {@code stopped}, the wish is stopped first — the worker still holds its
     * session, exactly as a tool already past the cancel gate does when the stop lands.
     */
    private static void sculptFor(UUID owner, String id, boolean stopped, AtomicReference<String> said) {
        UUID wish = UUID.randomUUID();
        String worker = "gt-stopped-" + id;
        Marshal.global().submit(wish, owner, "a creature called " + id);
        Marshal.global().assign(worker, wish);
        if (stopped) {
            Marshal.global().cancel(wish, CancelMode.STOP, "", "");
            Marshal.global().finish(wish);
        }
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                ToolResult r = new SculptCreatureTool().call(creature(id));
                said.set((r.isError() ? "ERROR " : "") + r.text());
            } catch (RuntimeException e) {
                said.set("THREW " + e);
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "gametest-stopped-" + id);
        t.setDaemon(true);
        t.start();
    }

    @GameTest(template = "empty_test", batch = "stoppedWishPlacesNothing", timeoutTicks = 6000)
    public static void aStoppedWishsCreatureAppearsInFrontOfNobody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper, "mfam-bob");      // joins FIRST: the old fallback's pick
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper, "mfam-alice");
        long t = level.getGameTime();
        String live = "gt_stop_ctrl_" + t;
        String stopped = "gt_stop_cat_" + t;
        MockPlayerNetwork.afterTest(helper, () -> {
            takeAway(level, live);
            takeAway(level, stopped);
            MockPlayerNetwork.remove(alice);
            MockPlayerNetwork.remove(bob);
        });
        AtomicReference<String> control = new AtomicReference<>();
        AtomicReference<String> afterStop = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean began = new java.util.concurrent.atomic.AtomicBoolean();
        sculptFor(alice.getUUID(), live, false, control);
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(control.get() != null, Component.literal("waiting for the control sculpt"));
            helper.assertTrue(standing(level, live) == 1,
                    Component.literal("CONTROL: Alice's live wish must stand its creature in the world (" + standing(level, live)
                            + "), or an empty world below proves nothing: " + control.get()));
            if (began.compareAndSet(false, true)) {   // the control has stood: now the stopped arm
                sculptFor(alice.getUUID(), stopped, true, afterStop);
            }
            helper.assertTrue(afterStop.get() != null, Component.literal("waiting for the stopped wish's sculpt"));
            helper.assertTrue(standing(level, stopped) == 0,
                    Component.literal("a STOPPED wish's creature was conjured anyway (" + standing(level, stopped)
                            + " standing) — the tool acted for somebody after its wish ended: " + afterStop.get()));
        });
    }
}
