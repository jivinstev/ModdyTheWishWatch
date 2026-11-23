package com.moddingfromamod.builder;

import java.util.List;

/**
 * <b>Where a REPLAYED seated part goes</b> (option C).
 *
 * <p>A part's recipe remembers its seat — {@code wishmob spawn boss at castle~1a2b:throne} — so a
 * replay ("Give me one" from his history) used to go to THAT castle wherever he was: 500 blocks off
 * where he sees nothing, or the first of his castles when he is looking at the fifth. The rule now:
 *
 * <ol>
 *   <li>the agent is in the loop (it read his words), or the same replay has just put the linked
 *   building up again → the link, exactly as asked;</li>
 *   <li>he is AIMING at a standing building that has a spot of that name → that building's spot
 *   (the castle he looks at, not the one the recipe names);</li>
 *   <li>he is STANDING inside one → that one;</li>
 *   <li>otherwise → where he aims, like every other wish, and it says so plainly.</li>
 * </ol>
 *
 * <p>No distance threshold, no guessing: a replay never seats anything somewhere he is not looking
 * or standing. Pure — no Minecraft — so every case is a row in {@code ReplaySeatTest}.
 */
public final class ReplaySeat {

    /** A locked aim point one block in front of a wall still counts as aiming AT the building. */
    static final int AIM_MARGIN = 1;

    private ReplaySeat() {
    }

    /** A point in the world. */
    public record Point(double x, double y, double z) {
    }

    /** A building's block bounds in the world, inclusive. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        boolean contains(Point p, int margin) {
            return p != null
                    && p.x() >= minX - margin && p.x() < maxX + 1 + margin
                    && p.y() >= minY - margin && p.y() < maxY + 1 + margin
                    && p.z() >= minZ - margin && p.z() < maxZ + 1 + margin;
        }

        long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    /** A building STANDING in his world, and whether it has the spot the recipe names. */
    public record Candidate(String buildId, Box box, boolean hasSpot) {
    }

    /**
     * @param buildId the building to seat on, or null for "where he aims"
     * @param note    what to say ({@code ""} when the link was simply honoured)
     */
    public record Decision(String buildId, String note) {

        public boolean onBuilding() {
            return buildId != null;
        }
    }

    /**
     * @param linked       the building the recipe names
     * @param spot         the spot it names ({@code throne})
     * @param trustLink    the agent is in the loop, or this replay just put {@code linked} up again
     * @param standing     every building standing in HIS world
     * @param aims         where he is aiming — the block his look hits, the locked spot of a served
     *                     wish; nulls are skipped
     * @param feet         where he stands, or null
     */
    public static Decision decide(String linked, String spot, boolean trustLink,
                                  List<Candidate> standing, List<Point> aims, Point feet) {
        if (trustLink) {
            return new Decision(linked, "");
        }
        Candidate inView = pick(standing, aims, AIM_MARGIN, linked);
        if (inView == null) {
            inView = pick(standing, feet == null ? List.of() : List.of(feet), 0, linked);
        }
        if (inView != null) {
            return new Decision(inView.buildId(), inView.buildId().equals(linked) ? ""
                    : " (On the " + spot + " of '" + inView.buildId() + "', the one he is looking at"
                            + " — not '" + linked + "'.)");
        }
        // Where he aims — say why, in his terms.
        Candidate lacking = pickAny(standing, aims, feet);
        String why;
        if (lacking != null) {
            why = "'" + lacking.buildId() + "', the building he is looking at, has no " + spot;
        } else if (standing.stream().anyMatch(c -> c.buildId().equals(linked))) {
            why = "he is not looking at a building with a " + spot + " ('" + linked
                    + "' is somewhere else)";
        } else {
            why = "'" + linked + "' is not standing any more, and he is not looking at another "
                    + "building with a " + spot;
        }
        return new Decision(null, " (It is right here with him instead of on a " + spot + ": "
                + why + ".)");
    }

    /** The building with the spot that one of {@code at} falls in: the linked one first, then the smallest. */
    private static Candidate pick(List<Candidate> standing, List<Point> at, int margin, String linked) {
        Candidate best = null;
        for (Point p : at) {
            for (Candidate c : standing) {
                if (!c.hasSpot() || !c.box().contains(p, margin)) {
                    continue;
                }
                if (c.buildId().equals(linked)) {
                    return c;
                }
                if (best == null || c.box().volume() < best.box().volume()) {
                    best = c;
                }
            }
            if (best != null) {
                return best;    // the first aim that hits anything decides
            }
        }
        return best;
    }

    private static Candidate pickAny(List<Candidate> standing, List<Point> aims, Point feet) {
        for (Candidate c : standing) {
            for (Point p : aims) {
                if (c.box().contains(p, AIM_MARGIN)) {
                    return c;
                }
            }
            if (c.box().contains(feet, 0)) {
                return c;
            }
        }
        return null;
    }
}
