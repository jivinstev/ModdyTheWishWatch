package com.moddingfromamod.vanilla;

import java.util.Collection;

/**
 * <b>Every move of a rig atom carries its rotation.</b>
 *
 * <p>Vanilla's tracker sends a moving entity a position-only packet when its yaw has not changed since it was last
 * sent, and 1.21.8's client handles a position-only move by re-targeting the entity's yaw to its CURRENT yaw
 * ({@code ClientPacketListener.handleMoveEntity}: {@code moveOrInterpolateTo(pos, entity.getYRot(), …)}). A display
 * eases into each rotation over its {@code teleport_duration} (3 ticks for a rig, {@link DisplayRig#MOTION_TICKS}), so
 * the next position-only move, one tick later, re-targets the yaw to wherever the ease had reached and the turn stops
 * there. Measured on the client: the hand-driven walk film (a big biped) turns 180 degrees at each end, and every part was drawn a median
 * 65.7 degrees off the server's facing for the whole of the next leg (he walked sideways). A part that took the turn a
 * client tick late (a server tick's packets split across two client ticks, or network jitter) froze at a different
 * angle from the rest of the body: parts held 10 degrees apart for seconds.
 *
 * <p>So a rig atom that moved is always sent its rotation with the move ({@code PosRot}, two bytes more than
 * {@code Pos}); the client then eases toward the true yaw on every move, and a late part is put right by the next one.
 * With it the same film's median is 0.1 degrees. A rig that did not move sends nothing extra.
 *
 * <p>On by default since the Bedrock extension stopped forwarding a relative move's Java yaw as the atom's entity
 * rotation; before that it would have turned a Bedrock creature twice. {@code
 * -Dmoddingfromamod.rigrotwithmove=off} is the A/B arm.
 */
public final class RigMoveRotation {

    private RigMoveRotation() {
    }

    public static final boolean ON = !"off".equals(System.getProperty("moddingfromamod.rigrotwithmove", "on"));

    /** {@code ServerEntity}'s own "did it move" threshold, the squared length of the encoded delta. */
    static final double MOVE_EPSILON_SQ = 7.6293945E-6F;

    public static boolean moved(double deltaLengthSqr) {
        return deltaLengthSqr >= MOVE_EPSILON_SQ;
    }

    /**
     * A last-sent yaw guaranteed to differ from {@code packed} by at least the one unit vanilla compares against, so its
     * own branch picks the packet that carries the rotation.
     */
    public static byte staleYaw(byte packed) {
        return (byte) (packed + 64);
    }

    /** Is this tag set a rig atom's (a display carrying its creature's owner tag)? */
    public static boolean isRigAtom(Collection<String> tags) {
        for (String t : tags) {
            if (t.startsWith(DisplayRig.RIG_OWNER_PREFIX)) {
                return true;
            }
        }
        return false;
    }
}
