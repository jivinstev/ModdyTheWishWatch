package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The copy-on-refine decision: its four cells.
 *
 * <p>Every case here is a sentence a child says about a thing that already exists. What changes
 * between them is only <b>whose thing it is</b>, and the answers differ in a way that is invisible
 * from the world: a fork and an edit both leave him with a bigger Spikor standing in front of him,
 * and only one of them leaves his brother's Spikor alone.
 */
class ForkPolicyTest {

    private static final String ALICE = "1f2e3d4c-0000-0000-0000-000000000001";
    private static final String BOB = "9a8b7c6d-0000-0000-0000-000000000002";

    /**
     * 🔴 THE HEADLINE. A refinement of a CURATED thing forks, whoever is asking — the library is a
     * source of bases, never a write target, and an unforked edit does not corrupt it on disk, it
     * SHADOWS it in this world for every child at once.
     */
    @Test
    void refiningALibraryArtifactForksIt() {
        assertEquals(ForkPolicy.Decision.FORK,
                ForkPolicy.decide(ALICE, "spikor", "", true));
        assertEquals("spikor~1f2e3d4c",
                ForkPolicy.keyFor(ForkPolicy.Decision.FORK, "spikor", ALICE));
        assertEquals(ALICE, ForkPolicy.ownerFor(ForkPolicy.Decision.FORK, ALICE, ""));
    }

    /** Refining his own thing edits it, exactly as before per-child ownership. */
    @Test
    void refiningHisOwnEditsInPlace() {
        assertEquals(ForkPolicy.Decision.EDIT_IN_PLACE,
                ForkPolicy.decide(ALICE, "dragon", ALICE, false));
        assertEquals("dragon", ForkPolicy.keyFor(ForkPolicy.Decision.EDIT_IN_PLACE, "dragon", ALICE));
    }

    /** And refining his own FORK edits it — a second refinement must not grow a second fork. */
    @Test
    void refiningHisOwnForkEditsItRatherThanForkingAgain() {
        assertEquals(ForkPolicy.Decision.EDIT_IN_PLACE,
                ForkPolicy.decide(ALICE, "spikor~1f2e3d4c", ALICE, false));
    }

    /**
     * Even when the stamp went missing. A fork's KEY carries its owner, so a record hand-edited or
     * restored from a bundle written by an older build still edits in place for the child it is
     * named after.
     */
    @Test
    void aForkKeyIsEnoughEvenWithNoStampOnTheRecord() {
        assertEquals(ForkPolicy.Decision.EDIT_IN_PLACE,
                ForkPolicy.decide(ALICE, "spikor~1f2e3d4c", "", false));
    }

    /** Another child's learned thing is forked, not edited. */
    @Test
    void refiningAnotherChildsThingForksIt() {
        assertEquals(ForkPolicy.Decision.FORK,
                ForkPolicy.decide(ALICE, "spikor", BOB, false));
        assertEquals("spikor~1f2e3d4c",
                ForkPolicy.keyFor(ForkPolicy.Decision.FORK, "spikor", ALICE));
    }

    /**
     * 🔴 An UNOWNED thing is edited in place and <b>stays unowned</b>. This is the cell that every
     * existing world is in, and stamping it would take a brother's castle off him for the price of
     * touching it.
     */
    @Test
    void refiningALegacyUnownedThingEditsItAndLeavesItShared() {
        assertEquals(ForkPolicy.Decision.EDIT_SHARED,
                ForkPolicy.decide(ALICE, "castle", "", false));
        assertEquals("", ForkPolicy.ownerFor(ForkPolicy.Decision.EDIT_SHARED, ALICE, ""));
    }

    /**
     * With nothing bound — a gate, a direct call, two wishes in flight — everything reads as
     * shared, which is the pre-HD-7 behaviour and the right one for a single-child server.
     */
    @Test
    void anUnidentifiableAskerEditsSharedRatherThanForking() {
        assertEquals(ForkPolicy.Decision.EDIT_SHARED, ForkPolicy.decide("", "castle", "", false));
        assertEquals(ForkPolicy.Decision.EDIT_SHARED, ForkPolicy.decide("", "castle", BOB, false));
    }

    /**
     * …except over a LIBRARY artifact, where there is no third option: editing shadows the curated
     * copy for everybody and forking needs an owner, so it refuses and says so.
     */
    @Test
    void anUnidentifiableAskerCannotTouchALibraryArtifactAtAll() {
        assertEquals(ForkPolicy.Decision.REFUSE, ForkPolicy.decide("", "spikor", "", true));
        assertEquals("", ForkPolicy.keyFor(ForkPolicy.Decision.REFUSE, "spikor", ""),
                "a REFUSE must not hand back the base's own key — a caller that ignored the "
                        + "decision would then write straight over it");
    }
}
