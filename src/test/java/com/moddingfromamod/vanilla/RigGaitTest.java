package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * <b>Which layer a creature is in, from what its carrier is doing</b>.
 *
 * <p>Each test drives the gait with a scripted carrier and reads the weights it would draw with.
 * The controls are the ones the issues name, because each is a way the feature could be on when it
 * must not be: a walker on flat ground never jumps, a creature on land never swims, a winged
 * creature on the ground never flaps at the flight rate, a creature nobody told to stay never sits.
 */
class RigGaitTest {

    private static RigGait.Sense ground(boolean staying, boolean moving) {
        return new RigGait.Sense(true, false, false, false, false, false, false, staying, moving,
                false, false, false, moving ? 0.1 : 0, -0.08, 0);
    }

    private static RigGait.Sense air(boolean flies, boolean winged, double vy) {
        return new RigGait.Sense(false, false, false, flies, winged, false, false, false, true,
                false, false, false, 0.1, vy, 0);
    }

    @Test
    void aWalkerOnFlatGroundNeverJumpsOrFlies() {
        RigGait g = new RigGait();
        for (int i = 0; i < 400; i++) {
            g.tick(ground(false, true));
            RigLocomotion.Weights w = g.weights();
            assertEquals(0f, w.jump());
            assertEquals(0f, w.fly());
            assertEquals(0f, w.swim());
            assertEquals(0f, w.sit());
        }
    }

    @Test
    void aJumpRunsFromTakeOffToTheTopToTheLandingThenEasesOut() {
        RigGait g = new RigGait();
        g.tick(ground(false, true));
        double vy = 0.42;
        float last = -1f;
        int air = 0;
        while (air < 40) {
            g.tick(air(false, false, vy));
            RigLocomotion.Weights w = g.weights();
            assertTrue(w.jumpProgress() >= last, "the jump's clock ran backwards");
            last = w.jumpProgress();
            vy = (vy - 0.08) * 0.98;
            air++;
            if (vy < -0.42) {
                break;
            }
        }
        assertTrue(last > 0.9f, "it should have come down: " + last);
        float jump = g.weights().jump();
        assertTrue(jump > 0.5f);
        g.tick(ground(false, true));
        assertTrue(g.weights().crouch() > 0f, "a landing is taken in the legs");
        float before = g.weights().jump();
        g.tick(ground(false, true));
        assertTrue(g.weights().jump() < before && g.weights().jump() > 0f,
                "and the jump eases out rather than snapping off");
    }

    @Test
    void aGroundedWingedCreatureDoesNotFlyAndAFlierDoesOnTheFirstTicks() {
        RigGait walker = new RigGait();
        for (int i = 0; i < 100; i++) {
            walker.tick(new RigGait.Sense(true, false, false, false, true, false, false, false,
                    true, false, false, false, 0.1, 0, 0));
        }
        assertEquals(0f, walker.weights().fly());
        RigGait flier = new RigGait();
        for (int i = 0; i < RigGait.FLY_IN; i++) {
            flier.tick(air(true, true, 0.0));
        }
        assertEquals(1f, flier.weights().fly());
    }

    @Test
    void aWingedWalkerSpreadsItsWingsOnlyOnceItHasBeenFallingAWhile() {
        RigGait g = new RigGait();
        g.tick(ground(false, false));
        for (int i = 0; i < RigGait.FALL_FLAP_TICKS - 1; i++) {
            g.tick(air(false, true, -0.3));
            assertEquals(0f, g.weights().fly(), "a step down is not a flight");
        }
        for (int i = 0; i < 6; i++) {
            g.tick(air(false, true, -0.3));
        }
        assertTrue(g.weights().fly() > 0.9f, "falling a while, it flaps like a chicken");
    }

    @Test
    void itSwimsOnlyInWaterAndOffTheBottom() {
        RigGait wading = new RigGait();
        RigGait swimming = new RigGait();
        for (int i = 0; i < 30; i++) {
            wading.tick(new RigGait.Sense(true, true, false, false, false, false, false, false,
                    true, false, false, false, 0.1, 0, 0));
            swimming.tick(new RigGait.Sense(false, true, true, false, false, false, false, false,
                    true, false, false, false, 0.1, 0.05, 0));
        }
        assertEquals(0f, wading.weights().swim(), "wading on the bottom is walking");
        assertEquals(1f, swimming.weights().swim());
        assertTrue(swimming.weights().travelPitch() > 10f, "rising, it points its nose up");
    }

    @Test
    void toldToStayItSitsAndGetsUpWhenItWalksOrIsHurt() {
        RigGait g = new RigGait();
        for (int i = 0; i < 60; i++) {
            g.tick(ground(true, false));
        }
        assertEquals(1f, g.weights().sit());
        RigGait free = new RigGait();
        for (int i = 0; i < 60; i++) {
            free.tick(ground(false, false));
        }
        assertEquals(0f, free.weights().sit(), "a creature nobody told to stay keeps standing");
        g.tick(new RigGait.Sense(true, false, false, false, false, false, false, true, false,
                false, true, false, 0, -0.08, 0));
        float s = g.weights().sit();
        assertTrue(s < 1f && s > 0f, "hurt, it starts to get up -- eased: " + s);
        for (int i = 0; i < 20; i++) {
            g.tick(ground(true, false));
        }
        assertEquals(0f, g.weights().sit(), "and stays up while startled");
    }

    @Test
    void aLongSitBecomesALieDown() {
        RigGait g = new RigGait();
        for (int i = 0; i < RigGait.LIE_AFTER + 100; i++) {
            g.tick(ground(true, false));
        }
        assertEquals(1f, g.weights().lie());
        assertEquals(0f, g.weights().sit());
    }

    @Test
    void aNapperSleepsAtNightAndNotByDayAndOnlyANapper() {
        RigGait napper = new RigGait();
        RigGait other = new RigGait();
        RigGait byDay = new RigGait();
        for (int i = 0; i < 120; i++) {
            napper.tick(new RigGait.Sense(true, false, false, false, false, false, true, false,
                    false, true, false, false, 0, -0.08, 0));
            other.tick(new RigGait.Sense(true, false, false, false, false, false, false, false,
                    false, true, false, false, 0, -0.08, 0));
            byDay.tick(new RigGait.Sense(true, false, false, false, false, false, true, false,
                    false, false, false, false, 0, -0.08, 0));
        }
        assertEquals(1f, napper.weights().sleep());
        assertEquals(0f, other.weights().sleep());
        assertEquals(0f, byDay.weights().sleep());
    }

    @Test
    void aHopperGathersThenLaunchesWhenItWantsToMove() {
        RigGait g = new RigGait();
        RigGait.Sense go = new RigGait.Sense(true, false, false, false, false, true, false, false,
                true, false, false, false, 0.05, -0.08, 0);
        int holds = 0;
        RigGait.Action a = RigGait.Action.NONE;
        for (int i = 0; i < 10 && a != RigGait.Action.LAUNCH; i++) {
            a = g.tick(go);
            if (a == RigGait.Action.HOLD) {
                holds++;
            }
        }
        assertEquals(RigGait.Action.LAUNCH, a);
        assertEquals(RigGait.GATHER_TICKS, holds, "it gathers before it leaps");
        assertTrue(g.weights().crouch() > 0.5f, "and the gather is a crouch");
        RigGait still = new RigGait();
        for (int i = 0; i < 40; i++) {
            assertEquals(RigGait.Action.NONE, still.tick(new RigGait.Sense(true, false, false,
                    false, false, true, false, false, false, false, false, false, 0, -0.08, 0)),
                    "a hopper that does not want to go anywhere stays put");
        }
    }
}
