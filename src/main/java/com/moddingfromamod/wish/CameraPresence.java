package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.PlayerKind;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The camera is not a person in the child's world</b> — the sight half.
 *
 * <h2>What it fixes</h2>
 *
 * <p> settled who may be ACTED ON and who may be PHOTOGRAPHED, and never once asked who may be
 * SEEN. So the camera did its job perfectly and did it as a second player standing two metres from
 * the creature, with a body, a skin and a floating <code>Moddy-Camera</code> name tag, for the
 * whole wish. Found in a photograph of the child's own screen; invisible to every gate, because a
 * gate that asks "did the camera join" is delighted by exactly this.
 *
 * <h2>The rule, and why it is symmetric</h2>
 *
 * <p><b>A service client is shown only to other service clients.</b> Stated that way rather than as
 * "hide the camera from children" because puts more than one camera in a world, and a second
 * camera drifting through the first one's establishing shot is the same defect with the roles
 * swapped — a photograph with a stranger in it, which the agent then judges the sculpt by.
 *
 * <h2>Why packets and not invisibility</h2>
 *
 * <p>An invisibility effect hides the body and leaves the name tag, which needs a scoreboard team;
 * spectator mode hides both and changes what the camera IS (its abilities, its HUD, what
 * {@code travel} does), which is a large change to the thing whose only job is to render exactly
 * what a child would render. Not sending the entity is smaller than either, cannot be defeated by a
 * hat, and reuses {@code RigVeilService}'s mechanism verbatim.
 *
 * <p>No flash: {@code PlayerList.placeNewPlayer} pairs the joining camera with existing trackers
 * synchronously, and this sweep runs at the END of that same tick, so the child's client applies
 * add-then-remove in one packet-handling pass.
 *
 * <p><b>Every other channel.</b> This class named what it left — the tab list and the
 * "joined the game" line — and a child could also read the camera in the server list, {@code /list}
 * and a selector, and could not skip the night while it stood awake. Each is now one mixin asking
 * one method below, all deciding by {@link com.moddingfromamod.marshal.ServiceClientRule} and all
 * switched by the same seam: the "joined/left/advancement/death" lines ({@code PlayerListMixin}),
 * the tab list ({@code TabListMixin}), the server list ({@code ServerListMixin}), the sleep count
 * ({@code SleepCountMixin}), {@code /list} ({@code ListCommandMixin}) and every selector but
 * {@code @s} ({@code SelectorMixin}); and "is anybody on" counts children ({@link #childrenOnline}).
 */
public final class CameraPresence {

    private CameraPresence() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-camera-presence");

    /** The last thing said, so a steady session says it once. */
    private static volatile String lastSaid;

    /**
     * <b>Is this subject hidden from this viewer?</b> Minecraft-free so it can be unit-tested, and
     * expressed over the two roles rather than over "child" so the second-camera case is covered
     * by construction.
     */
    public static boolean hiddenFrom(boolean viewerIsServiceClient, boolean subjectIsServiceClient) {
        return subjectIsServiceClient && !viewerIsServiceClient;
    }

    /**
     * <b>The A/B seam, and the only reason it exists.</b>
     *
     * <p>"He could not see the camera" and "the harness pointed him at the sky" produce the same
     * photograph, so the honest control is the SAME pose with this rule switched off — then the
     * player is at the crosshair in one frame and gone in the next, and nothing about aim can
     * explain the difference. {@code SightProbe} drives exactly that.
     *
     * <p>Default ON, from {@code -Dmoddingfromamod.hidecamera}, which follows {@code deferPack}:
     * a control you cannot run is a claim, and a seam is how this repo keeps its A/Bs runnable.
     */
    private static volatile boolean suppressing = !"false".equalsIgnoreCase(
            System.getProperty(ModdingFromAModMod.MODID + ".hidecamera", "true").trim());

    public static boolean suppressing() {
        return suppressing;
    }

    /** Flip the rule mid-run. The probe's only lever; nothing in a game ever calls it. */
    public static void setSuppressing(boolean on) {
        suppressing = on;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        keepCamerasSafe(event.getServer());
        sweep(event.getServer());
    }

    /**
     * <b>A camera cannot fall, drown, burn or suffocate</b>. A service client joins as an
     * ordinary player of the world's game mode, and on a survival world that is SURVIVAL — measured
     * on a production-shaped test server: one camera body "fell from a high place" 30 s after a turn left
     * it 140 blocks up, another "suffocated in a wall" on a frame posed inside a hill, and every take
     * after that filmed the "You Died!" screen and filed it as the wish's photograph. So every
     * service-client body flies and is invulnerable, always — re-asserted each tick but only SENT
     * when it changed. A child is never touched here.
     */
    public static void keepCamerasSafe(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (PlayerKind.isChild(p.getUUID())) {
                continue;
            }
            var a = p.getAbilities();
            if (!a.invulnerable || !a.mayfly || !a.flying) {
                a.invulnerable = true;
                a.mayfly = true;
                a.flying = true;
                p.onUpdateAbilities();
            }
            if (p.isDeadOrDying()) {
                // One that died anyway (the void, /kill): healing it on the server leaves the CLIENT on
                // its death screen, which it then films for every wish. Sent away instead — its
                // launcher starts it again at once (a remote camera launcher: "the client exited mid-lease")
                // and it rejoins alive.
                p.connection.disconnect(net.minecraft.network.chat.Component.literal(
                        "camera died — rejoining"));
                continue;
            }
            p.fallDistance = 0;
            p.setAirSupply(p.getMaxAirSupply());
            p.clearFire();
        }
    }

    /**
     * The sweep itself, callable without an event object.
     *
     * <p>Split out because {@code ServerTickEvent.Post} cannot be constructed from a test, and a
     * gate that reimplemented the loop instead would be asserting on its own copy of the rule —
     * which is exactly the shape of a check that passes over a listener nobody registered.
     * {@code RevealIsWiredTest} holds the other half: that this class is on the bus at all.
     */
    public static void sweep(MinecraftServer server) {
        if (server == null || !suppressing) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.size() < 2) {
            return;   // singleplayer, or a camera with nobody to hide from
        }
        int[] service = null;
        int sent = 0;
        for (ServerPlayer viewer : players) {
            if (!PlayerKind.isChild(viewer.getUUID())) {
                continue;   // a service client sees every other service client
            }
            if (service == null) {
                service = serviceIds(players);
                if (service.length == 0) {
                    return;
                }
            }
            viewer.connection.send(new ClientboundRemoveEntitiesPacket(service));
            sent++;
        }
        // Say it when the ANSWER changes, never on a timer. A sweep that is quietly not firing and
        // a sweep that is firing at nobody look identical in a photograph -- which is the whole
        // subject of this class -- so it has to be visible; and at twenty ticks a second a timer
        // is either spam or it is off when you need it. The set is the state, so the set is the
        // line, and a steady session says it once.
        String now = sent + ":" + java.util.Arrays.toString(service);
        if (sent > 0 && !now.equals(lastSaid)) {
            lastSaid = now;
            LOG.info("hiding {} service client(s) {} from {} child(ren)",
                    service.length, java.util.Arrays.toString(service), sent);
        }
    }

    // ──: every OTHER channel, by the same rule (ServiceClientRule) ────────────────────
    // The body is the sweep above; the rest is one mixin per surface, each asking a method here, and
    // each switched by the same seam, so the control arm (hidecamera=false) is the old game on every
    // surface at once. A method here is the whole decision; the mixin only filters.

    /** Is this player one a CHILD must not meet (a service client, with the rule on)? */
    public static boolean hiddenFromChildren(ServerPlayer p) {
        return suppressing && p != null && !PlayerKind.isChild(p.getUUID());
    }

    /**
     * <b>"Is anybody on"</b>: the children online. What the pool's reap/boot decision, the reach log
     * and "a wake nobody plays in" must count — a camera never leaves, so counting it keeps a pool
     * alive for nobody. With the rule off, everyone (the control).
     */
    public static int childrenOnline(MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        if (!suppressing) {
            return server.getPlayerList().getPlayerCount();
        }
        List<java.util.UUID> ids = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ids.add(p.getUUID());
        }
        return com.moddingfromamod.marshal.ServiceClientRule.publicCount(ids, PlayerKind.serviceClients());
    }

    /** The players this viewer is shown (/list): a service client sees everyone, anyone else the children. */
    public static List<ServerPlayer> visibleTo(boolean viewerIsService, List<ServerPlayer> players) {
        if (!suppressing || viewerIsService || players == null) {
            return players;
        }
        java.util.Set<java.util.UUID> service = PlayerKind.serviceClients();
        if (service.isEmpty()) {
            return players;
        }
        List<ServerPlayer> out = new ArrayList<>(players.size());
        for (ServerPlayer p : players) {
            if (com.moddingfromamod.marshal.ServiceClientRule.shown(
                    com.moddingfromamod.marshal.ServiceClientRule.Surface.LIST_COMMAND, false, service.contains(p.getUUID()))) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Is this broadcast line ABOUT a service client ("joined/left the game", an advancement, a
     * death)? Every such line carries the subject's display name, whose hover names the entity, so
     * the line is judged by who it is about rather than by which of vanilla's call sites sent it —
     * the advancement one is inside a lambda whose name differs between 1.21.1 and 1.21.4.
     */
    public static boolean aboutServiceClient(net.minecraft.network.chat.Component line) {
        if (!suppressing || line == null) {
            return false;
        }
        java.util.Set<java.util.UUID> service = PlayerKind.serviceClients();
        if (service.isEmpty()) {
            return false;
        }
        java.util.Set<java.util.UUID> mentioned = new java.util.HashSet<>();
        collect(line, mentioned, 0);
        return com.moddingfromamod.marshal.ServiceClientRule.aboutServiceClient(mentioned, service);
    }

    private static void collect(net.minecraft.network.chat.Component c, java.util.Set<java.util.UUID> into, int depth) {
        if (c == null || depth > 8) {
            return;
        }
        var hover = c.getStyle().getHoverEvent();
        if (hover != null) {
            if (hover instanceof net.minecraft.network.chat.HoverEvent.ShowEntity show) {
                into.add(show.entity().uuid);
            }
        }
        if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            for (Object arg : t.getArgs()) {
                if (arg instanceof net.minecraft.network.chat.Component ac) {
                    collect(ac, into, depth + 1);
                }
            }
        }
        for (net.minecraft.network.chat.Component sib : c.getSiblings()) {
            collect(sib, into, depth + 1);
        }
    }

    /**
     * The server list's player count and hover sample, children only. Rebuilt the way vanilla builds
     * it (up to 12, a player who has not allowed listing shown as ANONYMOUS) rather than filtered,
     * because an anonymous entry carries no id and so cannot be told apart from the camera's.
     */
    public static net.minecraft.network.protocol.status.ServerStatus.Players serverList(
            MinecraftServer server, net.minecraft.network.protocol.status.ServerStatus.Players built) {
        if (!suppressing || server == null || built == null) {
            return built;
        }
        java.util.Set<java.util.UUID> service = PlayerKind.serviceClients();
        if (service.isEmpty()) {
            return built;
        }
        List<ServerPlayer> kids = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!service.contains(p.getUUID())) {
                kids.add(p);
            }
        }
        if (kids.size() == server.getPlayerList().getPlayerCount()) {
            return built;          // no camera online: vanilla's own answer, untouched
        }
        List<com.mojang.authlib.GameProfile> sample = new ArrayList<>();
        if (!built.sample().isEmpty() || !server.hidesOnlinePlayers()) {
            for (int i = 0; i < Math.min(12, kids.size()); i++) {
                ServerPlayer p = kids.get(i);
                sample.add(p.allowsListing() ? p.getGameProfile() : MinecraftServer.ANONYMOUS_PLAYER_PROFILE);
            }
        }
        if (server.hidesOnlinePlayers()) {
            sample.clear();
        }
        return new net.minecraft.network.protocol.status.ServerStatus.Players(built.max(), kids.size(), sample);
    }

    /** The players the night waits for: no service client (a camera never sleeps). */
    public static List<ServerPlayer> sleepers(List<ServerPlayer> players) {
        return visibleTo(false, players);
    }

    /**
     * The tab-list packet a CHILD may be sent: the same packet when it names no service client, a
     * rebuilt one without them, or null when every entry was one (send nothing). A service client
     * gets the packet unchanged. Runs per send, so the common case is one instanceof and a return.
     */
    public static net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket tabListFor(
            ServerPlayer recipient, net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket info) {
        if (!suppressing || recipient == null || !PlayerKind.isChild(recipient.getUUID())) {
            return info;
        }
        boolean any = false;
        for (var e : info.entries()) {          // no copy of the registry: this runs per send
            if (!PlayerKind.isChild(e.profileId())) {
                any = true;
                break;
            }
        }
        if (!any) {
            return info;
        }
        java.util.Set<java.util.UUID> service = PlayerKind.serviceClients();
        List<ServerPlayer> keep = new ArrayList<>();
        var list = recipient.getServer() == null ? null : recipient.getServer().getPlayerList();
        for (var e : info.entries()) {
            if (!service.contains(e.profileId()) && list != null) {
                ServerPlayer p = list.getPlayer(e.profileId());
                if (p != null) {
                    keep.add(p);
                }
            }
        }
        return keep.isEmpty() ? null
                : new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(info.actions(), keep);
    }

    private static int[] serviceIds(List<ServerPlayer> players) {
        List<Integer> ids = new ArrayList<>();
        for (ServerPlayer p : players) {
            if (!PlayerKind.isChild(p.getUUID())) {
                ids.add(p.getId());
            }
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ids.get(i);
        }
        return out;
    }
}
