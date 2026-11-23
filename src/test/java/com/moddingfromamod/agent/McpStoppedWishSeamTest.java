package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.PickupHold;
import com.moddingfromamod.marshal.RunState;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.wish.Wish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A stopped wish must not land </b> — the two paths {@link McpCancelSeamTest} could not see,
 * driven through the real {@code McpProtocol.handlePost} against {@code Marshal.global()}, with tool
 * bodies that COUNT, because "the reply says refused" is not the claim: "the body never ran" is.
 *
 * <p>Live (2026-09-28): {@code /wish give my owl a tiny top hat}, {@code /wish undo} 4 s later, the
 * server logged {@code STOPPED … as UNDO (undone 0)}, the child read "I stopped and put it back!" —
 * and 24 s later the hat was on the owl.
 *
 * <ul>
 *   <li><b>Path A</b>: the stop lands before {@code await_wish} binds the worker (its pickup gates sit
 *       between the dequeue and the bind and can wait seconds on a grade). The marshal had no worker to
 *       wind down, so {@code CancelGate} had nothing to read; {@code await_wish} then either re-opened
 *       the wish as a FRESH run (submit re-opens a finished id) or bound nothing, and every tool ran.</li>
 *   <li><b>Path B</b>: a call parked on a pickup hold or a plan hold ({@code PickupHold}) had
 *       already passed {@code CancelGate}; a stop during the park neither woke it nor re-checked, so
 *       when the grade cleared the body ran.</li>
 * </ul>
 *
 * <p>Both sides are pinned: an untouched wish still grants on both paths, including a call that parks
 * and then clears.
 */
class McpStoppedWishSeamTest {

    private static final UUID CHILD = UUID.nameUUIDFromBytes("steve".getBytes());
    private static final UUID OTHER_CHILD = UUID.nameUUIDFromBytes("alex".getBytes());

    /** Every tool the report's wish could have used to land: the world-changers and the voice. */
    private static final List<String> ACTING = List.of(
            "modify_mob", "give_items", "run_command", "say", "finish_wish");

    private static final String SAFETY_PROP = ModdingFromAModMod.MODID + ".childsafety";

    private McpProtocol protocol;
    private WishQueue queue;
    private final AtomicInteger ran = new AtomicInteger();
    private String safetyBefore;

    private McpTool countingTool(String name) {
        return new McpTool() {
            @Override public String name() {
                return name;
            }

            @Override public String description() {
                return "counts how many times its body actually ran";
            }

            @Override public JsonObject inputSchema() {
                return Schemas.prop(Schemas.object(), "id", "string", "an id");
            }

            @Override public ToolResult call(JsonObject args) {
                ran.incrementAndGet();
                return ToolResult.ok("did it");
            }
        };
    }

    @BeforeEach
    void setUp() {
        // The pickup gates run for real in await_wish; with grading OFF they decide instantly and
        // offline (no grader process), which is the configuration whose window is the narrowest — so
        // a stop that still lands here lands everywhere.
        safetyBefore = System.getProperty(SAFETY_PROP);
        System.setProperty(SAFETY_PROP, "off");
        Marshal.global().clear();
        ran.set(0);
        queue = new WishQueue();
        ToolRegistry registry = new ToolRegistry().add(new AwaitWishTool(queue));
        ACTING.forEach(t -> registry.add(countingTool(t)));
        protocol = new McpProtocol(registry, new McpSessions());
    }

