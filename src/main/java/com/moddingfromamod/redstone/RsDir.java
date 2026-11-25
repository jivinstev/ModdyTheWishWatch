package com.moddingfromamod.redstone;

/** The four horizontal directions, named as Minecraft names them (south is +z, east is +x). */
public enum RsDir {
    NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);

    private final int dx;
    private final int dz;

    RsDir(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    public RsDir opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    /** The direction 90 degrees clockwise, seen from above. */
    public RsDir clockwise() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
        };
    }

    public boolean perpendicular(RsDir o) {
        return dx * o.dx + dz * o.dz == 0;
    }

    /** The blockstate value, e.g. {@code east}. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The direction from {@code a} to an adjacent {@code b} on the same level, or null. */
    public static RsDir between(RsPos a, RsPos b) {
        for (RsDir d : values()) {
            if (a.x() + d.dx == b.x() && a.z() + d.dz == b.z()) {
                return d;
            }
        }
        return null;
    }
}
