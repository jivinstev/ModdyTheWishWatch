package com.moddingfromamod.wish;

/**
 * <b>When should a camera client be running?</b> — an earlier issue's rule, Minecraft-free and unit-tested.
 *
 * <p>{@code CameraSupervisor} owns the process; this owns only the decision, because the decision
 * is the part with edge cases and the part a gate can assert without spawning a Minecraft.
 *
 * <h2>The rule as proposed, and why it needed four changes</h2>
 *
 * <p>The proposal was: <i>start the camera the second a vanilla client joins; close it when the
 * last vanilla client exits AND the last wish from a vanilla client exits.</i> That is the right
 * shape — demand-driven, and draining wishes rather than cutting them off — and four things it does
 * not say are what make it safe:
 *
 * <ol>
 *   <li><b>"A client" must mean A CHILD.</b> The camera is itself a client, so a rule keyed on
 *       "somebody joined" starts a second camera when the first one arrives, and a third when that
 *       one does. {@link com.moddingfromamod.marshal.PlayerKind} already draws the line and this
 *       counts only the children side of it.</li>
 *   <li><b>Stopping needs a LINGER, or a relog costs 13 seconds.</b> A child who drops and comes
 *       straight back would stop and restart the process, and the restart is not free — a camera
 *       pays ~13 s to join. Worse, the wish he reconnects to would run without one. So the stop is
 *       delayed by {@link #LINGER_MS} of genuine idleness.</li>
 *   <li><b>Draining needs a CAP.</b> "Wait for the last wish" is unbounded if a wish wedges, and a
 *       camera that never exits is exactly the leak this issue is about. {@link #DRAIN_CAP_MS}
 *       bounds it and the caller reports that it did.</li>
 *   <li><b>A dead camera must be RESTARTED, not silently tolerated.</b> {@code Viewpoint.acquire}
 *       falls back to the child when there is no camera, which is correct degradation and
 *       indistinguishable from success — an earlier issue's own complaint. Death while children are online is
 *       a START, and repeated failure has to give up loudly rather than respawn a JVM forever.</li>
 * </ol>
 *
 * <h2>⚠ The coupling that is easy to miss</h2>
 *
 * <p>an earlier issue's pack deferral is only safe while a camera is <b>actually running</b>. On
 * {@code CHILDS_EYES} the child IS the camera, so withholding his pack means the agent critiques a
 * creature made of paper — an earlier issue's bug, induced deliberately, on the loop whose whole job is to
 * notice things being wrong. So the deferral must ask "is there a camera right now", never "was one
 * requested" — and the 13 s join window, plus every restart, is a period where the honest answer is
 * no. {@link #cameraIsServing} is that question.
 */
public final class CameraLifecycle {

    private CameraLifecycle() {
    }

    /**
     * How long the camera stays up after the last child leaves.
     *
     * <p>Long enough to cover a relog and a brief disconnect, short enough that an empty server
     * does not hold a Minecraft for the afternoon. A restart costs ~13 s of join; two minutes of
     * idle is cheap against paying that for someone who dropped out of a lift.
     */
    public static final long LINGER_MS = 120_000;

    /**
     * The longest we wait for wishes to drain before stopping anyway.
     *
     * <p>A wish is ~106 s and the agent has its own timeouts, so five minutes is far past a healthy
     * one. This exists for the wedged case: "wait for the last wish" with no cap is a camera that
     * never exits, which is the leak is about.
     */
    public static final long DRAIN_CAP_MS = 300_000;

    /**
     * How many consecutive failed starts before giving up.
     *
     * <p>A camera that cannot start (no display, no jar, the port refused, out of memory) will not
     * start on the fourth attempt either, and spawning a Minecraft every few seconds forever is
     * worse than not having one. After this the supervisor says so LOUDLY and stops trying, because
     * a silent absence is the failure mode being removed.
     */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** How long to allow for a start before calling it failed. A camera joins in ~13 s. */
    public static final long START_TIMEOUT_MS = 90_000;
    /**
     * The same for a camera on its OWN VM ({@code camera.demand=wishes}): measured on a
     * test server, a member resumed with a stale client took 53–57 s to be ready and 68 s to join,
     * and a freshly CREATED member ~95 s — so the 90 s above shot body two just before it arrived.
     */
    public static final long VM_START_TIMEOUT_MS = 180_000;

