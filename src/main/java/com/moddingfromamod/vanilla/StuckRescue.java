package com.moddingfromamod.vanilla;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.SafeSpot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A player taking damage inside blocks is moved out instead</b> — the backstop under every
 * other placement rule. A wish built a volcano on a child's home spot and he suffocated on every
 * respawn; {@link SafePlacement#clearForBuild} and {@link SafePlacement#safeHome} now prevent the two
 * ways that happened, and this catches the ones they cannot see: a home in a chunk that was not
 * loaded when he respawned, a datapack's blocks, falling sand, another player's build, a rejoin
 * inside something built while he was away. Nothing a child wishes for is "suffocate in a wall".
 *
 * <p>Only {@code in_wall} damage, only a player, and only when somewhere near is safe — else
 * vanilla's damage stands, exactly as before. Survival only, in effect: Creative takes none.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class StuckRescue {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-safespot");

    private StuckRescue() {
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isSpectator()
                || !event.getSource().is(DamageTypes.IN_WALL)) {
            return;
        }
        SafeSpot.Spot from = new SafeSpot.Spot(p.getX(), p.getY(), p.getZ());
        SafeSpot.Spot to = SafePlacement.rescueSpot(p);
        if (to == null) {
            return;
        }
        event.setCanceled(true);
        SafePlacement.movePlayer(p, to, from, SafeSpot.YouWhy.STUCK);
        LOG.info("[safespot] player {} was suffocating at {} -> moved to {}", p.getScoreboardName(),
                SafePlacement.fmt(from), SafePlacement.fmt(to));
    }
}
