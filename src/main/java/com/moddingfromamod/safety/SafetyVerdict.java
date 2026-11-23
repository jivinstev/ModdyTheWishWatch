package com.moddingfromamod.safety;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.wish.WishText;

/**
 * <b>Everything a safety grader is allowed to say.</b>
 *
 * <p>The typed boundary of {@code docs/CHILD_SAFETY_PLAN.md} §4, and the same discipline as
 * {@link com.moddingfromamod.judge.VisionVerdict} and
 * {@link com.moddingfromamod.reference.ReferenceSpec}: enums, bounded numbers, a bounded list, and
 * exactly <b>one</b> free-text field, which is bounded, sanitised, and shown to the child verbatim
 * before it is used. A filter is a denylist and loses; a vocabulary that cannot express an
 * instruction cannot carry one.
 *
 * <h2>Four axes, one band each</h2>
 *
 * <p>Per-axis rather than one number, because the four have different actions and different false
 * positives, and a single score would average exactly the distinction this is for. The wish's band
 * is {@link #overall()}, the max.
 *
 * <p>The right-hand column of §1 — what is <i>allowed on purpose</i> — is the load-bearing half:
 * the real corpus of 84 wishes a child actually typed contains zero unsafe ones and many edgy ones
 * (killing bosses, grinding mobs into meat, a liquid made of bottoms). A grader that fires on those
 * is anti-correlated with what the child wants, so the expensive mistake here is a false positive,
 * not a false negative.
 *
 * <h2>UNAVAILABLE is not FINE</h2>
 *
 * <p>{@link #unavailable()} carries FINE bands so that nothing downstream has to special-case a
 * fifth band — but {@link #provenance()} says {@code UNAVAILABLE} and {@link #overallLabel()} says
 * so too, because "the grader could not run" and "the grader looked and it was fine" are different
 * facts and a log that conflates them cannot report an outage. What the GAME does about it is a
 * config decision ({@link ChildSafetyConfig#unavailableClosed()}), never this record's.
 *
 * @param violence   realistic gore, torture, cruelty for its own sake, violence at a real person
 * @param sexual     sexual content, nudity, adult romance — and NOT gross-out humour
 * @param hate       slurs, demeaning a group, cruelty aimed at a real person
 * @param danger     real drugs, real weapon-making, self-harm, personal information, strangers
 * @param wellbeing  real-life distress. <b>Never a block</b>: it writes a grown-up note (§1)
 * @param reasons    a closed vocabulary, at most {@link #MAX_REASONS}, so a log can be counted
 * @param redirect   the one free-text field; only meaningful at {@link Band#LIKELY}, see
 *                   {@link #redirectIfUsable()}
 * @param provenance who decided — the lexicon, the model, or nobody
 * @param stageIndex PLAN grading only: which step earned the band, or {@link #NO_STAGE}
 */
