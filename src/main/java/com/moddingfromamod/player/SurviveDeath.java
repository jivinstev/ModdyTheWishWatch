package com.moddingfromamod.player;

import java.util.List;
import java.util.Set;

import com.moddingfromamod.ModdingFromAModMod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * <b>"Make me run twice as fast FOREVER" — and forever includes after he dies.</b>
 *
 * <p>{@code modify_player} has always granted its powers as INFINITE effects and told the agent
 * they "last until they are taken away". They did not: vanilla builds a new body on respawn and
 * copies no effect onto it after a death ({@code ServerPlayer.restoreFrom} copies effects only on
 * the End-exit path). So every forever power ended the first time the child fell in lava — the
 * moment a fireproof power exists for. The same was true of {@code effect give @s … infinite}
 * written with {@code run_command}.
 *
 * <p>This carries every infinite effect across a death. Nothing else: a thirty-second effect still
 * ends when he dies, exactly as vanilla has it. The rule is the duration, not the source, which is
 * why it is right for both paths — vanilla itself never makes an infinite effect (beacons, conduits
 * and omens are all finite), so an infinite one is always something somebody asked for on purpose.
 *
 * <p><b>Attributes need nothing.</b> Read off both targets' source: {@code restoreFrom} calls
 * {@code assignBaseValues} on every respawn, so "giant", "no fall damage" and "can't be knocked
 * back" written as attribute base values already survive a death. Only effects were lost.
 *
 * <p><b>Why two events, not one.</b> The obvious design copies the effects in
 * {@link PlayerEvent.Clone}, and it does not work: {@code PlayerList.respawn} first removes the old
 * body with {@code KILLED}, and {@code LivingEntity.remove} runs {@code triggerOnDeathMobEffects},
 * which CLEARS its effects — before {@code restoreFrom} fires the Clone. The old body is empty by
 * then. (Found by {@code SurviveDeathGameTest}, which does a real respawn; the one-event version
 * compiled, read correctly and carried nothing.) So the effects are written down at
 * {@link LivingDeathEvent}, while he still has them, into the persisted tag NeoForge copies onto
 * the new body, and read back at the Clone. Written down rather than held in memory, a death
 * survives him logging out before he presses Respawn.
 *
 * <p>The Clone is the right moment to read them: effects added then are sent to the client by
 * {@code PlayerList.sendActivePlayerEffects}, which runs AFTER the respawn packet, so the icons
 * arrive on the new body rather than the discarded one.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class SurviveDeath {

    /**
     * The part this class IS, in the parts inventory (<code>rule.mechanism:survive_death</code>).
     * Read by {@code tools/parts-coverage.py}, so deleting this class takes the part out of the
     * inventory and {@code --check} names every wish that regresses.
     */
    public static final Set<String> PARTS = Set.of("survive_death");

    /** Under the vanilla-persisted tag, so NeoForge copies it onto the new body. */
    private static final String KEY = "mfam_forever_effects";

    private SurviveDeath() {
    }

    /**
     * LOWEST and not for a cancelled death: {@link NeverDie} cancels a death it prevents, and a
     * death that did not happen must not leave a stash behind for the next real one.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player) {
            remember(player);
        }
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.isWasDeath()) {
            restore(event.getEntity());
        }
    }

    /**
     * Write down every effect he has that was meant to last forever.
     *
     * @return how many
     */
    public static int remember(Player dying) {
        ListTag kept = new ListTag();
        for (MobEffectInstance e : List.copyOf(dying.getActiveEffects())) {
            if (PlayerPowers.lastsThroughDeath(e.getDuration())) {
                kept.add(com.moddingfromamod.compat.EntityNbt.saveEffect(e));
            }
        }
        CompoundTag persisted = dying.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        if (kept.isEmpty()) {
            persisted.remove(KEY);
        } else {
            persisted.put(KEY, kept);
        }
        dying.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        return kept.size();
    }

    /** Whether a death has been written down and not yet given back. */
    static boolean remembering(Player player) {
        return player.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG).contains(KEY);
    }

    /**
     * Give the new body what {@link #remember} wrote down, once, and forget it.
     *
     * @return how many were given back
     */
    public static int restore(Player reborn) {
        CompoundTag persisted = reborn.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        if (!persisted.contains(KEY)) {
            return 0;
        }
        ListTag kept = persisted.getListOrEmpty(KEY);
        persisted.remove(KEY);
        reborn.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        int given = 0;
        for (int i = 0; i < kept.size(); i++) {
            MobEffectInstance e = com.moddingfromamod.compat.EntityNbt.loadEffect(kept.getCompoundOrEmpty(i));
            if (e != null) {
                reborn.addEffect(e);
                given++;
            }
        }
        return given;
    }
}
