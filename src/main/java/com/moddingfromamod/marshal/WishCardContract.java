package com.moddingfromamod.marshal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>One skeleton for every wish class; a fixed contract for what fills each zone per class.</b>
 * See {@code docs/WISH_CARD.md}.
 *
 * <p>Six zones, same order, every class — so the card is learnable once and he always knows where
 * the hit points are — while a castle and a fire-breath spell each say the thing that actually
 * matters about them.
 *
 * <p>This class is the <b>contract</b>, not the drawing. It is pure, because a card is a projection
 * from a stored record, and because the rules below are the ones that rot silently if they live only
 * in a screen:
 *
 * <ul>
 *   <li><b>Every wish class has a row, or the build fails.</b> The same rule
 *       {@code StressRegistry.unclaimed()} already enforces for corpus cases, for the same reason: a
 *       class with no row ships a <i>blank card</i> while every other test is green.</li>
 *   <li><b>Every class fills all four vitals slots.</b> If a class genuinely has no fourth number it
 *       shows a derived one — a hole in a fixed grid reads as a bug, not as an absence.</li>
 *   <li><b>A vital is never invented.</b> An absent figure renders {@link #ABSENT}, and the tier
 *       badge falls back to what it does have. A confidently wrong stats sheet is worse than an
 *       incomplete one.</li>
 *   <li><b>The unit is always spelled out.</b> "7 blocks tall", never the raw {@code scale} of 2.2.
 *       No child has an intuition for an internal number.</li>
 * </ul>
 */
public final class WishCardContract {

    /** What an absent figure renders as. Never a zero, and never a guess. */
    public static final String ABSENT = "—";

    /** Exactly four, always in the same four positions. */
    public static final int VITALS = 4;

    /** One figure on the card: its label, its icon, and what it is measured in. */
    public record Vital(String icon, String label, String unit) {
    }

    /**
     * The row for one wish class.
     *
     * @param glyph      the kind badge, and the last-resort hero image
     * @param vitals     exactly four, in fixed order
     * @param tierMeans  what "how big a deal is this?" is computed from, in words
     * @param powers     what the chips are drawn from
     */
    public record Row(String kind, String glyph, List<Vital> vitals, String tierMeans,
                      String powers) {

        public Row {
            vitals = List.copyOf(vitals);
        }
    }

    /**
     * The tiers, and what the badge says.
     *
     * <p>The badge answers <i>"how big a deal is this?"</i> at a glance: a seven-year-old reads
     * ⭐ LEGENDARY instantly and cannot compare 300 against 12. A mod repo already proved the
     * approach with {@code ArenaStrength}. It is also the one card element justified by a guess
     * rather than by data, and {@code docs/WISH_CARD.md} flags it for an A/B in the read-aloud test.
     */
    public enum Tier {
        COMMON("🟫 COMMON"), RARE("🟩 RARE"), EPIC("🟪 EPIC"), LEGENDARY("⭐ LEGENDARY");

        private final String badge;

        Tier(String badge) {
            this.badge = badge;
        }

        public String badge() {
            return badge;
        }
    }

    private static final Map<String, Row> ROWS = new LinkedHashMap<>();

    static {
        // The kinds are ProvenanceContract.PUBLISHING_KINDS — deliberately the same list, because a
        // thing worth publishing is a thing he can tap.
        row(new Row("species", "🐉", List.of(
                new Vital("❤", "health", "hearts"),
                new Vital("⚔", "damage", "hearts a hit"),
                new Vital("📏", "height", "blocks tall"),
                new Vital("👟", "speed", "how fast")),
                "strength — health × damage",
                "powers[] + abilities[] + linked effect packs"));

        row(new Row("build", "🏰", List.of(
                new Vital("🧱", "blocks", "blocks"),
                new Vital("📐", "footprint", "wide × deep"),
                new Vital("📏", "height", "blocks tall"),
                new Vital("🎨", "palette", "colours")),
                "size — how many blocks",
                "landmark shapes, hollow?, which world"));

        // PAINTINGS. A picture on a surface is a thing he made and can point at, so it gets
        // a card like everything else. The vitals are what a child would actually say about a
        // picture — what it is OF, how big it hangs, and how much detail it really has — rather
        // than how it is stored. "pixels" is there on purpose: it is the whole reason the feature
        // exists, and a card that said "9" would be telling him something true and important.
        row(new Row("painting", "🎨", List.of(
                new Vital("🖼", "of", "what it shows"),
                new Vital("📐", "size", "blocks across"),
                new Vital("🔍", "detail", "pixels"),
                new Vital("🎨", "colours", "colours")),
                "detail — how many pixels the picture really has",
                "what it is a picture OF, and what it is painted on"));

        // EVENTS. What a child says about a wave or a meteor: what it is, how big, when it
        // comes, and whether the world puts itself back afterwards.
        row(new Row("event", "🌊", List.of(
                new Vital("🌋", "what happens", "motion"),
                new Vital("📏", "how big", "size"),
                new Vital("⏰", "when", "days"),
                new Vital("↩", "afterwards", "restore or stay")),
                "size — how many blocks it may touch",
                "what moves, what it is made of, and what follows"));

        row(new Row("datapack", "✨", List.of(
                new Vital("⏱", "how often", "seconds"),
                new Vital("💥", "how hard", "hearts"),
                new Vital("🎯", "who it hits", "target"),
                new Vital("📡", "what sets it off", "trigger")),
                "intensity — damage ÷ how often",
                "trigger, effect and target, in his words"));

        // ITEMS. Missing until now, and the omission was invisible from the server: every
        // server-side test passed while a child's javelin simply was not in his watch, because the
        // watch only ever swept the species, build (and the since-removed machine and train)
        // registries. A thing he
        // can hold is the most obviously "his" thing there is.
        row(new Row("item", "🗡", List.of(
                new Vital("✋", "what it is", "kind of thing"),
                new Vital("💥", "what it does", "its power"),
                new Vital("🎨", "colour", "colour"),
                new Vital("🔁", "made", "times")),
                "power — what it does when you use it",
                "the effect it carries, and what it is made from"));

        row(new Row("template", "🪄", List.of(
                new Vital("🔁", "used", "times"),
                new Vital("⚡", "instant", "no waiting"),
                new Vital("🧩", "steps", "things it does"),
                new Vital("📝", "wish", "what to say")),
                "how often he presses it",
                "the commands it runs, in his words"));
    }

    private static void row(Row r) {
        ROWS.put(r.kind(), r);
    }

    private WishCardContract() {
    }

    /** The row for a class, or null — which is what the gate fails on. */
    public static Row rowFor(String kind) {
        return kind == null ? null : ROWS.get(kind.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static List<String> kinds() {
        return List.copyOf(ROWS.keySet());
    }

    /**
     * Render one figure for the card.
     *
     * <p>The whole reason this is a function rather than string concatenation at the draw site: it
     * is where "never invent a vital" and "always spell out the unit" are enforced together, so a
     * new zone cannot quietly do either wrong.
     */
    public static String render(Vital vital, Double value) {
        if (vital == null) {
            return ABSENT;
        }
        if (value == null) {
            return vital.icon() + " " + ABSENT;
        }
        String number = value == Math.rint(value)
                ? String.valueOf((long) (double) value)
                : String.format("%.1f", value);
        return vital.icon() + " " + number + " " + vital.unit();
    }

    /**
     * "You've wished about this N times", from the stored history.
     *
     * <p>{@code 1 + refinements.length}, and an <b>empty history renders as a first wish</b> rather
     * than as an empty list — a card that says "0 times" about a thing he is looking at is simply
     * wrong, and a blank space where a number belongs reads as a bug.
     */
    public static String timesWishedAbout(int refinements) {
        int n = 1 + Math.max(0, refinements);
        return n == 1 ? "you wished for this once" : "you've wished about this " + n + " times";
    }

    /**
     * The tier badge, from whatever figures the artifact actually has.
     *
     * <p><b>Falls back rather than guessing.</b> With no score at all it is COMMON, which is honest;
     * inventing a LEGENDARY from a missing number is the confidently-wrong stats sheet the contract
     * forbids. Thresholds are deliberately spread so that a real Spikor (300 hp × 12 damage) and a
     * real castle (~160k blocks) do not land in the same bucket as everything else.
     */
    public static Tier tierFor(Double score) {
        if (score == null || score <= 0) {
            return Tier.COMMON;
        }
        if (score >= 3000) {
            return Tier.LEGENDARY;
        }
        if (score >= 400) {
            return Tier.EPIC;
        }
        if (score >= 60) {
            return Tier.RARE;
        }
        return Tier.COMMON;
    }

    /** A creature's score: armour-adjusted effective health × damage, the {@code ArenaStrength} shape. */
    public static Double creatureScore(Double health, Double damage) {
        if (health == null || damage == null) {
            return null;
        }
        return health * damage;
    }

    /** A building's score is simply how big it is. */
    public static Double buildScore(Integer blocks) {
        return blocks == null ? null : (double) blocks;
    }

    /** {@code scale} is an internal number; this is what a child is shown instead. */
    public static Double blocksTall(Double modelUnitsTall, Double scale) {
        if (modelUnitsTall == null || scale == null) {
            return null;
        }
        return modelUnitsTall * scale / 16.0;
    }
}
