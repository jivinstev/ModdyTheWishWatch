package com.moddingfromamod.species;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * <b>How a creature sounds</b> (chunk 4): the vanilla mob whose voice it borrows, any moment
 * given its own sound, or silence asked for.
 *
 * <p>A wished creature rides a carrier that is muted (a horse's whinny out of a dragon is its own
 * bug), and nothing put a voice back, so every creature ever wished was silent: no growl, no cry
 * when hit, no footfall. This is what it says instead; {@code vanilla/RigVoice} plays it and
 * {@link VoiceSounds} turns it into real sound ids.
 *
 * <p>Three shapes, all from the agent: {@code "ravager"} (that mob's voice), {@code "silent"}
 * (asked for none — not even footsteps), or an object {@code {"from":"wolf","hurt":"…"}} where a
 * named moment replaces the mob's own. {@link #NONE} is a creature nobody gave a voice, which is
 * every creature written before this: it stays as it was.
 *
 * <p>Pure, no Minecraft.
 */
public record Voice(String from, Map<Voice.Slot, String> sounds, boolean silent) {

    /** The moments a creature is heard. */
    public enum Slot {
        AMBIENT, HURT, DEATH, STEP;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** {@code "ambient"} (or {@code "idle"}), {@code "hurt"}, {@code "death"}, {@code "step"}; null otherwise. */
        public static Slot parse(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "ambient", "idle" -> AMBIENT;
                case "hurt" -> HURT;
                case "death", "die" -> DEATH;
                case "step", "steps", "footsteps" -> STEP;
                default -> null;
            };
        }
    }

    /** Nobody gave it a voice: as it always was. */
    public static final Voice NONE = new Voice("", Map.of(), false);
    /** Asked for silence: no voice and no footsteps. */
    public static final Voice SILENT = new Voice("", Map.of(), true);

    public Voice {
        from = mob(from);
        sounds = sounds == null || sounds.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(sounds));
    }

    public boolean isNone() {
        return !silent && from.isEmpty() && sounds.isEmpty();
    }

    /** A mob name as sound ids spell it: {@code "Polar Bear"} and {@code "minecraft:polar_bear"} are {@code polar_bear}. */
    public static String mob(String s) {
        if (s == null) {
            return "";
        }
        String m = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return m.startsWith("minecraft:") ? m.substring("minecraft:".length()) : m;
    }

    private static boolean quiet(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "silent", "none", "quiet", "mute", "nothing" -> true;
            default -> false;
        };
    }

    /**
     * What the agent passed, or {@link #NONE} for nothing. A slot word it does not know is a
     * {@link IllegalArgumentException} naming the four, so the tool can say so rather than drop it.
     */
    public static Voice parse(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return NONE;
        }
        if (e.isJsonPrimitive()) {
            String s = e.getAsString();
            if (s.isBlank()) {
                return NONE;
            }
            return quiet(s) ? SILENT : new Voice(s, Map.of(), false);
        }
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("voice is a mob name (\"ravager\"), \"silent\", or "
                    + "{\"from\":\"wolf\",\"hurt\":\"minecraft:entity.wolf.whine\"}");
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("silent") && o.get("silent").isJsonPrimitive() && o.get("silent").getAsBoolean()) {
            return SILENT;
        }
        String from = "";
        Map<Slot, String> sounds = new EnumMap<>(Slot.class);
        for (var en : o.entrySet()) {
            if (en.getKey().equals("from") || en.getKey().equals("mob") || en.getKey().equals("like")) {
                from = en.getValue().isJsonPrimitive() ? en.getValue().getAsString() : "";
                if (quiet(from)) {
                    return SILENT;
                }
                continue;
            }
            if (en.getKey().equals("silent")) {
                continue;
            }
            Slot slot = Slot.parse(en.getKey());
            if (slot == null) {
                throw new IllegalArgumentException("voice has no moment called '" + en.getKey()
                        + "': the moments are ambient, hurt, death and step");
            }
            if (en.getValue().isJsonPrimitive() && !en.getValue().getAsString().isBlank()) {
                sounds.put(slot, soundId(en.getValue().getAsString()));
            }
        }
        return new Voice(from, sounds, false);
    }

    /** {@code entity.wolf.growl} is {@code minecraft:entity.wolf.growl}. */
    public static String soundId(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.contains(":") ? t : "minecraft:" + t;
    }

    /** The shape {@link #parse} reads back, or null for {@link #NONE}. */
    public JsonElement toJson() {
        if (isNone()) {
            return null;
        }
        if (silent) {
            return new JsonPrimitive("silent");
        }
        if (sounds.isEmpty()) {
            return new JsonPrimitive(from);
        }
        JsonObject o = new JsonObject();
        if (!from.isEmpty()) {
            o.addProperty("from", from);
        }
        for (Slot s : Slot.values()) {
            if (sounds.containsKey(s)) {
                o.addProperty(s.key(), sounds.get(s));
            }
        }
        return o;
    }
}
