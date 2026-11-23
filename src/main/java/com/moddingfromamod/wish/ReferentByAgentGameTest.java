package com.moddingfromamod.wish;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.PlanWishTool;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.BuildShape;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The AGENT names what a wish is about — nothing is named before it does</b>.
 *
 * <p>Live, the local resolver named a thing at submit ("Working on your Pegasus!") 4 times and was
 * wrong 4 times. This drives the real {@link WishService#submit} with one of his buildings right in
 * front of him — exactly the shape that used to produce a name — and asserts:
 * <ol>
 *   <li>at submit: no line names a thing, the bar is still his words, and the wish's note carries
 *       the FACT that the building is in his view (never a resolution);</li>
 *   <li>at {@code plan_wish}: {@code about} names the building, the plan line says it, the bar is
 *       renamed to it, and an {@code about} that is not one of his things is refused.</li>
 * </ol>
 * Its own batch: it drives {@code Marshal.global()} and the shared mock player.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ReferentByAgentGameTest {

    @GameTest(template = "empty_test", batch = "referentByAgent1340", timeoutTicks = 1000000)
    public static void nothingIsNamedAtSubmitAndThePlanNamesTheAgentsReferent(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        BlockPos stand = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        child.setYRot(0f);   // facing +z
        child.setXRot(0f);
        WishService.forget(child);
        com.moddingfromamod.agent.WishQueue.global().clear();
        // The grader answers, so the fire-and-forget grade never reaches a network.
        SafetyGrader.forTest(null);
        SafetyGrader.transportForTest((prompt, text, schema, timeout) ->
                new SafetyGrader.Reply("{}", 1L, 0.0, "test-transport", "{}"));

        var server = helper.getLevel().getServer();
        // NOT "gt_ref_tower_" + the bare game time: once a reused world's clock reaches six digits (100000+), "_106230"
        // IS a Builder id suffix (`_` and six hex digits, ChildSubject.words) and the bar rightly says "your
        // gt ref tower" — red in three full runs at 104865, 106230 and 123160, green on a fresh world. The
        // letter keeps the id unique without ever looking like a suffix.
        String id = "gt_ref_tower_t" + helper.getLevel().getGameTime();
        String name = id.replace('_', ' ');
        BuildRegistry.put(server, new BuildPlan(id, id, List.of(
                new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 2, 2, 2, "minecraft:stone", false))));
        BuildRegistry.rememberAnchor(id, stand.offset(0, 0, 6).asLong(), helper.getLevel().dimension());
        WishTopic.remember(child.getUUID(), id);

        String text = "make it bigger " + UUID.randomUUID().toString().substring(0, 6);
        WishService.Outcome outcome = WishService.submit(child, text, WishSource.TYPED);
        Wish wish = null;
        for (Wish w : WishBook.get(server).recent(40)) {
            if (w.text().equals(text)) {
                wish = w;
                break;
            }
        }
        List<String> atSubmit = MockPlayerNetwork.chat(child);
        WishRun run = wish == null ? null : Marshal.global().run(wish.id());
        String note = wish == null ? "" : wish.result();
        String worker = "gt-referent-" + helper.getLevel().getGameTime();
        if (wish != null) {
            Marshal.global().assign(worker, wish.id());
        }

        AtomicReference<String> bogus = new AtomicReference<>("");
        AtomicReference<String> planned = new AtomicReference<>("");
        AtomicReference<Throwable> died = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        Thread t = new Thread(() -> {
            try {
                AgentContext.with(worker, () -> {
                    bogus.set(String.valueOf(new PlanWishTool().call(plan("not_his_thing"))));
                    ToolResult ok = new PlanWishTool().call(plan(id));
                    planned.set(String.valueOf(ok));
                    return null;
                });
            } catch (Throwable e) {
                died.set(e);
            } finally {
                done.set(true);
            }
        }, "referent-gametest");
        t.setDaemon(true);
        t.start();

        final Wish theWish = wish;
        long startedMs = System.currentTimeMillis();
        helper.succeedWhen(() -> {
            if (!done.get() && System.currentTimeMillis() - startedMs > 20_000L) {
                helper.testInfo.fail(GameTestCompat.failure(GameTestCompat.assertion("plan_wish did not answer in 20 s of wall clock")));
                return;
            }
            helper.assertTrue(done.get(), Component.literal("waiting for plan_wish"));
            try {
                helper.assertTrue(died.get() == null, Component.literal("the driver thread died: " + died.get()));
                helper.assertValueEqual(outcome, WishService.Outcome.PENDING_ESCALATION,
                        Component.literal("a referring wish goes to the agent"));
                helper.assertTrue(theWish != null && run != null, Component.literal("the wish and its run exist"));
                String said = String.join("\n  ", atSubmit);

                // 1 — AT SUBMIT: nothing named, the bar is his words, the note states facts.
                helper.assertFalse(said.contains("Working on") || said.contains("I think you mean")
                                || said.contains(name),
                        Component.literal("a thing was named at submit, before the agent read his words:\n  " + said));
                helper.assertTrue(atSubmit.stream().anyMatch(l -> WishWaitWords.OPENERS.stream()
                                .anyMatch(l::contains)),
                        Component.literal("the instant acknowledgement must still come:\n  " + said));
                helper.assertTrue(note.contains("FACTS ABOUT HIM") && note.contains("'" + id + "'"),
                        Component.literal("the note must carry the FACT that his tower is in view:\n" + note));
                helper.assertFalse(note.contains("THE CHILD MEANS") || note.contains("SAID SO"),
                        Component.literal("the note resolved the wish for the agent:\n" + note));

                // 2 — AT PLAN: the agent's about is refused when made up, and named when real.
                helper.assertTrue(bogus.get().contains("is not one of his things"),
                        Component.literal("a made-up about must be refused: " + bogus.get()));
                helper.assertValueEqual(run.about(), id, Component.literal("the run's about"));
                helper.assertValueEqual(run.displayName(), "your " + name, Component.literal("the bar's name"));
                List<String> after = MockPlayerNetwork.chat(child);
                helper.assertTrue(after.stream().anyMatch(l ->
                                l.contains("Here's my plan for your " + name + ":")),
                        Component.literal("the plan line must name what the agent said:\n  "
                                + String.join("\n  ", after)));
                helper.succeed();
            } catch (net.minecraft.gametest.framework.GameTestAssertException e) {
                helper.testInfo.fail(GameTestCompat.failure(e));
            } finally {
                if (done.get()) {
                    if (theWish != null) {
                        Marshal.global().cancel(theWish.id(), CancelMode.STOP, "", "");
                        WishProgress.clearWishBar(theWish.id());
                    }
                    com.moddingfromamod.agent.WishQueue.global().clear();
                    WishTopic.forget(child.getUUID());
                    SafetyGrader.transportForTest(null);
                    MockPlayerNetwork.remove(child);
                }
            }
        });
    }

    private static JsonObject plan(String about) {
        JsonObject a = new JsonObject();
        JsonArray steps = new JsonArray();
        steps.add("Making it bigger");
        a.add("steps", steps);
        a.addProperty("about", about);
        return a;
    }
}
