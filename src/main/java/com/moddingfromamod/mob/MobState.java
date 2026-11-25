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
                       String glow, String name, Boolean invulnerable, String pet,
                       Double armor, Double knockbackResistance) {

    /**
     * <b>The record's version</b>, written inline as {@code "v"} (#115; see {@code docs/STORE_MIGRATIONS.md}). v1 (no
     * {@code "v"}): before armour and knockback resistance were captured; such a record reads them as null, "not
     * captured", so an undo leaves them as they are. A record of a later version than this is REFUSED
     * ({@link #fromJson} throws {@link Newer}): its undo is not applied by a build that cannot read all of it, and
     * {@code mobs.json} itself is never written over by an older build ({@code MobHandleStore.VERSION}).
     */
    public static final int RECORD_VERSION = 2;

    /** Nothing known about it at all — what a mob touched before this existed restores to. */
    public static final MobState UNKNOWN =
            new MobState(null, null, null, null, null, null, null, null, null, null);

    /** A record written by a newer Moddy than this one: refused, never half-read. */
    public static final class Newer extends RuntimeException {
        public Newer(int v) {
            super("an undo record of version " + v + ", newer than this build's " + RECORD_VERSION);
        }
    }

    /** The shape before armour and knockback resistance (#115): neither is known. */
    public MobState(Double scale, Double maxHealth, Double damage, Double speed,
                    String glow, String name, Boolean invulnerable, String pet) {
        this(scale, maxHealth, damage, speed, glow, name, invulnerable, pet, null, null);
    }

    /**
     * The shape before pets added {@code pet}: a record read back from an older undo store, or a
     * test, knows nothing about pet behaviour, and null is exactly "we do not know".
     */
    public MobState(Double scale, Double maxHealth, Double damage, Double speed,
                    String glow, String name, Boolean invulnerable) {
        this(scale, maxHealth, damage, speed, glow, name, invulnerable, null, null, null);
    }

    public MobState {
        glow = glow == null || glow.isBlank() ? null : glow.trim().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? null : name.trim();
    }

    /** True when there is nothing here to put back. */
    public boolean isEmpty() {
        return scale == null && maxHealth == null && damage == null && speed == null
                && glow == null && name == null && invulnerable == null && pet == null && armor == null
                && knockbackResistance == null;
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
        if (armor != null) {
            parts.add(trim(armor) + " armour");
        }
        if (knockbackResistance != null) {
            parts.add("knockback resistance " + trim(knockbackResistance));
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
        o.addProperty("v", RECORD_VERSION);
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
        if (armor != null) {
            o.addProperty("armor", armor);
        }
        if (knockbackResistance != null) {
            o.addProperty("knockback_resistance", knockbackResistance);
        }
        return o;
    }

    /**
     * Read one back. Anything unreadable becomes null — "we do not know" — never a made-up value. A v1 record (no
     * {@code "v"}) has no armour or knockback resistance: null, not captured. A newer record throws {@link Newer}.
     */
    public static MobState fromJson(JsonObject o) {
        if (o == null) {
            return UNKNOWN;
        }
        int v = versionOf(o);
        if (v > RECORD_VERSION) {
            throw new Newer(v);
        }
        boolean defence = v >= 2;
        return new MobState(dbl(o, "scale"), dbl(o, "health"), dbl(o, "damage"), dbl(o, "speed"),
                str(o, "glow"), str(o, "name"), bool(o, "invulnerable"),
                o.has("pet") && o.get("pet").isJsonPrimitive() ? o.get("pet").getAsString() : null,
                defence ? dbl(o, "armor") : null, defence ? dbl(o, "knockback_resistance") : null);
    }

    /** A record's version: its {@code "v"}, or 1 for a record written before records had one. */
    public static int versionOf(JsonObject o) {
        try {
            return o != null && o.has("v") && o.get("v").isJsonPrimitive() ? o.get("v").getAsInt() : 1;
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;   // unreadable: refused like a newer one, never guessed at
        }
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
