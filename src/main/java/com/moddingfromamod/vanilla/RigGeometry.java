package com.moddingfromamod.vanilla;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.runtime.BedrockGeometry;
import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>Sculpt parts as a {@link BedrockGeometry}, so the spike can reuse {@code CreatureAnatomy}
 * rather than reimplement it.</b>
 *
 * <p>{@code CreatureAnatomy} is the whole reason Q3 is cheap — it already turns a bone tree into
 * {@code up}/{@code forward} in blocks, and it encodes a bug that was paid for in a real wish. The
 * spike's constraint is to reuse it, and it takes a {@code BedrockGeometry}. But
 * {@code BedrockGeometry} can only be built from JSON, and the emitter that produces that JSON
 * ({@code CreatureSculpt.geometry}) is package-private and sits in the frozen MCP path.
 *
 * <p>So this writes the same document, minus the parts nothing here reads.
 *
 * <h2>What is deliberately left out, and how that is kept honest</h2>
 *
 * <p>No packed UV and no texture size: {@code CreatureAnatomy} reads bone names, cube origins,
 * sizes and inflate, and nothing else. Emitting a UV net would mean duplicating the packer, which
 * is a real risk of drift for no gain.
 *
 * <p>That is a claim, so it is TESTED rather than asserted:
 * {@code RigGeometryTest.theShortcutMatchesTheRealWriter} runs the same parts through the shipped
 * {@code CreatureFiles} writer and {@code ModelRepository} parser and requires identical extents
 * and identical landmarks. If the anatomy ever starts reading a field this omits, that test goes
 * red rather than the spike quietly measuring a slightly different creature.
 *
 * <p><b>The rotation goes on the BONE.</b> Same reason {@code CreatureSculpt.geometry} does it:
 * {@code BedrockGeometry} parses cube-level rotation and {@code RuntimeGeoModel} silently discards
 * it, so writing it on the cube would look correct, unit-test clean, and render nothing.
 */
public final class RigGeometry {

    private RigGeometry() {
    }

    /** The bedrock document for these parts, in the shape {@link BedrockGeometry#fromJson} wants. */
    public static JsonObject json(List<CreatureSculpt.Part> parts) {
        JsonArray bones = new JsonArray();
        for (CreatureSculpt.Part p : parts) {
            JsonObject bone = new JsonObject();
            bone.addProperty("name", p.name());
            if (p.parent() != null && !p.parent().isEmpty() && hasPart(parts, p.parent())) {
                bone.addProperty("parent", p.parent());
            }
            bone.add("pivot", vec(p.pivot()));
            if (p.rotated()) {
                bone.add("rotation", vec(p.rotation()));
            }
            JsonObject cube = new JsonObject();
            cube.add("origin", vec(p.from()));
            cube.add("size", vec(new float[]{p.width(), p.height(), p.depth()}));
            if (p.inflate() > 0f) {
                cube.addProperty("inflate", p.inflate());
            }
            JsonArray cubes = new JsonArray();
            cubes.add(cube);
            bone.add("cubes", cubes);
            bones.add(bone);
        }
        JsonObject desc = new JsonObject();
        desc.addProperty("identifier", "geometry.rig");
        desc.addProperty("texture_width", 64);
        desc.addProperty("texture_height", 64);

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

    /** These parts, parsed by the real {@link BedrockGeometry} parser. */
    public static BedrockGeometry of(List<CreatureSculpt.Part> parts) {
        return BedrockGeometry.fromJson(json(parts));
    }

    /**
     * A parent naming a part that does not exist is dropped, exactly as
     * {@code CreatureSculpt.geometry}'s {@code hasBone} drops it — so the rig groups the creature
     * the same way the mod renderer draws it, which is the only thing that makes the A/B fair.
     */
    private static boolean hasPart(List<CreatureSculpt.Part> parts, String name) {
        for (CreatureSculpt.Part p : parts) {
            if (p.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray vec(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v) {
            a.add(f);
        }
        return a;
    }
}
