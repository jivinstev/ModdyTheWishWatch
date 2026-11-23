package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Turns a described creature — "a white head, red hair, a yellow body, big red shoes" — into real
 * geometry and a real skin.
 *
 * <p><b>Why this exists.</b> The other way to invent a creature is to pick a vanilla mob's texture
 * and tint it, and that quietly makes whole categories of character impossible. Every humanoid
 * texture the game ships is a <i>monster</i>, and a tint <b>multiplies</b>, so it can only ever
 * darken. Asked for a cheerful clown, the best that path can do is a darker, redder zombie — which is
 * exactly what it produced. Building the boxes and painting them outright has neither limit: a white
 * face is just white.
 *
 * <p>Each part is one bone holding one box, so parts can be parented ("hair" onto "head") and
 * animated by name. UVs are packed here rather than left to an editor's auto-layout, which in
 * practice assigned every cube the same corner of the atlas.
 *
 * <p>Pure — no Minecraft, no image library — so the packing and the coordinate maths are unit-tested.
 */
public final class CreatureSculpt {

    private CreatureSculpt() {
    }

    /** Guardrails: this comes from an agent acting on a child's description. */
    public static final int MAX_PARTS = 64;
    public static final int MIN_COORD = -64;
    public static final int MAX_COORD = 128;

    /** No tilt — the shape every part had before rotation existed. */
    public static final float[] NO_ROTATION = {0f, 0f, 0f};

    /** Degrees. Past this a "tilted" box is just a differently-aligned box, and reads as a mistake. */
    public static final float MAX_ROTATION = 90f;

    /** Sixteenths a box may be grown by. Enough to round a silhouette, not enough to hide a gap. */
    public static final float MAX_INFLATE = 4f;

    /**
     * One box of the creature: a named bone with a single cube.
     *
     * <p><b>{@code rotation} and {@code pattern} are why creatures stopped reading as blocks.</b>
     * This record used to be an <i>axis-aligned</i> box with one <i>flat</i> colour, while
     * {@link BedrockGeometry.Cube} — the thing that actually draws it — has always parsed rotation,
     * inflate, mirror and per-face UV. The agent's vocabulary was strictly narrower than the
     * renderer's, so a spike was not a shape the format had, and a 56-part Spikor against a 64-part
     * cap was still a staircase. See {@code docs/CREATURE_FIDELITY.md}.
     *
     * @param rotation      degrees about the pivot, X/Y/Z. {@link #NO_ROTATION} for an upright box.
     * @param inflate       sixteenths to grow the box by on every face, for a softer silhouette.
     * @param pattern       a {@link SculptPattern} name, or "" for a flat colour.
     * @param patternColour the second colour the pattern draws in.
     */
    public record Part(String name, String parent, float[] pivot,
                       float[] from, float[] to, int colour,
                       float[] rotation, float inflate, String pattern, int patternColour) {

        /**
         * The pre-#70 shape: an upright, flat-coloured box.
         *
         * <p>Kept so the 20-odd call sites that construct a {@code Part} — every gate, every test
         * fixture, the eval scorer — stay source-compatible. {@code ReferenceSpec} already uses this
         * pattern for the same reason.
         */
        public Part(String name, String parent, float[] pivot,
                    float[] from, float[] to, int colour) {
            this(name, parent, pivot, from, to, colour, NO_ROTATION, 0f, "", 0);
        }

        /** Is this box tilted at all? Cheap enough to ask per-part in a rasteriser. */
        public boolean rotated() {
            return rotation != null
                    && (rotation[0] != 0f || rotation[1] != 0f || rotation[2] != 0f);
        }

        /** Does this part carry a pattern the texture has to draw? */
        public boolean patterned() {
            return pattern != null && !pattern.isEmpty();
        }

        public int width() {
            return Math.round(to[0] - from[0]);
        }

        public int height() {
            return Math.round(to[1] - from[1]);
        }

        public int depth() {
            return Math.round(to[2] - from[2]);
        }

        /**
         * The box-UV net for this box in MODEL units: (2·depth + 2·width) wide, (depth + height)
         * tall. The packed net is this times {@link #TEXELS_PER_UNIT}.
         */
        public int netWidth() {
            return 2 * depth() + 2 * width();
        }

        public int netHeight() {
            return depth() + height();
        }

        /** The same net in TEXELS — what actually has to fit in the atlas. */
        public int netWidthTexels() {
            return netWidth() * texelsPerUnit();
        }

        public int netHeightTexels() {
            return netHeight() * texelsPerUnit();
        }
    }

