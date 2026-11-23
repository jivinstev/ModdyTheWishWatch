package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a {@code tp} by command is the wish taking him somewhere.</b> Through the agent's real
 * {@code run_command}, on a wish of his bound to a worker, from a worker thread — the path the live
 * "teleport me to a far away plains" wish took. A nudge does not count; a journey does.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishMovesGameTest {

    @GameTest(template = "empty_test", batch = "wishmoves", timeoutTicks = 200)
    public static void aTpByCommandIsWhereTheRevealIsShotFrom(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "mfam-wish-moves");
        var server = helper.getLevel().getServer();
        Wish wish = WishBook.get(server).add(Wish.create("teleport me to a far away plains", WishSource.TYPED,
                System.currentTimeMillis(), p.getUUID(), "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), p.getUUID(), wish.text());
        String worker = "gt-wish-moves";
        Marshal.global().assign(worker, wish.id());
        WishRun run = Marshal.global().run(wish.id());
        int x = p.getBlockX();
        int y = p.getBlockY();
        int z = p.getBlockZ();
        AtomicReference<Boolean> afterNudge = new AtomicReference<>();
        AtomicReference<String> said = new AtomicReference<>();
        Thread t = new Thread(() -> said.set(AgentContext.with(worker, () -> {
            new RunCommandTool().call(call("tp @s " + (x + 3) + " " + y + " " + z));
            afterNudge.set(run.tookHimSomewhere());
            return new RunCommandTool().call(call("tp @s " + (x + 200) + " " + y + " " + z)).text();
        })), "wishmoves-run-command");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(said.get() != null, Component.literal("waiting for run_command"));
            try {
                helper.assertTrue(Boolean.FALSE.equals(afterNudge.get()),
                        Component.literal("a 3-block nudge is not the wish taking him somewhere"));
                helper.assertTrue(said.get().contains("Ran: tp"), Component.literal("the tp did not run: " + said.get()));
                helper.assertTrue(run.tookHimSomewhere(),
                        Component.literal("a 200-block tp by command must count as the wish taking him somewhere (#1603)"));
            } finally {
                Marshal.global().finish(wish.id());
                BuildGate.settle(wish.id());
                MockPlayerNetwork.remove(p);
            }
        });
    }

    private static JsonObject call(String command) {
        JsonObject args = new JsonObject();
        JsonArray list = new JsonArray();
        list.add(command);
        args.add("command", list);
        return args;
    }
}
