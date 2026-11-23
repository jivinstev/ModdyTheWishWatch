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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: "a sword that gets stronger at night", on the real game.</b> The same sword,
 * held at noon and at midnight, and in a thunderstorm for a storm sword; a pickaxe above and below
 * y=0. Each asserts the value of his real attribute, which is what the hit or the dig reads — and
 * that letting go of the sword takes the strength away. Red on {@code main}: the property does not
 * exist, so nothing ever changes.
 *
 * <p>Its own batch, because it sets the world's time and weather, which every test shares.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class StrongerWhenGameTest {

    private static ItemStack made(net.minecraft.world.item.Item base, String when, String stat, int amount)
            throws Exception {
        JsonObject sw = new JsonObject();
        sw.addProperty("when", when);
        sw.addProperty("stat", stat);
        sw.addProperty("amount", amount);
        JsonObject props = new JsonObject();
        props.add(ItemProperties.STRONGER_WHEN_KEY, sw);
        String entries = ItemProperties.dataEntries(props);   // ",WishStrongerWhen:{…}"
        ItemStack s = new ItemStack(base);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(TagParser.parseCompoundFully("{" + entries.substring(1) + "}")));
        return s;
    }

    private static double attack(ServerPlayer p) {
        return p.getAttributeValue(Attributes.ATTACK_DAMAGE);
    }

    private static double dig(ServerPlayer p) {
        return p.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
    }

    @GameTest(template = "empty_test", batch = "strongerwhen", timeoutTicks = 200)
    public static void theNightSwordIsStrongerAtNightAndOnlyWhileHeld(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        long was = level.getDayTime();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        try {
            p.setItemInHand(InteractionHand.MAIN_HAND, made(Items.DIAMOND_SWORD, "night", "attack_damage", 6));
            level.setDayTime(6000);   // noon
            StrongerWhen.apply(p);
            double noon = attack(p);
            level.setDayTime(18000);  // midnight
            StrongerWhen.apply(p);
            double midnight = attack(p);
            helper.assertTrue(midnight == noon + 6,
                    Component.literal("the night sword hits " + noon + " at noon and " + midnight + " at midnight, not +6"));
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            StrongerWhen.apply(p);
            helper.assertTrue(attack(p) == noon, Component.literal("letting go of it must take the strength away: " + attack(p)));
            level.setDayTime(6000);
            p.setItemInHand(InteractionHand.MAIN_HAND, made(Items.DIAMOND_SWORD, "night", "attack_damage", 6));
            StrongerWhen.apply(p);
            helper.assertTrue(attack(p) == noon, Component.literal("morning must take it away again: " + attack(p)));
        } finally {
            level.setDayTime(was);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "strongerwhen", timeoutTicks = 200)
    public static void theStormSwordIsStrongerOnlyInAThunderstorm(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        try {
            p.setItemInHand(InteractionHand.MAIN_HAND, made(Items.DIAMOND_SWORD, "thunder", "attack_damage", 10));
            level.setWeatherParameters(6000, 0, false, false);
            StrongerWhen.apply(p);
            double clear = attack(p);
            level.setWeatherParameters(0, 6000, true, true);
            StrongerWhen.apply(p);
            helper.assertTrue(attack(p) == clear + 10,
                    Component.literal("the storm sword hits " + clear + " in clear weather and " + attack(p) + " in a storm"));
        } finally {
            level.setWeatherParameters(6000, 0, false, false);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "strongerwhen", timeoutTicks = 200)
    public static void theDeepPickaxeDigsFasterBelowTheLine(GameTestHelper helper) throws Exception {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setItemInHand(InteractionHand.MAIN_HAND, made(Items.DIAMOND_PICKAXE, "below_y", "mining_speed", 4));
        p.snapTo(p.getX(), 40, p.getZ());
        StrongerWhen.apply(p);
        double up = dig(p);
        p.snapTo(p.getX(), -30, p.getZ());
        StrongerWhen.apply(p);
        helper.assertTrue(Math.abs(dig(p) - up * 5) < 1e-6,
                Component.literal("the deep pickaxe digs at " + up + " up high and " + dig(p) + " below y=0, not 5x"));
        helper.succeed();
    }

    /** The wiring: the player's own tick puts it on, with nobody calling apply. */
    @GameTest(template = "empty_test", batch = "strongerwhen", timeoutTicks = 400)
    public static void hisOwnTickPutsItOn(GameTestHelper helper) throws Exception {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setItemInHand(InteractionHand.MAIN_HAND, made(Items.DIAMOND_PICKAXE, "below_y", "mining_speed", 4));
        p.snapTo(p.getX(), -30, p.getZ());
        double before = dig(p);
        helper.runAfterDelay(3 * StrongerWhen.PERIOD, () -> {
            helper.assertTrue(dig(p) > before,
                    Component.literal("no tick put the modifier on (dig speed " + before + " → " + dig(p) + ")"));
            helper.succeed();
        });
    }

    /**
     * Where it RUNS: the real make_item takes the property on a real sword, and the give line it
     * renders hands out a sword that is stronger at night.
     */
    @GameTest(template = "empty_test", batch = "strongerwhen", timeoutTicks = 200)
    public static void makeItemHandsOutANightSword(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long was = level.getDayTime();
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        String name = "Moon Blade " + level.getGameTime();
        JsonObject sw = new JsonObject();
        sw.addProperty("when", "night");
        sw.addProperty("stat", "attack_damage");
        sw.addProperty("amount", 6);
        JsonObject props = new JsonObject();
        props.add(ItemProperties.STRONGER_WHEN_KEY, sw);
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "sword");
        o.addProperty("colour", "#2040A0");
        o.addProperty("shape", "plain");
        o.addProperty("base_item", "minecraft:diamond_sword");
        o.addProperty("count", 1);
        o.add("properties", props);
        var tool = new com.moddingfromamod.agent.tools.MakeItemTool();
        var r = tool.call(o);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused stronger_when on a real sword: " + r.text()));
        String line = com.moddingfromamod.agent.tools.MakeItemTool.gives("plain", tool.lastMadeId(),
                com.moddingfromamod.datapack.ItemCatalogue.icon("sword"), 1, "A hint.",
                java.util.List.of(new com.moddingfromamod.agent.tools.MakeItemTool.Variant(name, 0x2040A0, props)),
                com.moddingfromamod.datapack.ItemRecipient.ME, null, "minecraft:diamond_sword");
        p.getInventory().clearContent();
        level.getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(), line.strip().substring(1));
        ItemStack got = ItemStack.EMPTY;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                got = s.copy();
            }
        }
        helper.assertTrue(got.is(Items.DIAMOND_SWORD), Component.literal("no real diamond sword arrived from: " + line));
        try {
            p.getInventory().clearContent();
            p.setItemInHand(InteractionHand.MAIN_HAND, got);
            level.setDayTime(6000);
            StrongerWhen.apply(p);
            double noon = attack(p);
            level.setDayTime(18000);
            StrongerWhen.apply(p);
            helper.assertTrue(attack(p) == noon + 6,
                    Component.literal("make_item's night sword hits " + noon + " at noon and " + attack(p) + " at midnight"));
        } finally {
            level.setDayTime(was);
        }
        helper.succeed();
    }
}
