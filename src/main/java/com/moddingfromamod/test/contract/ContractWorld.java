package com.moddingfromamod.test.contract;

import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.plugin.ModdyPlugin;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.wish.WishAsk;
import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.world.MakeView;
import com.moddingfromamod.world.WorldLimits;
import com.moddingfromamod.world.WorldMakes;

/**
 * <b>The contract test world</b> (#63): a tiny stand-in for a world jar, built ONLY from what a world jar may use (the
 * documented hooks, {@link WorldMakes} and {@link MakeFacts}), so a core change that would break a world jar goes red
 * in the core's own CI. It names no Builder id, no ready-made and no catalogue entry: it decides by the facts alone, so
 * a core upgrade that adds Builder content needs no change here.
 *
 * <p>The core rates nothing. This world makes its OWN rating from the facts ({@link #rating}, a toy formula of its own
 * that no other world need share), and copes with a number it is not given: absent is unknown, read as nothing here.
 * Its rules, every answer a world can give:
 * <ul>
 *   <li>a {@link MakeFacts#HOSTILE hostile} make is not for this world: {@code HIDDEN};</li>
 *   <li>a make it rates {@link #LOCKED_FROM} or more, or whose facts could not be read, waits until the player has
 *       earned it: {@code LOCKED} with {@link #LOCKED_WORDS};</li>
 *   <li>everything else is made, but at most {@link #MOST_AT_ONCE} at once, no bigger than size step
 *       {@link #BIGGEST_STEP}, no tougher than {@link #TOUGHEST} health and hitting no harder than {@link #HARDEST}:
 *       {@code FIT}, stated up front ({@link Answers#limits}, stamp {@link #STAMP}) and in each answer;</li>
 *   <li>a tag, number or kind it has never heard of is simply allowed.</li>
 * </ul>
 *
 * <p>Test code: under {@code test/}, never in the jar.
 */
public final class ContractWorld implements ModdyPlugin {

    /** What this world says about one make, as its own rule (the hook's answer is {@link Answers#view}). */
    public enum Verdict { ALLOWED, HIDDEN, LOCKED }

    /** From this rating up, a make is locked here. */
    public static final int LOCKED_FROM = 4;
    /** The lock's words, which the core sweeps before a child reads them. */
    public static final String LOCKED_WORDS = "🔒 That one unlocks after you finish the lighthouse.";
    /** The most of a thing made at once here. */
    public static final double MOST_AT_ONCE = 3;
    /** The biggest Builder size step here. */
    public static final double BIGGEST_STEP = 3;
    /** The most health a creature has here, in health points. */
    public static final double TOUGHEST = 40;
    /** The most damage a hit deals here, in health points. */
    public static final double HARDEST = 6;
    /** The limits' stamp: it would change if the limits did. */
    public static final String STAMP = "contract-v2";

    /** This world's limits, every make. */
    public static final Map<String, Double> LIMITS = Map.of(MakeFacts.COUNT_MADE, MOST_AT_ONCE,
            MakeFacts.BUILDER_SIZE_STEP, BIGGEST_STEP, MakeFacts.HEALTH_HALF_HEARTS, TOUGHEST,
            MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, HARDEST);

    public ContractWorld() {
    }

    @Override
    public String name() {
        return "contract-world";
    }

    @Override
    public void register(Plugins plugins) {
        plugins.add(WorldMakes.class, new Answers());
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

    /** The hook, as a world jar fills it. */
    public static final class Answers implements WorldMakes {
        @Override
        public MakeView view(MakeFacts facts, WishAsk ask) {
            return switch (verdict(facts)) {
                case HIDDEN -> MakeView.HIDDEN;
                case LOCKED -> MakeView.locked(LOCKED_WORDS);
                case ALLOWED -> MakeView.fit(LIMITS);
            };
        }

        @Override
        public WorldLimits limits(UUID player) {
            return new WorldLimits(LIMITS, STAMP);
        }
    }
}
