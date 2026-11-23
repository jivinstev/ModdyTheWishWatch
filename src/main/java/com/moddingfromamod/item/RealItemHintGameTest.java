package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> Q1, driven through the real {@code make_item}</b>: a made "Egg Bow" is refused naming
 * {@code minecraft:bow}, stores nothing, and goes through on the real bow or with
 * {@code only_looks_like}. Red on {@code main}: the made bow was simply minted.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RealItemHintGameTest {

    private static JsonObject egg(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#F0E0C0");
        o.addProperty("shape", "held");
        o.addProperty("effect", "teleport_home");
        o.addProperty("count", 1);
        return o;
    }

    @GameTest(template = "empty_test", batch = "realitemhint", timeoutTicks = 100)
    public static void aMadeItemNamedLikeARealOneIsSentToTheRealOne(GameTestHelper helper) {
        String name = "Egg Bow " + helper.getLevel().getGameTime();
        ToolResult r = new MakeItemTool().call(egg(name));
        helper.assertTrue(r.isError() && r.text().contains("minecraft:bow"),
                Component.literal("a made '" + name + "' was minted rather than sent to the real bow: " + r.text()));
        helper.assertTrue(ItemSpecStore.find(name) == null, Component.literal("the refused design was stored anyway"));

        String looks = "Toy Bow " + helper.getLevel().getGameTime();
        JsonObject o = egg(looks);
        o.addProperty("only_looks_like", true);
        ToolResult ok = new MakeItemTool().call(o);
        helper.assertTrue(!ok.isError(), Component.literal("only_looks_like did not get past the hint: " + ok.text()));
        helper.succeed();
    }
}
