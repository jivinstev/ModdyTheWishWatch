package com.moddingfromamod.wish;

import com.moddingfromamod.agent.ActingChild;
import com.moddingfromamod.marshal.PlayerKind;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Whose eyes is the agent looking through?</b> — acquired once, so there are not two capture
 * paths.
 *
 * <p> Part B. The agent photographs today by borrowing the child's body: freeze him, spin his
 * view onto the subject, fire the shutter on his client, warp him back. A camera client — a second,
 * modded client the service runs, joined as a declared non-child — does the same job without
 * touching anybody's game.
 *
 * <p><b>There are two of these and there must not be two code paths.</b> Everything above the
 * viewpoint differs (a camera is teleported freely; a child is frozen, told, made invulnerable and
 * put back) and everything below it is identical — framing, line of sight, the settle, waiting for
 * the file to stop growing, decoding. So the branch happens exactly HERE, once, and callers hold a
 * {@code Viewpoint} rather than a {@code ServerPlayer} and a boolean.
 *
 * <p><b>⚠ Why there are two at all, since one would be simpler.</b> Not memory. A camera must load
 * the same mod set as the server it joins or it cannot render what it is pointed at; on a
 * service-owned server we choose that set, so the camera is lean by construction, and on a child's
 * own machine the set is arbitrary and unknown. If that ever stops being true — if the mod set is
 * known on both sides — then always photographing through a camera is the better architecture and
 * this class should collapse to one case. See {@code docs/CAMERA_CLIENT.md} §3.
 */
public final class Viewpoint {

    /** A camera is the mod's own client; the child's eyes are borrowed and must be given back. */
    public enum Kind {
        CAMERA, CHILDS_EYES
    }

    private final ServerPlayer player;
    private final Kind kind;

    private Viewpoint(ServerPlayer player, Kind kind) {
        this.player = player;
        this.kind = kind;
    }

