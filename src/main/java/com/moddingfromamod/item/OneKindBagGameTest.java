package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.ItemProperties;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import com.moddingfromamod.testing.gt.GameTestHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>: a backpack that holds only food, on the real game.</b> His survival inventory
 * menu, a real bundle carrying {@code holds_only: food}: a stone clicked onto it stays on his cursor
 * and the bag stays empty; bread clicked onto it goes in. Both directions: the bag carried and
 * clicked onto a stone takes nothing. Red on {@code main}: the property does not exist, so the
 * stone goes in. And every tag in the table must be a real, non-empty tag on this Minecraft.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class OneKindBagGameTest {

    private static ItemStack bag(String word) throws Exception {
        JsonObject props = new JsonObject();
        props.addProperty(ItemProperties.HOLDS_ONLY_KEY, word);
        String entries = ItemProperties.dataEntries(props);
        ItemStack s = new ItemStack(Items.BUNDLE);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(TagParser.parseCompoundFully("{" + entries.substring(1) + "}")));
        return s;
    }

    private static int inside(ItemStack bag) {
        var c = bag.get(DataComponents.BUNDLE_CONTENTS);
        return c == null ? 0 : c.size();
    }

    private static ServerPlayer him(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        return p;
    }

    /** Slot index of inventory slot 9 (the first main-inventory slot) in his own menu. */
    private static final int BAG_SLOT = 9;
    private static final int THING_SLOT = 10;

    @GameTest(template = "empty_test", batch = "onekindbag", timeoutTicks = 200)
    public static void aStoneStaysOnTheCursorAndBreadGoesIn(GameTestHelper helper) throws Exception {
        if (!com.moddingfromamod.compat.Bundles.SUPPORTED) {
            helper.succeed();   // 1.21.1: refused by name, asserted by the unit test
            return;
        }
        ServerPlayer p = him(helper);
        p.getInventory().setItem(BAG_SLOT, bag("food"));
        var menu = p.inventoryMenu;
        menu.setCarried(new ItemStack(Items.STONE, 5));
        menu.clicked(BAG_SLOT, 0, ContainerInput.PICKUP, p);   // stone clicked onto the bag
        ItemStack theBag = p.getInventory().getItem(BAG_SLOT);
        helper.assertTrue(inside(theBag) == 0, Component.literal("a stone went into the food bag: " + theBag.get(DataComponents.BUNDLE_CONTENTS)));
        helper.assertTrue(menu.getCarried().is(Items.STONE) && menu.getCarried().getCount() == 5,
                Component.literal("the stone did not stay on his cursor: " + menu.getCarried()));
        menu.setCarried(new ItemStack(Items.BREAD, 3));
        menu.clicked(BAG_SLOT, 0, ContainerInput.PICKUP, p);   // bread clicked onto the bag
        helper.assertTrue(inside(p.getInventory().getItem(BAG_SLOT)) > 0, Component.literal("bread did not go into the food bag"));

        // The other direction: the bag on the cursor, clicked onto a stone in a slot.
        p.getInventory().setItem(THING_SLOT, new ItemStack(Items.COBBLESTONE, 4));
        menu.setCarried(p.getInventory().removeItemNoUpdate(BAG_SLOT));
        menu.clicked(THING_SLOT, 1, ContainerInput.PICKUP, p);
        helper.assertTrue(p.getInventory().getItem(THING_SLOT).is(Items.COBBLESTONE)
                        && p.getInventory().getItem(THING_SLOT).getCount() == 4,
                Component.literal("the carried bag took cobblestone from the slot: " + p.getInventory().getItem(THING_SLOT)));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "onekindbag", timeoutTicks = 100)
    public static void everyKindNamesARealTag(GameTestHelper helper) {
        if (!com.moddingfromamod.compat.Bundles.SUPPORTED) {
            // 1.21.1 never makes the bag (refused by name), and its tags differ — #c:flowers/tall is
            // empty there while #minecraft:tall_flowers is not: the table is 1.21.4's.
            helper.succeed();
            return;
        }
        List<String> bad = new ArrayList<>();
        ItemProperties.HOLDS_ONLY.forEach((word, tags) -> {
            for (String tag : tags.split(",")) {   // EACH one, or a dead half hides behind a live one
                TagKey<Item> key = TagKey.create(Registries.ITEM, Identifier.parse(tag));
                boolean any = false;
                for (Item i : BuiltInRegistries.ITEM) {
                    if (new ItemStack(i).is(key)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    bad.add(word + " → #" + tag);
                }
            }
        });
        helper.assertTrue(bad.isEmpty(), Component.literal("these kinds name a tag with nothing in it here: " + bad));
        helper.succeed();
    }

    /** Where it RUNS: make_item takes it on a real bundle, and refuses it on anything else. */
    @GameTest(template = "empty_test", batch = "onekindbag", timeoutTicks = 200)
    public static void makeItemBuildsAFoodBagAndRefusesItOffABundle(GameTestHelper helper) {
        JsonObject props = new JsonObject();
        props.addProperty(ItemProperties.HOLDS_ONLY_KEY, "food");
        JsonObject o = new JsonObject();
        o.addProperty("name", "Snack Pack " + helper.getLevel().getGameTime());
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#A06030");
        o.addProperty("shape", "plain");
        o.addProperty("base_item", "minecraft:bundle");
        o.addProperty("count", 1);
        o.add("properties", props);
        var r = new com.moddingfromamod.agent.tools.MakeItemTool().call(o);
        if (com.moddingfromamod.compat.Bundles.SUPPORTED) {
            helper.assertTrue(!r.isError(), Component.literal("make_item refused a food bag on a real bundle: " + r.text()));
            JsonObject off = o.deepCopy();
            off.addProperty("name", "Snack Box " + helper.getLevel().getGameTime());
            off.addProperty("base_item", "minecraft:chest");
            var no = new com.moddingfromamod.agent.tools.MakeItemTool().call(off);
            helper.assertTrue(no.isError() && no.text().contains("bundle"), Component.literal("holds_only on a chest was accepted: " + no.text()));
        } else {
            helper.assertTrue(r.isError() && r.text().contains("1.21.1"),
                    Component.literal("1.21.1 has no bundle, and make_item did not say so: " + r.text()));
        }
        helper.succeed();
    }
}
