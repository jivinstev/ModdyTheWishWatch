package com.moddingfromamod.bag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Every child's Wish Bag on this world, in memory</b>.
 *
 * <p>A bag is a list of stacks, NEWEST FIRST. It is loaded from his shelf the first time anything
 * asks for it ({@link WishBagStore}), and a bag that changed is written at the end of that tick,
 * when he leaves, and when the server stops — so a delivery of forty things is one write, not
 * forty. Server thread only, like the inventory it stands beside.
 *
 * <p>ONE BAG PER WORLD: the file is named by {@link #worldKey}, so one world's bag and
 * a second world's are two files on one shelf and neither server ever sees the other's.
 */
@EventBusSubscriber
public final class WishBagService {

    private static final Logger LOGGER = LoggerFactory.getLogger("mfam-wish-bag");

    private static final class Bag {
        final WishBagStore store;
        final List<ItemStack> entries;
        boolean dirty;

        Bag(WishBagStore store, List<ItemStack> entries) {
            this.store = store;
            this.entries = entries;
        }
    }

    private static final Map<String, Bag> BAGS = new ConcurrentHashMap<>();

    /** A test's world key, or null to derive it from the running world. */
    private static volatile String forcedWorld;

    private WishBagService() {
    }

    /**
     * Which world this is, as a file name: the world's manifest id when it has one, else the world folder's name. Lower-case, {@code [a-z0-9_-]} only.
     */
    public static String worldKey(MinecraftServer server) {
        String forced = forcedWorld;
        if (forced != null) {
            return forced;
        }
        return com.moddingfromamod.world.WorldModuleHost.worldKey(server);
    }

    /** For a GameTest: act as world {@code key} (null restores the real one). Saves and forgets every bag. */
    public static void forceWorldForTest(MinecraftServer server, String key) {
        flushAll(server);
        BAGS.clear();
        forcedWorld = key;
    }

    private static String slot(MinecraftServer server, UUID player) {
        return worldKey(server) + "/" + player;
    }

    private static Bag bag(MinecraftServer server, UUID player) {
        return BAGS.computeIfAbsent(slot(server, player), k -> {
            WishBagStore store = new WishBagStore(
                    com.moddingfromamod.runtime.RuntimeConfig.playersDir(), player.toString(),
                    worldKey(server));
            return new Bag(store, new ArrayList<>(store.load(server.registryAccess())));
        });
    }

    /** His bag, newest first — copies, so a caller cannot change it behind the store's back. */
    public static List<ItemStack> contents(MinecraftServer server, UUID player) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : bag(server, player).entries) {
            out.add(s.copy());
        }
        return out;
    }

    /** How many entries his bag holds. */
    public static int size(MinecraftServer server, UUID player) {
        return bag(server, player).entries.size();
    }

    /** False when his bag's file was refused (T9 latch) — nothing can go in until a grown-up looks. */
    public static boolean usable(MinecraftServer server, UUID player) {
        return bag(server, player).store.usable();
    }

    /**
     * Put a stack in, newest first: it tops up an existing entry of the same thing (which then
     * moves to the front), and whatever is left becomes new entries at the front. The stack is
     * emptied by what went in.
     *
     * @return how many went in; fewer than the stack's count only at the backstop or a refused file
     */
    public static int add(MinecraftServer server, UUID player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        Bag b = bag(server, player);
        if (!b.store.usable()) {
            return 0;
        }
        int before = stack.getCount();
        int max = stack.getMaxStackSize();
        for (int i = 0; i < b.entries.size() && !stack.isEmpty(); i++) {
            ItemStack e = b.entries.get(i);
            if (e.getCount() < max && ItemStack.isSameItemSameComponents(e, stack)) {
                int n = Math.min(max - e.getCount(), stack.getCount());
                e.grow(n);
                stack.shrink(n);
                b.entries.remove(i);
                b.entries.add(0, e);
            }
        }
        List<ItemStack> fresh = new ArrayList<>();
        while (!stack.isEmpty() && WishBagPages.hasRoom(b.entries.size() + fresh.size(), 1)) {
            fresh.add(stack.split(Math.min(max, stack.getCount())));
        }
        java.util.Collections.reverse(fresh);    // the last split is no newer than the first
        b.entries.addAll(0, fresh);
        int in = before - stack.getCount();
        if (in > 0) {
            b.dirty = true;
            reshow(server, player);
        }
        if (!stack.isEmpty()) {
            LOGGER.error("MODDY-ALERT {}'s Wish Bag is at its backstop ({} pages); {} could not go in. "
                    + "A source is handing over without end (#1351)", player, WishBagPages.MAX_PAGES, stack);
        }
        return in;
    }

    /**
     * Take up to {@code count} of {@code like} (same item, same components) out of his bag, newest
     * first. For an undo. Returns how many were taken.
     */
    public static int take(MinecraftServer server, UUID player, ItemStack like, int count) {
        Bag b = bag(server, player);
        int left = count;
        for (int i = 0; i < b.entries.size() && left > 0; i++) {
            ItemStack e = b.entries.get(i);
            if (ItemStack.isSameItemSameComponents(e, like)) {
                int n = Math.min(left, e.getCount());
                e.shrink(n);
                left -= n;
                if (e.isEmpty()) {
                    b.entries.remove(i--);
                }
            }
        }
        if (left < count) {
            b.dirty = true;
            reshow(server, player);
        }
        return count - left;
    }

    /**
     * An open bag shows the list it was laid from; a delivery or an undo while it is open moves
     * every entry along one, so the page is re-laid at once — or his next click would be read
     * against the wrong cells.
     */
    private static void reshow(MinecraftServer server, UUID player) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null && p.containerMenu instanceof WishBagMenu m) {
            m.showPage(m.page());
        }
    }

    /** Replace his bag with {@code entries} (newest first) — what the bag's chest does after a click. */
    static void replace(MinecraftServer server, UUID player, List<ItemStack> entries) {
        Bag b = bag(server, player);
        b.entries.clear();
        for (ItemStack s : entries) {
            if (s != null && !s.isEmpty()) {
                b.entries.add(s);
            }
        }
        b.dirty = true;
    }

    /** Empty his bag on this world (a test's clean start). */
    public static void clearForTest(MinecraftServer server, UUID player) {
        Bag b = bag(server, player);
        b.entries.clear();
        b.dirty = true;
        save(server, b);
    }

    /** Write every changed bag now. */
    public static void flushAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (Bag b : BAGS.values()) {
            if (b.dirty) {
                save(server, b);
            }
        }
    }

    /** Step 8: save every bag, then forget them all, so a re-key's moved files are read next. */
    public static void flushAndForget(MinecraftServer server) {
        reloadForTest(server);
    }

    /** Forget every loaded bag, saving first — the next ask reads the file again. */
    public static void reloadForTest(MinecraftServer server) {
        flushAll(server);
        BAGS.clear();
    }

    private static void save(MinecraftServer server, Bag b) {
        if (b.store.save(b.entries, server.registryAccess())) {
            b.dirty = false;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        flushAll(event.getServer());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            MinecraftServer server = p.level().getServer();
            Bag b = BAGS.remove(slot(server, p.getUUID()));
            if (b != null && b.dirty) {
                save(server, b);
            }
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        flushAll(event.getServer());
        BAGS.clear();
        forcedWorld = null;
    }
}
