package com.moddingfromamod.marshal;

/**
 * Where a wish is going to land — dimension plus block position, as plain numbers.
 *
 * <p>Deliberately not a {@code BlockPos}: the whole marshal is Minecraft-free so its scheduling,
 * cancel and anchoring rules are unit-tested in milliseconds. The game-facing code converts at the
 * edge.
 *
 * <p>This is the <b>locked</b> spot — the one the watch was aimed at when the wish was made, or the
 * one the agent moved it to with {@code conjure_at}. It is never recomputed from the live player,
 * which is the "mid-air McTrain" bug: a build anchored at wish time and then re-anchored at build
 * time appears wherever the child happened to wander to.
 */
public record Spot(String dimension, int x, int y, int z) {

    public Spot {
        dimension = dimension == null ? "" : dimension;
    }

    /** Squared distance in the horizontal-and-vertical sense, or -1 across dimensions. */
    public double distanceSqTo(double px, double py, double pz, String dim) {
        if (dim != null && !dim.isEmpty() && !dimension.isEmpty() && !dim.equals(dimension)) {
            return -1;
        }
        double dx = x + 0.5 - px;
        double dy = y + 0.5 - py;
        double dz = z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
