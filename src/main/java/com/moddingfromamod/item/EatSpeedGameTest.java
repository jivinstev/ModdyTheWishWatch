package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.ItemProperties;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b> part 1: eaten or drunk faster, on the real game.</b> He starts eating a real
 * loaf, and a real stew, and drinking a real potion: with {@code eat_speed} 4 the use is a quarter
 * of the same item's without. Red on {@code main}: the property does not exist, so the times match.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class EatSpeedGameTest {

    private static ItemStack faster(Item base, int n) throws Exception {
        JsonObject props = new JsonObject();
        props.addProperty(ItemProperties.EAT_SPEED_KEY, n);
        String entries = ItemProperties.dataEntries(props);
        ItemStack s = new ItemStack(base);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(TagParser.parseCompoundFully("{" + entries.substring(1) + "}")));
        return s;
    }

    /** How many ticks the use he just started will take. */
    private static int useTicks(ServerPlayer p, ItemStack stack) {
        p.stopUsingItem();
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        p.startUsingItem(InteractionHand.MAIN_HAND);
        int t = p.getUseItemRemainingTicks();
        p.stopUsingItem();
        return t;
    }

    @GameTest(template = "empty_test", batch = "eatspeed", timeoutTicks = 100)
    public static void foodAndDrinkGoFourTimesFaster(GameTestHelper helper) throws Exception {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        p.getFoodData().setFoodLevel(2);   // hungry, so a loaf can be started at all
        for (Item base : new Item[]{Items.BREAD, Items.MUSHROOM_STEW, Items.POTION}) {
            int plain = useTicks(p, new ItemStack(base));
            int quick = useTicks(p, faster(base, 4));
            helper.assertTrue(plain > 0 && quick == Math.max(1, plain / 4),
                    Component.literal(base + " takes " + plain + " ticks, and with eat_speed 4 took " + quick + ", not " + plain / 4));
        }
        helper.succeed();
    }

    /** Where it RUNS: make_item takes it on a real food, and refuses it on a thing nobody eats. */
    @GameTest(template = "empty_test", batch = "eatspeed", timeoutTicks = 100)
    public static void makeItemTakesItOnFoodAndRefusesItOnASword(GameTestHelper helper) {
        JsonObject props = new JsonObject();
        props.addProperty(ItemProperties.EAT_SPEED_KEY, 4);
        JsonObject o = new JsonObject();
        o.addProperty("name", "Speedy Soup " + helper.getLevel().getGameTime());
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#C08040");
        o.addProperty("shape", "plain");
        o.addProperty("base_item", "minecraft:mushroom_stew");
        o.addProperty("count", 1);
        o.add("properties", props);
        var r = new com.moddingfromamod.agent.tools.MakeItemTool().call(o);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused eat_speed on a real stew: " + r.text()));
        JsonObject off = o.deepCopy();
        off.addProperty("name", "Speedy Sword " + helper.getLevel().getGameTime());
        off.addProperty("base_item", "minecraft:iron_sword");
        var no = new com.moddingfromamod.agent.tools.MakeItemTool().call(off);
        helper.assertTrue(no.isError() && no.text().contains("eat_speed"), Component.literal("eat_speed on a sword was accepted: " + no.text()));
        helper.succeed();
    }
}
