package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Answers "<b>which pixels are his belt?</b>".
 *
 * <p>A child says "give him a green belt" or "make his wings purple". Painting that means finding the
 * rectangles of the texture that a named body part occupies — which the geometry already knows,
 * because every cube carries its UV origin and its size. This walks {@link BedrockGeometry} and turns
 * a bone name into the concrete texture rectangles to paint.
 *
 * <p>Standard bedrock box-UV unwraps a cube into a fixed net; from a cube at UV {@code (u,v)} with
 * size {@code (dx,dy,dz)} the whole net occupies {@code (u, v)} → {@code (u + 2dz + 2dx, v + dz + dy)}.
 * Painting that bounding rect colours every face of the cube, which is exactly what "make his wings
 * purple" means. Individual faces are available too, for finer work like a stripe across the front.
 *
 * <p>Pure — no Minecraft, no image library — so the rectangle maths is unit-tested. The actual pixel
 * pushing lives in {@code TexturePainter}.
 */
public final class UvRegions {

    private UvRegions() {
    }

    /** A rectangle in texture pixels. */
    public record Rect(int x, int y, int width, int height) {

        public boolean isEmpty() {
            return width <= 0 || height <= 0;
        }
    }

    /** The six faces of a box-UV cube, for finer-grained painting. */
    public enum Face { NORTH, SOUTH, EAST, WEST, UP, DOWN }

    /**
     * The full UV footprint of one cube — the bounding rect of its unwrapped net.
     *
     * <p>Per-face-UV cubes (Blockbench's "box UV" off) don't have one contiguous net, so their
     * rectangles are unioned instead.
     */
    public static Rect cubeRect(BedrockGeometry.Cube cube) {
        if (cube == null) {
            return new Rect(0, 0, 0, 0);
        }
        if (cube.uv().isPerFace()) {
            Integer x0 = null;
            Integer y0 = null;
            Integer x1 = null;
            Integer y1 = null;
            for (float[] uv : cube.uv().perFace().values()) {
                int ax = Math.round(uv[0]);
                int ay = Math.round(uv[1]);
                int bx = ax + Math.round(uv[2]);
                int by = ay + Math.round(uv[3]);
                x0 = x0 == null ? Math.min(ax, bx) : Math.min(x0, Math.min(ax, bx));
                y0 = y0 == null ? Math.min(ay, by) : Math.min(y0, Math.min(ay, by));
                x1 = x1 == null ? Math.max(ax, bx) : Math.max(x1, Math.max(ax, bx));
                y1 = y1 == null ? Math.max(ay, by) : Math.max(y1, Math.max(ay, by));
            }
            if (x0 == null) {
                return new Rect(0, 0, 0, 0);
            }
            return new Rect(x0, y0, x1 - x0, y1 - y0);
        }
        int u = Math.round(cube.uv().boxUv()[0]);
        int v = Math.round(cube.uv().boxUv()[1]);
        int dx = Math.round(cube.size()[0]);
        int dy = Math.round(cube.size()[1]);
        int dz = Math.round(cube.size()[2]);
        return new Rect(u, v, 2 * dz + 2 * dx, dz + dy);
    }

    /** One face's rectangle within a box-UV net (null for per-face cubes or an unmapped face). */
    public static Rect faceRect(BedrockGeometry.Cube cube, Face face) {
        if (cube == null) {
            return null;
        }
        if (cube.uv().isPerFace()) {
            float[] uv = cube.uv().perFace().get(face.name().toLowerCase(Locale.ROOT));
            if (uv == null) {
                return null;
            }
            return new Rect(Math.round(uv[0]), Math.round(uv[1]),
                    Math.round(uv[2]), Math.round(uv[3]));
        }
        int u = Math.round(cube.uv().boxUv()[0]);
        int v = Math.round(cube.uv().boxUv()[1]);
        int dx = Math.round(cube.size()[0]);
        int dy = Math.round(cube.size()[1]);
        int dz = Math.round(cube.size()[2]);
        // The bedrock box-UV net, same layout the renderer samples.
        return switch (face) {
            case UP -> new Rect(u + dz, v, dx, dz);
            case DOWN -> new Rect(u + dz + dx, v, dx, dz);
            case EAST -> new Rect(u, v + dz, dz, dy);
            case NORTH -> new Rect(u + dz, v + dz, dx, dy);
            case WEST -> new Rect(u + dz + dx, v + dz, dz, dy);
            case SOUTH -> new Rect(u + dz + dx + dz, v + dz, dx, dy);
        };
    }

