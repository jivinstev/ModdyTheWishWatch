package com.moddingfromamod.builder;

/**
 * <b>How far along a ray the world is already loaded</b> — Minecraft-free.
 *
 * <p>{@link Builder#aimAnchor} casts a ray from the child's eye along his gaze. A plain
 * {@code Level.clip} reads every block it crosses with {@code getBlockState}, and on a server that
 * LOADS (or generates) any chunk the ray enters on the server thread. A load test caught it in
 * {@code jstack}: ten new players on a server wished 20 s after arriving, each ray reached 24 blocks
 * into chunks still queued for generation, and the server froze 35 s and dropped every player
 * (measured). So the ray is first walked across the CHUNK grid, asking only
 * "is this chunk already loaded", and cut where it would first enter one that is not.
 */
public final class LoadedRay {

    /** Asked of each chunk the ray crosses, in order; must not load anything. */
    @FunctionalInterface
    public interface ChunkLoaded {
        boolean loaded(int chunkX, int chunkZ);
    }

    /** How far inside the last loaded chunk a cut ray stops, in blocks. */
    static final double BACK_OFF = 1.0e-3;

    private LoadedRay() {
    }

    /**
     * The fraction {@code t} in {@code [0, 1]} of the horizontal segment {@code (x0, z0) → (x1, z1)}
     * that runs through loaded chunks only, starting from the first: 1 when every chunk it crosses is
     * loaded, 0 when the one it starts in is not, and otherwise a point just short
     * ({@link #BACK_OFF} blocks) of the boundary of the first unloaded chunk. A chunk is asked about at
     * most once, in the order the ray reaches it.
     */
    public static double loadedFraction(double x0, double z0, double x1, double z1, ChunkLoaded loaded) {
        int cx = floorDiv16(x0);
        int cz = floorDiv16(z0);
        if (!loaded.loaded(cx, cz)) {
            return 0.0;
        }
        int endX = floorDiv16(x1);
        int endZ = floorDiv16(z1);
        double dx = x1 - x0;
        double dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dz * dz);
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        // Amanatides–Woo over 16-block cells: the t at which the ray crosses the next X (Z) boundary.
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY
                : ((stepX > 0 ? (cx + 1) * 16.0 : cx * 16.0) - x0) / dx;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : ((stepZ > 0 ? (cz + 1) * 16.0 : cz * 16.0) - z0) / dz;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dx);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dz);
        // A segment crosses at most |Δcx| + |Δcz| boundaries; the bound keeps a rounding slip finite.
        int guard = Math.abs(endX - cx) + Math.abs(endZ - cz) + 2;
        while ((cx != endX || cz != endZ) && guard-- > 0) {
            double t;
            if (tMaxX < tMaxZ) {
                t = tMaxX;
                cx += stepX;
                tMaxX += tDeltaX;
            } else {
                t = tMaxZ;
                cz += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (t >= 1.0) {
                return 1.0;
            }
            if (!loaded.loaded(cx, cz)) {
                return Math.max(0.0, t - BACK_OFF / len);
            }
        }
        return 1.0;
    }

    private static int floorDiv16(double v) {
        return Math.floorDiv((int) Math.floor(v), 16);
    }
}
