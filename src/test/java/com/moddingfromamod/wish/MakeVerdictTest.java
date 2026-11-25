package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.world.MakeView;
import org.junit.jupiter.api.Test;

/** What the core does with a world's answer about a make (#14): every case, without Minecraft. */
class MakeVerdictTest {

    private static final java.util.function.Predicate<String> PASSES = s -> !s.contains("bad");

    @Test
    void noAnswerAndNoLimitsIsTheGameAsItIs() {
        assertEquals(MakeVerdict.ALLOWED, MakeVerdict.decide(null, Map.of(), PASSES));
        assertEquals(MakeVerdict.ALLOWED, MakeVerdict.decide(MakeView.ALLOWED, Map.of(), PASSES));
    }

    @Test
    void hiddenAndLockedSayTheirLineAndAWorldsWordsAreSwept() {
        MakeVerdict h = MakeVerdict.decide(MakeView.HIDDEN, Map.of(), PASSES);
        assertTrue(h.hidden() && !h.makes() && h.line().equals(MakeVerdict.HIDDEN_LINE));
        MakeVerdict l = MakeVerdict.decide(MakeView.locked("Unlocks after the lighthouse"), Map.of(), PASSES);
        assertTrue(l.locked() && l.line().equals("Unlocks after the lighthouse"));
        assertEquals(MakeVerdict.LOCKED_FALLBACK, MakeVerdict.decide(MakeView.locked("bad words"), Map.of(), PASSES).line());
        assertEquals(MakeVerdict.LOCKED_FALLBACK, MakeVerdict.decide(MakeView.locked(""), Map.of(), PASSES).line());
        assertEquals(MakeVerdict.LOCKED_FALLBACK, MakeVerdict.decide(MakeView.locked("x"), Map.of(), s -> {
            throw new IllegalStateException("the sweep broke");
        }).line());
    }

    @Test
    void fitJoinsTheWorldsUpFrontLimitsWithTheAnswersTheTighterWinning() {
        MakeVerdict v = MakeVerdict.decide(MakeView.fit(Map.of(MakeFacts.COUNT_MADE, 5.0, MakeFacts.SCALE_TIMES, 2.0)),
                Map.of(MakeFacts.COUNT_MADE, 3.0, MakeFacts.AMOUNT_ITEMS, 16.0), PASSES);
        assertTrue(v.makes() && v.state() == MakeView.State.FIT);
        assertEquals(Map.of(MakeFacts.COUNT_MADE, 3.0, MakeFacts.SCALE_TIMES, 2.0, MakeFacts.AMOUNT_ITEMS, 16.0), v.limits());
        // up-front limits alone fit an allowed make
        assertEquals(MakeView.State.FIT, MakeVerdict.decide(MakeView.ALLOWED,
                Map.of(MakeFacts.COUNT_MADE, 3.0), PASSES).state());
        // a key the core does not know is ignored: nothing to fit
        assertEquals(MakeVerdict.ALLOWED, MakeVerdict.decide(MakeView.fit(Map.of("powerLevel", 1.0)), Map.of(), PASSES));
    }

    @Test
    void aFittedMakeIsToldInTheCoresOwnShortWords() {
        assertEquals("", MakeVerdict.fittedLine(List.of()));
        assertTrue(MakeVerdict.fittedLine(List.of(MakeFacts.SCALE_TIMES)).contains("smaller"));
        assertTrue(MakeVerdict.fittedLine(List.of(MakeFacts.COUNT_MADE)).contains("fewer"));
        assertTrue(MakeVerdict.fittedLine(List.of(MakeFacts.DURATION_TICKS)).contains("gentler"));
    }
}
