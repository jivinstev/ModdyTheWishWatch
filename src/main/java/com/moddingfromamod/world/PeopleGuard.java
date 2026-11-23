package com.moddingfromamod.world;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * step 4 (docs/OPEN_DOOR_PLAN.md §11.3): on a world whose {@code people.pvp} is {@code false}
 * (a world open to any age), no player hurts another. The source's ENTITY is the one who caused it
 * — the hand that swung, or the owner of the arrow, trident or snowball — so a projectile is covered
 * by the same line. A mob, the void, fall or lava still hurt as ever; so does a player hurting himself.
 *
 * <p>Mod-side rather than {@code server.properties}' {@code pvp}: it rides the jar with the rest of the
 * world's {@code people} rules, and needs no re-render of the install or a restart.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class PeopleGuard {

    private PeopleGuard() {
    }

    /** Whether this hit is one player hurting another where the world says players may not. Pure but for the read. */
    public static boolean refuses(boolean pvp, Entity victim, Entity cause) {
        return !pvp && victim instanceof Player && cause instanceof Player && cause != victim;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        if (refuses(WorldPeople.current().pvp(), event.getEntity(), event.getSource().getEntity())) {
            event.setCanceled(true);
        }
    }
}
