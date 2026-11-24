package com.moddingfromamod.test.contract;

import com.moddingfromamod.wishbuilder.MakeFacts;

/**
 * <b>The contract test world</b> (#63): a tiny stand-in for a world jar, built ONLY from what a world jar may use (the
 * documented hooks and {@link MakeFacts}), so a core change that would break a world jar goes red in the core's own CI.
 * It names no Builder id, no ready-made and no catalogue entry: it decides by the facts alone, so a core upgrade that
 * adds Builder content needs no change here.
 *
 * <p>The core rates nothing. This world makes its OWN rating from the facts ({@link #rating}, a toy formula of its own
 * that no other world need share), and copes with a number it is not given: absent is unknown, read as nothing here.
 * Its rules: a {@link MakeFacts#HOSTILE hostile} make is not for this world; a make it rates {@link #LOCKED_FROM} or
 * more waits until the player has earned it; anything else, including a tag, number or kind it has never heard of, is
 * allowed. A make whose facts could not be read is locked too: this world does not let through what it cannot see.
 *
 * <p>Test code: under {@code test/}, never in the jar.
 */
public final class ContractWorld {

    /** What this world says about one make. */
    public enum Verdict { ALLOWED, HIDDEN, LOCKED }

    /** From this rating up, a make is locked here. */
    public static final int LOCKED_FROM = 4;

    private ContractWorld() {
    }

    /**
     * This world's own rating (the toy formula the docs' example shows): a point for fighting, one for changing blocks,
     * two for a world rule, one each for a big creature (scale 1.6+), a tough one (health 60+), a strong one (damage 8+),
     * a crowd (5+ at once), and the Builder's top strength.
     */
    public static int rating(MakeFacts f) {
        int r = 0;
        r += f.has(MakeFacts.FIGHTS) ? 1 : 0;
        r += f.has(MakeFacts.CHANGES_BLOCKS) ? 1 : 0;
        r += f.has(MakeFacts.WORLD_RULE) ? 2 : 0;
        r += f.number(MakeFacts.SCALE_TIMES).orElse(1) >= 1.6 ? 1 : 0;
        r += f.number(MakeFacts.HEALTH_HALF_HEARTS).orElse(0) >= 60 ? 1 : 0;
        r += f.number(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS).orElse(0) >= 8 ? 1 : 0;
        r += f.number(MakeFacts.COUNT_MADE).orElse(1) >= 5 ? 1 : 0;
        r += f.number(MakeFacts.BUILDER_STRENGTH_STEP).orElse(1) >= 3 ? 1 : 0;
        return r;
    }

    /** This world's rule, by the facts alone. */
    public static Verdict verdict(MakeFacts facts) {
        if (facts.has(MakeFacts.HOSTILE)) {
            return Verdict.HIDDEN;
        }
        return !facts.known() || rating(facts) >= LOCKED_FROM ? Verdict.LOCKED : Verdict.ALLOWED;
    }
}
