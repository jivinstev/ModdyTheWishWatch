package com.moddingfromamod.redstone;

/** A block position in the world, pure (no Minecraft types), so the planner is unit-testable. */
public record RsPos(int x, int y, int z) {

    public RsPos plus(int dx, int dy, int dz) {
        return new RsPos(x + dx, y + dy, z + dz);
    }

    public RsPos step(RsDir d) {
        return plus(d.dx(), 0, d.dz());
    }

    public RsPos up(int n) {
        return plus(0, n, 0);
    }

    public int manhattan(RsPos o) {
        return Math.abs(x - o.x) + Math.abs(y - o.y) + Math.abs(z - o.z);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
