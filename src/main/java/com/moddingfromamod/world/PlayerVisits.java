package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.PlayerReach;
import com.moddingfromamod.agent.VisitCooldown;
import com.moddingfromamod.marshal.PlayerKind;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * <b>A visit, wherever it came from</b> — the RUN-TIME half of {@link PlayerReach}.
 *
 * <p>The compile-time and grant-time checks decide what a command MAY say. This decides what a
 * teleport DOES, at the moment it happens, for every path at once: {@code run_command}, a template's
 * button, a datapack's function compiled today, and one compiled before this rule existed (the live
 * {@code player_tracker} orb). It hangs on NeoForge's {@link EntityTeleportEvent.TeleportCommand},
 * which {@code /tp} and {@code /teleport} fire for every target whatever ran them — a function line
 * included, which no Java of ours otherwise sees.
 *
 * <p>A child moved by a command onto another child's exact position is a VISIT. On a world whose
 * {@code player_reach} is {@code off} it is cancelled with a kind line. Where it is {@code open}, the
 * same visitor reaching the same host inside {@link VisitCooldown#WINDOW_MS} is turned away kindly;
 * otherwise it goes ahead and the host is told {@code "✨ <name> came to visit!"}. Every decision is a
 * {@code reach} row. A camera, or any service client, is never a visitor or a host.
 *
 * <p>Also the names a datapack compile checks against ({@link #knownNames}): the players online,
 * kept here by login and logout because a compile runs off the server thread.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class PlayerVisits {

    /** Within this many blocks of a child's position, the destination IS that child. */
    static final double SAME_SPOT = 0.01;

    private static final Set<String> NAMES = new CopyOnWriteArraySet<>();

    private PlayerVisits() {
    }

    /** Every player online now, by name. Safe from any thread. */
    public static Set<String> knownNames() {
        return Set.copyOf(NAMES);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        NAMES.add(e.getEntity().getScoreboardName());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        NAMES.remove(e.getEntity().getScoreboardName());
    }

    /** This world's rule, as the hot-reloaded rules file says. */
    public static PlayerReach.Mode mode() {
        return WorldRulesService.current().playerReach();
    }

    /** What the visited child reads. */
    public static String visitLine(String visitor) {
        return "✨ " + visitor + " came to visit!";
    }

    /** What the visitor reads on a world where visiting is off. */
    public static String offLine() {
        return "✨ On this world you can't go to another player — visiting is for "
                + WorldRulesService.current().workshop() + "!";
    }

    /** What the visitor reads inside the cooldown. */
    public static String cooldownLine(String host, long seconds) {
        return "✨ You just visited " + host + "! You can go again in " + seconds + "s.";
    }

    @SubscribeEvent
    public static void onTeleportCommand(EntityTeleportEvent.TeleportCommand e) {
        if (!(e.getEntity() instanceof ServerPlayer mover) || !PlayerKind.isChild(mover.getUUID())) {
            return;
        }
        ServerPlayer host = hostAt(mover.getServer(), mover, e.getTarget());
        if (host == null) {
            return;
        }
        if (!decide(mover, host)) {
            e.setCanceled(true);
        }
    }

    /**
     * Decide one visit, say what it says, log it: true to let it go ahead. Also the entry point for
     * anything that moves a child to another in Java (a test drives it directly).
     */
    public static boolean decide(ServerPlayer mover, ServerPlayer host) {
        int others = Math.max(0, com.moddingfromamod.wish.CameraPresence.childrenOnline(mover.getServer()) - 1);
        if (mode() != PlayerReach.Mode.OPEN) {
            say(mover, offLine());
            com.moddingfromamod.agent.ActionLog.reach("visit", "refused", PlayerReach.WHY_REACH_OFF,
                    host.getScoreboardName(), "", others);
            return false;
        }
        long wait = VisitCooldown.SHARED.tryVisit(mover.getUUID(), host.getUUID());
        if (wait > 0) {
            say(mover, cooldownLine(host.getScoreboardName(), wait));
            com.moddingfromamod.agent.ActionLog.reach("visit", "refused", "visit_cooldown",
                    host.getScoreboardName(), "", others);
            return false;
        }
        say(host, visitLine(mover.getScoreboardName()));
        com.moddingfromamod.agent.ActionLog.reach("visit", "allowed", "visit",
                host.getScoreboardName(), "", others);
        return true;
    }

    /** The other child standing exactly where this teleport lands, or null. */
    static ServerPlayer hostAt(MinecraftServer server, ServerPlayer mover, Vec3 target) {
        if (server == null || target == null) {
            return null;
        }
        List<ServerPlayer> here = new ArrayList<>(server.getPlayerList().getPlayers());
        for (ServerPlayer p : here) {
            if (p != mover && !p.getUUID().equals(mover.getUUID()) && PlayerKind.isChild(p.getUUID())
                    && p.position().distanceToSqr(target) <= SAME_SPOT * SAME_SPOT) {
                return p;
            }
        }
        return null;
    }

    private static void say(ServerPlayer to, String line) {
        to.sendSystemMessage(Component.literal(line));
        com.moddingfromamod.agent.ActionLog.said("", to.getUUID().toString(), "PlayerVisits", line, false);
    }
}
