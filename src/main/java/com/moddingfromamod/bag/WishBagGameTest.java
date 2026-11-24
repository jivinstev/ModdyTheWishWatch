package com.moddingfromamod.bag;

import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.WishItem;
import com.moddingfromamod.item.delivery.WishDelivery;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.VanillaWatchItem;
import com.moddingfromamod.wish.WatchPin;
import com.moddingfromamod.wish.WatchPinService;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The Wish Bag </b> — every row of its spec, where it RUNS: the real
 * chest a stock client is sent, clicked the way a click packet lands; the real pin; a real death
 * and respawn; the real shelf file.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishBagGameTest {

    /** A pinned child: Moddy in slot 1, the bag in slot 2, an empty bag. */
    static ServerPlayer child(GameTestHelper helper, String name, boolean survival) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        if (survival) {
            MockPlayerNetwork.asSurvival(p);
        }
        WatchPinService.pinBagMockForTest(p);
        p.getInventory().clearContent();
        p.getInventory().setItem(WatchPin.HOME, VanillaWatchItem.stack());
        p.getInventory().setItem(WatchPin.BAG_HOME, WishBagItem.stack());
        WishBagService.clearForTest(serverOf(p), p.getUUID());
        return p;
    }

    static void done(ServerPlayer p) {
        if (p.containerMenu instanceof WishBagMenu) {
            p.closeContainer();
        }
        WatchPinService.unpinMockForTest(p);
        MockPlayerNetwork.remove(p);
    }

    /** A thing he wished for: a made item's mark in custom_data. */
    static ItemStack wished(String name) {
        ItemStack s = new ItemStack(Items.LEATHER_HORSE_ARMOR);
        CompoundTag tag = new CompoundTag();
        tag.putString(WishItem.WISH_FEATURE, name.toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
        tag.putBoolean(WishItem.CARRIER, true);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    /** The server a player's shelf lives on: the level he is in belongs to it. */
    static net.minecraft.server.MinecraftServer serverOf(ServerPlayer p) {
        return ((ServerLevel) p.level()).getServer();
    }

    static List<ItemStack> bag(ServerPlayer p) {
        return WishBagService.contents(serverOf(p), p.getUUID());
    }

    // ── what goes in ────────────────────────────────────────────────────────────────────────

    private static void refuseNormalAcceptWished(GameTestHelper helper, boolean survival) {
        ServerPlayer p = child(helper, survival ? "mfam-bag-in-s" : "mfam-bag-in-c", survival);
        try {
            helper.assertTrue(WishBagMenu.open(p) && p.containerMenu instanceof WishBagMenu,
                    Component.literal("the bag did not open"));
            var menu = p.containerMenu;
            // A normal block on the cursor, clicked onto an empty bag cell.
            menu.setCarried(new ItemStack(Items.DIRT, 5));
            menu.clicked(3, 0, ContainerInput.PICKUP, p);
            helper.assertTrue(bag(p).isEmpty(), Component.literal("a regular block went into the Wish Bag: " + bag(p)));
            helper.assertTrue(menu.getCarried().is(Items.DIRT) && menu.getCarried().getCount() == 5,
                    Component.literal("the refused dirt is not back on his cursor: " + menu.getCarried()));
            String refusal = "✨ " + String.format(WishBagMenu.REFUSAL, "block");
            helper.assertTrue(MockPlayerNetwork.chat(p).contains(refusal),
                    Component.literal("he was not told why: " + MockPlayerNetwork.chat(p)));
            menu.setCarried(ItemStack.EMPTY);
            // A wished thing goes in, and to the front.
            menu.setCarried(wished("Hot Coffee"));
            menu.clicked(7, 0, ContainerInput.PICKUP, p);
            helper.assertValueEqual(bag(p).size(), 1, Component.literal("things in the bag after putting a wished one in"));
            helper.assertTrue(bag(p).get(0).getHoverName().getString().equals("Hot Coffee"),
                    Component.literal("the bag holds " + bag(p)));
            helper.assertTrue(menu.getCarried().isEmpty(), Component.literal("still carrying " + menu.getCarried()));
            // A normal item SHIFT-CLICKED from his own inventory is refused too.
            p.getInventory().setItem(15, new ItemStack(Items.APPLE, 3));
            int appleSlot = -1;
            for (int i = 0; i < menu.slots.size(); i++) {
                if (menu.slots.get(i).container == p.getInventory() && menu.slots.get(i).getContainerSlot() == 15) {
                    appleSlot = i;
                }
            }
            menu.clicked(appleSlot, 0, ContainerInput.QUICK_MOVE, p);
            helper.assertValueEqual(bag(p).size(), 1, Component.literal("an apple was shift-clicked into the bag"));
            helper.assertValueEqual(p.getInventory().getItem(15).getCount(), 3, Component.literal("apples left in his inventory"));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    @GameTest(template = "empty_test", batch = "wishbag")
    public static void aNormalItemIsRefusedAndAWishedOneAcceptedInCreative(GameTestHelper helper) {
        refuseNormalAcceptWished(helper, false);
    }

    @GameTest(template = "empty_test", batch = "wishbag")
    public static void aNormalItemIsRefusedAndAWishedOneAcceptedInSurvival(GameTestHelper helper) {
        refuseNormalAcceptWished(helper, true);
    }

    // ── taking out ──────────────────────────────────────────────────────────────────────────

    private static void takingOutWorks(GameTestHelper helper, boolean survival) {
        ServerPlayer p = child(helper, survival ? "mfam-bag-out-s" : "mfam-bag-out-c", survival);
        try {
            // A PLAIN gift (32 melon slices) and a wished thing, both handed over into the bag.
            WishBagService.add(serverOf(p), p.getUUID(), new ItemStack(Items.MELON_SLICE, 32));
            WishBagService.add(serverOf(p), p.getUUID(), wished("Tracker"));
            helper.assertTrue(bag(p).get(0).getHoverName().getString().equals("Tracker"),
                    Component.literal("newest first: the bag's first cell is " + bag(p).get(0)));
            WishBagMenu.open(p);
            var menu = p.containerMenu;
            helper.assertTrue(menu.getSlot(0).getItem().getHoverName().getString().equals("Tracker"),
                    Component.literal("page 1's first cell shows " + menu.getSlot(0).getItem()));
            // Take the melon slices out (cell 2), onto the cursor.
            menu.clicked(1, 0, ContainerInput.PICKUP, p);
            helper.assertTrue(menu.getCarried().is(Items.MELON_SLICE) && menu.getCarried().getCount() == 32,
                    Component.literal("took out " + menu.getCarried()));
            helper.assertValueEqual(bag(p).size(), 1, Component.literal("entries left in the bag"));
            // ...and put them in his inventory.
            p.getInventory().setItem(20, menu.getCarried().copy());
            menu.setCarried(ItemStack.EMPTY);
            // A plain gift that came OUT cannot go back IN (design choice (b), WishedItems).
            menu.setCarried(new ItemStack(Items.MELON_SLICE, 32));
            menu.clicked(5, 0, ContainerInput.PICKUP, p);
            helper.assertValueEqual(bag(p).size(), 1, Component.literal("a plain melon slice went back in"));
            menu.setCarried(ItemStack.EMPTY);
            // Shift-click the Tracker out into his inventory.
            menu.clicked(0, 0, ContainerInput.QUICK_MOVE, p);
            helper.assertTrue(bag(p).isEmpty(), Component.literal("the bag still holds " + bag(p)));
            helper.assertTrue(p.getInventory().hasAnyMatching(s -> s.getHoverName().getString().equals("Tracker")),
                    Component.literal("the Tracker is not in his inventory"));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    @GameTest(template = "empty_test", batch = "wishbag")
    public static void takingOutWorksInCreative(GameTestHelper helper) {
        takingOutWorks(helper, false);
    }

    @GameTest(template = "empty_test", batch = "wishbag")
    public static void takingOutWorksInSurvival(GameTestHelper helper) {
        takingOutWorks(helper, true);
    }

    /** Overflow lands newest first, across pages: the 46th thing pushes the first onto page 2. */
    @GameTest(template = "empty_test", batch = "wishbag")
    public static void overflowLandsNewestFirstAndPages(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-bag-pages", false);
        try {
            for (int i = 2; i < 9; i++) {
                p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            for (int i = 0; i < 46; i++) {
                WishDelivery.give(p, wished("Gem " + i), "",
                        com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, false);
            }
            helper.assertValueEqual(bag(p).size(), 46, Component.literal("things in the bag"));
            helper.assertTrue(bag(p).get(0).getHoverName().getString().equals("Gem 45"),
                    Component.literal("the newest is not first: " + bag(p).get(0).getHoverName().getString()));
            WishBagMenu.open(p);
            WishBagMenu menu = (WishBagMenu) p.containerMenu;
            menu.clicked(WishBagPages.OLDER_SLOT, 0, ContainerInput.PICKUP, p);
            helper.assertValueEqual(menu.page(), 1, Component.literal("page after pressing 'older wishes'"));
            helper.assertTrue(menu.getSlot(0).getItem().getHoverName().getString().equals("Gem 0"),
                    Component.literal("page 2 starts with " + menu.getSlot(0).getItem().getHoverName().getString()));
            helper.assertTrue(menu.getCarried().isEmpty(), Component.literal("pressing a page button picked something up"));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    // ── pinning ─────────────────────────────────────────────────────────────────────────────

    static int bags(ServerPlayer p) {
        int n = WishBagItem.isBag(p.containerMenu.getCarried()) ? 1 : 0;
        for (int i = 0; i < WatchPin.SLOTS; i++) {
            if (WishBagItem.isBag(p.getInventory().getItem(i))) {
                n++;
            }
        }
        return n;
    }

    /** A creative-screen move puts the bag in slot 6; within a tick it is back in slot 2. */
    @GameTest(template = "empty_test", batch = "wishbag", timeoutTicks = 40)
    public static void theBagIsRePinnedAfterACreativeScreenMove(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-bag-pin", false);
        p.getInventory().setItem(5, p.getInventory().getItem(WatchPin.BAG_HOME).copy());
        p.getInventory().setItem(WatchPin.BAG_HOME, new ItemStack(Items.BREAD, 2));
        helper.succeedWhen(() -> {
            helper.assertTrue(WishBagItem.isBag(p.getInventory().getItem(WatchPin.BAG_HOME)) && bags(p) == 1,
                    Component.literal("the bag is not back in slot 2 alone"));
            helper.assertTrue(p.getInventory().getItem(5).is(Items.BREAD),
                    Component.literal("the bread did not swap into the slot the bag left: " + p.getInventory().getItem(5)));
            done(p);
        });
    }

    /** A child who has no bag gets one; what was in slot 2 moves to the first free slot. */
    @GameTest(template = "empty_test", batch = "wishbag", timeoutTicks = 40)
    public static void aNewBagMovesSlotTwosItemToTheFirstFreeSlot(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-bag-new", false);
        p.getInventory().setItem(WatchPin.BAG_HOME, new ItemStack(Items.APPLE, 4));
        p.getInventory().setItem(2, new ItemStack(Items.STICK, 1));
        helper.succeedWhen(() -> {
            helper.assertTrue(WishBagItem.isBag(p.getInventory().getItem(WatchPin.BAG_HOME)), Component.literal("no bag in slot 2"));
            helper.assertTrue(p.getInventory().getItem(3).is(Items.APPLE),
                    Component.literal("the apples are not in the first free slot (slot 4): " + p.getInventory().getItem(3)));
            done(p);
        });
    }

    /** A full inventory: slot 2's item lands at his feet, never destroyed, and he is told. */
    @GameTest(template = "empty_test", batch = "wishbag", timeoutTicks = 40)
    public static void aFullInventoryDropsSlotTwosItemAndTellsHim(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-bag-full", true);
        for (int i = 2; i < WatchPin.MAIN_END; i++) {
            p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        p.getInventory().setItem(WatchPin.BAG_HOME, new ItemStack(Items.GOLDEN_APPLE, 1));
        var where = p.blockPosition();
        helper.succeedWhen(() -> {
            helper.assertTrue(WishBagItem.isBag(p.getInventory().getItem(WatchPin.BAG_HOME)), Component.literal("no bag in slot 2"));
            helper.assertFalse(helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(4),
                    e -> e.getItem().is(Items.GOLDEN_APPLE)).isEmpty(), Component.literal("the golden apple is not at his feet"));
            helper.assertTrue(MockPlayerNetwork.chat(p).stream().anyMatch(l -> l.contains("Golden Apple")
                    && l.contains("at your feet")), Component.literal("he was not told: " + MockPlayerNetwork.chat(p)));
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(4))
                    .forEach(Entity::discard);
            done(p);
        });
    }

    /** It survives death: no bag dropped, a bag in slot 2 after respawn, and every thing still in it. */
    @GameTest(template = "empty_test", batch = "wishbag", timeoutTicks = 40)
    public static void theBagSurvivesDeath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer p = child(helper, "mfam-bag-death", true);
        WishBagService.add(serverOf(p), p.getUUID(), wished("Lucky Charm"));
        var where = p.blockPosition();
        p.die(p.damageSources().generic());
        int looseBags = level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(8),
                e -> WishBagItem.isBag(e.getItem())).size();
        ServerPlayer reborn = serverOf(p).getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
        helper.succeedWhen(() -> {
            helper.assertValueEqual(looseBags, 0, Component.literal("Wish Bags dropped at death"));
            helper.assertTrue(WishBagItem.isBag(reborn.getInventory().getItem(WatchPin.BAG_HOME)),
                    Component.literal("no bag in slot 2 after respawn"));
            helper.assertTrue(bag(reborn).stream().anyMatch(s -> s.getHoverName().getString().equals("Lucky Charm")),
                    Component.literal("the Lucky Charm is gone from his bag: " + bag(reborn)));
            level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(8)).forEach(Entity::discard);
            done(reborn);
        });
    }

    // ── one bag per world ───────────────────────────────────────────────────────────────────

    /**
     * One world's bag and another's are two files on his shelf; neither world sees the other's.
     * Its own batch: it switches the world key for every bag on the server.
     */
    @GameTest(template = "empty_test", batch = "wishbag_worlds")
    public static void eachWorldHasItsOwnBag(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-bag-worlds", false);
        var server = serverOf(p);
        try {
            WishBagService.forceWorldForTest(server, "world-a-gt");
            WishBagService.clearForTest(server, p.getUUID());
            WishBagService.add(server, p.getUUID(), wished("First Wand"));
            WishBagService.forceWorldForTest(server, "world-b-gt");
            WishBagService.clearForTest(server, p.getUUID());
            helper.assertTrue(bag(p).isEmpty(), Component.literal("the second world's bag holds the first world's things: " + bag(p)));
            WishBagService.add(server, p.getUUID(), wished("Second Shell"));
            WishBagService.forceWorldForTest(server, "world-a-gt");
            List<ItemStack> first = bag(p);
            helper.assertValueEqual(first.size(), 1, Component.literal("things in the first world's bag"));
            helper.assertTrue(first.get(0).getHoverName().getString().equals("First Wand"),
                    Component.literal("the first world's bag holds " + first));
            // Two files on his shelf, one per world, each in the versioned envelope.
            var dir = com.moddingfromamod.runtime.RuntimeConfig.playersDir().resolve(p.getUUID().toString())
                    .resolve(WishBagStore.DIR);
            for (String w : List.of("world-a-gt", "world-b-gt")) {
                var f = dir.resolve(w + ".json");
                helper.assertTrue(java.nio.file.Files.exists(f), Component.literal("no bag file " + f));
                String text = java.nio.file.Files.readString(f);
                helper.assertTrue(text.contains("\"store\":\"wish-bag\"") && text.contains("\"v\":1"),
                        Component.literal("not the versioned envelope: " + text));
            }
            helper.succeed();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            WishBagService.forceWorldForTest(server, null);
            done(p);
        }
    }

    /** Saved and read back: the bag that comes off disk is the bag that went on, newest first. */
    @GameTest(template = "empty_test", batch = "wishbag_worlds")
    public static void theBagComesBackFromItsFile(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-bag-file", false);
        try {
            WishBagService.add(serverOf(p), p.getUUID(), new ItemStack(Items.MELON_SLICE, 32));
            WishBagService.add(serverOf(p), p.getUUID(), wished("Mango Splash"));
            WishBagService.reloadForTest(serverOf(p));
            List<ItemStack> back = bag(p);
            helper.assertValueEqual(back.size(), 2, Component.literal("entries read back"));
            helper.assertTrue(back.get(0).getHoverName().getString().equals("Mango Splash")
                    && back.get(1).is(Items.MELON_SLICE) && back.get(1).getCount() == 32,
                    Component.literal("read back " + back));
            helper.succeed();
        } finally {
            done(p);
        }
    }
}
