package com.moddingfromamod.wish;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Moddy is pinned to hotbar slot 1,.</b>
 *
 * <p>The contract: exactly one watch per child, always in hotbar slot 1, never
 * dropped, stored or lost on death, in every game mode; the wish camera is excluded. A child's
 * client is stock, so the server cannot stop an attempt, only undo it — the guarantee is the END
 * STATE within one tick, and a one-frame flicker is accepted.
 *
 * <p>{@link WatchPin} decides; this does it, from five places:
 *
 * <ol>
 *   <li><b>every tick</b> — {@link #pin} puts the watch in slot 1, removes copies, mints one when
 *       there is none, and moves whatever was in slot 1 somewhere it survives;</li>
 *   <li><b>a toss</b> is cancelled — and cancelling a toss does NOT give the item back (NeoForge
 *       removes it from the inventory before asking), so the tick's mint is what returns it, and
 *       the client is resynced because it predicted the removal;</li>
 *   <li><b>a loose watch</b> joining any level is refused: a death drop, a creative drop, a full
 *       bag's overflow — nothing for another child to pick up;</li>
 *   <li><b>a death</b>'s drops leave the watch out;</li>
 *   <li><b>a closed container</b> gives back a watch stored in it.</li>
 * </ol>
 *
 * <p>An item frame, armour stand, allay or horse cannot take him either — not because of this
 * class: {@code VanillaCarrierAbilities} refuses every entity interaction for any carrier stack,
 * and the watch is a carrier ({@code WatchPinGameTest.anItemFrameCannotTakeHim} pins it).
 *
 * <p>The 5-second top-up in {@link VanillaWatchItem} and {@code /moddy} stay as the backstop.
 */
@EventBusSubscriber
public final class WatchPinService {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-watch-pin");

    /**
     * Gate mocks the pin applies to. A GameTest mock is a real ServerPlayer in the player list, and
     * hundreds of tests hand one a sword in slot 1 — pinning those would fail every one of them
     * against a rule they are not about. A mock is pinned only when a test opts it in; every real
     * player is pinned with no opt-in at all.
     */
    private static final Set<UUID> PINNED_MOCKS = ConcurrentHashMap.newKeySet();

    /**
     * Every time something was undone — a pin that moved, removed or minted, a toss cancelled, a
     * loose watch refused, a death drop removed, a stored watch given back. What the stock-client
     * film (lite/WatchPinStage) reads to know an attempt reached the server at all: an end state
     * that holds with nothing undone is also what a click that missed looks like.
     */
    private static final java.util.concurrent.atomic.AtomicInteger UNDONE =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int undone() {
        return UNDONE.get();
    }

    private WatchPinService() {
    }

    /** For a GameTest: pin this mock as a real child is pinned. */
    public static void pinMockForTest(ServerPlayer mock) {
        PINNED_MOCKS.add(mock.getUUID());
    }

    /** Has a test opted this mock in to the pin? (The top-up follows the same opt-in.) */
    static boolean isPinnedMock(ServerPlayer p) {
        return PINNED_MOCKS.contains(p.getUUID());
    }

    public static void unpinMockForTest(ServerPlayer mock) {
        PINNED_MOCKS.remove(mock.getUUID());
        BAG_MOCKS.remove(mock.getUUID());
    }

    /**
     * Gate mocks the BAG pin applies to — a separate opt-in, so the watch's own tests, which
     * fill slot 2 with cobblestone to prove where a displaced item goes, are not also tests of the
     * bag. Every real child gets both pins with no opt-in.
     */
    private static final Set<UUID> BAG_MOCKS = ConcurrentHashMap.newKeySet();

    /** For a GameTest: pin the Wish Bag on this mock too (and the watch). */
    public static void pinBagMockForTest(ServerPlayer mock) {
        PINNED_MOCKS.add(mock.getUUID());
        BAG_MOCKS.add(mock.getUUID());
    }

    /** Does the bag's pin apply to this player? A pinned child; a mock only when opted in. */
    public static boolean bagApplies(ServerPlayer p) {
        return applies(p) && (!com.moddingfromamod.seam.MockPlayers.is(p)
                || BAG_MOCKS.contains(p.getUUID()));
    }

    /** Bag pins that moved, removed or minted — the bag's own count, apart from the watch's. */
    private static final java.util.concurrent.atomic.AtomicInteger BAG_UNDONE =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int bagUndone() {
        return BAG_UNDONE.get();
    }

    /** Does the contract apply to this player? A child in any mode but spectator; never a camera. */
    public static boolean applies(ServerPlayer p) {
        if (p == null || p.isRemoved() || p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            return false;
        }
        if (!com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID())) {
            return false;                      // the wish camera, PlayerKind
        }
        return !com.moddingfromamod.seam.MockPlayers.is(p)
                || PINNED_MOCKS.contains(p.getUUID());
    }

    // ── 1. every tick ─────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            pin(p, null);
        }
    }

    /**
     * Apply the plan to one player.
     *
     * @param give a real watch stack to use instead of minting one (the copy a closed container gave
     *             back), or null
     * @return true if anything changed
     */
    public static boolean pin(ServerPlayer p, ItemStack give) {
        if (!applies(p)) {
            return false;
        }
        var inv = p.getInventory();
        boolean refreshed = refresh(p, inv.getItem(WatchPin.HOME));
        boolean[] watch = new boolean[WatchPin.SLOTS];
        boolean[] empty = new boolean[WatchPin.SLOTS];
        for (int i = 0; i < WatchPin.SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            empty[i] = s.isEmpty();
            watch[i] = !empty[i] && VanillaWatchItem.isWatch(s);
        }
        boolean carried = VanillaWatchItem.isWatch(p.containerMenu.getCarried());
        boolean stored = storedIn(p.containerMenu, p) != null;
        WatchPin.Plan plan = WatchPin.plan(WatchPin.HOME,
                bagApplies(p) ? java.util.Set.of(WatchPin.BAG_HOME) : java.util.Set.of(),
                watch, empty, carried, stored);
        if (plan.idle()) {
            return pinBag(p, null) | refreshed;
        }
        UNDONE.incrementAndGet();
        LOG.info("pin {}: source={} remove={} displacedTo={} carried={} stored={} menu={}",
                p.getScoreboardName(), plan.source(), plan.remove(), plan.displacedTo(), carried,
                stored, p.containerMenu.getClass().getSimpleName());
        for (int slot : plan.remove()) {
            inv.setItem(slot, ItemStack.EMPTY);
        }
        if (plan.source() != WatchPin.ELSEWHERE && plan.source() != WatchPin.HOME) {
            ItemStack moddy = plan.source() == WatchPin.MINT
                    ? (give != null && !give.isEmpty() ? give : VanillaWatchItem.stack())
                    : inv.getItem(plan.source());
            ItemStack displaced = inv.getItem(WatchPin.HOME);
            if (plan.source() != WatchPin.MINT) {
                inv.setItem(plan.source(), ItemStack.EMPTY);
            }
            inv.setItem(WatchPin.HOME, moddy);
            if (!displaced.isEmpty()) {
                if (plan.displacedTo() >= 0) {
                    inv.setItem(plan.displacedTo(), displaced);
                } else {
                    // Full bag (owner): at his feet, never destroyed. Not a watch, so the loose-watch
                    // guard lets it land.
                    p.drop(displaced, false);
                }
            }
        }
        pinBag(p, null);
        // The client predicted whatever it did (a toss, a drag, a creative slot) — tell it the truth.
        p.inventoryMenu.broadcastChanges();
        p.containerMenu.broadcastChanges();
        return true;
    }

    /**
     * <b>The Wish Bag in hotbar slot 2 </b> — the same contract as the watch, by the
     * same policy ({@link WatchPin#plan} at {@link WatchPin#BAG_HOME}): exactly one, always in slot
     * 2, never dropped, stored or lost on death. What was in slot 2 moves to the first free slot, or
     * — with none — lands at his feet and he is TOLD (owner), because a thing that quietly appears
     * on the ground is a thing he thinks the bag ate.
     *
     * @param give a real bag stack to use instead of minting one (the copy a closed container gave
     *             back), or null
     */
    public static boolean pinBag(ServerPlayer p, ItemStack give) {
        if (!bagApplies(p)) {
            return false;
        }
        var inv = p.getInventory();
        boolean refreshed = false;
        ItemStack home = inv.getItem(WatchPin.BAG_HOME);
        if (com.moddingfromamod.bag.WishBagItem.isBag(home)
                && !ItemStack.isSameItemSameComponents(home, currentBag())) {
            inv.setItem(WatchPin.BAG_HOME, currentBag().copy());
            refreshed = true;
        }
        boolean[] bag = new boolean[WatchPin.SLOTS];
        boolean[] empty = new boolean[WatchPin.SLOTS];
        for (int i = 0; i < WatchPin.SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            empty[i] = s.isEmpty();
            bag[i] = !empty[i] && com.moddingfromamod.bag.WishBagItem.isBag(s);
        }
        boolean carried = com.moddingfromamod.bag.WishBagItem.isBag(p.containerMenu.getCarried());
        boolean stored = storedBagIn(p.containerMenu, p) != null;
        WatchPin.Plan plan = WatchPin.plan(WatchPin.BAG_HOME, java.util.Set.of(WatchPin.HOME), bag, empty,
                carried, stored);
        if (plan.idle(WatchPin.BAG_HOME)) {
            if (refreshed) {
                p.inventoryMenu.broadcastChanges();
            }
            return refreshed;
        }
        BAG_UNDONE.incrementAndGet();
        LOG.info("pin bag {}: source={} remove={} displacedTo={} carried={} stored={}",
                p.getScoreboardName(), plan.source(), plan.remove(), plan.displacedTo(), carried, stored);
        for (int slot : plan.remove()) {
            inv.setItem(slot, ItemStack.EMPTY);
        }
        if (plan.source() != WatchPin.ELSEWHERE && plan.source() != WatchPin.BAG_HOME) {
            ItemStack theBag = plan.source() == WatchPin.MINT
                    ? (give != null && !give.isEmpty() ? give : currentBag().copy())
                    : inv.getItem(plan.source());
            ItemStack displaced = inv.getItem(WatchPin.BAG_HOME);
            if (plan.source() != WatchPin.MINT) {
                inv.setItem(plan.source(), ItemStack.EMPTY);
            }
            inv.setItem(WatchPin.BAG_HOME, theBag);
            if (!displaced.isEmpty()) {
                if (plan.displacedTo() >= 0) {
                    inv.setItem(plan.displacedTo(), displaced);
                } else {
                    String what = displaced.getHoverName().getString();
                    p.drop(displaced, false);
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal("✨ Your Wish Bag"
                            + " lives in slot 2 and your inventory is full, so your " + what
                            + " is on the ground at your feet!").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
                }
            }
        }
        p.inventoryMenu.broadcastChanges();
        p.containerMenu.broadcastChanges();
        return true;
    }

    /** The current bag mint, built once. Nothing per child lives on it, so replacing one loses nothing. */
    private static ItemStack currentBag;

    private static ItemStack currentBag() {
        if (currentBag == null) {
            currentBag = com.moddingfromamod.bag.WishBagItem.stack();
        }
        return currentBag;
    }

    /** Watches brought up to the current mint — what the GameTest reads. Not an undo. */
    private static final java.util.concurrent.atomic.AtomicInteger REFRESHED =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int refreshed() {
        return REFRESHED.get();
    }

    /** The current mint, built once: every watch in slot 1 is compared against it. */
    private static ItemStack current;

    private static ItemStack current() {
        if (current == null) {
            current = VanillaWatchItem.stack();
        }
        return current;
    }

    /**
     * <b>The watch in slot 1 is always the CURRENT watch.</b>
     *
     * <p>A watch lives in a child's save for as long as he plays, and the pin never re-mints one
     * that is already home — so every change to what a watch IS (an earlier issue's one-click stamp was the
     * first: without it an old watch still emptied his offhand) would otherwise reach only children
     * who never had one. So a watch in slot 1 whose components differ from a fresh mint is replaced
     * by a fresh mint, in place, once: the next tick they are equal and this does nothing. Nothing
     * per-child lives on the watch (its panel works on its own copy), so replacing it loses nothing.
     * NOT an undo: the watch never left, so {@link #UNDONE} (which the stock-client film reads as
     * "an attempt reached the server") is untouched.
     */
    static boolean refresh(ServerPlayer p, ItemStack home) {
        if (!VanillaWatchItem.isWatch(home) || sameMintButItsName(home)
                // The stock-client film's control arm (NOSTAMP=1) films the watch as it was.
                || Boolean.getBoolean(com.moddingfromamod.ModdingFromAModMod.MODID + ".watchpin.nostamp")) {
            return false;
        }
        p.getInventory().setItem(WatchPin.HOME, current().copy());
        REFRESHED.incrementAndGet();
        LOG.info("refreshed {}'s watch to the current one", p.getScoreboardName());
        p.inventoryMenu.broadcastChanges();
        return true;
    }

    /**
     * Equal to the current mint in everything but its NAME. The name is per child on a world with a
     * wish bank ({@code Moddy · ✨ 7/10}, kept live by {@code WishBankService.label}), so a
     * watch that differs only there is current — comparing the name too would replace it every
     * tick and the label every second, forever.
     */
    static boolean sameMintButItsName(ItemStack home) {
        ItemStack probe = home.copy();
        probe.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                current().get(net.minecraft.core.component.DataComponents.CUSTOM_NAME));
        return ItemStack.isSameItemSameComponents(probe, current());
    }

    // ── 2. a toss ─────────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        ItemStack tossed = event.getEntity().getItem();
        boolean isBag = com.moddingfromamod.bag.WishBagItem.isBag(tossed);
        if (!(VanillaWatchItem.isWatch(tossed) || isBag)
                || !(event.getPlayer() instanceof ServerPlayer p) || !applies(p)) {
            return;
        }
        if (isBag) {
            if (!bagApplies(p)) {
                return;
            }
            ItemStack back = tossed.copy();
            event.setCanceled(true);
            BAG_UNDONE.incrementAndGet();
            var server = ((net.minecraft.server.level.ServerLevel) p.level()).getServer();
            server.schedule(new net.minecraft.server.TickTask(server.getTickCount(),
                    () -> pinBag(p, back)));
            return;
        }
        ItemStack back = event.getEntity().getItem().copy();
        event.setCanceled(true);
        UNDONE.incrementAndGet();
        // Cancelling gives NOTHING back — the stack already left his inventory — so the watch is
        // put back here. AFTER the packet that tossed it, never inside it:
        //
        //  • a throw from the inventory screen runs inside the container-click handler, which
        //    suppresses updates, drops the carried stack (this event), clears the cursor, and then
        //    records the client's own predicted cursor (empty) as what the client has. A resync
        //    sent from in here went out before the cursor was cleared, so the client was told it
        //    was still carrying a watch, the server believed it was not, and nothing ever
        //    corrected it: a ghost watch on his cursor, and every number-key swap dead after it
        //    (vanilla swaps only with an empty cursor). Found by the stock-client film —
        //    drag_out then number_key failed every time, each alone passed.
        //  • Q needs no resync of its own: ServerPlayer.drop records the emptied slot as the
        //    client's view, so the ordinary broadcast after the pin sends the watch back.
        // schedule(TickTask), not execute() or submit(): on the server thread — where this event
        // fires — both of those run the task immediately, which is exactly the "inside" above.
        ServerPlayer who = p;
        var server = ((net.minecraft.server.level.ServerLevel) p.level()).getServer();
        server.schedule(new net.minecraft.server.TickTask(server.getTickCount(),
                () -> pin(who, back)));
    }

    // ── 3. a loose watch in the world ─────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof ItemEntity item
                && (VanillaWatchItem.isWatch(item.getItem())
                        || com.moddingfromamod.bag.WishBagItem.isBag(item.getItem()))) {
            LOG.info("a loose Moddy watch or Wish Bag at {} was refused", item.blockPosition());
            event.setCanceled(true);
            UNDONE.incrementAndGet();
        }
    }

    // ── 4. death ──────────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            if (event.getDrops().removeIf(e -> VanillaWatchItem.isWatch(e.getItem())
                    || com.moddingfromamod.bag.WishBagItem.isBag(e.getItem()))) {
                UNDONE.incrementAndGet();
            }
        }
    }

    // ── 5. a closed container ─────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !applies(p)) {
            return;
        }
        ItemStack back = null;
        for (Slot s; (s = storedIn(event.getContainer(), p)) != null; ) {
            back = s.getItem().copy();
            s.set(ItemStack.EMPTY);
            UNDONE.incrementAndGet();
        }
        ItemStack bagBack = null;
        for (Slot s; bagApplies(p) && (s = storedBagIn(event.getContainer(), p)) != null; ) {
            bagBack = s.getItem().copy();
            s.set(ItemStack.EMPTY);
            BAG_UNDONE.incrementAndGet();
        }
        pin(p, back);
        if (bagBack != null) {
            pinBag(p, bagBack);
        }
    }

    /**
     * <b>Does he hold one right now</b> — in a slot, on the cursor, or in the container he has open?
     *
     * <p>What the 5-second top-up asks. It used to ask only the 41 slots, so a watch he was carrying
     * or had just put in a chest he was still looking into read as lost, and a second one arrived —
     * caught by {@code WatchPinGameTest}'s chest row, red whenever the top-up's timer fell inside the
     * five ticks the chest was held open.
     */
    public static boolean holdsOne(ServerPlayer p) {
        return VanillaWatchItem.has(p) || VanillaWatchItem.isWatch(p.containerMenu.getCarried())
                || storedIn(p.containerMenu, p) != null;
    }

    /**
     * The first slot of an open container, not his own inventory, holding a watch — never one of
     * Moddy's own menus, whose COMPOSE cell IS a watch stack (an icon, not a stored watch).
     */
    /** The same as {@link #storedIn}, for the Wish Bag. */
    static Slot storedBagIn(AbstractContainerMenu menu, ServerPlayer p) {
        if (menu == null || menu == p.inventoryMenu
                || menu.getClass().getName().startsWith("com.moddingfromamod.")) {
            return null;
        }
        for (Slot s : menu.slots) {
            if (s.container != p.getInventory() && com.moddingfromamod.bag.WishBagItem.isBag(s.getItem())) {
                return s;
            }
        }
        return null;
    }

    static Slot storedIn(AbstractContainerMenu menu, ServerPlayer p) {
        if (menu == null || menu == p.inventoryMenu
                || menu.getClass().getName().startsWith("com.moddingfromamod.")) {
            return null;
        }
        for (Slot s : menu.slots) {
            if (s.container != p.getInventory() && VanillaWatchItem.isWatch(s.getItem())) {
                return s;
            }
        }
        return null;
    }

}
