package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.bag.WishBagItem;
import com.moddingfromamod.bag.WishBagService;
import com.moddingfromamod.item.delivery.WishDelivery;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>One delivery path for wished items </b> — where a wish's {@code /give} lands,
 * what Moddy says about it, and what an undo takes back, driven through the REAL paths: a raw
 * {@code give} through {@link WishExecutor#runOne} (the agent's {@code run_command}), and a
 * template/button replay through a real {@link WishExecutor.Job} — both hand-overs. A world rule's
 * give is a thing's own behaviour and lands elsewhere: {@code rules.WorldRulesGameTest} and
 * {@code item.delivery.WishGiveKindGameTest}. The made item's
 * {@code wishdatapack <id> give} is {@code bag.WishBagDatapackGameTest} (its own batch: it reloads).
 *
 * <p>Each child is a pinned mock — watch in slot 1, the Wish Bag in slot 2 — with the given
 * hotbar slots filled with his own cobblestone, so the hotbar is full or has exactly the free
 * slots the row is about.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishDeliveryGameTest {

    /** A pinned child with Moddy, his bag, and cobblestone in every hotbar slot from 3 up to {@code to}. */
    static ServerPlayer child(GameTestHelper helper, String name, int filledThrough, boolean survival) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        if (survival) {
            MockPlayerNetwork.asSurvival(p);
        }
        WatchPinService.pinBagMockForTest(p);
        p.getInventory().clearContent();
        p.getInventory().setItem(WatchPin.HOME, VanillaWatchItem.stack());
        p.getInventory().setItem(WatchPin.BAG_HOME, WishBagItem.stack());
        for (int i = 2; i < filledThrough; i++) {
            p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        WishBagService.clearForTest(p.level().getServer(), p.getUUID());
        return p;
    }

    static void done(ServerPlayer p) {
        WatchPinService.unpinMockForTest(p);
        MockPlayerNetwork.remove(p);
    }

    static int inBag(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : WishBagService.contents(p.level().getServer(), p.getUUID())) {
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Nothing of {@code item} in the 27 main-inventory slots — the rule's "never". */
    static int inMain(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 9; i < 36; i++) {
            if (p.getInventory().getItem(i).is(item)) {
                n += p.getInventory().getItem(i).getCount();
            }
        }
        return n;
    }

    static int onFloor(GameTestHelper helper, ServerPlayer p, net.minecraft.world.item.Item item) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(p.blockPosition()).inflate(8), e -> e.getItem().is(item) && !e.hasPickUpDelay()
                        || e.getItem().is(item) && e.getItem().getCount() > 1).size();
    }

    static WishExecutor.Result run(ServerPlayer p, String command) {
        return WishExecutor.runOne(p.level().getServer(), p.level(), p.position(), p, command);
    }

    // ── a full hotbar → the Wish Bag ─────────────────────────────────────────────────────────

    private static void rawGiveIntoAFullHotbar(GameTestHelper helper, boolean survival) {
        ServerPlayer p = child(helper, survival ? "mfam-dlv-raw-s" : "mfam-dlv-raw-c", 9, survival);
        try {
            WishExecutor.Result r = run(p, "give @s minecraft:melon_slice 32");
            helper.assertTrue(r.success(), Component.literal("the give failed: " + r.joined()));
            helper.assertValueEqual(inBag(p, Items.MELON_SLICE), 32, Component.literal("melon slices in his Wish Bag"));
            helper.assertValueEqual(inMain(p, Items.MELON_SLICE), 0,
                    Component.literal("melon slices in his MAIN inventory — the one place they must never go"));
            helper.assertValueEqual(onFloor(helper, p, Items.MELON_SLICE), 0, Component.literal("melon slices on the floor"));
            helper.assertTrue(r.joined().contains("32 × Melon Slice → his Wish Bag"),
                    Component.literal("the agent was not told where they went: " + r.joined()));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    @GameTest(template = "empty_test", batch = "wishdelivery")
    public static void aRawGiveIntoAFullHotbarGoesToTheBagInCreative(GameTestHelper helper) {
        rawGiveIntoAFullHotbar(helper, false);
    }

    @GameTest(template = "empty_test", batch = "wishdelivery")
    public static void aRawGiveIntoAFullHotbarGoesToTheBagInSurvival(GameTestHelper helper) {
        rawGiveIntoAFullHotbar(helper, true);
    }

    /**
     * A template or button replay is a {@link WishExecutor.Job}: the same commands, run with no
     * agent, under the served wish's own scope. Into a full hotbar, in both modes.
     */
    private static void replayIntoAFullHotbar(GameTestHelper helper, boolean survival) {
        ServerPlayer p = child(helper, survival ? "mfam-dlv-job-s" : "mfam-dlv-job-c", 9, survival);
        try {
            Wish wish = Wish.create("give me diamonds", WishSource.PRESET, System.currentTimeMillis(),
                    p.getUUID(), p.getScoreboardName(), "minecraft:overworld", p.getX(), p.getY(), p.getZ());
            WishExecutor.Job job = new WishExecutor.Job(p.getUUID(), p.level(), p.position(), wish,
                    CommandPlan.of(List.of("give @s minecraft:diamond 12", "give @s minecraft:emerald 8"),
                            "Here you go!"), null, false);
            job.runAll(p.level().getServer());
            helper.assertValueEqual(inBag(p, Items.DIAMOND), 12, Component.literal("diamonds in his Wish Bag"));
            helper.assertValueEqual(inBag(p, Items.EMERALD), 8, Component.literal("emeralds in his Wish Bag"));
            helper.assertValueEqual(inMain(p, Items.DIAMOND) + inMain(p, Items.EMERALD), 0,
                    Component.literal("a replay's give reached his main inventory"));
            helper.assertTrue(WishDelivery.handedOver(wish.id().toString()),
                    Component.literal("the replay's hand-over was not recorded against its wish"));
            // A replay he asked Moddy for is a HAND-OVER: Moddy says where it landed.
            WishDelivery.flush(p.level().getServer());
            helper.assertTrue(MockPlayerNetwork.chat(p).stream().anyMatch(l -> l.contains("Diamonds")
                            && l.contains("Wish Bag")),
                    Component.literal("Moddy did not say where the replay's diamonds landed: " + MockPlayerNetwork.chat(p)));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    @GameTest(template = "empty_test", batch = "wishdelivery")
    public static void aTemplateReplayIntoAFullHotbarGoesToTheBagInCreative(GameTestHelper helper) {
        replayIntoAFullHotbar(helper, false);
    }

    @GameTest(template = "empty_test", batch = "wishdelivery")
    public static void aTemplateReplayIntoAFullHotbarGoesToTheBagInSurvival(GameTestHelper helper) {
        replayIntoAFullHotbar(helper, true);
    }

    // ── free hotbar slots → the slots, and the exact line ───────────────────────────────────

    /**
     * The owner's own sentence. Slots 8 and 9 are free; four things arrive in one tick. The two
     * drinks take 8 and 9, the hotbar is then full, and the bar and the melon slices go to the bag.
     */
    @GameTest(template = "empty_test", batch = "wishdelivery", timeoutTicks = 100)
    public static void freeSlotsTakeItAndModdySaysExactlyWhere(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-dlv-line", 7, false);
        String expected = "Pineapple Paradise is in slot 8, Mango Splash in slot 9, and the Tropical"
                + " Oasis Bar and 32 Melon Slices are in your Wish Bag!";
        for (String c : List.of(
                "give @s minecraft:potion[minecraft:custom_name='\"Pineapple Paradise\"'] 1",
                "give @s minecraft:honey_bottle[minecraft:custom_name='\"Mango Splash\"'] 1",
                "give @s minecraft:barrel[minecraft:custom_name='\"Tropical Oasis Bar\"'] 1",
                "give @s minecraft:melon_slice 32")) {
            WishExecutor.Result r = run(p, c);
            helper.assertTrue(r.success(), Component.literal(c + " failed: " + r.joined()));
        }
        helper.assertTrue(p.getInventory().getItem(7).getHoverName().getString().equals("Pineapple Paradise"),
                Component.literal("slot 8 holds " + p.getInventory().getItem(7)));
        helper.assertTrue(p.getInventory().getItem(8).getHoverName().getString().equals("Mango Splash"),
                Component.literal("slot 9 holds " + p.getInventory().getItem(8)));
        WishDelivery.flush(p.level().getServer());
        helper.succeedWhen(() -> {
            List<String> chat = MockPlayerNetwork.chat(p);
            helper.assertTrue(chat.contains(expected), Component.literal("Moddy said " + chat + ", not: " + expected));
            helper.assertValueEqual(chat.stream().filter(l -> l.contains("Wish Bag")).count(), 1L,
                    Component.literal("how many hand-over lines (four gives, one tick: ONE line)"));
            done(p);
        });
    }

    /** A second batch of the same thing joins the first stack in the hotbar, not a new slot. */
    @GameTest(template = "empty_test", batch = "wishdelivery")
    public static void aMatchingStackIsToppedUpFirst(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-dlv-merge", 2, false);
        try {
            run(p, "give @s minecraft:melon_slice 10");
            run(p, "give @s minecraft:melon_slice 10");
            helper.assertValueEqual(p.getInventory().getItem(2).getCount(), 20, Component.literal("melon slices in slot 3"));
            helper.assertTrue(p.getInventory().getItem(3).isEmpty(), Component.literal("a second slot was taken"));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    /** A grown-up's own typed /give opens no wish scope and is vanilla: it may fill his main inventory. */
    @GameTest(template = "empty_test", batch = "wishdelivery")
    public static void aGrownUpsOwnGiveIsUntouched(GameTestHelper helper) {
        ServerPlayer p = child(helper, "mfam-dlv-op", 9, false);
        try {
            p.level().getServer().getCommands().performPrefixedCommand(
                    p.createCommandSourceStack().withSuppressedOutput().withPermission(2),
                    "give @s minecraft:melon_slice 5");
            helper.assertValueEqual(inMain(p, Items.MELON_SLICE), 5, Component.literal("vanilla /give, main inventory"));
            helper.assertValueEqual(inBag(p, Items.MELON_SLICE), 0, Component.literal("the Wish Bag"));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    // ── undo ───────────────────────────────────────────────────────────────────────────────

    /**
     * Undo takes back everything the wish handed over: the sword he has since moved into his main
     * inventory, AND the diamonds in his Wish Bag — and nothing of his own.
     */
    private static void undoTakesItBack(GameTestHelper helper, boolean survival) {
        ServerPlayer p = child(helper, survival ? "mfam-dlv-undo-s" : "mfam-dlv-undo-c", 8, survival);
        try {
            UUID wishId = UUID.randomUUID();
            var run = com.moddingfromamod.marshal.WishRun.detachedForTest(wishId, p.getUUID(), "a sword");
            com.moddingfromamod.mob.SpawnScope.during(wishId.toString(), com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, () -> {
                run(p, "give @s minecraft:diamond_sword 1");
                return run(p, "give @s minecraft:diamond 10");
            });
            helper.assertTrue(p.getInventory().getItem(8).is(Items.DIAMOND_SWORD), Component.literal("the sword took slot 9"));
            helper.assertValueEqual(inBag(p, Items.DIAMOND), 10, Component.literal("diamonds in the bag before undo"));
            // He moves the sword into his main inventory himself.
            p.getInventory().setItem(20, p.getInventory().getItem(8).copy());
            p.getInventory().setItem(8, ItemStack.EMPTY);
            p.getInventory().setItem(30, new ItemStack(Items.DIAMOND, 3));     // his OWN diamonds

            WishUndo.Result r = WishUndo.undo(p.level().getServer(), run);
            helper.assertTrue(r.anything(), Component.literal("undo reversed nothing: " + r.did()));
            helper.assertValueEqual(p.getInventory().countItem(Items.DIAMOND_SWORD), 0,
                    Component.literal("swords left after undo"));
            helper.assertValueEqual(inBag(p, Items.DIAMOND), 0, Component.literal("diamonds left in the bag after undo"));
            helper.assertValueEqual(p.getInventory().countItem(Items.DIAMOND), 3,
                    Component.literal("his OWN diamonds after undo — it must take the wish's ten from the bag, where "
                            + "the wish put them, not his three from his inventory"));
            helper.succeed();
        } finally {
            done(p);
        }
    }

    @GameTest(template = "empty_test", batch = "wishdelivery")
    public static void undoTakesBackFromTheHotbarAndTheBagInCreative(GameTestHelper helper) {
        undoTakesItBack(helper, false);
    }

    @GameTest(template = "empty_test", batch = "wishdelivery")
    public static void undoTakesBackFromTheHotbarAndTheBagInSurvival(GameTestHelper helper) {
        undoTakesItBack(helper, true);
    }
}
