package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.BuildStructureTool;
import com.moddingfromamod.agent.tools.GiveItemsTool;
import com.moddingfromamod.agent.tools.InspectPlayerTool;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The child who wished has LEFT, and another child is still here</b> — an earlier issue's decision 1.
 *
 * <p>{@code PlayerKind.acting} answered "the owner if he is here, else the first child", so the
 * moment Alice logged off mid-wish every tool acting for her acted on BOB: her wished diamonds went
 * into his bag, {@code inspect_player} described him as her, a teleport would have moved him. The
 * rule is now per behaviour class, and each class has a gate here:
 *
 * <ul>
 *   <li><b>PERSON</b> — anything done to or about a child's own body, bag or history: the owner or
 *       NOBODY. Seen red: Bob received Alice's diamonds.</li>
 *   <li><b>WORLD</b> — a thing placed where the wish was aimed: it still goes ahead, so a build she
 *       asked for is standing when she comes back.</li>
 *   <li><b>COMMAND</b> (decision 3) — {@code run_command} is a world tool, but a selector that
 *       can pick out a player ({@code @p}, {@code @a}, {@code @r}, an unrestricted {@code @e}) has
 *       no right answer once she has left, so that command is refused before its batch runs.
 *       Seen red: {@code give @p} put her diamonds in Bob's bag.</li>
 * </ul>
 *
 * <p>Alice is never online in these tests — only her wish is: a Marshal run owned by a UUID no
 * player has, exactly what a wish looks like after its owner disconnected.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class OwnerOfflineGameTest {

    /** One batch per test: mock players and the Marshal are process-wide. */
    private static final String BATCH_PERSON = "ownerOfflinePerson";
    private static final String BATCH_WORLD = "ownerOfflineWorld";
    private static final String BATCH_COMMAND = "ownerOfflineCommand";

    private static final int TIMEOUT_TICKS = 4_000;

    private OwnerOfflineGameTest() {
    }

    /** Run {@code body} as the agent granting a wish owned by {@code owner}, off the server thread. */
    private static Thread as(UUID owner, String label, Runnable body) {
        UUID wish = UUID.randomUUID();
        String worker = "gt-owneroffline-" + label + "-" + wish;
        Marshal.global().submit(wish, owner, "gt owner offline " + label);
        Marshal.global().assign(worker, wish);
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                body.run();
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "gametest-owneroffline-" + label);
        t.setDaemon(true);
        return t;
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static int diamonds(ServerPlayer p) {
        int n = 0;
        for (var stack : p.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.DIAMOND)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    @GameTest(template = "empty_test", batch = BATCH_PERSON, timeoutTicks = TIMEOUT_TICKS)
    public static void aChildWhoLeftIsNeverReplacedByAnotherChild(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);
        UUID alice = UUID.randomUUID();                 // her wish; she is not in the world
        int bobBefore = diamonds(bob);
        AtomicReference<ToolResult> given = new AtomicReference<>();
        AtomicReference<ToolResult> inspected = new AtomicReference<>();
        Thread t = as(alice, "person", () -> {
            given.set(new GiveItemsTool().call(json(
                    "{\"item\":\"minecraft:diamond\",\"count\":3,\"why\":\"your wish\"}")));
            inspected.set(new InspectPlayerTool().call(json("{}")));
        });
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(inspected.get() != null, Component.literal("waiting for both tool calls"));
            try {
                helper.assertValueEqual(diamonds(bob), bobBefore,
                        Component.literal("Bob's diamonds — Alice wished for them and logged off, so they must not "
                                + "go to whoever happens to still be here"));
                helper.assertTrue(given.get().isError(),
                        Component.literal("give_items must refuse, not report success: " + given.get().text()));
                helper.assertTrue(given.get().text().contains("left"),
                        Component.literal("and say WHY, so the agent does not retry at someone else: "
                                + given.get().text()));
                helper.assertTrue(!inspected.get().text().contains("The child right now"),
                        Component.literal("inspect_player described Bob as the child whose wish this is: "
                                + inspected.get().text()));
            } finally {
                MockPlayerNetwork.remove(bob);
            }
        });
    }

    @GameTest(template = "empty_test", batch = BATCH_WORLD, timeoutTicks = TIMEOUT_TICKS)
    public static void aThingShePlacedStillGetsBuiltWhileSheIsAway(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);
        UUID alice = UUID.randomUUID();
        AtomicReference<ToolResult> built = new AtomicReference<>();
        Thread t = as(alice, "world", () -> built.set(new BuildStructureTool().call(json("""
                {"id":"gt_owner_offline_hut","name":"Hut","note":"owner offline gate","shapes":[
                  {"kind":"box","width":5,"height":4,"depth":5,"block":"minecraft:oak_planks",
                   "hollow":true,"name":"walls"}]}
                """))));
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(built.get() != null, Component.literal("waiting for the build"));
            try {
                helper.assertTrue(!built.get().isError(),
                        Component.literal("a build she asked for must still go ahead while she is away: "
                                + built.get().text()));
            } finally {
                MockPlayerNetwork.remove(bob);
            }
        });
    }

    @GameTest(template = "empty_test", batch = BATCH_COMMAND, timeoutTicks = TIMEOUT_TICKS)
    public static void aCommandThatPicksAPlayerIsRefusedWhileSheIsAway(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper);
        UUID alice = UUID.randomUUID();
        int bobBefore = diamonds(bob);
        AtomicReference<ToolResult> picked = new AtomicReference<>();
        AtomicReference<ToolResult> placed = new AtomicReference<>();
        Thread t = as(alice, "command", () -> {
            picked.set(new RunCommandTool().call(json(
                    "{\"command\":\"give @p minecraft:diamond 3\"}")));
            placed.set(new RunCommandTool().call(json(
                    "{\"command\":\"say the hut is ready\"}")));
        });
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(placed.get() != null, Component.literal("waiting for both commands"));
            try {
                helper.assertValueEqual(diamonds(bob), bobBefore,
                        Component.literal("Bob's diamonds — 'give @p' from Alice's wish must not reach whoever is "
                                + "nearest once she has left"));
                helper.assertTrue(picked.get().isError(),
                        Component.literal("a command that picks a player must be refused: " + picked.get().text()));
                helper.assertTrue(picked.get().text().contains("left"),
                        Component.literal("and say WHY: " + picked.get().text()));
                helper.assertTrue(!placed.get().isError(),
                        Component.literal("a command that picks no player still runs: " + placed.get().text()));
            } finally {
                MockPlayerNetwork.remove(bob);
            }
        });
    }
}
