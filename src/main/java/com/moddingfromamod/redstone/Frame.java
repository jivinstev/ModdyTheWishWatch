package com.moddingfromamod.redstone;

/**
 * A module's local frame: {@code u} runs along a wall, {@code dy} is up, {@code w} runs inward (into the building).
 * A module is drawn once in these coordinates and placed by the frame, so every rotation uses the same drawing.
 *
 * @param origin the world position of local (0, 0, 0)
 * @param along  the world direction of +u
 * @param in     the world direction of +w (perpendicular to {@code along})
 */
public record Frame(RsPos origin, RsDir along, RsDir in) {

    public Frame {
        if (!along.perpendicular(in)) {
            throw new IllegalArgumentException("along and in must be perpendicular: " + along + ", " + in);
        }
    }

    public RsPos at(int u, int dy, int w) {
        return new RsPos(origin.x() + along.dx() * u + in.dx() * w, origin.y() + dy,
                origin.z() + along.dz() * u + in.dz() * w);
    }

    /** +u is {@code along}, -u its opposite; +w is {@code in}, -w its opposite. */
    public RsDir alongPlus() {
        return along;
    }

    public RsDir alongMinus() {
        return along.opposite();
    }

    public RsDir inward() {
        return in;
    }

    public RsDir outward() {
        return in.opposite();
    }
}
