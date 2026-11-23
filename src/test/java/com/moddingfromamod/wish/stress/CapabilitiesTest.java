package com.moddingfromamod.wish.stress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.wish.WishCorpus;
import org.junit.jupiter.api.Test;

/**
 * The capability gate: a corpus may describe more than the engine can verify, but never quietly.
 *
 * <p>The risk being managed is specific. Writing cases for unbuilt subsystems is the point — it
 * makes the corpus a specification each subsystem lands against rather than a mirror of what its
 * author happened to build. But a case naming a capability nobody has built is a case that never
 * runs, and this repo's founding defect was a corpus that loaded 160 cases, ran 19, and reported
 * green. So every one of these tests is about the gap being visible.
 *
 * <p>Its tests about the machines corpus itself went with Create; the mechanism stays, and
 * is exercised here on synthetic cases.
 */
class CapabilitiesTest {

    private static List<WishCorpus.Case> corpus() {
        return WishCorpus.load();
    }

    @Test
    void aCaseNeedingNothingIsRunnable() {
        WishCorpus.Case c = WishCorpus.parse(
                "{\"id\":\"x\",\"kind\":\"machines\",\"spec\":{}}").get(0);
        assertTrue(Capabilities.runnable(c));
        assertTrue(Capabilities.missingFor(c).isEmpty());
    }

    /**
     * A capability that will never exist, for the tests that need an example of an unbuilt one.
     *
     * <p>This line used to name a real backlog item and be repointed each time one landed —
     * fluid-probe, then be-nbt, then entity-oracle, then arrival-probe, then boiler-power. There is
     * nothing left to point it at: every oracle the Create backlog asked for is built. A deliberate
     * fiction is what keeps the mechanism under test after the backlog it was borrowing from is
     * gone.
     */
    private static final String NEVER_BUILT = "a-capability-nobody-will-ever-build";

    @Test
    void aCaseNeedingSomethingUnbuiltIsNotRunnableAndSaysWhat() {
        WishCorpus.Case c = WishCorpus.parse(
                "{\"id\":\"x\",\"kind\":\"machines\",\"spec\":{\"needs\":[\"" + NEVER_BUILT
                        + "\"]}}")
                .get(0);
        assertFalse(Capabilities.runnable(c));
        assertEquals(List.of(NEVER_BUILT), Capabilities.missingFor(c));
    }

    @Test
    void aCaseNeedingSomethingBuiltRunsAgain() {
        // The self-closing property: adding a capability to IMPLEMENTED switches its cases on with
        // no edit to the corpus at all. If they then fail, they were always failing.
        WishCorpus.Case c = WishCorpus.parse(
                "{\"id\":\"x\",\"kind\":\"machines\",\"spec\":{\"needs\":[\"kinetics\"]}}").get(0);
        assertTrue(Capabilities.runnable(c),
                "'kinetics' is in IMPLEMENTED, so a case needing it must run");
    }

    @Test
    void everyCapabilityTheCorpusNamesIsAKnownOne() {
        // A typo parks a case forever in a gap that can never close, looking like honest backlog.
        assertEquals(List.of(), Capabilities.unknownNames(corpus()));
    }

    @Test
    void theBacklogIsUnderItsCeiling() {
        List<WishCorpus.Case> waiting = Capabilities.waiting(corpus());
        assertTrue(waiting.size() <= Capabilities.MAX_GAPS,
                waiting.size() + " case(s) waiting, over the ceiling of " + Capabilities.MAX_GAPS
                        + ". Adding cases nobody can run is not progress, and it is the one thing "
                        + "that looks like progress in every report.");
    }

    @Test
    void theGapMechanismStillReadsNeedsEvenWithNoBacklogLeft() {
        // This used to assert the real corpus still HAD backlog, which could only stay true until
        // the work was finished — and it is: stock-network, spout-outcome and boiler-power were the
        // last three, so nothing is waiting on anything. That reading is also exactly what a
        // `needs` mechanism that had silently stopped being read would produce, and the two are
        // indistinguishable in every other report. So the guard is synthetic now, and outlives the
        // backlog it was borrowing from.
        var gaps = Capabilities.gapReport(WishCorpus.parse(
                "{\"id\":\"x\",\"kind\":\"machines\",\"spec\":{\"needs\":[\"" + NEVER_BUILT
                        + "\"]}}"));
        assertEquals(1, gaps.getOrDefault(NEVER_BUILT, 0),
                "a case waiting on a capability nobody has built must be COUNTED as waiting, or "
                        + "gating has stopped working and every skip reports green: " + gaps);
        // ...and the real corpus is clear because the work got done, not because nothing is read.
        assertTrue(Capabilities.gapReport(corpus()).isEmpty(),
                "no real case should be waiting: "
                        + Capabilities.gapReport(corpus()));
    }
}
