package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.AuthorDatapackTool;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

import java.util.concurrent.CompletableFuture;

/**
 * <b>, driven through the real {@code author_datapack}</b>: a pack that hands out a
 * made "Egg Bow" by a hand-written {@code give} is refused once, naming {@code minecraft:bow} and
 * make_item; the second call goes through (a look-alike he really wants), and a pack that gives
 * the REAL bow is never refused. Red on {@code main}: the made bow compiled and loaded silently.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class DatapackItemGivesGameTest {

    private static JsonObject pack(String id, String item) {
        JsonObject o = new JsonObject();
        o.addProperty("feature_id", id);
        o.addProperty("description", "an egg bow");
        o.addProperty("cbscript", "reset\n    /give @s " + item
                + "[minecraft:custom_name='{\"text\":\"Egg Bow\"}'] 1\nend\n");
        o.addProperty("note", "gametest #1068");
        return o;
    }

    @GameTest(template = "empty_test", batch = "datapackitemgives", timeoutTicks = 200_000)
    public static void aPackThatGivesAMadeBowIsSentToMakeItem(GameTestHelper helper) {
        String t = Long.toString(helper.getLevel().getGameTime());
        String made = "gt_eggbow_made_" + t;
        if (!CbScriptCompiler.available()) {
            System.out.println("DATAPACK ITEM GIVES: SKIPPED — no CBScript compiler here.");
            helper.succeed();
            return;
        }
        // Off the server thread: a call that is NOT refused compiles and loads, which hops to the
        // server thread. The timeout is huge on purpose: a GameTest server ticks as fast as it can
        // (2400 ticks went by in 0.95 s here), and a compile plus a pack reload is seconds of WALL
        // time.
        CompletableFuture<ToolResult> first = CompletableFuture.supplyAsync(
                () -> new AuthorDatapackTool().call(pack(made, "moddingfromamod:wish_item")));
        // thenApplyASYNC: a plain thenApply runs on the CALLING thread when `first` has already
        // finished — the server thread, where a compile's hop deadlocks. The refusal is fast, so
        // that race was lost once in 21 runs (gate-b on t6f10-stronger, 2026-09-28).
        CompletableFuture<ToolResult> second = first.thenApplyAsync(
                f -> new AuthorDatapackTool().call(pack(made, "moddingfromamod:wish_item")));
        CompletableFuture<ToolResult> real = second.thenApplyAsync(
                r -> new AuthorDatapackTool().call(pack("gt_eggbow_real_" + t, "minecraft:bow")));
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!real.isDone()) {
                helper.runAfterDelay(10, () -> poll[0].run());
                return;
            }
            ToolResult f = first.join();
            helper.assertTrue(f.isError() && f.text().contains("minecraft:bow") && f.text().contains("make_item"),
                    Component.literal("a pack giving a made 'Egg Bow' was not sent to make_item's real bow: " + f.text()));
            ToolResult s = second.join();
            ToolResult r = real.join();
            helper.assertTrue(!s.text().contains("only CALLED"),
                    Component.literal("the refusal must give way on the second call: " + s.text()));
            helper.assertTrue(!r.text().contains("only CALLED"),
                    Component.literal("a pack giving the REAL bow was refused: " + r.text()));
            helper.succeed();
        };
        helper.runAfterDelay(10, () -> poll[0].run());
    }
}
