package com.moddingfromamod.world;

/**
 * <b>Which of this world's rules refused a wish's step</b>, carried as data from where the rule is judged (the reach
 * rule at {@code McpProtocol}'s {@code tools/call}, or at a replay in {@code WishExecutor.runOne}) to where a make with no
 * agent tells the child how it ended ({@code BuilderMake}, a served wish's job). The Magician's agent reads the
 * refusal's words and explains them itself; a Builder, Apprentice or replayed make has nobody to read them, so without
 * this the child heard only that it "didn't work". Pure.
 *
 * @param rule     the rule in the world's rules file that denies the effect: its {@code message.rule} and
 *                 {@code message.why} are what the child reads ({@link WorldRulesWords#madeRefused})
 * @param effect   the effect the step would have had
 * @param workshop where a denied wish works ({@link WorldRules#workshop})
 * @param noun     what a player's own place is called on this world ("island", "base")
 */
public record WorldRefusal(WorldRule rule, WorldEffect effect, String workshop, String noun) {

    public WorldRefusal {
        workshop = workshop == null || workshop.isBlank() ? WorldRules.ALLOW_ALL.workshop() : workshop;
        noun = noun == null || noun.isBlank() ? "base" : noun;
    }

    /**
     * The rule that denies {@code effect} here, or null when no rule of this world's does (a refusal that is no
     * rule's: a tool with no decision, a child with no base yet). {@code rules} null is {@link WorldRules#CLOSED}, as the
     * reach rule reads it.
     */
    public static WorldRefusal of(WorldEffect effect, WorldRules rules, String noun) {
        if (effect == null) {
            return null;
        }
        WorldRules in = rules == null ? WorldRules.CLOSED : rules;
        WorldRule rule = in.ruleFor(effect);
        return rule == null ? null : new WorldRefusal(rule, effect, in.workshop(), noun);
    }

    /**
     * Is it about WHERE the wish was aimed, rather than what it would change? A place effect is only ever a
     * {@code base_reach} rule's ({@link WorldRules#parse} refuses one on any other type).
     */
    public boolean aboutPlace() {
        return effect.isPlace();
    }
}
