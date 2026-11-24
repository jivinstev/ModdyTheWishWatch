package com.moddingfromamod.test.contract;

import com.moddingfromamod.wishbuilder.MakeFacts;

/**
 * <b>The contract test world</b> (#63): a tiny stand-in for a world jar, built ONLY from what a world jar may use (the
 * documented hooks and {@link MakeFacts}), so a core change that would break a world jar goes red in the core's own CI.
 * It names no Builder id, no ready-made and no catalogue entry: it decides by the facts alone, the way the docs tell a
 * world to, so a core upgrade that adds Builder content needs no change here.
 *
 * <p>Its rules: a {@link MakeFacts#HOSTILE hostile} make is not for this world; a make at tier
 * {@link #LOCKED_FROM} or above waits until the player has earned it; anything else, including a tag or kind it has
 * never heard of, is allowed.
 *
 * <p>Test code: under {@code test/}, never in the jar.
 */
public final class ContractWorld {

    /** What this world says about one make. */
    public enum Verdict { ALLOWED, HIDDEN, LOCKED }

    /** From this tier up, a make is locked. */
    public static final int LOCKED_FROM = 3;

    private ContractWorld() {
    }

    /** This world's rule, by the facts alone. */
    public static Verdict verdict(MakeFacts facts) {
        if (facts.has(MakeFacts.HOSTILE)) {
            return Verdict.HIDDEN;
        }
        return facts.tier() >= LOCKED_FROM ? Verdict.LOCKED : Verdict.ALLOWED;
    }
}
