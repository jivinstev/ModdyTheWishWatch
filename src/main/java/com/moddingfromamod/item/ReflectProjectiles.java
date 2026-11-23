package com.moddingfromamod.item;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * <b>A blocked arrow goes back where it came from.</b>
 *
 * <p>Vanilla already turns a blocked arrow round, at a fifth of its speed, so it drops at his feet
 * still belonging to whoever fired it. The {@code reflect} effect asks for more: the arrow goes back
 * the way he is FACING, at full speed, and it is HIS now, so it can hit the one who fired it. An
 * arrow's motion cannot be reversed by a command, so the effect's function only tags him
 * ({@code mfam_reflect}) and this does the turning, right after that function runs.
 *
 * <p>The tag is always taken off, reflected or not, so a {@code reflect} wish on something that is
 * not a shield cannot leave it on him for the next block.
 */
public final class ReflectProjectiles {

    /** E1's label (tools/parts-coverage.py reads this set): the behaviour this class grants. */
    public static final Set<String> PARTS = Set.of("reflect_projectiles");

    /** The tag the {@code reflect} effect puts on the one who blocked. */
    public static final String TAG = "mfam_reflect";

    /** How fast the arrow goes back, in blocks a tick — a full bow draw is about 3. */
    static final double SPEED = 2.0;

    private ReflectProjectiles() {
    }

    /** Run after a wished shield's function: reflect what he blocked if it asked, then clear the ask. */
    public static void afterBlock(LivingEntity blocker, Entity blocked) {
        if (!blocker.getTags().contains(TAG)) {
            return;
        }
        blocker.removeTag(TAG);
        if (!(blocked instanceof Projectile p) || p.isRemoved()) {
            return;
        }
        Vec3 look = blocker.getLookAngle();
        p.setOwner(blocker);
        p.setPos(blocker.getX() + look.x, blocker.getEyeY() - 0.1 + look.y, blocker.getZ() + look.z);
        p.setDeltaMovement(look.scale(SPEED));
        p.hasImpulse = true;
    }
}
