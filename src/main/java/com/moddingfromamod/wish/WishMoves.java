package com.moddingfromamod.wish;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.marshal.WishRun;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;

/**
 * <b>A wish that MOVED its child by command is a wish that took him somewhere</b>.
 *
 * <p>Live, 2026-10-16: <i>"teleport me to a far away plains"</i>. {@code take_me_to} only goes to
 * things he made, so the agent moved him with {@code tp}, about 6,300 blocks — and the reveal was
 * photographed at the spot he had wished FROM, because the photograph's "where the wish took him"
 * pose ({@code PhotographTool.aimAtWishSpot}) switched on {@link WishRun#wentTo}, which only
 * {@code take_me_to} sets. Every other way a wish moves him — {@code tp}/{@code teleport} in
 * {@code run_command}, under {@code execute}, in a button replay, in a datapack function, and
 * {@code spreadplayers} — converges on NeoForge's two command-teleport events, so this listens there
 * and marks the child's own wish ({@link WishRun#movedByCommand}).
 *
 * <p>Lowest priority, and only an event nobody cancelled: a visit {@code PlayerVisits} turned away
 * did not move him. Only a real journey counts ({@link #isJourney}) — the agent nudging him out of a
 * block, or onto the floor of what it just built, is not "the wish took him somewhere".
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WishMoves {

    private WishMoves() {
    }

    /** Further than this (horizontally) is a journey; nearer is a nudge. */
    static final double JOURNEY_BLOCKS = 32;

    /** Pure: is a move from {@code (px, pz)} to {@code (x, z)} a journey? */
    static boolean isJourney(double px, double pz, double x, double z) {
        double dx = x - px;
        double dz = z - pz;
        return dx * dx + dz * dz > JOURNEY_BLOCKS * JOURNEY_BLOCKS;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTeleportCommand(EntityTeleportEvent.TeleportCommand e) {
        moved(e);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSpreadPlayers(EntityTeleportEvent.SpreadPlayersCommand e) {
        moved(e);
    }

    private static void moved(EntityTeleportEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer child)
                || !PlayerKind.isChild(child.getUUID())
                || !isJourney(e.getPrevX(), e.getPrevZ(), e.getTargetX(), e.getTargetZ())) {
            return;
        }
        WishRun run = runFor(child);
        if (run != null) {
            run.movedByCommand();
        }
    }

    /**
     * The wish this move belongs to: the one whose worker is running the command (the agent's own
     * {@code run_command}), else his only wish being worked on. Two at once and no worker bound is
     * not guessed — a photo aimed by a wrong guess is the bug.
     */
    static WishRun runFor(ServerPlayer child) {
        WishRun bound = com.moddingfromamod.agent.AgentContext.currentRun();
        if (bound != null && child.getUUID().equals(bound.playerId())) {
            return bound;
        }
        WishRun only = null;
        for (WishRun r : com.moddingfromamod.marshal.Marshal.global().working()) {
            if (child.getUUID().equals(r.playerId())) {
                if (only != null) {
                    return null;
                }
                only = r;
            }
        }
        return only;
    }
}
