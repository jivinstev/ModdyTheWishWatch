package com.moddingfromamod.safety;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.HttpReply;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.ToolRegistry;
import com.moddingfromamod.agent.tools.PlanWishTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.BuildGate;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishPlan;
import com.moddingfromamod.wish.WishProgress;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for the plan grade: the REAL dispatch, the REAL PlanWishTool, and a REAL player.</b>
 *
 * <p>{@code SafetyPlanGateTest} settles the policy in milliseconds. What it structurally cannot
 * reach is the thing this layer exists for — that a diverted plan never becomes a line in a child's
 * chat. That needs the real tool, which needs a server, an {@code AgentContext} bound to a real
 * {@code WishRun}, and somebody to be told.
 *
 * <h2>What "the child never saw it" is measured as</h2>
 *
 * <p>Two independent observables, because the interesting one is not directly hookable:
 *
 * <ul>
 *   <li>the tool RESULT. {@code PlanWishTool} answers "Plan set, N step(s)…" when its body runs; a
 *       diverted call gets the refusal instead, so the body demonstrably did not run.</li>
 *   <li>{@link WishProgress#planFor}. In {@code PlanWishTool.call} the line
 *       {@code WishProgress.setPlan(tell, run.wishId(), plan)} sits immediately above
 *       {@code WishFeedback.planned(tell, plan.steps())}, in one block — the only branch between
 *       them is an earlier issue's once-per-wish guard, which can only SUPPRESS the chat line — so "the bar
 *       never got this plan" still implies "the child was never sent it". The bar still holding {@code startThinking}'s placeholder is the proof.</li>
 * </ul>
 *
 * <p>And a FINE control in the same file flips both, which is what stops "nothing happened" being
 * confused with "the harness never worked". A gate that can only fail one way has not been proven
 * to fire.
 *
 * <h2>A batch each, its own player, its own wish</h2>
 *
 * <p>This test drives {@code Marshal.global()}, which is not optional: {@code
 * AgentContext.currentRun()} asks the global marshal and nothing else, so handing it a private one
 * would test a seam instead of the path a wish takes. It is made safe the other way, exactly as
 * {@code TwoWorkersGameTest} records — {@code assign} names the wish rather than claiming whatever
 * is next, so it can only ever be handed the one it submitted itself, and every run it makes is
 * finished on the way out.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SafetyPlanGameTest {

    // ⚠ ONE BATCH EACH, and it is not caution — it is the only thing that makes these tests
    // measure what they say. Tests in one batch run CONCURRENTLY and share every static, and this
    // file leans on two process-wide ones that cannot be handed in: SafetyGrader's stub (which IS
    // the seam, so there is nowhere else for it to live) and the `childsafety` property that puts
    // the shadow test in shadow mode.
    //
    // MEASURED, with all four in one batch: the two tests whose premise needs a non-default
    // verdict BOTH failed with "Plan set, 3 step(s)…" — a sibling's stub answering their call —
    // while the two whose premise is "nothing happens" passed, for the wrong reason. That is the
    // expensive shape: it reads as the gate not firing, and it makes the control worthless.
    //
    // Batches ARE strictly sequential (measured across 522 tests: 0 started before their own batch
    // began), so a batch apiece is real isolation. It costs a few seconds of serialisation and
    // removes the whole class.
    private static final String BATCH_DIVERT = "childsafety_plan_divert";
    private static final String BATCH_CONTROL = "childsafety_plan_control";
    private static final String BATCH_STOP = "childsafety_plan_stop";
    private static final String BATCH_SHADOW = "childsafety_plan_shadow";

    /** What {@code startThinking} parks in the bar until a real plan arrives. */
    private static final String PLACEHOLDER = WishPlan.THINKING_LABEL;

    // ---- driving the real dispatch ----

    private static McpProtocol protocol() {
        // The REAL tool. A counting stub would prove the gate refuses and say nothing about
        // whether the thing that talks to the child was reached.
        return new McpProtocol(new ToolRegistry().add(new PlanWishTool()), new McpSessions());
    }

    private static McpProtocol.Headers headers(String session) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        if (session != null) {
            m.put("Mcp-Session-Id", session);
        }
        return McpProtocol.Headers.of(m);
    }

    private static String openSession(McpProtocol p) {
        return p.handlePost(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                .headers().get("Mcp-Session-Id");
    }

    private static JsonObject planWish(McpProtocol p, String session, String... steps) {
        StringBuilder arr = new StringBuilder();
        for (String s : steps) {
            arr.append(arr.length() == 0 ? "" : ",").append('"').append(s).append('"');
        }
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"plan_wish\",\"arguments\":{\"status\":\"Working it out\","
                + "\"steps\":[" + arr + "]}}}";
        HttpReply r = p.handlePost(headers(session), body, 0L);
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("result");
    }

    /**
     * Run {@code body} OFF the server thread and hand back somewhere to poll.
     *
     * <p>A {@code @GameTest} body runs ON the server thread, and both the tool and the safety
     * layer's stop hop across to it — so calling either synchronously would deadlock, which is the
     * hazard {@code AgentBridge}'s same-thread guard exists to name. Whole sequences go in here
     * rather than one call each, so nothing has to be parked in a static between polls.
     */
    private static AtomicReference<List<JsonObject>> offThread(
            java.util.function.Supplier<List<JsonObject>> body) {
        AtomicReference<List<JsonObject>> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                out.set(body.get());
            } catch (Throwable e) {
                JsonObject boom = new JsonObject();
                boom.addProperty("isError", true);
                JsonObject item = new JsonObject();
                item.addProperty("text", "THREW: " + e);
                JsonArray content = new JsonArray();
                content.add(item);
                boom.add("content", content);
                out.set(List.of(boom));
            }
        }, "mfam-safety-plan-gametest");
        t.setDaemon(true);
        t.start();
        return out;
    }

    private static boolean isError(JsonObject result) {
        return result.has("isError") && result.get("isError").getAsBoolean();
    }

    private static String text(JsonObject result) {
        return result.has("content")
                ? result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()
                : String.valueOf(result);
    }

    // ---- the verdicts ----

    /** Answer every grade with this, however the plan reads. */
    private static void grades(SafetyVerdict v) {
        SafetyGrader.forTest(text -> v);
    }

    private static SafetyVerdict likely(int stage) {
        return new SafetyVerdict(SafetyVerdict.Band.LIKELY, SafetyVerdict.Band.FINE,
                SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE, false,
                List.of(SafetyVerdict.Reason.GORE),
                "a cartoon fright — a green glow and a wobble, no blood",
                SafetyVerdict.Provenance.HAIKU, stage);
    }

    // ---- the state a real plan call arrives in ----

    private record Wishing(ServerPlayer player, UUID wishId, String session,
                           McpProtocol protocol) { }

    private static Wishing begin(GameTestHelper helper, String label) {
        // The enforcing arm, explicitly rather than by default (ChildSafetyConfig.mode is ON today,
        // but every batch here but the shadow twin is a claim about what ENFORCEMENT does and must
        // not depend on the default). The twin overrides this to "shadow" after begin(); tidy()
        // clears it.
        System.setProperty(ModdingFromAModMod.MODID + ".childsafety", "on");
        RuntimeConfig.invalidate();
        ServerPlayer player = MockPlayerNetwork.inLevel(helper);
        McpProtocol p = protocol();
        String session = openSession(p);
        UUID wishId = UUID.randomUUID();
        Marshal.global().submit(wishId, player.getUUID(), label);
        WishRun run = Marshal.global().assign(session, wishId);
        helper.assertTrue(run != null, Component.literal("the wish must be assignable — assign names it, so a "
                + "neighbouring test cannot have taken it"));
        WishBook.get(player.getServer()).add(new Wish(wishId, label, WishSource.TYPED,
                WishStatus.RUNNING, System.currentTimeMillis(), player.getUUID(),
                player.getName().getString(), player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(), "", "", 0L));
        // The bar the child is already watching: startThinking parks a one-step placeholder, which
        // is what makes "the real plan never arrived" observable rather than merely absent.
        WishProgress.startThinking(player, wishId, label);
        BuildGate.startWish(wishId);
        Wishing w = new Wishing(player, wishId, session, p);
        cleanUpWhenTheTestEnds(helper, w);
        return w;
    }

    /**
     * Put everything back when the TEST ends — pass or fail.
     *
     * <p>⚠ Deliberately a listener rather than a {@code finally} inside the {@code succeedWhen}
     * body, and the difference is not tidiness. {@code succeedWhen} RE-RUNS its lambda every tick
     * until it stops throwing, so a {@code finally} fires on the very first poll — the one still
     * waiting for the reply — and tears the fixture down before the test has begun. Worse, it
     * would clear the grader's stub, which is process-wide: a failing safety test would leave every
     * later {@code plan_wish} in the whole Gate B run graded by whatever was left behind, and the
     * failures would land on tests that have nothing to do with this file.
     */
    private static void cleanUpWhenTheTestEnds(GameTestHelper helper, Wishing w) {
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                end(w);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                end(w);
            }

            @Override
            public void testAddedForRerun(GameTestInfo before, GameTestInfo after,
                                          GameTestRunner runner) {
                // A rerun builds its own fixture and arms its own teardown; this one has already
                // been cleaned up by testFailed.
            }
        });
    }

    private static void end(Wishing w) {
        try {
            Marshal.global().finish(w.wishId());
        } catch (RuntimeException ignored) {
            // Already finished by the stop path, which is what one of these tests is about.
        }
        WishProgress.forget(w.player().getUUID());
        BuildGate.settle(w.wishId());
        // Scoped to THIS wish, never clearAll(): the budget is process-wide and a Gate B batch is
        // concurrent, so wiping it would hand a neighbouring test's diverted wish a second chance.
        SafetyRetryBudget.forget(w.wishId());
        // THE ONE THAT MUST NOT LEAK: it is process-wide, and a stub left set would answer every
        // plan_wish in every later batch of the run.
        SafetyGrader.forTest(null);
        System.clearProperty(ModdingFromAModMod.MODID + ".childsafety");
        RuntimeConfig.invalidate();
        // The player is already armed for removal by MockPlayerNetwork.inLevel; remove() is
        // idempotent and this is the belt to its braces.
        MockPlayerNetwork.remove(w.player());
    }

    private static boolean barCarriesThePlan(UUID wishId, String firstStep) {
        WishPlan plan = WishProgress.planFor(wishId);
        return plan != null && !plan.steps().isEmpty() && plan.steps().get(0).equals(firstStep);
    }

    // ---- the tests ----

    /**
     * <b>The headline.</b> A LIKELY plan is refused at the seam and the child is never shown it.
     */
    @GameTest(template = "empty_test", batch = BATCH_DIVERT, timeoutTicks = 600)
    public static void aLikelyPlanNeverReachesTheChild(GameTestHelper helper) {
        Wishing w = begin(helper, "make a scary villager");
        grades(likely(1));
        var reply = offThread(() -> List.of(planWish(w.protocol(), w.session(),
                "Building the villager", "Painting blood pooling under him",
                "Looping the scream")));
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting for the refusal"));
            JsonObject result = reply.get().get(0);
            helper.assertTrue(isError(result),
                    Component.literal("a diverted plan must come back as an error: " + text(result)));
            String msg = text(result);
            helper.assertTrue(msg.contains("Step 1"),
                    Component.literal("the refusal must name the offending stage by index: " + msg));
            helper.assertTrue(msg.contains("Painting blood pooling under him"),
                    Component.literal("…and by its own words: " + msg));
            helper.assertTrue(msg.contains("green glow"),
                    Component.literal("…and say what to plan instead, or the agent improvises: " + msg));

            // The tool body never ran, so neither did the line below setPlan that sends
            // "✨ Here's my plan:" into this child's chat. The bar still holds the placeholder.
            helper.assertFalse(barCarriesThePlan(w.wishId(), "Building the villager"),
                    Component.literal("the plan reached the bar, which means WishFeedback.planned reached the child "
                            + "one line later"));
            helper.assertTrue(barCarriesThePlan(w.wishId(), PLACEHOLDER),
                    Component.literal("…and the bar should still be showing what it was showing before"));

            // A divert is not a stop: the wish is very much still being granted.
            helper.assertTrue(WishProgress.hasBar(w.player().getUUID()),
                    Component.literal("a divert must leave the bar up — the agent is about to re-plan"));
            helper.assertTrue(Marshal.global().windingDown(w.session()) == null,
                    Component.literal("a divert must NOT wind the worker down, or it cannot call plan_wish again"));
        });
    }

    /**
     * <b>The control, and it is not optional.</b> The same harness, one band lower, must produce
     * the opposite of everything above — otherwise "the plan did not arrive" is equally consistent
     * with a test that never worked.
     */
    @GameTest(template = "empty_test", batch = BATCH_CONTROL, timeoutTicks = 600)
    public static void aFinePlanReachesTheChildExactlyAsBefore(GameTestHelper helper) {
        Wishing w = begin(helper, "make a scary villager");
        grades(SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
        var reply = offThread(() -> List.of(planWish(w.protocol(), w.session(),
                "Building the villager", "Giving him a spooky laugh")));
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting for the plan"));
            JsonObject result = reply.get().get(0);
            helper.assertFalse(isError(result),
                    Component.literal("a FINE plan must go straight through: " + text(result)));
            helper.assertTrue(text(result).contains("Plan set"),
                    Component.literal("the tool's own answer, so its body demonstrably ran: " + text(result)));
            helper.assertTrue(barCarriesThePlan(w.wishId(), "Building the villager"),
                    Component.literal("the bar must carry the agent's real plan — and one line later the child was "
                            + "sent it"));
        });
    }

    /**
     * The second divert ends the wish: the bar comes down, the book says REJECTED, and the worker
     * is wound down so {@code CancelGate} refuses everything but {@code await_wish}.
     *
     * <p>A layer that can divert for ever is a child watching a bar that never moves, which is the
     * failure every gate in {@code BuildGate} was written against.
     */
    @GameTest(template = "empty_test", batch = BATCH_STOP, timeoutTicks = 900)
    public static void aSecondDivertEndsTheWishWithTheBarDown(GameTestHelper helper) {
        Wishing w = begin(helper, "make a scary villager");
        grades(likely(0));
        // BOTH calls on one thread, in order: the agent was diverted and came back with a plan
        // that leans the same way, which is the case this budget exists for.
        var replies = offThread(() -> List.of(
                planWish(w.protocol(), w.session(), "Make it gory"),
                planWish(w.protocol(), w.session(), "Make it gory, but bigger")));
        helper.succeedWhen(() -> {
            helper.assertTrue(replies.get() != null && replies.get().size() == 2,
                    Component.literal("waiting for the divert and then the stop"));
            JsonObject first = replies.get().get(0);
            JsonObject second = replies.get().get(1);
            helper.assertTrue(isError(first), Component.literal("the first call must be a divert: " + text(first)));
            helper.assertTrue(text(first).contains("plan_wish again"),
                    Component.literal("…which asks for exactly one more plan: " + text(first)));

            helper.assertTrue(isError(second), Component.literal("the second divert must refuse: " + text(second)));
            helper.assertTrue(text(second).contains("this wish is over"),
                    Component.literal("…and say the wish is over rather than asking for a third plan: "
                            + text(second)));
            helper.assertTrue(text(second).contains("await_wish"),
                    Component.literal("…naming the only tool that will answer it now: " + text(second)));

            helper.assertFalse(WishProgress.hasBar(w.player().getUUID()),
                    Component.literal("the bar must come down — a wish that ended with its bar up is a child waiting "
                            + "for something nobody is making"));
            helper.assertTrue(Marshal.global().windingDown(w.session()) != null,
                    Component.literal("the worker must be wound down, or CancelGate lets it carry on building"));
            helper.assertTrue(WishBook.get(w.player().getServer()).byId(w.wishId())
                            .map(x -> x.status() == WishStatus.REJECTED).orElse(false),
                    Component.literal("the book must record REJECTED — 'refused before doing anything … unsafe' is "
                            + "what its own documentation has always said it meant"));
        });
    }

    /**
     * <b>Shadow mode changes nothing.</b> The same LIKELY plan, the same everything, and the child
     * gets his plan — because shadow grades, logs, and acts on nothing.
     *
     * <p>The twin of the headline test, and worth its own run because shadow is the mode that ships
     * first: "it is on and quietly diverting" is precisely the failure nobody would notice from the
     * log alone.
     */
    @GameTest(template = "empty_test", batch = BATCH_SHADOW, timeoutTicks = 600)
    public static void shadowModeDivertsNothing(GameTestHelper helper) {
        Wishing w = begin(helper, "make a scary villager");
        System.setProperty(ModdingFromAModMod.MODID + ".childsafety", "shadow");
        RuntimeConfig.invalidate();
        grades(likely(1));
        var reply = offThread(() -> List.of(planWish(w.protocol(), w.session(),
                "Building the villager", "Painting blood pooling under him")));
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting"));
            JsonObject result = reply.get().get(0);
            helper.assertFalse(isError(result), Component.literal("shadow must act on nothing: " + text(result)));
            helper.assertTrue(barCarriesThePlan(w.wishId(), "Building the villager"),
                    Component.literal("…so the plan reaches the child exactly as it would with the layer off"));
            helper.assertTrue(WishProgress.hasBar(w.player().getUUID()), Component.literal("and the bar stays up"));
        });
    }
}
