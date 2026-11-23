package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Moddy stays in hotbar slot 1 — one GameTest per mechanism.</b>
 *
 * <p>Each test does what a child's client makes the server do — the same server-side call a packet
 * lands in — and then asks for the contract's END STATE: exactly one watch, in slot 1, none loose,
 * within one tick ({@link #MAX_TICKS} allows for where in the tick the action and the pin fall).
 * What a stock client SHOWS is filmed separately ({@code tools/watch-pin-film.sh}).
 *
 * <p>Every mock here is opted in with {@link WatchPinService#pinMockForTest}: an ordinary gate mock
 * is not pinned, because hundreds of tests hand one a sword in slot 1.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WatchPinGameTest {

    private static final String BATCH = "watchpin";
    /** The end state must hold this many ticks after the attempt — "within one tick", plus order. */
    private static final int MAX_TICKS = 2;

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private static ServerPlayer pinned(GameTestHelper helper, String name) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, name);
        WatchPinService.pinMockForTest(p);
        p.getInventory().clearContent();
        p.getInventory().setItem(0, VanillaWatchItem.stack());
        p.getInventory().setSelectedSlot(0);
        return p;
    }

    private static void done(ServerPlayer p) {
        WatchPinService.unpinMockForTest(p);
        MockPlayerNetwork.remove(p);
    }

    /** Watches anywhere on him: 41 slots and the cursor. */
    static int watches(ServerPlayer p) {
        int n = VanillaWatchItem.isWatch(p.containerMenu.getCarried()) ? 1 : 0;
        for (int i = 0; i < WatchPin.SLOTS; i++) {
            if (VanillaWatchItem.isWatch(p.getInventory().getItem(i))) {
                n++;
            }
        }
        return n;
    }

    static boolean home(ServerPlayer p) {
        return VanillaWatchItem.isWatch(p.getInventory().getItem(WatchPin.HOME)) && watches(p) == 1;
    }

    static int loose(ServerLevel level, ServerPlayer p) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(p.blockPosition()).inflate(16),
                e -> VanillaWatchItem.isWatch(e.getItem())).size();
    }

    private static String state(ServerPlayer p) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < WatchPin.SLOTS; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) {
                b.append(i).append('=').append(VanillaWatchItem.isWatch(s) ? "WATCH"
                        : s.getItem().toString()).append(' ');
            }
        }
        return b.append("carried=").append(p.containerMenu.getCarried()).append(" menu=")
                .append(p.containerMenu.getClass().getSimpleName()).append(']').toString();
    }

    /**
     * Succeed when {@code end} holds, and fail if it first holds more than {@link #MAX_TICKS} after
     * {@code start} — "comes back eventually" is the 5-second top-up, not this contract.
     */
    private static void within(GameTestHelper helper, long start, BooleanSupplier end,
                               java.util.function.Supplier<String> why, Runnable cleanup) {
        helper.succeedWhen(() -> {
            long took = helper.getLevel().getGameTime() - start;
            boolean ok = end.getAsBoolean();
            if (!ok) {
                if (took > 90) {
                    cleanup.run();
                }
                helper.assertTrue(false, Component.literal(why.get() + " (" + took + " ticks)"));
            }
            cleanup.run();
            helper.assertTrue(took <= MAX_TICKS, Component.literal("the end state held, but only after " + took
                    + " ticks — the contract is within one tick: " + why.get()));
        });
    }

    // ── the watch is always the current watch ─────────────────────────────────────────────────

    /**
     * <b>An old watch in slot 1 becomes the current one, once, and that is not an undo</b>.
     * A child who already HAS Moddy holds a watch minted by an older jar; the pin
     * never re-mints a watch that is home, so without this every change to the watch reached only
     * new children. Own batch: it reads two global counters.
     */
    @GameTest(template = "empty_test", batch = "watchpin_refresh", timeoutTicks = 100)
    public static void anOldWatchBecomesTheCurrentOneOnceAndNothingIsUndone(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "refresh");
        ItemStack old = VanillaWatchItem.stack();
        // An older jar's watch: the same marks, a different shape (no one-click stamp, other lore).
        com.moddingfromamod.compat.Consumables.unstamp(old);
        old.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                        net.minecraft.network.chat.Component.literal("an older watch"))));
        p.getInventory().setItem(WatchPin.HOME, old);
        int refreshed0 = WatchPinService.refreshed();
        int undone0 = WatchPinService.undone();
        WatchPinService.pin(p, null);
        ItemStack home = p.getInventory().getItem(WatchPin.HOME);
        helper.assertTrue(ItemStack.isSameItemSameComponents(home, VanillaWatchItem.stack()),
                Component.literal("the watch in slot 1 is not the current watch: " + home.getComponents()));
        helper.assertTrue(watches(p) == 1, Component.literal("exactly one watch after the refresh: " + state(p)));
        helper.assertTrue(WatchPinService.refreshed() - refreshed0 == 1,
                Component.literal("refreshed " + (WatchPinService.refreshed() - refreshed0) + " times, expected 1"));
        helper.assertTrue(WatchPinService.undone() == undone0,
                Component.literal("the refresh counted as an undo: " + (WatchPinService.undone() - undone0)));
        helper.runAfterDelay(10, () -> {
            try {
                helper.assertTrue(WatchPinService.refreshed() - refreshed0 == 1,
                        Component.literal("the refresh looped: " + (WatchPinService.refreshed() - refreshed0) + " in 10 ticks"));
                helper.assertTrue(WatchPinService.undone() == undone0, Component.literal("something was undone after the refresh"));
                helper.assertTrue(home(p), Component.literal("the watch left slot 1: " + state(p)));
            } finally {
                done(p);
            }
            helper.succeed();
        });
    }

    /** The control: a watch that IS the current one is never touched. Own batch: the counter. */
    @GameTest(template = "empty_test", batch = "watchpin_refresh_control", timeoutTicks = 100)
    public static void aCurrentWatchIsLeftAlone(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "current");
        ItemStack mine = p.getInventory().getItem(WatchPin.HOME);
        int refreshed0 = WatchPinService.refreshed();
        WatchPinService.pin(p, null);
        helper.runAfterDelay(10, () -> {
            try {
                helper.assertTrue(p.getInventory().getItem(WatchPin.HOME) == mine,
                        Component.literal("a current watch was replaced"));
                helper.assertTrue(WatchPinService.refreshed() == refreshed0, Component.literal("a current watch was refreshed"));
            } finally {
                done(p);
            }
            helper.succeed();
        });
    }

    // ── Q ───────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Q.</b> {@code ServerPlayer.drop(false)} is where the drop-item packet lands. NeoForge
     * removes the stack from the inventory BEFORE it asks {@code ItemTossEvent}, and cancelling the
     * event does not give it back — so a cancel alone would delete Moddy.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void qPutsHimBackInSlotOneAndNothingLandsOnTheGround(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(pinned(helper, "mfam-pin-q"));
        long start = helper.getLevel().getGameTime();
        p.drop(false);
        within(helper, start, () -> home(p) && loose(helper.getLevel(), p) == 0,
                () -> "Q: " + state(p) + " loose=" + loose(helper.getLevel(), p), () -> done(p));
    }

    /** Ctrl+Q, and the same in creative (the mode a child mostly plays in). */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void ctrlQInCreativeToo(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "mfam-pin-cq");
        long start = helper.getLevel().getGameTime();
        p.drop(true);
        within(helper, start, () -> home(p) && loose(helper.getLevel(), p) == 0,
                () -> "ctrl+Q: " + state(p), () -> done(p));
    }

    // ── moved out of slot 1 ─────────────────────────────────────────────────────────────────

    /** A drag or a number key leaves him in slot 5 and bread in slot 1: they swap back. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void movedToAnotherSlotHeSwapsBack(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "mfam-pin-move");
        long start = helper.getLevel().getGameTime();
        p.getInventory().setItem(5, p.getInventory().getItem(0));
        p.getInventory().setItem(0, new ItemStack(Items.BREAD, 3));
        within(helper, start, () -> home(p) && p.getInventory().getItem(5).is(Items.BREAD)
                        && p.getInventory().getItem(5).getCount() == 3,
                () -> "moved: " + state(p), () -> done(p));
    }

    /**
     * <b>Thrown out of the inventory screen, and no ghost left on the cursor.</b> The two packets a
     * stock client sends — pick him up from slot 1, click outside the window — through the real
     * packet handler. The throw fires the toss INSIDE the container-click handler, which then
     * clears the cursor and records the client's own prediction (empty) as what it holds; a resync
     * sent from inside the toss went out with the cursor not yet cleared, told the client it still
     * carried a watch, and nothing corrected it — every number-key swap after it was dead. Found by
     * the stock-client film (drag_out, then number_key), which is how a mock-only test missed it:
     * this asserts the wire, not the server's state.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void throwingHimOutOfTheInventoryLeavesNoGhostOnTheCursor(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(pinned(helper, "mfam-pin-ghost"));
        var channel = p.connection.getConnection().channel();
        helper.assertTrue(channel instanceof io.netty.channel.embedded.EmbeddedChannel,
                Component.literal("cannot see the wire: " + channel));
        var wire = (io.netty.channel.embedded.EmbeddedChannel) channel;
        java.util.List<Object> sent = new java.util.ArrayList<>();
        java.util.function.Consumer<Boolean> drain = keep -> {
            Object o;
            while ((o = wire.readOutbound()) != null) {
                if (keep) {
                    sent.add(o);
                }
            }
        };
        drain.accept(false);
        var menu = p.inventoryMenu;
        int hotbar0 = 36;                         // InventoryMenu's slot for hotbar slot 1
        // since 1.21.5 a click carries HASHES of the stacks the client predicts, not the stacks
        // (ServerPlayer's own: the component's value encoded through CRC32C hash ops)
        var hashOps = p.registryAccess().createSerializationContext(net.minecraft.util.HashOps.CRC32C_INSTANCE);
        net.minecraft.network.HashedPatchMap.HashGenerator hasher =
                typed -> typed.encodeValue(hashOps).getOrThrow().asInt();
        var picked = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<net.minecraft.network.HashedStack>();
        picked.put(hotbar0, net.minecraft.network.HashedStack.EMPTY);
        p.connection.handleContainerClick(new net.minecraft.network.protocol.game
                .ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) hotbar0, (byte) 0,
                net.minecraft.world.inventory.ClickType.PICKUP, picked,
                net.minecraft.network.HashedStack.create(VanillaWatchItem.stack(), hasher)));
        p.connection.handleContainerClick(new net.minecraft.network.protocol.game
                .ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) -999, (byte) 0,
                net.minecraft.world.inventory.ClickType.PICKUP,
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY));
        long start = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            drain.accept(true);
            long took = helper.getLevel().getGameTime() - start;
            helper.assertTrue(took >= 3, Component.literal("collecting three ticks of packets"));
            try {
                int ghosts = 0;
                for (Object o : sent) {
                    if (o instanceof net.minecraft.network.protocol.game
                            .ClientboundContainerSetContentPacket c
                            && VanillaWatchItem.isWatch(c.carriedItem())) {
                        ghosts++;
                    }
                }
                helper.assertTrue(ghosts == 0, Component.literal("the server told the client " + ghosts + " time(s) "
                        + "that a thrown watch was still on its cursor — a ghost that kills every "
                        + "number-key swap after it"));
                helper.assertTrue(home(p) && loose(helper.getLevel(), p) == 0,
                        Component.literal("thrown out of the inventory: " + state(p)));
            } finally {
                done(p);
            }
        });
    }

    /** F: into the offhand, a torch into slot 1. They swap back. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void offhandSwapIsUndone(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(pinned(helper, "mfam-pin-f"));
        p.getInventory().setItem(0, new ItemStack(Items.TORCH, 5));
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, VanillaWatchItem.stack());
        long start = helper.getLevel().getGameTime();
        within(helper, start, () -> home(p) && p.getOffhandItem().is(Items.TORCH),
                () -> "offhand: " + state(p), () -> done(p));
    }

    /** Held on the cursor with a screen open: allowed; nothing is minted meanwhile. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void heldOnTheCursorIsHisUntilHeLetsGo(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(pinned(helper, "mfam-pin-cur"));
        p.containerMenu.setCarried(p.getInventory().getItem(0));
        p.getInventory().setItem(0, ItemStack.EMPTY);
        AtomicLong released = new AtomicLong(-1);
        helper.succeedWhen(() -> {
            long now = helper.getLevel().getGameTime();
            if (released.get() < 0) {
                for (int i = 0; i < VanillaWatchItem.TOP_UP_TICKS; i++) {
                    VanillaWatchItem.onServerTick(new net.neoforged.neoforge.event.tick
                            .ServerTickEvent.Post(() -> true, p.getServer()));
                }
                helper.assertTrue(watches(p) == 1, Component.literal("a watch on the cursor is his watch — a second "
                        + "was minted while he held it: " + state(p)));
                // Let go of it by closing the screen, as a child does: vanilla puts the carried
                // stack back into the inventory.
                p.closeContainer();
                released.set(now);
            }
            try {
                helper.assertTrue(home(p), Component.literal("after letting go: " + state(p)));
                helper.assertTrue(now - released.get() <= MAX_TICKS,
                        Component.literal("took " + (now - released.get()) + " ticks"));
            } finally {
                if (home(p) || now - released.get() > 80) {
                    done(p);
                }
            }
        });
    }

    // ── stored ──────────────────────────────────────────────────────────────────────────────

    /** Into a chest: nothing minted while it is open; back in slot 1 the moment it closes. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aWatchPutInAChestComesBackWhenItCloses(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(pinned(helper, "mfam-pin-chest"));
        BlockPos rel = new BlockPos(2, 1, 2);
        helper.setBlock(rel, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(rel));
        // Beside it: a chest menu more than 8 blocks away fails stillValid and vanilla closes it on
        // the next tick — which the pin then (correctly) answers, and the test measures nothing.
        BlockPos stand = helper.absolutePos(rel.east());
        p.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        p.openMenu(chest);
        chest.setItem(0, p.getInventory().getItem(0));
        p.getInventory().setItem(0, ItemStack.EMPTY);
        long opened = helper.getLevel().getGameTime();
        AtomicLong closed = new AtomicLong(-1);
        helper.succeedWhen(() -> {
            long now = helper.getLevel().getGameTime();
            if (closed.get() < 0) {
                helper.assertTrue(now - opened >= 5, Component.literal("holding the chest open for 5 ticks"));
                // A whole cycle of the 5-second top-up while it is open, so the row does not depend
                // on where its timer falls: it minted a duplicate here until it asked holdsOne.
                for (int i = 0; i < VanillaWatchItem.TOP_UP_TICKS; i++) {
                    VanillaWatchItem.onServerTick(new net.neoforged.neoforge.event.tick
                            .ServerTickEvent.Post(() -> true, p.getServer()));
                }
                helper.assertTrue(watches(p) == 0 && VanillaWatchItem.isWatch(chest.getItem(0)),
                        Component.literal("while the chest is open the watch stays where he put it, and no second one "
                                + "is minted: " + state(p)));
                p.closeContainer();
                closed.set(now);
            }
            helper.assertTrue(home(p) && chest.getItem(0).isEmpty(),
                    Component.literal("closed: " + state(p) + " chest[0]=" + chest.getItem(0)));
            helper.assertTrue(now - closed.get() <= MAX_TICKS, Component.literal("took " + (now - closed.get())));
            done(p);
        });
    }

    /** An item frame takes a held item with no container to close: refused. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void anItemFrameCannotTakeHim(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(pinned(helper, "mfam-pin-frame"));
        BlockPos wall = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        ItemFrame frame = new ItemFrame(helper.getLevel(), wall.north(), Direction.NORTH);
        helper.getLevel().addFreshEntity(frame);
        long start = helper.getLevel().getGameTime();
        p.interactOn(frame, InteractionHand.MAIN_HAND);
        within(helper, start, () -> home(p) && frame.getItem().isEmpty(),
                () -> "frame: " + state(p) + " frame=" + frame.getItem(), () -> {
                    frame.discard();
                    done(p);
                });
    }

    // ── commands ────────────────────────────────────────────────────────────────────────────

    private static void run(ServerPlayer p, String command) {
        p.getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void clearIsRestoredWithinATick(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "mfam-pin-clear");
        long start = helper.getLevel().getGameTime();
        run(p, "clear @s");
        within(helper, start, () -> home(p), () -> "/clear: " + state(p), () -> done(p));
    }

    /** {@code /item replace} over slot 1: the watch returns; the diamond moves to a free slot. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void itemReplaceOverSlotOneIsRestored(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "mfam-pin-item");
        long start = helper.getLevel().getGameTime();
        run(p, "item replace entity @s hotbar.0 with minecraft:diamond 2");
        within(helper, start, () -> home(p) && p.getInventory().countItem(Items.DIAMOND) == 2,
                () -> "/item replace: " + state(p), () -> done(p));
    }

    /**
     * <b>{@code /item replace} from a WISH</b>: the agent's real {@code run_command}, on a wish of
     * his, from a worker thread (every tool hops onto the server thread and refuses a re-entrant
     * hop). Its own batch: it binds a run on {@code Marshal.global()}.
     *
     * <p>{@code /clear} is the other half of the contract's row. It was refused by {@code CommandGuard}
     * opened it ("clear my inventory"), and this row said so: now the wish's
     * {@code clear} RUNS, and the watch must be back in slot 1 afterwards like any typed /clear
     * ({@link #clearIsRestoredWithinATick}).
     */
    @GameTest(template = "empty_test", batch = "watchpin_run_command", timeoutTicks = 200)
    public static void aWishsRunCommandCannotTakeHimEither(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "mfam-pin-wish");
        var server = helper.getLevel().getServer();
        Wish wish = WishBook.get(server).add(Wish.create("clear my inventory", WishSource.TYPED,
                System.currentTimeMillis(), p.getUUID(), "Steve", "minecraft:overworld", 0, 64, 0));
        com.moddingfromamod.marshal.Marshal.global().submit(wish.id(), p.getUUID(), wish.text());
        String worker = "gt-watchpin-wish";
        com.moddingfromamod.marshal.Marshal.global().assign(worker, wish.id());
        java.util.concurrent.atomic.AtomicReference<String> said =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicLong ranAt = new AtomicLong(-1);
        Thread t = new Thread(() -> said.set(com.moddingfromamod.agent.AgentContext.with(worker,
                () -> {
                    com.google.gson.JsonObject args = new com.google.gson.JsonObject();
                    com.google.gson.JsonArray cmds = new com.google.gson.JsonArray();
                    cmds.add("clear @p");
                    args.add("command", cmds);
                    var cleared = new com.moddingfromamod.agent.tools.RunCommandTool().call(args);
                    com.google.gson.JsonObject args2 = new com.google.gson.JsonObject();
                    com.google.gson.JsonArray cmds2 = new com.google.gson.JsonArray();
                    cmds2.add("item replace entity @p hotbar.0 with minecraft:stick");
                    args2.add("command", cmds2);
                    var r = new com.moddingfromamod.agent.tools.RunCommandTool().call(args2);
                    ranAt.set(helper.getLevel().getGameTime());
                    return (cleared == null ? "null" : cleared.text()) + " || "
                            + (r == null ? "null" : r.text());
                })), "watchpin-run-command");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(said.get() != null, Component.literal("waiting for run_command"));
            long took = helper.getLevel().getGameTime() - ranAt.get();
            boolean ok = false;
            try {
                helper.assertTrue(said.get().startsWith("Ran: clear"),
                        Component.literal("a wish's /clear runs since #1602 (\"clear my inventory\"): " + said.get()));
                helper.assertTrue(said.get().contains("Ran: item replace"), Component.literal("run_command did not "
                        + "run the /item replace: " + said.get()));
                helper.assertTrue(home(p) && p.getInventory().countItem(Items.STICK) == 1,
                        Component.literal("after the wish's /item replace: " + state(p) + " (" + took + " ticks)"));
                helper.assertTrue(took <= MAX_TICKS, Component.literal("took " + took + " ticks"));
                ok = true;
            } finally {
                // Only on success or giving up: the first look can land before the pin's tick.
                if (ok || took > 60) {
                    com.moddingfromamod.marshal.Marshal.global().finish(wish.id());
                    BuildGate.settle(wish.id());
                    done(p);
                }
            }
        });
    }

    /**
     * <b>Full inventory, slot 1 taken, no watch.</b> The watch wins slot 1; what was there is
     * dropped at his feet — never destroyed (owner).
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aFullInventoryDropsTheDisplacedItemAtHisFeet(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(pinned(helper, "mfam-pin-full"));
        for (int i = 0; i < WatchPin.MAIN_END; i++) {
            p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        p.getInventory().setItem(0, new ItemStack(Items.GOLDEN_APPLE, 1));
        long start = helper.getLevel().getGameTime();
        BooleanSupplier dropped = () -> !helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(p.blockPosition()).inflate(4),
                e -> e.getItem().is(Items.GOLDEN_APPLE)).isEmpty();
        within(helper, start, () -> home(p) && dropped.getAsBoolean()
                        && p.getInventory().countItem(Items.COBBLESTONE) == 35 * 64,
                () -> "full: " + state(p) + " droppedApple=" + dropped.getAsBoolean(), () -> {
                    helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                            new AABB(p.blockPosition()).inflate(4)).forEach(Entity::discard);
                    done(p);
                });
    }

    // ── copies and loose watches ────────────────────────────────────────────────────────────

    /** Middle-click copies in creative: one watch stays, in slot 1. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void copiesAreRemoved(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "mfam-pin-dup");
        p.getInventory().setItem(3, VanillaWatchItem.stack());
        p.getInventory().setItem(20, VanillaWatchItem.stack());
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, VanillaWatchItem.stack());
        long start = helper.getLevel().getGameTime();
        within(helper, start, () -> home(p), () -> "copies: " + state(p), () -> done(p));
    }

    /** A watch item entity reaching the world by any route is refused. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aLooseWatchNeverLands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 2, 1.5));
        ItemEntity loose = new ItemEntity(level, at.x, at.y, at.z, VanillaWatchItem.stack());
        level.addFreshEntity(loose);
        long start = level.getGameTime();
        // ONE verdict, taken on the next tick and kept: the cleanup discards the entity, so a
        // retry that looked again would pass on its own tidy-up.
        java.util.concurrent.atomic.AtomicReference<Boolean> landed = new java.util.concurrent
                .atomic.AtomicReference<>();
        helper.succeedWhen(() -> {
            if (landed.get() == null) {
                helper.assertTrue(level.getGameTime() > start, Component.literal("waiting one tick"));
                landed.set(!level.getEntitiesOfClass(ItemEntity.class,
                        new AABB(at, at).inflate(4), e -> VanillaWatchItem.isWatch(e.getItem()))
                        .isEmpty());
                loose.discard();
            }
            helper.assertFalse(landed.get(), Component.literal("a loose Moddy watch landed in the world for any "
                    + "child to pick up"));
        });
    }

    // ── death ───────────────────────────────────────────────────────────────────────────────

    private static void deathTest(GameTestHelper helper, String name, boolean keep) {
        ServerLevel level = helper.getLevel();
        var rule = level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean was = rule.get();
        rule.set(keep, level.getServer());
        ServerPlayer p = MockPlayerNetwork.asSurvival(pinned(helper, name));
        p.getInventory().setItem(3, new ItemStack(Items.DIRT, 5));
        var where = p.blockPosition();
        p.die(p.damageSources().generic());
        int looseAtDeath = level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(8),
                e -> VanillaWatchItem.isWatch(e.getItem())).size();
        boolean dirtDropped = !level.getEntitiesOfClass(ItemEntity.class,
                new AABB(where).inflate(8), e -> e.getItem().is(Items.DIRT)).isEmpty();
        ServerPlayer reborn = p.getServer().getPlayerList().respawn(p, false,
                Entity.RemovalReason.KILLED);
        long start = level.getGameTime();
        within(helper, start, () -> looseAtDeath == 0 && home(reborn)
                        && dirtDropped == !keep,
                () -> "keepInventory=" + keep + ": looseAtDeath=" + looseAtDeath + " dirtDropped="
                        + dirtDropped + " reborn=" + state(reborn),
                () -> {
                    rule.set(was, level.getServer());
                    level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(8))
                            .forEach(Entity::discard);
                    done(reborn);
                });
    }

    @GameTest(template = "empty_test", batch = "watchpin_death_drop", timeoutTicks = 100)
    public static void deathWithoutKeepInventoryNeverDropsHim(GameTestHelper helper) {
        deathTest(helper, "mfam-pin-die", false);
    }

    @GameTest(template = "empty_test", batch = "watchpin_death_keep", timeoutTicks = 100)
    public static void deathWithKeepInventoryKeepsHimInSlotOne(GameTestHelper helper) {
        deathTest(helper, "mfam-pin-keep", true);
    }

    // ── who it applies to ───────────────────────────────────────────────────────────────────

    /** Every mode but spectator: adventure too. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void adventureModeIsPinnedToo(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "mfam-pin-adv");
        p.setGameMode(GameType.ADVENTURE);
        p.getInventory().setItem(8, p.getInventory().getItem(0));
        p.getInventory().setItem(0, ItemStack.EMPTY);
        long start = helper.getLevel().getGameTime();
        within(helper, start, () -> home(p), () -> "adventure: " + state(p), () -> done(p));
    }

    /** The wish camera is never pinned (PlayerKind): its hotbar is nobody's. */
    @GameTest(template = "empty_test", batch = "watchpin_camera", timeoutTicks = 100)
    public static void theWishCameraIsLeftAlone(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "mfam-pin-cam");
        com.moddingfromamod.marshal.PlayerKind.registerServiceClient(p.getUUID());
        p.getInventory().setItem(6, p.getInventory().getItem(0));
        p.getInventory().setItem(0, new ItemStack(Items.BREAD));
        long start = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getGameTime() - start >= 10, Component.literal("waiting 10 ticks"));
            try {
                helper.assertTrue(p.getInventory().getItem(0).is(Items.BREAD)
                                && VanillaWatchItem.isWatch(p.getInventory().getItem(6)),
                        Component.literal("the camera's inventory was rearranged: " + state(p)));
            } finally {
                com.moddingfromamod.marshal.PlayerKind.forgetServiceClient(p.getUUID());
                done(p);
            }
        });
    }

    // ── no regressions: the zero-typing door ────────────────────────────────────────────────

    /**
     * Right-clicking the pinned watch still opens Moddy's panel, the panel stays open while the pin
     * runs (its COMPOSE cell IS a watch stack — an icon, not a stored watch), and closing it leaves
     * exactly one watch, in slot 1.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void rightClickStillOpensThePanelAndThePinLeavesItAlone(GameTestHelper helper) {
        ServerPlayer p = pinned(helper, "mfam-pin-panel");
        int boxes = com.moddingfromamod.wish.WishCompose.openedCount(p.getUUID());
        MockPlayerNetwork.withoutChannels(p, () -> p.gameMode.useItem(p, p.level(),
                p.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND));
        // (on by default, 2026-11-16): the click opens the text-first wish box; the CHEST the pin must leave
        // alone is now "📚 My wishes", held open below.
        helper.assertTrue(com.moddingfromamod.wish.WishCompose.openedCount(p.getUUID()) > boxes,
                Component.literal("right-clicking the pinned watch opened nothing"));
        com.moddingfromamod.wish.WatchMenu.openLibrary(p);
        long start = helper.getLevel().getGameTime();
        AtomicLong closed = new AtomicLong(-1);
        helper.succeedWhen(() -> {
            long now = helper.getLevel().getGameTime();
            if (closed.get() < 0) {
                helper.assertTrue(p.containerMenu != p.inventoryMenu,
                        Component.literal("right-clicking the pinned watch opened nothing"));
                helper.assertTrue(now - start >= 5, Component.literal("holding the panel open for 5 ticks"));
                helper.assertTrue(home(p), Component.literal("the pin moved something while the panel was open: "
                        + state(p)));
                p.closeContainer();
                closed.set(now);
            }
            try {
                helper.assertTrue(home(p), Component.literal("after closing the panel: " + state(p)));
            } finally {
                done(p);
            }
        });
    }

    // ── what it costs ───────────────────────────────────────────────────────────────────────

    /**
     * <b>The per-tick scan, measured.</b> Steady state is the case that runs every tick for every
     * child: the watch already home, a full bag (41 slots to look at, 36 of them occupied). Timed
     * beside main's only scan, {@code VanillaWatchItem.has}, which the top-up runs per player every
     * 100 ticks. The bound is 50 µs per child per tick, 0.1% of a 50 ms tick; the numbers are
     * printed for the PR, not tuned against.
     */
    /** main's {@code has()} as it shipped before the pin: {@code copyTag()} for every stack. */
    private static int mainScan(ServerPlayer p) {
        for (int i = 0; i < WatchPin.SLOTS; i++) {
            var data = p.getInventory().getItem(i).get(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if (data != null && data.copyTag().getBooleanOr(com.moddingfromamod.item.WishItem.WATCH, false)) {
                return 1;
            }
        }
        return 0;
    }

    @GameTest(template = "empty_test", batch = "watchpin_perf", timeoutTicks = 200)
    public static void thePerTickScanCostsNothingMeasurable(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(pinned(helper, "mfam-pin-perf"));
        try {
            for (int i = 1; i < WatchPin.MAIN_END; i++) {
                p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            int n = 50_000;
            for (int i = 0; i < n; i++) {           // warm the JIT on both paths
                WatchPinService.pin(p, null);
                VanillaWatchItem.has(p);
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                WatchPinService.pin(p, null);
            }
            long pinNs = (System.nanoTime() - t0) / n;
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                VanillaWatchItem.has(p);
            }
            long hasNs = (System.nanoTime() - t0) / n;
            // main's scan exactly as it shipped: isWatch copied the whole tag for every stack.
            int seen = 0;
            for (int i = 0; i < n; i++) {
                seen += mainScan(p);
            }
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                seen += mainScan(p);
            }
            long mainNs = (System.nanoTime() - t0) / n;
            System.out.println("WATCHPIN_PERF after_pin_ns_per_player_tick=" + pinNs
                    + " after_has_ns_per_scan=" + hasNs
                    + " before_main_scan_ns=" + mainNs + " before_main_ns_per_player_tick="
                    + mainNs / VanillaWatchItem.TOP_UP_TICKS + " slots=41 occupied=37 (" + seen
                    + ")");
            helper.assertTrue(home(p), Component.literal("the probe itself moved the watch: " + state(p)));
            helper.assertTrue(pinNs < 50_000, Component.literal("the pin costs " + pinNs + " ns per child per tick, "
                    + "over 0.1% of a 50 ms tick"));
            helper.succeed();
        } finally {
            done(p);
        }
    }
}
