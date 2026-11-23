package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.wish.reveal.FanfareTiming;
import com.moddingfromamod.wish.reveal.Reveals;
import com.moddingfromamod.wish.reveal.WishOrbs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A served wish's moment. A button, a cached wish or a starter has no agent and so
 * no wait, and it used to land the instant he tapped: a dragon simply there, a sword simply in his
 * hand. Now it gets what an agent wish gets, compressed to about four seconds:
 *
 * <ol>
 *   <li>the pillar rises at the spot locked when he tapped, its helix climbing in three beats;</li>
 *   <li>the reveal for what the plan makes plays from the pillar ({@link Reveals#lookForCommands});</li>
 *   <li>the plan's commands run at the reveal's CLIMAX ({@link FanfareTiming#climax}), at that spot
 *       (via {@link WishProgress#withServedSpot}), so the thing appears in the flash, never before.</li>
 * </ol>
 *
 * <p>An ITEM is the one that must exist before its reveal (the Spiral flies the real item), so its
 * commands run first, what they put in his inventory is taken back out (as soon as it lands: a
 * starter's datapack gives a tick or two late, {@link #CATCH_TICKS}) and held, flown in, and given
 * back when the Spiral reaches him. A BUILDING runs straight after the pillar, because the builder's
 * whirlwind is its moment. A second tap while one is on its way gets the quick pillar and waits its turn.
 * {@code /moddy stop} before the climax stops it with nothing run.
 */
public final class ServedFanfare {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Off runs served wishes at once, as before (an A/B seam, and the tests that need a bare drip). */
    public static volatile boolean enabled = true;

    private enum Phase { WAIT, RISE, CATCH, REVEAL, HANDOFF, DONE }

    /**
     * How long an item reveal waits for its item to land after the commands ran. A starter item is
     * a datapack's {@code give}, and the first time that pack is used it is installed and reloaded
     * asynchronously, so the item arrives a tick or two AFTER the commands "ran" (the
     * first films found every starter item taking the burst path, with no spiral at all).
     */
    static final int CATCH_TICKS = 60;

    private static final class Fanfare {
        final WishExecutor.Job job;
        final UUID playerId;
        final Reveals.Look look;
        final BlockPos spot;
        Phase phase = Phase.WAIT;
        boolean quick;
        /** staged by a film's timeline at an exact spot; never waits on his other fanfares. */
        boolean staged;
        boolean stagedQuick;
        int age;
        /** An item reveal's items, held until the Spiral reaches him. */
        final List<ItemStack> held = new ArrayList<>();
        /** The hotbar slot each held item was taken from: it goes back THERE. */
        final List<Integer> heldFrom = new ArrayList<>();
        /** His Wish Bag's size before the item plan ran: what it grew by went to the bag. */
        int bagBefore;
        /** His inventory before an item plan ran, to see what it gave once the item lands. */
        List<ItemStack> before;

        Fanfare(WishExecutor.Job job, UUID playerId, Reveals.Look look, BlockPos spot) {
            this.job = job;
            this.playerId = playerId;
            this.look = look;
            this.spot = spot;
        }

        UUID wishId() {
            return job.wish.id();
        }

        /** Its commands have not run: it can still be stopped with nothing to undo. */
        boolean beforeClimax() {
            return phase == Phase.WAIT || phase == Phase.RISE
                    || (phase == Phase.REVEAL && look != Reveals.Look.ITEM);
        }
    }

    private static final List<Fanfare> ACTIVE = new ArrayList<>();

    /**
     * <b> (Moddy Media): the next served plan for this player rises HERE.</b> A film stages several
     * pillars at once at spots chosen on the set, each timed to a word of the narration, so a staged
     * fanfare skips the aim and does not wait its turn behind his others; its rise is the full one
     * unless asked otherwise. One-shot: consumed by the next {@link #submit} for him. Nothing else
     * about the wish changes; it is the same served path a button press takes.
     */
    private record Stage(BlockPos spot, boolean quick) {
    }

    private static final Map<UUID, Stage> STAGE_NEXT = new HashMap<>();

    public static void stageNext(UUID player, BlockPos spot, boolean quick) {
        STAGE_NEXT.put(player, new Stage(spot.immutable(), quick));
    }

    /** Drop an unconsumed stage (the wish was refused before it was served). */
    public static void clearStage(UUID player) {
        STAGE_NEXT.remove(player);
    }
    private static final Map<UUID, Long> LAST_REVEAL = new HashMap<>();
    private static long now;

    private ServedFanfare() {
    }

    /** Take a served plan: lock its spot now, and start its pillar when it is its turn. */
    static void submit(ServerPlayer player, Wish wish, CommandPlan plan) {
        Reveals.Look look = Reveals.lookForCommands(plan.commands());
        ServerLevel level = player.level();
        Stage stage = STAGE_NEXT.remove(player.getUUID());
        if (stage != null) {
            BlockPos at = stage.spot();
            Vec3 stagedPos = look == Reveals.Look.ITEM
                    ? player.position() : new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            WishExecutor.Job staged = new WishExecutor.Job(player.getUUID(), level, stagedPos, wish, plan, at, true);
            Fanfare f = new Fanfare(staged, player.getUUID(), look, at);
            f.staged = true;
            f.stagedQuick = stage.quick();
            ACTIVE.add(f);
            LOGGER.info("[reveal] staged wish {} at {} {} {} ({})", wish.shortId(), at.getX(), at.getY(), at.getZ(),
                    look == null ? "build" : look.name().toLowerCase());
            return;
        }
        // the same aim-then-clamp rule an agent's wish gets, and here the kind is known at
        // once, so the one slide happens now. It used to be a fixed few blocks ahead of his feet,
        // dropped straight down — under him when he was flying.
        var from = WishAimer.snapshot(player);
        WishAim.Spot aimed = WishAimer.aim(player);
        BlockPos spot = new BlockPos(aimed.x(), aimed.y(), aimed.z());
        WishAim.Spot slid = WishAimer.slide(level, from, spot, kindOf(look));
        WishAimer.logAim(wish.shortId(), from, slid != null ? slid : aimed);
        if (slid != null) {
            spot = new BlockPos(slid.x(), slid.y(), slid.z());
        }
        // Where its commands run from. A creature, a picture or an event is staged AT the spot; a
        // datapack or an item keeps his own position, because its commands were written relative to
        // him (@p, ~ ~ ~) and moving them would put his slushie machine four blocks into a wall.
        Vec3 pos = look == Reveals.Look.CREATURE || look == Reveals.Look.EVENT
                || look == Reveals.Look.PICTURE
                ? new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5)
                : player.position();
        WishExecutor.Job job = new WishExecutor.Job(player.getUUID(), level, pos, wish, plan,
                spot, true);
        ACTIVE.add(new Fanfare(job, player.getUUID(), look, spot));
    }

    /** The kind a served plan's spot is clamped for: the look it reveals with. */
    static WishAim.Kind kindOf(Reveals.Look look) {
        if (look == null) {
            return WishAim.Kind.BUILDING;      // a wishbuild: the builder's whirlwind is its moment
        }
        return switch (look) {
            case CREATURE -> WishAim.Kind.CREATURE;
            case PICTURE -> WishAim.Kind.PICTURE;
            case EVENT -> WishAim.Kind.EVENT;
            case ITEM -> WishAim.Kind.ITEM;
            case BUILD -> WishAim.Kind.BUILDING;
            case BURST -> WishAim.Kind.OTHER;
        };
    }

    /** How many served wishes are mid-fanfare (for {@code /moddy status}). */
    public static int pending() {
        return ACTIVE.size();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tick(event.getServer());
    }

    /** One tick. Public for tests and films. */
    public static void tick(MinecraftServer server) {
        now++;
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (Fanfare f : new ArrayList<>(ACTIVE)) {
            step(server, f);
        }
        ACTIVE.removeIf(f -> f.phase == Phase.DONE);
    }

    private static void step(MinecraftServer server, Fanfare f) {
        ServerLevel level = f.job.level;
        double fx = f.spot.getX() + 0.5, fy = f.spot.getY(), fz = f.spot.getZ() + 0.5;
        switch (f.phase) {
            case WAIT -> {
                if (!f.staged && busy(f)) {
                    return;
                }
                Long last = LAST_REVEAL.get(f.playerId);
                f.quick = f.staged ? f.stagedQuick
                        : FanfareTiming.quick(anotherBefore(f), last == null ? -1 : now - last);
                f.phase = Phase.RISE;
                f.age = 0;
                LOGGER.info("[reveal] served {} for wish {} ({})",
                        f.look == null ? "build" : f.look.name().toLowerCase(),
                        f.job.wish.shortId(), f.quick ? "quick" : "full");
                WishOrbs.pinServed(level, f.wishId(), fx, fy, fz, 3, FanfareTiming.fraction(0, f.quick));
            }
            case RISE -> {
                f.age++;
                if (f.age < FanfareTiming.rise(f.quick)) {
                    WishOrbs.pinServed(level, f.wishId(), fx, fy, fz, 3,
                            FanfareTiming.fraction(f.age, f.quick));
                    return;
                }
                LAST_REVEAL.put(f.playerId, now);
                f.age = 0;
                ServerPlayer player = server.getPlayerList().getPlayer(f.playerId);
                if (f.look == Reveals.Look.ITEM && player != null) {
                    // The pillar stays up until the item has landed and can be flown in.
                    f.before = snapshot(player);
                    // What it hands over is said when it is handed over — at the Spiral's
                    // end, not while it is still flying — and what went to the Wish Bag is shown
                    // from there, since it never sits in his inventory to be caught.
                    com.moddingfromamod.item.delivery.WishDelivery.hold(f.wishId().toString());
                    f.bagBefore = com.moddingfromamod.bag.WishBagService.size(server, player.getUUID());
                    f.job.runAll(server);
                    f.phase = Phase.CATCH;
                    catchItem(server, f, player, level, fx, fy, fz);
                    return;
                }
                double[] orb = release(f, fx, fy, fz);
                if (f.look == null) {
                    // A building: its whirlwind is the moment, and it goes up the instant he is told.
                    WishExecutor.enqueue(f.job);
                    f.phase = Phase.DONE;
                    return;
                }
                if (f.look == Reveals.Look.ITEM) {
                    // He has gone: nothing to fly it to.
                    f.job.runAll(server);
                    f.job.finish(server);
                    f.phase = Phase.DONE;
                    return;
                }
                Reveals.play(level, f.look, f.wishId(), orb, new double[]{fx, fy, fz}, player, ItemStack.EMPTY);
                f.phase = Phase.REVEAL;
            }
            case CATCH -> {
                WishOrbs.pinServed(level, f.wishId(), fx, fy, fz, 3, 1f);
                catchItem(server, f, server.getPlayerList().getPlayer(f.playerId), level, fx, fy, fz);
            }
            case REVEAL -> {
                if (f.age++ >= FanfareTiming.climax(f.look)) {
                    WishExecutor.enqueue(f.job);
                    f.phase = Phase.DONE;
                }
            }
            case HANDOFF -> {
                if (f.age++ >= FanfareTiming.ITEM_HANDOFF) {
                    give(f, server.getPlayerList().getPlayer(f.playerId));
                    f.job.finish(server);
                    f.phase = Phase.DONE;
                }
            }
            case DONE -> {
            }
        }
    }

    /** An earlier fanfare of his is still before its climax: this one waits its turn. */
    private static boolean busy(Fanfare f) {
        for (Fanfare o : ACTIVE) {
            if (o == f) {
                return false;
            }
            if (o.playerId.equals(f.playerId) && o.phase != Phase.WAIT && o.phase != Phase.DONE
                    && o.phase != Phase.HANDOFF) {
                return true;
            }
        }
        return false;
    }

    /** Another of his is in the list ahead of this one. */
    private static boolean anotherBefore(Fanfare f) {
        for (Fanfare o : ACTIVE) {
            if (o == f) {
                return false;
            }
            if (o.playerId.equals(f.playerId) && o.phase != Phase.DONE) {
                return true;
            }
        }
        return false;
    }

    /** Take down the pillar; where its orb was, for the reveal to start from. */
    private static double[] release(Fanfare f, double fx, double fy, double fz) {
        double[] orb = WishOrbs.release(f.wishId());
        return orb != null ? orb
                : new double[]{fx, fy + com.moddingfromamod.wish.reveal.OrbShape.ORB_HEIGHT, fz};
    }

    private static List<ItemStack> snapshot(ServerPlayer player) {
        var inv = player.getInventory();
        List<ItemStack> before = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            before.add(inv.getItem(i).copy());
        }
        return before;
    }

    /**
     * Once the item plan has run: as soon as what it gives is in his inventory, take it back out
     * and fly it in from the orb. If nothing lands within {@link #CATCH_TICKS} (his inventory was
     * full, or the plan failed), a burst still marks the moment and the job's own feedback says
     * what happened.
     */
    private static void catchItem(MinecraftServer server, Fanfare f, ServerPlayer player, ServerLevel level,
                                  double fx, double fy, double fz) {
        ItemStack shown = player == null ? ItemStack.EMPTY : takeWhatItGave(f, player);
        if (shown.isEmpty() && player != null) {
            shown = newestInBag(server, f, player);
        }
        if (!shown.isEmpty()) {
            double[] orb = release(f, fx, fy, fz);
            Reveals.play(level, Reveals.Look.ITEM, f.wishId(), orb, new double[]{fx, fy, fz},
                    player, shown.copyWithCount(1));
            f.phase = Phase.HANDOFF;
            f.age = 0;
            return;
        }
        if (player == null || f.age++ >= CATCH_TICKS) {
            double[] orb = release(f, fx, fy, fz);
            Reveals.play(level, Reveals.Look.BURST, f.wishId(), orb, new double[]{fx, fy, fz},
                    player, ItemStack.EMPTY);
            give(f, player);
            f.job.finish(server);
            f.phase = Phase.DONE;
        }
    }

    /**
     * Take back out what the item plan put in his inventory since {@link Fanfare#before}, to be
     * flown in. Returns the first thing it gave (what the Spiral carries), or EMPTY when nothing yet.
     */
    private static ItemStack takeWhatItGave(Fanfare f, ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && i < f.before.size(); i++) {
            ItemStack was = f.before.get(i);
            ItemStack is = inv.getItem(i);
            if (is.isEmpty()) {
                continue;
            }
            int gained = was.isEmpty() || !ItemStack.isSameItemSameComponents(was, is)
                    ? is.getCount() : is.getCount() - was.getCount();
            if (gained > 0) {
                f.held.add(is.copyWithCount(gained));
                f.heldFrom.add(i);
                is.shrink(gained);
            }
        }
        if (!f.held.isEmpty()) {
            // Keeps the client from holding it before its time (it may have seen it for a tick).
            player.containerMenu.broadcastChanges();
        }
        return f.held.isEmpty() ? ItemStack.EMPTY : f.held.get(0);
    }

    /**
     * What went into his Wish Bag since the plan ran — the bag's newest entry, shown by the Spiral
     * and left where it is. EMPTY when the bag did not grow.
     */
    private static ItemStack newestInBag(MinecraftServer server, Fanfare f, ServerPlayer player) {
        if (com.moddingfromamod.bag.WishBagService.size(server, player.getUUID()) <= f.bagBefore) {
            return ItemStack.EMPTY;
        }
        var bag = com.moddingfromamod.bag.WishBagService.contents(server, player.getUUID());
        return bag.isEmpty() ? ItemStack.EMPTY : bag.get(0).copyWithCount(1);
    }

    /**
     * Hand back what an item reveal held — into the very slot it was taken from, which is the slot
     * he was told it is in; else through the one delivery path. Dropped at the spot only
     * when he is gone. Then what it handed over is said.
     */
    private static void give(Fanfare f, ServerPlayer player) {
        for (int k = 0; k < f.held.size(); k++) {
            ItemStack s = f.held.get(k);
            if (player != null) {
                int slot = k < f.heldFrom.size() ? f.heldFrom.get(k) : -1;
                var inv = player.getInventory();
                if (slot >= 0 && inv.getItem(slot).isEmpty()) {
                    inv.setItem(slot, s);
                } else if (slot >= 0 && ItemStack.isSameItemSameComponents(inv.getItem(slot), s)
                        && inv.getItem(slot).getCount() + s.getCount() <= s.getMaxStackSize()) {
                    inv.getItem(slot).grow(s.getCount());
                } else {
                    com.moddingfromamod.item.delivery.WishDelivery.give(player, s, "",
                            com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, true);
                }
                player.inventoryMenu.broadcastChanges();
            } else {
                f.job.level.addFreshEntity(new ItemEntity(f.job.level, f.spot.getX() + 0.5,
                        f.spot.getY(), f.spot.getZ() + 0.5, s));
            }
        }
        f.held.clear();
        f.heldFrom.clear();
        com.moddingfromamod.item.delivery.WishDelivery.release(f.wishId().toString());
    }

    /**
     * {@code /moddy stop} with no agent run: stop his newest served wish that has not reached its
     * climax. Nothing has run, so there is nothing to undo. Null when there is none to stop.
     */
    public static Wish cancelFor(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Fanfare f = ACTIVE.get(i);
            if (f.playerId.equals(player.getUUID()) && f.beforeClimax()) {
                ACTIVE.remove(i);
                com.moddingfromamod.item.delivery.WishDelivery.release(f.wishId().toString());
                WishOrbs.drop(f.wishId());
                Reveals.cut(f.wishId());
                WishBook.get(player.getServer()).complete(f.wishId(), WishStatus.STOPPED, "stopped");
                LOGGER.info("[reveal] served wish {} stopped before its climax", f.job.wish.shortId());
                return f.job.wish;
            }
        }
        return null;
    }

    /**
     * 👎 on a served wish whose pillar is still rising: its climax must not land after the
     * wish was undone. The caller settles the book. False when that wish has no fanfare before its
     * climax (after it, the thing is already made and the undo takes it away).
     */
    public static boolean cancelBeforeClimax(java.util.UUID wishId) {
        if (wishId == null) {
            return false;
        }
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Fanfare f = ACTIVE.get(i);
            if (wishId.equals(f.wishId()) && f.beforeClimax()) {
                ACTIVE.remove(i);
                com.moddingfromamod.item.delivery.WishDelivery.release(f.wishId().toString());
                WishOrbs.drop(f.wishId());
                Reveals.cut(f.wishId());
                LOGGER.info("[reveal] served wish {} undone before its climax", f.job.wish.shortId());
                return true;
            }
        }
        return false;
    }

    /** World stop: anything held goes back to whoever is still here; nothing else runs. */
    public static void clear(MinecraftServer server) {
        for (Fanfare f : ACTIVE) {
            WishOrbs.drop(f.wishId());
            if (!f.held.isEmpty() && server != null) {
                ServerPlayer p = server.getPlayerList().getPlayer(f.playerId);
                if (p != null) {
                    give(f, p);
                }
            }
            com.moddingfromamod.item.delivery.WishDelivery.release(f.wishId().toString());
        }
        ACTIVE.clear();
        LAST_REVEAL.clear();
    }

    /**
     * Test seam: this wish's item plan has run and its fanfare is waiting for the item to
     * land. A test simulating a late give hands it over after THIS, not at a tick counted from its
     * own start: the snapshot it is measured against is taken when the rise ends, and when that is
     * depends on this fanfare's own ticks, not the test's.
     */
    static boolean catching(UUID wishId) {
        for (Fanfare f : ACTIVE) {
            if (f.wishId().equals(wishId)) {
                return f.phase == Phase.CATCH;
            }
        }
        return false;
    }

    /**
     * Test seam: this wish's item has been caught and the Spiral is flying it to him. A test
     * asks this rather than counting ticks from its own start: when the hand-off begins depends on
     * this fanfare's own rise and catch, and a test that guessed it from a fixed delay read a hand-off
     * that had not happened yet as one that never would.
     */
    static boolean handingOff(UUID wishId) {
        for (Fanfare f : ACTIVE) {
            if (f.wishId().equals(wishId)) {
                return f.phase == Phase.HANDOFF;
            }
        }
        return false;
    }

    /** Test seam: this wish's fanfare is still under way — it has not settled or been stopped. */
    static boolean active(UUID wishId) {
        for (Fanfare f : ACTIVE) {
            if (f.wishId().equals(wishId)) {
                return true;
            }
        }
        return false;
    }

    /** Test seam: forget the quick-tap memory so a test starts from a full fanfare. */
    public static void forgetForTest() {
        LAST_REVEAL.clear();
    }
}
