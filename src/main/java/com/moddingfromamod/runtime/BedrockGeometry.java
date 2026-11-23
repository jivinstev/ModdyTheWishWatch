package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A creature's <b>shape</b>, parsed from a Blockbench "Bedrock" export (a.k.a. the
 * {@code minecraft:geometry} 1.12 format, which is also what GeckoLib's {@code .geo.json} files use).
 *
 * <p>This is the data half of "give a wish creature real custom geometry with no game restart".
 * Minecraft freezes its registries at load, so we can never register a new model layer at runtime —
 * but a bone/cube tree is just numbers, and numbers can be drawn immediately. The client turns this
 * into quads in bedrock coordinates (see {@code RuntimeGeoModel}); the server never needs it.
 *
 * <p>Deliberately <b>Minecraft-free</b> so the fiddly parsing (box-UV vs per-face-UV, inflate,
 * mirror, optional bone/cube rotation and pivots) is unit-tested in milliseconds instead of only
 * ever exercised by booting a client. Every field is defensively defaulted: this JSON comes off disk
 * and out of Blockbench, so a missing {@code size} or a stray string must degrade to an empty/ignored
 * cube, never throw.
 */
public final class BedrockGeometry {

    /** One box. Coordinates are Blockbench/bedrock space: model-space Y-up, units = 1/16 block. */
    public record Cube(float[] origin, float[] size, float inflate, boolean mirror,
                       float[] rotation, float[] pivot, FaceUv uv) {
    }

    /** UV mapping for a cube: either a single box-UV top-left, or an explicit per-face map. */
    public record FaceUv(float[] boxUv, java.util.Map<String, float[]> perFace) {
        public boolean isPerFace() {
            return perFace != null && !perFace.isEmpty();
        }
    }

    /** One articulated part. May reference a parent by name to form a tree. */
    public record Bone(String name, String parent, float[] pivot, float[] rotation,
                       List<Cube> cubes) {
    }

    private final int textureWidth;
    private final int textureHeight;
    private final List<Bone> bones;

    private BedrockGeometry(int textureWidth, int textureHeight, List<Bone> bones) {
        this.textureWidth = Math.max(1, textureWidth);
        this.textureHeight = Math.max(1, textureHeight);
        this.bones = bones;
    }

    public int textureWidth() {
        return textureWidth;
    }

    public int textureHeight() {
        return textureHeight;
    }

    public List<Bone> bones() {
        return bones;
    }

    public boolean isEmpty() {
        return bones.isEmpty();
    }

    public int cubeCount() {
        int n = 0;
        for (Bone b : bones) {
            n += b.cubes().size();
        }
        return n;
    }

    // ===================== parsing =====================

    /** Guardrails: this is drawn every frame and comes from an agent + a child. */
    public static final int MAX_BONES = 256;
    public static final int MAX_CUBES = 1024;

    /**
     * Parse a Blockbench/bedrock geometry document. Returns {@code null} only when there is nothing
     * usable at all (no geometry object, or zero bones) — anything partially readable is kept.
     */
    public static BedrockGeometry fromJson(JsonObject root) {
        if (root == null) {
            return null;
        }
        JsonObject geo = firstGeometry(root);
        if (geo == null) {
            return null;
        }
        int texW = 16;
        int texH = 16;
        JsonObject desc = obj(geo, "description");
        if (desc != null) {
            texW = intOf(desc, "texture_width", 16);
            texH = intOf(desc, "texture_height", 16);
        }
        List<Bone> bones = new ArrayList<>();
        JsonArray boneArr = arr(geo, "bones");
        if (boneArr != null) {
            int cubeBudget = MAX_CUBES;
            for (JsonElement el : boneArr) {
                if (bones.size() >= MAX_BONES) {
                    break;
                }
                if (!el.isJsonObject()) {
                    continue;
                }
                Bone b = parseBone(el.getAsJsonObject(), cubeBudget);
                if (b != null) {
                    bones.add(b);
                    cubeBudget -= b.cubes().size();
                }
            }
        }
        if (bones.isEmpty()) {
            return null;
        }
        // The geometry exactly as authored. Coplanar boxes used to be pushed apart HERE
        // (FightingFaces, 2026-08-11) -- but nothing has DRAWN parsed geometry; this
        // parser now only feeds measurements (anatomy, landmarks, the agent's tools), and growing
        // boxes here made the hitbox disagree with the rig while the rig itself flickered.
        // The separation lives where the creature is drawn: vanilla/RigSeparation.
        return new BedrockGeometry(texW, texH, bones);
    }

