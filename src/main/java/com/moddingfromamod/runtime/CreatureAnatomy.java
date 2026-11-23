package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a sculpted creature's <b>body parts actually are</b>, in blocks.
 *
 * <p><b>Why this exists.</b> The wish agent could WRITE geometry ({@code sculpt_creature} takes a
 * list of boxes) but nothing ever read it back. {@code list_creatures} returned a scale and a health
 * number; {@code paint_creature} returned part NAMES with no positions; {@code photograph} returned
 * a picture. So a later session — a different agent run, days after the sculpt — knew a creature
 * called "spikor" existed and nothing whatever about its shape.
 *
 * <p>That is not academic. Asked to make Spikor breathe fire from his mouth, the agent had to guess
 * where the mouth was, and put the jet 0.55 blocks too high and 1.2 blocks too far forward — firing
 * from thin air just past his snout. It had three signals: the scale (right), a screenshot, and the
 * hitbox — and the hitbox LIED, because it was a fixed 0.6x1.95 player box that ignored the model
 * entirely (see {@code WishMobEntity#getDefaultDimensions}, fixed in the same change).
 *
 * <p>So this converts a bone tree into the numbers an aiming decision actually needs, already in
 * BLOCKS at the creature's live scale, in the frame a datapack uses: {@code up} is height above the
 * feet, {@code forward} is distance out in front. Both map straight onto {@code positioned ^0 ^up
 * ^forward}.
 *
 * <p><b>Coordinate conventions</b>, verified against Spikor's real geometry rather than assumed:
 * bedrock model space is Y-up with 16 units per block and the feet at y=0, and the model faces
 * <b>-Z</b> (his snout runs to z=-14 while the body stops at z=-6). So {@code forward = -z}. Cube
 * origins are absolute model coordinates, not bone-relative — the same assumption {@link Framing}
 * already makes when it measures height.
 *
 * <p>Sideways offset is deliberately NOT reported. Left/right pairs are merged to their shared
 * centre line (two eyes become one "eyes"), which is where an aimed effect belongs anyway, and it
 * avoids publishing a handedness convention that has not been checked in game. Every landmark here
 * sits on the centre line.
 *
 * <p>Pure and Minecraft-free, so the arithmetic is unit-tested instead of eyeballed one screenshot
 * at a time.
 */
public final class CreatureAnatomy {

    /** Bedrock units per block. */
    public static final float UNITS_PER_BLOCK = 16.0f;

    /** No hitbox may be smaller than this, however degenerate the model. */
    public static final float MIN_SIZE_BLOCKS = 0.2f;

    /**
     * No hitbox may be bigger than this, however silly the model. A creature whose collision box
     * dwarfs the chunks around it gets stuck in terrain and suffocates; a wrong-but-sane box is a
     * far better failure than an unplayable one.
     */
    public static final float MAX_SIZE_BLOCKS = 16.0f;

    private CreatureAnatomy() {
    }

    /** The model's bounding box, in bedrock units. */
    public record Extents(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {

        /** True when no cube contributed — an absent or unparseable model. */
        public boolean isEmpty() {
            return maxY <= minY && maxX <= minX;
        }
    }

    /** A point on the creature's centre line, in BLOCKS at its live scale. */
    public record Landmark(String name, double up, double forward) {
    }

    /**
     * The measured bounding box of every cube in every bone.
     *
     * <p><b>{@code inflate} counts.</b> It grows a cube on every face at render time — the renderer's
     * {@code bakeCube} has always applied it — so a model that uses it draws bigger than its origins
     * and sizes say. Measuring without it gives a hitbox <i>smaller than the creature</i>, and the
     * sculptor allows inflate up to {@code CreatureSculpt.MAX_INFLATE} = 4 units, which is half a
     * block missing off each dimension: exactly the "head and shoulders with no collision" failure
     * this class was written to end, arriving by a different door.
     *
     * <p>Normalised with min/max rather than assuming {@code origin} is the low corner, because a
     * negative size and a negative inflate are both legal and both turn up in files off disk.
     */
    public static Extents extents(BedrockGeometry geometry) {
        boolean any = false;
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        if (geometry != null) {
            for (BedrockGeometry.Bone b : geometry.bones()) {
                for (BedrockGeometry.Cube c : b.cubes()) {
                    float[] o = c.origin();
                    float[] s = c.size();
                    if (o == null || s == null || o.length < 3 || s.length < 3) {
                        continue;   // defensive: this JSON comes off disk and out of Blockbench
                    }
                    any = true;
                    float inf = c.inflate();
                    minX = Math.min(minX, Math.min(o[0], o[0] + s[0]) - inf);
                    maxX = Math.max(maxX, Math.max(o[0], o[0] + s[0]) + inf);
                    minY = Math.min(minY, Math.min(o[1], o[1] + s[1]) - inf);
                    maxY = Math.max(maxY, Math.max(o[1], o[1] + s[1]) + inf);
                    minZ = Math.min(minZ, Math.min(o[2], o[2] + s[2]) - inf);
                    maxZ = Math.max(maxZ, Math.max(o[2], o[2] + s[2]) + inf);
                }
            }
        }
        return any ? new Extents(minX, maxX, minY, maxY, minZ, maxZ)
                : new Extents(0, 0, 0, 0, 0, 0);
    }

    /**
     * The creature's UNSCALED hitbox width in blocks — the widest the model reaches on either
     * horizontal axis, doubled, because a Minecraft hitbox is a square centred on the entity's
     * position. Taking the larger of the X and Z reach means nothing visible pokes out of the box.
     */
    public static float widthBlocks(Extents e) {
        float reach = Math.max(
                Math.max(Math.abs(e.minX()), Math.abs(e.maxX())),
                Math.max(Math.abs(e.minZ()), Math.abs(e.maxZ())));
        return clamp(2f * reach / UNITS_PER_BLOCK);
    }

    /** The creature's UNSCALED hitbox height in blocks. Feet sit at y=0, so the top IS the height. */
    public static float heightBlocks(Extents e) {
        return clamp(Math.max(0f, e.maxY()) / UNITS_PER_BLOCK);
    }

    private static float clamp(float blocks) {
        return Math.max(MIN_SIZE_BLOCKS, Math.min(MAX_SIZE_BLOCKS, blocks));
    }

    /**
     * Bone-name fragments worth reporting, most useful for aiming first. Order is the output order,
     * so an agent scanning the top of the list finds the mouth before it finds the tail.
     */
    private static final String[][] KEYWORDS = {
        {"mouth", "mouth"},
        {"snout", "snout"}, {"muzzle", "snout"}, {"beak", "snout"}, {"jaw", "snout"},
        {"nostril", "nostrils"},
        {"eye", "eyes"},          // matches eyeL/eyeR; "pupil" deliberately not surfaced
        {"horn", "horns"},
        {"head", "head"},
        {"hand", "hands"}, {"claw", "hands"}, {"paw", "hands"},
        {"arm", "arms"},
        {"tail", "tail"},
        {"wing", "wings"},
        {"foot", "feet"}, {"leg", "legs"},
    };

    /** Never flood the agent with a 40-bone model; the first few are the ones worth aiming at. */
    private static final int MAX_LANDMARKS = 10;

    /**
     * Named points on the creature, in blocks at {@code scale}. Left/right bones are merged into one
     * centre-line landmark, so {@code eyeL} + {@code eyeR} report once as "eyes".
     */
    public static List<Landmark> landmarks(BedrockGeometry geometry, double scale) {
        if (geometry == null) {
            return List.of();
        }
        double s = Math.max(0.01, scale);
        // label -> running sum of cube centres, so a merged pair averages to the centre line.
        Map<String, double[]> acc = new LinkedHashMap<>();   // label -> {sumY, sumZ, count}
        for (String[] rule : KEYWORDS) {
            for (BedrockGeometry.Bone b : geometry.bones()) {
                String name = b.name() == null ? "" : b.name().toLowerCase(Locale.ROOT);
                if (!name.contains(rule[0]) || b.cubes().isEmpty()) {
                    continue;
                }
                // A bone matching an EARLIER, more specific rule keeps that label: "mouth" must not
                // be re-reported as "snout" just because it also sits inside the snout region.
                if (claimedBy(acc, geometry, name, rule[1])) {
                    continue;
                }
                for (BedrockGeometry.Cube c : b.cubes()) {
                    float[] o = c.origin();
                    float[] sz = c.size();
                    if (o == null || sz == null || o.length < 3 || sz.length < 3) {
                        continue;
                    }
                    double[] a = acc.computeIfAbsent(rule[1], k -> new double[3]);
                    a[0] += o[1] + sz[1] / 2.0;      // centre Y, model units
                    a[1] += o[2] + sz[2] / 2.0;      // centre Z, model units
                    a[2] += 1;
                }
            }
        }
        List<Landmark> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : acc.entrySet()) {
            double[] a = e.getValue();
            if (a[2] <= 0) {
                continue;
            }
            double up = (a[0] / a[2]) / UNITS_PER_BLOCK * s;
            double forward = -(a[1] / a[2]) / UNITS_PER_BLOCK * s;   // model faces -Z
            out.add(new Landmark(e.getKey(), round(up), round(forward)));
            if (out.size() >= MAX_LANDMARKS) {
                break;
            }
        }
        return out;
    }

    /**
     * Has this bone already been counted under a different (earlier, more specific) label? Without
     * this a bone named "mouth" would land in both "mouth" and — via the "head" rule, since it is a
     * child of the head — the head average, dragging the head landmark toward the jaw.
     */
    private static boolean claimedBy(Map<String, double[]> acc, BedrockGeometry geometry,
                                     String boneName, String label) {
        for (String[] rule : KEYWORDS) {
            if (rule[1].equals(label)) {
                return false;      // reached our own rule without an earlier match
            }
            if (boneName.contains(rule[0]) && acc.containsKey(rule[1])) {
                return true;
            }
        }
        return false;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
