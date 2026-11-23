package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link RigLaunch}: where a throw leaves a drawn body, and a flight that lands where it was aimed. */
class RigLaunchTest {

    private static RigLaunch.Part box(String name, String parent, double x0, double y0, double z0,
                                      double x1, double y1, double z1) {
        return new RigLaunch.Part(name, parent, x0, y0, z0, x1, y1, z1);
    }

    /** The fire buddy, in blocks: a body, a head, two arms, and a fireball under the right arm. */
    private static final List<RigLaunch.Part> FIRE_BUDDY = List.of(
            box("body", null, -0.4, 0.9, -0.2, 0.4, 1.8, 0.2),
            box("head", null, -0.4, 1.7, -0.4, 0.4, 2.5, 0.4),
            box("armL", null, -0.6, 1.0, -0.15, -0.3, 1.7, 0.15),
            box("armR", null, 0.3, 1.0, -0.15, 0.6, 1.7, 0.15),
            box("fireball", "armR", 0.3, 0.75, -0.15, 0.6, 1.05, 0.15),
            box("legL", null, -0.3, 0, -0.15, 0, 0.95, 0.15),
            box("legR", null, 0, 0, -0.15, 0.3, 0.95, 0.15));

    private static final double[] AHEAD = {0, 1, -7};

    @Test
    void aHeldFireballIsWhereTheThrowLeaves() {
        RigLaunch.Launch l = RigLaunch.from(FIRE_BUDDY, "minecraft:fire_charge", AHEAD);
        assertEquals("held", l.rule());
        assertEquals("fireball", l.part());
        assertEquals(0.45, l.x(), 1e-9);
        assertEquals(0.9, l.y(), 1e-9);
    }

    @Test
    void anItemsOwnNameCountsAsHeld() {
        List<RigLaunch.Part> parts = List.of(
                box("body", null, -0.4, 0.5, -0.4, 0.4, 1.5, 0.4),
                box("snowball_pile", null, 0.4, 1.0, -0.1, 0.6, 1.2, 0.1));
        assertEquals("snowball_pile", RigLaunch.from(parts, "minecraft:snowball", AHEAD).part());
    }

    @Test
    void withNothingHeldItIsTheHandEndOfTheArmNearestTheTarget() {
        List<RigLaunch.Part> parts = FIRE_BUDDY.stream().filter(p -> !p.name().equals("fireball")).toList();
        RigLaunch.Launch l = RigLaunch.from(parts, "", new double[]{-5, 1, 0});   // off to its left
        assertEquals("hand", l.rule());
        assertEquals("armL", l.part());
        assertTrue(l.y() < 1.2, "the free end of the arm, not its shoulder: " + l.y());
    }

    @Test
    void legsAreNeverTheHand() {
        // A cat calls its hind legs armL/armR (RigAnatomy's rule): a limb on the ground is no hand.
        List<RigLaunch.Part> parts = List.of(
                box("body", null, -0.3, 0.4, -0.6, 0.3, 0.8, 0.6),
                box("head", null, -0.25, 0.6, -0.9, 0.25, 1.0, -0.5),
                box("armL", null, -0.3, 0, 0.3, -0.1, 0.4, 0.5),
                box("legL", null, -0.3, 0, -0.5, -0.1, 0.4, -0.3));
        RigLaunch.Launch l = RigLaunch.from(parts, "", AHEAD);
        assertEquals("head", l.rule());
        assertEquals(-0.9, l.z(), 1e-9);   // the face it turns to the target
    }

    @Test
    void aMouthBeatsTheHead() {
        List<RigLaunch.Part> parts = List.of(
                box("body", null, -0.5, 0.5, -0.5, 0.5, 1.5, 0.5),
                box("head", null, -0.3, 1.4, -0.9, 0.3, 2.0, -0.3),
                box("jaw", "head", -0.2, 1.4, -1.0, 0.2, 1.55, -0.6));
        assertEquals("jaw", RigLaunch.from(parts, "", AHEAD).part());
    }

    @Test
    void aBodyWithNoHeadThrowsFromItsFront() {
        List<RigLaunch.Part> parts = List.of(box("blob", null, -0.5, 0, -0.5, 0.5, 1, 0.5));
        RigLaunch.Launch l = RigLaunch.from(parts, "", AHEAD);
        assertEquals("body", l.rule());
        assertEquals(-0.5, l.z(), 1e-9);
        assertEquals(0.7, l.y(), 1e-9);
    }

    @Test
    void aShortThrowStillTakesTheMinimumAndAFarOneIsNoSlower() {
        assertEquals(RigLaunch.MIN_FLIGHT_TICKS, RigLaunch.flightTicks(6));
        assertEquals(RigLaunch.MIN_FLIGHT_TICKS, RigLaunch.flightTicks(0.5));
        assertEquals((int) Math.ceil(20 / RigLaunch.CRUISE), RigLaunch.flightTicks(20));
    }

    @Test
    void theFlightLandsWhereItWasAimedInExactlyThatManyTicks() {
        double[][] cases = {{0, 1, 0, 6, 1.2, 0}, {0, 0.9, 0, 3, 2.5, -4}, {1, 2, 1, -15, 0, 9}};
        for (double[] c : cases) {
            double[] from = {c[0], c[1], c[2]};
            double[] to = {c[3], c[4], c[5]};
            int n = RigLaunch.flightTicks(Math.sqrt(Math.pow(to[0] - from[0], 2) + Math.pow(to[1] - from[1], 2)
                    + Math.pow(to[2] - from[2], 2)));
            double[] v = RigLaunch.velocity(from, to, n);
            double[] p = from.clone();
            // vanilla ThrowableProjectile.tick, 1.21.8: gravity, then drag, then the move
            for (int k = 0; k < n; k++) {
                v[1] -= RigLaunch.GRAVITY;
                for (int i = 0; i < 3; i++) {
                    v[i] *= RigLaunch.DRAG;
                    p[i] += v[i];
                }
            }
            for (int i = 0; i < 3; i++) {
                assertEquals(to[i], p[i], 1e-9, "axis " + i + " after " + n + " ticks");
            }
        }
    }
}
