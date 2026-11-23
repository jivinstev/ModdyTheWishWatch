package com.moddingfromamod.item;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>What a wished item LOOKS like — its icon, held apart from what it IS</b> (wish metadata
 * split into the actual wish and its icon, so that both can be iterated on independently).
 *
 * <p>Until this existed an item's icon was two strings among thirteen in {@link ItemSpecStore.Spec},
 * persisted inline in {@code item-specs.json}. So any change to what an icon IS — the bodies ×
 * trims × accents — was a schema change to the <i>design</i> store too, and authoring a
 * cached wish meant committing to its artwork in the same record. Now the look is persisted in its
 * own store ({@link ItemIconStore}, {@code item-icons.json}) with its own schema version, and the
 * design store ({@code item-specs.json} v2) no longer carries it.
 *
 * <h2>⚠ The sprite is a NAME, and that is what keeps the number contract</h2>
 *
 * {@link #sprite} is the icon's name in {@link IconSprites} ({@code "wand"}), never its
 * {@code custom_model_data} number. The number is what a stack in a child's inventory carries, and
 * {@link WishIcons}' rule — <i>retire a number rather than repoint it</i> — binds whatever this
 * record grows into: a new field here may add artwork, it may never change what an existing name
 * resolves to. Nothing about this split moved a number; {@code ItemIconSplitTest} says so.
 *
 * <p>Pure and Minecraft-free.
 *
 * @param sprite the {@link IconSprites} name, e.g. {@code "wand"}
 * @param tint   the colour the grey sprite is dyed, exactly as the agent sent it ({@code "#8A2BE2"}
 *               or a word like {@code "purple"}); may be null
 */
public record ItemLook(String sprite, String tint) {

    /** @return this look's persisted fields, WITHOUT the id — the store adds that. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("sprite", sprite);
        if (tint != null) {
            o.addProperty("tint", tint);
        }
        return o;
    }

    /** @return the look, or null when the record has no usable sprite — never a half-built one. */
    public static ItemLook fromJson(JsonObject o) {
        String sprite = str(o, "sprite");
        return sprite == null || sprite.isBlank() ? null : new ItemLook(sprite, str(o, "tint"));
    }

    /**
     * <b>The look an OLD design record carried inline</b> — {@code icon} and {@code colour}, beside
     * the design's own fields, in every {@code item-specs.json} up to v1, every pre-T9.1
     * {@code items.json} and every library {@code item.json} promoted before the split.
     *
     * @return the look, or null when the record carries no icon (a v2 design record)
     */
    public static ItemLook inline(JsonObject design) {
        String icon = str(design, "icon");
        return icon == null || icon.isBlank() ? null : new ItemLook(icon, str(design, "colour"));
    }

    private static String str(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
