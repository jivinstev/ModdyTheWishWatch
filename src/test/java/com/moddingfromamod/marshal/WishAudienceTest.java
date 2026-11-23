package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The addressing rule for two children — D1/D2 of {@code docs/MULTIPLAYER_DECISIONS.md}.
 *
 * <p>Every assertion here is an IDENTITY rather than an absence, deliberately. "B was told nothing
 * about A's wish" passes when the whole feature is dead; "B was told about B's wish, and only B's"
 * is a claim that fails when it stops working.
 */
class WishAudienceTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final List<UUID> BOTH = List.of(ALICE, BOB);

    @Test
    @DisplayName("one child: his own wish reaches him")
    void oneChildHearsHisOwnWish() {
        assertEquals(Set.of(ALICE), WishAudience.forNarration(ALICE, List.of(ALICE)));
    }

    @Test
    @DisplayName("two children: each hears his OWN wish, and only his own")
    void eachChildHearsOnlyHisOwn() {
        assertEquals(Set.of(ALICE), WishAudience.forNarration(ALICE, BOTH),
                "Alice's wish must reach Alice");
        assertEquals(Set.of(BOB), WishAudience.forNarration(BOB, BOTH),
                "Bob's wish must reach Bob — and this is the assertion that fails on the pre-fix "
                        + "code, where both wishes reached both children");
    }

    /**
     * <b>The bug, stated as a test.</b> The pre-fix write loop said "for every player", which is the
     * answer this returns only when it genuinely cannot tell — and then it must be nobody.
     */
    @Test
    @DisplayName("an unknown owner is told to NOBODY rather than to everybody")
    void anUnknownOwnerIsToldToNobodyRatherThanEverybody() {
        assertTrue(WishAudience.forNarration(null, BOTH).isEmpty(),
                "'I cannot tell whose wish this is, so I will tell everyone' IS the defect — with "
                        + "one child it is indistinguishable from correct, which is why it survived");
        assertNull(WishAudience.actingPlayer(null, BOTH));
    }

    @Test
    @DisplayName("a child who has logged out is not addressed, and it is not an error")
    void anOfflineOwnerIsSimplyNotAddressed() {
        assertTrue(WishAudience.forNarration(ALICE, List.of(BOB)).isEmpty(),
                "a wish outlives the child who made it; narrating to nobody is correct, and "
                        + "narrating to whoever is left is the same bug in a smaller room");
        assertNull(WishAudience.actingPlayer(ALICE, List.of(BOB)));
        assertTrue(WishAudience.forNarration(ALICE, List.of()).isEmpty());
    }

    /**
     * The two answers agree today and are separate on purpose — see the javadoc. This pins that they
     * agree, so a future widening of {@link WishAudience#forNarration} is a visible edit here rather
     * than a silent change to who gets teleported.
     */
    @Test
    @DisplayName("acting and narration agree today, and the split is deliberate")
    void actingAndNarrationAgreeToday() {
        for (UUID who : BOTH) {
            assertEquals(Set.of(who), WishAudience.forNarration(who, BOTH));
            assertEquals(who, WishAudience.actingPlayer(who, BOTH));
        }
    }

    @Test
    @DisplayName("null and empty inputs answer, rather than throwing")
    void theRuleNeverThrows() {
        assertTrue(WishAudience.forNarration(ALICE, null).isEmpty());
        assertTrue(WishAudience.forNarration(null, null).isEmpty());
        assertNull(WishAudience.actingPlayer(ALICE, null));
    }
}
