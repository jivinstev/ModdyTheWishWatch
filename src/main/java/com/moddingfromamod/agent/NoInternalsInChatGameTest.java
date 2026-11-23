package com.moddingfromamod.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestCompat;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.BuildGate;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishSource;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The agent's bookkeeping never reaches a child's chat</b>.
 *
 * <p>Seen 2026-11-16, filming the how-to-wish film with a real Opus-low grant: he asked Moddy a
 * question, the agent answered it, and then his chat read <i>"Question answered; nothing built, so
 * no finish_building."</i> A question builds nothing, so it never calls {@code finish_building} and
 * has had no celebration — and on that path {@code finish_wish}'s {@code note} on a {@code done} IS
 * read out (it is the one celebration), while the note's own schema told the agent a done note is
 * "recorded, not read out". So it wrote its bookkeeping there.
 *
 * <p>Driven the way the agent drives it: an answer-only question wish through the REAL
 * {@code tools/call} dispatch, over the REAL tool registry ({@link WishAgent#buildTools()}), with a
 * mock child reading the chat. It looks (a question must, {@code LookFirst}), answers with
 * {@code notify_player}, tries a second line that names a tool, and ends with {@code finish_wish}
 * carrying the exact note from the film. The child must read the answer, and no line that names a
 * tool or narrates the agent's own process.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class NoInternalsInChatGameTest {

    /** The line that reached the child's chat in the film (the agent's words, not a child's). */
    static final String BOOKKEEPING = "Question answered; nothing built, so no finish_building.";
    /** A real answer, which must still reach him. */
    static final String ANSWER = "Hold your watch and press the button, then say your wish!";
    /** A second slip: an ordinary-looking line that names a tool. */
    static final String NAMES_A_TOOL = "I used list_creatures to look around for you!";

    @GameTest(template = "empty_test", batch = "noInternalsInChat2366", timeoutTicks = 1000000)
    public static void anAnswerOnlyWishShowsTheAnswerAndNoToolNames(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        var server = helper.getLevel().getServer();
        Wish wish = WishBook.get(server).add(Wish.create("how do I use my wish watch?",
                WishSource.TYPED, System.currentTimeMillis(), child.getUUID(), "Steve",
                "minecraft:overworld", 0, 64, 0));
        McpProtocol protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions(),
                Marshal.global(), () -> false);
        String session = open(protocol);
        Marshal.global().submit(wish.id(), child.getUUID(), wish.text());
        Marshal.global().assign(session, wish.id());
        BuildGate.startWish(wish.id());
        int before = MockPlayerNetwork.chat(child).size();

        AtomicReference<JsonObject> toolNamed = new AtomicReference<>();
        AtomicReference<JsonObject> finished = new AtomicReference<>();
        AtomicReference<Throwable> died = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        Thread t = new Thread(() -> {
            try {
                call(protocol, session, "list_wishes", new JsonObject());     // a question looks first
                JsonObject answer = new JsonObject();
                answer.addProperty("message", ANSWER);
                call(protocol, session, "notify_player", answer);
                JsonObject slip = new JsonObject();
                slip.addProperty("message", NAMES_A_TOOL);
                toolNamed.set(call(protocol, session, "notify_player", slip));
                JsonObject end = new JsonObject();
                end.addProperty("id", wish.shortId());
                end.addProperty("verdict", "done");
                end.addProperty("note", BOOKKEEPING);
                JsonObject r = call(protocol, session, "finish_wish", end);
                // finish_wish turns away a wish that never called finish_building ONCE and then
                // gives way (BuildGate.refuseFinish) — the agent in the film called it again.
                if (r.get("isError").getAsBoolean() && r.toString().contains("finish_building")) {
                    r = call(protocol, session, "finish_wish", end);
                }
                finished.set(r);
            } catch (Throwable e) {
                died.set(e);
            } finally {
                done.set(true);
            }
        }, "no-internals-in-chat-gametest");
        t.setDaemon(true);
        t.start();

        long startedMs = System.currentTimeMillis();
        helper.succeedWhen(() -> {
            if (!done.get() && System.currentTimeMillis() - startedMs > 30_000L) {
                helper.testInfo.fail(GameTestCompat.assertion("the question wish did not end in 30 s of wall clock"));
                return;
            }
            helper.assertTrue(done.get(), Component.literal("waiting for the question wish to end"));
            try {
                helper.assertTrue(died.get() == null, Component.literal("the driver thread died: " + died.get()));
                List<String> all = MockPlayerNetwork.chat(child);
                List<String> chat = List.copyOf(all.subList(Math.min(before, all.size()), all.size()));
                String transcript = "\n  " + String.join("\n  ", chat);
                helper.assertTrue(finished.get().toString().contains("ended as DONE"),
                        Component.literal("finish_wish did not end the wish: " + finished.get()));
                helper.assertTrue(chat.stream().anyMatch(l -> l.contains(ANSWER)),
                        Component.literal("the real answer did not reach him —" + transcript));
                for (String line : chat) {
                    String low = line.toLowerCase(java.util.Locale.ROOT);
                    for (String internal : List.of("finish_building", "list_creatures", "finish_wish",
                            "notify_player", "question answered", "nothing built")) {
                        helper.assertFalse(low.contains(internal), Component.literal(
                                "the agent's bookkeeping reached the child's chat (" + internal + ") —"
                                        + transcript));
                    }
                }
                // …and the agent is TOLD, so it does not believe he read it and learns the rule.
                helper.assertTrue(toolNamed.get().toString().contains("NOT shown to the child"),
                        Component.literal("the agent was not told its tool-naming line was held back: "
                                + toolNamed.get()));
                helper.assertTrue(finished.get().toString().contains("NOT shown to the child"),
                        Component.literal("the agent was not told its bookkeeping note was held back: "
                                + finished.get()));
                System.out.println("NoInternalsInChatGameTest — the child's chat:" + transcript);
                helper.succeed();
            } catch (GameTestAssertException e) {
                helper.testInfo.fail(GameTestCompat.failure(e));
            } finally {
                if (done.get()) {
                    Marshal.global().finish(wish.id());
                    BuildGate.settle(wish.id());
                }
            }
        });
    }

    /**
     * The BACKSTOP, on its own: a line that reaches the speech layer without passing the dispatch
     * (a direct call, a stored plan say) is still never said — the agent's words give way to Moddy's
     * default, and a normal line still goes through.
     */
    @GameTest(template = "empty_test", batch = "noInternalsInChat2366")
    public static void theSpeechLayerHoldsBackALineThatSlippedPastTheDispatch(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        int before = MockPlayerNetwork.chat(child).size();
        com.moddingfromamod.wish.WishFeedback.granted(child, BOOKKEEPING);
        com.moddingfromamod.wish.WishFeedback.verdictRough(child, NAMES_A_TOOL);
        com.moddingfromamod.wish.WishFeedback.described(child, ANSWER);
        List<String> all = MockPlayerNetwork.chat(child);
        List<String> chat = List.copyOf(all.subList(Math.min(before, all.size()), all.size()));
        String transcript = "\n  " + String.join("\n  ", chat);
        helper.assertTrue(chat.stream().noneMatch(l -> l.contains("finish_building")
                        || l.contains("list_creatures") || l.contains("Question answered")),
                Component.literal("a tool-named line reached the child —" + transcript));
        helper.assertTrue(chat.stream().anyMatch(l -> l.contains("Your wish came true!")),
                Component.literal("the held-back grant line did not fall back to Moddy's own —" + transcript));
        helper.assertTrue(chat.stream().anyMatch(l -> l.contains(ANSWER)),
                Component.literal("a normal line was held back too —" + transcript));
        helper.succeed();
    }

    // ---- the in-process MCP client (LookFirstGameTest's shape) ----

    private static McpProtocol.Headers headers(String session) {
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        if (session != null) {
            m.put("Mcp-Session-Id", session);
        }
        return McpProtocol.Headers.of(m);
    }

    private static String open(McpProtocol protocol) {
        return protocol.handlePost(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", System.currentTimeMillis())
                .headers().get("Mcp-Session-Id");
    }

    private static JsonObject call(McpProtocol protocol, String session, String tool, JsonObject args) {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", args);
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", 2);
        msg.addProperty("method", "tools/call");
        msg.add("params", params);
        var reply = protocol.handlePost(headers(session), msg.toString(), System.currentTimeMillis());
        String body = reply.body();
        int at = body.indexOf('{');     // an SSE reply carries the JSON after "data: "
        JsonObject o = JsonParser.parseString(at < 0 ? "{}" : body.substring(at, body.lastIndexOf('}') + 1))
                .getAsJsonObject();
        if (!o.has("result")) {
            throw new IllegalStateException(tool + " gave no result: " + body);
        }
        return o.getAsJsonObject("result");
    }
}
