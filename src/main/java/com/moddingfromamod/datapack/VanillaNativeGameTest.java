package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: what {@link VanillaNative} says the game already does, the game does — here, on
 * this Minecraft.</b>
 *
 * <p>Each way is built through the REAL {@code make_item} and handed over by the real give line, and
 * the stack he would hold is checked for the fact the way stands on: a golden carrot is in the tags
 * that make horses and rabbits follow it; the enchantment is on the item and is one that item takes
 * (a Loyalty trident, Frost Walker boots). A table of vanilla facts that nothing checks is how a
 * lesson ends up teaching a form the game refuses — this repo's copycat, again.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class VanillaNativeGameTest {

    @GameTest(template = "empty_test", batch = "vanillanative", timeoutTicks = 1200)
    public static void everyWayTheTableNamesIsTrueInThisGame(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        try {
            for (VanillaNative.Way w : VanillaNative.WAYS) {
                ItemStack got = build(helper, p, w);
                helper.assertTrue(BuiltInRegistries.ITEM.getKey(got.getItem()).toString().equals(w.baseItem()),
                        Component.literal(w.part() + ": make_item handed over " + got + ", not " + w.baseItem()));
                String fact = w.fact();
                if (fact.startsWith("tag:")) {
                    for (String tag : fact.substring(4).split(",")) {
                        helper.assertTrue(got.is(TagKey.create(Registries.ITEM, Identifier.parse(tag))),
                                Component.literal(w.part() + ": " + w.baseItem() + " is not in #" + tag
                                        + " on this Minecraft, so it does not do what the table says"));
                    }
                } else if (fact.startsWith("enchantment:")) {
                    String id = fact.substring("enchantment:".length());
                    ItemEnchantments ench = got.get(DataComponents.ENCHANTMENTS);
                    boolean on = false;
                    boolean takes = false;
                    if (ench != null) {
                        for (var holder : ench.keySet()) {
                            if (holder.unwrapKey().map(k -> k.location().toString().equals(id)).orElse(false)) {
                                on = true;
                                takes = holder.value().isSupportedItem(got);
                            }
                        }
                    }
                    helper.assertTrue(on, Component.literal(w.part() + ": " + id + " is not on the " + w.baseItem() + " he got"));
                    helper.assertTrue(takes, Component.literal(w.part() + ": " + w.baseItem() + " does not take " + id
                            + " — it would sit on the item and do nothing"));
                }
            }
        } finally {
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }

    /**
     * The retry steers to the real item: a wish the real item does by itself, sent in a shape that
     * wants an effect and without one, is told the shape is `plain` (measured: frost walker boots
     * came back 3 in 8 as worn_feet with no effect).
     */
    @GameTest(template = "empty_test", batch = "vanillanative_hint", timeoutTicks = 600)
    public static void aRealItemSentWithNoEffectIsToldToBePlain(GameTestHelper helper) {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Frost Boots");
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#80C0FF");
        o.addProperty("shape", "worn_feet");
        o.addProperty("count", 1);
        o.addProperty("base_item", "minecraft:diamond_boots");
        ToolResult r = new MakeItemTool().call(o);
        helper.assertTrue(r.isError() && r.text().contains("use shape plain"),
                Component.literal("a real item with no effect must be told to be plain: " + r.text()));
        helper.succeed();
    }

    private static ItemStack build(GameTestHelper helper, ServerPlayer p, VanillaNative.Way w) {
        String name = "Native " + w.part() + " " + helper.getLevel().getGameTime();
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FFAA00");
        o.addProperty("shape", "plain");
        o.addProperty("count", 1);
        o.addProperty("base_item", w.baseItem());
        if (w.enchantment() != null) {
            JsonObject levels = new JsonObject();
            levels.addProperty(w.enchantment(), ItemProperties.ENCHANTMENTS.get(w.enchantment()));
            JsonObject props = new JsonObject();
            props.add(ItemProperties.ENCHANTMENTS_KEY, levels);
            o.add("properties", props);
        }
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        helper.assertFalse(r.isError(), Component.literal(w.part() + ": make_item refused the vanilla way: " + r.text()));
        String line = MakeItemTool.gives("plain", tool.lastMadeId(), ItemCatalogue.icon("wand"), 1,
                "A hint.", java.util.List.of(new MakeItemTool.Variant(name, 0xFFAA00)),
                ItemRecipient.ME, null, w.baseItem());
        // The real give line carries no properties by itself; the wish's own give does. Give by
        // the pack, the way the agent hands it over.
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(),
                "wishdatapack " + tool.lastMadeId() + " give");
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                return s.copy();
            }
        }
        helper.fail(Component.literal(w.part() + ": nothing arrived (the give line would have been: " + line + ")"));
        return ItemStack.EMPTY;
    }
}