    /** A rectangle of the skin to fill with one colour. */
    public record PaintRect(int x, int y, int width, int height, int colour) {
    }

    /**
     * <b>The pixel loop. THE one place a rectangle list becomes pixels.</b>
     *
     * <p>Extracted so the creature skin, the painted panel and the <i>vanilla</i> map-art route are
     * all painted by the same code rather than by three copies that agree today. Methodology
     * &sect;3.B.8: "share the implementation, do not fork it" — and specifically so the A/B between
     * the mod renderer and the vanilla route compares a QUANTISATION, never two rasterisers that
     * have drifted. A second copy would drift on exactly the two details that are silent when
     * wrong: rectangles are painted <b>in order</b> (base colour first, pattern on top) and
     * everything unpainted stays <b>transparent</b> rather than black.
     *
     * <p>Transparency is load-bearing on both arms and means the same thing on each:
     * {@code paint_surface} tells the agent "whatever you do not paint is see-through, so the blocks
     * behind show past the picture". On the mod renderer that is an alpha-0 texel; on a map it is
     * map colour 0 ({@code MapColor.NONE}), which vanilla draws as nothing. The contract survives
     * the change of view, which is why the route is a view layer at all.
     *
     * <p>Pure — plain ints in, plain ints out, no {@code BufferedImage} and no Minecraft — so the
     * ordering and the clipping are unit-testable without a game or a render thread.
     *
     * @return {@code size * size} pixels in ARGB, row-major, 0 (fully transparent) where nothing
     *     was painted.
     */
    public static int[] rasterise(int size, List<PaintRect> rects) {
        int s = Math.max(1, size);
        int[] px = new int[s * s];
        if (rects == null) {
            return px;
        }
        for (PaintRect r : rects) {
            if (r == null) {
                continue;
            }
            int argb = 0xFF000000 | (r.colour() & 0xFFFFFF);
            for (int y = Math.max(0, r.y()); y < Math.min(s, r.y() + r.height()); y++) {
                for (int x = Math.max(0, r.x()); x < Math.min(s, r.x() + r.width()); x++) {
                    px[y * s + x] = argb;
                }
            }
        }
        return px;
    }

    /** Everything needed to write the creature to disk. */
    public record Baked(JsonObject geometry, List<PaintRect> paint, int textureSize) {
    }

    // ===================== parsing =====================

