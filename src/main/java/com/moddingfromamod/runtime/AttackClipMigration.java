package com.moddingfromamod.runtime;

import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>A stored copy of the old attack template becomes the new one, on load</b>.
 *
 * <p>Every sculpted creature carries its own {@code animations.json}, written from {@link CreatureFiles#animations()}
 * when it was made, so changing the template changes nothing already made. The old attack ({@link #OLD}) was sampled
 * away on screen: a 6-tick swing posed every 3rd tick showed only the arm going BACK on two phases of three. A clip
 * that is EXACTLY the old template is ours (the generator wrote it, nobody authored it), so it is rewritten to the
 * template's current attack; a clip with any other keyframe is left alone, because then somebody made it.
 *
 * <p>A migration, not a shim ({@code docs/COMPAT_SHIMS.md}: a file we own is migrated, then the reader goes):
 * {@link ModelRepository#animations} applies it at read and writes the file back where the creature lives, so each
 * stored copy is rewritten the first time it is read. Pure, so the rule is a unit test.
 */
public final class AttackClipMigration {

    private AttackClipMigration() {
    }

    /** The attack template every creature was given, exactly as {@link CreatureFiles} wrote it. */
    public static final JsonObject OLD = JsonParser.parseString("""
            {"loop": false, "animation_length": 0.6,
             "bones": {"armR": {"rotation": {"0.0": [0,0,0], "0.25": [-120,0,0],
                                             "0.45": [35,0,0], "0.6": [0,0,0]}}}}
            """).getAsJsonObject();

    /** The template's attack today. */
    public static JsonObject current() {
        return CreatureFiles.animations().getAsJsonObject("animations")
                .getAsJsonObject("animation.sculpted.attack");
    }

    /**
     * Replace, in place, every attack clip in {@code root} that equals {@link #OLD}.
     *
     * @return true when anything changed (the caller then writes the file back)
     */
    public static boolean migrate(JsonObject root) {
        if (root == null || !root.has("animations") || !root.get("animations").isJsonObject()) {
            return false;
        }
        JsonObject anims = root.getAsJsonObject("animations");
        boolean changed = false;
        for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
            if (e.getKey().endsWith(".attack") && OLD.equals(e.getValue())) {
                e.setValue(current().deepCopy());
                changed = true;
            }
        }
        return changed;
    }
}
