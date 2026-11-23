package com.moddingfromamod.event.motion;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;
import com.moddingfromamod.event.shape.FallShape;
import com.mojang.math.Transformation;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * <b>A falling body drawn as display entities instead of blocks</b> (E7b).
 *
 * <p>The first falling body (a block ball, now gone) rewrote a hollow ball of real blocks every tick, and the client
 * re-meshed every chunk section the ball passed through: a film showed it as a stack of old balls
 * left behind, then as a choppy small one. A {@code block_display} is ONE entity: the client eases it
 * between server positions over {@code teleport_duration} and eases its tumble over
 * {@code interpolation_duration}, and its size costs nothing, so it can be big again.
 *
 * <p>It follows the same {@link FallShape} slant, keeps the same smoke-and-flame trail, and on
 * arrival discards its displays and calls {@code onArrive} (the crater or splash, still in real
 * blocks so undo gives the ground back). The displays are spawned through {@link EventWriter#spawn},
 * so they carry the event's tag and an undo mid-fall takes them away too.
 */
public final class DisplayBodyMotion implements EventMotion {

    /** One cube of the body: its block, its centre relative to the body's centre, and its edge. */
    public record Part(BlockState state, float x, float y, float z, float size) {
    }

    /** Ticks the client takes to glide to each new position: 3 is how it eases a vanilla mob. */
    public static final int GLIDE_TICKS = 3;

    private final List<Part> parts;
    private final float radius;
    private final FallShape.Point start;
    private final FallShape.Point target;
    private final int ticks;
    private final float spinPerTick;
    private final Vector3f spinAxis;
    private final List<Entity> displays = new ArrayList<>();
    private Runnable onArrive;

    public DisplayBodyMotion(List<Part> parts, float radius, float spinPerTick,
                             FallShape.Point viewer, FallShape.Point target) {
        this.parts = List.copyOf(parts);
        this.radius = Math.max(0.5f, radius);
        this.spinPerTick = spinPerTick;
        this.target = target;
        this.start = FallShape.start(viewer, target);
        this.ticks = FallShape.ticks(start, target);
        // Tumble about an axis across the direction of travel, so it rolls forward as it comes in.
        Vector3f travel = new Vector3f((float) (target.x() - start.x()), 0f, (float) (target.z() - start.z()));
        Vector3f axis = travel.lengthSquared() < 1e-6f ? new Vector3f(1, 0, 0)
                : new Vector3f(travel.z, 0.35f, -travel.x);
        this.spinAxis = axis.normalize();
    }

    public DisplayBodyMotion onArrive(Runnable r) {
        this.onArrive = r;
        return this;
    }

    public FallShape.Point start() {
        return start;
    }

    public int ticks() {
        return ticks;
    }

    /** The live displays (for tests): empty before the first tick and after arrival. */
    public List<Entity> displays() {
        return List.copyOf(displays);
    }

    /**
     * The pose of one cube at this tumble angle. A display draws its block as the unit cube scaled
     * by S, rotated by L, then moved by T: v' = T + L·(S·v). We want each vertex at
     * R·(offset + size·(v − ½)), so L = R, S = size and T = R·(offset − ½·size).
     */
    static Transformation pose(Part p, Quaternionf spin) {
        Vector3f corner = new Vector3f(p.x() - p.size() / 2f, p.y() - p.size() / 2f, p.z() - p.size() / 2f);
        Vector3f t = new Quaternionf(spin).transform(corner);
        return new Transformation(t, new Quaternionf(spin), new Vector3f(p.size(), p.size(), p.size()),
                new Quaternionf());
    }

    private void spawn(EventWriter w) {
        for (Part p : parts) {
            Entity d = EntityType.BLOCK_DISPLAY.create(w.level(), EntitySpawnReason.COMMAND);
            if (d == null) {
                continue;
            }
            d.snapTo(start.x(), start.y(), start.z(), 0f, 0f);
            CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
            tag.put("block_state", NbtUtils.writeBlockState(p.state()));
            tag.putInt("teleport_duration", GLIDE_TICKS);
            // Seen from as far as the sky it falls out of; the default range culls it mid-fall.
            tag.putFloat("view_range", 8f);
            CompoundTag light = new CompoundTag();
            light.putInt("block", 15);
            light.putInt("sky", 15);
            tag.put("brightness", light);
            com.moddingfromamod.compat.EntityNbt.load(d, tag);
            w.spawn(d);
            displays.add(d);
        }
    }

    private void trail(EventWriter w, FallShape.Point c, int age) {
        double bx = start.x() - target.x(), by = start.y() - target.y(), bz = start.z() - target.z();
        double len = Math.max(1e-3, Math.sqrt(bx * bx + by * by + bz * bz));
        double back = radius + 1.5;
        double x = c.x() + bx / len * back, y = c.y() + by / len * back, z = c.z() + bz / len * back;
        w.level().sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x, y, z, 3, radius * 0.4, radius * 0.4, radius * 0.4, 0.01);
        w.level().sendParticles(ParticleTypes.FLAME, c.x(), c.y(), c.z(), 12, radius * 0.7, radius * 0.7, radius * 0.7, 0.02);
        if (age % 10 == 0) {
            w.level().playSound(null, c.x(), c.y(), c.z(), SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 3.0f, 0.5f);
        }
    }

    @Override
    public boolean step(EventWriter w, int age) {
        boolean arrived = age >= ticks;
        if (age == 0) {
            spawn(w);
        }
        if (!arrived) {
            FallShape.Point c = FallShape.at(start, target, age / (double) ticks);
            trail(w, c, age);
            Quaternionf spin = new Quaternionf().rotationAxis(spinPerTick * age, spinAxis);
            for (int i = 0; i < displays.size(); i++) {
                Entity d = displays.get(i);
                d.snapTo(c.x(), c.y(), c.z(), 0f, 0f);
                var pose = (com.moddingfromamod.mixin.DisplayPoseInvoker) d;
                pose.moddingfromamod$setTransformation(pose(parts.get(i), spin));
                pose.moddingfromamod$setTransformationInterpolationDuration(1);
                pose.moddingfromamod$setTransformationInterpolationDelay(0);
            }
            return false;
        }
        for (Entity d : displays) {
            d.discard();
        }
        displays.clear();
        w.level().sendParticles(ParticleTypes.EXPLOSION_EMITTER, target.x(), target.y(), target.z(), 1, 0, 0, 0, 0);
        w.level().playSound(null, target.x(), target.y(), target.z(), SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.BLOCKS, 4.0f, 0.7f);
        if (onArrive != null) {
            onArrive.run();
        }
        return true;
    }
}
