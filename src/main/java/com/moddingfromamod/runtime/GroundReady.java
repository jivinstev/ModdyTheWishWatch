package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Which chunk sections must be DRAWN before a take's first frame</b> — Minecraft-free.
 *
 * <p>A take waited a fixed number of frames at its first pose, and a frame count says nothing about
 * the world: measured on the bench server, a volcano filmed by a camera that had joined 8 s earlier
 * showed the island filling in section by section across the whole clip, the event's own blocks
 * among them. The client can answer the real question per section ({@code isSectionCompiled}), so
 * the server names the box the take looks at and the client waits — never past a budget — until
 * every section of it inside its own render distance has been drawn.
 *
 * <p>A section is 16³; this returns the CENTRE of each section the box touches, so a client asks
 * about a position certainly inside it. Capped: a huge box is sampled evenly rather than checked
 * whole, because the wait exists to catch a world still arriving, which is never one section.
 */
public final class GroundReady {

    /** More than any framed event touches (a big reach is ~6×2×6); a bigger box is sampled. */
    public static final int MAX_SECTIONS = 256;

    private GroundReady() {
    }

    /** The section centres of the box, clamped to [{@code minY}, {@code topExclusive}). */
    public static List<int[]> sections(int x0, int y0, int z0, int x1, int y1, int z1,
                                       int minY, int topExclusive) {
        int lx = Math.min(x0, x1), hx = Math.max(x0, x1);
        int ly = Math.max(minY, Math.min(y0, y1)), hy = Math.min(topExclusive - 1, Math.max(y0, y1));
        int lz = Math.min(z0, z1), hz = Math.max(z0, z1);
        List<int[]> out = new ArrayList<>();
        if (hy < ly) {
            return out;
        }
        int sx0 = Math.floorDiv(lx, 16), sx1 = Math.floorDiv(hx, 16);
        int sy0 = Math.floorDiv(ly, 16), sy1 = Math.floorDiv(hy, 16);
        int sz0 = Math.floorDiv(lz, 16), sz1 = Math.floorDiv(hz, 16);
        long total = (long) (sx1 - sx0 + 1) * (sy1 - sy0 + 1) * (sz1 - sz0 + 1);
        int stride = (int) Math.max(1, Math.ceil(Math.cbrt(total / (double) MAX_SECTIONS)));
        for (int sx = sx0; sx <= sx1; sx += stride) {
            for (int sy = sy0; sy <= sy1; sy += stride) {
                for (int sz = sz0; sz <= sz1; sz += stride) {
                    out.add(new int[] {sx * 16 + 8, sy * 16 + 8, sz * 16 + 8});
                }
            }
        }
        return out;
    }

    /** Is a section within {@code chunks} (Chebyshev, in chunks) of the camera at ({@code cx}, {@code cz})? */
    public static boolean inRange(int[] section, double cx, double cz, int chunks) {
        int dx = Math.abs(Math.floorDiv(section[0], 16) - Math.floorDiv((int) Math.floor(cx), 16));
        int dz = Math.abs(Math.floorDiv(section[2], 16) - Math.floorDiv((int) Math.floor(cz), 16));
        return Math.max(dx, dz) <= chunks;
    }
}
