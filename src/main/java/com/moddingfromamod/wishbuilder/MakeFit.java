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
        m.put(MakeFacts.HEALTH_HALF_HEARTS, new Fact(MakeFacts.HEALTH_HALF_HEARTS, false, NOTHING));
        m.put(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, new Fact(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, false, NOTHING));
        m.put(MakeFacts.MOVEMENT_SPEED_ATTRIBUTE, new Fact(MakeFacts.MOVEMENT_SPEED_ATTRIBUTE, false, NOTHING));
        m.put(MakeFacts.SCALE_TIMES, new Fact(MakeFacts.SCALE_TIMES, true, MakeFit::smaller));
        m.put(MakeFacts.BUILDER_SIZE_STEP, new Fact(MakeFacts.BUILDER_SIZE_STEP, true, MakeFit::smaller));
        m.put(MakeFacts.BUILDER_STRENGTH_STEP, new Fact(MakeFacts.BUILDER_STRENGTH_STEP, true, MakeFit::gentler));
        m.put(MakeFacts.COUNT_MADE, new Fact(MakeFacts.COUNT_MADE, true, MakeFit::fewer));
        m.put(MakeFacts.AMOUNT_ITEMS, new Fact(MakeFacts.AMOUNT_ITEMS, true, MakeFit::fewerItems));
        m.put(MakeFacts.RADIUS_BLOCKS, new Fact(MakeFacts.RADIUS_BLOCKS, true, MakeFit::gentler));
        m.put(MakeFacts.DURATION_TICKS, new Fact(MakeFacts.DURATION_TICKS, true, MakeFit::gentler));
        m.put(MakeFacts.EXTENT_BLOCKS, new Fact(MakeFacts.EXTENT_BLOCKS, false, NOTHING));
        return Collections.unmodifiableMap(m);
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
        return satisfies(f, limits) ? new Fitted(s, true, true, lowered) : new Fitted(spec, false, false, List.of());
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
            return out;
        }
        for (int st = now - 1; st >= 1; st--) {
            Map<String, String> o = new LinkedHashMap<>(s.opts());
            o.put("strength", String.valueOf(st));
            out.add(with(s, s.size(), s.count(), o));
        }
        return out;
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
            List<String> over = over(f, limits);
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
