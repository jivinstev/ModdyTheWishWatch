package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The world half of {@link PolishGlint}: put the glint on a polished stack, and take every expired
 * one off wherever it is next seen. Server thread.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class PolishGlintService {

    private PolishGlintService() {
    }

    /** Game tick until which a glint may still be live somewhere: the fast sweep runs till then. */
    private static volatile long liveUntil = Long.MIN_VALUE;

    /** Glint this stack until {@code now + TICKS}. A stack already glinting keeps what it was. */
    public static void apply(ItemStack st, long now) {
        if (st == null || st.isEmpty()) {
            return;
        }
        long until = now + PolishGlint.TICKS;
        CompoundTag held = marker(st);
        String was = held != null ? held.getStringOr("was", "")
                : PolishGlint.was(st.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        CustomData.update(DataComponents.CUSTOM_DATA, st, tag -> {
            CompoundTag m = new CompoundTag();
            m.putLong("until", until);
            m.putString("was", was);
            tag.put(PolishGlint.MARKER, m);
        });
        st.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        liveUntil = Math.max(liveUntil, until);
    }

    /** Does this stack still wear a glint marker? */
    public static boolean glinting(ItemStack st) {
        return marker(st) != null;
    }

    /**
     * Take the glint off this stack if its time is up. Returns whether it changed.
     *
     * @param now the game time; {@link Long#MAX_VALUE} takes it off whatever its time
     */
    public static boolean settle(ItemStack st, long now) {
        CompoundTag m = marker(st);
        if (m == null || !PolishGlint.expired(m.getLongOr("until", 0L), now)) {
            return false;
        }
        Boolean back = PolishGlint.restore(m.getStringOr("was", ""));
        if (back == null) {
            st.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        } else {
            st.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, back);
        }
        CustomData.update(DataComponents.CUSTOM_DATA, st, tag -> tag.remove(PolishGlint.MARKER));
        return true;
    }

    private static CompoundTag marker(ItemStack st) {
        if (st == null || st.isEmpty()) {
            return null;
        }
        CustomData cd = st.get(DataComponents.CUSTOM_DATA);
        if (cd == null || !cd.contains(PolishGlint.MARKER)) {
            return null;
        }
        return cd.copyTag().getCompoundOrEmpty(PolishGlint.MARKER);
    }

    /** Everything this player can see or hold: his inventory, the screen he has open, the cursor. */
    static int sweep(ServerPlayer p, long now) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (settle(inv.getItem(i), now)) {
                n++;
            }
        }
        n += sweep(p.containerMenu, now);
        return n;
    }

    private static int sweep(AbstractContainerMenu menu, long now) {
        if (menu == null) {
            return 0;
        }
        int n = 0;
        for (Slot s : menu.slots) {
            if (settle(s.getItem(), now)) {
                n++;
            }
        }
        if (settle(menu.getCarried(), now)) {
            n++;
        }
        return n;
    }

    private static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    /** Every 2 ticks while a glint may be live, and once a second always (a stack that came back). */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = now(server);
        boolean fast = now <= liveUntil + 2;
        if (!(fast ? now % 2 == 0 : now % 20 == 0)) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sweep(p, now);
        }
    }

    /** A chest, a barrel, his ender chest: a glinting stack put away is put right when it is seen. */
    @SubscribeEvent
    public static void onOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            sweep(event.getContainer(), now(p.level().getServer()));
        }
    }

    /** A server that stopped mid-glint leaves the marker on the stacks it saved. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            sweep(p, Long.MAX_VALUE);
        }
    }
}
