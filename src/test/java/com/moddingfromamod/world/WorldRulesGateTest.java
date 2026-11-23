package com.moddingfromamod.world;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pickup decision, every row of the locked design; the verdict's typed boundary; and the
 * grader's cheap exits (a world with no rules never asks a model; an unavailable grader fails OPEN).
 */
class WorldRulesGateTest {

    private static final WorldRules CLOSED_RULES = WorldRules.parse(WorldRulesTest.OK, "closed").rules();

    private static WorldRulesVerdict v(WorldRulesVerdict.Kind k, String part, String suggestion, WorldEffect... e) {
        Set<WorldEffect> s = EnumSet.noneOf(WorldEffect.class);
        java.util.Collections.addAll(s, e);
        return new WorldRulesVerdict(k, s, part, suggestion, WorldRulesVerdict.Provenance.TEST);
    }

    private static WorldRulesGate.Decision decide(WorldRulesVerdict v) {
        return WorldRulesGate.decide(v, CLOSED_RULES, "the wish", false, s -> true);
    }

    @AfterEach
    void reset() {
        WorldRulesGrader.forTest(null);
        WorldRulesGrader.transportForTest(null);
    }

    @Test
    void clearlyWorldWideOnADeniedEffectIsRefusedWithTheKindMessage() {
        WorldRulesGate.Decision d = decide(v(WorldRulesVerdict.Kind.WORLD_WIDE, "", "A rain of fish over your island!",
                WorldEffect.TIME));
        assertEquals(WorldRulesGate.Act.REFUSE, d.act());
        assertEquals("no-time", d.rule().id());
        String m = d.childMessage();
        // heard you → I know how → the rule and why → where it works → something instead
        int heard = m.indexOf("I heard your wish");
        int how = m.indexOf("I know just how");
        int rule = m.indexOf("time is shared");
        int why = m.indexOf("everyone shares the sky");
        int where = m.indexOf("It works in Moddy's Workshop");
        int instead = m.indexOf("A rain of fish over your island!");
        assertTrue(heard >= 0 && heard < how && how < rule && rule < why && why < where && where < instead,
                "the five parts, in order: " + m);
        assertFalse(m.toLowerCase(Locale.ROOT).contains("charge") || m.toLowerCase(Locale.ROOT).contains("cost"),
                "a refusal never costs anything: " + m);
    }

    @Test
    void theFileNotTheModelSaysWhatIsForbidden() {
        assertEquals(WorldRulesGate.Act.PASS, decide(v(WorldRulesVerdict.Kind.WORLD_WIDE, "", "",
                WorldEffect.RECIPES_DROPS)).act(), "recipes are not denied by this file");
        assertEquals(WorldRulesGate.Act.PASS, decide(v(WorldRulesVerdict.Kind.WORLD_WIDE, "", "")).act(),
                "world_wide with no effect refuses nothing");
        assertEquals(WorldRulesGate.Act.PASS, WorldRulesGate.decide(
                v(WorldRulesVerdict.Kind.WORLD_WIDE, "", "", WorldEffect.TIME), WorldRules.ALLOW_ALL,
                "x", false, s -> true).act(), "a world with no rules sees no change");
    }

    @Test
    void unsureAndUnavailableAreGrantedWithTheRulesInTheBrief() {
        WorldRulesGate.Decision u = decide(v(WorldRulesVerdict.Kind.UNSURE, "", ""));
        assertEquals(WorldRulesGate.Act.UNSURE, u.act());
        assertEquals("the wish", u.runText());
        assertTrue(u.briefNote().contains("UNSURE") && u.childMessage().isEmpty());
        assertEquals(WorldRulesGate.Act.UNSURE, decide(WorldRulesVerdict.unavailable()).act(),
                "the grader fails OPEN: the backstop still judges what is built");
        String brief = WorldRulesGate.brief(CLOSED_RULES);
        assertTrue(brief.contains("THIS WORLD'S RULES (closed)") && brief.contains("the time of day")
                && brief.contains("author_datapack") && brief.contains("his own pets") && brief.contains("- be terse"),
                brief);
        assertEquals("", WorldRulesGate.brief(WorldRules.ALLOW_ALL), "a no-rules world's brief is unchanged");
    }

    @Test
    void mixedIsSplitAndHyperboleIsClamped() {
        WorldRulesGate.Decision s = decide(v(WorldRulesVerdict.Kind.MIXED, "a castle on my island", "", WorldEffect.TIME));
        assertEquals(WorldRulesGate.Act.SPLIT, s.act());
        assertEquals("a castle on my island", s.runText(), "the agent is handed the allowed part");
        assertTrue(s.childMessage().contains("I'll grant the part I can here: a castle on my island")
                && s.childMessage().contains("time is shared"), s.childMessage());
        assertTrue(s.briefNote().contains("SPLIT"));

        WorldRulesGate.Decision c = decide(v(WorldRulesVerdict.Kind.HYPERBOLE, "", "", WorldEffect.OUTSIDE_OWN_BASE));
        assertEquals(WorldRulesGate.Act.CLAMP, c.act());
        assertEquals("the wish", c.runText());
        assertTrue(c.childMessage().contains("on your own island") && c.briefNote().contains("CLAMP"));
    }

