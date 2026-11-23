package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.ItemProperties;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a bag that tidies, on the real game.</b> His real inventory: like things merge,
 * the groups come out in order (blocks, food, tools, other) and his hotbar never moves; junk is dropped
 * at his feet and a diamond stays; and a bag that drops junk by itself empties it the moment he is
 * full. Red on {@code main}: nothing sorts, nothing drops.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class TidyGameTest {

    private static ServerPlayer him(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        return p;
    }

    private static int first(Inventory inv, Item item) {
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    @GameTest(template = "empty_test", batch = "tidy", timeoutTicks = 100)
    public static void likeThingsMergeAndGroupsComeOutInOrder(GameTestHelper helper) {
        ServerPlayer p = him(helper);
        Inventory inv = p.getInventory();
        inv.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        inv.setItem(20, new ItemStack(Items.DIAMOND, 3));
        inv.setItem(12, new ItemStack(Items.DIRT, 10));
        inv.setItem(30, new ItemStack(Items.BREAD, 5));
        inv.setItem(9, new ItemStack(Items.IRON_PICKAXE));
        inv.setItem(15, new ItemStack(Items.DIRT, 20));
        Tidy.sort(p);
        int dirt = first(inv, Items.DIRT);
        helper.assertTrue(dirt >= 0 && inv.getItem(dirt).getCount() == 30, Component.literal("the two dirt piles did not merge"));
        int bread = first(inv, Items.BREAD), pick = first(inv, Items.IRON_PICKAXE), gem = first(inv, Items.DIAMOND);
        helper.assertTrue(dirt < bread && bread < pick && pick < gem,
                Component.literal("groups out of order: dirt " + dirt + ", bread " + bread + ", pickaxe " + pick + ", diamond " + gem));
        helper.assertTrue(inv.getItem(0).is(Items.DIAMOND_SWORD), Component.literal("his hotbar moved"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "tidy", timeoutTicks = 100)
    public static void junkIsDroppedAndTheDiamondStays(GameTestHelper helper) {
        ServerPlayer p = him(helper);
        Inventory inv = p.getInventory();
        inv.setItem(10, new ItemStack(Items.COBBLESTONE, 64));
        inv.setItem(11, new ItemStack(Items.ROTTEN_FLESH, 7));
        inv.setItem(12, new ItemStack(Items.DIAMOND, 2));
        int n = Tidy.dropJunk(p);
        helper.assertTrue(n == 2 && first(inv, Items.COBBLESTONE) < 0 && first(inv, Items.ROTTEN_FLESH) < 0,
                Component.literal("junk was not dropped (" + n + " stacks)"));
        helper.assertTrue(first(inv, Items.DIAMOND) >= 0, Component.literal("the diamond was dropped as junk"));
        long onGround = helper.getLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(4),
                e -> e.getItem().is(Items.COBBLESTONE) || e.getItem().is(Items.ROTTEN_FLESH)).size();
        helper.assertTrue(onGround == 2, Component.literal("the junk is not on the ground at his feet: " + onGround));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "tidy", timeoutTicks = 20_000)
    public static void aFullBagThrowsOutItsJunkByItself(GameTestHelper helper) {
        ServerPlayer p = him(helper);
        Inventory inv = p.getInventory();
        ItemStack bag = new ItemStack(Items.LEATHER);
        CompoundTag t = new CompoundTag();
        t.putBoolean(ItemProperties.DROPS_JUNK_DATA, true);
        bag.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        inv.setItem(0, bag);
        for (int i = 9; i < 36; i++) {
            inv.setItem(i, new ItemStack(i % 3 == 0 ? Items.GRAVEL : Items.DIAMOND, 1));
        }
        // The server's own tick does it (every 20 ticks) — poll with a fresh lambda each time.
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (first(inv, Items.GRAVEL) >= 0) {
                helper.runAfterDelay(5, () -> poll[0].run());
                return;
            }
            helper.assertTrue(first(inv, Items.DIAMOND) >= 0, Component.literal("diamonds were thrown out too"));
            helper.succeed();
        };
        helper.runAfterDelay(1, () -> poll[0].run());
    }

    /** Where it RUNS: make_item takes both effects on a thing he clicks. */
    @GameTest(template = "empty_test", batch = "tidy", timeoutTicks = 200)
    public static void makeItemTakesSortAndJunk(GameTestHelper helper) {
        for (String effect : new String[]{"sort_inventory", "drop_junk"}) {
            JsonObject o = new JsonObject();
            o.addProperty("name", "Tidy " + effect + " " + helper.getLevel().getGameTime());
            o.addProperty("icon", "wand");
            o.addProperty("colour", "#806040");
            o.addProperty("shape", "held");
            o.addProperty("effect", effect);
            o.addProperty("count", 1);
            var r = new com.moddingfromamod.agent.tools.MakeItemTool().call(o);
            helper.assertTrue(!r.isError(), Component.literal("make_item refused " + effect + ": " + r.text()));
        }
        helper.succeed();
    }

    /** A bag carrying auto_sort keeps his inventory sorted by itself — never his hotbar. */
    @GameTest(template = "empty_test", batch = "tidy", timeoutTicks = 20_000)
    public static void anAutoSortBagSortsByItself(GameTestHelper helper) {
        ServerPlayer p = him(helper);
        Inventory inv = p.getInventory();
        ItemStack bag = new ItemStack(Items.LEATHER);
        CompoundTag t = new CompoundTag();
        t.putBoolean(ItemProperties.AUTO_SORT_DATA, true);
        bag.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        inv.setItem(0, bag);
        inv.setItem(30, new ItemStack(Items.DIRT, 10));
        inv.setItem(12, new ItemStack(Items.DIRT, 5));
        inv.setItem(9, new ItemStack(Items.BREAD, 2));
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            int dirt = first(inv, Items.DIRT);
            if (dirt < 0 || inv.getItem(dirt).getCount() != 15) {
                helper.runAfterDelay(5, () -> poll[0].run());
                return;
            }
            helper.assertTrue(dirt < first(inv, Items.BREAD), Component.literal("the blocks did not come first"));
            helper.assertTrue(inv.getItem(0).is(Items.LEATHER), Component.literal("his hotbar moved"));
            helper.succeed();
        };
        helper.runAfterDelay(1, () -> poll[0].run());
    }
}
