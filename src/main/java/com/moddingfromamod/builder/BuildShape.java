package com.moddingfromamod.builder;

import java.util.Locale;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

/**
 * One piece of a building, described as data.
 *
 * <p>This is the Builder's version of the trick that made creatures work without a restart: rather
 * than shipping buildings, ship a handful of <b>primitives</b> an agent can compose. A castle is a
 * box for the keep, four cylinders for the towers, four pyramids for the roofs and a box for the
 * courtyard — which an AI can describe far more reliably than it can emit forty correct
 * {@code fill} commands with the right corners.
 *
 * <p>The geometry is <b>pure</b> — no Minecraft imports, positions are plain ints — so "does a
 * hollow cylinder actually produce a ring?" is answered by a millisecond unit test rather than by
 * squinting at a build in-game.
 *
 * <p>Every dimension is clamped on construction. These numbers come from an AI acting on a
 * seven-year-old's request, and the difference between a 40-block tower and a 400-block one is a
 * typo — one of which is a nice castle and the other of which is millions of block updates.
 *
 * <h2>Why a shape has a name</h2>
 *
 * <p>So a refinement can address one. Measured on the buildings ladder: with no way to change part
 * of a building, a worker asked to <i>"put a big glass dome on top of it"</i> built a second
 * building beside the castle, and after five wishes the child had four buildings instead of one
 *. A name is what lets {@link BuildBase} say "the keep is now forty tall" in one shape
 * instead of re-describing twenty-four.
 *
 * <p>It is optional and defaults to {@code ""}, because every plan ever saved has no names and must
 * keep working — {@link BuildBase} treats a nameless base shape as something to preserve, never as
 * something to overwrite.
 */
