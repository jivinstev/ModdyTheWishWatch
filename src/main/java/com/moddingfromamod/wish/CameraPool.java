package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>Which camera a wish gets, and how many cameras there should be</b>, the wish cam
 * pool.
 *
 * <p>Minecraft-free and unit-tested, per this repo's first key rule. {@link Viewpoint#acquire} asks
 * {@link #choose} and {@link CameraSupervisor} asks {@link #decide}; neither decides anything itself.
 *
 * <h2>Why a pool, and why it is bounded</h2>
 *
 * <p> gave the camera a queue, which made a second wish WAIT rather than race — and it named the
 * problem it left: <i>"ONE CAMERA STOPS BEING ENOUGH SOMEWHERE and nothing currently says where."</i>
 * With D10's ten workers and three concurrent wishes per child, a single body means a four-angle
 * orbit waits behind another four-angle orbit, and a wish that gives up waiting falls back to an
 * unframed snapshot. But a camera is a whole Minecraft (~2 GB), so the answer is never one per wish:
 * it is a pool of at most {@link CameraProfile#concurrent()} bodies, grown one at a time <b>only when
 * the queue says a wish is actually waiting</b>, and shrunk back when the extra body is idle.
 *
 * <h2>⚠ N cameras are not N identities to buy or N keys to mint</h2>
 *
 * <p>D14's keypair signs the connection's USERNAME with one deployment key, so every camera in the
 * pool proves itself with the same key under its own name ({@link #names}). That is the whole reason
 * the plan could say a pool is not N purchases.
 */
public final class CameraPool {

    private CameraPool() {
    }

    /** The hard ceiling a world may raise {@link #MAX} to. */
    public static final int HARD_MAX = 16;

    /** The per-world default when nothing is configured: four bodies, the measured pool. */
    public static final int DEFAULT_MAX = 4;

    /**
     * <b>The most bodies THIS world may run</b> — the ceiling {@link CameraProfile} clamps
     * {@code camera.concurrent} to and the number of names registered at mod construction.
     *
     * <p>With the cameras on a shared, scalable VM pool the per-world cap
     * is a SETTING, not a constant: {@code -Dmoddingfromamod.camera.max} (the world manifest's
     * {@code moddy.camera_max}, MODDY_CAMERA_MAX), clamped to 1–{@value #HARD_MAX}, default
     * {@value #DEFAULT_MAX}. Read once at class load — it decides which names are registered as
     * service clients before any join, so it cannot move under a running server; changing it is a
     * restart, never a recompile.
     */
    public static final int MAX = maxFromProperty(System.getProperty("moddingfromamod.camera.max"));

    /** Pure: the configured cap, or the default for anything blank, unparsable or out of range. */
    static int maxFromProperty(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX;
        }
        try {
            return Math.max(1, Math.min(HARD_MAX, Integer.parseInt(raw.strip())));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX;
        }
    }

    /**
     * How long a wish must have been waiting in line before a second body is worth starting.
     *
     * <p>Short, because any wait at all is a whole orbit (~10 s) of somebody else's photographs, and
     * a camera takes ~13 s to join — so a pool that waited for congestion to become obvious would
     * always arrive after it had passed. Not zero, because a burst's own re-claims are not a queue
     * and a single tick of depth is not a measurement.
     */
    public static final long GROW_AFTER_MS = 3_000;

    /**
     * How long an EXTRA body must be idle, with nobody waiting anywhere, before it is stopped.
     *
     * <p>Long against the join cost for the same reason {@link CameraLifecycle#LINGER_MS} is: two
     * children who wish in waves would otherwise pay ~13 s of join on every wave. The first camera
     * is never stopped by this rule — its life is {@link CameraLifecycle}'s.
     */
    public static final long SHRINK_AFTER_MS = 300_000;

    /** Minecraft refuses a username longer than this, so a pool name must fit in it. */
    static final int MAX_NAME = 16;

    /**
     * The name of each body: the configured camera name, then {@code <name>-2}, {@code <name>-3}…
     *
     * <p>⚠ Every one of these is registered as a service client at mod construction, BEFORE any of
     * them joins — the same timing rule the single camera already had, and for the same reason: a
     * camera that is a child for its first few ticks is handed wishes in exactly the window a tool
     * asks in. The base is shortened rather than the suffix dropped when the pair would not fit.
     */
    public static List<String> names(String base, int n) {
        return names(base, n, MAX);
    }

    /** Pure: {@link #names(String, int)} under an explicit cap, so a test can name 16 bodies. */
    static List<String> names(String base, int n, int cap) {
        List<String> out = new ArrayList<>();
        if (base == null || base.isBlank()) {
            return out;
        }
        String b = base.strip();
        int count = Math.max(0, Math.min(Math.min(cap, HARD_MAX), n));
        for (int i = 0; i < count; i++) {
            if (i == 0) {
                out.add(b);
                continue;
            }
            String suffix = "-" + (i + 1);
            String stem = b.length() + suffix.length() > MAX_NAME
                    ? b.substring(0, MAX_NAME - suffix.length()) : b;
            out.add(stem + suffix);
        }
        return out;
    }

    /** One connected camera as the chooser sees it: who holds it and how deep its line is. */
    public record Cam(UUID id, UUID holder, int depth, int positionOfWish) {
        boolean idle() {
            return holder == null && depth == 0;
        }
    }

    /**
     * <b>Which of the connected cameras this wish should use</b>, as an index into {@code cams}, or
     * -1 when there are none.
     *
     * <ol>
     *   <li><b>The one it already holds.</b> A four-angle orbit is several claims in a burst, and a
     *       burst that hopped bodies would aim one camera and shoot through another.</li>
     *   <li><b>An idle one.</b> Before the pool existed this was always "the first non-child in the
     *       player list", so two wishes queued on body one while body two stood idle.</li>
     *   <li><b>The line it is already in.</b> Leaving a line is only worth it for an idle body.</li>
     *   <li><b>The shortest line</b>, ties to the earliest — so waiters spread rather than stack.</li>
     * </ol>
     *
     * <p>⚠ A null wish (a headless probe, a manual command) always gets the FIRST camera. Those share
     * rather than queue ({@link CameraQueue}'s class note), so spreading them buys nothing and would
     * make a gate's two photographs come from two different bodies.
     */
    public static int choose(UUID wish, List<Cam> cams) {
        if (cams == null || cams.isEmpty()) {
            return -1;
        }
        if (wish == null) {
            return 0;
        }
        for (int i = 0; i < cams.size(); i++) {
            if (Objects.equals(cams.get(i).holder(), wish)) {
                return i;
            }
        }
        for (int i = 0; i < cams.size(); i++) {
            if (cams.get(i).idle()) {
                return i;
            }
        }
        for (int i = 0; i < cams.size(); i++) {
            if (cams.get(i).positionOfWish() > 0) {
                return i;
            }
        }
        int best = 0;
        int bestLoad = Integer.MAX_VALUE;
        for (int i = 0; i < cams.size(); i++) {
            Cam c = cams.get(i);
            int load = (c.holder() == null ? 0 : 1) + c.depth();
            if (load < bestLoad) {
                best = i;
                bestLoad = load;
            }
        }
        return best;
    }

    /** What the supervisor should do to the pool this look. */
    public enum Change {
        /** Start one more body. */
        GROW,
        /** Stop the highest-numbered extra body. */
        SHRINK,
        /** Leave it alone. */
        HOLD
    }

    /**
     * What the supervisor measured.
     *
     * @param running      bodies it has a live process for, including one still joining
     * @param joined       bodies actually connected to the server
     * @param starting     whether any body is still joining — the pool grows one at a time
     * @param waiting      wishes queued behind a holder, summed over every body
     *                     ({@link WishCapture#queueDepth}) — the measurement the size comes from
     * @param waitingSinceMs when {@code waiting} last went from zero to non-zero, or 0
     * @param idleSinceMs  when the highest extra body last went idle with nobody waiting, or 0
     * @param max          {@link CameraProfile#concurrent()}
     * @param wanted       bodies the wishes in flight want by their camera time
     *                     ({@link CameraNeed#bodiesFor}) — growth AHEAD of a queue
     */
    public record State(int running, int joined, boolean starting, int waiting,
                        long waitingSinceMs, long idleSinceMs, int max, int wanted) {

        /** Before the camera-time forecast: queue depth alone ({@code wanted} 0 asks for nothing). */
        public State(int running, int joined, boolean starting, int waiting,
                     long waitingSinceMs, long idleSinceMs, int max) {
            this(running, joined, starting, waiting, waitingSinceMs, idleSinceMs, max, 0);
        }
    }

    /**
     * <b>Grow when a wish has been waiting, shrink when an extra body is idle.</b>
     *
     * <p>The first body is not this rule's to start or stop — {@link CameraLifecycle} owns it, and a
     * pool that could start body one would start it while no child is online. So growth needs one
     * body already JOINED, and shrinking never goes below one.
     */
    public static Change decide(State s, long nowMs) {
        int max = Math.max(1, Math.min(MAX, s.max()));
        if (s.running() > max) {
            // The cap was lowered under a running pool (the camera.concurrent knob). Shed one per
            // look rather than all at once, and only when nobody is waiting, so a live orbit is
            // not cut off by an operator's edit.
            return s.waiting() == 0 ? Change.SHRINK : Change.HOLD;
        }
        if (s.running() < 1) {
            return Change.HOLD;      // body one is CameraLifecycle's, never the pool's
        }
        int wanted = Math.min(max, Math.max(0, s.wanted()));
        if (s.running() < wanted) {
            // The forecast: the wishes in flight carry more camera time than these bodies can shoot
            // (CameraNeed). Start the next one NOW — and WITHOUT waiting for the others to join.
            // This used to hold while any body was still starting ("one at a time"), and a test
            // server measured what that costs: seven wishes at once, body one took 68 s to join, and
            // only THEN was body two started — so the forecast bought nothing exactly when it was
            // needed. `running` counts bodies still starting, so this stops at what is wanted.
            return Change.GROW;
        }
        if (s.starting() || s.joined() < 1) {
            return Change.HOLD;
        }
        if (s.waiting() > 0) {
            // The safety net, as before: the forecast was low (a class priced too cheaply, a burst of
            // re-shoots) and somebody is actually waiting.
            boolean waitedLongEnough = s.waitingSinceMs() > 0
                    && nowMs - s.waitingSinceMs() >= GROW_AFTER_MS;
            return waitedLongEnough && s.running() < max ? Change.GROW : Change.HOLD;
        }
        if (s.running() > Math.max(1, wanted) && s.idleSinceMs() > 0
                && nowMs - s.idleSinceMs() >= SHRINK_AFTER_MS) {
            return Change.SHRINK;
        }
        return Change.HOLD;
    }
}