    /**
     * Read the parts an agent described. Skips anything unusable rather than failing the whole
     * creature — a single malformed box shouldn't cost a child the entire thing.
     */
    public static List<Part> parseParts(JsonArray arr) {
        List<Part> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        Set<String> names = new LinkedHashSet<>();
        List<float[][]> raw = new ArrayList<>();
        for (JsonElement el : arr) {
            if (out.size() >= MAX_PARTS || !el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String name = sanitize(str(o, "name", ""));
            if (name.isEmpty() || !names.add(name)) {
                continue;   // unnamed, or a duplicate that would shadow an earlier bone
            }
            float[] from = vec3(o, "from");
            float[] to = vec3(o, "to");
            if (from == null || to == null) {
                continue;
            }
            // Normalise so from is always the low corner, and clamp into a sane build volume.
            for (int i = 0; i < 3; i++) {
                float lo = Math.min(from[i], to[i]);
                float hi = Math.max(from[i], to[i]);
                from[i] = clamp(lo);
                to[i] = clamp(hi);
            }
            if (to[0] - from[0] < 1 || to[1] - from[1] < 1 || to[2] - from[2] < 1) {
                continue;   // a flat or inverted box draws nothing
            }
            String parent = o.has("parent") && o.get("parent").isJsonPrimitive()
                    ? sanitize(o.get("parent").getAsString()) : null;
            if (parent != null && (parent.isEmpty() || parent.equals(name))) {
                parent = null;
            }
            float[] pivot = vec3(o, "pivot");
            if (pivot == null) {
                pivot = defaultPivot(name, from, to);
            }
            // Same colour vocabulary the agent already uses elsewhere ("red", "hot_pink", "#FFD800").
            int colour = com.moddingfromamod.species.Species.parseColour(
                    str(o, "colour", str(o, "color", "")));

            // Arm A of the A/B gets the pre-#70 sculptor exactly: no tilt, no inflate, no pattern.
            // The tool schema hides these too, so nothing is being silently discarded here -- see
            // FidelityFlags.
            if (!FidelityFlags.enabled()) {
                out.add(new Part(name, parent, pivot, from, to, colour));
                raw.add(rawCorners(o));
                continue;
            }

            // Tilt. Clamped rather than rejected: a part that silently vanishes because one angle
            // was out of range costs a child a horn and tells nobody why.
            float[] rotation = vec3(o, "rotation");
            rotation = rotation == null ? NO_ROTATION : clampRotation(rotation);
            float inflate = Math.max(0f,
                    Math.min(MAX_INFLATE, (float) dbl(o, "inflate", 0)));

            // Surface. An unknown pattern name degrades to flat -- the part still draws.
            String pattern = SculptPattern.sanitize(
                    str(o, "pattern", str(o, "texture", "")));
            int patternColour = pattern.isEmpty() ? 0
                    : com.moddingfromamod.species.Species.parseColour(
                            str(o, "pattern_colour", str(o, "pattern_color", "")));

            out.add(new Part(name, parent, pivot, from, to, colour,
                    rotation, inflate, pattern, patternColour));
            raw.add(rawCorners(o));
        }
        keepProud(out, raw);
        return out;
    }

    /** A part's corners as authored, low corner first, before any rounding. */
    private static float[][] rawCorners(JsonObject o) {
        float[] f = vec3(o, "from");
        float[] t = vec3(o, "to");
        float[][] c = new float[2][3];
        for (int i = 0; i < 3; i++) {
            c[0][i] = Math.min(f[i], t[i]);
            c[1][i] = Math.max(f[i], t[i]);
        }
        return c;
    }

    /**
     * <b>A detail authored proud of a surface stays proud of it after rounding</b>.
     *
     * <p>Rounding to whole units flattens anything proud by less than half a unit onto the face it stands
     * on: an eye at {@code -8.6} on a face plate at {@code -8} becomes coplanar with it, and two coplanar
     * faces fight in the depth buffer, so one eye shows and the other does not (an earlier issue's lopsided Puffo; 70
     * archived creatures after the half-away fix above). So when a SMALLER box was in front of a bigger one
     * it overlaps, and rounding made their faces meet, the smaller one's face moves out one unit. Tilted
     * boxes are left alone: their faces are not on these planes.
     */
    static void keepProud(List<Part> parts, List<float[][]> raw) {
        int n = Math.min(parts.size(), raw.size());
        for (int i = 0; i < n; i++) {
            Part s = parts.get(i);
            if (s.rotated() || tiltedParent(parts, s)) {
                continue;
            }
            for (int j = 0; j < n; j++) {
                Part b = parts.get(j);
                if (i == j || b.rotated() || tiltedParent(parts, b) || volume(s) >= volume(b)) {
                    continue;
                }
                for (int ax = 0; ax < 3; ax++) {
                    if (!overlapsOn(s, b, (ax + 1) % 3) || !overlapsOn(s, b, (ax + 2) % 3)) {
                        continue;
                    }
                    // the low side: authored in front, rounded flush
                    if (raw.get(i)[0][ax] < raw.get(j)[0][ax] - 1e-3f && s.from()[ax] == b.from()[ax]
                            && s.from()[ax] - 1 >= MIN_COORD) {
                        s.from()[ax] -= 1;
                    }
                    if (raw.get(i)[1][ax] > raw.get(j)[1][ax] + 1e-3f && s.to()[ax] == b.to()[ax]
                            && s.to()[ax] + 1 <= MAX_COORD) {
                        s.to()[ax] += 1;
                    }
                }
            }
        }
    }

    private static boolean tiltedParent(List<Part> parts, Part p) {
        if (p.parent() == null) {
            return false;
        }
        for (Part q : parts) {
            if (q.name().equals(p.parent())) {
                return q.rotated();
            }
        }
        return false;
    }

    private static boolean overlapsOn(Part a, Part b, int ax) {
        return a.from()[ax] < b.to()[ax] && b.from()[ax] < a.to()[ax];
    }

    private static float volume(Part p) {
        return (p.to()[0] - p.from()[0]) * (p.to()[1] - p.from()[1]) * (p.to()[2] - p.from()[2]);
    }

    /**
     * Where a bone rotates about when the description didn't say.
     *
     * <p>A limb swings from the joint at its <b>top</b> (shoulder, hip); everything else — a head on a
     * neck, a body, a hat — turns about its <b>base</b>. Getting this wrong doesn't fail loudly, it
     * just makes a walk cycle look like the legs are pivoting around the feet, so the guess is worth
     * making deliberately.
     */
    static float[] defaultPivot(String name, float[] from, float[] to) {
        String n = name.toLowerCase(Locale.ROOT);
        boolean limb = n.startsWith("arm") || n.startsWith("leg") || n.contains("wing")
                || n.startsWith("tail");
        float cx = (from[0] + to[0]) / 2f;
        float cz = (from[2] + to[2]) / 2f;
        return new float[]{cx, limb ? to[1] : from[1], cz};
    }

    // ===================== baking =====================

    /** Texture sizes we'll consider, smallest first. */
    private static final int[] SIZES = {32, 64, 128, 256, 512, 1024};

    /**
     * <b>Texels per model unit.</b>
     *
     * <p>This used to be 1, implicitly, because bedrock <b>box-UV defines</b> a cube's net as
     * exactly {@code (2d+2w) x (d+h)} UV units — the density is not a choice while box-UV is in
     * use. One model unit is 1/16 of a block, so the finest mark anything could draw was a
     * 1/16-block band, and every pattern authored as fine surface detail came out as slabs: fur
     * rendered as chunky blocks, scales as a brick wall. Photographs of four live creatures, not a
     * theory.
     *
     * <p>So the geometry now emits <b>per-face UV</b>, which {@code BedrockGeometry} has always
     * parsed and this writer never produced, and each face is packed at this density. At 4 the
     * finest mark is a quarter of a model unit, which is where fur starts to read as fur.
     *
     * <p>The cost is atlas area, which grows with the square — a creature that needed a 128px
     * texture needs 512px. That is a runtime {@code DynamicTexture} either way, so it is memory and
     * not latency, and nothing about the wish clock changes.
     */
    public static final int DEFAULT_TEXELS_PER_UNIT = 4;

    private static int texelsPerUnit = DEFAULT_TEXELS_PER_UNIT;

    /** Texels per model unit. See the note above. */
    public static int texelsPerUnit() {
        return texelsPerUnit;
    }

    /**
     * Test seam: render at the OLD density so a before/after can be photographed in one run.
     * Not a feature flag — a shipped build is always {@link #DEFAULT_TEXELS_PER_UNIT}.
     */
    public static void setTexelsPerUnitForTest(int n) {
        texelsPerUnit = Math.max(1, Math.min(8, n));
    }

    /**
     * Lay the parts out into an atlas and build the geometry document.
     *
     * @return null when there is nothing drawable, or the parts cannot fit even the largest atlas
     */
    public static Baked bake(List<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        for (int size : SIZES) {
            List<PaintRect> packed = pack(parts, size);
            if (packed != null) {
                // Base colour first, then any pattern ON TOP -- the writer paints rects in order,
                // so a stripe drawn after its part's fill is what makes a striped part.
                // geometry() still gets the index-aligned `packed` list; overlays are drawing
                // instructions only, and a stray overlay must never shift a part's UV.
                List<PaintRect> paint = new ArrayList<>(packed);
                for (int i = 0; i < parts.size(); i++) {
                    paint.addAll(SculptPattern.overlay(parts.get(i), packed.get(i)));
                }
                return new Baked(geometry(parts, packed, size), paint, size);
            }
        }
        return null;
    }

    /**
     * Shelf-pack each part's UV net. Tallest first, wrapping to a new shelf — simple, deterministic,
     * and good enough for the couple of dozen boxes a creature has. Returns null if they don't fit.
     */
    static List<PaintRect> pack(List<Part> parts, int textureSize) {
        // Sort INDICES, not the parts themselves: a record holding float[] compares by array
        // identity, so looking a part back up by value after sorting is a trap waiting to happen.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Integer.compare(
                parts.get(b).netHeightTexels(), parts.get(a).netHeightTexels()));

