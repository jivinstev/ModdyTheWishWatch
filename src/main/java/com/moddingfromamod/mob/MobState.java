package com.moddingfromamod.mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;

/**
 * What a mob was like <b>before a wish touched it</b> — every field {@code modify_mob} can write.
 *
 * <p>Two jobs, and they are the same data seen from two ends.
 *
 * <ul>
 *   <li><b>Undo.</b> {@code MadeKind} says in as many words that a kind recorded but never reversed
 *       makes the {@code [undo]} button lie. A mob edit is not a thing that can be deleted — the
 *       animal was already there — so its only honest inverse is putting the numbers back, and that
 *       needs them written down at the moment they change.</li>
 *   <li><b>History.</b> "Rung 2 took him from ×1 to ×2" is what tells rung 3 what "even bigger"
 *       should mean. Without it the agent reads only the current value and has to guess the step.</li>
 * </ul>
 *
 * <p><b>Null means "this mob has no such thing", not zero.</b> A cow has no attack damage and a bat
 * has no attack damage; writing 0 back into either on undo would be inventing a value the mob never
 * had. Every field is boxed for that reason, exactly as {@link MobTweak}'s are.
 *
 * <p>Pure — no Minecraft imports — so the merge, the description and the round trip are unit tests.
 * {@link MobStates} is the half that reads and writes a real entity.
 */
public record MobState(Double scale, Double maxHealth, Double damage, Double speed,
                       String glow, String name, Boolean invulnerable, String pet) {

    /** Nothing known about it at all — what a mob touched before this existed restores to. */
    public static final MobState UNKNOWN =
            new MobState(null, null, null, null, null, null, null, null);

    /**
     * The shape before pets added {@code pet}: a record read back from an older undo store, or a
     * test, knows nothing about pet behaviour, and null is exactly "we do not know".
     */
    public MobState(Double scale, Double maxHealth, Double damage, Double speed,
                    String glow, String name, Boolean invulnerable) {
        this(scale, maxHealth, damage, speed, glow, name, invulnerable, null);
    }

    public MobState {
        glow = glow == null || glow.isBlank() ? null : glow.trim().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? null : name.trim();
    }

    /** True when there is nothing here to put back. */
    public boolean isEmpty() {
        return scale == null && maxHealth == null && damage == null && speed == null
                && glow == null && name == null && invulnerable == null && pet == null;
    }

    /**
     * How it read, in the same words {@link MobTweak#summary()} uses.
     *
     * <p>Deliberately the same vocabulary in both directions: the agent sees "was size ×1" beside
     * "size ×2" and can subtract. Two different phrasings for one number is a thing to decode.
     */
    public String describe() {
        List<String> parts = new ArrayList<>();
        if (scale != null) {
            parts.add("size ×" + trim(scale));
        }
        if (maxHealth != null) {
            parts.add((int) (double) maxHealth + " health");
        }
        if (damage != null) {
            parts.add((int) (double) damage + " attack damage");
        }
        if (speed != null) {
            parts.add("speed " + trim(speed));
        }
        if (glow != null) {
            parts.add("glowing " + glow.replace('_', ' '));
        }
        if (name != null) {
            parts.add("named \"" + name + "\"");
        }
        if (invulnerable != null && invulnerable) {
            parts.add("invulnerable");
        }
        return parts.isEmpty() ? "nothing recorded" : String.join(", ", parts);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v)
                : String.format(Locale.ROOT, "%.2f", v);
    }

    // ===================== JSON =====================

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (scale != null) {
            o.addProperty("scale", scale);
        }
        if (maxHealth != null) {
            o.addProperty("health", maxHealth);
        }
        if (damage != null) {
            o.addProperty("damage", damage);
        }
        if (speed != null) {
            o.addProperty("speed", speed);
        }
        if (glow != null) {
            o.addProperty("glow", glow);
        }
        if (name != null) {
            o.addProperty("name", name);
        }
        if (invulnerable != null) {
            o.addProperty("invulnerable", invulnerable);
        }
        if (pet != null) {
            // "" is a real value: it DID nothing, and undoing a "follow me" puts that back.
            o.addProperty("pet", pet);
        }
        return o;
    }

    /** Read one back. Anything unreadable becomes null — "we do not know" — never a made-up value. */
    public static MobState fromJson(JsonObject o) {
        if (o == null) {
            return UNKNOWN;
        }
        return new MobState(dbl(o, "scale"), dbl(o, "health"), dbl(o, "damage"), dbl(o, "speed"),
                str(o, "glow"), str(o, "name"), bool(o, "invulnerable"),
                o.has("pet") && o.get("pet").isJsonPrimitive() ? o.get("pet").getAsString() : null);
    }

    private static Double dbl(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Boolean bool(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
