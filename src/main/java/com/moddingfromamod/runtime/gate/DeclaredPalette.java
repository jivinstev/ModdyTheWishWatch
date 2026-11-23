package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptCritique;

/**
 * <b>Did the creature come out the way the agent said it would?</b>
 *
 * <p>The reference-free graders can only check what the <i>child</i> stated, and for a named
 * character the child states almost nothing: "make Spikor, king of the Kroaks, who breathes fire"
 * names no colour, no count and no size. Run against a real Spikor sculpt, {@link NamedProperties}
 * correctly reported "nothing countable" — which leaves the entire named tier, the tier every
 * quality complaint has come from, ungraded.
 *
 * <p>This closes that hole without a reference database and without taste. The agent declares the
 * proportions it is aiming for <i>before</i> it sculpts; afterwards the build is compared against
 * that declaration. It converts a question nobody can answer automatically — "is this really
 * Spikor?" — into one that is purely arithmetic: <b>"did you build what you said you would?"</b>
 *
 * <p>Three properties make this worth more than it looks:
 * <ul>
 *   <li><b>It needs no web access.</b> The agent has none, so every claim it makes about a real
 *       character is recall. Recall may be wrong, but a declaration that is <i>followed</i> is
 *       still strictly better than one silently abandoned halfway through the sculpt.</li>
 *   <li><b>It is intent-aware by construction.</b> The standard is the agent's own stated plan, so
 *       it cannot punish a deliberate choice the way a fixed reference would.</li>
 *   <li><b>It catches drift.</b> Six consecutive Spikor revisions each moved further from the
 *       character because every one iterated on the last and none compared back to the plan.</li>
 * </ul>
 *
 * <p>Pure and Minecraft-free, so it is unit-tested without a game.
 */
public final class DeclaredPalette {

    private DeclaredPalette() {
    }

    /**
     * How far a colour may miss its declared share before it is worth mentioning.
     *
     * <p>Deliberately loose. Sculpting in boxes cannot hit a percentage exactly, and a grader that
     * fires on every honest build is one that gets switched off. This is set to catch the failure
     * that actually happened — a colour declared at 45% arriving at 12%, or vanishing — not to
     * police the difference between 45% and 38%.
     */
    public static final double TOLERANCE = 0.20;

    /** Below this share, a colour is effectively not on the creature. */
    public static final double ABSENT = 0.03;

    /**
     * Only a colour declared as a real PRESENCE has to actually show up.
     *
     * <p>Without this the presence rule punishes correct work: a 1% accent that lands at 0.8% was
     * marked MISSED, which is absurd — it is a tiny detail, built as a tiny detail. What matters is
     * a colour promised as a significant part of the creature that then vanishes, which is the
     * Spikor drift. Accents are judged on tolerance alone.
     */
    public static final double MUST_APPEAR_ABOVE = 0.10;

    /** One declared colour and how it actually turned out. */
    public record Outcome(String colour, double declared, double actual, boolean met) {

        public String describe() {
            return String.format("%s: said %.0f%%, built %.0f%%%s", colour, declared * 100,
                    actual * 100, met ? "" : "  <-- MISSED");
        }
    }

    /** The whole comparison. Empty when the agent declared nothing. */
    public record Report(List<Outcome> outcomes) {

        public boolean isEmpty() {
            return outcomes.isEmpty();
        }

        public List<Outcome> missed() {
            return outcomes.stream().filter(o -> !o.met()).toList();
        }

        public boolean allMet() {
            return missed().isEmpty();
        }

