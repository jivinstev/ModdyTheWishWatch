package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The soak's timeline. Pinned here because a wrong one is found two hours into a paid run.
 */
class SoakPlanTest {

    private static final long MIN = 60_000L;

    @Test
    void threeCyclesFitInThreeHours() {
        // 60-minute cycles since the reap grace moved to ~25.7 min (min cycle ~56).
        SoakPlan p = SoakPlan.of(180 * MIN, 60 * MIN, List.of("a", "b", "c"));
        long joins = p.steps().stream().filter(s -> s.kind() == SoakPlan.Kind.JOIN).count();
        long wishes = p.steps().stream().filter(s -> s.kind() == SoakPlan.Kind.WISH).count();
        assertEquals(3, joins);
        assertEquals(6, wishes, "two wishes a cycle — the second lands while the first is mid-grant");
        assertEquals(SoakPlan.Kind.END, p.steps().get(p.steps().size() - 1).kind());
        assertEquals(180 * MIN, p.steps().get(p.steps().size() - 1).atMs());
    }

    @Test
    void wishesAreTakenRoundRobin() {
        SoakPlan p = SoakPlan.of(180 * MIN, 60 * MIN, List.of("a", "b", "c"));
        List<String> made = p.steps().stream().filter(s -> s.kind() == SoakPlan.Kind.WISH)
                .map(SoakPlan.Step::wish).toList();
        assertEquals(List.of("a", "b", "c", "a", "b", "c"), made);
    }

    /** The empty stretch must outlast the reap grace, or the soak never sees a reap at all. */
    @Test
    void aCycleTooShortToReapIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> SoakPlan.of(180 * MIN, 45 * MIN, List.of("a")));
        assertTrue(SoakPlan.minCycleMs() > SoakPlan.LEAVE_MS + IdlePolicy.GRACE_MS);
    }

    @Test
    void presenceFollowsJoinAndLeave() {
        SoakPlan p = SoakPlan.of(180 * MIN, 60 * MIN, List.of("a"));
        assertTrue(p.presentAt(0));
        assertTrue(p.presentAt(10 * MIN));
        assertFalse(p.presentAt(30 * MIN), "left at 25");
        assertTrue(p.presentAt(61 * MIN), "the second cycle joined at 60");
        assertFalse(p.presentAt(180 * MIN), "nobody is online once the soak has ended");
    }

    @Test
    void aSoakNeedsSomethingToWishFor() {
        assertThrows(IllegalArgumentException.class, () -> SoakPlan.of(180 * MIN, 60 * MIN, List.of()));
    }
}
