package com.moddingfromamod.world;

/**
 * A column-aligned rectangle, inclusive on both ends, covering every Y — the shape of a base
 * (it was {@code IslandGrid.Region}, lifted here unchanged so every provider of
 * {@link Bases} speaks it). Minecraft-free.
 */
public record Region(int minX, int maxX, int minZ, int maxZ) {

    public boolean contains(double x, double z) {
        return x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
    }

    /** Every column within {@code radius} (a square) of {@code (x, z)} is inside. */
    public boolean containsBox(double x, double z, double radius) {
        double r = Math.max(0, radius);
        return x - r >= minX && x + r < maxX + 1 && z - r >= minZ && z + r < maxZ + 1;
    }

    public Region shrink(int by) {
        return new Region(minX + by, maxX - by, minZ + by, maxZ - by);
    }

    /** "(x1, z1) to (x2, z2)" — for a refusal the agent reads. */
    public String describe() {
        return "(" + minX + ", " + minZ + ") to (" + maxX + ", " + maxZ + ")";
    }
}