        PaintRect[] placed = new PaintRect[parts.size()];
        int x = 0;
        int y = 0;
        int shelfHeight = 0;
        for (int i : order) {
            Part p = parts.get(i);
            int w = p.netWidthTexels();
            int h = p.netHeightTexels();
            if (w > textureSize) {
                return null;
            }
            if (x + w > textureSize) {
                x = 0;
                y += shelfHeight + 1;
                shelfHeight = 0;
            }
            if (y + h > textureSize) {
                return null;
            }
            placed[i] = new PaintRect(x, y, w, h, p.colour());
            x += w + 1;
            shelfHeight = Math.max(shelfHeight, h);
        }
        return java.util.Arrays.asList(placed);
    }

    /** Build the {@code minecraft:geometry} document, with each part's packed UV baked in. */
    static JsonObject geometry(List<Part> parts, List<PaintRect> packed, int textureSize) {
        JsonObject desc = new JsonObject();
        desc.addProperty("identifier", "geometry.sculpted");
        desc.addProperty("texture_width", textureSize);
        desc.addProperty("texture_height", textureSize);

        JsonArray bones = new JsonArray();
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            PaintRect r = packed.get(i);
            JsonObject bone = new JsonObject();
            bone.addProperty("name", p.name());
            if (p.parent() != null && hasBone(parts, p.parent())) {
                bone.addProperty("parent", p.parent());
            }
            bone.add("pivot", vec(p.pivot()));
            // the tilt goes on the BONE, not the cube.
            //
            // This is not a stylistic choice and it is worth not "tidying" later. BedrockGeometry
            // parses cube-level rotation, so writing it there looks correct and unit-tests clean --
            // but RuntimeGeoModel only ever applies BONE rotation (see its bakeCube: inflate and
            // mirror are honoured, cube rotation is dropped on the floor). A cube rotation would
            // therefore render as nothing at all, and we would have shipped "spikes work" having
            // changed exactly zero pixels. A part is one bone holding one cube, so the bone is an
            // exact substitute -- and it composes with animation, which a cube rotation would not.
            if (p.rotated()) {
                bone.add("rotation", vec(p.rotation()));
            }

            JsonObject cube = new JsonObject();
            cube.add("origin", vec(p.from()));
            cube.add("size", vec(new float[]{p.width(), p.height(), p.depth()}));
            // PER-FACE UV, not box-UV. Box-UV *defines* the net as (2d+2w) x (d+h) UV
            // units, so it cannot carry a density; per-face states each rectangle outright, which
            // is what lets a face be packed at TEXELS_PER_UNIT and finally hold fur or scales.
            //
            // The rectangles below are the standard bedrock box-UV net, multiplied by the density.
            // They must stay in lockstep with RuntimeGeoModel#bakeCube's box-UV branch, or every
            // creature's skin scrambles at once — which is why FACE_LAYOUT is derived from the same
            // six offsets rather than re-typed.
            cube.add("uv", faceUv(r.x(), r.y(), p.width(), p.height(), p.depth()));
            // Inflate, unlike rotation, IS honoured at cube level by the renderer's bakeCube.
            if (p.inflate() > 0f) {
                cube.addProperty("inflate", p.inflate());
            }

            JsonArray cubes = new JsonArray();
            cubes.add(cube);
            bone.add("cubes", cubes);
            bones.add(bone);
        }

        JsonObject geo = new JsonObject();
        geo.add("description", desc);
        geo.add("bones", bones);
        JsonArray list = new JsonArray();
        list.add(geo);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");
        root.add("minecraft:geometry", list);
        return root;
    }

    /**
     * The six face rectangles of a box-UV net, in texels, at {@link #TEXELS_PER_UNIT}.
     *
     * <p>Laid out exactly as bedrock's box-UV does — {@code up}/{@code down} across the top after a
     * depth-wide margin, then {@code east}/{@code north}/{@code west}/{@code south} along the row
     * below. Emitting it explicitly is the whole point: the rectangles can then be bigger than the
     * cube, which box-UV forbids.
     */
    static JsonObject faceUv(int x, int y, int w, int h, int d) {
        // x,y are the packed net's TOP-LEFT IN TEXELS (the packer already works in texels); the
        // offsets within the net are in model units and are what gets scaled. Scaling the origin
        // too would push every face off its own rectangle.
        int s = texelsPerUnit();
        JsonObject uv = new JsonObject();
        face(uv, "up", x + d * s, y, w * s, d * s);
        face(uv, "down", x + (d + w) * s, y, w * s, d * s);
        face(uv, "east", x, y + d * s, d * s, h * s);
        face(uv, "north", x + d * s, y + d * s, w * s, h * s);
        face(uv, "west", x + (d + w) * s, y + d * s, d * s, h * s);
        face(uv, "south", x + (d + w + d) * s, y + d * s, w * s, h * s);
        return uv;
    }

    private static void face(JsonObject uv, String name, int u, int v, int w, int h) {
        JsonObject f = new JsonObject();
        JsonArray at = new JsonArray();
        at.add(u);
        at.add(v);
        JsonArray size = new JsonArray();
        size.add(w);
        size.add(h);
        f.add("uv", at);
        f.add("uv_size", size);
        uv.add(name, f);
    }

    private static boolean hasBone(List<Part> parts, String name) {
        for (Part p : parts) {
            if (p.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ===================== helpers =====================

    private static JsonArray vec(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v) {
            a.add(f);
        }
        return a;
    }

    /**
     * To the nearest unit, a half AWAY FROM ZERO. {@code Math.round} sends every half toward
     * +infinity, so a pair mirrored about the centre line came out unmirrored ({@code -2.5} to {@code -2},
     * {@code 2.5} to {@code 3}: 74 of 573 archived creatures), and a detail authored half a unit proud of
     * the FRONT (which is -z) was flattened onto the face it stood on ({@code -9.5} to {@code -9}).
     * Rounding by magnitude keeps both: the build is symmetric about each axis exactly as authored.
     */
    private static float clamp(float v) {
        float r = Math.signum(v) * Math.round(Math.abs(v));
        return Math.max(MIN_COORD, Math.min(MAX_COORD, r));
    }

    public static float[] vec3(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return null;
        }
        JsonArray a = o.getAsJsonArray(key);
        if (a.size() < 3) {
            return null;
        }
        float[] v = new float[3];
        for (int i = 0; i < 3; i++) {
            try {
                v[i] = a.get(i).getAsFloat();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return v;
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
    }

    private static double dbl(JsonObject o, String k, double d) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : d;
        } catch (RuntimeException e) {
            return d;
        }
    }

    /**
     * Keep a tilt inside the band where it still reads as a tilt.
     *
     * <p>Clamped, never rejected: a horn that silently disappears because one angle was 200° costs a
     * child the horn and reports nothing. Past {@link #MAX_ROTATION} a box is not tilted, it is
     * simply aligned to a different axis, which the agent should express with {@code from}/{@code to}.
     */
    static float[] clampRotation(float[] r) {
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            float v = Float.isFinite(r[i]) ? r[i] : 0f;
            out[i] = Math.max(-MAX_ROTATION, Math.min(MAX_ROTATION, v));
        }
        return out;
    }

    /** Bone names become animation keys and folder-safe strings. */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            }
            if (sb.length() >= 32) {
                break;
            }
        }
        return sb.toString();
    }
}
