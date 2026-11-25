package com.moddingfromamod.safety;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.HttpReply;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.ToolRegistry;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.agent.tools.PlanWishTool;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.PickupHold;
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
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B: a wish is handed over before its safety grades land, and nothing the child
 * sees or the world holds happens until they do.</b>
 *
 * <p>The pickup ratchet ({@code AwaitWishTool.speculative}, {@code PickupHold}) hands a wish to its
 * worker while the input grade is still running, and the async plan grade
 * ({@code McpProtocol.planGradeLater}, {@code PlanDeferral}) answers {@code plan_wish} before its
 * plan is graded. Both are latency wins only if a late verdict still has the last word. Each test
 * here drives the REAL {@code await_wish} and {@code plan_wish} through the real {@link McpProtocol},
 * so the session is the worker exactly as in play, against a grader that answers <b>late on
 * purpose</b> — a test transport that sleeps, never the whole-path stub, which makes grading
 * instant and so (correctly) keeps the old waiting path ({@link SafetyGrader#gradesTakeTime}).
 *
 * <p>The thing the child would see or the world would hold is {@code speak}, a tool of this file's
 * that counts its calls: it is not on {@code PickupGatedTools.READ_ONLY}, so it parks exactly as
 * {@code notify_player} or {@code build_structure} would, and "it ran" is a number rather than a
 * judgement about chat.
 *
 * <p>One batch per test: they share the grader's transport and the {@code childsafety} property.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PickupRatchetGameTest {

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";
    private static final String UNAVAILABLE = ModdingFromAModMod.MODID + ".childSafetyUnavailable";
    private static final String BUDGET = ModdingFromAModMod.MODID + ".childSafetyInputBudgetMs";

    /** How late the grader answers. Long enough that a call made at once is unmistakably early. */
    private static final long LATE_MS = 1_500;

    /** Ticks are not seconds here (CLAUDE.md): a generous ceiling for a test that waits on wall time. */
    private static final int TICKS = 20_000;

    // ---- the late grader ----

    private static String verdictJson(String band, String redirect, int stage) {
        return "{\"violence\":\"" + band + "\",\"sexual\":\"FINE\",\"hate\":\"FINE\",\"danger\":\"FINE\","
                + "\"reasons\":[" + ("FINE".equals(band) ? "" : "\"GORE\"") + "],"
                + "\"redirect\":\"" + redirect + "\",\"stageIndex\":" + stage + "}";
    }

    private static boolean isPlan(String userText) {
        return userText != null && userText.contains("THE PLAN, one step per line");
    }

    /**
     * A transport that answers {@code LATE_MS} after it is asked, and records that it has answered.
     * {@code input} and {@code plan} pick the verdict; null means "no answer" (the unavailable shape).
     */
    private static AtomicBoolean lateGrader(Function<String, String> input, Function<String, String> plan) {
        AtomicBoolean answered = new AtomicBoolean(false);
        SafetyGrader.forTest(null);
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> {
            try {
                Thread.sleep(LATE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String json = isPlan(text) ? plan.apply(text) : input.apply(text);
            answered.set(true);
            return json == null ? null : new SafetyGrader.Reply(json, LATE_MS, 0.0, "test-late", json);
        });
        return answered;
    }

    // ---- the counting tool ----

    /** A tool that would speak to the child: not read-only, so it parks. Counts every time its body runs. */
    private static final class Speak implements McpTool {
        final AtomicInteger ran = new AtomicInteger();

        @Override
        public String name() {
            return "speak";
        }

        @Override
        public String description() {
            return "Test tool: stands for anything the child would see.";
        }

        @Override
        public JsonObject inputSchema() {
            JsonObject s = new JsonObject();
            s.addProperty("type", "object");
            s.add("properties", new JsonObject());
            return s;
        }

        @Override
        public ToolResult call(JsonObject args) {
            ran.incrementAndGet();
            return ToolResult.ok("spoke");
        }
    }

    // ---- MCP plumbing (the SafetyPlanGameTest shape) ----

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

    /** One tools/call; returns {@code [isError, text]} plus the wall time it took. */
    private record Call(boolean error, String text, long ms, boolean graderHadAnswered) { }

    private static Call call(McpProtocol p, String session, String tool, String argsJson, AtomicBoolean answered) {
        long t0 = System.currentTimeMillis();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}}";
        HttpReply r = p.handlePost(headers(session), body, 0L);
        JsonObject result = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("result");
        boolean err = result.has("isError") && result.get("isError").getAsBoolean();
        String text = result.has("content")
                ? result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()
                : String.valueOf(result);
        return new Call(err, text, System.currentTimeMillis() - t0, answered.get());
    }

    private static final String PLAN_ARGS = "{\"status\":\"Working it out\",\"about\":\"new\","
            + "\"steps\":[\"Building the tower\",\"Painting it bright red\"]}";

    /** Run a whole sequence of calls off the server thread (the tools hop onto it). */
    private static AtomicReference<List<Call>> offThread(java.util.function.Supplier<List<Call>> body,
                                                         AtomicReference<Throwable> blew) {
        AtomicReference<List<Call>> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                out.set(body.get());
            } catch (Throwable e) {
                blew.set(e);
            }
        }, "mfam-pickup-ratchet-gametest");
        t.setDaemon(true);
        t.start();
        return out;
    }

    // ---- the fixture ----

    private record Wishing(ServerPlayer player, Wish wish, WishQueue queue, McpProtocol protocol,
                           Speak speak, List<String> sessions, StringBuilder said) { }

    /**
     * A wish on a private queue, with the input grade already in flight — exactly the state
     * {@code WishService.submit} leaves it in a moment before a worker's {@code await_wish}.
     */
    private static Wishing begin(GameTestHelper helper, String tag) {
        // WALL TIME, NOT TICKS. These tests wait on a grader that answers LATE_MS of REAL time
        // later, and a GameTest server ticks as fast as it can (CLAUDE.md): in a small targeted
        // run TICKS went by in a few seconds and the calls were still parked, so 1-3 of these
        // failed "waiting for the calls" at random, on main too (2026-10-27). One millisecond a
        // tick makes the TICKS ceiling at least TICKS ms of wall time whatever the batch size;
        // the tools still hop onto the server thread between ticks.
        helper.onEachTick(() -> {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        System.setProperty(MODE, "on");
        RuntimeConfig.invalidate();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        String text = "make a " + tag + " tower " + UUID.randomUUID().toString().substring(0, 8);
        Wish wish = WishBook.get(helper.getLevel().getServer()).add(Wish.create(text, WishSource.COMMAND,
                System.currentTimeMillis(), p.getUUID(), p.getName().getString(),
                p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ()));
        Marshal.global().submit(wish.id(), p.getUUID(), text);
        WishProgress.startThinking(p, wish.id(), text);
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(wish), Component.literal("the test queue refused the wish"));
        Speak speak = new Speak();
        McpProtocol protocol = new McpProtocol(new ToolRegistry().add(new AwaitWishTool(queue))
                .add(new PlanWishTool()).add(speak), new McpSessions());
        Wishing w = new Wishing(p, wish, queue, protocol, speak, new ArrayList<>(), new StringBuilder());
        tidyWhenTheTestEnds(helper, w);
        return w;
    }

    private static void startTheInputGrade(Wishing w) {
        SafetyGrader.gradeInput(w.wish().text());     // what WishService.submit does
    }

    private static void tidyWhenTheTestEnds(GameTestHelper helper, Wishing w) {
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
            public void testAddedForRerun(GameTestInfo before, GameTestInfo after, GameTestRunner runner) {
                end(w);
            }
        });
    }

    private static void end(Wishing w) {
        SafetyGrader.transportForTest(null);
        SafetyGrader.forTest(null);
        System.clearProperty(MODE);
        System.clearProperty(UNAVAILABLE);
        System.clearProperty(BUDGET);
        RuntimeConfig.invalidate();
        try {
            Marshal.global().cancel(w.wish().id(), CancelMode.STOP, "", "");
        } catch (RuntimeException ignored) {
            // already finished — the usual case after a refusal
        }
        PickupHold.global().forget(w.wish().id());
        PickupHold.plans().forget(w.wish().id());
        for (String s : w.sessions()) {
            PickupHold.global().forgetSession(s);
        }
        WishProgress.clearWishBar(w.wish().id());
        BuildGate.settle(w.wish().id());
        MockPlayerNetwork.remove(w.player());
    }

    private static String newSession(Wishing w) {
        String s = openSession(w.protocol());
        w.sessions().add(s);
        return s;
    }

    private static String chat(Wishing w) {
        if (w.player().connection.getConnection().channel()
                instanceof io.netty.channel.embedded.EmbeddedChannel embedded) {
            Object o;
            while ((o = embedded.readOutbound()) != null) {
                if (o instanceof ClientboundSystemChatPacket c) {
                    w.said().append(c.content().getString()).append('\n');
                }
            }
        }
        return w.said().toString();      // accumulated: readOutbound is destructive and succeedWhen re-polls
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static WishStatus status(Wishing w) {
        return WishBook.get(w.player().level().getServer()).byId(w.wish().id()).map(Wish::status).orElse(null);
    }

    private static boolean barCarriesThePlan(UUID wishId, String firstStep) {
        WishPlan plan = WishProgress.planFor(wishId);
        return plan != null && !plan.steps().isEmpty() && plan.steps().get(0).equals(firstStep);
    }

    // ── the input ratchet ─────────────────────────────────────────────────────────────────────

    /**
     * <b>The headline.</b> A wish the grader BLOCKS, late: the worker was handed it at once, but the
     * call that would speak never runs — it parks, then is told the wish was taken back — and the
     * wish ends REJECTED with the child told the block wording, as the waiting path would.
     *
     * <p>The control arm is the next test: the same harness with a FINE verdict lets the parked call
     * run; it is the ratchet alone that keeps {@code speak} at zero here.
     */
    @GameTest(template = "empty_test", batch = "pickup_ratchet_block", timeoutTicks = TICKS)
    public static void aLateBlockTakesTheWishBackBeforeAnythingIsSaid(GameTestHelper helper) {
        Wishing w = begin(helper, "late-block");
        AtomicBoolean answered = lateGrader(t -> verdictJson("CLEARLY", "", -1), t -> verdictJson("FINE", "", -1));
        startTheInputGrade(w);
        AtomicReference<Throwable> blew = new AtomicReference<>();
        var calls = offThread(() -> {
            String s = newSession(w);
            Call take = call(w.protocol(), s, "await_wish", "{}", answered);
            Call speak = call(w.protocol(), s, "speak", "{}", answered);
            Call after = call(w.protocol(), s, "speak", "{}", answered);
            return List.of(take, speak, after);
        }, blew);
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("threw: " + blew.get()));
            helper.assertTrue(calls.get() != null, Component.literal("waiting for the calls"));
            Call take = calls.get().get(0);
            helper.assertFalse(take.error(), Component.literal("the wish should have been handed over at once: " + take.text()));
            helper.assertFalse(take.graderHadAnswered(),
                    Component.literal("await_wish waited for the grade (" + take.ms() + " ms) — the ratchet did not engage"));
            helper.assertTrue(take.text().contains(w.wish().text()), Component.literal("the take carried the wish: " + take.text()));
            Call speak = calls.get().get(1);
            helper.assertTrue(speak.error(), Component.literal("a call after a late BLOCK must be refused: " + speak.text()));
            helper.assertTrue(speak.ms() >= LATE_MS / 2, Component.literal("the call did not park (" + speak.ms() + " ms)"));
            helper.assertTrue(calls.get().get(2).error(), Component.literal("the taken-back worker acted again"));
            helper.assertValueEqual(w.speak().ran.get(), 0, Component.literal("times the child-facing tool RAN"));
            helper.assertValueEqual(status(w), WishStatus.REJECTED, Component.literal("the wish's status"));
            helper.assertTrue(chat(w).contains(SafetyWords.sayBlocked()), Component.literal("the child was not told: " + chat(w)));
        });
    }

    /**
     * The control, and a MAYBE: the late verdict passes the wish unchanged, so the parked call RUNS
     * — once, after the grade — and carries the grader's guidance, which the waiting path put in
     * the wake text and the ratchet puts on the first gated result.
     */
    @GameTest(template = "empty_test", batch = "pickup_ratchet_release", timeoutTicks = TICKS)
    public static void aLateClearReleasesTheParkedCallWithTheNote(GameTestHelper helper) {
        Wishing w = begin(helper, "late-maybe");
        AtomicBoolean answered = lateGrader(t -> verdictJson("MAYBE", "", -1), t -> verdictJson("FINE", "", -1));
        startTheInputGrade(w);
        AtomicReference<Throwable> blew = new AtomicReference<>();
        var calls = offThread(() -> {
            String s = newSession(w);
            Call take = call(w.protocol(), s, "await_wish", "{}", answered);
            Call speak = call(w.protocol(), s, "speak", "{}", answered);
            Call again = call(w.protocol(), s, "speak", "{}", answered);
            return List.of(take, speak, again);
        }, blew);
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("threw: " + blew.get()));
            helper.assertTrue(calls.get() != null, Component.literal("waiting for the calls"));
            Call take = calls.get().get(0);
            helper.assertFalse(take.error(), Component.literal(take.text()));
            helper.assertFalse(take.graderHadAnswered(), Component.literal("await_wish waited for the grade (" + take.ms() + " ms)"));
            Call speak = calls.get().get(1);
            helper.assertFalse(speak.error(), Component.literal("a cleared wish's parked call must run: " + speak.text()));
            helper.assertTrue(speak.graderHadAnswered(), Component.literal("the call ran before the verdict landed"));
            helper.assertTrue(speak.text().contains("SAFETY NOTE"),
                    Component.literal("a MAYBE's guidance must ride the first gated result: " + speak.text()));
            helper.assertFalse(calls.get().get(2).text().contains("SAFETY NOTE"),
                    Component.literal("the note rides ONE call: " + calls.get().get(2).text()));
            helper.assertValueEqual(w.speak().ran.get(), 2, Component.literal("times the child-facing tool ran"));
            helper.assertTrue(status(w) != WishStatus.REJECTED, Component.literal("a MAYBE is not a refusal"));
        });
    }

    /**
     * A late REDIRECT: the first worker read the child's own words, so it is taken off the wish;
     * a fresh worker is handed ONLY the redirect, and the child is told the redirect once — not
     * once per take.
     */
    @GameTest(template = "empty_test", batch = "pickup_ratchet_redirect", timeoutTicks = TICKS)
    public static void aLateRedirectGoesToAFreshWorkerWithOnlyTheNewWords(GameTestHelper helper) {
        Wishing w = begin(helper, "late-redirect");
        String redirect = "a friendly tower that waves its flag";
        AtomicBoolean answered = lateGrader(t -> verdictJson("LIKELY", redirect, -1), t -> verdictJson("FINE", "", -1));
        startTheInputGrade(w);
        AtomicReference<Throwable> blew = new AtomicReference<>();
        var calls = offThread(() -> {
            String first = newSession(w);
            Call take = call(w.protocol(), first, "await_wish", "{}", answered);
            Call speak = call(w.protocol(), first, "speak", "{}", answered);
            String second = newSession(w);
            Call retake = call(w.protocol(), second, "await_wish", "{}", answered);
            Call speak2 = call(w.protocol(), second, "speak", "{}", answered);
            return List.of(take, speak, retake, speak2);
        }, blew);
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("threw: " + blew.get()));
            helper.assertTrue(calls.get() != null, Component.literal("waiting for the calls"));
            Call take = calls.get().get(0);
            helper.assertFalse(take.error(), Component.literal(take.text()));
            helper.assertFalse(take.graderHadAnswered(), Component.literal("await_wish waited for the grade (" + take.ms() + " ms)"));
            helper.assertTrue(calls.get().get(1).error(), Component.literal("the first worker must be taken off: " + calls.get().get(1).text()));
            Call retake = calls.get().get(2);
            helper.assertFalse(retake.error(), Component.literal("the fresh worker must be handed the wish: " + retake.text()));
            helper.assertTrue(retake.text().contains(redirect), Component.literal("the fresh worker gets the redirect: " + retake.text()));
            helper.assertFalse(retake.text().contains(w.wish().text()),
                    Component.literal("the fresh worker must never see the child's own words: " + retake.text()));
            helper.assertFalse(calls.get().get(3).error(), Component.literal("the fresh worker acts freely: " + calls.get().get(3).text()));
            helper.assertValueEqual(w.speak().ran.get(), 1, Component.literal("times the child-facing tool ran (fresh worker only)"));
            helper.assertValueEqual(count(chat(w), redirect), 1, Component.literal("times the child was told the redirect"));
        });
    }

    /**
     * Fail closed: a grader that never answers within the budget, on {@code unavailable=closed}.
     * The parked call never runs and the wish ends — "no verdict" is never read as "go".
     */
    @GameTest(template = "empty_test", batch = "pickup_ratchet_closed", timeoutTicks = TICKS)
    public static void aGradeThatNeverLandsFailsClosed(GameTestHelper helper) {
        Wishing w = begin(helper, "never-graded");
        System.setProperty(UNAVAILABLE, "closed");
        System.setProperty(BUDGET, "1000");
        RuntimeConfig.invalidate();
        helper.assertTrue(ChildSafetyConfig.unavailableClosed(), Component.literal("the dial did not take"));
        AtomicBoolean answered = lateGrader(t -> null, t -> null);
        startTheInputGrade(w);
        AtomicReference<Throwable> blew = new AtomicReference<>();
        var calls = offThread(() -> {
            String s = newSession(w);
            return List.of(call(w.protocol(), s, "await_wish", "{}", answered),
                    call(w.protocol(), s, "speak", "{}", answered));
        }, blew);
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("threw: " + blew.get()));
            helper.assertTrue(calls.get() != null, Component.literal("waiting for the calls"));
            helper.assertFalse(calls.get().get(0).error(), Component.literal(calls.get().get(0).text()));
            helper.assertTrue(calls.get().get(1).error(), Component.literal("an ungraded wish's call ran: " + calls.get().get(1).text()));
            helper.assertValueEqual(w.speak().ran.get(), 0, Component.literal("times the child-facing tool ran"));
            helper.assertTrue(chat(w).contains(SafetyWords.sayUnavailable()), Component.literal("the child was not told: " + chat(w)));
        });
    }

    // ── the async plan grade ──────────────────────────────────────────────────────────────────

    /** A wish already on a worker (no input hold), as {@code SafetyPlanGameTest} sets one up. */
    private static String assigned(Wishing w, GameTestHelper helper) {
        String s = newSession(w);
        WishRun run = Marshal.global().assign(s, w.wish().id());
        helper.assertTrue(run != null, Component.literal("the wish must be assignable"));
        BuildGate.startWish(w.wish().id());
        return s;
    }

    /**
     * <b>A late plan refusal is never shown.</b> {@code plan_wish} answers at once, but the plan
     * reaches neither the bar nor chat; the next building call is answered with the grader's own
     * message (naming the stage), so the SAME worker revises in place; and the worker is marked to
     * be retired once this wish is over, so a refused plan never seeds its next wish.
     */
    @GameTest(template = "empty_test", batch = "pickup_ratchet_plan_divert", timeoutTicks = TICKS)
    public static void aLatePlanRefusalIsNeverShownAndTheAgentRevises(GameTestHelper helper) {
        Wishing w = begin(helper, "plan-divert");
        AtomicBoolean answered = lateGrader(t -> verdictJson("FINE", "", -1),
                t -> verdictJson("LIKELY", "a red tower with a cartoon glow", 1));
        AtomicReference<String> session = new AtomicReference<>(assigned(w, helper));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        var calls = offThread(() -> {
            String s = session.get();
            Call plan = call(w.protocol(), s, "plan_wish", PLAN_ARGS, answered);
            Call speak = call(w.protocol(), s, "speak", "{}", answered);
            return List.of(plan, speak);
        }, blew);
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("threw: " + blew.get()));
            helper.assertTrue(calls.get() != null, Component.literal("waiting for the calls"));
            Call plan = calls.get().get(0);
            helper.assertFalse(plan.error(), Component.literal("plan_wish answers at once: " + plan.text()));
            helper.assertFalse(plan.graderHadAnswered(), Component.literal("plan_wish waited for its grade (" + plan.ms() + " ms)"));
            helper.assertTrue(plan.text().contains("grown-ups' check"), Component.literal("the agent is told the plan is being checked"));
            Call speak = calls.get().get(1);
            helper.assertTrue(speak.error(), Component.literal("a call after a refused plan must not run: " + speak.text()));
            helper.assertTrue(speak.text().contains("Step 1"), Component.literal("the refusal names the stage: " + speak.text()));
            helper.assertValueEqual(w.speak().ran.get(), 0, Component.literal("times the child-facing tool ran"));
            helper.assertFalse(barCarriesThePlan(w.wish().id(), "Building the tower"),
                    Component.literal("the refused plan reached the bar"));
            helper.assertFalse(chat(w).contains("Painting it bright red"), Component.literal("the refused plan reached chat: " + chat(w)));
            helper.assertTrue(Marshal.global().windingDown(session.get()) == null,
                    Component.literal("a divert must not wind the worker down — it re-plans in place"));
            helper.assertTrue(PickupHold.global().takeRetire(session.get()),
                    Component.literal("the worker must be retired after this wish"));
            WishRun run = Marshal.global().run(w.wish().id());
            helper.assertTrue(run != null && !"new".equals(run.about()),
                    Component.literal("a refused plan must not set what the wish is about: about=" + (run == null ? "?" : run.about())));
        });
    }

    /** The control: a late FINE plan grade lets the parked call run, and THEN the plan is shown. */
    @GameTest(template = "empty_test", batch = "pickup_ratchet_plan_fine", timeoutTicks = TICKS)
    public static void aLateFinePlanIsShownAndTheParkedCallRuns(GameTestHelper helper) {
        Wishing w = begin(helper, "plan-fine");
        AtomicBoolean answered = lateGrader(t -> verdictJson("FINE", "", -1), t -> verdictJson("FINE", "", -1));
        AtomicReference<String> session = new AtomicReference<>(assigned(w, helper));
        AtomicReference<Throwable> blew = new AtomicReference<>();
        AtomicBoolean barBeforeGrade = new AtomicBoolean();
        var calls = offThread(() -> {
            String s = session.get();
            Call plan = call(w.protocol(), s, "plan_wish", PLAN_ARGS, answered);
            barBeforeGrade.set(barCarriesThePlan(w.wish().id(), "Building the tower") && !answered.get());
            Call speak = call(w.protocol(), s, "speak", "{}", answered);
            return List.of(plan, speak);
        }, blew);
        helper.succeedWhen(() -> {
            helper.assertTrue(blew.get() == null, Component.literal("threw: " + blew.get()));
            helper.assertTrue(calls.get() != null, Component.literal("waiting for the calls"));
            Call plan = calls.get().get(0);
            helper.assertFalse(plan.error(), Component.literal(plan.text()));
            helper.assertFalse(plan.graderHadAnswered(), Component.literal("plan_wish waited for its grade (" + plan.ms() + " ms)"));
            helper.assertFalse(barBeforeGrade.get(), Component.literal("the plan was shown before its grade passed"));
            Call speak = calls.get().get(1);
            helper.assertFalse(speak.error(), Component.literal(speak.text()));
            helper.assertTrue(speak.graderHadAnswered(), Component.literal("the call ran before the plan grade landed"));
            helper.assertValueEqual(w.speak().ran.get(), 1, Component.literal("times the child-facing tool ran"));
            helper.assertTrue(barCarriesThePlan(w.wish().id(), "Building the tower"), Component.literal("the passed plan must be shown"));
            helper.assertFalse(PickupHold.global().takeRetire(session.get()), Component.literal("a clean plan retires nobody"));
            WishRun run = Marshal.global().run(w.wish().id());
            helper.assertTrue(run != null && "new".equals(run.about()),
                    Component.literal("a passed plan sets what the wish is about: about=" + (run == null ? "?" : run.about())));
        });
    }
}
