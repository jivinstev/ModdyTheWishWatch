package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.ItemProperties;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>: a rod that catches something special, on the real game.</b> The real
 * {@code ItemFishedEvent}, posted with a real hook on his line: at chance 100 the fish is cancelled
 * and a diamond flies from the hook; a random list lands on more than one kind; a treasure chest is a
 * chest with its loot table; an enchanted book is really enchanted; a plain rod is left alone. Red on
 * {@code main}: the property does not exist, so the fish always comes up.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CustomCatchGameTest {

    private static ItemStack rod(String what, int chance) throws Exception {
        JsonObject c = new JsonObject();
        c.addProperty("what", what);
        c.addProperty("chance", chance);
        JsonObject props = new JsonObject();
        props.add(ItemProperties.CATCHES_KEY, c);
        String entries = ItemProperties.dataEntries(props);
        ItemStack s = new ItemStack(Items.FISHING_ROD);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(TagParser.parseCompoundFully("{" + entries.substring(1) + "}")));
        return s;
    }

    /** Reel in one bite: post the real event with a real hook, and return what flew at him. */
    private static List<ItemEntity> reel(GameTestHelper helper, ServerPlayer p, boolean[] cancelled) {
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(12)).forEach(e -> e.discard());
        FishingHook hook = new FishingHook(p, helper.getLevel(), 0, 0);
        hook.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        ItemFishedEvent ev = new ItemFishedEvent(List.of(new ItemStack(Items.COD)), 1, hook);
        NeoForge.EVENT_BUS.post(ev);
        cancelled[0] = ev.isCanceled();
        hook.discard();
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(12));
    }

    private static ServerPlayer him(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
        return p;
    }

    @GameTest(template = "empty_test", batch = "customcatch", timeoutTicks = 100)
    public static void theRodCatchesADiamondInsteadOfTheFish(GameTestHelper helper) throws Exception {
        ServerPlayer p = him(helper);
        boolean[] cancelled = new boolean[1];
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
        List<ItemEntity> plain = reel(helper, p, cancelled);
        helper.assertTrue(!cancelled[0] && plain.isEmpty(), Component.literal("a plain rod's fish was touched"));

        p.setItemInHand(InteractionHand.MAIN_HAND, rod("minecraft:diamond", 100));
        List<ItemEntity> got = reel(helper, p, cancelled);
        helper.assertTrue(cancelled[0], Component.literal("the fish was not cancelled"));
        helper.assertTrue(got.size() == 1 && got.get(0).getItem().is(Items.DIAMOND),
                Component.literal("no diamond flew from the hook: " + got.stream().map(e -> e.getItem().toString()).toList()));
        helper.assertTrue(got.get(0).getDeltaMovement().x < 0, Component.literal("the diamond did not fly TOWARD him: "
                + got.get(0).getDeltaMovement()));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "customcatch", timeoutTicks = 100)
    public static void randomTreasureAndBooksAreReal(GameTestHelper helper) throws Exception {
        ServerPlayer p = him(helper);
        boolean[] cancelled = new boolean[1];
        p.setItemInHand(InteractionHand.MAIN_HAND, rod("random: minecraft:diamond, minecraft:emerald, minecraft:gold_ingot", 100));
        Set<String> kinds = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            for (ItemEntity e : reel(helper, p, cancelled)) {
                kinds.add(e.getItem().getItem().toString());
            }
        }
        helper.assertTrue(kinds.size() >= 2, Component.literal("a random list always caught the same thing: " + kinds));

        p.setItemInHand(InteractionHand.MAIN_HAND, rod("treasure_chest", 100));
        List<ItemEntity> chest = reel(helper, p, cancelled);
        helper.assertTrue(chest.size() == 1 && chest.get(0).getItem().is(Items.CHEST)
                        && chest.get(0).getItem().has(DataComponents.CONTAINER_LOOT),
                Component.literal("the treasure chest is not a chest with loot: " + chest));

        p.setItemInHand(InteractionHand.MAIN_HAND, rod("enchanted_book", 100));
        List<ItemEntity> book = reel(helper, p, cancelled);
        helper.assertTrue(book.size() == 1 && book.get(0).getItem().is(Items.ENCHANTED_BOOK)
                        && !book.get(0).getItem().getOrDefault(DataComponents.STORED_ENCHANTMENTS,
                                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).isEmpty(),
                Component.literal("the book is not really enchanted: " + book));
        helper.succeed();
    }

    /** Where it RUNS: make_item takes it on a real rod, refuses a made-up id and a rod-less base. */
    @GameTest(template = "empty_test", batch = "customcatch", timeoutTicks = 100)
    public static void makeItemTakesACatchOnARealRod(GameTestHelper helper) {
        JsonObject c = new JsonObject();
        c.addProperty("what", "random: minecraft:diamond, treasure_chest");
        c.addProperty("chance", 25);
        JsonObject props = new JsonObject();
        props.add(ItemProperties.CATCHES_KEY, c);
        JsonObject o = new JsonObject();
        o.addProperty("name", "Lucky Rod " + helper.getLevel().getGameTime());
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#2080C0");
        o.addProperty("shape", "plain");
        o.addProperty("base_item", "minecraft:fishing_rod");
        o.addProperty("count", 1);
        o.add("properties", props);
        var r = new com.moddingfromamod.agent.tools.MakeItemTool().call(o);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused a lucky rod: " + r.text()));
        JsonObject fake = o.deepCopy();
        fake.addProperty("name", "Fake Rod " + helper.getLevel().getGameTime());
        fake.getAsJsonObject("properties").getAsJsonObject(ItemProperties.CATCHES_KEY)
                .addProperty("what", "minecraft:unicorn_horn");
        var no = new com.moddingfromamod.agent.tools.MakeItemTool().call(fake);
        helper.assertTrue(no.isError() && no.text().contains("unicorn_horn"), Component.literal("a made-up id was accepted: " + no.text()));
        helper.succeed();
    }
}