    /**
     * Every rectangle belonging to a bone, by name.
     *
     * <p>Matching is deliberately loose, because a child names body parts and an agent guesses bone
     * names: exact match first, then prefix/suffix, then substring — so "wing" finds {@code wingL}
     * and {@code wingR}, and "belt" finds {@code belt_front}. Returns empty when nothing matches,
     * which the caller reports as "I couldn't find that part" rather than painting the wrong thing.
     */
    public static List<Rect> bonePixels(BedrockGeometry geometry, String boneQuery) {
        List<Rect> out = new ArrayList<>();
        if (geometry == null || boneQuery == null || boneQuery.isBlank()) {
            return out;
        }
        for (BedrockGeometry.Bone bone : matchBones(geometry, boneQuery)) {
            for (BedrockGeometry.Cube cube : bone.cubes()) {
                Rect r = cubeRect(cube);
                if (!r.isEmpty()) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    /** The bones a query names, in the geometry's own order. */
    public static List<BedrockGeometry.Bone> matchBones(BedrockGeometry geometry, String query) {
        List<BedrockGeometry.Bone> exact = new ArrayList<>();
        List<BedrockGeometry.Bone> edge = new ArrayList<>();
        List<BedrockGeometry.Bone> loose = new ArrayList<>();
        if (geometry == null || query == null) {
            return exact;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return exact;
        }
        for (BedrockGeometry.Bone bone : geometry.bones()) {
            String name = bone.name().toLowerCase(Locale.ROOT);
            if (name.equals(q)) {
                exact.add(bone);
            } else if (name.startsWith(q) || name.endsWith(q)) {
                edge.add(bone);
            } else if (name.contains(q)) {
                loose.add(bone);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        return edge.isEmpty() ? loose : edge;
    }

    /** Every bone name in the model — what to show a child when their word didn't match. */
    public static List<String> boneNames(BedrockGeometry geometry) {
        List<String> names = new ArrayList<>();
        if (geometry != null) {
            for (BedrockGeometry.Bone b : geometry.bones()) {
                names.add(b.name());
            }
        }
        return names;
    }

    /**
     * A "belt": the horizontal band across a bone's side faces, given as a fraction of the bone's
     * height. This is the shape of request that doesn't map to a whole bone — a stripe around the
     * middle rather than the entire part.
     *
     * @param fromFraction 0 = the bottom of the part, 1 = the top
     */
    public static List<Rect> bandPixels(BedrockGeometry geometry, String boneQuery,
                                        float fromFraction, float toFraction) {
        List<Rect> out = new ArrayList<>();
        float lo = Math.max(0f, Math.min(fromFraction, toFraction));
        float hi = Math.min(1f, Math.max(fromFraction, toFraction));
        for (BedrockGeometry.Bone bone : matchBones(geometry, boneQuery)) {
            for (BedrockGeometry.Cube cube : bone.cubes()) {
                for (Face face : new Face[]{Face.NORTH, Face.SOUTH, Face.EAST, Face.WEST}) {
                    Rect f = faceRect(cube, face);
                    if (f == null || f.isEmpty()) {
                        continue;
                    }
                    // v grows downward in texture space, so the TOP of the part is the low v.
                    int bandTop = f.y() + Math.round(f.height() * (1f - hi));
                    int bandBottom = f.y() + Math.round(f.height() * (1f - lo));
                    int h = Math.max(1, bandBottom - bandTop);
                    out.add(new Rect(f.x(), bandTop, f.width(), h));
                }
            }
        }
        return out;
    }

    /** Named shorthands a child actually says, mapped to a height band. */
    public static final Map<String, float[]> BANDS = new LinkedHashMap<>(Map.of(
            "belt", new float[]{0.35f, 0.55f},
            "waist", new float[]{0.35f, 0.55f},
            "collar", new float[]{0.85f, 1.0f},
            "boots", new float[]{0.0f, 0.25f},
            "stripe", new float[]{0.45f, 0.6f}));
}