    private static volatile boolean vmBodies;

    /** Are the bodies camera VMs? Set by the supervisor each look; decides {@link #startTimeoutMs}. */
    public static void vmBodies(boolean vm) {
        vmBodies = vm;
    }

    public static long startTimeoutMs() {
        return vmBodies ? VM_START_TIMEOUT_MS : START_TIMEOUT_MS;
    }

    /** What the supervisor should do. */
    public enum Action {
        /** Nothing to do. */
        NONE,
        /** Launch a camera. */
        START,
        /** Shut the camera down. */
        STOP,
        /** It should be up, it is not, and we have given up. Say so and do not retry. */
        GIVE_UP
    }

    /** What the supervisor can see. */
    public record State(
            /** Children — never the camera itself — currently connected. */
            int childrenOnline,
            /** Wishes still running, whoever owns them. */
            int wishesInFlight,
            /** Is a camera process alive and joined? */
            boolean cameraUp,
            /** Is a start in progress (spawned, not yet joined)? */
            boolean starting,
            /** When the last child disconnected, or 0 if one is online. */
            long idleSinceMs,
            /** When the current start was launched, or 0. */
            long startedAtMs,
            /** Consecutive failed starts. */
            int failures) {
    }

    /**
     * Decide.
     *
     * <p>Ordered deliberately: giving up outranks starting, a start in flight outranks a second
     * start, and a stop is the last thing considered — so a camera is never stopped in the same
     * breath as being wanted.
     */
    public static Action decide(State s, long nowMs) {
        return decide(s, nowMs, LINGER_MS);
    }

    /**
     * The same, with the linger as a parameter — the wishes mode ({@link CameraWait}) counts
     * wishes in flight as {@code childrenOnline} ("who wants a camera") and lingers 60 s, not two
     * minutes, because a camera VM that is not needed should be asleep.
     */
    public static Action decide(State s, long nowMs, long lingerMs) {
        boolean wanted = s.childrenOnline() > 0;

        if (s.starting()) {
            // A start that has overrun is a FAILURE, not a reason to start again. The supervisor
            // records the failure and this method is asked afresh.
            return nowMs - s.startedAtMs() > startTimeoutMs() ? Action.STOP : Action.NONE;
        }
        if (!s.cameraUp()) {
            if (!wanted) {
                return Action.NONE;
            }
            return s.failures() >= MAX_CONSECUTIVE_FAILURES ? Action.GIVE_UP : Action.START;
        }

        // It is up. Keep it while anybody is here.
        if (wanted) {
            return Action.NONE;
        }
        // Nobody is here. Drain the wishes that are still running -- but not forever.
        long idleFor = s.idleSinceMs() == 0 ? 0 : nowMs - s.idleSinceMs();
        if (s.wishesInFlight() > 0 && idleFor < DRAIN_CAP_MS) {
            return Action.NONE;
        }
        return idleFor >= lingerMs ? Action.STOP : Action.NONE;
    }

    /**
     * <b>Is a camera serving RIGHT NOW?</b> — the question an earlier issue's deferral must ask.
     *
     * <p>Deliberately not "is one configured" or "did we ask for one". A camera that is starting,
     * dead, or given up on is one the agent will photograph THROUGH THE CHILD, and withholding the
     * child's pack in that window makes the self-critique loop look at paper.
     */
    public static boolean cameraIsServing(State s) {
        return s.cameraUp() && !s.starting();
    }

    /** Was this start attempt overdue? Split out so the supervisor and a test agree on the clock. */
    public static boolean startTimedOut(State s, long nowMs) {
        return s.starting() && nowMs - s.startedAtMs() > startTimeoutMs();
    }
}
