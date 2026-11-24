package com.moddingfromamod.readymade;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.BuildGate;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishProgress;
import com.moddingfromamod.wish.WishSource;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B 's nudges: driven through the real {@link McpProtocol}, where they run.</b>
 *
 * <p>The pure classes are unit-tested; this is the rule CLAUDE.md names — a rule tested only where it
 * is DEFINED is not tested where it RUNS. A real {@code await_wish} hands the wish over and starts
 * the nudge ({@code ReadyMadeLive.atHandOver}, every nudged kind in one call), and the note must ride
 * the worker's next reply, once; {@code author_datapack} must wait for one in flight and nothing else
 * may; the worker's first build call decides the race the tower reports ({@code NudgeRace}); and with
 * the knob off nothing is asked at all. Haiku is a test transport that answers late on
 * purpose — the wiring is the question here; the model's judgement is the routing arm's.
 *
 * <p>Child safety is off in this file: the ratchet has its own Gate B, and grading here would
 * only make every call wait for a grader this test is not about. One batch per test: they share the
 * transport and the knobs.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ReadyMadeNudgeGameTest {

    private static final String SAFETY = ModdingFromAModMod.MODID + ".childsafety";
    private static final String NUDGE = ModdingFromAModMod.MODID + ".readymade.nudge";
    private static final String SEARCH = ModdingFromAModMod.MODID + ".readymade.search";
    /**
     * Generous on purpose: these tests wait on WALL time (a late Haiku, a 5 s park), and a GameTest
     * server ticks as fast as it can — alone, 20 000 ticks went by in about a second and every test
     * timed out mid-wait (CLAUDE.md: ticks are not seconds). A passing test ends the moment its
     * condition holds, so the ceiling costs nothing.
     */
    private static final int TICKS = 1_000_000;
    private static final long LATE_MS = 1_500;

    private static String envelope(String picks) {
        return "{\"type\":\"result\",\"total_cost_usd\":0.001,\"structured_output\":{\"picks\":" + picks + "}}";
    }

    /** Haiku, answering {@code picks} after {@code lateMs}; counts how often it was asked. */
    private static AtomicInteger haiku(String picks, long lateMs) {
        AtomicInteger asked = new AtomicInteger();
        ReadyMadeLive.transportForTest((sys, user, schema, timeout) -> {
            asked.incrementAndGet();
            try {
                Thread.sleep(lateMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return envelope(picks);
        });
        return asked;
    }

    /** Stands for any tool; the name decides whether McpProtocol parks it for a nudge. */
    private static final class Counting implements McpTool {
        private final String name;
        final AtomicInteger ran = new AtomicInteger();

        Counting(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Test tool.";
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
            return ToolResult.ok(name + " ran");
        }
    }

    private record Fixture(ServerPlayer player, Wish wish, McpProtocol protocol) { }

    private static Fixture begin(GameTestHelper helper, String nudge, String tag) {
        System.setProperty(SAFETY, "off");
        if (nudge == null) {
            System.clearProperty(NUDGE);
        } else {
            System.setProperty(NUDGE, nudge);
        }
        System.clearProperty(SEARCH);
        RuntimeConfig.invalidate();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        String text = "a pearl that makes the zombie fight the cow " + tag + " "
                + UUID.randomUUID().toString().substring(0, 6);
        Wish wish = WishBook.get(p.level().getServer()).add(Wish.create(text, WishSource.COMMAND,
                System.currentTimeMillis(), p.getUUID(), p.getGameProfile().name(),
                p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ()));
        Marshal.global().submit(wish.id(), p.getUUID(), text);
        WishProgress.startThinking(p, wish.id(), text);
        WishQueue queue = new WishQueue();
        helper.assertTrue(queue.offer(wish), Component.literal("the test queue refused the wish"));
        McpProtocol protocol = new McpProtocol(new ToolRegistry().add(new AwaitWishTool(queue))
                .add(new PlanWishTool()).add(new Counting("speak")).add(new Counting("author_datapack"))
                .add(new Counting("sculpt_creature")),
                new McpSessions());
        Fixture f = new Fixture(p, wish, protocol);
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                end(f);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                end(f);
            }

            @Override
            public void testAddedForRerun(GameTestInfo before, GameTestInfo after, GameTestRunner runner) {
                end(f);
            }
        });
        return f;
    }

    private static void end(Fixture f) {
        ReadyMadeLive.transportForTest(null);
        System.clearProperty(SAFETY);
        System.clearProperty(NUDGE);
        System.clearProperty(SEARCH);
        RuntimeConfig.invalidate();
        try {
            Marshal.global().cancel(f.wish().id(), CancelMode.STOP, "", "");
        } catch (RuntimeException ignored) {
            // already finished
        }
        WishNotes.global().forget(f.wish().id().toString());
        NudgeRace.global().forget(f.wish().id().toString());
        ReadyMadeLive.lastRace = null;
        WishProgress.clearWishBar(f.wish().id());
        BuildGate.settle(f.wish().id());
        MockPlayerNetwork.remove(f.player());
    }

    // ---- MCP plumbing ----

    private static McpProtocol.Headers headers(String session) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        if (session != null) {
            m.put("Mcp-Session-Id", session);
        }
        return McpProtocol.Headers.of(m);
    }

    private record Call(String tool, boolean error, String text, long ms) { }

    private static Call call(McpProtocol p, String session, String tool, String argsJson) {
        long t0 = System.currentTimeMillis();
        HttpReply r = p.handlePost(headers(session), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}}", 0L);
        JsonObject result = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("result");
        boolean err = result.has("isError") && result.get("isError").getAsBoolean();
        String text = result.has("content")
                ? result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()
                : String.valueOf(result);
        return new Call(tool, err, text, System.currentTimeMillis() - t0);
    }

    private static final String PLAN = "{\"status\":\"Making it\",\"about\":\"new\","
            + "\"steps\":[\"Making the fight pearl\"]}";

    /** await_wish → whatever {@code then} does, off the server thread. */
    private static AtomicReference<List<Call>> run(Fixture f, java.util.function.BiConsumer<String, List<Call>> then,
                                                   AtomicReference<Throwable> blew) {
        AtomicReference<List<Call>> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                String s = f.protocol().handlePost(headers(null),
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                        .headers().get("Mcp-Session-Id");
                List<Call> calls = new ArrayList<>();
                calls.add(call(f.protocol(), s, "await_wish", "{}"));
                then.accept(s, calls);
                out.set(calls);
            } catch (Throwable e) {
                blew.set(e);
            }
        }, "mfam-readymade-gametest");
        t.setDaemon(true);
        t.start();
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final String NOTE_MARK = "make_item effect:\"aggro\"";

    /** The headline: a hand-over pick rides the next good reply, once, as an option. */
    @GameTest(template = "empty_test", batch = "readymade_rides", timeoutTicks = TICKS)
    public static void aHandOverNudgeRidesTheNextReplyOnce(GameTestHelper helper) {
        Fixture f = begin(helper, "d", "rides");
        AtomicInteger asked = haiku("[\"d:aggro\"]", 200);
        AtomicReference<Throwable> blew = new AtomicReference<>();
        var calls = run(f, (s, out) -> {
            sleep(1_500);                                   // the nudge has landed by now
            out.add(call(f.protocol(), s, "plan_wish", PLAN));
            out.add(call(f.protocol(), s, "speak", "{}"));
        }, blew);
        helper.succeedWhen(() -> {
            if (blew.get() != null) {
                throw new AssertionError("threw: " + blew.get(), blew.get());
            }
            List<Call> c = calls.get();
            helper.assertTrue(c != null, Component.literal("still running"));
            helper.assertTrue(asked.get() == 1, Component.literal("Haiku was asked " + asked.get() + " times, not once"));
            helper.assertFalse(c.get(0).text().contains(NOTE_MARK), Component.literal("the note arrived ON await_wish — it "
                    + "cannot have: the nudge starts after the hand-over"));
            helper.assertTrue(c.get(1).text().contains(NOTE_MARK) && c.get(1).text().contains("none of these fits"),
                    Component.literal("the next reply (plan_wish) did not carry the pick, worded as an option: " + c.get(1).text()));
            helper.assertFalse(c.get(2).text().contains(NOTE_MARK), Component.literal("the note was delivered twice"));
        });
    }

    /** author_datapack waits (≤ 5 s) for a plan nudge in flight; an ordinary tool never does. */
    @GameTest(template = "empty_test", batch = "readymade_park", timeoutTicks = TICKS)
    public static void writingItsOwnWaitsForTheNudgeAndNothingElseDoes(GameTestHelper helper) {
        Fixture f = begin(helper, "d", "park");
        haiku("[\"d:aggro\"]", LATE_MS);
        AtomicReference<Throwable> blew = new AtomicReference<>();
        var calls = run(f, (s, out) -> {
            out.add(call(f.protocol(), s, "plan_wish", PLAN));         // the server's plan-first rule
            out.add(call(f.protocol(), s, "speak", "{}"));            // at once: must not wait
            out.add(call(f.protocol(), s, "author_datapack", "{}"));  // must wait, then carry the pick
        }, blew);
        helper.succeedWhen(() -> {
            if (blew.get() != null) {
                throw new AssertionError("threw: " + blew.get(), blew.get());
            }
            List<Call> c = calls.get();
            helper.assertTrue(c != null, Component.literal("still running"));
            helper.assertTrue(c.get(2).ms() < LATE_MS / 2, Component.literal("an ordinary tool waited " + c.get(2).ms() + " ms"));
            helper.assertFalse(c.get(2).text().contains(NOTE_MARK), Component.literal("the note landed before the nudge did"));
            helper.assertTrue(c.get(3).text().contains(NOTE_MARK),
                    Component.literal("author_datapack did not wait for the nudge in flight: " + c.get(3).text()));
            helper.assertTrue(c.get(3).ms() < 5_000 + 1_000, Component.literal("author_datapack waited past the ceiling"));
        });
    }

    /** "None of these" adds nothing to any reply. */
    @GameTest(template = "empty_test", batch = "readymade_none", timeoutTicks = TICKS)
    public static void aNoneAddsNothing(GameTestHelper helper) {
        Fixture f = begin(helper, "bcd", "none");
        AtomicInteger asked = haiku("[]", 100);
        AtomicReference<Throwable> blew = new AtomicReference<>();
        var calls = run(f, (s, out) -> {
            out.add(call(f.protocol(), s, "author_datapack", "{}"));
            out.add(call(f.protocol(), s, "speak", "{}"));
        }, blew);
        helper.succeedWhen(() -> {
            if (blew.get() != null) {
                throw new AssertionError("threw: " + blew.get(), blew.get());
            }
            List<Call> c = calls.get();
            helper.assertTrue(c != null, Component.literal("still running"));
            helper.assertTrue(asked.get() == 1, Component.literal("the nudged kinds were not asked in ONE call: " + asked.get()));
            for (Call x : c) {
                helper.assertFalse(x.text().contains("READY-MADE things"), Component.literal(x.tool() + " carried a note for a none"));
            }
        });
    }

    /** The shipped default: no knob, no question, no note. */
    @GameTest(template = "empty_test", batch = "readymade_off", timeoutTicks = TICKS)
    public static void offAsksNothing(GameTestHelper helper) {
        Fixture f = begin(helper, null, "off");
        AtomicInteger asked = haiku("[\"d:aggro\"]", 50);
        AtomicReference<Throwable> blew = new AtomicReference<>();
        var calls = run(f, (s, out) -> {
            sleep(500);
            out.add(call(f.protocol(), s, "author_datapack", "{}"));
        }, blew);
        helper.succeedWhen(() -> {
            if (blew.get() != null) {
                throw new AssertionError("threw: " + blew.get(), blew.get());
            }
            List<Call> c = calls.get();
            helper.assertTrue(c != null, Component.literal("still running"));
            helper.assertTrue(asked.get() == 0, Component.literal("Haiku was asked with every nudge off"));
            helper.assertFalse(c.get(1).text().contains(NOTE_MARK), Component.literal("a note with every nudge off"));
        });
    }

    /**
     * The race the tower reports: a first build call made while the nudge is still in flight is a
     * LOST race, decided — and logged with how late it was — when the nudge lands. The build call
     * itself never waits.
     */
    @GameTest(template = "empty_test", batch = "readymade_race", timeoutTicks = TICKS)
    public static void aBuildBeforeTheNudgeLandsIsALostRace(GameTestHelper helper) {
        Fixture f = begin(helper, "b", "race");
        haiku("[\"b:frog\"]", LATE_MS);
        AtomicReference<Throwable> blew = new AtomicReference<>();
        var calls = run(f, (s, out) -> {
            out.add(call(f.protocol(), s, "plan_wish", PLAN));         // the server's plan-first rule
            out.add(call(f.protocol(), s, "sculpt_creature", "{}"));   // at once: the worker commits
            sleep(LATE_MS + 1_000);                                    // the nudge lands after it
        }, blew);
        helper.succeedWhen(() -> {
            if (blew.get() != null) {
                throw new AssertionError("threw: " + blew.get(), blew.get());
            }
            List<Call> c = calls.get();
            helper.assertTrue(c != null, Component.literal("still running"));
            helper.assertFalse(c.get(2).error(), Component.literal("the build call was refused, so it decided nothing: " + c.get(2).text()));
            helper.assertTrue(c.get(2).ms() < LATE_MS / 2, Component.literal("the build call waited for the nudge: " + c.get(2).ms()));
            NudgeRace.Outcome o = ReadyMadeLive.lastRace;
            helper.assertTrue(o != null && o.wishId().equals(f.wish().id().toString()), Component.literal("no race was logged"));
            helper.assertFalse(o.won(), Component.literal("a nudge that landed after the first build was logged as a win"));
            helper.assertTrue(o.ms() > LATE_MS / 2 && o.picked(), Component.literal("the loss was not logged with its lag and its pick: " + o));
        });
    }
}
