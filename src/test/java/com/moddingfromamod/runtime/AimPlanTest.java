package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * How the fallback shot frames its subject, tested without a world.
 *
 * <p>The bug these pin is silent by construction: a rotation-only viewpoint at staging range
 * photographs empty scenery, arrives where it was sent, misplaces no frame and passes.
 *
 * <p>This file used to be titled <i>"the one rule that differs between a camera and a child"</i> and
 * asserted that difference in four places. The rule is retired — see {@link AimPlan} — so the tests
 * that pinned it are now the tests that would bring it back, and the last one here is the guard
 * against it returning by the door it came in through the first time.
 */
class AimPlanTest {

    private static final double H = 6.5;          // a Spikor-ish creature

    @Test
    void aSubjectThatAlreadyFitsIsJustFaced() {
        assertEquals(AimPlan.Move.ROTATE_ONLY, AimPlan.plan(H, Framing.distanceFor(H)).move());
    }

    @Test
    void aViewpointTRAVELSToASubjectItCannotSee() {
        // THE STAGING CASE, and the whole reason this class exists.
        AimPlan.Plan p = AimPlan.plan(H, 2000);
        assertEquals(AimPlan.Move.MOVE_VIEWPOINT, p.move());
        assertTrue(p.tooFarToSee(), "the caller still reports how far it went");
    }

    @Test
    void tooCloseMovesTheVIEWPOINTBackAndNeverTheSubject() {
        double nose = 1.0;
        assertFalse(Framing.fitsInFrame(H, nose), "the fixture must actually be too close");
        AimPlan.Plan p = AimPlan.plan(H, nose);
        assertEquals(AimPlan.Move.MOVE_VIEWPOINT, p.move(),
                "a self-critique loop must not judge a creature it just teleported");
        assertFalse(p.tooFarToSee(), "too close is not the staging case");
    }

    @Test
    void theStandoffIsFramingsAnswerAndNotASecondCopyOfIt() {
        assertEquals(Framing.distanceFor(H), AimPlan.plan(H, 2000).standoff(), 1e-9);
        assertEquals(Framing.distanceFor(2.0), AimPlan.plan(2.0, 1.0).standoff(), 1e-9);
    }

    @Test
    void aSubjectJustInsideVisibleRangeIsNotTravelledTo() {
        // The boundary matters: travelling for an ordinary in-world creature would teleport the
        // viewpoint away for every routine shot, which is a behaviour change nobody asked for.
        // Only the far case is the staging case.
        AimPlan.Plan near = AimPlan.plan(H, AimPlan.VISIBLE_RANGE - 1);
        assertFalse(near.tooFarToSee());
        assertEquals(AimPlan.Move.ROTATE_ONLY, near.move());
    }

    @Test
    void aTinySubjectStillGetsARealStandoff() {
        assertTrue(AimPlan.plan(0.2, 500).standoff() > 0,
                "a standoff of zero would put the lens inside the creature");
    }

    /**
     * <b>The plan cannot ask who is looking.</b>
     *
     * <p>Not decoration. The retired rule did not start life as a rule about the camera — it was a
     * rule about a child's body that <i>became</i> one, silently, the day {@code aimAt} started
     * being handed a viewpoint instead of a child, and nothing failed because a camera rotating to
     * face a dot on the horizon still writes a frame. A parameter is the mechanism that made that
     * possible, so its absence is the thing worth pinning: a source sweep is the only check that
     * survives someone re-adding the branch inside the method.
     */
    @Test
    void theDecisionCannotBranchOnTheViewpointKind() throws IOException {
        String src = Files.readString(
                Path.of("src/main/java/com/moddingfromamod/runtime/AimPlan.java"));
        String body = src.substring(src.indexOf("public static Plan plan("));
        assertFalse(body.contains("isCamera"),
                "plan() must not take or test a viewpoint kind — that is the rule that was retired");
        // The enum itself, NOT a source sweep for the word: this file's own javadoc records why
        // MOVE_SUBJECT went, and a text search would make writing that reason down a test failure.
        for (AimPlan.Move m : AimPlan.Move.values()) {
            assertFalse(m.name().contains("SUBJECT"),
                    "stepping the subject back was the child-only branch, and a self-critique loop "
                            + "must not judge a creature it just teleported: " + m);
        }
    }
}
