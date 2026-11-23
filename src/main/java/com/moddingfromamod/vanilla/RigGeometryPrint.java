package com.moddingfromamod.vanilla;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>"Was this creature re-sculpted?", answered without writing a file</b> —, blocker 1.
 * Minecraft-free and unit-tested ({@code RigGeometryPrintTest}).
 *
 * <h2>What it replaced, and why that had to come first</h2>
 *
 * <p>The only exact answer used to be {@code RigPackWriter.Written.changed()}: the atom arm wrote
 * the whole per-creature resource pack on every publish, delivered it to nobody, and read the
 * writer's byte-compare. Two live things stood on that write, and neither was about a pack:
 *
 * <ol>
 *   <li><b>{@code VanillaRigAnimator}'s rebuild</b>. A {@code Species} record holds a
 *       {@code modelKey}, not the parts, so {@code sculpt_creature} with the same id and new
 *       geometry leaves the record byte-identical. Without a geometry answer a re-sculpted creature
 *       keeps its old body for ever, with nothing logged.</li>
 *   <li><b>The atom arm's reveal state</b>. {@code RigVeil}'s rule reads "has the stock route been
 *       cleared for the thing currently outstanding", keyed by a content hash; on the atom arm
 *       that hash was the pack directory's zip sha1.</li>
 * </ol>
 *
 * <p>So the pack rig could not be retired without first building this — the register's
 * blocker 1. It is the writer's INPUT, hashed, instead of its output: the same parts, scale and
 * animated bones {@code RigPackWriter.write} was handed. That makes it at least as sensitive as
 * the byte-compare for everything that is about THIS creature (a colour, a pivot, a pattern, a
 * bone that starts animating), and insensitive to what the pack's bytes also depended on and the
 * creature does not — which slot the creature was allocated, and which OTHER creatures share the
 * pack.
 *
 * <h2>Why not compare part lists</h2>
 *
 * <p>{@link CreatureSculpt.Part} holds {@code float[]}, and a record's {@code equals} compares
 * arrays by REFERENCE, so {@code parts.equals(otherParts)} is "different" for every freshly-read
 * sculpt — a comparison that silently always says "re-sculpted" and rebuilds every rig on every
 * revision bump. That is the heuristic {@code VanillaRigAnimator}'s comment already refused. This
 * encodes every array element by value.
 *
 * <h2>Order is part of the geometry</h2>
 *
 * <p>Parts are hashed in list order, deliberately: a rig's displays are planned from that order,
 * so a reordered sculpt is a differently-built rig. Animated bones are a SET and are sorted.
 */
public final class RigGeometryPrint {

    private RigGeometryPrint() {
    }

    /**
     * Bumped when the encoding changes, so a print from one encoding can never equal a print of
     * different geometry under another. Prints are held in memory only and never persisted, so a
     * bump needs no migration.
     */
    static final int ENCODING = 1;

    /**
     * The geometry's print: 40 lowercase hex characters.
     *
     * @param parts         the sculpt, in the order the rig is planned from — the same list
     *                      {@code RigPackWriter.write} was handed
     * @param scale         the creature's scale
     * @param animatedBones the bones a clip moves ({@code RigPose.animatedBones}); may be empty
     */
    public static String of(List<CreatureSculpt.Part> parts, double scale,
                            Collection<String> animatedBones) {
        MessageDigest d = sha1();
        putInt(d, ENCODING);
        putLong(d, Double.doubleToLongBits(scale));
        TreeSet<String> bones = new TreeSet<>(animatedBones == null ? List.of() : animatedBones);
        putInt(d, bones.size());
        for (String b : bones) {
            putString(d, b);
        }
        List<CreatureSculpt.Part> ps = parts == null ? List.of() : parts;
        putInt(d, ps.size());
        for (CreatureSculpt.Part p : ps) {
            putString(d, p.name());
            putString(d, p.parent());
            putFloats(d, p.pivot());
            putFloats(d, p.from());
            putFloats(d, p.to());
            putInt(d, p.colour());
            putFloats(d, p.rotation());
            putInt(d, Float.floatToIntBits(p.inflate()));
            putString(d, p.pattern());
            putInt(d, p.patternColour());
        }
        return HexFormat.of().formatHex(d.digest());
    }

    /**
     * <b>One token for "everything published so far"</b> — the atom arm's reveal state.
     *
     * <p>It stands where the pack directory's zip sha1 used to: it moves when ANY creature's print
     * moves, and a byte-identical re-publish leaves it where it was. That is the only property
     * {@code RigVeil.alreadyRevealed} relies on, so {@code RigVeil}'s rule reads it unchanged.
     * Sorted by key, so the order creatures were published in cannot move it.
     *
     * @param printsByKey creature key to {@link #of} print
     * @return 40 lowercase hex characters — a valid sha1, which is what the reveal state expects
     */
    public static String combine(Map<String, String> printsByKey) {
        MessageDigest d = sha1();
        putInt(d, ENCODING);
        TreeMap<String, String> sorted =
                new TreeMap<>(printsByKey == null ? Map.of() : printsByKey);
        putInt(d, sorted.size());
        for (var e : sorted.entrySet()) {
            putString(d, e.getKey());
            putString(d, e.getValue());
        }
        return HexFormat.of().formatHex(d.digest());
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM ships SHA-1", e);
        }
    }

    private static void putInt(MessageDigest d, int v) {
        d.update(ByteBuffer.allocate(4).putInt(v).array());
    }

    private static void putLong(MessageDigest d, long v) {
        d.update(ByteBuffer.allocate(8).putLong(v).array());
    }

    /**
     * Length-prefixed, with null distinct from empty — so ("ab","c") and ("a","bc") cannot collide,
     * and a part with no parent cannot collide with one whose parent is "".
     */
    private static void putString(MessageDigest d, String s) {
        if (s == null) {
            putInt(d, -1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        putInt(d, b.length);
        d.update(b);
    }

    private static void putFloats(MessageDigest d, float[] fs) {
        if (fs == null) {
            putInt(d, -1);
            return;
        }
        putInt(d, fs.length);
        for (float f : fs) {
            putInt(d, Float.floatToIntBits(f));
        }
    }
}
