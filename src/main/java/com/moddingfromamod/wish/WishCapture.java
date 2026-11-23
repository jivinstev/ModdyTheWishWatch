package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * <b>Wish Capture Mode</b> — turns the agent's "screenshot the wish" step into gameplay the child
 * understands, instead of the view suddenly snapping around for no reason.
 *
 * <p>When the agent photographs a wish it has to point the camera through the child's eyes. On its
 * own that reads as the game glitching. So the moment a photo is taken we: tell the child in Moddy's
 * voice that his eyes are being borrowed, <b>freeze</b> him where he stands (so he can't wander out
 * of shot), make him <b>invulnerable</b> (he mustn't die just for holding still), and — on the client
 * — paint a magical shimmer so the state is obvious. When the agent is done we <b>warp him back</b> to
 * exactly where he was looking and clear everything. None of it persists.
 *
 * <p><b>One session, many shots.</b> The agent often takes several photos in a row (fix, re-shoot).
 * A session stays open across a burst so it isn't a jarring string of freeze/unfreeze/teleport; it
 * ends {@link #IDLE_EXIT_MS} after the last shot (the agent moved on), and a hard
 * {@link #MAX_SESSION_MS} watchdog ALWAYS releases him even if something wedged — the freeze can
 * never trap him. On {@code NeoForge.EVENT_BUS}; state is per-player and in memory.
 */
public final class WishCapture {

    // ⚠ THE NUMBERS LIVE IN CaptureTimings, and these are aliases so every existing call site and
    // test keeps working. They were moved because the question that matters is not what any one of
    // them is, it is what they SUM TO: a body is held for IDLE_EXIT_MS after the last shot, capped
    // at MAX_SESSION_MS, and both are only ever CHECKED on a ticker that looks every TICK_INTERVAL
    // ticks. Anything that waits for a body to come back has to be sized against that sum, and a
    // sum spread over three files is one nobody can read off. CaptureTimingsTest asserts the
    // relationships between them, which is the part no single constant could ever be wrong about
    // on its own.

    /** Release the child this long after the last photo — long enough to cover a quick re-shoot. */
    static final long IDLE_EXIT_MS = com.moddingfromamod.runtime.CaptureTimings.IDLE_EXIT_MS;

    /** Hard cap: he is NEVER frozen longer than this, whatever goes wrong. */
    public static final long MAX_SESSION_MS =
            com.moddingfromamod.runtime.CaptureTimings.MAX_SESSION_MS;

    /**
     * The same backstop for a lease with NO FREEZE behind it — a camera, or a headless probe.
     *
     * <p>Six times longer, because the two caps answer different questions. A child's cap is a
     * promise to the child: he is never held still longer than this, and being cut off mid-burst is
     * the correct outcome. A camera is nobody's body, so its cap is only an anti-wedge backstop, and
     * cutting it off mid-burst would hand the camera to a second wish halfway through the first
     * one's four-angle orbit — reintroducing exactly the interleaving the queue exists to stop. A
     * burst that legitimately runs 10 s is ordinary; one that runs a minute has died.
     */
    public static final long MAX_CAMERA_HOLD_MS =
            com.moddingfromamod.runtime.CaptureTimings.MAX_CAMERA_HOLD_MS;

    /** check ~4x/second on a healthy server — and far less often on a struggling one, which is
     *  exactly why {@link com.moddingfromamod.runtime.CaptureTimings#settleWaitMs()} allows for it */
    private static final int TICK_INTERVAL =
            com.moddingfromamod.runtime.CaptureTimings.TICK_INTERVAL;

    /**
     * How long a wish may wait in line before it is dropped.
     *
     * <p>Deliberately far longer than {@link #MAX_SESSION_MS}: a waiter is not stuck, it is second,
     * and the hold cap already guarantees the queue moves. This is the backstop for a wish that
     * died without ever calling {@link #abandonCamera} — without it a corpse holds a place forever
     * and {@link #queueDepth} reports congestion that is not real.
     */
    static final long MAX_WAIT_MS = com.moddingfromamod.runtime.CaptureTimings.MAX_WAIT_MS;

    /**
     * Report a queue at least this deep, because ONE CAMERA STOPS BEING ENOUGH SOMEWHERE and
     * nothing currently says where. A queue makes the wait fair; it does not make it short, and a
     * fair wait behind eight wishes is still a child watching a progress bar. an earlier issue's follow-up
     * needs a number rather than an intuition, so the number is logged the moment it happens.
     */
    static final int CONGESTED_DEPTH = 2;

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("mfam-camera-queue");

    private static final Map<UUID, Session> ACTIVE = new ConcurrentHashMap<>();

    /**
     * One queue per BODY — the camera has one too, and that was the bug.
     *
     * <p>{@code Viewpoint.take()} used to answer {@code TAKEN} unconditionally for a camera, on the
     * reasoning that a camera interrupts nobody so there is no fairness question. True, and beside
     * the point: fairness is not why a lease exists, <b>one body</b> is. Two wishes both told TAKEN
     * both teleport the camera, so the second one's aim lands inside the first one's settle and
     * BOTH photographs are of somewhere else — with every gate green, because a frame was written.
     */
    private static final Map<UUID, CameraQueue> QUEUES = new ConcurrentHashMap<>();

    /**
     * Where the child was, and how he was, before Moddy borrowed his eyes — restored on exit.
     *
     * <p>⚠ {@code wasNoGravity} is here because the capture path RAISES that flag on the body it is
     * holding — every seat it teleports to is pinned with {@code setNoGravity(true)}, or the body
     * falls ~40 blocks while the shot waits on a file. Three separate places in
     * {@code PhotographTool} lowered it again afterwards, each in its own {@code finally}-shaped
     * block, and each was correct only for the path it sat on. The moment a fourth path could move
     * a CHILD — which is what retiring {@code AimPlan}'s "a child may not travel" rule did — a
     * missed one stops being a camera left floating and becomes a child left floating in his own
     * world. Restoring it here makes it a property of giving the body back rather than a duty of
     * each caller.
     */
    private record Origin(double x, double y, double z, float yaw, float pitch,
                          boolean wasInvulnerable, boolean wasNoGravity) {
    }

    /**
     * The FREEZE, not the ownership — who holds the camera lives in {@link CameraQueue}, which is
     * the only thing that can also order the wishes waiting behind it.
     */
    private record Session(Origin origin, long startedMs, long lastActivityMs) {
    }

    private static int tickCounter;

    private WishCapture() {
    }

    /** Pure: should the session end now? Idle since the last shot, or past the hard cap. */
    static boolean shouldExit(long startedMs, long lastActivityMs, long nowMs) {
        return (nowMs - lastActivityMs) >= IDLE_EXIT_MS || (nowMs - startedMs) >= MAX_SESSION_MS;
    }

    /**
     * How far a frozen body may drift from where it is meant to be before it is put back.
     *
     * <p>Squared, because the caller has {@code distanceToSqr} and nothing here needs a root.
     * 0.02 is about a seventh of a block — smaller than a step, larger than the float noise a
     * teleport lands with.
     */
    static final double DRIFT_SQ = 0.02;

    /**
     * <b>Where a frozen body must be held THIS tick, or null to leave it alone —.</b>
     *
     * <p>Pure, and separated out because the answer was wrong in a way no gate could see. The hold
     * exists so a frozen child cannot walk off mid-burst, and it used to anchor unconditionally on
     * {@code origin} — the spot he was standing when his eyes were borrowed. But the capture path
     * <b>moves that same body on purpose</b>: {@code PhotographTool.moveCameraTo} teleports it out
     * to an orbit seat eighteen blocks away, which is by definition further than {@link #DRIFT_SQ}
     * from the origin. So two writers fought over one body — the orbit every 100 ms, this hold
     * every {@code TICK_INTERVAL} ticks — and whichever wrote last before the shutter decided the
     * picture.
     *
     * <p><b>That is why only the film frame was wrong.</b> An orbit stop teleports and waits about
     * a tenth of a second for its PNG, so it usually beats the next hold; the film's still is
     * requested {@code seconds * 20} ticks ahead (two whole seconds on a shipped wish) and loses
     * the race almost every time — the camera is back at the child's feet, pointing wherever he was
     * pointing, and the frame is of the field he was standing in. And it is why <b>only arm 1</b>
     * was wrong: a camera holds a lease with no {@code origin}, so it was never yanked at all,
     * which is exactly the asymmetry called its most useful clue.
     *
     * <p>So the anchor is the last spot the capture path deliberately put the body, when there is
     * one, and the origin otherwise. {@link #restore} still returns him to the ORIGIN at exit —
     * where a child is put back is a different question from where he is pinned mid-shot, and
     * collapsing the two is what caused this.
     *
     * @param aimed the last commanded camera seat as {x, y, z}, or null if nothing has moved it
     * @return the spot to teleport to, or null when the body is already where it should be
     */
    static double[] holdAt(double originX, double originY, double originZ,
                           double[] aimed,
                           double atX, double atY, double atZ) {
        double ax = aimed != null ? aimed[0] : originX;
        double ay = aimed != null ? aimed[1] : originY;
        double az = aimed != null ? aimed[2] : originZ;
        double dx = atX - ax;
        double dy = atY - ay;
        double dz = atZ - az;
        return (dx * dx + dy * dy + dz * dz) > DRIFT_SQ ? new double[]{ax, ay, az} : null;
    }

    /**
     * <b>How many times this player's eyes have been borrowed since the server started.</b>
     *
     * <p>{@link #isCapturing} answers "is he frozen RIGHT NOW", which is the wrong question for the
     * claim that matters: a camera means the child is <b>never</b> frozen, and a poll that happens
     * to land between two four-second sessions cannot tell that from "never". A counter can, and it
     * is the difference between a test that is true and one that is usually true.
     *
     * <p>Never reset. A gate compares it before and after; something that only ever goes up cannot
     * be confused by a session that opened and closed while nobody was looking.
     */
    public static int timesBorrowed(UUID playerId) {
        return playerId == null ? 0 : BORROWED.getOrDefault(playerId, 0);
    }

    private static final Map<UUID, Integer> BORROWED = new ConcurrentHashMap<>();

    /**
     * Where the capture path last deliberately put each borrowed body — see {@link #holdAt}.
     *
     * <p>Separate from {@link #ACTIVE} rather than a field on the session, because it is written
     * from the agent's thread on every camera move and read from the server tick; a small map of
     * its own says that plainly and keeps the session record about the freeze.
     */
    private static final Map<UUID, double[]> AIMED = new ConcurrentHashMap<>();

    /**
     * <b>The capture path has just moved this body on purpose — hold it HERE, not at its origin.</b>
     *
     * <p>Called by {@code PhotographTool.moveCameraTo}, which is the only site that moves a body
     * that might be frozen (the two aim-in-place teleports keep x/y/z, and the travelling-camera
     * seat is reached only when the viewpoint is a camera, which is never frozen). One caller is
     * the point: a hold that has to be told about each new mover is one a seventh mover will not
     * tell, which is the wiring failure docs/CAMERA.md keeps recording.
     *
     * <p>Harmless for a camera — nothing frozen is never held — so it needs no caller-side branch.
     */
    public static void aimedAt(UUID playerId, double x, double y, double z) {
        if (playerId != null) {
            AIMED.put(playerId, new double[]{x, y, z});
        }
    }

    /**
     * True while this player is actually FROZEN (so damage/death are cancelled).
     *
     * <p>⚠ A LEASE IS NOT A CAPTURE, and conflating them was already loose before the camera made
     * it reachable. {@code claimCameraFor} takes the lease with no player and no freeze — the
     * ownership seam a headless gate uses, and now the camera's whole path — and this used to
     * answer true for it. The consequences were real if quiet: {@code onDeath} would cancel a death
     * and heal a body nobody was holding still, i.e. invulnerability granted for a ritual that
     * never happened.
     *
     * <p>The origin is the honest discriminator: it is recorded only when a body is genuinely taken
     * over, and it is what {@link #restore} gives back.
     */
    /**
     * Is ANY session open on this body — a child's borrowed eyes or a camera's lease? Not
     * {@link #isCapturing}, which is a child's freeze only, and not {@link #cameraHeldBy}, which is
     * null for a lease taken outside a wish.
     */
    public static boolean hasSession(UUID playerId) {
        return playerId != null && ACTIVE.containsKey(playerId);
    }

    public static boolean isCapturing(UUID playerId) {
        Session s = playerId == null ? null : ACTIVE.get(playerId);
        return s != null && s.origin() != null;
    }

    /**
     * What happened when a wish asked to borrow the child's eyes.
     *
     * <p><b>Three answers, not two, and the third is the point.</b> This returned a boolean, so
     * "another wish is shooting right now" and "this client cannot take a photograph at all" were
     * the same value — and the caller's message for it says <i>carry on, it will be free within a
     * few seconds</i>. For a child playing without the mod that is not merely unhelpful, it is a
     * retry loop against a condition that will never change. {@code worthWaitingFor} is the code
     * that acts on the difference, which is why the difference has to reach it.
     */
    public enum Lease {
        /** The camera is ours; the child is frozen and told. */
        TAKEN,
        /** Another wish holds it. Shoot later rather than stealing it. */
        BUSY,
        /** There is no shutter on this client — a vanilla one, or nobody. Do not wait. */
        NO_CAMERA
    }

    /**
     * Begin a capture session, or extend the one already running (a later shot in the same burst).
     * Call this on the server thread RIGHT BEFORE aiming the camera, so the child's real spot + view
     * are recorded before anything moves.
     *
     * <p><b>The camera is a lease, because he has one body.</b> Sparkle beams and progress bars
     * stack for free; a freeze does not. Without an owner, a second wish photographing during the
     * first one's burst would teleport him mid-shot and then <i>release</i> him — the first wish
     * gets a picture of somewhere else, and he is left unfrozen while it is still shooting.
     *
     * @return false when another wish is holding the camera; the caller should shoot later rather
     *         than steal it
     */
    public static Lease enter(ServerPlayer player) {
        if (player == null) {
            return Lease.NO_CAMERA;
        }
        // ⚠ ASK WHETHER THE SHUTTER CAN FIRE BEFORE BORROWING HIS BODY (Part A).
        //
        // Everything below reaches a vanilla client except the one payload that makes the freeze
        // pleasant. The chat line arrives, the invulnerability is server-side, and the camera is
        // spun round and yanked back by connection.teleport, which is a vanilla packet — while
        // CaptureModePayload, the client input lock, is silently dropped by Send.to. So the child
        // is told to hold still, has his view turned for him, and then RUBBER-BANDS if he walks:
        // the half that enforces the freeze survives and the half that explains it does not.
        //
        // Nothing asked. Send.to's guard is real and correct and sits four lines too late — the
        // shape docs/CAMERA.md records, where a check exists and is not wired to the path that
        // needs it. Asking here rather than at each of the six call sites is deliberate: a guard
        // added per caller is one a seventh caller will not have.
        if (!com.moddingfromamod.net.ModdedClient.can(player,
                com.moddingfromamod.net.ModdedClient.Channel.CAPTURE_MODE)) {
            return Lease.NO_CAMERA;
        }
        boolean fresh = !ACTIVE.containsKey(player.getUUID());
        if (!claimCameraFor(player.getUUID(), currentOwner())) {
            return Lease.BUSY;
        }
        if (!fresh) {
            return Lease.TAKEN;   // a later shot in the same burst; nothing to record or announce
        }
        Session held = ACTIVE.get(player.getUUID());
        Origin origin = new Origin(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.isInvulnerable(), player.isNoGravity());
        ACTIVE.put(player.getUUID(),
                new Session(origin, held.startedMs(), held.lastActivityMs()));
        BORROWED.merge(player.getUUID(), 1, Integer::sum);
        player.setInvulnerable(true);
        player.sendSystemMessage(Component.literal(
                        "✨ Hold still for a sec! Moddy needs to borrow your eyes to make sure your "
                        + "wish looks just right... 📸🪄")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)));
        com.moddingfromamod.net.Send.to(player, com.moddingfromamod.net.ModdedClient.captureMode(true));
        return Lease.TAKEN;
    }

    /**
     * <b>Where this body's own spot is, while it is borrowed</b> — or null if nobody is holding it.
     *
     * <p>The truth about where he will be put back, which during a capture is a different place
     * from where he is standing. Deliberately NOT offered as "the child's position": the two
     * questions have different right answers and a helper that blurs them is how
     * {@code AimPlan}'s rule about a child's body quietly became a rule about the camera. A caller
     * that wants the live body should ask the live body.
     */
    public static net.minecraft.world.phys.Vec3 homeOf(UUID playerId) {
        Session s = playerId == null ? null : ACTIVE.get(playerId);
        Origin o = s == null ? null : s.origin();
        return o == null ? null : new net.minecraft.world.phys.Vec3(o.x(), o.y(), o.z());
    }

    /**
     * <b>Block until this body is the child's own again.</b> See
     * {@link com.moddingfromamod.runtime.CaptureSettle} for why waiting beats answering from
     * {@link #homeOf}, and for why calling this from the server thread is a deadlock rather than a
     * slow path.
     *
     * <p>This is the whole of the Minecraft-facing half: the clock, the session map and the thread
     * question. {@code ACTIVE} is a {@link ConcurrentHashMap} and a {@code Session} is immutable, so
     * the poll needs no hop onto the server thread — which is just as well, because a hop is the one
     * thing it must not do.
     */
    public static com.moddingfromamod.runtime.CaptureSettle.Verdict awaitSettled(UUID playerId) {
        var server = com.moddingfromamod.agent.AgentBridge.serverOrNull();
        return com.moddingfromamod.runtime.CaptureSettle.await(
                () -> isCapturing(playerId),
                () -> server != null && server.isSameThread(),
                System::currentTimeMillis,
                ms -> {
                    try {
                        Thread.sleep(ms);
                        return true;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                },
                com.moddingfromamod.runtime.CaptureTimings.settleWaitMs());
    }

    /**
     * A clip being filmed of a finished wish. The clip runs AFTER the capture session has exited,
     * so without this the orb came straight back and sat in the child's film (the Muse smoke's
     * dragon clip). Counted, because two wishes can film at once.
     */
    private static final java.util.concurrent.atomic.AtomicInteger FILMING =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Start filming a clip; close the result when the take ends. */
    public static AutoCloseable filming() {
        FILMING.incrementAndGet();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true)) {
                FILMING.decrementAndGet();
            }
        };
    }

    /**
     * Is any photograph being taken anywhere right now (a child's capture session, or a camera held
     * for a wish)? The wish orbs and reveals take themselves down while it is, so they are never in
     * the agent's picture.
     */
    public static boolean anyShooting() {
        if (FILMING.get() > 0 || !ACTIVE.isEmpty()) {
            return true;
        }
        for (CameraQueue q : QUEUES.values()) {
            if (q.holder() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>The children whose OWN eyes are taking a photograph right now</b> — the only
     * viewpoints a wish's pillar and reveals are really taken down for. A camera client never needs
     * a take-down: it is never sent them at all ({@code FxVeil}), so the child keeps his pillar while
     * the camera shoots. Without a camera the child IS the camera: a frozen capture session, or a
     * clip being filmed (which, with no camera online, can only be through a child's eyes).
     */
    public static java.util.List<ServerPlayer> ownEyesShooting(net.minecraft.server.MinecraftServer server) {
        if (server == null || (ACTIVE.isEmpty() && FILMING.get() == 0)) {
            return java.util.List.of();
        }
        java.util.List<ServerPlayer> eyes = new ArrayList<>();
        boolean camera = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID())) {
                camera = true;
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID())) {
                continue;
            }
            if (isCapturing(p.getUUID()) || (FILMING.get() > 0 && !camera)) {
                eyes.add(p);
            }
        }
        return eyes;
    }

    /** Which wish is holding the camera on this player, or null. */
    public static UUID cameraHeldBy(UUID playerId) {
        CameraQueue q = playerId == null ? null : QUEUES.get(playerId);
        return q == null ? null : q.holder();
    }

    /** How many wishes are waiting behind whoever holds this body's camera. */
    public static int queueDepth(UUID playerId) {
        CameraQueue q = playerId == null ? null : QUEUES.get(playerId);
        return q == null ? 0 : q.depth();
    }

    /** 0 = holds it, 1 = next, -1 = not in line. What a "busy" answer should really have said. */
    public static int queuePositionOf(UUID playerId, UUID owner) {
        CameraQueue q = playerId == null ? null : QUEUES.get(playerId);
        return q == null ? -1 : q.positionOf(owner);
    }

    /** This wish has stopped waiting. Without it a wish that gave up blocks everyone behind it. */
    public static void abandonCamera(UUID playerId, UUID owner) {
        CameraQueue q = playerId == null ? null : QUEUES.get(playerId);
        if (q != null) {
            q.abandon(owner);
        }
    }

    /** Which wish is asking, or null when nothing identified one (a probe, a gate, a command). */
    private static UUID currentOwner() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? null : run.wishId();
    }

    /**
     * Put down a lease taken WITHOUT a freeze — the camera's case.
     *
     * <p>Not {@link #exit}: that one warps a body back to a remembered spot and tells a child his
     * eyes have been given back, and a camera has neither. Sending it down that path would put a
     * "Perfect — got a good look at your wish!" line into a client with nobody behind it.
     */
    public static void releaseCameraLease(UUID playerId, UUID owner) {
        if (playerId == null) {
            return;
        }
        CameraQueue q = QUEUES.get(playerId);
        if (q != null) {
            q.release(owner, false, System.currentTimeMillis());
        }
        Session s = ACTIVE.get(playerId);
        if (s != null && s.origin() == null) {
            ACTIVE.remove(playerId);
            AIMED.remove(playerId);
        }
    }

    /**
     * Take (or extend) the camera lease, with <b>no player needed</b>.
     *
     * <p>The lease is separated from the freeze deliberately: the decision — <i>may this wish borrow
     * his eyes right now?</i> — is the part concurrency changes, and it is the part a headless gate
     * can assert. Putting a real player in a GameTest's player list fires our sync payloads at an
     * embedded channel, a known way to fail a test for reasons unrelated to what it is testing.
     *
     * @return false when another wish holds it
     */
    public static boolean claimCameraFor(UUID playerId, UUID owner) {
        if (playerId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        CameraQueue q = QUEUES.computeIfAbsent(playerId, k -> new CameraQueue());
        if (q.claim(owner, now) != CameraQueue.Outcome.GRANTED) {
            if (q.depth() >= CONGESTED_DEPTH) {
                LOG.warn("camera queue is {} deep on {} -- one camera may have stopped being "
                        + "enough (#510 follow-up)", q.depth(), playerId);
            }
            return false;   // in line, not refused: ask again rather than stealing it
        }
        Session existing = ACTIVE.get(playerId);
        ACTIVE.put(playerId, existing == null
                ? new Session(null, now, now)
                : new Session(existing.origin(), existing.startedMs(), now));
        return true;
    }

    /**
     * End the session: warp the child back to exactly where he was, and clear everything.
     *
     * <p>Only the wish that took the camera may put it down — otherwise a second wish's tidy-up
     * releases the freeze while the first is still shooting. The watchdog in the ticker is exempt,
     * because the freeze must ALWAYS end however wedged things get.
     */
    public static void exit(ServerPlayer player) {
        exit(player, false);
    }

    private static void exit(ServerPlayer player, boolean watchdog) {
        if (player == null) {
            return;
        }
        Session peek = ACTIVE.get(player.getUUID());
        if (peek == null) {
            return;
        }
        UUID holder = cameraHeldBy(player.getUUID());
        if (!watchdog && holder != null && !holder.equals(currentOwner())) {
            return;   // not yours to end
        }
        Session s = ACTIVE.remove(player.getUUID());
        AIMED.remove(player.getUUID());   // the next burst aims from scratch
        if (s == null) {
            return;
        }
        CameraQueue q = QUEUES.get(player.getUUID());
        if (q != null) {
            q.release(holder, true, System.currentTimeMillis());
        }
        restore(player, s);
        if (s.origin() != null) {
            // ONLY IF SOMETHING WAS ACTUALLY BORROWED. A lease taken without a freeze -- the
            // camera, and every headless probe -- announced nothing on the way in, so announcing
            // its end would be a message about something that never happened.
            player.sendSystemMessage(Component.literal(
                            "✨ Perfect — got a good look at your wish! Here you go 🪄")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)));
        }
    }

    private static void restore(ServerPlayer player, Session s) {
        Origin o = s.origin();
        if (o == null) {
            // The lease was taken without a player ever being frozen (the ownership seam). There is
            // nothing to put back, and teleporting to a remembered nowhere would be a real bug.
            com.moddingfromamod.net.Send.to(player, com.moddingfromamod.net.ModdedClient.captureMode(false));
            return;
        }
        // Warp back to the exact spot AND view he had before — undoing the camera aim.
        player.connection.teleport(o.x(), o.y(), o.z(), o.yaw(), o.pitch());
        player.setInvulnerable(o.wasInvulnerable());
        // AND PUT GRAVITY BACK -- see Origin. The aim raised this to stop the body falling out of
        // frame mid-shot; leaving it raised leaves a child floating after his eyes are handed back.
        player.setNoGravity(o.wasNoGravity());
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0;
        player.setHealth(player.getMaxHealth());   // holding still must never leave a mark
        player.setRemainingFireTicks(0);
        com.moddingfromamod.net.Send.to(player, com.moddingfromamod.net.ModdedClient.captureMode(false));
    }

    // ---- events ----

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // a camera's night vision lasts exactly as long as its take. Before the early
        // return, because the take having ended is precisely when there is nothing ACTIVE.
        Viewpoint.sweepNightVision(event.getServer());
        if ((ACTIVE.isEmpty() && QUEUES.isEmpty()) || ++tickCounter % TICK_INTERVAL != 0) {
            return;
        }
        var server = event.getServer();
        long now = System.currentTimeMillis();

        // ⚠ EXPIRE THE QUEUES SEPARATELY, over their OWN keys. Walking ACTIVE would miss the one
        // case the anti-wedge exists for: release() promotes a waiter immediately, and that waiter
        // has no session until its next claim -- so a wish that was offered the camera and never
        // came back for it would hold it with nothing looking at it. The queue guards against
        // exactly that and could not be reached to do it.
        for (UUID id : new ArrayList<>(QUEUES.keySet())) {
            CameraQueue q = QUEUES.get(id);
            if (q == null) {
                continue;
            }
            Session s = ACTIVE.get(id);
            // A lease with no freeze behind it -- a camera, a probe -- gets the longer backstop.
            long holdCap = (s == null || s.origin() == null) ? MAX_CAMERA_HOLD_MS : MAX_SESSION_MS;
            if (q.expire(now, holdCap, MAX_WAIT_MS)) {
                LOG.warn("camera queue expired a stale holder or waiter on {} (depth now {})",
                        id, q.depth());
            }
            if (q.idle() && s == null) {
                QUEUES.remove(id);   // nobody holding, nobody waiting, nothing frozen
            }
        }

        for (UUID id : new ArrayList<>(ACTIVE.keySet())) {
            Session s = ACTIVE.get(id);
            if (s == null) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                ACTIVE.remove(id);   // left the world; nothing to restore
                AIMED.remove(id);
                QUEUES.remove(id);   // ...and nothing for the queue behind it to wait for
                continue;
            }
            if (shouldExit(s.startedMs(), s.lastActivityMs(), now)) {
                exit(player, true);   // the watchdog always wins: the freeze can never trap him
                continue;
            }
            // Hold him at his spot (belt-and-braces with the client input freeze) — but keep the
            // camera's CURRENT aim so the agent's framing survives. Only nudge if he actually drifted.
            Origin o = s.origin();
            if (o == null) {
                continue;   // a lease with nobody frozen: nothing to hold in place
            }
            double[] to = holdAt(o.x(), o.y(), o.z(), AIMED.get(id),
                    player.getX(), player.getY(), player.getZ());
            if (to != null) {
                player.connection.teleport(to[0], to[1], to[2],
                        player.getYRot(), player.getXRot());
                player.setDeltaMovement(0, 0, 0);
            }
        }
    }

    /** He cannot die during capture — cancel it and heal, so a stray hit while frozen is harmless. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isCapturing(player.getUUID())) {
            event.setCanceled(true);
            player.setHealth(player.getMaxHealth());
        }
    }

    /** Logged out mid-capture: drop the session so nothing is left dangling. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Session s = ACTIVE.remove(player.getUUID());
            QUEUES.remove(player.getUUID());
            if (s != null && s.origin() != null) {
                player.setInvulnerable(s.origin().wasInvulnerable());
                player.setNoGravity(s.origin().wasNoGravity());
            }
        }
    }

    /** Reset on world shutdown, so a stale session can't linger into the next load. */
    public static void clear() {
        ACTIVE.clear();
        QUEUES.clear();
    }

    /**
     * Forget ONE player's session — the seam a GameTest needs instead of {@link #clear}.
     *
     * <p>{@code ACTIVE} is keyed by player, so two tests using two fake players never collide;
     * {@code clear()} is the only thing that makes them. It used to be called as tidy-up at the end
     * of a concurrency test, which could flip a neighbour's *"a second claim must be refused"*
     * assertion into a pass by releasing the camera underneath it. Deliberately not the ownership
     * check {@link #exit} enforces — this is a fixture putting down its own fake player, not a wish
     * releasing a real one.
     */
    public static void forgetForTest(UUID playerId) {
        if (playerId != null) {
            ACTIVE.remove(playerId);
            QUEUES.remove(playerId);
        }
    }
}