    @Test
    void aSplitNeverOverridesARedirectOrSaysAnUnsweptLine() {
        WorldRulesVerdict mixed = v(WorldRulesVerdict.Kind.MIXED, "a castle", "a hint", WorldEffect.TIME);
        assertEquals(WorldRulesGate.Act.UNSURE, WorldRulesGate.decide(mixed, CLOSED_RULES, "redirected", true, s -> true).act(),
                "after a safety redirect the child's original words must not come back as a split");
        assertEquals(WorldRulesGate.Act.UNSURE, WorldRulesGate.decide(mixed, CLOSED_RULES, "x", false, s -> false).act(),
                "an allowed part that fails the sweep is not said");
        WorldRulesGate.Decision r = WorldRulesGate.decide(v(WorldRulesVerdict.Kind.WORLD_WIDE, "", "bad words",
                WorldEffect.TIME), CLOSED_RULES, "x", false, s -> !s.contains("bad"));
        assertTrue(r.refuses() && !r.childMessage().contains("bad words")
                && r.childMessage().contains(WorldRulesWords.FALLBACK_SUGGESTION), r.childMessage());
    }

    @Test
    void theVerdictIsATypeAndGarbageIsUnsureNeverARefusal() {
        assertTrue(WorldRulesVerdict.parse(null).isUnavailable());
        assertTrue(WorldRulesVerdict.parse("[1]").isUnavailable());
        assertEquals(WorldRulesVerdict.Kind.UNSURE, WorldRulesVerdict.parse("{\"verdict\":\"refuse_it\"}").kind());
        WorldRulesVerdict p = WorldRulesVerdict.parse("{\"verdict\":\"world_wide\",\"effects\":[\"time\",\"nonsense\","
                + "\"weather\",\"recipes_drops\",\"others_bases\",\"outside_own_base\"],\"suggestion\":\"" + "x".repeat(400)
                + "\\u0007\"}");
        assertEquals(WorldRulesVerdict.Kind.WORLD_WIDE, p.kind());
        assertEquals(4, p.effects().size(), "bounded, unknown keys dropped: " + p.effects());
        assertTrue(p.suggestion().length() <= WorldRulesVerdict.MAX_TEXT && !p.suggestion().contains("\u0007"));
    }

    @Test
    void anAllowAllWorldNeverAsksAModel() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        WorldRulesGrader.forTest(t -> {
            asked.incrementAndGet();
            return WorldRulesVerdict.unavailable();
        });
        assertTrue(WorldRulesGrader.gradeInput("make it night", WorldRules.ALLOW_ALL).get().verdict().isUnavailable());
        assertEquals(0, asked.get(), "no call, no cost on a world that denies nothing");
        WorldRulesGrader.gradeInput("make it night", CLOSED_RULES).get();
        assertEquals(1, asked.get());
        WorldRulesGrader.gradeInput("make it night", CLOSED_RULES).get();
        assertEquals(1, asked.get(), "memoised: a re-offer joins the grade already made");
    }

    @Test
    void anUnreachableGraderIsUnsure() throws Exception {
        WorldRulesGrader.transportForTest((sys, user, schema, timeout) -> null);
        WorldRulesGrader.Graded g = WorldRulesGrader.gradeInput("make it night " + System.nanoTime(), CLOSED_RULES).get();
        assertTrue(g.verdict().isUnavailable());
        assertEquals(WorldRulesGate.Act.UNSURE, WorldRulesGate.decide(g.verdict(), CLOSED_RULES, "x", false, s -> true).act());
        WorldRulesGrader.transportForTest((sys, user, schema, timeout) -> new com.moddingfromamod.safety.SafetyGrader.Reply(
                "{\"verdict\":\"world_wide\",\"effects\":[\"time\"]}", 900, 0.004, "claude-haiku-4-5", ""));
        WorldRulesGrader.Graded ok = WorldRulesGrader.gradeInput("make it night " + System.nanoTime(), CLOSED_RULES).get();
        assertEquals(WorldRulesVerdict.Kind.WORLD_WIDE, ok.verdict().kind());
        assertEquals(0.004, ok.costUsd(), 1e-9);
    }

    @Test
    void theUserMessageIsPinnedBecauseTheEvalRebuildsIt() {
        // tools/world-rules-eval.py builds this same string (its test pins the same text).
        assertEquals("THIS WORLD DENIES: time, weather, others_bases, outside_own_base\n"
                        + "THIS WORLD ALLOWS standing rules scoped to: own_base, pets\n"
                        + "THE CHILD'S WISH: make it night",
                WorldRulesGrader.userText(CLOSED_RULES, "make it night"));
        assertTrue(WorldRulesGrader.resource("grader-prompt.md").contains("A WRONG REFUSAL IS THE WORST MISTAKE"));
        assertTrue(WorldRulesGrader.resource("verdict-schema.json").contains("\"world_wide\""));
    }

    @Test
    void theModuleIsTheNamedOneOrTheImpliedOne() {
        assertEquals("islands", WorldModuleHost.chooseModule("islands", ""));
        assertEquals("islands", WorldModuleHost.chooseModule(null, "islands"), "a server whose launch config predates `module`");
        assertEquals("", WorldModuleHost.chooseModule("", ""), "a world with no rules runs no module");
        assertEquals("", WorldModuleHost.chooseModule("none", "islands"));
        assertEquals("", WorldModuleHost.chooseModule(null, ""), "no plug-in implies one: the public mod");
    }
}
