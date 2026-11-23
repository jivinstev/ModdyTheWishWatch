package com.moddingfromamod.event.catching;

import java.util.function.Consumer;
import java.util.function.IntToDoubleFunction;

import com.moddingfromamod.event.EventWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * <b>What an event has caught this tick</b> (E3). Two ways of catching cover every event in
 * the plan: being INSIDE what it wrote (in the wave's water, under the flood, in the falling rock)
 * and being NEAR its middle (a quake, a blast). Everything else is when: {@link #between} and
 * {@link #every}.
 */
@FunctionalInterface
public interface Catch {

    void find(EventWriter w, int age, Consumer<Caught> out);

    /**
     * Anything whose feet or head is in a cell the event holds, pushed toward {@code yaw} (Minecraft's
     * yaw; {@link Float#NaN} for no direction), with a strength that may change over the event's
     * life (a wave weakens as it dies away).
     */
    static Catch inside(float yaw, IntToDoubleFunction strength) {
        double dx = Float.isNaN(yaw) ? 0 : -Math.sin(Math.toRadians(yaw));
        double dz = Float.isNaN(yaw) ? 0 : Math.cos(Math.toRadians(yaw));
        return (w, age, out) -> {
            AABB box = w.written(1.0);
            if (box == null) {
                return;
            }
            double s = strength.applyAsDouble(age);
            if (s <= 0) {
                return;
            }
            for (Entity e : w.level().getEntities((Entity) null, box, Catch::catchable)) {
                BlockPos feet = e.blockPosition();
                BlockPos head = BlockPos.containing(e.getX(), e.getEyeY(), e.getZ());
                if (w.ours(feet) || w.ours(head)) {
                    out.accept(new Caught(e, s, dx, dz));
                }
            }
        };
    }

    /** Anything within {@code reach} of {@code centre}, strongest at the middle, pushed outward. */
    static Catch near(BlockPos centre, double reach) {
        Vec3 c = Vec3.atCenterOf(centre);
        return (w, age, out) -> {
            for (Entity e : w.level().getEntities((Entity) null, new AABB(centre).inflate(reach),
                    Catch::catchable)) {
                double dx = e.getX() - c.x;
                double dz = e.getZ() - c.z;
                double d = Math.sqrt(dx * dx + dz * dz + Math.pow(e.getY() - c.y, 2));
                double s = CatchRules.falloff(d, reach);
                if (s <= 0) {
                    continue;
                }
                double flat = Math.sqrt(dx * dx + dz * dz);
                out.accept(new Caught(e, s, flat < 1e-6 ? 0 : dx / flat, flat < 1e-6 ? 0 : dz / flat));
            }
        };
    }

    /** Only from tick {@code from} up to (not including) {@code to}. */
    default Catch between(int from, int to) {
        return (w, age, out) -> {
            if (age >= from && age < to) {
                find(w, age, out);
            }
        };
    }

    /** Only one tick in {@code n} (a quake's jolts, a blast's single moment with {@code between}). */
    default Catch every(int n) {
        return (w, age, out) -> {
            if (n <= 1 || age % n == 0) {
                find(w, age, out);
            }
        };
    }

    /** Spectators are never caught; nor is anything already gone. */
    static boolean catchable(Entity e) {
        return e.isAlive() && !(e instanceof Player p && p.isSpectator());
    }
}
