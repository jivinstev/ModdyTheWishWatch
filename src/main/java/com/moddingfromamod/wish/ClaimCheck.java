package com.moddingfromamod.wish;

import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>How the world is asked whether a claim is true</b> — a closed vocabulary, never
 * free text.
 *
 * <p>Live, 2026-09-25: the child was told <i>"✔ 2 mobs playing pickleball"</i> about two companions
 * that had already walked off the court, <i>"✔ he follows me"</i> about a Spikor that did not, and
 * <i>"Fixed! Spikor's fire won't burn himself"</i> straight after his spawn had failed. Each ✔ was
 * the agent's own boolean: {@code finish_building} took {@code works:true} on trust, and
 * {@code evidence} was stored and read by nothing.
 *
 * <p>So a claim that says it works now carries a {@code check} the SERVER runs before the child is
 * told. The check is a type — a kind from {@link Kind} and bounded fields — for the reason
 * docs/PATTERNS.md gives: the boundary is a type system, not a filter, so there is no string here
 * the agent could use to make the server do anything but count, measure and compare. Minecraft-free
 * so it is unit-tested ({@code ClaimCheckTest}); {@code ClaimWorld} is the half that looks.
 *
 * @param kind   what to measure
 * @param what   the creature (species id, mob type or name), block, item, effect or datapack
 * @param min    how many there must be (counts only)
 * @param near   where to look (counts only)
 * @param radius how far from {@code near}, in blocks (counts only)
 */
public record ClaimCheck(Kind kind, String what, int min, Near near, int radius) {

    /** The things a claim can be checked by. Anything else is a claim nothing can check. */
    public enum Kind {
        /** At least {@code min} of a creature near the wish's spot (or the child), after it settles. */
        CREATURE_COUNT,
        /** A creature that is set to follow the child, and is with him (or closing in) after it settles. */
        FOLLOWS,
        /** A creature alive and not losing health while it is watched — "his fire won't burn him". */
        STAYS_HEALTHY,
        /** At least {@code min} of a block near the wish's spot (or the child). */
        BLOCK_PRESENT,
        /** At least {@code min} of an item (id or name) in the child's inventory. */
        ITEM_IN_INVENTORY,
        /** The child has an effect on him. */
        EFFECT_ON,
        /** A datapack is loaded and enabled. */
        DATAPACK_ACTIVE
    }

    /** Where a count is centred. */
    public enum Near {
        /** The spot the wish was aimed at (its locked anchor). */
        WISH,
        /** Wherever the child is standing. */
        CHILD
    }

    public static final int MAX_MIN = 64;
    public static final int MAX_RADIUS = 48;
    public static final int DEFAULT_RADIUS = 16;
    public static final int MAX_WHAT = 64;

    public ClaimCheck {
        what = what == null ? "" : what.trim();
    }

    /** The kinds a creature check covers — the ones that have to be WATCHED rather than read once. */
    public boolean watched() {
        return kind == Kind.CREATURE_COUNT || kind == Kind.FOLLOWS || kind == Kind.STAYS_HEALTHY;
    }

    /**
     * Read {@code claim.check}. Absent is {@code null} — a claim nothing checks, which the child is
     * told as "I tried" rather than ticked. Present but malformed throws, naming the problem, so the
     * agent is told rather than silently getting "I tried".
     */
    public static ClaimCheck parse(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("`check` must be an object like "
                    + "{\"kind\":\"creature_count\",\"what\":\"turtle_king\",\"min\":1}");
        }
        JsonObject o = el.getAsJsonObject();
        String kindText = text(o, "kind").toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        Kind kind;
        try {
            kind = Kind.valueOf(kindText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown check kind '" + text(o, "kind") + "'. The kinds are: "
                    + kinds());
        }
        String what = text(o, "what");
        if (what.isEmpty()) {
            throw new IllegalArgumentException("a " + kind.name().toLowerCase(Locale.ROOT)
                    + " check needs `what` (the creature, block, item, effect or datapack)");
        }
        if (what.length() > MAX_WHAT || !what.matches("[A-Za-z0-9_:./\\- ]+")) {
            throw new IllegalArgumentException("`what` must be a plain id or name (letters, digits, "
                    + "_ : . / - and spaces, at most " + MAX_WHAT + ")");
        }
        int min = bounded(o, "min", 1, 1, MAX_MIN);
        int radius = bounded(o, "radius", DEFAULT_RADIUS, 1, MAX_RADIUS);
        String nearText = text(o, "near").toUpperCase(Locale.ROOT);
        Near near = nearText.isEmpty() ? Near.WISH : switch (nearText) {
            case "WISH", "BUILD", "IT" -> Near.WISH;
            case "CHILD", "PLAYER", "NEAR_PLAYER", "ME" -> Near.CHILD;
            default -> throw new IllegalArgumentException("`near` is 'wish' or 'child'");
        };
        return new ClaimCheck(kind, what, min, near, radius);
    }

    /** The vocabulary, as the agent is told it. */
    public static String kinds() {
        return "creature_count {what, min, near:wish|child, radius}, follows {what}, "
                + "stays_healthy {what}, block_present {what, min, near, radius}, "
                + "item_in_inventory {what, min}, effect_on {what}, datapack_active {what}";
    }

    /** How the check reads, for the agent and the log. */
    public String describe() {
        String k = kind.name().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case CREATURE_COUNT, BLOCK_PRESENT -> k + " " + min + "× " + what + " within " + radius
                    + " of the " + (near == Near.WISH ? "wish's spot" : "child");
            case ITEM_IN_INVENTORY -> k + " " + min + "× " + what;
            default -> k + " " + what;
        };
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().trim() : "";
    }

    private static int bounded(JsonObject o, String key, int dflt, int lo, int hi) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return dflt;
        }
        int v;
        try {
            v = o.get(key).getAsInt();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("`" + key + "` must be a whole number");
        }
        if (v < lo || v > hi) {
            throw new IllegalArgumentException("`" + key + "` must be between " + lo + " and " + hi);
        }
        return v;
    }
}
