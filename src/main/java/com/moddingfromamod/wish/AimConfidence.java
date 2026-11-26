package com.moddingfromamod.wish;

import java.util.List;

/**
 * <b>Do we confidently know he is looking at what his gaze hit?</b> (#114, the owner's rule). A confident hit settles
 * where he looked however far below him; an unconfident one is held level, as a miss is (#46). Pure, so the rule is a
 * unit test ({@code AimConfidenceTest}). All four must hold:
 * <ol>
 *   <li><b>A solid face</b>: the ray ended on a block a thing can stand on, not water, leaves or a plant.</li>
 *   <li><b>Square on, not grazing an edge</b>: the hit point is at least {@link #EDGE} of a block in from every edge of
 *       the face, and his gaze meets the face at least {@link #MIN_INCIDENCE_DEGREES} off its plane.</li>
 *   <li><b>Within reach</b>: no farther than {@link WishAim#REACH} from his eyes.</li>
 *   <li><b>Steady</b>: over the last {@link #STEADY_TICKS} ticks his look moved no more than {@link #STEADY_DEGREES}
 *       in yaw or pitch: a gaze, not a single-tick flick.</li>
 * </ol>
 *
 * <p><b>The four numbers are FIRST GUESSES</b> (2026-11-25), named here so they can be tuned in one place: checked in a
 * scripted real client (Gate C, {@code gatec/SkyAimClientTest}), not yet against a person's hand. Touch aiming (a
 * Bedrock client) was not measured; it is jumpier, and {@link #STEADY_TICKS}/{@link #STEADY_DEGREES} are the threshold
 * most likely to need loosening there.
 */
public final class AimConfidence {

    /** How far in from a face's edge the hit must be, in blocks. A first guess. */
    public static final double EDGE = 0.1;

    /** How far off the face's plane the gaze must meet it, in degrees. A first guess. */
    public static final double MIN_INCIDENCE_DEGREES = 15;

    /** How many ticks of looking make a gaze. A first guess (a quarter of a second). */
    public static final int STEADY_TICKS = 5;

    /** How far his look may wander over those ticks, in degrees. A first guess. */
    public static final float STEADY_DEGREES = 3f;

    private AimConfidence() {
    }

    /** One tick of where he looked. */
    public record Look(float yaw, float pitch) {
    }

    /**
     * The verdict.
     *
     * @param solidFace  the hit block is one a thing can stand on
     * @param u          where on the face the ray hit, 0..1 along its first in-plane axis
     * @param v          …and along its second
     * @param incidence  the angle between his gaze and the face's plane, in degrees (90 = square on)
     * @param distance   from his eyes to the hit, in blocks
     * @param recent     where he looked over the last ticks, oldest first, the newest being the press
     */
    public static boolean confident(boolean solidFace, double u, double v, double incidence, double distance,
                                    List<Look> recent) {
        return solidFace && squareOn(u, v, incidence) && distance <= WishAim.REACH && steady(recent);
    }

    /** On the face, not its edge, and not glancing along it. */
    static boolean squareOn(double u, double v, double incidence) {
        return u >= EDGE && u <= 1 - EDGE && v >= EDGE && v <= 1 - EDGE && incidence >= MIN_INCIDENCE_DEGREES;
    }

    /** At least {@link #STEADY_TICKS} looks, and the last of them all within {@link #STEADY_DEGREES} of the newest. */
    static boolean steady(List<Look> recent) {
        if (recent == null || recent.size() < STEADY_TICKS) {
            return false;
        }
        Look now = recent.get(recent.size() - 1);
        for (int i = recent.size() - STEADY_TICKS; i < recent.size(); i++) {
            Look l = recent.get(i);
            if (Math.abs(wrap(l.yaw() - now.yaw())) > STEADY_DEGREES || Math.abs(l.pitch() - now.pitch()) > STEADY_DEGREES) {
                return false;
            }
        }
        return true;
    }

    /** A yaw difference brought into -180..180. */
    static float wrap(float d) {
        float r = d % 360f;
        if (r > 180f) {
            r -= 360f;
        } else if (r < -180f) {
            r += 360f;
        }
        return r;
    }
}