        /** What the agent is shown after sculpting — the mirror, in its own terms. */
        public String describe() {
            if (isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("Against the palette you declared:\n");
            for (Outcome o : outcomes) {
                sb.append("  ").append(o.describe()).append('\n');
            }
            if (!allMet()) {
                sb.append("Fix the ones marked MISSED by re-sculpting the same id, or say plainly "
                        + "why you changed your mind — do not just carry on, because the next "
                        + "revision will build on the drift rather than on the character.\n");
            }
            return sb.toString();
        }

        /**
         * The same report, but <b>naming the boxes to repaint</b>.
         *
         * <p>Measured across a 21-creature run, four creatures were told their colours had missed
         * and none of them corrected it — including a gorilla declared black 70% / grey 12% that
         * came out grey 88% / black 11%, a wholesale inversion of its own plan rather than a
         * rounding error. The leading explanation was that the checker was crying wolf (with the
         * grader bugs of the day it flagged ~47% of declarations; corrected, it flags ~9%), and
         * that has since been fixed.
         *
         * <p>But the feedback was also <b>informational where it needed to be directive</b>.
         * "black: said 70%, built 11%" is a true sentence that does not tell you what to do:
         * re-deriving which of forty boxes to repaint, from a percentage, is work the agent has to
         * redo from scratch and can get wrong. This says which parts are carrying the colour that
         * took its place, largest first — the actual edit.
         */
        public String describe(List<CreatureSculpt.Part> parts) {
            String base = describe();
            if (base.isEmpty() || allMet() || parts == null || parts.isEmpty()) {
                return base;
            }
            StringBuilder sb = new StringBuilder(base);
            Map<String, Double> actual = SculptCritique.palette(parts);
            for (Outcome o : missed()) {
                if (o.actual() >= o.declared()) {
                    continue;               // over-built: nothing to repaint TO, only away from
                }
                String took = tookItsPlace(actual, outcomes);
                if (took == null) {
                    continue;
                }
                List<String> names = biggestPartsOfColour(parts, took, 4);
                if (names.isEmpty()) {
                    continue;
                }
                sb.append("  -> ").append(took).append(" took ").append(o.colour())
                        .append("'s place. The biggest ").append(took)
                        .append(" parts are ").append(String.join(", ", names))
                        .append(" — repaint the ones that should be ").append(o.colour())
                        .append(".\n");
            }
            return sb.toString();
        }

        /** Whichever built colour most exceeds what was declared for it. */
        private static String tookItsPlace(Map<String, Double> actual, List<Outcome> outcomes) {
            Map<String, Double> declaredBy = new LinkedHashMap<>();
            outcomes.forEach(o -> declaredBy.put(o.colour(), o.declared()));
            String worst = null;
            double excess = 0.05;           // ignore a colour that is merely a little generous
            for (var e : actual.entrySet()) {
                double over = e.getValue() - declaredBy.getOrDefault(e.getKey(), 0.0);
                if (over > excess) {
                    excess = over;
                    worst = e.getKey();
                }
            }
            return worst;
        }

        /** The parts wearing that colour, biggest first — the ones worth repainting. */
        private static List<String> biggestPartsOfColour(List<CreatureSculpt.Part> parts,
                                                        String colour, int limit) {
            record Sized(String name, double volume) { }
            List<Sized> hits = new ArrayList<>();
            for (CreatureSculpt.Part part : parts) {
                if (colour.equals(SculptCritique.bucket(part.colour()))) {
                    hits.add(new Sized(part.name(),
                            Math.max(1, part.width()) * Math.max(1, part.height())
                                    * Math.max(1, part.depth())));
                }
            }
            return hits.stream()
                    .sorted((a, b) -> Double.compare(b.volume(), a.volume()))
                    .limit(limit)
                    .map(Sized::name)
                    .toList();
        }
    }

    /**
     * Parse the {@code palette} argument. Tolerant on purpose: an agent writing {@code 45},
     * {@code "45"} or {@code "45%"} all mean the same thing, and a declaration thrown away over
     * punctuation would silently disable the whole check.
     */
    public static Map<String, Double> parse(JsonElement raw) {
        Map<String, Double> declared = new LinkedHashMap<>();
        if (raw == null || !raw.isJsonArray()) {
            return declared;
        }
        for (JsonElement el : raw.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("colour") && !o.has("color")) {
                continue;
            }
            String colour = (o.has("colour") ? o.get("colour") : o.get("color"))
                    .getAsString().toLowerCase(Locale.ROOT).trim();
            double pct = 0;
            JsonElement p = o.has("percent") ? o.get("percent")
                    : o.has("pct") ? o.get("pct") : null;
            if (p != null) {
                try {
                    pct = Double.parseDouble(p.getAsString().replace("%", "").trim());
                } catch (RuntimeException ignored) {
                    continue;
                }
            }
            // "45" and "0.45" both mean 45% -- but ">= 1" is the boundary, not "> 1".
            // With "> 1.0" a declared percent of 1 stayed 1.0, i.e. ONE HUNDRED PERCENT, and the
            // report showed real declarations as "said 100%, built 0% MISSED". The schema asks
            // for 0-100, so anything at or above 1 is a percentage and only a true fraction is
            // below it.
            if (pct >= 1.0) {
                pct = pct / 100.0;
            }
            if (!colour.isEmpty() && pct > 0) {
                declared.merge(bucket(colour), pct, Double::sum);
            }
        }
        return declared;
    }

