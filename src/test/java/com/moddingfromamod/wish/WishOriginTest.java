package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * a wish a session, a canary or a stand-in sent on a live server is decided test traffic
 * AT SUBMIT, and a child's wish never is — including a child's {@code /wish}, which is the same
 * {@code COMMAND} source the test wishes used.
 */
class WishOriginTest {

    private static final String CHILD_ID = "07e1e78c-0cfa-3cca-91c6-3cf484ce277d";
    private static final String STAND_IN_ID = "65c9acfc-ae7e-3e02-b0ac-df045f09af05";
    private static final long NOW = 1_800_000_000_000L;

    @AfterEach
    void reset() {
        WishOrigin.resetForTest();
    }

    @Test
    void aChildWithNothingConfiguredIsAChild() {
        assertEquals(WishOrigin.CHILD,
                WishOrigin.decide(null, CHILD_ID, "wishcraft0001", Set.of(), Map.of(), NOW));
        assertFalse(WishOrigin.isTest(WishOrigin.CHILD));
    }

    @Test
    void aStandInIsMatchedByIdOrByNameInAnyCase() {
        Set<String> standIns = WishOrigin.parseStandIns(" " + STAND_IN_ID.toUpperCase() + ", ModdyTester ,");
        assertEquals(Set.of(STAND_IN_ID, "moddytester"), standIns);
        assertEquals(WishOrigin.STAND_IN,
                WishOrigin.decide(null, STAND_IN_ID, "whoever", standIns, Map.of(), NOW));
        assertEquals(WishOrigin.STAND_IN,
                WishOrigin.decide(null, "aaaaaaaa-0000-0000-0000-000000000000", "moddytester",
                        standIns, Map.of(), NOW));
        assertEquals(WishOrigin.CHILD,
                WishOrigin.decide(null, CHILD_ID, "wishcraft0001", standIns, Map.of(), NOW));
    }

    @Test
    void anArmedWindowMarksThatPlayerUntilItEnds() {
        Map<String, Long> w = Map.of("wishcraft0001", NOW + 60_000);
        assertEquals(WishOrigin.SESSION,
                WishOrigin.decide(null, CHILD_ID, "WishCraft0001", Set.of(), w, NOW));
        assertEquals(WishOrigin.CHILD,
                WishOrigin.decide(null, CHILD_ID, "wishcraft0001", Set.of(), w, NOW + 60_000),
                "a window that has ended marks nothing — the child's next wish is his");
        assertEquals(WishOrigin.CHILD,
                WishOrigin.decide(null, "bbbbbbbb-0000-0000-0000-000000000000", "sibling",
                        Set.of(), w, NOW), "only the armed player");
    }

    @Test
    void anExplicitScopeWinsAndIsRestoredOnClose() {
        assertEquals(WishOrigin.CANARY,
                WishOrigin.decide(WishOrigin.CANARY, STAND_IN_ID, "x", Set.of(STAND_IN_ID), Map.of(), NOW));
        try (var outer = WishOrigin.scope(WishOrigin.PROBE)) {
            assertEquals(WishOrigin.PROBE, WishOrigin.of(CHILD_ID, "wishcraft0001"));
            try (var inner = WishOrigin.scope(WishOrigin.CANARY)) {
                assertEquals(WishOrigin.CANARY, WishOrigin.of(CHILD_ID, "wishcraft0001"));
            }
            assertEquals(WishOrigin.PROBE, WishOrigin.of(CHILD_ID, "wishcraft0001"));
        }
        assertEquals(WishOrigin.CHILD, WishOrigin.of(CHILD_ID, "wishcraft0001"));
    }

    @Test
    void armIsClampedAndOffDisarms() {
        long until = WishOrigin.arm("wishcraft0001", 100_000, NOW);
        assertEquals(NOW + WishOrigin.MAX_WINDOW_MINUTES * 60_000L, until);
        assertEquals(Set.of("wishcraft0001"), WishOrigin.windows(NOW).keySet());
        assertEquals(WishOrigin.SESSION, WishOrigin.of(CHILD_ID, "wishcraft0001"));
        assertEquals(0, WishOrigin.arm("WISHCRAFT0001", 0, NOW));
        assertTrue(WishOrigin.windows(NOW).isEmpty());
        assertEquals(WishOrigin.CHILD, WishOrigin.of(CHILD_ID, "wishcraft0001"));
    }

    @Test
    void onlyTestWishesAreRememberedAndByEitherIdForm() {
        String full = "e7b84c19-1111-2222-3333-444444444444";
        WishOrigin.remember(full, WishOrigin.SESSION);
        assertEquals(WishOrigin.SESSION, WishOrigin.ofWish(full));
        assertEquals(WishOrigin.SESSION, WishOrigin.ofWish("e7b84c19"));
        WishOrigin.remember("1c5fd691", WishOrigin.CANARY);
        assertEquals(WishOrigin.CANARY, WishOrigin.ofWish("1c5fd691-aaaa-bbbb-cccc-dddddddddddd"));
        WishOrigin.remember("abcd1234", WishOrigin.CHILD);
        assertNull(WishOrigin.ofWish("abcd1234"), "a child's wish is never remembered");
        assertNull(WishOrigin.ofWish(""));
    }
}
