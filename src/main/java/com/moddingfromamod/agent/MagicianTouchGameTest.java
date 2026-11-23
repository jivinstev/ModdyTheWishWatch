package com.moddingfromamod.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.BuilderSpecStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * <b>: a Magician edit marks the Builder make it changed — through the real protocol.</b>
 *
 * <p>Before this, a wish that changed one of his Builder makes left its kept spec as it was, and "✦ Change it in the
 * Builder" re-lowered the OLD picks under the same id, silently throwing the Magician's change away. The mark is made in
 * {@link McpProtocol}'s {@code tools/call}, where every making tool passes, so this drives that dispatch with stub
 * tools (the {@code PlanFirstGameTest} pattern): the tool bodies are not the point, the hook after them is.
 *
 * <p>Each test uses its own owner uuid: GameTests in a batch run concurrently and share the players directory.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class MagicianTouchGameTest {

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
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                .headers().get("Mcp-Session-Id");
    }

    private static JsonObject call(McpProtocol protocol, String session, String tool, String args) {
        var reply = protocol.handlePost(headers(session),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + tool + "\",\"arguments\":" + args + "}}", 0L);
        return JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result");
    }

    private static McpTool ok(String name) {
        return new McpTool() {
            @Override public String name() {
                return name;
            }

            @Override public String description() {
                return "succeeds";
            }

            @Override public JsonObject inputSchema() {
                return Schemas.object();
            }

            @Override public ToolResult call(JsonObject args) {
                return ToolResult.ok("ok");
            }
        };
    }

    private record Rig(McpProtocol protocol, String session, WishRun run) {
    }

    /** A wish of {@code owner}'s, claimed by a worker, with a plan: what a real wish is when it calls a making tool. */
    private static Rig rig(UUID owner, boolean builderMake) {
        Marshal marshal = Marshal.forTest(com.moddingfromamod.marshal.Clock.system(), 1);
        ToolRegistry tools = new ToolRegistry().add(ok("plan_wish")).add(ok("sculpt_creature"));
        McpProtocol protocol = new McpProtocol(tools, new McpSessions(), marshal);
        String session = open(protocol);
        marshal.submit(UUID.randomUUID(), owner, "give my cat a crown");
        WishRun run = marshal.claim(session);
        if (builderMake) {
            run.markBuilderMade();
        }
        call(protocol, session, "plan_wish", "{}");
        return new Rig(protocol, session, run);
    }

    /** A Builder make kept on {@code owner}'s shelf, as {@code BuilderMake.keep} writes it. */
    private static String keepACat(UUID owner) {
        String id = "cat_" + UUID.randomUUID().toString().substring(0, 6);
        BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2}").getAsJsonObject());
        try {
            BuilderSpecStore.put(owner.toString(), new BuilderSpecStore.Made(id, id, "creature", spec.toJson(), "w0",
                    System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return id;
    }

    /**
     * Run {@code body} off the server thread, as a real worker's tool calls are (a hook inside {@code tools/call} hands
     * work to the server and would deadlock on it), and pass when it returns no problem.
     */
    private static void offThread(GameTestHelper helper, java.util.function.Supplier<String> body) {
        java.util.concurrent.CompletableFuture<String> run = java.util.concurrent.CompletableFuture.supplyAsync(body);
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(run.isDone(), Component.literal("the calls are still running"));
            String problem;
            try {
                problem = run.join();
            } catch (RuntimeException e) {
                problem = "threw: " + e;
            }
            helper.assertTrue(problem == null, Component.literal(String.valueOf(problem)));
        });
    }

    @GameTest(template = "empty_test")
    public static void aWishThatChangesABuilderMakeMarksIt(GameTestHelper helper) {
        offThread(helper, () -> {
            UUID owner = UUID.randomUUID();
            String cat = keepACat(owner);
            String other = keepACat(owner);
            Rig r = rig(owner, false);
            if (r.run() == null) {
                return "the wish was not claimed";
            }
            JsonObject edited = call(r.protocol(), r.session(), "sculpt_creature",
                    "{\"id\":\"" + cat + "\",\"start_from\":\"" + cat + "\",\"spawn\":false}");
            if (edited.get("isError").getAsBoolean()) {
                return "the edit was refused: " + edited;
            }
            BuilderSpecStore.Made kept = BuilderSpecStore.get(owner.toString(), cat);
            if (kept == null || !kept.magicianTouched()) {
                return "a wish changed the Builder's cat, and its kept spec was not marked: " + kept;
            }
            if (!kept.touched().equals(r.run().wishId().toString())) {
                return "the mark must name the wish that made the change: " + kept.touched();
            }
            if (BuilderSpecStore.idFor(owner.toString(), cat, "Cat") != null) {
                return "a Magician-changed make must no longer offer ✦ Change it in the Builder";
            }
            if (BuilderSpecStore.get(owner.toString(), other).magicianTouched()) {
                return "only the thing the wish changed is marked";
            }
            return null;
        });
    }

    @GameTest(template = "empty_test")
    public static void aBuilderEditOfItsOwnMakeIsNotAMagicianChange(GameTestHelper helper) {
        offThread(helper, () -> {
            UUID owner = UUID.randomUUID();
            String cat = keepACat(owner);
            Rig r = rig(owner, true);
            call(r.protocol(), r.session(), "sculpt_creature", "{\"id\":\"" + cat + "\",\"spawn\":false}");
            if (BuilderSpecStore.get(owner.toString(), cat).magicianTouched()) {
                return "the Builder changing its own make (#1864) must not mark it";
            }
            if (!cat.equals(BuilderSpecStore.idFor(owner.toString(), cat, "Cat"))) {
                return "an untouched make keeps its ✦ Change it in the Builder link";
            }
            return null;
        });
    }

    @GameTest(template = "empty_test")
    public static void aWishThatMakesSomethingNewMarksNothing(GameTestHelper helper) {
        offThread(helper, () -> {
            UUID owner = UUID.randomUUID();
            String cat = keepACat(owner);
            Rig r = rig(owner, false);
            call(r.protocol(), r.session(), "sculpt_creature",
                    "{\"id\":\"baby_" + cat + "\",\"start_from\":\"" + cat + "\"}");   // a variant: a NEW id
            return BuilderSpecStore.get(owner.toString(), cat).magicianTouched()
                    ? "a baby version of his cat changes nothing about the cat" : null;
        });
    }
}
