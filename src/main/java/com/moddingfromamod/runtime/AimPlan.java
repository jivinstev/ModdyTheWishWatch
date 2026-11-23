package com.moddingfromamod.runtime;

/**
 * <b>How the fallback single shot gets its subject into frame.</b>
 *
 * <p>Minecraft-free and unit-tested. {@code PhotographTool.aimAt} executes the result.
 *
 * <h2>The rule this class used to hold, and why it is gone</h2>
 *
 * <p>Aiming was rotation-only for a child, on a rule that reads as obviously kind:
 *
 * <blockquote>Yanking a child across the world to frame a photo is a far ruder thing to do than
 * handing back a picture that needs a second try.</blockquote>
 *
 * <p>It was already false in practice, and that is the part worth knowing. The ORBIT path — the
 * one that takes the four stills a creature wish card is actually made of — goes through
 * {@code PhotographTool.moveCameraTo}, which teleports whatever body it is handed and has never
 * had a camera guard: on a modded client with no camera in the world, the viewpoint is the child
 * and he is already flown to an orbit seat eighteen blocks out, four times, and put back. So
 * "a camera may travel and a child may not" was a rule about the FALLBACK shot alone, and the
 * fallback is the shot taken when the orbit finds nothing — the worst place to be the strictest.
 *
 * <p>The consequences of keeping it were not theoretical either. A rotation-only viewpoint beyond
 * {@link #VISIBLE_RANGE} turns to face a dot on the horizon and photographs it; the caller could
 * only say <i>this picture is NOT of the creature</i> and hand it over anyway.
 *
 * <h2>What replaced it</h2>
 *
 * <p><b>One rule for both viewpoints</b>, which is §0.5 of {@code docs/VANILLA_COMPAT_METHODOLOGY.md}
 * applied to framing: the internal model and the tool's behaviour may not diverge by who is holding
 * the eyes; only the VIEW may. A child is warped exactly as a camera is — and unlike a camera he
 * is told first, held safe, and put back, because {@code WishCapture.enter} runs before any aim and
 * refuses outright on a client that cannot be shown the wish-cam. <b>A body that can be moved by
 * this plan is, by construction, a body that was asked.</b>
 *
 * <p><b>{@code MOVE_SUBJECT} went with it.</b> Stepping the creature back was the gentler answer
 * only while the alternative was yanking a child; the camera branch already argued the other way —
 * <i>the creature is the thing under inspection and a self-critique loop should not be judging a
 * subject it just teleported</i> — and that argument never depended on who was looking. A subject
 * that has walked nose-to-close is now fixed by the viewpoint stepping BACK to its stand-off, which
 * solves the same framing problem without perturbing the thing being photographed.
 */
public final class AimPlan {

    private AimPlan() {
    }

    /** What the viewpoint should do to get the subject in frame. */
    public enum Move {
        /** It already fits. Turn to face it and shoot. */
        ROTATE_ONLY,
        /** Travel to a good stand-off — a camera freely, a child having been told and held. */
        MOVE_VIEWPOINT
    }

    /**
     * @param move        what to do
     * @param standoff    the distance the shot wants, in blocks
     * @param tooFarToSee true when the subject is beyond {@link #VISIBLE_RANGE} — no longer a
     *                    refusal, since the viewpoint travels either way, but the caller says how
     *                    far it went and a gate can still assert the staging case was recognised
     */
    public record Plan(Move move, double standoff, boolean tooFarToSee) {
    }

    /**
     * How far a subject can be and still be worth photographing without travelling.
     *
     * <p>Tied to what the camera can actually be shown: {@code PhotoClient} raises the render
     * distance to 32 chunks for a burst, so past 512 blocks the world is not drawn at all and the
     * frame is sky. Well inside that, a creature is a handful of pixels — {@link Framing} caps a
     * portrait stand-off at {@code MAX_DISTANCE} for exactly that reason — so this is deliberately
     * far tighter than the render distance and generous against any real wish anchor.
     */
    public static final double VISIBLE_RANGE = 96.0;

    /**
     * Decide.
     *
     * <p>⚠ <b>There is deliberately no {@code isCamera} parameter.</b> It used to be the whole
     * point of this class, and its removal is the change: a plan that cannot ask who is looking
     * cannot start differing by it again, which is how the old rule quietly became a rule about the
     * camera the day {@code aimAt} was handed a viewpoint instead of a child.
     *
     * @param heightBlocks how tall the subject really is
     * @param distance     how far the viewpoint currently is from it
     */
    public static Plan plan(double heightBlocks, double distance) {
        double want = Framing.distanceFor(heightBlocks);
        boolean tooFar = distance > VISIBLE_RANGE;
        boolean tooClose = !Framing.fitsInFrame(heightBlocks, distance);
        return new Plan(tooFar || tooClose ? Move.MOVE_VIEWPOINT : Move.ROTATE_ONLY, want, tooFar);
    }
}
