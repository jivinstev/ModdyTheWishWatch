package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Does a stop actually stop?</b> — driven through the real dispatch, not through the gate class.
 *
 * <p>The open question this closes is stated: <i>a worker that ignores the cancel
 * result and keeps calling tools is invisible except as blocks appearing after the bar went away.</i>
 * So the assertion is not "the reply says cancelled" — it is that <b>the tool body never ran</b>,
 * counted.
 *
 * <p>Driven against {@code Marshal.global()} because that is what {@code McpProtocol} consults, which
 * is the point: a test against an injected marshal would prove the gate works and say nothing about
 * whether it is wired in.
 */
class McpCancelSeamTest {

    private static final UUID CHILD = UUID.nameUUIDFromBytes("steve".getBytes());

    private McpProtocol protocol;
    private final AtomicInteger ran = new AtomicInteger();

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

    /**
     * A build for a bound wish is refused until the wish has planned (PlanFirst). These
     * tests are about the CANCEL seam, so every wish here has already planned.
     */
    private static com.moddingfromamod.marshal.WishRun planned(com.moddingfromamod.marshal.WishRun run) {
        run.markPlanned();
        return run;
    }

    @BeforeEach
    void setUp() {
        Marshal.global().clear();
        ran.set(0);
        protocol = new McpProtocol(new ToolRegistry()
                .add(countingTool("build_structure"))
                .add(countingTool("await_wish")),
                new McpSessions());
    }

    @AfterEach
    void tearDown() {
        Marshal.global().clear();
    }

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

    private JsonObject call(String session, String tool) {
        HttpReply r = protocol.handlePost(headers(session),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + tool + "\",\"arguments\":{\"id\":\"castle\"}}}", 0L);
        return JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonObject("result");
    }

    private static boolean isError(JsonObject result) {
        return result.get("isError").getAsBoolean();
    }

    private static String text(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void aStoppedWishesToolCallIsRefusedAndTheToolBodyNeverRuns() {
        String session = openSession();
        UUID wishId = UUID.randomUUID();
        Marshal.global().submit(wishId, CHILD, "build me a castle");
        planned(Marshal.global().assign(session, wishId));

        assertFalse(isError(call(session, "build_structure")));
        assertEquals(1, ran.get(), "…it works normally first, so the refusal below means something");

        Marshal.global().cancel(wishId, CancelMode.STOP, "", null);

        JsonObject result = call(session, "build_structure");
        assertTrue(isError(result));
        assertTrue(text(result).contains("REFUSED"));
        assertEquals(1, ran.get(),
                "the tool body must NOT have run — informing the agent is not stopping it");
    }

    @Test
    void awaitWishStillAnswersSoTheWorkerCanGetBackToBeingUseful() {
        String session = openSession();
        UUID wishId = UUID.randomUUID();
        Marshal.global().submit(wishId, CHILD, "build me a castle");
        planned(Marshal.global().assign(session, wishId));
        Marshal.global().cancel(wishId, CancelMode.STOP, "", null);

        assertFalse(isError(call(session, "await_wish")));
        assertEquals(1, ran.get(), "await_wish really ran");
        assertFalse(isError(call(session, "build_structure")),
                "and once it has parked, it is not refused forever");
    }

    @Test
    void anotherWorkerOnAnUntouchedWishIsNotRefused() {
        String one = openSession();
        String two = openSession();
        UUID stopped = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        Marshal.global().submit(stopped, CHILD, "a castle");
        Marshal.global().submit(fine, CHILD, "a dragon");
        planned(Marshal.global().assign(one, stopped));
        planned(Marshal.global().assign(two, fine));

        Marshal.global().cancel(stopped, CancelMode.STOP, "", null);

        assertTrue(isError(call(one, "build_structure")));
        assertFalse(isError(call(two, "build_structure")),
                "stopping one wish must not stop the one nobody touched");
        assertEquals(1, ran.get());
    }

    @Test
    void whatTheWishUsedIsRecordedSoUndoCanBeAnsweredHonestly() {
        String session = openSession();
        UUID wishId = UUID.randomUUID();
        Marshal.global().submit(wishId, CHILD, "build me a castle");
        var run = planned(Marshal.global().assign(session, wishId));

        call(session, "build_structure");

        assertEquals(java.util.List.of("build_structure"), run.toolsUsed());
        assertEquals(1, run.made().size(), "and what it made, so an undo can take it back down");
        assertEquals("castle", run.made().get(0).id());
    }

    @Test
    void aRefusedCallIsNotCountedAsSomethingTheWishUsed() {
        String session = openSession();
        UUID wishId = UUID.randomUUID();
        Marshal.global().submit(wishId, CHILD, "build me a castle");
        var run = planned(Marshal.global().assign(session, wishId));
        Marshal.global().cancel(wishId, CancelMode.STOP, "", null);

        call(session, "build_structure");

        assertTrue(run.toolsUsed().isEmpty(),
                "a refused call did nothing, so it cannot be why undo is unavailable");
    }
}
