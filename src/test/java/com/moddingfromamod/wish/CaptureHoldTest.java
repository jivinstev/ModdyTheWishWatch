package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Where a frozen body is held mid-burst —, the establishing shot of an empty field.</b>
 *
 * <p>The hold exists so a child whose eyes have been borrowed cannot walk out of his own wish card.
 * It anchored on the spot he was standing when the burst began, and the capture path
 * <b>moves that same body on purpose</b> — eighteen blocks out to an orbit seat. So two writers
 * fought over one position: the orbit every 100&nbsp;ms, the hold every five ticks, and whichever
 * wrote last before the shutter decided the photograph.
 *
 * <p>Every test here is about {@link WishCapture#holdAt}, which is pure. The wiring it depends on —
 * that {@code PhotographTool.moveCameraTo} tells it where the body now belongs — is a single call
 * site and is asserted by {@code SourceRuleSweepTest}; that split is deliberate, because a rule
 * tested only where it is DEFINED is not tested where it RUNS.
 */
class CaptureHoldTest {

    private static final double[] ORIGIN = {10.0, 64.0, 10.0};
    private static final double[] SEAT = {28.0, 70.0, 10.0};   // an orbit seat, 18 blocks out

    /**
     * <b>VERIFIED TO FAIL — this is itself.</b>
     *
     * <p>The body is exactly where the camera put it. Anchoring on the origin answers "you have
     * drifted eighteen blocks, come back", which is the yank that emptied the frame.
     */
    @Test
    @DisplayName("a body sitting on the camera seat the capture chose is LEFT ALONE")
    void doesNotDragTheCameraOffItsOwnSeat() {
        assertNull(WishCapture.holdAt(ORIGIN[0], ORIGIN[1], ORIGIN[2], SEAT,
                        SEAT[0], SEAT[1], SEAT[2]),
                "the capture path put it here on purpose; pulling it back to the child's feet is "
                        + "the bug — the shutter then photographs the field he was standing in");
    }

    @Test
    @DisplayName("…and it is still pinned THERE if it drifts off the seat")
    void stillPinsTheBodyToTheSeat() {
        double[] to = WishCapture.holdAt(ORIGIN[0], ORIGIN[1], ORIGIN[2], SEAT,
                SEAT[0] + 3.0, SEAT[1], SEAT[2]);
        assertNotNull(to, "three blocks off the seat is a drift and must be corrected");
        assertArrayEquals(SEAT, to, 1e-9,
                "corrected back to the SEAT, not to the origin — the freeze must not undo the "
                        + "framing it was asked to hold");
    }

    /**
     * The behaviour that was always right and must survive the fix: before anything has aimed the
     * camera, a child who wanders is put back where he was standing.
     */
    @Test
    @DisplayName("with nothing aimed yet, a wandering child is put back at his own spot")
    void withoutAnAimItHoldsTheOrigin() {
        double[] to = WishCapture.holdAt(ORIGIN[0], ORIGIN[1], ORIGIN[2], null,
                ORIGIN[0] + 4.0, ORIGIN[1], ORIGIN[2]);
        assertNotNull(to, "four blocks is a walk, not float noise");
        assertArrayEquals(ORIGIN, to, 1e-9, "no aim recorded -> the origin is the anchor");
    }

    @Test
    @DisplayName("…and a body already at its anchor is never teleported")
    void anchorNoiseIsNotADrift() {
        assertNull(WishCapture.holdAt(ORIGIN[0], ORIGIN[1], ORIGIN[2], null,
                        ORIGIN[0], ORIGIN[1], ORIGIN[2]),
                "exactly on the spot");
        double tiny = Math.sqrt(WishCapture.DRIFT_SQ) / 2.0;
        assertNull(WishCapture.holdAt(ORIGIN[0], ORIGIN[1], ORIGIN[2], null,
                        ORIGIN[0] + tiny, ORIGIN[1], ORIGIN[2]),
                "inside the drift threshold: a teleport every five ticks over float noise would "
                        + "rubber-band a child who is standing perfectly still");
    }

    /**
     * <b>The threshold is smaller than a step and bigger than the noise a teleport lands with.</b>
     *
     * <p>Pinned because both directions have a real cost: raise it and a child can lean out of his
     * own portrait, lower it and he stutters in place for the whole burst.
     */
    @Test
    @DisplayName("the drift threshold is under a seventh of a block")
    void theDriftThresholdIsTight() {
        assertEquals(0.02, WishCapture.DRIFT_SQ, 1e-9);
        double blocks = Math.sqrt(WishCapture.DRIFT_SQ);
        org.junit.jupiter.api.Assertions.assertTrue(blocks > 0.01 && blocks < 0.5,
                "a drift threshold of " + blocks + " blocks is either float noise or a stride");
    }

    /**
     * <b>Height counts.</b> The film seat is above the child, and an anchor that only compared the
     * horizontal would have left the camera at his feet looking up at nothing.
     */
    @Test
    @DisplayName("a body at the right x/z and the wrong height is a drift")
    void heightIsPartOfTheDistance() {
        double[] to = WishCapture.holdAt(ORIGIN[0], ORIGIN[1], ORIGIN[2], SEAT,
                SEAT[0], SEAT[1] - 6.0, SEAT[2]);
        assertNotNull(to, "six blocks below the seat is a picture of the ground");
        assertArrayEquals(SEAT, to, 1e-9);
    }
}
