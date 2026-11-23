package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.tools.PlanWishTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.testing.MockPlayerNetwork;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for {@link CameraNeed}'s wiring</b>: the REAL {@code plan_wish}, the
 * REAL {@link WishRun#made}, the REAL wish bars. {@code CameraNeedTest} proves the state machine;
 * this proves the places that drive it are the places a wish actually goes through — the shape of
 * bug {@code docs/MACHINE_DEBUG_LOOP.md} names: a rule tested only where it is DEFINED.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CameraNeedGameTest {

    /** Its own batch: wish bars and CameraNeed's register are process-wide. */
    private static final String BATCH = "cameraNeed";

    private CameraNeedGameTest() {
    }

    /** One wish as plan_wish meets it: submitted, assigned to a worker, its bar up, its need open. */
    private record Wishing(UUID wish, String worker, WishRun run) { }

    private static Wishing wish(ServerPlayer player, String label) {
        UUID wish = UUID.randomUUID();
        String worker = "gt-camneed-" + label + "-" + wish;
        Marshal.global().submit(wish, player.getUUID(), "gt camera need " + label);
        WishRun run = Marshal.global().assign(worker, wish);
        WishProgress.startThinking(player, wish, label);
        CameraNeed.shared().open(wish, System.currentTimeMillis());   // WishService.submitScoped's line
        return new Wishing(wish, worker, run);
    }

    /** The real tool, off the server thread (it hops to it), as the worker bound to this wish. */
    private static Thread plan(Wishing w, String kind, AtomicReference<String> out) {
        Thread t = new Thread(() -> {
            AgentContext.bind(w.worker());
            try {
                JsonObject args = new JsonObject();
                JsonArray steps = new JsonArray();
                steps.add("Make it");
                args.add("steps", steps);
                args.addProperty("status", "Working it out");
                if (kind != null) {
                    args.addProperty("kind", kind);
                }
                out.set(String.valueOf(new PlanWishTool().call(args)));
            } catch (Throwable e) {
                out.set("THREW: " + e);
            } finally {
                AgentContext.unbind();
            }
        }, "gametest-camneed-" + kind);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void end(ServerPlayer player, Wishing... ws) {
        for (Wishing w : ws) {
            try {
                Marshal.global().finish(w.wish());
            } catch (RuntimeException ignored) {
                // already finished
            }
            WishProgress.clearWishBar(w.wish());
        }
        MockPlayerNetwork.remove(player);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void planWishsKindDecidesWhoKeepsACamera(GameTestHelper helper) {
        ServerPlayer player = MockPlayerNetwork.inLevel(helper);
        Wishing item = wish(player, "item");
        Wishing creature = wish(player, "creature");
        Wishing silent = wish(player, "nokind");
        var ids = Set.of(item.wish(), creature.wish(), silent.wish());
        helper.assertTrue(CameraNeed.shared().demand(ids, System.currentTimeMillis()).wanting() == 3,
                Component.literal("three wishes just submitted: all three want a camera before anyone knows what they are"));
        AtomicReference<String> a = new AtomicReference<>();
        AtomicReference<String> b = new AtomicReference<>();
        AtomicReference<String> c = new AtomicReference<>();
        plan(item, "item", a);
        plan(creature, "creature", b);
        plan(silent, null, c);
        helper.succeedWhen(() -> {
            helper.assertTrue(a.get() != null && b.get() != null && c.get() != null, Component.literal("the plans returned"));
            helper.assertFalse(a.get().startsWith("THREW") || b.get().startsWith("THREW")
                    || c.get().startsWith("THREW"), Component.literal("plan_wish threw: " + a + " / " + b + " / " + c));
            helper.assertTrue(CameraNeed.shared().phase(item.wish()) == CameraNeed.Phase.NONE,
                    Component.literal("an item's plan prices no camera work (it still holds one awake while in flight, #1604) — was " + CameraNeed.shared().phase(item.wish())));
            helper.assertTrue(CameraNeed.shared().phase(creature.wish()) == CameraNeed.Phase.NEEDS,
                    Component.literal("a creature's plan keeps it"));
            helper.assertTrue(CameraNeed.shared().phase(silent.wish()) == CameraNeed.Phase.PENDING,
                    Component.literal("a plan that names no kind keeps it too (the long-planning wish)"));
            var d = CameraNeed.shared().demand(WishProgress.inFlightIds(), System.currentTimeMillis());
            helper.assertTrue(d.none() >= 1 && d.wanting() >= 2,
                    Component.literal("the supervisor's read of the real wish bars: " + d));

            // a making tool's record upgrades the item wish: it sculpted a pet after all
            item.run().made("creature", "gt_pet");
            helper.assertTrue(CameraNeed.shared().phase(item.wish()) == CameraNeed.Phase.NEEDS,
                    Component.literal("WishRun.made(creature) gives a NONE wish its camera back"));

            // the wish ending — through the bar, the path every settle takes — forgets it
            WishProgress.clearWishBar(creature.wish());
            helper.assertTrue(CameraNeed.shared().phase(creature.wish()) == null,
                    Component.literal("clearWishBar closes its need"));
            end(player, item, creature, silent);
        });
    }

    /**
     * A wish that only puts back something kept takes no camera and no photograph.
     * The REAL run_command marks the replay; the REAL photograph then answers without a camera.
     */
    // ITS OWN BATCH: the plan test beside it calls CameraNeed.demand with only its own three wishes,
    // and demand prunes every wish it is not handed — this one's replay mark included.
    @GameTest(template = "empty_test", batch = BATCH + "_replay", timeoutTicks = 400)
    public static void aReplayThroughRunCommandTakesNoCameraAndNoPhotograph(GameTestHelper helper) {
        // Its own name too: a mock player's UUID is derived from its name.
        ServerPlayer player = MockPlayerNetwork.inLevel(helper, "mfam-camneed-replay");
        Wishing w = wish(player, "replay");
        CameraNeed.shared().declared(w.wish(), "building", System.currentTimeMillis());
        AtomicReference<String> photo = new AtomicReference<>();
        Thread t = new Thread(() -> {
            AgentContext.bind(w.worker());
            try {
                JsonObject args = new JsonObject();
                args.addProperty("command", "wishbuild gt_kept_castle");
                var ran = new com.moddingfromamod.agent.tools.RunCommandTool().call(args);
                if (!CameraNeed.shared().reuseOnly(w.wish())) {
                    photo.set("THREW: run_command did not mark the replay (run="
                            + AgentContext.currentRun() + "): " + ran);
                    return;
                }
                photo.set(String.valueOf(com.moddingfromamod.agent.tools.PhotographTool.capture(
                        "gt_replay", null, null)));
            } catch (Throwable e) {
                photo.set("THREW: " + e);
            } finally {
                AgentContext.unbind();
            }
        }, "gametest-camneed-replay");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            if (photo.get() == null && helper.getTick() >= 395) {
                // say WHERE the worker is stuck, not only that it is
                StringBuilder at = new StringBuilder();
                for (StackTraceElement e : t.getStackTrace()) {
                    at.append("\n    at ").append(e);
                }
                helper.fail(Component.literal("the photograph never returned; the worker is at:" + at));
            }
            helper.assertTrue(photo.get() != null, Component.literal("the photograph returned"));
            helper.assertFalse(photo.get().startsWith("THREW"), Component.literal(photo.get()));
            helper.assertTrue(CameraNeed.shared().reuseOnly(w.wish()), Component.literal("run_command's replay marked it"));
            helper.assertTrue(CameraNeed.shared().phase(w.wish()) == CameraNeed.Phase.NONE,
                    Component.literal("it prices no camera work and takes no photograph, though its plan said building"));
            helper.assertTrue(photo.get().contains("Nothing new was made"),
                    Component.literal("the photograph answered without a camera: " + photo.get()));
            end(player, w);
        });
    }
}