    /** Compare a declaration against what was actually built. */
    public static Report compare(Map<String, Double> declared, List<CreatureSculpt.Part> parts) {
        if (declared == null || declared.isEmpty() || parts == null || parts.isEmpty()) {
            return new Report(List.of());
        }
        // composition, not palette: palette leaves out anything under 4%, which graded a colour
        // built at 3.5% as vanished.
        Map<String, Double> actual = SculptCritique.composition(parts);
        List<Outcome> outcomes = new ArrayList<>();
        for (var e : declared.entrySet()) {
            double got = actual.getOrDefault(e.getKey(), 0.0);
            boolean vanished = e.getValue() >= MUST_APPEAR_ABOVE && got < ABSENT;
            boolean met = !vanished && Math.abs(got - e.getValue()) <= TOLERANCE;
            outcomes.add(new Outcome(e.getKey(), e.getValue(), got, met));
        }
        return new Report(List.copyOf(outcomes));
    }

    /** Convenience: parse and compare in one step. */
    public static Report check(JsonObject args, List<CreatureSculpt.Part> parts) {
        return compare(parse(args == null ? null : args.get("palette")), parts);
    }

    /**
     * Map a colour word onto the buckets {@link SculptCritique} reports, so a declaration of
     * "brown" is compared against the bucket brown actually lands in rather than scoring zero
     * against a bucket that does not exist.
     */
    static String bucket(String word) {
        // ONE SPELLING, so "dark_gray", "dark-gray" and "dark gray" are one word.
        String colour = word.toLowerCase(Locale.ROOT).trim().replace('_', ' ').replace('-', ' ')
                .replaceAll("\\s+", " ");
        // A COLOUR THE PARTS CAN BE PAINTED IN is judged by the colour it is painted, so the
        // declaration and the build can never disagree about a word the engine itself defines.
        // They did: a part painted "black" is #404040, which the critique sees as grey,
        // so every creature declared black and painted black was told black had vanished — and
        // "gold" was yellow when declared but tan/orange when built. 16 of 89 first-sculpt
        // MISSED flags in the archive were the check disagreeing with itself.
        String named = paintedBucket(colour);
        if (named != null) {
            return named;
        }
        // "dark grey", "light blue", "bright red": the shade is the agent's, the colour is the word.
        String[] w = colour.split(" ", 2);
        if (w.length == 2 && SHADES.contains(w[0])) {
            String base = bucket(w[1]);
            return "dark".equals(w[0]) && "white".equals(base) ? "grey" : base;
        }
        return alias(colour);
    }

    /** Words that change how light a colour is, never which colour it is. */
    private static final java.util.Set<String> SHADES = java.util.Set.of(
            "dark", "light", "bright", "pale", "deep", "neon", "pastel", "soft", "dull", "dusty");

    /** The bucket of a colour NAME a part may be painted in, or null when the word is not one. */
    private static String paintedBucket(String colour) {
        String key = colour.replace(' ', '_');
        return com.moddingfromamod.species.Species.colourNames().contains(key)
                ? SculptCritique.bucket(com.moddingfromamod.species.Species.parseColour(key))
                : null;
    }

    private static String alias(String colour) {
        // A HEX declaration is still a colour. The agent frequently declares exact hexes
        // ("#6e8b4a"), and with words-only matching every one of them scored 0% built -- Grumbnar
        // "missed" all six of its declared colours while being visibly green, tan and red.
        // SculptCritique already knows how to bucket an RGB value; use it rather than inventing a
        // second, divergent answer.
        if (colour.startsWith("#") && (colour.length() == 7 || colour.length() == 4)) {
            try {
                String h = colour.substring(1);
                if (h.length() == 3) {   // #abc -> #aabbcc
                    h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1)
                            + h.charAt(2) + h.charAt(2);
                }
                return SculptCritique.bucket(Integer.parseInt(h, 16));
            } catch (RuntimeException ignored) {
                return colour;
            }
        }
        return switch (colour) {
            case "orange", "tan", "brown", "skin", "beige", "cream", "sand", "peach", "copper",
                 "bronze" -> "tan/orange";
            case "pink", "purple", "violet", "magenta", "lavender", "indigo" -> "purple/pink";
            case "gray", "silver", "slate", "charcoal" -> "grey";
            case "lime", "olive", "emerald", "mint", "forest" -> "green";
            case "gold", "amber", "mustard" -> "yellow";
            case "navy", "azure", "sky" -> "blue";
            case "teal", "turquoise", "aqua" -> "cyan";
            case "maroon", "crimson", "scarlet" -> "red";
            case "ivory", "bone" -> "white";
            default -> colour;
        };
    }

    /** True when the agent bothered to declare anything at all. */
    public static boolean declared(JsonObject args) {
        return args != null && args.has("palette") && args.get("palette").isJsonArray()
                && !args.get("palette").getAsJsonArray().isEmpty();
    }

    /** The empty declaration, for callers that want a stable value rather than a null. */
    public static JsonArray none() {
        return new JsonArray();
    }
}
