package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>A picture, composed</b>: WHAT he paints (a subject from the Builder's library of pixel-art drawings, an
 * animal, a food, a face, a symbol…), WHAT IS BEHIND IT (a blue sky, a starry night, a green hill, a sunset, plain
 * white, or just the drawing), then the tune dialog's frame size and colours.
 *
 * <p>A subject is a {@code pictures/*} drawing the Builder already ships, so it fills its canvas as that drawing does
 * ({@code LibraryPictureFillsItsCanvasTest}). A background is painted FIRST, under the drawing, across the whole canvas.
 * A ready-made IS its subject with just the drawing at its own frame size, so every one is a composition
 * ({@code BuilderPictureComposableTest}, pixel for pixel). A subject that already fills its canvas (the moon in its
 * night, the sunset) has nothing behind it to show, so it is offered just the drawing. Pure.
 */
public final class BuilderPictures {

    private BuilderPictures() {
    }

    /** What is behind the drawing. */
    public record Background(String id, String label, String tile, List<String> words) {
    }

    public static final String DRAWN = "drawn";

    public static final List<Background> BACKGROUNDS = List.of(
            new Background(DRAWN, "Just the picture", "minecraft:item_frame", List.of("just the picture", "no background", "on its own")),
            new Background("sky", "A blue sky", "minecraft:light_blue_wool", List.of("blue sky", "sky", "in the sky", "clouds")),
            new Background("night", "A starry night", "minecraft:black_wool", List.of("night", "starry night", "at night", "stars behind")),
            new Background("hill", "A green hill", "minecraft:lime_wool", List.of("grass", "green hill", "on the grass", "a field")),
            new Background("sunset", "A sunset", "minecraft:orange_wool", List.of("sunset sky", "at sunset", "orange sky", "dusk")),
            new Background("white", "Plain white", "minecraft:white_wool", List.of("white background", "plain white", "on white", "plain background")));

    /** The biggest and smallest frame, in blocks a side. */
    public static final int SMALLEST = 1;
    public static final int BIGGEST = 4;

    public static Background background(String id) {
        String bare = id == null ? "" : id.substring(id.indexOf(':') + 1);
        return BACKGROUNDS.stream().filter(b -> b.id().equals(bare)).findFirst().orElse(null);
    }

    /** The subject's preset id ({@code pictures/duck}) from any of its spellings ({@code preset:pictures/duck}). */
    public static String subject(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        String bare = id.startsWith("preset:") ? id.substring("preset:".length()) : id;
        return bare.startsWith("pictures/") ? bare : "";
    }

    /** Does {@code drawing} (a stored picture) already cover its whole canvas? Then nothing behind it would show. */
    public static boolean fillsItsCanvas(JsonObject drawing) {
        int s = drawing.get("size").getAsInt();
        boolean[] on = new boolean[s * s];
        for (JsonElement e : drawing.getAsJsonArray("rects")) {
            JsonObject r = e.getAsJsonObject();
            int x0 = Math.max(0, r.get("x").getAsInt());
            int y0 = Math.max(0, r.get("y").getAsInt());
            int x1 = Math.min(s, x0 + r.get("w").getAsInt());
            int y1 = Math.min(s, y0 + r.get("h").getAsInt());
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    on[y * s + x] = true;
                }
            }
        }
        int n = 0;
        for (boolean b : on) {
            n += b ? 1 : 0;
        }
        return n >= on.length * 0.95;
    }

    /** What may go behind {@code drawing}: just the drawing for one that already fills its canvas. */
    public static List<Background> fitting(JsonObject drawing) {
        return drawing == null ? List.of() : fillsItsCanvas(drawing) ? List.of(background(DRAWN)) : BACKGROUNDS;
    }

    /** Is {@code spec} a composed picture (a subject picked; no ready-made)? */
    public static boolean composed(BuilderSpec spec) {
        return spec.kind() == BuilderSpec.Kind.PICTURE && spec.from().isEmpty() && !subject(spec.opt("subject")).isEmpty();
    }

    /** The opts a composed spec carries; {@code frame} 0 leaves the drawing's own frame size. */
    public static Map<String, String> opts(String subject, String background, int frame) {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("subject", "preset:" + subject(subject.contains("/") ? subject : "pictures/" + subject));   // a world's allowlist applies
        o.put("background", background == null || background.isEmpty() ? DRAWN : background);
        if (frame > 0) {
            o.put("frame", String.valueOf(Math.max(SMALLEST, Math.min(BIGGEST, frame))));
        }
        return o;
    }

    /** The drawing's own frame (blocks a side) as its library file hangs it; 1 when it says nothing. */
    public static int ownFrame(JsonObject drawing) {
        int w = drawing.has("wide") ? drawing.get("wide").getAsInt() : 1;
        return Math.max(SMALLEST, Math.min(BIGGEST, w));
    }

    // ------------------------------------------------------------------ what is behind it, as rects

    /** The rects that paint {@code background} across a {@code size}-pixel canvas, drawn before the subject. */
    public static JsonArray rects(String background, int size) {
        JsonArray out = new JsonArray();
        if (background == null || background.isEmpty() || background.equals(DRAWN)) {
            return out;
        }
        int s = size;
        switch (background) {
            case "sky" -> {
                out.add(rect("#8FD3FF", 0, 0, s, s));
                out.add(rect("#FFFFFF", f(s, 0.08), f(s, 0.10), f(s, 0.25), f(s, 0.07)));
                out.add(rect("#FFFFFF", f(s, 0.12), f(s, 0.06), f(s, 0.14), f(s, 0.05)));
                out.add(rect("#FFFFFF", f(s, 0.62), f(s, 0.16), f(s, 0.28), f(s, 0.07)));
                out.add(rect("#FFFFFF", f(s, 0.68), f(s, 0.12), f(s, 0.12), f(s, 0.05)));
            }
            case "night" -> {
                out.add(rect("#141B3C", 0, 0, s, s));
                double[][] stars = {{0.08, 0.10}, {0.30, 0.05}, {0.55, 0.12}, {0.85, 0.08}, {0.12, 0.45}, {0.90, 0.40},
                        {0.06, 0.82}, {0.40, 0.90}, {0.72, 0.86}, {0.93, 0.70}};
                int d = Math.max(1, f(s, 0.03));
                for (double[] p : stars) {
                    out.add(rect("#FFF6B0", f(s, p[0]), f(s, p[1]), d, d));
                }
            }
            case "hill" -> {
                int ground = f(s, 0.70);
                out.add(rect("#8FD3FF", 0, 0, s, ground));
                out.add(rect("#5DBB4A", 0, ground, s, s - ground));
                out.add(rect("#4A9A3A", 0, ground, s, Math.max(1, f(s, 0.03))));
            }
            case "sunset" -> {
                String[] bands = {"#5B3A8C", "#C2477F", "#F2795A", "#FFB347", "#FFD86B"};
                int y = 0;
                for (int i = 0; i < bands.length; i++) {
                    int next = i == bands.length - 1 ? s : f(s, (i + 1) / (double) bands.length);
                    out.add(rect(bands[i], 0, y, s, next - y));
                    y = next;
                }
            }
            case "white" -> out.add(rect("#F4F4F4", 0, 0, s, s));
            default -> throw new IllegalArgumentException("nothing called '" + background + "' goes behind a picture");
        }
        return out;
    }

    private static int f(int size, double share) {
        return (int) Math.round(size * share);
    }

    private static JsonObject rect(String colour, int x, int y, int w, int h) {
        JsonObject o = new JsonObject();
        o.addProperty("colour", colour);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("w", w);
        o.addProperty("h", h);
        return o;
    }

    /** The background ids, for the tests. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        BACKGROUNDS.forEach(b -> out.add(b.id()));
        return out;
    }
}