    /**
     * The camera if one is in the world, otherwise the child whose wish this is.
     *
     * @return null when there is neither — nobody is online and no camera has joined
     */
    public static Viewpoint acquire(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        // ── WHICH camera, when there is more than one ─────────────────────────────────
        // This used to return the FIRST non-child in the player list, so with two cameras
        // connected every wish queued on body one while body two stood idle — measured red in
        // CameraPoolGameTest. The choice is CameraPool's (pure, unit-tested); this only gathers
        // what each body's queue says. Called on the server thread in the same lambda as take(),
        // which is what makes "choose, then claim" atomic against another wish doing the same.
        java.util.List<ServerPlayer> cams = new java.util.ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!PlayerKind.isChild(p.getUUID())) {
                cams.add(p);
            }
        }
        if (!cams.isEmpty()) {
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            java.util.UUID wish = run == null ? null : run.wishId();
            java.util.List<CameraPool.Cam> seen = new java.util.ArrayList<>();
            for (ServerPlayer c : cams) {
                java.util.UUID id = c.getUUID();
                seen.add(new CameraPool.Cam(id, WishCapture.cameraHeldBy(id),
                        WishCapture.queueDepth(id),
                        wish == null ? -1 : WishCapture.queuePositionOf(id, wish)));
            }
            int pick = CameraPool.choose(wish, seen);
            if (wish != null) {
                // Leaving a line for an idle body: give the old place back, or it holds a place
                // nobody will claim and reports congestion that is not real until MAX_WAIT_MS.
                for (int i = 0; i < seen.size(); i++) {
                    if (i != pick && seen.get(i).positionOfWish() > 0) {
                        WishCapture.abandonCamera(seen.get(i).id(), wish);
                    }
                }
            }
            return new Viewpoint(cams.get(Math.max(0, pick)), Kind.CAMERA);
        }
        ServerPlayer child = ActingChild.online(server);
        return child == null ? null : new Viewpoint(child, Kind.CHILDS_EYES);
    }

    /**
     * Classify a player we already hold.
     *
     * <p>For a site that resolved a body first and asks about the lease second. It must go through
     * {@link PlayerKind} rather than assuming a child: calling {@link WishCapture#enter} on a camera
     * would open a real capture session on it — a "hold still" message and an invulnerability flag
     * for a client with nobody behind it — which is harmless and wrong-shaped, and the kind of thing
     * that is discovered much later by wondering why the camera is in a freeze.
     */
    public static Viewpoint of(ServerPlayer player) {
        return new Viewpoint(player,
                PlayerKind.isChild(player == null ? null : player.getUUID())
                        ? Kind.CHILDS_EYES : Kind.CAMERA);
    }

    public ServerPlayer player() {
        return player;
    }

    public boolean isCamera() {
        return kind == Kind.CAMERA;
    }

    /**
     * Get ready to shoot.
     *
     * <p>A camera needs no <b>ritual</b>: nobody is being interrupted, so there is nothing to
     * announce, nothing to freeze and nothing to give back. It still needs the <b>lease</b>.
     *
     * <p>⚠ This used to answer TAKEN unconditionally, and the reasoning written here was that a
     * camera cannot be BUSY — <i>"the lease exists because a child has one body and two wishes
     * cannot both spin it, and a camera that is already aiming for another wish is a scheduling
     * question rather than a fairness one."</i> The first half is right and the conclusion does not
     * follow: <b>a camera has one body too.</b> Two wishes both told TAKEN both teleport it, so the
     * second one's aim lands inside the first one's settle and both photographs are of somewhere
     * else — and nothing reports it, because a frame was written and a frame is what the gates
     * check. Being a scheduling question is exactly why it needs a queue.
     *
     * <p>The child's eyes go through {@link WishCapture#enter} exactly as before, including its
     * refusal to start the ritual on a client that cannot receive the freeze (D11).
     */
    public WishCapture.Lease take() {
        if (!isCamera()) {
            return WishCapture.enter(player);
        }
        if (player == null) {
            return WishCapture.Lease.NO_CAMERA;
        }
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return WishCapture.claimCameraFor(player.getUUID(), run == null ? null : run.wishId())
                ? WishCapture.Lease.TAKEN : WishCapture.Lease.BUSY;
    }

    /**
     * Put it down. A camera hands on its lease; a child is also unfrozen and warped back.
     *
     * <p>Deliberately not the same call: {@link WishCapture#exit} restores a remembered spot and
     * tells a child his eyes are his again, and a camera has neither a spot to go back to nor
     * anybody to tell.
     */
    public void release() {
        if (player == null) {
            return;
        }
        if (isCamera()) {
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            endTake(player);
            WishCapture.releaseCameraLease(player.getUUID(),
                    run == null ? null : run.wishId());
        } else {
            WishCapture.exit(player);
        }
    }

    /**
     * <b>Night vision for a camera's take, never for a child.</b> Called with every shot
     * sent to a viewpoint ({@code PhotographTool.shutter}), so the effect is on the camera before
     * the client draws the frame: the effect's packet goes ahead of the shot's on the same
     * connection. Renewed per shot, removed in {@link #release}; a crash in between leaves a
     * camera that sees in the dark for at most {@link CameraNightVision#TAKE_TICKS}.
     */
    public static void seeInTheDark(ServerPlayer player) {
        if (player == null || !CameraNightVision.appliesTo(PlayerKind.isChild(player.getUUID()))) {
            return;
        }
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.NIGHT_VISION, CameraNightVision.TAKE_TICKS,
                0, false, false, false));
        SEEING.add(player.getUUID());
    }

    /** Cameras given night vision by a take that has not been seen to end yet. */
    private static final java.util.Set<java.util.UUID> SEEING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Take the take's night vision away again. Only ever a camera's — a child never had it. */
    static void endTake(ServerPlayer player) {
        if (player != null && SEEING.remove(player.getUUID())) {
            player.removeEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);
        }
    }

    /**
     * <b>End the night vision of every take that has ended, however it ended.</b> A take ends in
     * {@link #release} far less often than it ends in {@code WishCapture}'s own watchdog or a
     * direct lease release — measured on the first Gate C run, where the camera arm's night vision
     * was still on when the next (child) arm shot, and that arm's photograph was brightened by it.
     * So the session's END is the signal, not any one caller: run every server tick from
     * {@code WishCapture.onServerTick}, nothing to do while no camera is seeing. ⚠ "Ended" is
     * the SESSION closing, not the lease holder going null: a lease taken outside a wish has no
     * holder at all, and keying on it stripped the night vision mid-take (second Gate C run).
     */
    public static void sweepNightVision(net.minecraft.server.MinecraftServer server) {
        if (SEEING.isEmpty() || server == null) {
            return;
        }
        for (java.util.UUID id : java.util.List.copyOf(SEEING)) {
            if (WishCapture.hasSession(id) || WishCapture.cameraHeldBy(id) != null) {
                continue;                     // the take is still going
            }
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.removeEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);
            }
            SEEING.remove(id);
        }
    }

    @Override
    public String toString() {
        return kind + "(" + (player == null ? "nobody" : player.getName().getString()) + ")";
    }
}
