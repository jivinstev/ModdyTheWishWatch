package com.moddingfromamod.runtime;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * <b>When does the creature do its cool thing?</b> The timing brain behind a wished-for creature's
 * abilities — which move fires, at what range, and how often.
 *
 * <p>This is the data-driven answer to "he shoots fireballs and stomps and teleports!". A shipped mod
 * mob writes that as bespoke Java; here it composes from a set of abilities on the species, so a new
 * creature needs no new code and therefore no restart.
 *
 * <p>Kept Minecraft-free so the rules that actually make a fight feel fair — a stomp only lands up
 * close, a fireball only at range, nothing fires twice in the same tick, and a brand-new creature
 * can't nuke you the instant it spawns — are unit-tested instead of play-tested.
 */
public final class Abilities {

    private Abilities() {
    }

    /** The moves a creature can have. */
    public enum Kind {
        /** Spits a projectile at a distant target. */
        RANGED(6.0, 24.0, 40),
        /** A ground slam that knocks everything nearby away. */
        SLAM(0.0, 5.0, 80),
        /** A burst of speed straight at the target. */
        CHARGE(6.0, 20.0, 100),
        /** Calls in small helpers. */
        SUMMON(0.0, 24.0, 300),
        /** Blinks to the target. */
        TELEPORT(8.0, 32.0, 120),
        /** Quietly heals itself. */
        HEAL(0.0, Double.MAX_VALUE, 100);

        /** Distance band in blocks within which this move makes sense. */
        public final double minRange;
        public final double maxRange;
        /** Ticks between uses. */
        public final int cooldown;

        Kind(double minRange, double maxRange, int cooldown) {
            this.minRange = minRange;
            this.maxRange = maxRange;
            this.cooldown = cooldown;
        }

        public boolean inRange(double distance) {
            return distance >= minRange && distance <= maxRange;
        }
    }

    /**
     * Grace period after spawning before ANY ability fires.
     *
     * <p>So a summoned creature doesn't detonate a slam in the child's face on frame one — he should
     * get a moment to look at what he made.
     */
    public static final int SPAWN_GRACE_TICKS = 40;

    /** Per-creature memory of when each move was last used. */
    public static final class Cooldowns {
        private final Map<Kind, Integer> lastUsed = new EnumMap<>(Kind.class);

        public boolean ready(Kind kind, int tick) {
            Integer last = lastUsed.get(kind);
            return last == null || tick - last >= kind.cooldown;
        }

        public void use(Kind kind, int tick) {
            lastUsed.put(kind, tick);
        }

        /** For tests/diagnostics. */
        public int usedAt(Kind kind) {
            return lastUsed.getOrDefault(kind, Integer.MIN_VALUE);
        }
    }

    /**
     * Pick the move to use this tick, or {@code null} for "just keep fighting normally".
     *
     * <p>Order is deliberate: the list is scanned in {@link Kind} declaration order, so a creature
     * that could both slam and shoot prefers the dramatic close-range move when you're next to it and
     * the ranged one when you're not — which is what the range bands already encode.
     *
     * @param have     the abilities this creature has
     * @param tick     its age in ticks
     * @param distance distance to its target, or a negative number when it has none
     */
    public static Kind choose(Set<Kind> have, int tick, double distance, Cooldowns cooldowns) {
        if (have == null || have.isEmpty() || tick < SPAWN_GRACE_TICKS) {
            return null;
        }
        for (Kind kind : Kind.values()) {
            if (!have.contains(kind)) {
                continue;
            }
            // HEAL is the only move that makes sense with no target at all.
            if (distance < 0 && kind != Kind.HEAL) {
                continue;
            }
            if (distance >= 0 && !kind.inRange(distance)) {
                continue;
            }
            if (cooldowns.ready(kind, tick)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * Could {@link #choose} pick ANYTHING this tick, whatever the distance? False when every move it
     * has is cooling down (or it is still in its spawn grace) — then {@code choose} is null for every
     * target, so a caller need not look for one (2026-10-31: the 24-block monster scan with a line of
     * sight per monster ran every tick for every creature with abilities, mostly while cooling down).
     */
    public static boolean anyReady(Set<Kind> have, int tick, Cooldowns cooldowns) {
        if (have == null || have.isEmpty() || tick < SPAWN_GRACE_TICKS) {
            return false;
        }
        for (Kind kind : have) {
            if (cooldowns.ready(kind, tick)) {
                return true;
            }
        }
        return false;
    }

    /** Parse an ability name; null when it isn't one (so unknown words are ignored, not fatal). */
    public static Kind parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toUpperCase(java.util.Locale.ROOT);
        for (Kind k : Kind.values()) {
            if (k.name().equals(v)) {
                return k;
            }
        }
        return null;
    }

    /** Every ability name, for a tool schema or a tab-complete. */
    public static Set<String> names() {
        Set<String> out = new LinkedHashSet<>();
        for (Kind k : Kind.values()) {
            out.add(k.name().toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }
}
