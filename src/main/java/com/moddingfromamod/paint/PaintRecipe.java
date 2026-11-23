package com.moddingfromamod.paint;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>A picture, written down as the shapes that make it — never as pixels.</b>
 *
 * <p>This is the thing that travels: on the block entity, in a contraption's NBT, and one day over
 * a network. {@code CapturedStructure} in the mod repo paid for the alternative — a village template
 * is multiple MB, which blows Minecraft's ~2 MB frame, so the item carries a UUID and the blueprint
 * is looked up server-side. A 256&sup2; RGBA image is <b>256 KB</b>; the same picture as a list of
 * coloured rectangles is a few hundred bytes to a few kilobytes, and it survives a change of
 * resolution for free — re-bake at 512&sup2; and nothing stored here moves.
 *
 * <p>It is deliberately {@link CreatureSculpt.PaintRect}, the creature sculptor's own rectangle,
 * rather than a new one. A creature's skin already <i>is</i> a list of coloured rectangles painted
 * in order, so a picture and a skin are the same kind of thing and go through the same painter
 * ({@link com.moddingfromamod.runtime.CreatureFiles#writeTexture}).
 *
 * <p>Pure — no Minecraft — so the caps and the parsing are unit-tested without a game.
 */
public record PaintRecipe(int size, List<CreatureSculpt.PaintRect> rects) {

    /**
     * <b>The cap that keeps a recipe a recipe.</b>
     *
     * <p>The whole argument for storing shapes rather than pixels is an arithmetic one, and it stops
     * being true if the shapes get small enough. One rectangle per pixel of a 256&sup2; picture is
     * 65,536 of them — about 1.3 MB, which is not a saving on the 256 KB image, it is five times
     * worse and within sight of the network frame limit.
     *
     * <p>At this cap the worst case is 1,024 &times; 5 ints &asymp; <b>20 KB</b> against 256 KB for
     * the image: still twelve times smaller, and comfortably drawable. Past it, an author is
     * transmitting a bitmap one pixel at a time and should be told so rather than allowed to.
     */
    public static final int MAX_RECTS = 1024;

    /** Sizes worth baking. Below 32 a picture is block art again; above 512 is memory nobody sees. */
    public static final int MIN_SIZE = 32;
    public static final int MAX_SIZE = 512;

    public PaintRecipe {
        size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        rects = rects == null ? List.of()
                : List.copyOf(rects.subList(0, Math.min(rects.size(), MAX_RECTS)));
    }

    /**
     * How many shapes a picture document SENT, before the cap — so the tool can refuse a picture
     * the cap would cut short instead of painting part of it and saying nothing (P3).
     */
    public static int rectsSent(JsonObject doc) {
        return doc != null && doc.has("rects") && doc.get("rects").isJsonArray()
                ? doc.getAsJsonArray("rects").size() : 0;
    }

    /** A blank canvas — a picture nobody has described yet. */
    public static PaintRecipe blank() {
        return new PaintRecipe(PaintArt.SIZE, List.of());
    }

    public boolean isEmpty() {
        return rects.isEmpty();
    }

    /**
     * Roughly what this costs to carry, in bytes: five ints per rectangle.
     *
     * <p>Approximate on purpose — it exists to be compared against the size of the image it
     * replaces, and to make {@link #MAX_RECTS}'s arithmetic something a test can check rather than
     * a claim in a comment.
     */
    public int approximateBytes() {
        return rects.size() * 5 * Integer.BYTES;
    }

    /** What the same picture would cost as an image, for the comparison that justifies all this. */
    public int bytesAsAnImage() {
        return size * size * 4;
    }

    /**
     * Read a picture an agent described.
     *
     * <p>Skips anything unusable rather than refusing the whole picture — the same rule
     * {@link CreatureSculpt#parseParts} follows, and for the same reason: one malformed rectangle
     * should cost a child one rectangle, not the picture.
     *
     * <p>A rectangle is {@code {"x":0,"y":0,"w":128,"h":64,"colour":"red"}}. Colours go through
     * {@code Species.parseColour}, so the vocabulary is the one the agent already uses everywhere
     * else — {@code "red"}, {@code "hot_pink"}, {@code "#FFD800"} — rather than a second one it
     * would have to be taught.
     */
    public static PaintRecipe parse(JsonObject doc) {
        if (doc == null) {
            return blank();
        }
        int size = doc.has("size") ? asInt(doc.get("size"), PaintArt.SIZE) : PaintArt.SIZE;
        List<CreatureSculpt.PaintRect> out = new ArrayList<>();
        if (doc.has("rects") && doc.get("rects").isJsonArray()) {
            JsonArray arr = doc.getAsJsonArray("rects");
            for (JsonElement el : arr) {
                if (out.size() >= MAX_RECTS || !el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                int w = asInt(o.get("w"), 0);
                int h = asInt(o.get("h"), 0);
                if (w <= 0 || h <= 0) {
                    continue;   // a rectangle with no area paints nothing and says nothing
                }
                String colour = o.has("colour") ? str(o.get("colour"))
                        : o.has("color") ? str(o.get("color")) : "";
                out.add(new CreatureSculpt.PaintRect(
                        asInt(o.get("x"), 0), asInt(o.get("y"), 0), w, h,
                        com.moddingfromamod.species.Species.parseColour(colour)));
            }
        }
        return new PaintRecipe(size, out);
    }

    /**
     * The flat int form the block entity stores: {@code [size, x,y,w,h,colour, x,y,w,h,colour, …]}.
     *
     * <p>One int array rather than a list of compound tags, because this rides in a contraption's
     * saved NBT and is re-read on every assembly: a compound per rectangle carries five tag names
     * with it, which is most of the payload for something whose whole point is being small.
     */
    public int[] toInts() {
        int[] out = new int[1 + rects.size() * 5];
        out[0] = size;
        int i = 1;
        for (CreatureSculpt.PaintRect r : rects) {
            out[i++] = r.x();
            out[i++] = r.y();
            out[i++] = r.width();
            out[i++] = r.height();
            out[i++] = r.colour();
        }
        return out;
    }

    /** Read it back. Damaged or truncated data gives a blank canvas, never an exception. */
    public static PaintRecipe fromInts(int[] data) {
        if (data == null || data.length < 1) {
            return blank();
        }
        List<CreatureSculpt.PaintRect> out = new ArrayList<>();
        // Deliberately tolerant of a TRAILING partial rectangle: a truncated tag should cost the
        // last shape, not the picture.
        for (int i = 1; i + 4 < data.length && out.size() < MAX_RECTS; i += 5) {
            out.add(new CreatureSculpt.PaintRect(
                    data[i], data[i + 1], data[i + 2], data[i + 3], data[i + 4]));
        }
        return new PaintRecipe(data[0], out);
    }

    /**
     * The recipe as the JSON it was written in — the re-editable source a manifest publishes.
     *
     * <p>Deliberately the SHAPES and not the image: an image is a finished thing, and what makes a
     * painting worth keeping is that "the same Spikor but bluer" can start from what was drawn
     * rather than from a PNG somebody would have to trace. Same argument as {@code sculpt.json}
     * being kept beside a creature's baked model.
     */
    public JsonObject toJson() {
        JsonObject doc = new JsonObject();
        doc.addProperty("size", size);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (CreatureSculpt.PaintRect r : rects) {
            JsonObject o = new JsonObject();
            o.addProperty("x", r.x());
            o.addProperty("y", r.y());
            o.addProperty("w", r.width());
            o.addProperty("h", r.height());
            o.addProperty("colour", String.format("#%06X", r.colour() & 0xFFFFFF));
            arr.add(o);
        }
        doc.add("rects", arr);
        return doc;
    }

    private static int asInt(JsonElement el, int fallback) {
        try {
            return el != null && el.isJsonPrimitive() ? el.getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String str(JsonElement el) {
        return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
    }
}
