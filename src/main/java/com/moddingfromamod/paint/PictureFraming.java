package com.moddingfromamod.paint;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.builder.BuildFraming;
import com.moddingfromamod.wish.reveal.RevealFrame;

/**
 * <b>Where the camera stands to photograph a hung picture</b>. Minecraft-free.
 *
 * <p>Found by the first real picture wish on Gate D: {@code finish_building} was handed the
 * picture's id and answered <i>"Couldn't find a 'purple_octopus_tophat' in the world to point at,
 * so this is whatever the child was already looking at"</i> — the framing knew buildings, trains,
 * machines and creatures, and a picture fell through to the unframed shot. The picture was
 * correct and on the wall; the reveal photographed something else.
 *
 * <p>A picture is flat and only has a front, so this is not an orbit: the first pose stands square
 * in front of it with its eye level with the board's middle (— never the child's eye), far
 * enough back that the whole board fits a 70° lens, and two more look at it from a little to either
 * side. The first frame is the one the tower and the site lead
 * with.
 */
public final class PictureFraming {

    /** Minecraft's vertical field of view at the default setting, halved. */
    private static final double HALF_V_FOV = Math.toRadians(35.0);
    /** The horizontal half-angle at 16:9 for that vertical FOV. */
    private static final double HALF_H_FOV = Math.atan(Math.tan(HALF_V_FOV) * 16.0 / 9.0);
    /** Room around the board, so its edge is not the edge of the photograph. */
    private static final double MARGIN = 1.3;
    private static final double MIN_DISTANCE = 3.0;
    /** How far to either side the second and third frames stand. */
    private static final double SIDE_DEG = 25.0;
    /** A standing player's eye above his feet: a pose's y is where the camera STANDS. */
    private static final double EYE_HEIGHT = 1.62;

    private PictureFraming() {
    }

    /**
     * Poses for a picture whose BOTTOM-LEFT block (as a viewer sees it) is {@code x,y,z}, which
     * looks toward {@code facing} and is {@code wide} by {@code tall} blocks. Empty for a facing
     * that is not north, south, east or west.
     */
    public static List<BuildFraming.Pose> poses(int x, int y, int z, String facing, int wide, int tall) {
        return poses(RevealFrame.forPicture(facing, x, y, z, wide, tall));
    }

    /**
     * <b>Poses framed on the BOARD</b> — the same {@link RevealFrame} the reveal's beads
     * are traced from ({@code Reveals.boardOf}), so the camera and the reveal can never disagree
     * about where a picture is. Empty for null.
     *
     * <p>The camera's EYE is level with the board's middle and looks square at it (pitch 0), and it
     * stands back until the board's whole height fits the vertical field of view and its whole width
     * fits the horizontal one. The child's eye is never the reference: the first cut was reached
     * only when the agent named the picture, and when it did not, the shutter fired from where he
     * stood at pitch 0 — and an 8 × 16 BlockyBob came back as everything below the chest.
     */
    public static List<BuildFraming.Pose> poses(RevealFrame board) {
        if (board == null) {
            return List.of();
        }
        double cx = board.cx(), cy = board.cy(), cz = board.cz();
        double wide = Math.max(1, board.boardWide()), tall = Math.max(1, board.boardTall());
        double dist = distance(wide, tall);
        List<BuildFraming.Pose> out = new ArrayList<>();
        String[] names = {"front", "left", "right"};
        double[] offsets = {0, -SIDE_DEG, SIDE_DEG};
        double base = Math.atan2(board.fx(), board.fz());   // the direction the picture faces
        for (int i = 0; i < offsets.length; i++) {
            double a = base + Math.toRadians(offsets[i]);
            double px = cx + dist * Math.sin(a);
            double pz = cz + dist * Math.cos(a);
            float yaw = yawToward(cx - px, cz - pz);
            out.add(new BuildFraming.Pose(px, cy - EYE_HEIGHT, pz, yaw, 0f, names[i]));
        }
        return out;
    }

    /** How far back the camera stands so a {@code wide × tall} board fills the frame with room. */
    static double distance(double wide, double tall) {
        return Math.max(MIN_DISTANCE, MARGIN * Math.max(
                (tall / 2.0) / Math.tan(HALF_V_FOV), (wide / 2.0) / Math.tan(HALF_H_FOV)));
    }

    /** The vertical half field of view the framing assumes, in radians (for the test's own check). */
    static double halfVerticalFov() {
        return HALF_V_FOV;
    }

    /** The horizontal half field of view at 16:9, in radians. */
    static double halfHorizontalFov() {
        return HALF_H_FOV;
    }

    /** A standing player's eye above his feet, which a pose's y is measured from. */
    static double eyeHeight() {
        return EYE_HEIGHT;
    }

    /** The picture's middle, for aiming anything else at it: {x, y, z}. */
    public static double[] centre(int x, int y, int z, String facing, int wide, int tall) {
        int[] f = vector(facing);
        if (f == null) {
            return null;
        }
        int[] right = {f[1], -f[0]};
        return new double[]{x + 0.5 + right[0] * (wide - 1) / 2.0, y + tall / 2.0,
                z + 0.5 + right[1] * (wide - 1) / 2.0};
    }

    /** Minecraft's yaw for looking along (dx, dz): south is 0, west 90, north 180, east 270. */
    static float yawToward(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(-dx, dz));
        return (float) ((deg % 360.0 + 360.0) % 360.0);
    }

    private static int[] vector(String facing) {
        if (facing == null) {
            return null;
        }
        return switch (facing.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "east" -> new int[]{1, 0};
            case "west" -> new int[]{-1, 0};
            default -> null;
        };
    }
}