public record BuildShape(Kind kind, int dx, int dy, int dz,
                         int width, int height, int depth,
                         String block, boolean hollow, Anchor anchor, String name, boolean gen) {

    /**
     * Whether {@code dx/dy/dz} names a shape's <b>corner</b> or its <b>middle</b>.
     *
     * <p>This exists because leaving it unsaid shipped broken buildings. The offsets have always
     * been the low corner — {@link #forEachBlock} walks {@code x} from 0 to {@code width} — but the
     * tool schema said only "offset from the child", and an agent describing a tower thinks in
     * middles. So a 44-wide wall and the 40-wide air cylinder meant to hollow it were both given
     * {@code dx=0}, which does not put one inside the other: it makes them flush on the low side and
     * eats the west and north walls. Two real builds shipped that way, and two hand-written
     * fixtures had the same confusion, which is the tell that the contract was ambiguous rather than
     * that anyone was careless.
     *
     * <p>{@link #CORNER} stays the default so every plan ever saved still means what it meant.
     */
    public enum Anchor {
        /** {@code dx/dy/dz} is the shape's low corner. The original meaning, and the default. */
        CORNER,
        /**
         * {@code dx/dy/dz} is the middle of the shape's footprint, with {@code dy} still its base.
         *
         * <p>Deliberately horizontal-only. "Centre" for a tower means the middle of the floor it
         * stands on, not a point halfway up it, and a vertical centre would make {@code dy=0} bury
         * half of every shape underground.
         */
        CENTRE;

        public static Anchor parse(String s) {
            if (s != null) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (t.equals("centre") || t.equals("center") || t.equals("middle")) {
                    return CENTRE;
                }
            }
            return CORNER;
        }
    }

    /** The primitives. Deliberately few: an agent picks better from a short list. */
    public enum Kind {
        /** Rectangular. Walls, keeps, floors (height 1), platforms. */
        BOX,
        /** Round, vertical. Towers, wells, pillars. */
        CYLINDER,
        /** Round. Domes (use with a box below to cut it flat). */
        SPHERE,
        /** Square base tapering to a point. Roofs and spires. */
        PYRAMID;

        public static Kind parse(String s) {
            if (s != null) {
                for (Kind k : values()) {
                    if (k.name().equalsIgnoreCase(s.trim())) {
                        return k;
                    }
                }
            }
            return BOX;
        }
    }

    /** Big enough for a real castle, small enough that one shape can't be a world edit. */
    public static final int MAX_DIM = 96;
    /** Offsets are bounded too, or a shape could be placed halfway across the world. */
    public static final int MAX_OFFSET = 128;

    public BuildShape {
        kind = kind == null ? Kind.BOX : kind;
        width = clamp(width, 1, MAX_DIM);
        height = clamp(height, 1, MAX_DIM);
        depth = clamp(depth, 1, MAX_DIM);
        dx = clamp(dx, -MAX_OFFSET, MAX_OFFSET);
        dy = clamp(dy, -MAX_OFFSET, MAX_OFFSET);
        dz = clamp(dz, -MAX_OFFSET, MAX_OFFSET);
        block = (block == null || block.isBlank()) ? "minecraft:stone"
                : block.trim().toLowerCase(Locale.ROOT);
        anchor = anchor == null ? Anchor.CORNER : anchor;
        name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    /**
     * A shape somebody WROTE (an agent, a preset): {@code gen} false. {@code gen} marks one the engine generated from
     * a part — a re-walked stair's treads and what holds them up ({@link BuildScale}) — which does not count
     * toward {@link BuildPlan#MAX_SHAPES}: that cap is about what an agent describes, and the block cap is the guard.
     */
    public BuildShape(Kind kind, int dx, int dy, int dz, int width, int height, int depth,
                      String block, boolean hollow, Anchor anchor, String name) {
        this(kind, dx, dy, dz, width, height, depth, block, hollow, anchor, name, false);
    }

    /** This shape, marked as generated. */
    public BuildShape generated() {
        return new BuildShape(kind, dx, dy, dz, width, height, depth, block, hollow, anchor, name, true);
    }

    /** A corner-anchored, unnamed shape — the long-standing eight-argument form. */
    public BuildShape(Kind kind, int dx, int dy, int dz, int width, int height, int depth,
                      String block, boolean hollow) {
        this(kind, dx, dy, dz, width, height, depth, block, hollow, Anchor.CORNER, "");
    }

    /** An anchored but unnamed shape — every plan saved before names existed. */
    public BuildShape(Kind kind, int dx, int dy, int dz, int width, int height, int depth,
                      String block, boolean hollow, Anchor anchor) {
        this(kind, dx, dy, dz, width, height, depth, block, hollow, anchor, "");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * The low corner this shape actually draws from, whichever way it was described.
     *
     * <p>Every drawing method goes through these rather than through {@code dx/dz} directly, so
     * "centre" is honoured in exactly one place and cannot be half-applied. {@code dy} is a base in
     * both modes and so needs no equivalent.
     */
    public int originX() {
        return anchor == Anchor.CENTRE ? dx - width / 2 : dx;
    }

    public int originZ() {
        return anchor == Anchor.CENTRE ? dz - depth / 2 : dz;
    }

    /**
     * How many blocks this shape will place, exactly.
     *
     * <p>Counted by walking the same code that places them rather than by a formula, so the budget
     * can never drift from reality — a cheap estimate that under-counts a hollow sphere is how you
     * get a "small" build that freezes the game.
     */
    public int volume() {
        int[] n = {0};
        forEachBlock((x, y, z) -> n[0]++);
        return n[0];
    }

    /** Visit every block position this shape covers, relative to the build's anchor. */
    public void forEachBlock(PositionSink sink) {
        switch (kind) {
            case BOX -> box(sink);
            case CYLINDER -> cylinder(sink);
            case SPHERE -> sphere(sink);
            case PYRAMID -> pyramid(sink);
            default -> box(sink);
        }
    }

    /** Receives relative block positions. */
    public interface PositionSink {
        void at(int x, int y, int z);
    }

    /**
     * <b>A hollow box or cylinder under three tall is a RING</b> (for boxes, for cylinders): with one or
     * two layers every cell is on the top or the bottom face, so the old rule kept them all and a "hollow" fence or
     * low wall filled the whole area (the playground's sand pad was a forest of fence posts; the lighthouse's
     * railing was a plate of iron bars over its balcony). Nobody draws a hollow slab meaning a solid one, so the
     * caps are dropped. Air keeps the old meaning: a short hollow AIR box or cylinder clears its area, and builds
     * rely on that.
     */
    boolean isRing() {
        String b = block == null ? "" : block.trim();
        return hollow && height <= 2 && !b.equals("minecraft:air") && !b.equals("air");
    }

    private void box(PositionSink sink) {
        boolean ring = isRing();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    boolean shell = x == 0 || x == width - 1
                            || (!ring && (y == 0 || y == height - 1))
                            || z == 0 || z == depth - 1;
                    if (!hollow || shell) {
                        sink.at(originX() + x, dy + y, originZ() + z);
                    }
                }
            }
        }
    }

    private void cylinder(PositionSink sink) {
        boolean ring = isRing();
        double rx = width / 2.0;
        double rz = depth / 2.0;
        double cx = rx - 0.5;
        double cz = rz - 0.5;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                double nx = (x - cx) / rx;
                double nz = (z - cz) / rz;
                double d = nx * nx + nz * nz;
                if (d > 1.0) {
                    continue;
                }
                // A hollow cylinder is a WALL: keep only the outer ring, but always keep the top
                // and bottom caps so a tower has a floor and a ceiling to put a roof on — unless it is
                // under three tall, where every layer is a cap: then it is the ring alone.
                boolean wall = d > 0.55;
                for (int y = 0; y < height; y++) {
                    boolean cap = !ring && (y == 0 || y == height - 1);
                    if (!hollow || wall || cap) {
                        sink.at(originX() + x, dy + y, originZ() + z);
                    }
                }
            }
        }
    }

    private void sphere(PositionSink sink) {
        double rx = width / 2.0;
        double ry = height / 2.0;
        double rz = depth / 2.0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    double nx = (x - (rx - 0.5)) / rx;
                    double ny = (y - (ry - 0.5)) / ry;
                    double nz = (z - (rz - 0.5)) / rz;
                    double d = nx * nx + ny * ny + nz * nz;
                    if (d > 1.0) {
                        continue;
                    }
                    if (!hollow || d > 0.55) {
                        sink.at(originX() + x, dy + y, originZ() + z);
                    }
                }
            }
        }
    }

    private void pyramid(PositionSink sink) {
        for (int y = 0; y < height; y++) {
            // Shrink towards the top; stop once a layer would vanish.
            int inset = (int) Math.round((double) y / height * Math.min(width, depth) / 2.0);
            int x0 = inset;
            int x1 = width - 1 - inset;
            int z0 = inset;
            int z1 = depth - 1 - inset;
            if (x0 > x1 || z0 > z1) {
                break;
            }
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    if (!hollow || edge) {
                        sink.at(originX() + x, dy + y, originZ() + z);
                    }
                }
            }
        }
    }

    // ===================== JSON =====================

    public static BuildShape fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        return new BuildShape(
                Kind.parse(str(o, "kind", "box")),
                intOr(o, "dx", 0), intOr(o, "dy", 0), intOr(o, "dz", 0),
                intOr(o, "width", 1), intOr(o, "height", 1), intOr(o, "depth", 1),
                str(o, "block", "minecraft:stone"),
                boolOr(o, "hollow", false),
                Anchor.parse(str(o, "anchor", null)),
                str(o, "name", ""),
                boolOr(o, "gen", false));
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind.name().toLowerCase(Locale.ROOT));
        o.addProperty("dx", dx);
        o.addProperty("dy", dy);
        o.addProperty("dz", dz);
        o.addProperty("width", width);
        o.addProperty("height", height);
        o.addProperty("depth", depth);
        o.addProperty("block", block);
        o.addProperty("hollow", hollow);
        // Only written when it is not the default, so every previously-saved plan round-trips
        // byte-identical and a diff of the corpus shows real changes rather than a new key on
        // every line.
        if (anchor != Anchor.CORNER) {
            o.addProperty("anchor", anchor.name().toLowerCase(Locale.ROOT));
        }
        // Same rule as the anchor, and the same reason: only written when it is not the default,
        // so every plan saved before names existed still round-trips byte-identical.
        if (!name.isEmpty()) {
            o.addProperty("name", name);
        }
        if (gen) {
            o.addProperty("gen", true);
        }
        return o;
    }

    private static String str(JsonObject o, String k, String fallback) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : fallback;
    }

    private static int intOr(JsonObject o, String k, int fallback) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean boolOr(JsonObject o, String k, boolean fallback) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