public record SafetyVerdict(Band violence, Band sexual, Band hate, Band danger,
                            boolean wellbeing, List<Reason> reasons, String redirect,
                            Provenance provenance, int stageIndex)
        implements com.moddingfromamod.agent.ActionLog.SafetyVerdictView {

    /** Four bands, ordered. {@link #overall()} is the max, so declaration order is the severity. */
    public enum Band { FINE, MAYBE, LIKELY, CLEARLY }

    /** Who produced this verdict. Never inferred later — a log that guesses cannot report an outage. */
    public enum Provenance { LEXICON, HAIKU, UNAVAILABLE }

    /**
     * Why, from a closed set.
     *
     * <p>A free-text "reason" would be a second channel out of the model and would also be
     * uncountable — and counting is the whole point of §6: "REDIRECT fired 30 times, 28 of them
     * GORE" is a sentence a lesson can be written against, and "it seemed a bit much" is not.
     */
    public enum Reason {
        GORE, TORTURE, CRUELTY, SEXUAL, NUDITY, SLUR, DEMEANING_GROUP, REAL_PERSON_CRUELTY,
        DRUGS, ALCOHOL, REAL_WEAPON, SELF_HARM, PERSONAL_INFO, STRANGER_CONTACT, DISTRESS, OTHER
    }

    /**
     * <b>The parent's dial</b> (§8). Applied to a grade, never asked of the model — a model told to
     * "be strict" moves in ways nobody can measure, while a promotion applied afterwards is one
     * arithmetic step that an A/B can turn off.
     */
    public enum Strictness { RELAXED, STANDARD, STRICT }

    /** At most four reasons. A list that can grow is a free-text field with extra steps. */
    public static final int MAX_REASONS = 4;

    /** A redirect is one sentence a child reads at a glance, not a paragraph. */
    public static final int MAX_REDIRECT = 200;

    /** {@link #stageIndex()} when no single step is responsible — including every INPUT grade. */
    public static final int NO_STAGE = -1;

    /**
     * Total and defensive, because both callers are boundaries: a model's reply and a text file an
     * operator edits.
     *
     * <p><b>A null band becomes MAYBE, not FINE.</b> Null means "nobody said", and the honest
     * reading of that leans cautious — but only as far as MAYBE, which passes the wish and merely
     * tells the builder to keep it cartoon. Defaulting to FINE would make a dropped field invisible;
     * defaulting to LIKELY would let one malformed reply redirect a child's wish.
     */
    public SafetyVerdict {
        violence = violence == null ? Band.MAYBE : violence;
        sexual = sexual == null ? Band.MAYBE : sexual;
        hate = hate == null ? Band.MAYBE : hate;
        danger = danger == null ? Band.MAYBE : danger;
        provenance = provenance == null ? Provenance.UNAVAILABLE : provenance;
        reasons = boundReasons(reasons);
        redirect = boundRedirect(redirect);
        stageIndex = stageIndex < 0 ? NO_STAGE : stageIndex;
    }

    /** Nothing objectionable, from a named source. */
    public static SafetyVerdict fine(Provenance provenance) {
        return new SafetyVerdict(Band.FINE, Band.FINE, Band.FINE, Band.FINE, false,
                List.of(), "", provenance, NO_STAGE);
    }

    /** The grader could not run: no CLI, a timeout, or a reply nothing could read. */
    public static SafetyVerdict unavailable() {
        return fine(Provenance.UNAVAILABLE);
    }

    /** The highest band across the four axes. */
    public Band overall() {
        Band max = violence;
        for (Band b : List.of(sexual, hate, danger)) {
            if (b.compareTo(max) > 0) {
                max = b;
            }
        }
        return max;
    }

    /** True when nobody graded this. Distinct from "graded and FINE" — see the class note. */
    public boolean isUnavailable() {
        return provenance == Provenance.UNAVAILABLE;
    }

    /**
     * What a log should write for {@code overall}: the band, or the word UNAVAILABLE.
     *
     * <p>§2's table has five rows and {@link Band} has four values on purpose. UNAVAILABLE is not a
     * severity — it is the absence of a measurement — so it lives here rather than as a fifth enum
     * constant that every {@code switch} would have to pretend to rank.
     */
    @Override
    public String overallLabel() {
        return isUnavailable() ? "UNAVAILABLE" : overall().name();
    }

    /**
     * The redirect, but only when it is the thing to do.
     *
     * <p>§2: a redirect exists in the LIKELY band alone. A CLEARLY wish gets no clever rewording
     * (that invites "how close can I get") and a MAYBE wish is the child's own wish and stays so.
     * Callers ask this rather than reading {@link #redirect()}, so the rule lives in one place; the
     * raw field survives for the log, because "the model proposed one and we did not use it" is
     * worth being able to count.
     *
     * <p><b>And the floor gets a veto</b> — {@link #redirectRejected()}, §12.5. Every consumer of a
     * redirect goes through this method, which is what makes one check enough for all of them.
     */
    public Optional<String> redirectIfUsable() {
        return overall() == Band.LIKELY && !redirect.isEmpty() && !redirectRejected()
                ? Optional.of(redirect) : Optional.empty();
    }

    /**
     * <b>Did L0 refuse the model's redirect?</b> (A maintainer's decision 12.5.)
     *
     * <p>The redirect is the one free-text field a MODEL writes and a CHILD reads — shown to him as
     * his own next wish, and handed to the agent as the thing to build. Everything else the model
     * says is an enum. So it goes through the same floor every other piece of text does: a
     * {@code CLEARLY} on any axis, or a wellbeing phrase, and it is <b>not a usable redirect</b>.
     *
     * <p>Both halves are deliberate. A banded hit is the obvious one. The wellbeing half is not
     * obvious and matters more: §1's rule is that a child in distress is never <i>blocked</i>, and
     * that says nothing about putting a self-harm phrasing in front of him as a suggestion. A
     * redirect is the one place this layer SPEAKS rather than judges, and "never blocked" must not
     * be read as "safe to echo".
     *
     * <p>Note what falls out of doing it here rather than at the two call sites:
     * {@link SafetyInputGate#actionOf} already turns a {@code LIKELY} with no usable redirect into
     * a {@code BLOCK} (§2's rule, because a redirect that does not exist is not an action), and
     * {@link SafetyPlanGate} already falls back to its generic alternative. So a rejected redirect
     * needs no new branch anywhere — it is simply absent, and the existing rules are correct about
     * an absent one.
     *
     * <p>Computed rather than stored, because {@link SafetyLexicon} is overridable at runtime: a
     * term added tonight must apply to a verdict parsed a moment later, and a boolean baked in at
     * construction would answer for the list as it was. It is a couple of dozen regex scans over at
     * most {@link #MAX_REDIRECT} characters, on a path that has just paid for a model round trip.
     */
    @Override
    public boolean redirectRejected() {
        if (redirect.isEmpty()) {
            return false;
        }
        return SafetyLexicon.shared().check(redirect)
                .filter(v -> v.overall() == Band.CLEARLY || v.wellbeing())
                .isPresent();
    }

    /**
     * The parent's dial, applied.
     *
     * <p>STRICT promotes MAYBE to LIKELY (so a leaning wish is redirected rather than merely
     * guided); RELAXED demotes LIKELY to MAYBE (so it is logged and guided rather than redirected).
     * <b>Neither touches CLEARLY</b> — the dial tunes the grey area, and a dial that could switch
     * off the unambiguous cases would be a different feature.
     *
     * <p>Note what falls out for free: a demoted LIKELY is no longer the overall band, so
     * {@link #redirectIfUsable()} goes empty on its own and RELAXED really is "log only".
     */
    public SafetyVerdict adjusted(Strictness strictness) {
        if (strictness == null || strictness == Strictness.STANDARD) {
            return this;
        }
        return new SafetyVerdict(shift(violence, strictness), shift(sexual, strictness),
                shift(hate, strictness), shift(danger, strictness),
                wellbeing, reasons, redirect, provenance, stageIndex);
    }

    private static Band shift(Band b, Strictness s) {
        if (s == Strictness.STRICT && b == Band.MAYBE) {
            return Band.LIKELY;
        }
        if (s == Strictness.RELAXED && b == Band.LIKELY) {
            return Band.MAYBE;
        }
        return b;
    }

    /**
     * Read a grader's structured output. <b>Total</b>: nothing here throws, and every field
     * degrades on its own.
     *
     * <p>The degradations are deliberately not uniform, because the failures are not:
     * <ul>
     *   <li><b>nothing usable at all</b> — {@link #unavailable()}. A reply nobody can read is
     *       indistinguishable from a grader that never ran, and saying "FINE" about it would be a
     *       claim nobody made.</li>
     *   <li><b>an unknown band</b> — MAYBE, plus {@link Reason#OTHER}, so the log carries the fact
     *       that a word arrived which the schema does not contain. That is a schema-drift alarm,
     *       and it has to be visible rather than rounded away.</li>
     *   <li><b>an unknown reason</b> — dropped. Reasons are annotation; one bad word in the list
     *       must not cost the bands.</li>
     *   <li><b>an over-long redirect</b> — truncated on a word boundary. A child reads this.</li>
     * </ul>
     */
    public static SafetyVerdict parse(String structuredOutputJson) {
        if (structuredOutputJson == null || structuredOutputJson.isBlank()) {
            return unavailable();
        }
        try {
            int a = structuredOutputJson.indexOf('{');
            int b = structuredOutputJson.lastIndexOf('}');
            JsonElement el = JsonParser.parseString(
                    a >= 0 && b > a ? structuredOutputJson.substring(a, b + 1)
                            : structuredOutputJson);
            if (!el.isJsonObject()) {
                return unavailable();
            }
            JsonObject o = el.getAsJsonObject();
            // A reply with not one recognised field is not a verdict, whatever it parses as. It is
            // an empty object, a stray array element, or the model talking about something else.
            if (!o.has("violence") && !o.has("sexual") && !o.has("hate") && !o.has("danger")) {
                return unavailable();
            }
            List<Reason> reasons = new ArrayList<>(readReasons(o));
            Band violence = band(o, "violence", reasons);
            Band sexual = band(o, "sexual", reasons);
            Band hate = band(o, "hate", reasons);
            Band danger = band(o, "danger", reasons);
            return new SafetyVerdict(violence, sexual, hate, danger,
                    bool(o, "wellbeing"), reasons, str(o, "redirect"), Provenance.HAIKU,
                    integer(o, "stageIndex"));
        } catch (RuntimeException e) {
            return unavailable();
        }
    }

    // ---- parsing helpers, each one total ----

    private static Band band(JsonObject o, String key, List<Reason> reasons) {
        String raw = str(o, key);
        if (raw.isEmpty()) {
            // Absent. Not a drift alarm -- a schema-conforming reply simply cannot do this, so an
            // absent field means the reply was hand-rolled or partial, and MAYBE with no extra
            // reason is the quiet cautious read.
            return Band.MAYBE;
        }
        try {
            return Band.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            if (!reasons.contains(Reason.OTHER) && reasons.size() < MAX_REASONS) {
                reasons.add(Reason.OTHER);
            }
            return Band.MAYBE;
        }
    }

    private static List<Reason> readReasons(JsonObject o) {
        if (!o.has("reasons") || !o.get("reasons").isJsonArray()) {
            return List.of();
        }
        JsonArray arr = o.getAsJsonArray("reasons");
        List<Reason> out = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonPrimitive()) {
                continue;
            }
            try {
                Reason r = Reason.valueOf(e.getAsString().trim().toUpperCase(Locale.ROOT));
                if (!out.contains(r)) {
                    out.add(r);
                }
            } catch (RuntimeException ignored) {
                // A word the vocabulary does not contain. Dropped, never guessed at.
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int integer(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : NO_STAGE;
        } catch (RuntimeException e) {
            return NO_STAGE;
        }
    }

    // ---- the log's view of this (ActionLog.SafetyVerdictView) ----
    //
    // Strings, because a log is strings. The interface lives in ActionLog so that the shape a
    // report reads is pinned there rather than moving whenever this record does.

    @Override
    public String grader() {
        return provenance.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public java.util.Map<String, String> bands() {
        // A LinkedHashMap so the four axes come out in the declared order every time. A report
        // that diffs two runs should not see a field reorder as a change.
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("violence", violence.name());
        m.put("sexual", sexual.name());
        m.put("hate", hate.name());
        m.put("danger", danger.name());
        return m;
    }

    @Override
    public List<String> reasonNames() {
        return reasons.stream().map(Enum::name).toList();
    }

    // ---- bounds, applied in the canonical constructor so no caller can dodge them ----

    private static List<Reason> boundReasons(List<Reason> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        EnumSet<Reason> seen = EnumSet.noneOf(Reason.class);
        List<Reason> out = new ArrayList<>(MAX_REASONS);
        for (Reason r : raw) {
            if (r != null && seen.add(r) && out.size() < MAX_REASONS) {
                out.add(r);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The one free-text field, made boring before anything sees it.
     *
     * <p>{@link WishText#sanitize} first — it strips control characters (a newline could smuggle a
     * second instruction), NFKC-folds lookalikes and collapses whitespace — and then a word-boundary
     * cut, because this string is shown to a child as their own next wish and "a zombie that bon"
     * reads as broken rather than as an offer.
     */
    private static String boundRedirect(String raw) {
        String s = WishText.sanitize(raw);
        if (s.length() <= MAX_REDIRECT) {
            return s;
        }
        String cut = s.substring(0, MAX_REDIRECT);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > MAX_REDIRECT / 2 ? cut.substring(0, lastSpace) : cut).trim();
    }
}
