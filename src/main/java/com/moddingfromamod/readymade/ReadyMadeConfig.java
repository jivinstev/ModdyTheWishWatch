package com.moddingfromamod.readymade;

import java.util.EnumSet;
import java.util.Set;

import com.moddingfromamod.runtime.RuntimeConfig;

/**
 * <b>Which cells of the matrix are switched on</b> — {@code readymade.nudge} and
 * {@code readymade.search}, each a set of kind letters ({@code a}–{@code d}).
 *
 * <p>A cell ships only when its arm beats "neither" (the issue's bars), and shipping it is
 * flipping its letter's default here. Search defaults to NONE: no search tools, the full catalogues
 * in {@code make_item} and {@code apply_effect}.
 *
 * <p><b>The nudge defaults to {@value #DEFAULT_NUDGES}.</b> (a) prior wishes, shipped 2026-10-27
 * (owner): routing +16 points pooled (CI [+7, +27]), almost all of it Spark's "a new one like my X"
 * (6/14 → 11–12/14); real grants reused his thing 12/12 against 10/12 and never the wrong one, with
 * duration unchanged (corpus/wish-runs/2026-10-27-1669-a-panel). The nudge reads his FACTS, so a
 * follow-up is matched against the same history the worker has.
 * <b>(c) creature abilities, shipped 2026-10-27 (owner).</b> Routing arm, harness timing fixed: hit 52% → 100% (Opus), 40% → 100% (Spark), no
 * decoy misused. Real grants (corpus/wish-runs/2026-10-27-1669-c-phase2): 20/20 against 13/20, the
 * nudge landing before the first build in every one of the 20 (5–21 s ahead), Spark's hand-written
 * datapacks 3 → 0. {@code readymade.nudge=none} is the agent.
 * Read fresh every time, like {@code ChildSafetyConfig}, so a GameTest's property is honoured.
 */
public final class ReadyMadeConfig {

    /** The nudge kinds when nothing is configured. */
    public static final String DEFAULT_NUDGES = "ac";

    private ReadyMadeConfig() {
    }

    /**
     * ⚠ <b>Unset in a GATE means none</b>, as {@code ChildSafetyConfig.unavailableClosed} does it: a
     * gate has no Haiku (on a runner, no {@code claude} CLI), so a default-on nudge would start a
     * subprocess beside every GameTest wish and measure nothing. A test about the nudge sets it
     * explicitly ({@code ReadyMadeNudgeGameTest}); anything explicit is obeyed everywhere.
     */
    public static Set<ReadyMade.Kind> nudges() {
        String v = RuntimeConfig.readyMadeNudge();
        if (v == null || v.isBlank()) {
            v = com.moddingfromamod.RunContext.isTestRun() ? "none" : DEFAULT_NUDGES;
        }
        return parse(v);
    }

    public static Set<ReadyMade.Kind> searches() {
        return parse(RuntimeConfig.readyMadeSearch());
    }

    public static boolean nudges(ReadyMade.Kind k) {
        return nudges().contains(k);
    }

    public static boolean searches(ReadyMade.Kind k) {
        return searches().contains(k);
    }

    /** {@code "bcd"}, {@code "b,c"}, {@code "all"}, {@code "none"} — unknown letters are ignored. */
    public static Set<ReadyMade.Kind> parse(String v) {
        EnumSet<ReadyMade.Kind> out = EnumSet.noneOf(ReadyMade.Kind.class);
        if (v == null) {
            return out;
        }
        String t = v.strip().toLowerCase(java.util.Locale.ROOT);
        if (t.equals("all") || t.equals("on")) {
            return EnumSet.allOf(ReadyMade.Kind.class);
        }
        if (t.isEmpty() || t.equals("none") || t.equals("off")) {
            return out;
        }
        for (char c : t.toCharArray()) {
            ReadyMade.Kind k = ReadyMade.Kind.parse(String.valueOf(c));
            if (k != null) {
                out.add(k);
            }
        }
        return out;
    }

    /** The arm as a log field: {@code nudge=bd search=d}. */
    public static String label() {
        return "nudge=" + letters(nudges()) + " search=" + letters(searches());
    }

    public static String letters(Set<ReadyMade.Kind> kinds) {
        if (kinds.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (ReadyMade.Kind k : EnumSet.copyOf(kinds)) {
            sb.append(k.letter);
        }
        return sb.toString();
    }
}
