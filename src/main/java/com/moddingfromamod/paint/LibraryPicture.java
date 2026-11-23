package com.moddingfromamod.paint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>A picture the library can hang again</b> — {@code pictures/<id>/painting.json}.
 *
 * <p>The same file the Mod Repo's provenance publishes for a painted wish ({@code painting.json}:
 * {@code size} and {@code rects}), so promoting a picture is a copy rather than a conversion, plus
 * how many blocks {@code wide} and {@code tall} it hangs. The envelope is INLINE —
 * {@code {"store":"library-picture","v":1,"size":…,"rects":[…],"wide":3,"tall":3}} — so the
 * published shape, which carries no envelope, still reads as v1.
 *
 * <p>Minecraft-free apart from the recipe type, which is itself plain data.
 */
public record LibraryPicture(PaintRecipe recipe, int wide, int tall) {

    public static final String STORE = "library-picture";
    public static final int SCHEMA = 1;
    /** The file that proves a {@code pictures/<id>/} directory holds a picture. */
    public static final String FILE = "painting.json";

    /** Read one. Throws {@link IllegalArgumentException} naming what is wrong — never a blank. */
    public static LibraryPicture parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json == null ? "" : json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage());
        }
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("a picture is a JSON object");
        }
        JsonObject o = root.getAsJsonObject();
        if (o.has("store") && !STORE.equals(o.get("store").getAsString())) {
            throw new IllegalArgumentException("this is a '" + o.get("store").getAsString()
                    + "' file, not a picture");
        }
        int v = o.has("v") ? o.get("v").getAsInt() : 1;
        if (v > SCHEMA) {
            // The latch: a newer jar wrote this, and guessing at its shape is how a picture
            // comes back blank. Refuse by name instead.
            throw new IllegalArgumentException("this picture is schema v" + v + " and this server "
                    + "reads v" + SCHEMA + " — it was written by a newer version");
        }
        PaintRecipe recipe = PaintRecipe.parse(o);
        if (recipe.isEmpty()) {
            throw new IllegalArgumentException("the picture has no shapes in it");
        }
        return new LibraryPicture(recipe, span(o, "wide"), span(o, "tall"));
    }

    private static int span(JsonObject o, String key) {
        int n = o.has(key) ? o.get(key).getAsInt() : 2;
        return PanelGeometry.clampSpan(n);
    }
}
