package com.moddingfromamod.item;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.builder.BuildFraming;
import com.moddingfromamod.runtime.OrbitCamera;

/**
 * <b>Where to stand to photograph a MADE machine</b> — the item shape {@code machine}: a
 * three-block body with its handle and sign on one face, standing on an armor-stand anchor.
 *
 * <p>Moved here from the Create contraption framing when Create support was removed; the
 * numbers are unchanged. A made machine is shot from CLOSE and LOW: a building's 24-block floor
 * would frame a speck, and an overhead angle hides the handle and the sign, which are the whole of
 * what it is.
 */
public final class MadeMachineFraming {

    /** The angle a machine is looked at from. Lower than a building's 18°. */
    static final double ELEVATION_DEG = 12.0;
    /** How far a made machine is shot from: three blocks tall, so anything further is a speck. */
    static final double MADE_MACHINE_DISTANCE = 4.0;
    /** A standing player's eye above his feet. */
    static final double EYE_HEIGHT = 1.62;

    /**
     * Four poses around a made machine. The FIRST pose looks at its front face, because the first
     * frame is the one the tower and the public site lead with, and a machine shot from behind is a
     * blue pillar. {@code frontBearing} is the orbit bearing whose camera stands in front of it.
     */
    public static List<BuildFraming.Pose> orbit(int anchorX, int anchorY, int anchorZ,
                                                double frontBearing) {
        List<BuildFraming.Pose> out = new ArrayList<>();
        double cx = anchorX + 0.5;
        double cz = anchorZ + 0.5;
        double dist = MADE_MACHINE_DISTANCE;
        // Aim at the middle of the three-block body. A pose's y is where the camera STANDS and its
        // eye is a player's height above that — nothing at a castle's distance, but at four blocks
        // it put the whole machine below the middle of the frame (measured, twice).
        double aimY = anchorY + 1.5;
        double eyeY = aimY + dist * Math.tan(Math.toRadians(ELEVATION_DEG)) - EYE_HEIGHT;
        for (int stop = 0; stop < OrbitCamera.STOPS; stop++) {
            double deg = (frontBearing + OrbitCamera.bearing(stop)) % 360.0;
            double rad = Math.toRadians(deg);
            out.add(new BuildFraming.Pose(cx + dist * Math.sin(rad), eyeY, cz - dist * Math.cos(rad),
                    (float) deg, (float) ELEVATION_DEG, BuildFraming.stopName(stop)));
        }
        return out;
    }

    private MadeMachineFraming() {
    }
}
