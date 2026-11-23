package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Two workers, two wishes, at the same time — through the real protocol.</b>
 *
 * <p>This is the test the whole is for, and it costs nothing. Everything above the model is
 * exercised for real: two genuine MCP sessions, opened the way the CLI opens them, each calling
 * tools through the real {@link McpProtocol} dispatch with its own session id — the same seam that
 * carries the cancel check, the narrator and the action log. The only thing not present is the model
 * itself, which is the one part that costs money and time and the one part that has nothing to do
 * with whether two wishes can be granted at once.
 *
 * <p>What it settles that {@code MarshalSchedulingTest} cannot: the scripted pool proves the
 * <i>rules</i>, and a fake worker is not a session. Here the workers are distinguished by nothing
 * but their {@code Mcp-Session-Id} header, exactly as two parked agents would be — so if the binding
 * from session to wish were wrong anywhere in the dispatch, both workers would answer as one and
 * every assertion below would collapse.
 *
 * <p>Every test runs at {@code wishslots=3} and restores the flag afterwards. A neighbour must never
 * inherit a concurrent marshal, and the default is 1.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class TwoWorkersGameTest {

    private static UUID child(String test) {
        return UUID.nameUUIDFromBytes(("gt-two-workers-" + test).getBytes());
    }

    /** A session opened the way the CLI opens one. */
    private static String openSession(McpProtocol protocol) {
        var reply = protocol.handlePost(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L);
        return reply.headers().get("Mcp-Session-Id");
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

    private static JsonObject call(McpProtocol protocol, String session, String tool, String args) {
        var reply = protocol.handlePost(headers(session),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + tool + "\",\"arguments\":" + args + "}}", 0L);
        return JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result");
    }

    private static boolean isError(JsonObject result) {
        return result.get("isError").getAsBoolean();
    }

    /** A protocol over a tool that only records which session called it, on ITS OWN queue. */
    private static McpProtocol protocolRecording(Marshal marshal, Map<String, String> calledBy) {
        ToolRegistry tools = new ToolRegistry()
                .add(recorder(marshal, "build_structure", calledBy))
                // A build is refused until its wish has planned, so each wish plans first.
                .add(recorder(marshal, "plan_wish", calledBy))
                .add(recorder(marshal, "await_wish", calledBy));
        return new McpProtocol(tools, new McpSessions(), marshal);
    }

    /** A marshal of this test's own, at the slot count this test is about. */
    private static Marshal mine(int slots) {
        return Marshal.forTest(com.moddingfromamod.marshal.Clock.system(), slots);
    }

    private static McpTool recorder(Marshal marshal, String name, Map<String, String> calledBy) {
        return new McpTool() {
            @Override public String name() {
                return name;
            }

            @Override public String description() {
                return "records the calling worker";
            }

            @Override public JsonObject inputSchema() {
                return Schemas.prop(Schemas.object(), "id", "string", "an id");
            }

            @Override public ToolResult call(JsonObject args) {
                // AgentContext is bound by the dispatch, per call. If that binding were shared, or
                // leaked between calls, this is what would show it.
                var run = AgentContext.currentRun(marshal);
                calledBy.put(AgentContext.workerId(), run == null ? "<none>" : run.text());
                return ToolResult.ok("ok");
            }
        };
    }

    /**
     * <b>The headline.</b> Two sessions, two wishes, and each tool call knows which wish it belongs
     * to.
     */
    @GameTest(template = "empty_test")
    public static void twoSessionsEachGrantTheirOwnWish(GameTestHelper helper) {
        UUID me = child("headline");
        Marshal marshal = mine(3);
        Map<String, String> calledBy = new HashMap<>();
        McpProtocol protocol = protocolRecording(marshal, calledBy);
        {
            String one = openSession(protocol);
            String two = openSession(protocol);
            helper.assertFalse(one.equals(two), Component.literal("two sessions must be two sessions"));

            UUID dragonId = UUID.randomUUID();
            UUID castleId = UUID.randomUUID();
            marshal.submit(dragonId, me, "an ice dragon");
            marshal.submit(castleId, me, "a floating castle");
            WishRun dragon = marshal.claim(one);
            WishRun castle = marshal.claim(two);

            helper.assertTrue(dragon != null && castle != null,
                    Component.literal("at wishslots=3 both workers must get work; this is the whole feature"));
            helper.assertFalse(dragon.wishId().equals(castle.wishId()),
                    Component.literal("they must get DIFFERENT wishes, or one is doing the other's work"));

            call(protocol, one, "plan_wish", "{}");
            call(protocol, two, "plan_wish", "{}");
            call(protocol, one, "build_structure", "{\"id\":\"dragon_lair\"}");
            call(protocol, two, "build_structure", "{\"id\":\"castle\"}");

            helper.assertTrue("an ice dragon".equals(calledBy.get(one)),
                    Component.literal("worker one's tool call was attributed to: " + calledBy.get(one)));
            helper.assertTrue("a floating castle".equals(calledBy.get(two)),
                    Component.literal("worker two's tool call was attributed to: " + calledBy.get(two)));

            // …and what each MADE is recorded against its own wish, which is what an undo and the
            // ladder's in-flight rung both read.
            helper.assertTrue(dragon.made().size() == 1 && castle.made().size() == 1,
                    Component.literal("each wish records its own work"));
            helper.assertTrue("dragon_lair".equals(dragon.made().get(0).id()),
                    Component.literal("the dragon wish recorded: " + dragon.made()));
            helper.assertTrue("castle".equals(castle.made().get(0).id()),
                    Component.literal("the castle wish recorded: " + castle.made()));

            marshal.finish(dragonId);
            marshal.finish(castleId);
        }
        helper.succeed();
    }

    /**
     * Stopping one worker's wish must not touch the other's — the failure that only exists once two
     * things can be in flight.
     */
    @GameTest(template = "empty_test")
    public static void stoppingOneWorkerLeavesTheOtherWorking(GameTestHelper helper) {
        UUID me = child("stop-one");
        Marshal marshal = mine(3);
        {
            Map<String, String> calledBy = new HashMap<>();
            McpProtocol protocol = protocolRecording(marshal, calledBy);
            String one = openSession(protocol);
            String two = openSession(protocol);

            UUID stoppedId = UUID.randomUUID();
            UUID fineId = UUID.randomUUID();
            marshal.submit(stoppedId, me, "the one he stops");
            marshal.submit(fineId, me, "the one he does not");
            marshal.claim(one);
            marshal.claim(two);

            call(protocol, one, "plan_wish", "{}");
            call(protocol, two, "plan_wish", "{}");
            marshal.cancel(stoppedId, CancelMode.STOP, "", null);

            helper.assertTrue(isError(call(protocol, one, "build_structure", "{\"id\":\"a\"}")),
                    Component.literal("the stopped worker must be REFUSED at the seam"));
            helper.assertFalse(isError(call(protocol, two, "build_structure", "{\"id\":\"b\"}")),
                    Component.literal("…and the other one must carry on, untouched"));

            marshal.finish(fineId);
        }
        helper.succeed();
    }

    /**
     * Two wishes in flight anchor independently, all the way through the real dispatch.
     *
     * <p><b>The one test here that must use the global marshal</b>, because what it is checking is
     * {@code WishProgress.currentWishAnchor()} — a static with no seam, which asks
     * {@code AgentContext.currentRun()}, which asks {@code Marshal.global()}. Handing this test its
     * own queue would mean testing a seam instead of the path a wish actually takes.
     *
     * <p>So it is made safe the other way: {@code assign} instead of {@code claim}. Claim asks the
     * marshal to pick the next wish — which in a shared queue may be a neighbouring test's — while
     * assign names the wish, so this test can only ever be handed the two it submitted itself. It
     * also needs no slot count, which is what lets it leave {@code WishSlots} alone.
     */
    @GameTest(template = "empty_test")
    public static void twoWorkersKeepTheirOwnAnchors(GameTestHelper helper) {
        UUID me = child("anchors");
        {
            McpProtocol protocol = protocolRecording(Marshal.global(), new HashMap<>());
            String one = openSession(protocol);
            String two = openSession(protocol);
            String dim = helper.getLevel().dimension().location().toString();

            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            Marshal.global().submit(a, me, "one");
            Marshal.global().submit(b, me, "two");
            WishRun runA = Marshal.global().assign(one, a);
            WishRun runB = Marshal.global().assign(two, b);
            helper.assertTrue(runA != null && runB != null,
                    Component.literal("both wishes must be assignable; assign names the wish, so a neighbour cannot "
                            + "have taken it"));
            runA.lockAnchor(new Spot(dim, 10, 64, 10));
            runB.lockAnchor(new Spot(dim, -900, 70, 400));

            AgentContext.bind(one);
            var askedByOne = com.moddingfromamod.wish.WishProgress.currentWishAnchor();
            AgentContext.bind(two);
            var askedByTwo = com.moddingfromamod.wish.WishProgress.currentWishAnchor();
            AgentContext.unbind();

            helper.assertTrue(askedByOne != null && askedByOne.getX() == 10,
                    Component.literal("worker one was told to build at " + askedByOne));
            helper.assertTrue(askedByTwo != null && askedByTwo.getX() == -900,
                    Component.literal("worker two was told to build at " + askedByTwo
                            + " — a shared anchor gives both the same answer and one build lands "
                            + "on top of the other"));

            Marshal.global().finish(a);
            Marshal.global().finish(b);
        }
        helper.succeed();
    }

    /**
     * <b>The A/B, proven both ways.</b> The same script must be serial at one slot and concurrent at
     * three — otherwise a concurrency bug cannot be told from an ordinary one.
     */
    @GameTest(template = "empty_test")
    public static void theSameTwoWishesAreSerialAtOneSlotAndConcurrentAtThree(GameTestHelper helper) {
        helper.assertTrue(bothStarted(child("ab-three"), 3),
                Component.literal("at wishslots=3 both wishes must be in flight at once"));
        helper.assertFalse(bothStarted(child("ab-one"), 1),
                Component.literal("at wishslots=1 the second must NOT start — that is today's behaviour, and "
                        + "every gate written before this branch depends on it"));
        helper.succeed();
    }

    /**
     * The A/B arm, run on a marshal built for it.
     *
     * <p>The slot count is a CONSTRUCTOR ARGUMENT, not a static override, and that is the whole
     * repair: the serial arm below runs at one slot, and while it did so through
     * {@code WishSlots.override(1)} every overlapping test in the run was also at one slot. This
     * arm cannot be told apart from a real concurrency bug by anyone reading the other test's
     * failure, which is why an earlier issue's failing set moved run to run.
     */
    private static boolean bothStarted(UUID me, int slots) {
        Marshal marshal = mine(slots);
        McpProtocol protocol = protocolRecording(marshal, new HashMap<>());
        String one = openSession(protocol);
        String two = openSession(protocol);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        marshal.submit(a, me, "one");
        marshal.submit(b, me, "two");
        boolean first = marshal.claim(one) != null;
        boolean second = marshal.claim(two) != null;
        marshal.finish(a);
        marshal.finish(b);
        return first && second;
    }
}