    @AfterEach
    void tearDown() {
        Marshal.global().all().forEach(r -> {
            PickupHold.global().forget(r.wishId());
            PickupHold.plans().forget(r.wishId());
        });
        Marshal.global().clear();
        AgentContext.unbind();
        if (safetyBefore == null) {
            System.clearProperty(SAFETY_PROP);
        } else {
            System.setProperty(SAFETY_PROP, safetyBefore);
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────────

    private static McpProtocol.Headers headers(String session) {
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        if (session != null) {
            m.put("Mcp-Session-Id", session);
        }
        return McpProtocol.Headers.of(m);
    }

    private String openSession() {
        return protocol.handlePost(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                .headers().get("Mcp-Session-Id");
    }

    private JsonObject call(String session, String tool, String argsJson) {
        HttpReply r = protocol.handlePost(headers(session),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + tool + "\",\"arguments\":" + argsJson + "}}", 0L);
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("result");
    }

    private JsonObject call(String session, String tool) {
        return call(session, tool, "{\"id\":\"owl\"}");
    }

    private JsonObject awaitWish(String session) {
        return call(session, "await_wish", "{\"timeoutSeconds\":1}");
    }

    private static boolean isError(JsonObject result) {
        return result.get("isError").getAsBoolean();
    }

    private static String text(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    /** A wish the way WishService makes one: a run in the marshal, and an entry in the queue. */
    private Wish wish(UUID player, String words) {
        Wish w = Wish.create(words, com.moddingfromamod.wish.WishSource.COMMAND, System.currentTimeMillis(),
                player, "steve", "minecraft:overworld", 0, 64, 0);
        Marshal.global().submit(w.id(), player, words);
        queue.offer(w);
        return w;
    }

    private void assertEveryActingToolRefused(String session, String why) {
        for (String tool : ACTING) {
            JsonObject r = call(session, tool);
            assertTrue(isError(r), why + " — " + tool + " was NOT refused: " + text(r));
        }
        assertEquals(0, ran.get(), why + " — a tool body ran for a wish the child had stopped");
    }

    // ── PATH A — the stop lands before await_wish binds the worker ────────────────────────────────

    /**
     * The report, end to end: the child stops the wish while it is still on its way to a worker, and
     * the worker's await_wish then reaches it. Nothing may be handed over, nothing re-opened, and every
     * tool that could land the hat is refused.
     */
    @Test
    void aWishStoppedBeforeTheBindIsNeverHandedOverAndNothingLands() {
        String session = openSession();
        Wish w = wish(CHILD, "give my owl a tiny top hat");
        // /moddy undo: the marshal is stopped; the dequeue's own book check sees nothing (no server
        // here, and live the book was moved a moment later on the server thread).
        assertTrue(Marshal.global().cancel(w.id(), CancelMode.UNDO, "", null).stopped());

        JsonObject handed = awaitWish(session);

        assertFalse(text(handed).contains("tiny top hat"),
                "await_wish handed the worker a wish the child had already stopped: " + text(handed));
        WishRun run = Marshal.global().run(w.id());
        assertEquals(RunState.STOPPED, run.state(),
                "await_wish RE-OPENED the stopped wish as a fresh run — the child was told it stopped");
        assertNull(Marshal.global().runForWorker(session), "the worker was bound to a stopped wish");
        assertEveryActingToolRefused(session, "path A, through the real await_wish");
    }

    /**
     * The narrowest form of path A, the 2026-11-18 repro: the stop lands between await_wish's submit
     * and its assign, so assign refuses (null). The worker must still be wound down, or it goes on
     * with an unbound wish that no gate can see.
     */
    @Test
    void aStopBetweenSubmitAndAssignStillWindsTheWorkerDown() {
        String session = openSession();
        UUID wishId = UUID.randomUUID();
        Marshal.global().submit(wishId, CHILD, "give my owl a tiny top hat");
        Marshal.global().cancel(wishId, CancelMode.UNDO, "", null);

        assertNull(Marshal.global().assign(session, wishId), "a stopped run must not be assigned");

        assertNotNull(Marshal.global().windingDown(session),
                "assign refused a stopped run and left the worker free to act on it");
        assertEveryActingToolRefused(session, "path A, stop between submit and assign");
        assertFalse(isError(call(session, "await_wish", "{\"timeoutSeconds\":1}")),
                "…and await_wish still answers, so the worker gets back to being useful");
    }

    /**
     * A worker whose wish was stopped before it was ever bound has no run of its own, and
     * {@code AgentContext.currentRun}'s "the single live run" fallback would hand it ANOTHER child's
     * wish — his plan, his bar, his build spot.
     */
    @Test
    void aStoppedWishsWorkerNeverResolvesToAnotherChildsRun() {
        String mine = openSession();
        String theirs = openSession();
        Wish other = wish(OTHER_CHILD, "a dragon");
        WishRun otherRun = Marshal.global().assign(theirs, other.id());
        otherRun.markPlanned();
        queue.clear();
        Wish w = wish(CHILD, "give my owl a tiny top hat");
        Marshal.global().cancel(w.id(), CancelMode.UNDO, "", null);

        awaitWish(mine);

        assertNull(AgentContext.with(mine, AgentContext::currentRun),
                "the stopped wish's worker resolved to another child's run");
        assertSame(otherRun, AgentContext.with(theirs, AgentContext::currentRun),
                "…while the other child's own worker still finds his");
        assertEveryActingToolRefused(mine, "the stopped wish's worker");
        assertFalse(isError(call(theirs, "modify_mob")), "the untouched wish is not refused");
        assertEquals(1, ran.get());
    }

    /**
     * The live shape's other branch: the BOOK already says STOPPED when the gates finish, so
     * await_wish drops the wish without asking the marshal to bind it. The worker must be wound down
     * all the same (found by StaleWishGameTest's grade-window case on its first Gate B run).
     */
    @Test
    void aWishTheBookSaysIsOverWindsTheWorkerDownToo() {
        String mine = openSession();
        String theirs = openSession();
        Wish other = wish(OTHER_CHILD, "a dragon");
        Marshal.global().assign(theirs, other.id()).markPlanned();
        UUID stopped = UUID.randomUUID();
        Marshal.global().submit(stopped, CHILD, "give my owl a tiny top hat");
        Marshal.global().cancel(stopped, CancelMode.UNDO, "", null);

        Marshal.global().droppedAfterTheGates(mine, stopped);

        assertNotNull(Marshal.global().windingDown(mine), "the worker is not winding down");
        assertNull(AgentContext.with(mine, AgentContext::currentRun),
                "the dropped wish's worker resolved to another child's run");
        assertEveryActingToolRefused(mine, "after a drop the book decided");
    }

    // ── PATH B — a call parked on a grade hold when the stop lands ────────────────────────────────

    private CompletableFuture<JsonObject> parked(String session, String tool) {
        return CompletableFuture.supplyAsync(() -> call(session, tool));
    }

    /** Wait until the call is really parked on the hold (it has passed CancelGate), not merely queued. */
    private static void untilParked(CompletableFuture<JsonObject> f) throws InterruptedException {
        Thread.sleep(300);
        assertFalse(f.isDone(), "the call was meant to park on the hold, and it returned: " + f.getNow(null));
    }

    private WishRun boundAndPlanned(String session, String words) {
        Wish w = wish(CHILD, words);
        queue.clear();
        WishRun run = Marshal.global().assign(session, w.id());
        run.markPlanned();
        return run;
    }

    @Test
    void aStopWhileACallIsParkedOnThePickupHoldRefusesIt() throws Exception {
        String session = openSession();
        WishRun run = boundAndPlanned(session, "give my owl a tiny top hat");
        PickupHold.global().open(run.wishId());

        CompletableFuture<JsonObject> f = parked(session, "modify_mob");
        untilParked(f);
        Marshal.global().cancel(run.wishId(), CancelMode.UNDO, "", null);

        // Well under the hold's own ceiling (pickupParkMs, ~40 s): the stop must WAKE the park.
        JsonObject r = f.get(5, TimeUnit.SECONDS);
        assertTrue(isError(r), "a call parked on the pickup hold ran after the stop: " + text(r));
        // …and the grade landing afterwards changes nothing.
        PickupHold.global().clear(run.wishId(), "");
        assertEquals(0, ran.get(), "the parked body ran after the child stopped the wish");
    }

    @Test
    void aStopWhileACallIsParkedOnThePlanHoldRefusesIt() throws Exception {
        String session = openSession();
        WishRun run = boundAndPlanned(session, "give my owl a tiny top hat");
        PickupHold.plans().open(run.wishId());

        CompletableFuture<JsonObject> f = parked(session, "say");
        untilParked(f);
        Marshal.global().cancel(run.wishId(), CancelMode.UNDO, "", null);

        JsonObject r = f.get(5, TimeUnit.SECONDS);
        assertTrue(isError(r), "a call parked on the plan hold ran after the stop: " + text(r));
        PickupHold.plans().clear(run.wishId(), "");
        assertEquals(0, ran.get(), "the parked body ran after the child stopped the wish");
    }

    // ── MUST STILL WORK ───────────────────────────────────────────────────────────────────────────

    @Test
    void anUntouchedWishIsHandedOverBoundAndItsToolsRun() {
        String session = openSession();
        Wish w = wish(CHILD, "give my owl a tiny top hat");

        JsonObject handed = awaitWish(session);

        assertFalse(isError(handed), text(handed));
        assertTrue(text(handed).contains("tiny top hat"), "the wish was not handed over: " + text(handed));
        WishRun run = Marshal.global().runForWorker(session);
        assertNotNull(run, "await_wish did not bind the worker");
        assertEquals(w.id(), run.wishId());
        assertEquals(RunState.WORKING, run.state());
        run.markPlanned();
        assertFalse(isError(call(session, "modify_mob")));
        assertFalse(isError(call(session, "say")));
        assertEquals(2, ran.get());
    }

    @Test
    void aCallParkedOnThePickupHoldRunsOnceItClears() throws Exception {
        String session = openSession();
        WishRun run = boundAndPlanned(session, "give my owl a tiny top hat");
        PickupHold.global().open(run.wishId());

        CompletableFuture<JsonObject> f = parked(session, "modify_mob");
        untilParked(f);
        PickupHold.global().clear(run.wishId(), "");

        JsonObject r = f.get(5, TimeUnit.SECONDS);
        assertFalse(isError(r), "a cleared pickup hold refused an untouched wish: " + text(r));
        assertEquals(1, ran.get());
    }

    @Test
    void aCallParkedOnThePlanHoldRunsOnceItClears() throws Exception {
        String session = openSession();
        WishRun run = boundAndPlanned(session, "give my owl a tiny top hat");
        PickupHold.plans().open(run.wishId());

        CompletableFuture<JsonObject> f = parked(session, "say");
        untilParked(f);
        PickupHold.plans().clear(run.wishId(), "");

        JsonObject r = f.get(5, TimeUnit.SECONDS);
        assertFalse(isError(r), "a cleared plan hold refused an untouched wish: " + text(r));
        assertEquals(1, ran.get());
    }

    @Test
    void aStopAfterTheBindIsStillRefusedAsBefore() {
        String session = openSession();
        WishRun run = boundAndPlanned(session, "give my owl a tiny top hat");
        assertFalse(isError(call(session, "modify_mob")));
        assertEquals(1, ran.get());

        Marshal.global().cancel(run.wishId(), CancelMode.UNDO, "", null);

        for (String tool : ACTING) {
            assertTrue(isError(call(session, tool)), tool + " ran after a stop");
        }
        assertEquals(1, ran.get());
    }

    @Test
    void aRedirectedWishIsStillHandedOverAsItsFreshRun() {
        // /moddy redirect stops the run and re-opens the SAME wish id as a fresh run before offering it
        // (WishCancel.reaim) — that one is wanted, and must not be mistaken for a stopped wish.
        String session = openSession();
        Wish w = wish(CHILD, "make it bigger");
        Marshal.global().cancel(w.id(), CancelMode.REDIRECT, "", "the stone tower");
        queue.clear();
        WishRun fresh = Marshal.global().submit(w.id(), CHILD, "make it bigger — aimed at: the stone tower");
        queue.offer(w.withText("make it bigger — aimed at: the stone tower"));

        JsonObject handed = awaitWish(session);

        assertTrue(text(handed).contains("the stone tower"), "the re-aimed wish was dropped: " + text(handed));
        assertSame(fresh, Marshal.global().runForWorker(session), "the re-aimed wish was not bound");
    }
}
