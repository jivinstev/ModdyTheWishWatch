package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A wish touches only the child who made it</b> — option 2 (2026-09-24).
 *
 * <p>Alice and Bob are both in the world and it is Alice's wish. The world is shared, so a command
 * that changes the world runs as before; a command aimed at players is narrowed to Alice, and one
 * that names Bob is refused. Seen red before {@code RunCommandTool} learned it: <i>"make everyone
 * glow"</i> made Bob glow, and <i>"give Bob a sword"</i> put one in his bag.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WishReachGameTest {

    private static final int TIMEOUT_TICKS = 4_000;

    private WishReachGameTest() {
    }

    private static JsonObject cmd(String command) {
        JsonObject o = new JsonObject();
        o.addProperty("command", command);
        return o;
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

    @GameTest(template = "empty_test", batch = "wishReach", timeoutTicks = TIMEOUT_TICKS)
    public static void aWishReachesOnlyTheChildWhoMadeIt(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer alice = MockPlayerNetwork.inLevel(helper, "Alice");
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper, "Bob");
        int bobBefore = diamonds(bob);
        var wish = java.util.UUID.randomUUID();
        String worker = "gt-wishreach-" + wish;
        Marshal.global().submit(wish, alice.getUUID(), "gt wish reach");
        Marshal.global().assign(worker, wish);
        AtomicReference<ToolResult> everyone = new AtomicReference<>();
        AtomicReference<ToolResult> gift = new AtomicReference<>();
        AtomicReference<ToolResult> night = new AtomicReference<>();
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                everyone.set(new RunCommandTool().call(cmd("effect give @a minecraft:glowing 120")));
                gift.set(new RunCommandTool().call(cmd("give Bob minecraft:diamond 3")));
                night.set(new RunCommandTool().call(cmd("time set night")));
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "gametest-wishreach");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(night.get() != null, Component.literal("waiting for all three commands"));
            // No finally: succeedWhen re-runs this every tick, and removing the players after the
            // first failed check made every later retry report on children who had gone. The
            // mocks retire with their test either way (MockPlayerNetwork.retireWhenItsOwnTestEnds).
            helper.assertTrue(alice.hasEffect(MobEffects.GLOWING),
                    Component.literal("'make everyone glow' must still make ALICE glow: " + everyone.get().text()));
            helper.assertTrue(!bob.hasEffect(MobEffects.GLOWING),
                    Component.literal("…and not Bob, whose wish it was not: " + everyone.get().text()));
            helper.assertValueEqual(diamonds(bob), bobBefore,
                    Component.literal("Bob's diamonds — a command naming him must be refused"));
            helper.assertTrue(gift.get().isError(), Component.literal("refused, not ran: " + gift.get().text()));
            helper.assertTrue(!night.get().isError(),
                    Component.literal("the shared world is still anybody's to change: " + night.get().text()));
            MockPlayerNetwork.remove(alice);
            MockPlayerNetwork.remove(bob);
        });
    }
}
