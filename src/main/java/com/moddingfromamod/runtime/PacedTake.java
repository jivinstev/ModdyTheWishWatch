package com.moddingfromamod.runtime;

/**
 * <b>Which moment a paced take is at</b> — Minecraft-free.
 *
 * <p>An event's frames are MOMENTS, {@code paceMs} apart from the first capture, and the world does not
 * wait for the camera. Measured on llvmpipe: a tracked tidal wave took 20 s to capture 48 frames meant
 * to span 6 s, so pinning pose k for frame k put the camera ~3× behind the real front and it filmed
 * 48 frames of grass. So a slow client skips to the moment that is due, and the skipped moments repeat
 * the last frame: the strip still plays in real time, choppier, and stays in step with the world.
 */
public final class PacedTake {

    /**
     * A rough send-to-first-frame time, for an event that was NOT held for its camera (it has been
     * running; where is it by the first frame?). A held event starts on the camera's own first frame
     * ({@code SpinReadyPayload}), so moment k is tick 2.5·k of it however slowly the first pose settles.
     */
    public static final int LEAD_MS = 800;

    private PacedTake() {
    }

    /** The pose due {@code elapsedMs} after the first capture: never past the last one. */
    public static int due(long elapsedMs, int paceMs, int count) {
        if (count <= 0) {
            return 0;
        }
        if (paceMs <= 0 || elapsedMs <= 0) {
            return 0;
        }
        long k = elapsedMs / paceMs;
        return (int) Math.min(count - 1L, k);
    }
}
