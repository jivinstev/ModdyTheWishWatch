package com.moddingfromamod.runtime;

/**
 * <b>Where a creature stands when it is spawned for the child — never around him</b>.
 *
 * <p>an earlier issue's giant sea creature (scale 4.0) was put two blocks in front of the child by
 * {@code wishmob spawn}, whatever its size; production's log placed it 2.2 m from him. A model that
 * big, centred that close, has his eyes inside it, and from inside the rig's boxes are not drawn:
 * <i>"I can't see the sea creature you made"</i>. {@code sculpt_creature} already stood a creature
 * back far enough to be photographed; nothing else did, and nothing at all measured its LENGTH.
 *
 * <p>Pure, so the rule is a unit test ({@code SpawnSpotTest}). The distance is the larger of what
 * frames it ({@link Framing#distanceFor}) and its full horizontal reach plus {@link #CLEARANCE}
 * — full rather than half, because the carrier stands at the creature's mass, which may sit anywhere
 * along it. An AIMED spot (the wish's locked anchor) wins, as the manifest rule says,
 * unless it is so close to him that the creature would swallow him.
 */
public final class SpawnSpot {

    /** Space between the creature's furthest reach and the child's eyes, in blocks. */
    public static final double CLEARANCE = 1.5;

    private SpawnSpot() {
    }

    /** A place in the world. */
    public record Spot(double x, double y, double z) {
    }

    /**
     * How far from the child a creature must stand to be seen whole and not around him.
     *
     * @param heightBlocks its drawn height, scale included
     * @param spanBlocks   its longest horizontal extent, scale included
     */
    public static double standOff(double heightBlocks, double spanBlocks) {
        return Math.max(Framing.distanceFor(heightBlocks), Math.max(0, spanBlocks) + CLEARANCE);
    }

    /**
     * Where the {@code index}th creature of a spawn stands.
     *
     * @param px/py/pz  the child
     * @param yawDeg    which way he faces (Minecraft yaw: 0 = +z)
     * @param aimed     the wish's locked anchor, or null
     * @param standOff  {@link #standOff}
     * @param spacing   how far apart a herd stands (its span, at least one block)
     */
    public static Spot choose(double px, double py, double pz, float yawDeg, Spot aimed,
                              double standOff, double spacing, int index) {
        double rad = Math.toRadians(yawDeg);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        Spot base;
        if (aimed != null && Math.hypot(aimed.x() - px, aimed.z() - pz) >= standOff) {
            base = aimed;
            // face the herd's rows along the line from him to the aimed spot
            double len = Math.hypot(aimed.x() - px, aimed.z() - pz);
            fx = (aimed.x() - px) / len;
            fz = (aimed.z() - pz) / len;
        } else {
            base = new Spot(px + fx * standOff, aimed != null ? aimed.y() : py,
                    pz + fz * standOff);
        }
        double gap = Math.max(1.0, spacing);
        double side = ((index % 3) - (index % 3 == 2 ? 3 : 0)) * gap;   // 0, +1, -1, 0, +1, …
        double back = (index / 3) * gap;
        // side is perpendicular to forward (fz, -fx); back is further away from him
        return new Spot(base.x() + fz * side + fx * back, base.y(),
                base.z() - fx * side + fz * back);
    }
}
