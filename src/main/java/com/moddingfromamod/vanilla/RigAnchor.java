package com.moddingfromamod.vanilla;

import com.moddingfromamod.runtime.CreatureAnatomy;
import net.minecraft.world.phys.Vec3;

/**
 * <b>Q3 — where an ability comes out of, on either renderer.</b>
 *
 * <p>{@code CreatureAnatomy} already answers the hard half: it turns the bone tree into {@code up}
 * and {@code forward} in BLOCKS at the creature's live scale, in the frame a datapack uses, so the
 * pair maps straight onto {@code positioned ^0 ^up ^forward}. That arithmetic is pure, reads
 * geometry only, and knows nothing about how the creature is drawn — which is exactly why the spike
 * predicts Q3 costs nothing.
 *
 * <p>This is the remaining half: turning that pair into a world point given where the creature
 * actually is and which way it is facing. It is the ONLY place the two renderers can disagree, so
 * it is deliberately one function that both arms call, with the entity's live position and yaw as
 * its only inputs. If the delta is ever non-zero, the cause is that the two entities are not in the
 * same place or not facing the same way — not the anatomy.
 *
 * <p><b>Facing.</b> A sculpted model faces model -Z, and Minecraft yaw 0 also faces +Z... which is
 * why the forward vector below is {@code (-sin y, 0, cos y)} — the standard Minecraft
 * {@code Entity.getLookAngle} horizontal, with {@code CreatureAnatomy} having already folded the
 * model's -Z convention into the sign of {@code forward}. Deriving it here rather than typing a
 * literal is the same rule {@code docs/CAMERA.md} draws out of the train-axis bug: a framing input
 * a feature can vary must be derived.
 */
public final class RigAnchor {

    private RigAnchor() {
    }

    /**
     * The world point an ability should fire from.
     *
     * @param feet    the creature's position — Minecraft entity positions are at the feet, on both
     *                a {@code WishMobEntity} and any vanilla base, which is what makes the two
     *                comparable at all
     * @param yawDeg  the creature's body yaw in degrees
     * @param lm      the landmark, already in blocks at the creature's live scale
     */
    public static Vec3 emissionPoint(Vec3 feet, float yawDeg, CreatureAnatomy.Landmark lm) {
        double rad = Math.toRadians(yawDeg);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        return new Vec3(feet.x + fx * lm.forward(),
                feet.y + lm.up(),
                feet.z + fz * lm.forward());
    }
}
