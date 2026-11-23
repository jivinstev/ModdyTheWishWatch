package com.moddingfromamod.wish;

/**
 * <b>A camera sees in the dark for the length of a take; a child never does.</b>
 *
 * <p>an earlier issue's throne room came back black but for the boss's outline: the room was roofed and
 * unlit. The cause is the building, and {@code build_structure} now says so before the reveal
 * ({@code builder/DarkInterior}). This is the backstop for every room that is ALREADY dark — an
 * old castle, a cave, a room the agent did not fix — so the agent's photograph shows what is in
 * it. A camera is its own player (docs/CAMERA_CLIENT.md), so giving it {@code night_vision} changes
 * no child's game. A child's borrowed eyes are never given it: it would change what he sees.
 *
 * <p>⚠ The honest cost: a photograph is then brighter than the room is to the child. That is why
 * the lighting fix is the build check and the lesson, and this only the backstop — and why the
 * DARK INSIDE note tells the agent to judge light from the note, not from the picture.
 *
 * <p>Pure, so the decision is a Gate A test; {@link Viewpoint#seeInTheDark} applies it.
 */
public final class CameraNightVision {

    /** A/B seam: {@code -Dmoddingfromamod.camera.nightvision=off} photographs a dark room dark. */
    public static final String PROP = "moddingfromamod.camera.nightvision";

    /**
     * How long one shot's night vision lasts, renewed by every shot of the take and taken away when
     * the camera is released. A minute: longer than any take, and far longer than the last
     * {@link #FLICKER_TICKS} in which vanilla makes night vision flicker — a frame shot inside that
     * window would be lit or not by chance.
     */
    public static final int TAKE_TICKS = 20 * 60;

    /** Vanilla fades night vision in and out over its last 10 seconds ({@code getNightVisionScale}). */
    public static final int FLICKER_TICKS = 200;

    private CameraNightVision() {
    }

    /** Does this body get night vision for the take? Only a camera, and only with the seam on. */
    public static boolean appliesTo(boolean isChild, String seam) {
        if (isChild) {
            return false;
        }
        return seam == null || !"off".equalsIgnoreCase(seam.trim());
    }

    /** {@link #appliesTo} with the seam read from the system property, every call. */
    public static boolean appliesTo(boolean isChild) {
        return appliesTo(isChild, System.getProperty(PROP));
    }
}
