package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** D10's rule, on its own. The queue that executes it is {@code WishQueueFairnessTest}. */
class FairShareTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void aWorldsBootPropertySetsItsCapAndNothingElseDoes() {
        // D18: a world may run 2 per player through its own JVM's -D; one that names nothing
        // keeps D10's 3. The knob still beats both — Knobs is not installed in a unit test.
        assertEquals(3, FairShare.bootPerChild(null), "no property: D10's 3");
        assertEquals(2, FairShare.bootPerChild("2"), "a manifest's 2");
        assertEquals(3, FairShare.bootPerChild("two"), "a typo is D10's 3, never something else");
        assertEquals(3, FairShare.bootPerChild("0"), "zero would start nobody's wish: ignored");
        assertEquals(3, FairShare.bootPerChild("11"), "above the pool's ceiling: ignored");
        try {
            System.setProperty(FairShare.PER_CHILD_PROPERTY, "2");
            assertEquals(2, FairShare.perChild(), "the JVM's own property is what perChild() reads");
        } finally {
            System.clearProperty(FairShare.PER_CHILD_PROPERTY);
        }
        assertEquals(3, FairShare.perChild());
    }

    @Test
    void withOneChildItIsTheOldQueue() {
        assertEquals(0, FairShare.pick(List.of(A, A, A), Map.of(), Map.of(A, 5), 3),
                "one child: the oldest of his wishes, as before");
    }

    @Test
    void theChildWithFewerPickupsGoesFirstAndTiesGoToTheOlderWish() {
        assertEquals(3, FairShare.pick(List.of(A, A, A, B), Map.of(), Map.of(A, 2), 3),
                "B has had nothing picked up and A has had two: B's wish goes, though it is last");
        assertEquals(0, FairShare.pick(List.of(A, B), Map.of(), Map.of(A, 1, B, 1), 3),
                "equal pickups: the older wish");
    }

    @Test
    void aChildAtHisCapIsSkippedNotRefused() {
        assertEquals(2, FairShare.pick(List.of(A, A, B), Map.of(A, 3), Map.of(B, 9), 3),
                "A is at his cap, so B goes even with more pickups");
        assertEquals(-1, FairShare.pick(List.of(A, A), Map.of(A, 3), Map.of(), 3),
                "nothing may go: every waiting wish is A's and A is at his cap");
    }

    @Test
    void aWishWithNoChildIsItsOwnBucket() {
        List<UUID> owners = Arrays.asList(null, A);
        java.util.Map<UUID, Integer> inFlight = new java.util.HashMap<>();
        inFlight.put(null, 3);
        assertEquals(1, FairShare.pick(owners, inFlight, Map.of(), 3),
                "a console's wishes are capped among themselves, not against a child");
    }
}
