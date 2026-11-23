package com.moddingfromamod.world;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>One rule a world applies</b> ("How a world's rules are configured"):
 * a TYPE from the rule library, its parameters, and the words a refusal says.
 *
 * <p>The library is Java, tested once ({@code WorldRulesTest}); a world picks from it in JSON
 * ({@code worlds/rules/<id>.rules.json}). What each type means:
 * <ul>
 *   <li>{@link Type#DENY_WORLD_EFFECT} — the world-rules grader refuses a wish that CLEARLY asks for
 *       one of its {@code effects} at world scope, and the reach backstop refuses the world-wide
 *       tools and commands that change it;</li>
 *   <li>{@link Type#BASE_REACH} — a wish lands on its caster's base and nowhere else
 *       ({@link BaseReach}); its effects are the places ({@code outside_own_base},
 *       {@code others_bases}, {@code other_dimensions});</li>
 *   <li>{@link Type#ALLOW_BASE_SCOPED} — standing rules scoped to the child's own base or his pets
 *       are allowed (the analysis: 18 of the 52 blocked synthetic rule wishes were of that kind),
 *       said to the grader and to the agent;</li>
 *   <li>{@link Type#WISH_BANK} — the world has a refilling wish bank (D18): its
 *       {@code start}, {@code refillMinutes}, {@code cap} and {@code pricePerWish}. A world with no
 *       such rule has no bank, and wishing there is exactly what it was before ({@code bank/WishBank});</li>
 *   <li>{@link Type#PLAYER_REACH} — whether another player may be a REFERENCE here: a place
 *       a wish takes you, or a compass points at. {@code mode} is {@code open} or {@code off}; a world
 *       that declares none is {@code off}. Doing anything TO another player is refused on every
 *       world whatever this says ({@code agent.PlayerReach});</li>
 *   <li>{@link Type#CLAMP} — per-wish caps on what a wish hands HIM: power seconds, item stacks
 *       and kinds, XP levels; clamped and said, never refused ({@code WishClamp});</li>
 *   <li>the rest are the economy's rule types, declared here so a rules file can carry them and the
 *       schema checks their parameters, and ENFORCED by the part named in {@link Type#enforcedBy()}
 *       (the wish price, or the wish budget). Until then an operator's view shows them as "declared,
 *       not yet enforced", which is the truth;</li>
 *   <li>{@link Type#PLUGIN} — a type a plug-in added ({@link WorldRuleType}); {@link #pluginType} is its key, and
 *       only that plug-in knows what it means.</li>
 * </ul>
 *
 * @param id      stable, {@code [a-z0-9-]}: what the log and an operator's tools count refusals by
 * @param type    the library type
 * @param effects what it denies (empty for the types that deny nothing)
 * @param params  the type's parameters, already checked ({@link WorldRules#parse})
 * @param rule    the rule, in a child's words ("Time is the same for every island")
 * @param why     the reason, in a child's words ("so the sun rises for everyone together")
 * @param pluginType the {@link WorldRuleType#key()} of a {@link Type#PLUGIN} rule; {@code ""} for a built-in type
 */
public record WorldRule(String id, Type type, Set<WorldEffect> effects, Map<String, Object> params,
                        String rule, String why, String pluginType) {

    /** A rule of a built-in type. */
    public WorldRule(String id, Type type, Set<WorldEffect> effects, Map<String, Object> params,
                     String rule, String why) {
        this(id, type, effects, params, rule, why, "");
    }

    /** The rule library. {@code enforcedBy} is null for a type enforced now. */
    public enum Type {
        DENY_WORLD_EFFECT(null, true),
        BASE_REACH(null, true),
        ALLOW_BASE_SCOPED(null, false),
        SALVAGE_LOCK("budget", false),
        VALUE_GATE("price", false),
        PRICE_BY_SIZE("price", false),
        MILESTONE_PAYOUT("budget", false),
        CREATURE_CAP("budget", false),
        /** D18: the world's wish bank — start, refill, cap, price. Enforced now. */
        WISH_BANK(null, false),
        /**
         * may another player be a place to go ({@code mode: open}) or not ({@code off})?
         * Denies no effect: a subject is refused everywhere, hard-coded ({@code agent.PlayerReach}).
         */
        PLAYER_REACH(null, false),
        /**
         * "short boosts, modest gifts" in code. A power on a player lasts at most {@code powerSeconds}
         * (never forever); a wish gives at most {@code stacksPerKind} stacks of one item and {@code kindsPerWish}
         * kinds; at most {@code xpLevels} levels of XP. CLAMPS — shortens or shrinks and says so — rather than
         * refusing ({@code world.WishClamp}). Denies no effect.
         */
        CLAMP(null, false),
        /**
         * A type a plug-in added to the library ({@link WorldRuleType}). Never written in a file: the file names
         * the plug-in type's own key, kept in {@link WorldRule#pluginType()}. Denies no effect.
         */
        PLUGIN(null, false);

        private final String enforcedBy;
        private final boolean denies;

        Type(String enforcedBy, boolean denies) {
            this.enforcedBy = enforcedBy;
            this.denies = denies;
        }

        /** The part that will enforce it ({@code price} or {@code budget}), or null when it is enforced now. */
        public String enforcedBy() {
            return enforcedBy;
        }

        /** Does this type deny effects (and so need a message)? */
        public boolean denies() {
            return denies;
        }

        /** The file's spelling: {@code deny_world_effect}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The built-in type a file spells this way, or null. {@link #PLUGIN} has no spelling of its own. */
        public static Type ofKey(String k) {
            for (Type t : values()) {
                if (t != PLUGIN && t.key().equals(k)) {
                    return t;
                }
            }
            return null;
        }
    }

    public WorldRule {
        effects = Set.copyOf(effects);
        params = Map.copyOf(params);
        pluginType = pluginType == null ? "" : pluginType;
    }

    /** The type as the file spells it: a built-in type's key, or the plug-in type's. */
    public String typeKey() {
        return type == Type.PLUGIN ? pluginType : type.key();
    }

    /** Is this rule doing anything today? */
    public boolean enforced() {
        return type.enforcedBy() == null;
    }

    /** A whole-number parameter, or {@code dflt} when absent. */
    public int integer(String key, int dflt) {
        Object o = params.get(key);
        return o instanceof Integer i ? i : dflt;
    }

    /** A parameter as a list of strings (empty when absent). */
    @SuppressWarnings("unchecked")
    public List<String> strings(String key) {
        Object o = params.get(key);
        return o instanceof List<?> l ? (List<String>) l : List.of();
    }
}
