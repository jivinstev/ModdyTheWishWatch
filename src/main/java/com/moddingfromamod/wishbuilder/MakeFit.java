package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * <b>A make fitted to a world's limits</b> (#14): the inverse of {@link MakeFacts}'s numbers. Each number is declared
 * HERE once, beside the knob that lowers it ({@link #FACTS}): its derivation is always {@link MakeFacts#of}'s own (the
 * one function that reads a make), and its clamp only proposes the next smaller value of the knob and keeps the first
 * one whose DERIVED number fits. So the two cannot drift: a clamp is never trusted, it is measured.
 *
 * <p>A number no knob lowers (a stored creature's health, a building's extent) is not clampable: a make over that limit
 * does not fit, and the core locks it in its own words, never a silent wrong clamp. A key that is not a
 * {@link MakeFacts#NUMBERS} name is ignored; a limit on a number the make does not carry does nothing.
 *
 * <p>{@link BuilderAllowlist#capped(BuilderSpec, Map)} is the one entry: "the spec as THIS world makes it", given limits.
 * Pure: {@code MakeFitTest} fits every ready-made and tree leaf to a spread of limits and checks the fitted spec's facts
 * satisfy them.
 */
public final class MakeFit {

    private MakeFit() {
    }

    /**
     * One number and how a spec is lowered to fit it: {@code lower} gives the specs to try, each one step smaller in
     * the knob than the last, nearest first; empty for a number no knob lowers.
     */
    public record Fact(String key, boolean clampable, Function<BuilderSpec, List<BuilderSpec>> lower) {
    }

    private static final Function<BuilderSpec, List<BuilderSpec>> NOTHING = s -> List.of();

    /** Every {@link MakeFacts#NUMBERS} name, with its clamp. {@code MakeFitTest} fails a number left out. */
    public static final Map<String, Fact> FACTS = facts();

    private static Map<String, Fact> facts() {
        Map<String, Fact> m = new LinkedHashMap<>();
        // no knob is assumed to move health or damage one way: every size and strength step is tried, highest first
        m.put(MakeFacts.HEALTH_HALF_HEARTS, new Fact(MakeFacts.HEALTH_HALF_HEARTS, true, MakeFit::steps));
        m.put(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, new Fact(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, true, MakeFit::steps));
        m.put(MakeFacts.MOVEMENT_SPEED_ATTRIBUTE, new Fact(MakeFacts.MOVEMENT_SPEED_ATTRIBUTE, false, NOTHING));
        m.put(MakeFacts.SCALE_TIMES, new Fact(MakeFacts.SCALE_TIMES, true, MakeFit::smaller));
        m.put(MakeFacts.BUILDER_SIZE_STEP, new Fact(MakeFacts.BUILDER_SIZE_STEP, true, MakeFit::smaller));
        m.put(MakeFacts.BUILDER_STRENGTH_STEP, new Fact(MakeFacts.BUILDER_STRENGTH_STEP, true, MakeFit::gentler));
        m.put(MakeFacts.COUNT_MADE, new Fact(MakeFacts.COUNT_MADE, true, MakeFit::fewer));
        m.put(MakeFacts.AMOUNT_ITEMS, new Fact(MakeFacts.AMOUNT_ITEMS, true, MakeFit::fewerItems));
        m.put(MakeFacts.RADIUS_BLOCKS, new Fact(MakeFacts.RADIUS_BLOCKS, true, MakeFit::gentler));
        m.put(MakeFacts.DURATION_TICKS, new Fact(MakeFacts.DURATION_TICKS, true, MakeFit::gentler));
        m.put(MakeFacts.EXTENT_BLOCKS, new Fact(MakeFacts.EXTENT_BLOCKS, false, NOTHING));
        // no knob moves a creature's armour or knockback resistance: they are fitted on the live creature (LIVE)
        m.put(MakeFacts.ARMOR_POINTS, new Fact(MakeFacts.ARMOR_POINTS, true, NOTHING));
        m.put(MakeFacts.KNOCKBACK_RESISTANCE_FRACTION, new Fact(MakeFacts.KNOCKBACK_RESISTANCE_FRACTION, true, NOTHING));
        // #102: what a creature's moves do a second is read, not lowered: no knob moves it, so over a limit is locked
        for (String k : new String[] {MakeFacts.MOVE_DAMAGE_HALF_HEARTS_PER_SECOND,
                MakeFacts.EFFECTIVE_DAMAGE_HALF_HEARTS_PER_SECOND, MakeFacts.HEAL_HALF_HEARTS_PER_SECOND}) {
            m.put(k, new Fact(k, false, NOTHING));
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * The numbers a creature can still be fitted to on the LIVE entity when no step of its make lowers them: health,
     * attack damage, armour and knockback resistance, by one attribute modifier each with a fixed id, put on at spawn
     * ({@code wish.LiveFit}) and never written into a spec, a shelf or a store.
     */
    public static final java.util.Set<String> LIVE = java.util.Set.of(MakeFacts.HEALTH_HALF_HEARTS,
            MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, MakeFacts.ARMOR_POINTS, MakeFacts.KNOCKBACK_RESISTANCE_FRACTION);

    /**
     * A creature's or ride's SIZE, fitted where it stands at every spawn ({@code wish.LiveFit.drawnScale}), never kept in
     * its make: a replay of a kept make over a size limit (a hotbar fire, "Make another one") is made and stands
     * smaller, never refused, and a later, higher limit gives it its full size. A make itself is still fitted by its size
     * knob when it is made (MakeFit.fit).
     */
    public static final java.util.Set<String> LIVE_SIZE = java.util.Set.of(MakeFacts.SCALE_TIMES, MakeFacts.BUILDER_SIZE_STEP);

    /** Everything a creature or ride may carry over a limit and still be made here: {@link #LIVE} and {@link #LIVE_SIZE}. */
    static boolean fittedWhereItStands(java.util.Collection<String> over) {
        for (String k : over) {
            if (!LIVE.contains(k) && !LIVE_SIZE.contains(k)) {
                return false;
            }
        }
        return true;
    }

    /** Is this a make whose creatures are fitted live ({@link #LIVE})? */
    public static boolean livesFitted(MakeFacts f) {
        return f.kind().equals(BuilderSpec.Kind.CREATURE.id()) || f.kind().equals(BuilderSpec.Kind.RIDE.id());
    }

    /**
     * Does a made thing keep within {@code limits} here: every number within, or a creature over only in what is fitted
     * on the live entity at spawn ({@link #LIVE}) and its size, fitted where it stands ({@link #LIVE_SIZE}).
     */
    public static boolean satisfiesHere(MakeFacts facts, Map<String, Double> limits) {
        List<String> over = over(facts, limits);
        return over.isEmpty() || livesFitted(facts) && fittedWhereItStands(over);
    }

    /** What fitting did: the spec as made, whether it changed, whether it fits at all, and which numbers were lowered. */
    public record Fitted(BuilderSpec spec, boolean changed, boolean fits, List<String> lowered) {
        public Fitted {
            lowered = List.copyOf(lowered);
        }
    }

    /** Do these facts keep within {@code limits}? A key not in the vocabulary, or a number the make lacks, never fails. */
    public static boolean satisfies(MakeFacts facts, Map<String, Double> limits) {
        return over(facts, limits).isEmpty();
    }

    /** The numbers of {@code facts} over their limit, in vocabulary order. */
    public static List<String> over(MakeFacts facts, Map<String, Double> limits) {
        List<String> out = new ArrayList<>();
        if (limits == null || limits.isEmpty()) {
            return out;
        }
        for (String key : MakeFacts.NUMBERS) {
            Double max = limits.get(key);
            if (max != null && facts.number(key).isPresent() && facts.number(key).getAsDouble() > max + 1e-9) {
                out.add(key);
            }
        }
        return out;
    }

    /** {@code spec} fitted to {@code limits}: each number over its limit lowered by its own knob, nearest value first. */
    public static Fitted fit(BuilderSpec spec, Map<String, Double> limits) {
        if (spec == null) {
            return new Fitted(null, false, false, List.of());
        }
        MakeFacts f = MakeFacts.of(spec);
        if (limits == null || limits.isEmpty() || satisfies(f, limits)) {
            return new Fitted(spec, false, true, List.of());
        }
        BuilderSpec s = spec;
        List<String> lowered = new ArrayList<>();
        // two passes: lowering one knob can move another number (a smaller size, a shorter reach)
        for (int pass = 0; pass < 2 && !satisfies(f, limits); pass++) {
            for (String key : over(f, limits)) {
                if (lowered.contains(key) && LIVE.contains(key) && livesFitted(f)) {
                    continue;   // already left to the live fit
                }
                Fact fact = FACTS.get(key);
                if (fact == null || !fact.clampable()) {
                    return new Fitted(spec, false, false, List.of());
                }
                BuilderSpec found = null;
                for (BuilderSpec candidate : fact.lower().apply(s)) {
                    if (!over(MakeFacts.of(candidate), Map.of(key, limits.get(key))).contains(key)) {
                        found = candidate;
                        break;
                    }
                }
                if (found == null && LIVE.contains(key) && livesFitted(f)) {
                    // the LAST step: no size or strength lowers it, so it is fitted on the live creature at spawn
                    if (!lowered.contains(key)) {
                        lowered.add(key);
                    }
                    continue;
                }
                if (found == null) {
                    return new Fitted(spec, false, false, List.of());
                }
                s = found;
                f = MakeFacts.of(s);
                if (!lowered.contains(key)) {
                    lowered.add(key);
                }
            }
        }
        return satisfiesHere(f, limits) ? new Fitted(s, !s.equals(spec) || !lowered.isEmpty(), true, lowered)
                : new Fitted(spec, false, false, List.of());
    }

    /**
     * {@code spec} with every knob a fit may lower at its lowest (the smallest size, strength 1, one at once, one item):
     * the least it can be made as. A page that compares a world's limits with this, and with the make as it is, knows
     * whether its make can be fitted without fitting it; the make itself is still fitted and checked by {@link #fit}.
     */
    public static BuilderSpec floor(BuilderSpec spec) {
        BuilderSpec s = spec;
        for (var lower : List.<Function<BuilderSpec, List<BuilderSpec>>>of(MakeFit::smaller, MakeFit::gentler,
                MakeFit::fewer, MakeFit::fewerItems)) {
            List<BuilderSpec> down = lower.apply(s);
            if (!down.isEmpty()) {
                s = down.get(down.size() - 1);
            }
        }
        return s;
    }

    /**
     * Could a make with these facts be fitted to {@code limits}, judged by its facts as it is and at its
     * {@link #floor}: every number over its limit is clampable and its floor keeps within it. A comparison, no fitting.
     */
    public static boolean fittable(MakeFacts asIs, MakeFacts floor, Map<String, Double> limits) {
        for (String key : over(asIs, limits)) {
            if (LIVE.contains(key) && livesFitted(asIs)) {
                continue;   // fitted on the live creature at spawn, whatever its steps
            }
            Fact fact = FACTS.get(key);
            if (fact == null || !fact.clampable() || floor == null || over(floor, Map.of(key, limits.get(key))).contains(key)) {
                return false;
            }
        }
        return true;
    }

    // ── the knobs ─────────────────────────────────────────────────────────────────────────────

    private static BuilderSpec with(BuilderSpec s, int size, int count, Map<String, String> opts) {
        return new BuilderSpec(s.version(), s.kind(), s.from(), s.scheme(), size, s.powers(), s.temper(), count, s.at(),
                s.name(), opts);
    }

    /** Smaller: a creature or ride by its named sizes, anything else by its 1–4 size step. */
    static List<BuilderSpec> smaller(BuilderSpec s) {
        List<BuilderSpec> out = new ArrayList<>();
        boolean creature = s.kind() == BuilderSpec.Kind.CREATURE || s.kind() == BuilderSpec.Kind.RIDE;
        if (creature) {
            BuilderMobSizes.Level now = s.opt("mobsize").isEmpty() ? BuilderMobSizes.fromOldSize(s.size())
                    : BuilderMobSizes.of(s.opt("mobsize"));
            List<BuilderMobSizes.Level> down = new ArrayList<>(BuilderMobSizes.ALL);
            down.sort((a, b) -> Double.compare(b.scale(), a.scale()));
            for (BuilderMobSizes.Level l : down) {
                if (l.scale() < now.scale()) {
                    Map<String, String> o = new LinkedHashMap<>(s.opts());
                    o.put("mobsize", l.id());
                    out.add(with(s, BuilderMobSizes.oldSize(l), s.count(), o));
                }
            }
            return out;
        }
        for (int size = s.size() - 1; size >= 1; size--) {
            out.add(with(s, size, s.count(), s.opts()));
        }
        return out;
    }

    /** Gentler: the Builder's strength step, one lower each time. */
    static List<BuilderSpec> gentler(BuilderSpec s) {
        List<BuilderSpec> out = new ArrayList<>();
        int now;
        try {
            now = Integer.parseInt(s.opt("strength").strip());
        } catch (NumberFormatException none) {
            if (!creatureLike(s)) {
                return out;
            }
            now = BuilderCreatureStrength.OWN;   // #103: a creature's own strength is written as nothing; gentler still exists
        }
        for (int st = now - 1; st >= 1; st--) {
            Map<String, String> o = new LinkedHashMap<>(s.opts());
            o.put("strength", String.valueOf(st));
            out.add(with(s, s.size(), s.count(), o));
        }
        return out;
    }

    /**
     * Every lower combination of the size and strength steps, highest first (the most size and strength kept that
     * still fits), for a number no knob is known to move one way: health, attack damage. A discrete search, never a
     * slope: each candidate is kept only when its RE-DERIVED facts fit ({@link #fit}), and when none does the make is
     * not fitted at all (locked), never made wrong.
     */
    static List<BuilderSpec> steps(BuilderSpec s) {
        if (creatureLike(s)) {
            // #103: a creature's size and How strong now move its health and damage, but a limit on those is still
            // fitted on the LIVE creature (LIVE), never by making it smaller or gentler: a world that raises its limits
            // later must get the creature back at its full strength (LiveFit). Its size and strength are lowered only
            // by a limit on them (builderSizeStep, scaleTimes, builderStrengthStep).
            return List.of();
        }
        List<BuilderSpec> tiers = new ArrayList<>();
        tiers.add(s);
        tiers.addAll(lowerTiers(s));
        List<BuilderSpec> out = new ArrayList<>();
        for (BuilderSpec tiered : tiers) {
            List<BuilderSpec> sizes = new ArrayList<>();
            sizes.add(tiered);
            sizes.addAll(smaller(tiered));
            for (BuilderSpec sized : sizes) {
                out.add(sized);
                out.addAll(gentler(sized));
            }
        }
        out.remove(0);   // the make as it is is not a candidate
        // highest first: the best material kept, then the most steps, then the bigger size
        out.sort((a, b) -> Integer.compare(stepsKept(b), stepsKept(a)));
        return out;
    }

    /**
     * An item made of a lower material ({@link BuilderItemPowers#TIERS}, a world's {@code itemTier}): the same stock item
     * and power at each lower tier that still makes it (a stone sword; never a basic mace, which there is none of).
     */
    static List<BuilderSpec> lowerTiers(BuilderSpec s) {
        List<BuilderSpec> out = new ArrayList<>();
        if (s.kind() != BuilderSpec.Kind.ITEM || BuilderItemPowers.of(s) == null) {
            return out;
        }
        String now = s.opt("tier").isEmpty() ? BuilderItemPowers.TIER_ANY : s.opt("tier");
        int at = BuilderItemPowers.TIERS.indexOf(now);
        for (int i = at + 1; i < BuilderItemPowers.TIERS.size(); i++) {
            String tier = BuilderItemPowers.TIERS.get(i);
            if (BuilderItemPowers.tiered(BuilderItemPowers.of(s), tier) == null) {
                continue;
            }
            Map<String, String> o = new LinkedHashMap<>(s.opts());
            o.put("tier", tier);
            out.add(with(s, s.size(), s.count(), o));
        }
        return out;
    }

    /** A creature or a ride: its strength is #103's How strong, its own (2) when unstated. */
    private static boolean creatureLike(BuilderSpec s) {
        return s.kind() == BuilderSpec.Kind.CREATURE || s.kind() == BuilderSpec.Kind.RIDE;
    }

    private static int stepsKept(BuilderSpec s) {
        int strength;
        try {
            strength = Integer.parseInt(s.opt("strength").strip());
        } catch (NumberFormatException none) {
            strength = creatureLike(s) ? BuilderCreatureStrength.OWN : 0;
        }
        BuilderMobSizes.Level l = s.opt("mobsize").isEmpty() ? null : BuilderMobSizes.of(s.opt("mobsize"));
        int size = l == null ? s.size() : BuilderMobSizes.ALL.indexOf(l);
        int tier = BuilderItemPowers.TIERS.size() - 1 - Math.max(0, BuilderItemPowers.TIERS.indexOf(
                s.opt("tier").isEmpty() ? BuilderItemPowers.TIER_ANY : s.opt("tier")));
        return tier * 1000 + size * 10 + strength;
    }

    /** Fewer at once. */
    static List<BuilderSpec> fewer(BuilderSpec s) {
        List<BuilderSpec> out = new ArrayList<>();
        for (int n = s.count() - 1; n >= 1; n--) {
            out.add(with(s, s.size(), n, s.opts()));
        }
        return out;
    }

    /** Fewer items in a bundle of supplies. */
    static List<BuilderSpec> fewerItems(BuilderSpec s) {
        List<BuilderSpec> out = new ArrayList<>();
        if (s.kind() != BuilderSpec.Kind.SUPPLIES) {
            return out;
        }
        for (int n = BuilderSupplies.amount(s) - 1; n >= 1; n--) {
            Map<String, String> o = new LinkedHashMap<>(s.opts());
            o.putAll(BuilderSupplies.opts(n));
            out.add(with(s, s.size(), s.count(), o));
        }
        return out;
    }

    // ── a recipe ──────────────────────────────────────────────────────────────────────────────

    /**
     * A recipe's commands fitted to {@code limits}, or null when they cannot be: the one number a replay command itself
     * carries is how many it spawns ({@code wishmob spawn <id> <n>}), so that is lowered; every other number is the
     * stored thing's own and is not clampable here. {@code facts} reads each command's thing as
     * {@link MakeFacts#ofCommands} does.
     */
    public static List<String> fitCommands(List<String> commands, Map<String, Double> limits, MakeFacts.Artifacts artifacts) {
        List<String> out = new ArrayList<>();
        for (String c : commands) {
            MakeFacts.Ref r = MakeFacts.ref(c);
            MakeFacts f = r == null ? null : MakeFacts.ofCommands(List.of(c), artifacts);
            if (f == null || satisfies(f, limits)) {
                out.add(c);
                continue;
            }
            List<String> over = new ArrayList<>(over(f, limits));
            if (livesFitted(f)) {
                over.removeAll(LIVE);        // fitted on the live creature at spawn
                over.removeAll(LIVE_SIZE);   // and its size where it stands (LiveFit.drawnScale)
            }
            if (over.isEmpty()) {
                out.add(c);
                continue;
            }
            Double most = limits.get(MakeFacts.COUNT_MADE);
            if (!over.equals(List.of(MakeFacts.COUNT_MADE)) || most == null || most < 1
                    || !c.strip().startsWith("wishmob spawn ")) {
                return null;
            }
            // `wishmob spawn <id> [count] [at <build>:<spot>]`: the count is how many in all; the rest is kept
            String[] w = c.strip().split("\\s+");
            int n = (int) Math.floor(most + 1e-9);
            StringBuilder b = new StringBuilder("wishmob spawn ").append(r.id()).append(' ').append(n);
            for (int i = w.length >= 4 && w[3].matches("\\d+") ? 4 : 3; i < w.length; i++) {
                b.append(' ').append(w[i]);
            }
            out.add(b.toString());
        }
        return out;
    }
}
