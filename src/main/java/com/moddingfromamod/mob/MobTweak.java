package com.moddingfromamod.mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;

/**
 * A requested change to a <b>real</b> mob — one already in the world, whether vanilla or from any
 * installed mod — parsed and clamped before anything touches the game.
 *
 * <p>This is the data half of {@code modify_mob}, and the reason the "summon the real thing" rule
 * is actually workable. Previously an agent that had summoned a genuine
 * {@code witherstormmod:wither_storm} had no way to answer "make it bigger!" except to throw the
 * real boss away and rebuild a lookalike out of {@code wish_mob} parts. Scale and glow colour ride
 * on vanilla mechanisms that work on <i>any</i> entity, so the real mob can simply be adjusted.
 *
 * <p><b>Null means "leave it alone."</b> Every knob is a boxed value precisely so that "don't touch
 * the health" is representable and distinct from "set the health to 0" — an agent sending
 * {@code {"scale": 4}} must not silently reset every other stat to a default.
 *
 * <p>Pure — no Minecraft imports — so parsing and clamping are unit-tested without booting the game.
 */
public record MobTweak(String targetQuery, double radius, int count,
                       Double scale, Double health, Double damage, Double speed,
                       String glow, String name, Boolean invulnerable, Boolean persistent,
                       PetBehaviour pet, BehaviourProfile behaviour) {

    /** Every caller from: no temperament, powers or abilities. */
    public MobTweak(String targetQuery, double radius, int count,
                    Double scale, Double health, Double damage, Double speed,
                    String glow, String name, Boolean invulnerable, Boolean persistent,
                    PetBehaviour pet) {
        this(targetQuery, radius, count, scale, health, damage, speed, glow, name, invulnerable, persistent,
                pet, BehaviourProfile.NONE);
    }

    public static final double MIN_RADIUS = 1, MAX_RADIUS = 64, DEFAULT_RADIUS = 24;
    /**
     * How many mobs one tweak reaches — one wish's whole wave of ordinary mobs, so "make
     * them all giant" reaches every one the wand just brought. Was 20, which nothing measured.
     */
    public static final int MAX_COUNT = SpawnBudget.VANILLA_PER_WISH;

    /** Same size range as an invented creature — big enough to be exciting, small enough to play with. */
    public static final double MIN_SCALE = 0.2, MAX_SCALE = 8.0;

    /**
     * Deliberately more generous than a {@code wish_mob}'s 500. A modded boss can natively have
     * well over that, and clamping to the invented-creature ceiling would turn "make it tougher"
     * into a silent <i>nerf</i> of the very boss the child was excited about.
     */
    public static final double MIN_HEALTH = 1, MAX_HEALTH = 2000;

    /** Likewise above the invented-creature cap: real bosses already hit harder than 40. */
    public static final double MAX_DAMAGE = 100;

    public static final double MIN_SPEED = 0.05, MAX_SPEED = 0.6;

    public static final int MAX_NAME_LENGTH = 48;

    public MobTweak {
        pet = pet == null ? PetBehaviour.NONE : pet;
        behaviour = behaviour == null ? BehaviourProfile.NONE : behaviour;
        targetQuery = targetQuery == null ? "" : targetQuery.trim().toLowerCase(Locale.ROOT);
        radius = clamp(radius, MIN_RADIUS, MAX_RADIUS);
        count = (int) clamp(count, 1, MAX_COUNT);
        scale = clampOrNull(scale, MIN_SCALE, MAX_SCALE);
        health = clampOrNull(health, MIN_HEALTH, MAX_HEALTH);
        damage = clampOrNull(damage, 0, MAX_DAMAGE);
        speed = clampOrNull(speed, MIN_SPEED, MAX_SPEED);
        glow = glow == null ? null : GlowColour.resolve(glow);
        if (name != null) {
            name = name.trim();
            if (name.length() > MAX_NAME_LENGTH) {
                name = name.substring(0, MAX_NAME_LENGTH);
            }
            if (name.isEmpty()) {
                name = null;
            }
        }
    }

    /** Nothing to do — worth catching early so the agent gets a useful error, not a silent no-op. */
    public boolean isEmpty() {
        return scale == null && health == null && damage == null && speed == null
                && glow == null && name == null && invulnerable == null && persistent == null
                && pet.isEmpty() && behaviour.isEmpty();
    }

    /** True when the agent asked for a glow colour rather than for the glow to be switched off. */
    public boolean glowsInColour() {
        return glow != null && !glow.equals(GlowColour.NONE);
    }

    /** What changed, in words — returned to the agent so it can tell the child accurately. */
    public String summary() {
        List<String> parts = new ArrayList<>();
        if (scale != null) {
            parts.add("size ×" + trim(scale));
        }
        if (health != null) {
            parts.add((int) (double) health + " health");
        }
        if (damage != null) {
            parts.add((int) (double) damage + " attack damage");
        }
        if (speed != null) {
            parts.add("speed " + trim(speed));
        }
        if (glow != null) {
            parts.add(glowsInColour() ? "glowing " + glow.replace('_', ' ') : "glow off");
        }
        if (name != null) {
            parts.add("named \"" + name + "\"");
        }
        if (invulnerable != null) {
            parts.add(invulnerable ? "invulnerable" : "vulnerable again");
        }
        if (persistent != null) {
            parts.add(persistent ? "here to stay" : "free to despawn");
        }
        parts.addAll(pet.summary());
        parts.addAll(behaviour.summary());
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    // ===================== JSON =====================

    /** Parse the tool's arguments. Bad values are clamped or ignored — never fatal. */
    public static MobTweak fromJson(JsonObject o) {
        if (o == null) {
            return new MobTweak("", DEFAULT_RADIUS, 1, null, null, null, null, null, null, null, null,
                    PetBehaviour.NONE);
        }
        return new MobTweak(
                str(o, "target"),
                dbl(o, "radius") == null ? DEFAULT_RADIUS : dbl(o, "radius"),
                intOr(o, "count", 1),
                dbl(o, "scale"),
                dbl(o, "health"),
                dbl(o, "damage"),
                dbl(o, "speed"),
                str(o, "glow") == null || str(o, "glow").isEmpty() ? null : str(o, "glow"),
                str(o, "name"),
                bool(o, "invulnerable"),
                bool(o, "persistent"),
                PetBehaviour.fromJson(o),
                BehaviourProfile.fromJson(o));
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    private static Double clampOrNull(Double v, double lo, double hi) {
        return v == null ? null : clamp(v, lo, hi);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static Double dbl(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int intOr(JsonObject o, String k, int fallback) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
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
