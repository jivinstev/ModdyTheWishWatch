package com.moddingfromamod.paint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * <b>One painting, and enough about what was there before to put it back.</b>
 *
 * <p>Undo is what this record exists for. {@code WishUndo} replays from a registry rather than from
 * a region snapshot, and it can only reverse what somebody wrote down — so the two cases a child
 * can produce are both recorded here:
 *
 * <ul>
 *   <li><b>a panel that was not there before</b> — the inverse is to take it away, which is why
 *       {@link #placedPanel} exists rather than being inferred;</li>
 *   <li><b>a repaint of a panel that was</b> — the inverse is the picture that was on it, which is
 *       why {@link #previousKey} and {@link #previousRecipe} are kept. Nothing else in the mod
 *       remembers them: the block entity holds the CURRENT picture and has no history.</li>
 * </ul>
 *
 * <p><b>One step, not a stack.</b> A child says "no, put it back" about the thing that just
 * happened; nobody has ever asked for the picture before last. A stack would cost payload on every
 * paint for a case that does not arise, and {@code UndoContract} only ever reverses the last wish.
 *
 * <p>Pure — no Minecraft — so the JSON round trip is unit-tested. Positions are stored as three
 * ints rather than a {@code BlockPos} for the same reason.
 */
public record PaintRecord(String id, String dimension, int x, int y, int z,
                          String key, boolean placedPanel,
                          String previousKey, PaintRecipe previousRecipe,
                          int previousWidth, int previousHeight,
                          String facing, int wide, int tall, PaintRecipe recipe,
                          String ownerId, java.util.List<PaintRecord.BoardCell> board) {

    /**
     * <b>One block a picture's board changed</b>: where, what it was, and what the
     * board made it. {@code PictureCommand.hangBoard} stands a dark-oak board up behind a picture
     * and clears the cells in front, and before this neither was written down anywhere, so an
     * undo took the frames down and left the board standing in the child's world. An undo puts
     * {@code was} back only where the block is still {@code put} — anything built on it since is
     * the child's, and stays.
     */
    public record BoardCell(int x, int y, int z, String was, String put) {
        public BoardCell {
            was = was == null ? "" : was;
            put = put == null ? "" : put;
        }
    }

    /** Every shape before boards: no board recorded. */
    public PaintRecord(String id, String dimension, int x, int y, int z,
                       String key, boolean placedPanel,
                       String previousKey, PaintRecipe previousRecipe,
                       int previousWidth, int previousHeight,
                       String facing, int wide, int tall, PaintRecipe recipe, String ownerId) {
        this(id, dimension, x, y, z, key, placedPanel, previousKey, previousRecipe,
                previousWidth, previousHeight, facing, wide, tall, recipe, ownerId,
                java.util.List.of());
    }

    /** The same record, with the board it stood up. */
    public PaintRecord withBoard(java.util.List<BoardCell> cells) {
        return new PaintRecord(id, dimension, x, y, z, key, placedPanel, previousKey,
                previousRecipe, previousWidth, previousHeight, facing, wide, tall, recipe,
                ownerId, cells);
    }

    /**
     * Every shape: no owner, which is SHARED ({@code ArtifactOwner}) — the right
     * reading for every picture painted before pictures had owners.
     */
    public PaintRecord(String id, String dimension, int x, int y, int z,
                       String key, boolean placedPanel,
                       String previousKey, PaintRecipe previousRecipe,
                       int previousWidth, int previousHeight,
                       String facing, int wide, int tall, PaintRecipe recipe) {
        this(id, dimension, x, y, z, key, placedPanel, previousKey, previousRecipe,
                previousWidth, previousHeight, facing, wide, tall, recipe, "");
    }

    /** The same record, stamped with whose picture it is. */
    public PaintRecord withOwner(String owner) {
        return new PaintRecord(id, dimension, x, y, z, key, placedPanel, previousKey,
                previousRecipe, previousWidth, previousHeight, facing, wide, tall, recipe,
                owner, board);
    }

    /**
     * The pre-flip shape: a picture with no vanilla copy to take down.
     *
     * <p>Kept so that every caller and every test that predates the path-2 flip still compiles and
     * still means what it meant. An empty {@code facing} is the record saying "no map art here",
     * which is the truth for a panel on a contraption and for anything written before the flip.
     */
    public PaintRecord(String id, String dimension, int x, int y, int z,
                       String key, boolean placedPanel,
                       String previousKey, PaintRecipe previousRecipe,
                       int previousWidth, int previousHeight) {
        this(id, dimension, x, y, z, key, placedPanel, previousKey, previousRecipe,
                previousWidth, previousHeight, "", 0, 0, PaintRecipe.blank());
    }

    /**
     * The vanilla-route shape without the current recipe — kept for the tests and callers written
     * between the flip and the repaint fix.
     */
    public PaintRecord(String id, String dimension, int x, int y, int z,
                       String key, boolean placedPanel,
                       String previousKey, PaintRecipe previousRecipe,
                       int previousWidth, int previousHeight,
                       String facing, int wide, int tall) {
        this(id, dimension, x, y, z, key, placedPanel, previousKey, previousRecipe,
                previousWidth, previousHeight, facing, wide, tall, PaintRecipe.blank());
    }

    public PaintRecord {
        id = id == null ? "" : id;
        dimension = dimension == null ? "" : dimension;
        key = key == null ? "" : key;
        previousKey = previousKey == null ? "" : previousKey;
        previousRecipe = previousRecipe == null ? PaintRecipe.blank() : previousRecipe;
        previousWidth = PanelGeometry.clampSpan(previousWidth);
        previousHeight = PanelGeometry.clampSpan(previousHeight);
        facing = facing == null ? "" : facing;
        // NOT clamped through PanelGeometry, and that is deliberate: 0 has to survive as 0, because
        // 0 is how this record says "there is no map art at this position". clampSpan's floor is 1,
        // so clamping here would turn every pre-flip record into a claim that one map tile is
        // hanging somewhere — and undo would then go looking for a frame that never existed.
        wide = wide <= 0 ? 0 : PanelGeometry.clampSpan(wide);
        tall = tall <= 0 ? 0 : PanelGeometry.clampSpan(tall);
        recipe = recipe == null ? PaintRecipe.blank() : recipe;
        ownerId = com.moddingfromamod.marshal.ArtifactOwner.normalise(ownerId);
        board = board == null ? java.util.List.of() : java.util.List.copyOf(board);
    }

    /**
     * <b>The picture this record painted</b> — needed only on the vanilla route, and needed there
     * for a reason worth stating.
     *
     * <p>Undoing a REPAINT means putting the earlier picture back, and the earlier picture used to
     * be read off the panel's block entity, which held it. On a static wall there is no longer a
     * block entity, so a repaint had no way to learn what it was painting over: undo would have
     * removed the mural outright instead of reverting it. Keeping the current recipe here means the
     * NEXT paint of the same id can read it as its {@code previousRecipe}, which is exactly the
     * relationship the block entity used to provide.
     *
     * <p>Blank on the panel route, where the block entity is still the answer.
     */
    public boolean hasRecipe() {
        return !recipe.isEmpty();
    }

    /**
     * Is there vanilla map art at this position for an undo to take down?
     *
     * <p>Recorded rather than looked up, because after the flip there is no panel on a static wall
     * and so <b>nothing in the world to ask</b>: the facing and the span used to be read off the
     * panel's block state, and once the panel stopped being placed that read returned nothing and
     * an undo left thirty maps hanging over a wish it had reported as reversed.
     */
    public boolean hasVanillaCopy() {
        return !facing.isEmpty() && wide > 0 && tall > 0;
    }

    /** Was there a picture here before this one? Decides remove-vs-restore. */
    public boolean hadAPictureBefore() {
        return !previousKey.isEmpty() && !previousRecipe.isEmpty();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("dimension", dimension);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("key", key);
        o.addProperty("placed_panel", placedPanel);
        o.addProperty("previous_key", previousKey);
        o.addProperty("previous_width", previousWidth);
        o.addProperty("previous_height", previousHeight);
        o.addProperty("facing", facing);
        o.addProperty("wide", wide);
        o.addProperty("tall", tall);
        JsonArray now = new JsonArray();
        for (int v : recipe.toInts()) {
            now.add(v);
        }
        o.add("recipe", now);
        JsonArray ints = new JsonArray();
        for (int v : previousRecipe.toInts()) {
            ints.add(v);
        }
        o.add("previous_recipe", ints);
        // Only when there is one, so a shared picture's bytes are exactly what v1 wrote.
        com.moddingfromamod.marshal.ArtifactOwner.stamp(o, ownerId);
        // Likewise the board: absent on every record without one, so nothing already
        // saved changes by a byte, and an older jar reading a new record simply ignores the key.
        if (!board.isEmpty()) {
            JsonArray cells = new JsonArray();
            for (BoardCell c : board) {
                JsonObject b = new JsonObject();
                b.addProperty("x", c.x());
                b.addProperty("y", c.y());
                b.addProperty("z", c.z());
                b.addProperty("was", c.was());
                b.addProperty("put", c.put());
                cells.add(b);
            }
            o.add("board", cells);
        }
        return o;
    }

    /** Read one back. Returns null for anything unusable — one bad record must not cost the file. */
    public static PaintRecord fromJson(JsonObject o) {
        if (o == null || !o.has("id")) {
            return null;
        }
        try {
            int[] ints = new int[0];
            if (o.has("previous_recipe") && o.get("previous_recipe").isJsonArray()) {
                JsonArray arr = o.getAsJsonArray("previous_recipe");
                ints = new int[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    ints[i] = arr.get(i).getAsInt();
                }
            }
            return new PaintRecord(
                    o.get("id").getAsString(),
                    o.has("dimension") ? o.get("dimension").getAsString() : "",
                    intOf(o, "x"), intOf(o, "y"), intOf(o, "z"),
                    o.has("key") ? o.get("key").getAsString() : "",
                    o.has("placed_panel") && o.get("placed_panel").getAsBoolean(),
                    o.has("previous_key") ? o.get("previous_key").getAsString() : "",
                    PaintRecipe.fromInts(ints),
                    intOf(o, "previous_width"), intOf(o, "previous_height"),
                    // Absent on every record written before the flip, which reads back as "no map
                    // art" — the right answer for those, since there was none.
                    o.has("facing") ? o.get("facing").getAsString() : "",
                    intOf(o, "wide"), intOf(o, "tall"),
                    PaintRecipe.fromInts(intsOf(o, "recipe")),
                    com.moddingfromamod.marshal.ArtifactOwner.of(o),
                    boardOf(o));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static java.util.List<BoardCell> boardOf(JsonObject o) {
        if (!o.has("board") || !o.get("board").isJsonArray()) {
            return java.util.List.of();
        }
        java.util.List<BoardCell> out = new java.util.ArrayList<>();
        for (var el : o.getAsJsonArray("board")) {
            if (el.isJsonObject()) {
                JsonObject c = el.getAsJsonObject();
                out.add(new BoardCell(intOf(c, "x"), intOf(c, "y"), intOf(c, "z"),
                        c.has("was") ? c.get("was").getAsString() : "",
                        c.has("put") ? c.get("put").getAsString() : ""));
            }
        }
        return out;
    }

    private static int[] intsOf(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) {
            return new int[0];
        }
        JsonArray arr = o.getAsJsonArray(k);
        int[] out = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = arr.get(i).getAsInt();
        }
        return out;
    }

    private static int intOf(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
