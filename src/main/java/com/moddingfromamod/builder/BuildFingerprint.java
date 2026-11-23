package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>What a building IS, as numbers</b> — and how much of one building survives into the next.
 *
 * <p>This is the buildings half of the refinement measurement (R1–R6 in
 * {@code docs/WISH_BUILD_GUARDRAILS.md}). A creature persists as a named palette and a handful of
 * stats, which is what makes <i>"is he still blue"</i> a lookup rather than an opinion; a building
 * persists as a {@link BuildPlan} in {@link BuildRegistry}, so the same question — <i>"is it still
 * the castle he asked for"</i> — is a lookup too, once somebody reduces the plan to numbers. That
 * is all this class does.
 *
 * <h2>Why the palette is weighted by what is actually STANDING</h2>
 * {@link BuildPlan#blockIds()} already lists the distinct blocks, but a block that appears in one
 * 3×3 doorway lintel counts the same there as the block the whole keep is made of. Shapes are
 * placed <b>in order</b> and later ones overwrite earlier ones (and {@code minecraft:air} carves
 * rather than fills), so the only honest palette is the one computed after composition — which is
 * exactly what {@link BuildGeometry} already does for occupancy, done here for block ids as well.
 * A castle whose walls were painted over is then visibly repainted, whereas a distinct-ids list
 * would show the old colour still "present" because one lintel kept it.
 *
 * <h2>Why survival is measured in BLOCKS, not in shapes</h2>
 * The obvious metric is "how many of the original shapes are still in the list", and it is wrong in
 * both directions. A rebuild that re-describes the same keep with a different-but-equivalent set of
 * boxes scores zero while the child sees no change at all; and an edit that nudges one shape by a
 * block scores a loss it did not cause. Blocks are what the child actually sees, and the plan is
 * anchored — an edited building is rebuilt at the anchor it already stands on
 * ({@code BuildStructureTool}'s {@code editing} branch) — so anchor-relative occupancy is directly
 * comparable between rungs.
 *
 * <p>{@link #footprintSurvival} is the same question asked of the <i>ground plan</i> alone, and it
 * is the one names for this class: <i>"the footprint and the room layout, vs. the
 * palette"</i>. A castle re-roofed keeps its footprint; a castle replaced by a tower does not.
 *
 * <p>Pure — no Minecraft imports — so all of it is unit-tested without a game.
 */
public final class BuildFingerprint {

    private BuildFingerprint() {
    }

    /** The block that means "nothing here", which carves rather than fills. Mirrors BuildGeometry. */
    private static final String AIR = "minecraft:air";

    /**
     * A building reduced to the things a refinement can quietly destroy.
     *
     * @param volume     blocks actually standing once the whole plan is composed
     * @param height     tallest extent — the bounding box, which ONE thin decoration can own
     * @param bulkHeight how tall the building reads: the median column's top. See
     *                   {@link #bulkHeight(Map, int)} for why both exist
     * @param span       the longer horizontal extent — "how wide does it read"
     * @param shapes     how many pieces it is described in
     * @param rooms      how many of those pieces enclose a space
     * @param palette    {@code "minecraft:purple_concrete:52"} entries, biggest share first
     */
    public record Fingerprint(int volume, int height, int bulkHeight, int span, int shapes,
                              int rooms, List<String> palette) {

        public Fingerprint {
            palette = palette == null ? List.of() : List.copyOf(palette);
        }
    }

    /** How much of {@code before} is still there in {@code after}, and how big {@code before} was. */
    public record Survival(int before, int kept) {

        /**
         * The fraction, or {@code null} when there was nothing to survive.
         *
         * <p>Null rather than 0.0 or 1.0 on purpose: a building that was never made has not scored
         * badly at preserving itself, it has not been measured. Scoring it either way blames the
         * agent for the harness, which is the instrument bug this repo keeps paying for.
         */
        public Double fraction() {
            return before == 0 ? null : (double) kept / before;
        }
    }

    public static Fingerprint of(BuildPlan plan) {
        if (plan == null || plan.shapes().isEmpty()) {
            return new Fingerprint(0, 0, 0, 0, 0, 0, List.of());
        }
        // ONE composition drives every number here, deliberately. The first cut took volume and
        // the dimensions from BuildGeometry and the palette from compose(), which meant a mutation
        // to compose() left the volume assertions green — the fixture was testing the wrong class
        // and a broken composition passed the "air carves" test. `volumeMatchesTheCanonicalGeometry`
        // is what keeps this composition honest against BuildGeometry instead.
        Map<Long, String> cells = compose(plan);
        if (cells.isEmpty()) {
            // Shapes that hollowed each other out entirely. Not an error, and not nothing either —
            // the shape count is still real, so it is reported.
            return new Fingerprint(0, 0, 0, 0, plan.shapes().size(), 0, List.of());
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long k : cells.keySet()) {
            int x = (int) ((k >> 40) & 0xFFFFF) - 512;
            int y = (int) ((k >> 20) & 0xFFFFF) - 512;
            int z = (int) (k & 0xFFFFF) - 512;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        return new Fingerprint(cells.size(), maxY - minY + 1, bulkHeight(cells, minY),
                Math.max(maxX - minX + 1, maxZ - minZ + 1), plan.shapes().size(),
                BuildGeometry.of(plan).rooms().size(), palette(cells));
    }

    /**
     * <b>How tall the building READS — the median occupied column's top.</b>
     *
     * <p>{@link Fingerprint#height} is the bounding box, and a bounding box is owned by whatever
     * single thing pokes highest. That is the right answer for "will it fit" and the wrong answer
     * for <i>"make it much taller"</i>, which is what the buildings ladder asks.
     *
     * <p><b>Measured, on 13 real ladder cells.</b> The `glimmerhold` ladder adds a glass dome at
     * rung 2 — and in one cell that "dome" is 82 blocks tall starting at y=13, a glass envelope
     * wrapping the whole castle rather than a cap on it. From rung 3 on, bounding-box height stops
     * asking "did it get taller" and starts asking "did you clear the decoration":
     *
     * <pre>
     *   arm            grew(bounding box)   castle growth
     *   owner                     4 / 4        +22.5 blocks
     *   stranger                  2 / 9        +34.0 blocks
     * </pre>
     *
     * The stranger raised the castle MORE and scored a quarter as well. One cell doubled its towers
     * (0 -&gt; 72) and was marked a failure because a dome sat overhead at 104 the whole time. That
     * false gap was reported as an agent defect twice, and two fixes were written for it, before
     * anyone asked how the number was produced.
     *
     * <p><b>Why the median COLUMN and not the median SHAPE.</b> A median over shapes weights a
     * two-block finial the same as a keep. A median over occupied {@code (x,z)} columns weights by
     * how much of the footprint actually stands that tall, so a spire — which occupies a handful of
     * columns — cannot move it, while towers that all grew move it a lot. Re-measured on the same
     * 13 cells this reads <b>9/10</b> on the stranger arm against the bounding box's <b>2/10</b>.
     *
     * <p><b>And a mass-weighted version was tried first and REJECTED</b> ("the highest layer still
     * holding 10% of the fattest layer's blocks"). It scored 3/9 — barely better than the bug —
     * because a glass envelope has a large footprint as well as a large height, so it keeps its
     * layers fat all the way up. It is recorded here because it is the obvious idea and it does not
     * work.
     *
     * <p>This is a REGRESSION GUARD, not a ranker: on today's archive it passes every cell, which is
     * the honest reading — the agent does this rung correctly and the oracle was the defect. Its job
     * is to go red if a refinement ever starts flattening the building, without crying wolf at a
     * castle that grew under a dome.
     *
     * @param cells the composed building, keyed as {@code of} keys them
     * @param minY  the building's floor, so the answer is a height rather than a world y
     */
    static int bulkHeight(Map<Long, String> cells, int minY) {
        Map<Integer, Integer> topOfColumn = new HashMap<>();
        for (long k : cells.keySet()) {
            int x = (int) ((k >> 40) & 0xFFFFF) - 512;
            int y = (int) ((k >> 20) & 0xFFFFF) - 512;
            int z = (int) (k & 0xFFFFF) - 512;
            // A column key that cannot collide for the coordinate range compose() encodes.
            int column = (x + 512) * 4096 + (z + 512);
            topOfColumn.merge(column, y, Math::max);
        }
        if (topOfColumn.isEmpty()) {
            return 0;
        }
        List<Integer> tops = new ArrayList<>(topOfColumn.values());
        Collections.sort(tops);
        // The lower median on an even count, deliberately: it can only understate the height, and a
        // guard that understates fires late rather than falsely.
        return tops.get((tops.size() - 1) / 2) - minY + 1;
    }

    /**
     * The block palette of the COMPOSED building, biggest share first, as {@code "<id>:<percent>"}.
     *
     * <p>Percentages are rounded and therefore need not total 100; that is fine, because every
     * check asked of a palette is "is this block in it" or "is this block the biggest", never
     * arithmetic on the shares.
     */
    public static List<String> palette(BuildPlan plan) {
        return palette(compose(plan));
    }

    private static List<String> palette(Map<Long, String> blockAt) {
        Map<String, Integer> counts = new HashMap<>();
        for (String block : blockAt.values()) {
            counts.merge(block, 1, Integer::sum);
        }
        int total = blockAt.size();
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        // Biggest share first, then by id so the same plan always prints the same palette — a
        // fingerprint that reorders itself between two reads of one building is not a fingerprint.
        sorted.sort((a, b) -> b.getValue().equals(a.getValue())
                ? a.getKey().compareTo(b.getKey())
                : Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted) {
            out.add(e.getKey() + ":" + Math.round(100.0f * e.getValue() / total));
        }
        return out;
    }

    /** Share of {@code before}'s standing blocks that are still standing in {@code after}. */
    public static Survival survival(BuildPlan before, BuildPlan after) {
        Set<Long> was = compose(before).keySet();
        Set<Long> now = compose(after).keySet();
        int kept = 0;
        for (long k : was) {
            if (now.contains(k)) {
                kept++;
            }
        }
        return new Survival(was.size(), kept);
    }

    /**
     * The same question asked of the ground plan: share of {@code before}'s occupied XZ columns
     * that {@code after} still occupies.
     *
     * <p>Forgiving of everything a legitimate refinement does vertically — a taller keep, a new
     * roof, a dome — and unforgiving of the thing a rebuild does, which is to put a different
     * building on a different patch of ground.
     */
    public static Survival footprintSurvival(BuildPlan before, BuildPlan after) {
        Set<Long> was = columns(compose(before).keySet());
        Set<Long> now = columns(compose(after).keySet());
        int kept = 0;
        for (long k : was) {
            if (now.contains(k)) {
                kept++;
            }
        }
        return new Survival(was.size(), kept);
    }

    // ───────────────────────── composition ─────────────────────────

    /**
     * Position → the block that ends up there, shapes in order, air removing rather than filling.
     *
     * <p>Deliberately the same rule as {@link BuildGeometry#of}, because the whole value of this
     * measurement is that it describes what the builder will really put in the world. A second,
     * subtly different composition here would measure a building nobody ever sees.
     */
    private static Map<Long, String> compose(BuildPlan plan) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (plan == null) {
            return out;
        }
        for (BuildShape s : plan.shapes()) {
            boolean fills = !AIR.equals(s.block());
            s.forEachBlock((x, y, z) -> {
                long k = key(x, y, z);
                if (fills) {
                    out.put(k, s.block());
                } else {
                    out.remove(k);
                }
            });
        }
        return out;
    }

    private static Set<Long> columns(Set<Long> cells) {
        Set<Long> out = new HashSet<>();
        for (long k : cells) {
            out.add(k & ~(0xFFFFFL << 20));      // drop the y field, keep x and z
        }
        return out;
    }

    // Same packing as BuildGeometry: offsets are bounded by MAX_OFFSET + MAX_DIM = 224, so 20 bits
    // each with a bias is plenty and lets a whole building live in one map.
    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }
}
