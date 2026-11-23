package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M2 — the whole-body modifier. Pure joml, so the composition rules that make it safe to stack on
 * top of a clip pose are a unit test rather than something to notice in a client.
 */
class RigTransformFlopTest {

    private static RigTransform.Placed placed(float tx, float ty, float tz) {
        return new RigTransform.Placed(new Vector3f(tx, ty, tz), new Quaternionf(),
                new Vector3f(2, 3, 4), new Quaternionf());
    }

    @Test
    @DisplayName("no rotation changes nothing — a live creature pays nothing for the death path")
    void identityIsANoOp() {
        RigTransform.Placed p = placed(1, 2, 3);
        RigTransform.Placed q = RigTransform.about(p, new Quaternionf());
        assertEquals(p.translation(), q.translation());
        assertEquals(p.scale(), q.scale());
        assertTrue(q.leftRotation().equals(p.leftRotation(), 1e-6f));
    }

    @Test
    @DisplayName("it rotates the OFFSET as well as the orientation — otherwise the head stays put")
    void itMovesTheTranslationToo() {
        // A bone a metre above the feet must END UP a metre to the side, not still overhead with
        // its own box turned. Getting only the rotation right gives a creature that shears rather
        // than falls, and it is the mistake a naive "just spin each display" makes.
        RigTransform.Placed head = placed(0, 1, 0);
        Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(90));
        Vector3f t = RigTransform.about(head, q).translation();
        assertEquals(-1f, t.x, 1e-5f, "+Y about +Z by 90 degrees goes to -X");
        assertEquals(0f, t.y, 1e-5f);
        assertEquals(0f, t.z, 1e-5f);
    }

    @Test
    @DisplayName("scale and the right rotation are untouched, which is what makes it composable")
    void itLeavesTheClipsWorkAlone() {
        RigTransform.Placed p = placed(1, 2, 3);
        RigTransform.Placed q = RigTransform.about(p,
                new Quaternionf().rotateZ((float) Math.toRadians(37)));
        assertEquals(p.scale(), q.scale(), "a flop must not resize the creature");
        assertTrue(q.rightRotation().equals(p.rightRotation(), 1e-6f));
    }

    @Test
    @DisplayName("two modifiers compose like the rotations do — no drift, no re-derivation")
    void itComposes() {
        RigTransform.Placed p = placed(0.5f, 1.5f, -0.25f);
        Quaternionf a = new Quaternionf().rotateZ((float) Math.toRadians(30));
        Quaternionf b = new Quaternionf().rotateZ((float) Math.toRadians(60));
        Vector3f twice = RigTransform.about(RigTransform.about(p, a), b).translation();
        Vector3f once = RigTransform.about(p, new Quaternionf(b).mul(a)).translation();
        assertEquals(once.x, twice.x, 1e-5f);
        assertEquals(once.y, twice.y, 1e-5f);
        assertEquals(once.z, twice.z, 1e-5f);
    }

    @Test
    @DisplayName("a full flop lays a standing bone flat, and the feet stay where they are")
    void aFullFlopLaysItFlat() {
        // The pivot is the display origin, which for this rig IS the carrier's feet -- so a bone
        // at the origin must not move at all however far the body goes over.
        RigTransform.Placed foot = placed(0, 0, 0);
        Quaternionf full = new Quaternionf()
                .rotateZ((float) Math.toRadians(RigFlop.FLIP_DEGREES));
        Vector3f t = RigTransform.about(foot, full).translation();
        assertEquals(0f, t.length(), 1e-6f, "the feet are the pivot and must not slide");

        RigTransform.Placed top = placed(0, 4.5f, 0);
        Vector3f flat = RigTransform.about(top, full).translation();
        assertEquals(0f, flat.y, 1e-4f, "the head of a fully flopped creature is at ground level");
        assertEquals(4.5f, Math.abs(flat.x), 1e-4f, "and its height has become its length");
    }
}
