package com.moddingfromamod.species;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * How a MAGICIAN-made creature lives. The agent names its life ({@code behaves_like}) on every sculpt; this
 * settles what it named, or names one when it did not. Pure: the tool hands in what the sculpt says.
 *
 * <ul>
 *   <li><b>Named, and it fits</b>: kept as it is.</li>
 *   <li><b>A flier named a ground life</b> ({@code flies} with "wolf", "cow", "ravager"…): a flier never lives on the
 *       ground, so it gets a flying one, by the Builder's own rule ({@link BehaviourChoice#hardRules}): a mount when
 *       he rides it, a dragon when it is big and fierce, else a flitter. A life that flies itself (a dragon, a bee)
 *       brings {@code flies} with it ({@link Archetype#impliedPowers}), so the other way round is no contradiction.</li>
 *   <li><b>A sea creature named a land life</b> (fins and no legs, by {@code CarrierPool.movementOf}'s own words, and
 *       it does not fly): it swims, as the Builder's sea bodies do. A vehicle (a boat, a submarine) keeps its own.</li>
 *   <li><b>Two guardrails the issue listed are not needed</b>, 's measurement: a hostile creature with a life
 *       that runs from a fight still fights (a life's FLEE style applies only when its temper does not fight,
 *       {@code RigInstincts}); and a Magician creature has no body file to call a vehicle, so the agent's own
 *       {@code vehicle} is the only word that makes one (and brings {@code rideable}).</li>
 *   <li><b>None named, with {@code start_from}</b>: the base's life stays, as before.</li>
 *   <li><b>None named</b>: never refused. The Builder's chooser ({@link BehaviourChoice#choose}) picks one from what
 *       the sculpt says: does it fly or swim, does he ride it, its temper and its size.</li>
 * </ul>
 * The {@code note} tells the agent what happened, so it can name another; empty when it named one that fits.
 */
public final class MagicianBehaviour {

    private MagicianBehaviour() {
    }

    /** {@code key} null: leave the life alone (an inheriting sculpt that named none). */
    public record Outcome(String key, String note, boolean fallback, boolean corrected) {
    }

    /** The Builder tile a Magician creature is not: no body's own life, no pinned starter. */
    static final String FROM = "magician";

    /**
     * @param named   the resolved archetype key the agent named, or null for none
     * @param startFrom whether the sculpt inherits a base creature
     * @param powers  the sculpt's power words as sent ({@code flies}, {@code rideable}, {@code hostile}…)
     * @param base    its instinct ({@code wild}, {@code guardian}, {@code hunter}, {@code coward}, {@code companion}), or null
     * @param scale   its scale, or null
     * @param swims   whether its body swims (fins and no legs, or a sea creature's name)
     */
    public static Outcome settle(String named, boolean startFrom, Set<String> powers, String base, Double scale,
                                 boolean swims) {
        Set<String> words = new HashSet<>();
        if (powers != null) {
            for (String p : powers) {
                if (p != null) {
                    words.add(p.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        String b = base == null ? "" : base.trim().toLowerCase(Locale.ROOT);
        boolean flies = words.contains("flies");
        BehaviourChoice.Pick pick = pick(words, b, scale, swims, named);
        if (named != null && !named.isBlank()) {
            if (flies && !BehaviourChoice.FLYING.contains(named)) {
                String to = pick.ridden() ? "mount" : BehaviourChoice.hardRules(pick, named);
                return new Outcome(to, "It flies, so it cannot live like a " + named.replace('_', ' ')
                        + " on the ground: it lives like a " + to.replace('_', ' ') + " (a flying life). "
                        + "Name a flying life for a flier: 'dragon', 'bee', 'horse' for one he rides, 'vehicle' "
                        + "for one he flies in.", false, true);
            }
            if (swims && !flies && !named.equals("swimmer") && !named.equals("vehicle")) {
                return new Outcome("swimmer", "It is a sea creature (fins and no legs), so it cannot live like a "
                        + named.replace('_', ' ') + ": it swims. Name 'cod' for a sea creature, or give it legs to "
                        + "walk, 'flies' to fly, or 'vehicle' for a boat he drives.", false, true);
            }
            return new Outcome(named, "", false, false);
        }
        if (startFrom) {
            return new Outcome(null, "", false, false);
        }
        String to = BehaviourChoice.choose(pick).key();
        return new Outcome(to, "You named no behaves_like, so it lives like a " + to.replace('_', ' ')
                + ", chosen from its powers and size. Name the mob it lives like on every creature.", true, false);
    }

    /**
     * <b>"Make it fly"</b> on a creature that already lives: {@code modify_mob} added {@code flies} and named no
     * life, so the life it had (a ground one, or none, which is its carrier's) is settled again by the same rules a new
     * sculpt gets: a mount when he rides it, a dragon when it is big and fierce, else a flitter. Null when nothing needs
     * to change: it flew before, it does not fly now, or its life already flies.
     *
     * @param was         its life before the change (null for none)
     * @param flewBefore  whether it had {@code flies} before
     * @param powers      its power words after the change
     */
    public static String afterPowers(String was, boolean flewBefore, Set<String> powers, String base, Double scale,
                                     boolean swims) {
        Set<String> words = new HashSet<>();
        if (powers != null) {
            powers.forEach(w -> words.add(w.trim().toLowerCase(Locale.ROOT)));
        }
        if (flewBefore || !words.contains("flies") || (was != null && BehaviourChoice.FLYING.contains(was))) {
            return null;
        }
        String b = base == null ? "" : base.trim().toLowerCase(Locale.ROOT);
        BehaviourChoice.Pick pick = pick(words, b, scale, swims, was);
        return pick.ridden() ? "mount" : BehaviourChoice.hardRules(pick, was == null ? BehaviourChoice.choose(pick).key() : was);
    }

    static BehaviourChoice.Pick pick(Set<String> words, String base, Double scale, boolean swims, String named) {
        boolean flies = words.contains("flies");
        Set<String> powers = new HashSet<>();
        for (String w : words) {
            if (w.equals("flies") || w.equals("rideable") || w.equals("glowing") || w.equals("fireproof")
                    || w.equals("leaps") || w.equals("hops")) {
                powers.add("power:" + w);
            }
        }
        return new BehaviourChoice.Pick(FROM, null, false, swims && !flies, "vehicle".equals(named),
                words.contains("rideable"), powers, temper(words, base), size(scale), false);
    }

    /** The Builder's four tempers from a sculpt's powers and instinct. */
    static String temper(Set<String> words, String base) {
        if (words.contains("hostile") || base.equals("hunter")) {
            return "wild";
        }
        if (base.equals("guardian")) {
            return "guard";
        }
        if (base.equals("coward") || words.contains("skittish")) {
            return "shy";
        }
        return "friendly";
    }

    /** The Builder's sizes 1–4 from a scale (1.0 is as built, player-sized). */
    static int size(Double scale) {
        if (scale == null) {
            return 2;
        }
        double s = scale;
        return s < 0.75 ? 1 : s < 1.75 ? 2 : s < 3.0 ? 3 : 4;
    }
}
