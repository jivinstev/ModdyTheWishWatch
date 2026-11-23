package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.compat.Wearables;
import com.moddingfromamod.item.WishItem;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A kind status effect while he wears it, or after he uses it.</b>
 *
 * <p>Through the real {@code make_item} and the production give line, on a mock player: the
 * helmet's power arrives when it goes on and is RENEWED before night vision's ten-second flash
 * window (the reason a worn status is given longer than it is renewed); a held one works on a
 * right-click; and the unkind effects are refused in words. Red on {@code main}, where
 * {@code status} was not an effect.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class StatusEffectGameTest {

    /** Its own batch: mock players and the reloads each make_item does are process-wide. */
    private static final String BATCH = "statuseffect";

    private static JsonObject call(String name, String shape, String effect, String level, String seconds) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "helmet");
        o.addProperty("colour", "#3366CC");
        o.addProperty("shape", shape);
        o.addProperty("effect", "status");
        o.addProperty("count", 1);
        JsonObject p = new JsonObject();
        p.addProperty("EFFECT", effect);
        p.addProperty("LEVEL", level);
        p.addProperty("SECONDS", seconds);
        o.add("params", p);
        return o;
    }

    /** Make it, give it with the production line, and return the stack (or EMPTY, having failed). */
    private static ItemStack make(GameTestHelper helper, ServerPlayer p, String name, String shape,
                                  String effect, String seconds) {
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(call(name, shape, effect, "0", seconds));
        if (r.isError()) {
            helper.fail(Component.literal("make_item refused status " + effect + " / " + shape + ": " + r.text()));
            return ItemStack.EMPTY;
        }
        String arg = "held".equals(shape)
                ? MakeItemTool.gives("held", tool.lastMadeId(), ItemCatalogue.icon("helmet"), 1,
                        "A hint.", java.util.List.of(new MakeItemTool.Variant(name, 0x3366CC)))
                        .strip().substring("/give @s ".length()).replaceAll(" 1$", "")
                : MakeItemTool.itemArg(new MakeItemTool.Variant(name, 0x3366CC),
                        ItemCatalogue.icon("helmet"), "A hint.", null, null,
                        WishItem.WISH_FEATURE + ":\"" + tool.lastMadeId() + "\","
                                + WishItem.WORN + ":\"" + WornShape.slot(shape) + "\"", shape);
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(), "give @s " + arg + " 1");
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                return s.copy();
            }
        }
        helper.fail(Component.literal("nothing arrived from: " + arg));
        return ItemStack.EMPTY;
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void aNightVisionHelmetStaysOnAndNeverFlashes(GameTestHelper helper) {
        if (!Wearables.SUPPORTED || !CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        ItemStack helmet = make(helper, p, "Night Eyes " + helper.getLevel().getGameTime(),
                "worn_head", "night_vision", "3");
        if (helmet.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        p.getInventory().clearContent();
        p.removeAllEffects();
        p.setItemSlot(EquipmentSlot.HEAD, helmet);
        // The clock renews at 3 s (60 ticks); sample well after two renewals.
        helper.runAfterDelay(150, () -> {
            try {
                var e = p.getEffect(MobEffects.NIGHT_VISION);
                helper.assertTrue(e != null, Component.literal("the helmet is on and he has no night vision"));
                helper.assertTrue(e.getDuration() > 200, Component.literal("night vision is down to " + e.getDuration()
                        + " ticks — inside vanilla's 10 s flash window, so the helmet flickers"));
                helper.succeed();
            } finally {
                MockPlayerNetwork.remove(p);
            }
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900)
    public static void aHeldOneWorksWhenHeUsesIt(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        try {
            ItemStack cloak = make(helper, p, "Hide Charm " + helper.getLevel().getGameTime(),
                    "held", "invisibility", "20");
            if (cloak.isEmpty()) {
                return;
            }
            p.removeAllEffects();
            p.setItemInHand(InteractionHand.MAIN_HAND, cloak);
            p.gameMode.useItem(p, helper.getLevel(), cloak, InteractionHand.MAIN_HAND);
            var e = p.getEffect(MobEffects.INVISIBILITY);
            helper.assertTrue(e != null, Component.literal("he used it and is not invisible"));
            helper.assertTrue(e.getDuration() <= 20 * 20, Component.literal("a HELD one lasts what he asked, not the worn pad"));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
        }
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void anUnkindEffectIsRefused(GameTestHelper helper) {
        ToolResult poison = new MakeItemTool().call(call("Bad", "held", "poison", "0", "10"));
        helper.assertTrue(poison.isError() && poison.text().contains("EFFECT"),
                Component.literal("poison was accepted: " + poison.text()));
        ToolResult tooStrong = new MakeItemTool().call(call("Too Much", "held", "night_vision", "2", "10"));
        helper.assertTrue(tooStrong.isError() && tooStrong.text().contains("LEVEL"),
                Component.literal("night vision at level 2 was accepted: " + tooStrong.text()));
        helper.succeed();
    }
}
