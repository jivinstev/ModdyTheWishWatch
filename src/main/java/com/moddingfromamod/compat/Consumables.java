package com.moddingfromamod.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;

/** Is this a thing he eats or drinks? 1.21.2 made that one component; see the mc21 half. */
public final class Consumables {
    private Consumables() {
    }

    public static boolean isConsumed(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.CONSUMABLE);
    }

    /**
     * Long enough never to finish: 11.5 days held. Also far past where vanilla starts the eating
     * sounds and particles ({@code Consumable.shouldEmitParticlesAndSounds}: after 21.875% of the
     * duration), and small enough that {@code consumeSeconds * 20} stays an int.
     */
    public static final float ENDLESS_SECONDS = 1_000_000.0F;

    /** Does {@link #stopsTheClick} do anything on this Minecraft? (1.21.1: no.) */
    public static final boolean STAMPS = true;

    /** The one stamp, built once. */
    private static final Consumable STAMP = Consumable.builder()
            .consumeSeconds(ENDLESS_SECONDS)
            .animation(ItemUseAnimation.NONE)
            .sound(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.EMPTY))
            .hasConsumeParticles(false)
            .build();

    /**
     * <b>Make a stock client's right-click STOP at this stack.</b>
     *
     * <p>A stock client decides for itself whether a right-click used the main hand, and only a
     * Success stops {@code Minecraft.startUseItem} from going on to the OFFHAND. Leather horse
     * armour's own use is PASS for a player, so every click on Moddy also used his offhand — the
     * owner's backpack emptied onto the ground. {@code Item.use} asks {@code consumable} FIRST and
     * {@code Consumable.startConsuming} returns CONSUME, so with this component the client stops at
     * the main hand. It never finishes (see {@link #ENDLESS_SECONDS}), draws nothing
     * ({@code animation: none}), makes no sound or particles (filmed with the game's audio: -91 dB),
     * and the server never starts the use at all (the watch's click is cancelled before
     * {@code Item.use}). The one visible trace is vanilla's "item used" bob of the watch in his
     * hand, for the round trip before the panel covers it.
     *
     * <p>1.21.1 has no {@code consumable} component: the mc21 half does nothing (the compat build
     * keeps the old behaviour), and ViaVersion drops the component for a 1.21.1 client behind a
     * version-translating proxy, which is not a supported client for this. Only Moddy is stamped: a wished carrier
     * whose use does nothing on the server would really start being eaten there.
     */
    public static void stopsTheClick(ItemStack stack) {
        stack.set(DataComponents.CONSUMABLE, STAMP);
    }


    /** Undo {@link #stopsTheClick}: the control arm of the stock-client film (watchpin.nostamp). */
    public static void unstamp(ItemStack stack) {
        stack.remove(DataComponents.CONSUMABLE);
    }
}