    /** The document may be a bare geometry object, a {@code minecraft:geometry} array, or 1.8-ish. */
    private static JsonObject firstGeometry(JsonObject root) {
        JsonArray mc = arr(root, "minecraft:geometry");
        if (mc != null && !mc.isEmpty() && mc.get(0).isJsonObject()) {
            return mc.get(0).getAsJsonObject();
        }
        // A raw geometry object (has "bones") — accept it directly.
        if (root.has("bones")) {
            return root;
        }
        return null;
    }

    private static Bone parseBone(JsonObject o, int cubeBudget) {
        String name = strOf(o, "name", "");
        if (name.isEmpty()) {
            return null;
        }
        String parent = o.has("parent") && o.get("parent").isJsonPrimitive()
                ? o.get("parent").getAsString() : null;
        float[] pivot = vec3(o, "pivot", 0, 0, 0);
        float[] rotation = vec3(o, "rotation", 0, 0, 0);
        List<Cube> cubes = new ArrayList<>();
        JsonArray cubeArr = arr(o, "cubes");
        if (cubeArr != null) {
            for (JsonElement el : cubeArr) {
                if (cubes.size() >= cubeBudget) {
                    break;
                }
                if (el.isJsonObject()) {
                    Cube c = parseCube(el.getAsJsonObject());
                    if (c != null) {
                        cubes.add(c);
                    }
                }
            }
        }
        return new Bone(name, parent, pivot, rotation, cubes);
    }

    private static Cube parseCube(JsonObject o) {
        float[] origin = vec3(o, "origin", 0, 0, 0);
        float[] size = vec3(o, "size", 0, 0, 0);
        if (size[0] <= 0 && size[1] <= 0 && size[2] <= 0) {
            return null; // a zero cube draws nothing; drop it
        }
        float inflate = floatOf(o, "inflate", 0f);
        boolean mirror = o.has("mirror") && o.get("mirror").isJsonPrimitive()
                && o.get("mirror").getAsBoolean();
        float[] rotation = o.has("rotation") ? vec3(o, "rotation", 0, 0, 0) : null;
        float[] pivot = o.has("pivot") ? vec3(o, "pivot", 0, 0, 0) : null;
        FaceUv uv = parseUv(o.get("uv"));
        return new Cube(origin, size, inflate, mirror, rotation, pivot, uv);
    }

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

    private static FaceUv parseUv(JsonElement uvEl) {
        if (uvEl == null) {
            return new FaceUv(new float[]{0, 0}, null);
        }
        if (uvEl.isJsonArray()) {
            JsonArray a = uvEl.getAsJsonArray();
            float u = a.size() > 0 ? asFloat(a.get(0)) : 0;
            float v = a.size() > 1 ? asFloat(a.get(1)) : 0;
            return new FaceUv(new float[]{u, v}, null);
        }
        if (uvEl.isJsonObject()) {
            JsonObject o = uvEl.getAsJsonObject();
            java.util.Map<String, float[]> perFace = new java.util.HashMap<>();
            for (String face : FACES) {
                JsonObject f = obj(o, face);
                if (f != null) {
                    float[] uv = vec2(f, "uv", 0, 0);
                    float[] uvSize = vec2(f, "uv_size", 0, 0);
                    // pack as [u, v, uSize, vSize]
                    perFace.put(face, new float[]{uv[0], uv[1], uvSize[0], uvSize[1]});
                }
            }
            if (!perFace.isEmpty()) {
                return new FaceUv(null, perFace);
            }
        }
        return new FaceUv(new float[]{0, 0}, null);
    }

    // ---- small, forgiving JSON helpers (no exceptions escape) ----

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : null;
    }

    private static String strOf(JsonObject o, String k, String d) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
    }

    private static int intOf(JsonObject o, String k, int d) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : d;
        } catch (RuntimeException e) {
            return d;
        }
    }

    private static float floatOf(JsonObject o, String k, float d) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsFloat() : d;
        } catch (RuntimeException e) {
            return d;
        }
    }

    private static float asFloat(JsonElement el) {
        try {
            return el != null && el.isJsonPrimitive() ? el.getAsFloat() : 0f;
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    private static float[] vec2(JsonObject o, String k, float dx, float dy) {
        JsonArray a = arr(o, k);
        if (a == null) {
            return new float[]{dx, dy};
        }
        return new float[]{a.size() > 0 ? asFloat(a.get(0)) : dx,
                a.size() > 1 ? asFloat(a.get(1)) : dy};
    }

    private static float[] vec3(JsonObject o, String k, float dx, float dy, float dz) {
        JsonArray a = arr(o, k);
        if (a == null) {
            return new float[]{dx, dy, dz};
        }
        return new float[]{a.size() > 0 ? asFloat(a.get(0)) : dx,
                a.size() > 1 ? asFloat(a.get(1)) : dy,
                a.size() > 2 ? asFloat(a.get(2)) : dz};
    }

    private BedrockGeometry() {
        this(1, 1, new ArrayList<>());
    }
}
