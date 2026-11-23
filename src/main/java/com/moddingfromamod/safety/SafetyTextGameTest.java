package com.moddingfromamod.safety;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.agent.HttpReply;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.ToolRegistry;
import com.moddingfromamod.agent.tools.NotifyPlayerTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for the text sweep: the REAL dispatch, the REAL NotifyPlayerTool, and a REAL player.</b>
 *
 * <p>{@code SafetyTextSweepTest} settles the table and the policy in milliseconds. What it
 * structurally cannot reach is the thing this layer exists for — that a sentence carrying a lexicon
 * term never becomes a line in a child's chat. That needs the real tool, which needs a server, an
 * {@code AgentContext} bound to a real {@code WishRun}, and somebody to be told.
 *
 * <h2>What "the child never read it" is measured as</h2>
 *
 * <p>{@code NotifyPlayerTool.call} has exactly one statement that talks to the child —
 * {@code p.sendSystemMessage(…)} — and it sits inside the tool BODY. The sweep returns
 * {@code ToolResult.error} before {@code called.call(args)} runs, so "the body did not run" and
 * "the child was not sent it" are the same fact. The tool's own success answer,
 * {@code "Told the player: …"}, is the observable: present when the body ran, absent when it did
 * not. That is the same argument {@code SafetyPlanGameTest} makes about
 * {@code WishFeedback.planned}, and it is why the tool here is the real one rather than a counting
 * stub — a stub would prove the gate refuses and say nothing about whether the thing that talks to
 * the child was reached.
 *
 * <p>The second observable is the {@code safety} record: {@code layer:"text"},
 * {@code action:"BLOCK"}. It says the SWEEP is what refused, rather than the tool having failed for
 * some unrelated reason and the test reading a coincidence as a pass.
 *
 * <h2>The shadow twin IS the control</h2>
 *
 * <p>Same harness, same message, one property different, and the child gets told. That is a
 * stronger control than a benign sentence would be: it holds the text fixed and varies only the
 * thing under test, so "nothing happened" cannot be confused with "the harness never worked".
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SafetyTextGameTest {

    // ⚠ ONE BATCH EACH, for SafetyPlanGameTest's measured reason: tests in one batch run
    // CONCURRENTLY and share every static, and both tests here lean on the process-wide
    // `childsafety` property. One batch between them would have the enforcing test clearing the
    // mode out from under the shadow one, and the two would then be measuring each other.
    private static final String BATCH_BLOCK = "childsafety_text";
    private static final String BATCH_SHADOW = "childsafety_text_shadow";

    /** A term from the shipped lexicon. Moddy is about to say it to a seven-year-old. */
    private static final String TERM = "cocaine";

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";

    // ---- driving the real dispatch ----

    private static McpProtocol protocol() {
        return new McpProtocol(new ToolRegistry().add(new NotifyPlayerTool()), new McpSessions());
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

    private static JsonObject notify(McpProtocol p, String session, String message) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"notify_player\",\"arguments\":{\"message\":\"" + message + "\"}}}";
        HttpReply r = p.handlePost(headers(session), body, 0L);
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("result");
    }

    /**
     * Run {@code body} OFF the server thread and hand back somewhere to poll.
     *
     * <p>A {@code @GameTest} body runs ON the server thread, and {@code NotifyPlayerTool} hops
     * across to it — so calling it synchronously would deadlock, which is the hazard
     * {@code AgentBridge}'s same-thread guard exists to name.
     */
    private static AtomicReference<JsonObject> offThread(
            java.util.function.Supplier<JsonObject> body) {
        AtomicReference<JsonObject> out = new AtomicReference<>();
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
                out.set(boom);
            }
        }, "mfam-safety-text-gametest");
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

    // ---- the state a real notify arrives in ----

    private record Talking(ServerPlayer player, UUID wishId, String session,
                           McpProtocol protocol) { }

    private static Talking begin(GameTestHelper helper, String label) {
        // The enforcing arm, explicitly rather than by default: the default is ON today, but the
        // headline here is a claim about what ENFORCEMENT does and must not depend on the default.
        // The twin overrides this after begin(); end() clears it.
        System.setProperty(MODE, "on");
        RuntimeConfig.invalidate();
        SafetyLexicon.reload();
        ServerPlayer player = MockPlayerNetwork.inLevel(helper);
        McpProtocol p = protocol();
        String session = openSession(p);
        UUID wishId = UUID.randomUUID();
        Marshal.global().submit(wishId, player.getUUID(), label);
        WishRun run = Marshal.global().assign(session, wishId);
        helper.assertTrue(run != null, Component.literal("the wish must be assignable — assign names it, so a "
                + "neighbouring test cannot have taken it"));
        Talking t = new Talking(player, wishId, session, p);
        cleanUpWhenTheTestEnds(helper, t);
        return t;
    }

    /**
     * Put everything back when the TEST ends — pass or fail.
     *
     * <p>⚠ A listener rather than a {@code finally} inside {@code succeedWhen}, for the reason
     * {@code SafetyPlanGameTest} measured: that lambda RE-RUNS every tick until it stops throwing,
     * so a {@code finally} fires on the first poll — the one still waiting for the reply — and
     * tears the fixture down before the test has begun. Worse here than anywhere, because the thing
     * it would tear down is a process-wide property: every later tool call in the whole Gate B run
     * would be swept in whatever mode was left behind.
     */
    private static void cleanUpWhenTheTestEnds(GameTestHelper helper, Talking t) {
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                end(t);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                end(t);
            }

            @Override
            public void testAddedForRerun(GameTestInfo before, GameTestInfo after,
                                          GameTestRunner runner) {
                // A rerun builds its own fixture and arms its own teardown.
            }
        });
    }

    private static void end(Talking t) {
        try {
            Marshal.global().finish(t.wishId());
        } catch (RuntimeException ignored) {
            // Already finished; nothing here ends a wish, but a neighbour's stop path might.
        }
        // THE ONE THAT MUST NOT LEAK: it is process-wide, and a mode left set would sweep every
        // later tool call in every later batch of the run.
        System.clearProperty(MODE);
        RuntimeConfig.invalidate();
        MockPlayerNetwork.remove(t.player());
    }

    /** Every {@code safety} record at this layer, oldest first. */
    private static List<JsonObject> textRecords() {
        List<JsonObject> out = new ArrayList<>();
        try {
            Path f = ActionLog.path();
            if (f == null || !Files.exists(f)) {
                return out;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                // ONE LINE AT A TIME: the log is shared by every run on the machine, and a single
                // torn line (a full disk mid-append — measured 2026-09-27) used to throw out of the
                // whole read, hiding every record after it and failing both tests here for a
                // reason that had nothing to do with safety.
                JsonObject o;
                try {
                    o = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException torn) {
                    continue;
                }
                if (o.has("kind") && "safety".equals(o.get("kind").getAsString())
                        && "text".equals(o.has("layer") ? o.get("layer").getAsString() : "")) {
                    out.add(o);
                }
            }
        } catch (Exception ignored) {
            // A log we cannot read is not a reason to fail; the tool result is the headline.
        }
        return out;
    }

    private static JsonObject recordFor(String wishId) {
        for (JsonObject o : textRecords()) {
            if (o.has("wishId") && wishId.startsWith(o.get("wishId").getAsString())) {
                return o;
            }
        }
        return null;
    }

    // ---- the tests ----

    /**
     * <b>The headline.</b> A sentence carrying a lexicon term is refused at the seam, and the tool
     * that talks to the child never runs.
     */
    @GameTest(template = "empty_test", batch = BATCH_BLOCK, timeoutTicks = 600)
    public static void aLexiconTermNeverReachesTheChild(GameTestHelper helper) {
        Talking t = begin(helper, "make me a potion");
        var reply = offThread(() -> notify(t.protocol(), t.session(),
                "Here is your " + TERM + " potion!"));
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting for the refusal"));
            JsonObject result = reply.get();
            helper.assertTrue(isError(result),
                    Component.literal("a swept sentence must come back as an error: " + text(result)));
            String msg = text(result);

            // The tool body never ran, so neither did the one statement in it that sends a chat
            // line to this child.
            helper.assertFalse(msg.contains("Told the player"),
                    Component.literal("NotifyPlayerTool's own success answer must be absent — it is the evidence "
                            + "that its body, and the sendSystemMessage inside it, did not run: "
                            + msg));
            helper.assertTrue(msg.contains("`message`"),
                    Component.literal("the refusal must name the FIELD to rewrite: " + msg));
            helper.assertTrue(msg.contains("notify_player"),
                    Component.literal("…and the tool to call again: " + msg));
            helper.assertTrue(msg.contains("kid-safe"),
                    Component.literal("…and in what direction, or the agent improvises: " + msg));
            helper.assertFalse(msg.contains(TERM),
                    Component.literal("the refusal must not quote the term back — the agent copies its own tool "
                            + "results into chat more often than anyone expects: " + msg));

            JsonObject rec = recordFor(t.wishId().toString());
            helper.assertTrue(rec != null,
                    Component.literal("a text-layer safety record must exist, or something other than the sweep "
                            + "refused this and the test is reading a coincidence"));
            helper.assertTrue("BLOCK".equals(rec.get("action").getAsString()),
                    Component.literal("the record must say what was DONE: " + rec));
            helper.assertTrue("lexicon".equals(rec.get("grader").getAsString()),
                    Component.literal("…and that the floor decided it, not a model: " + rec));
        });
    }

    /**
     * <b>The control, and it is not optional.</b> The same harness and the same sentence, one
     * property different, and the child is told — so "nothing happened" cannot be confused with a
     * harness that never worked.
     */
    @GameTest(template = "empty_test", batch = BATCH_SHADOW, timeoutTicks = 600)
    public static void shadowModeTellsTheChildAnyway(GameTestHelper helper) {
        Talking t = begin(helper, "make me a potion");
        System.setProperty(MODE, "shadow");
        RuntimeConfig.invalidate();
        var reply = offThread(() -> notify(t.protocol(), t.session(),
                "Here is your " + TERM + " potion!"));
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting"));
            JsonObject result = reply.get();
            helper.assertFalse(isError(result),
                    Component.literal("shadow acts on nothing — the tool result included: " + text(result)));
            helper.assertTrue(text(result).contains("Told the player"),
                    Component.literal("the tool's own answer, so its body demonstrably ran and the child was sent "
                            + "the line: " + text(result)));

            JsonObject rec = recordFor(t.wishId().toString());
            helper.assertTrue(rec != null, Component.literal("shadow still GRADES and still LOGS; that is the whole "
                    + "point of it"));
            helper.assertTrue("SHADOW_BLOCK".equals(rec.get("action").getAsString()),
                    Component.literal("…with the prefix that stops a shadow block being counted as a child "
                            + "refused: " + rec));
        });
    }
}
