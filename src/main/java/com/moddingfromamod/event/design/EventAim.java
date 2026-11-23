package com.moddingfromamod.event.design;

import java.util.function.IntBinaryOperator;

import com.moddingfromamod.place.PlaceResolver;

/**
 * <b>Where an event aimed at a named place happens</b> — "a meteor hits my castle", "snow
 * over my tower", "a flood around my house", "a wave crashing over my base". Minecraft-free: the
 * world comes in as {@code ground} (the top block of a column), so every rule is a row in
 * {@code EventAimTest}.
 *
 * <p>An event is staged at a CENTRE, the top block of the ground it happens on, heading a yaw:
 * <ul>
 *   <li>{@code top} (also roof, over, on) — the roof block itself: a meteor lands ON the castle, a
 *   shower falls ON the house, a crater opens in its roof;</li>
 *   <li>{@code around} — the building's middle at the GROUND beside it, never its roof: a flood rises
 *   round it, a shower covers it and its yard;</li>
 *   <li>a room ({@code inside}) or a spot the building marked — the block under that spot;</li>
 *   <li>anything outside it (front, a side, beside, behind) or on a creature or him — the ground
 *   there.</li>
 * </ul>
 * A WAVE is the one motion with a direction that matters: it is born beyond the place, on the far
 * side from him, and rolls over the place toward him — the same "rears up and comes in" shot a wave
 * aimed at nothing gets, with the building in its path.
 */
public final class EventAim {

    private EventAim() {
    }

    /** The centre (top block of the ground) and heading. */
    public record Aim(int x, int y, int z, float yaw) {
    }

    /**
     * @param r        the resolved place
     * @param building whether the thing is a building
     * @param motion   the event's motion
     * @param childX   where he stands (NaN when nobody is there)
     * @param childYaw which way he faces
     * @param waveOut  how far a wave travels before it breaks (moving_front only)
     * @param ground   the top block's y at a column
     */
    public static Aim of(PlaceResolver.Resolved r, boolean building, EventDesign.Motion motion, double childX,
                         double childZ, float childYaw, int waveOut, IntBinaryOperator ground) {
        int x = (int) Math.floor(r.x()), z = (int) Math.floor(r.z());
        String spot = r.spot() == null ? "" : r.spot();
        int y;
        if (r.ring() > 0) {
            // The ground beside it, read on the ring on his side — the middle column is its roof.
            double a = Double.isNaN(childX) ? 0 : Math.atan2(-(childX - r.x()), childZ - r.z());
            double rx = r.x() - Math.sin(a) * r.ring(), rz = r.z() + Math.cos(a) * r.ring();
            // The TERRAIN there, not a height carried out from the middle: a hollow tower's floor
            // lifted its centre spot three blocks and the flood around it hung in the air (at-e3).
            y = ground.applyAsInt((int) Math.floor(rx), (int) Math.floor(rz));
        } else if (building && "top".equals(spot)) {
            y = (int) Math.floor(r.y()) - 1;
        } else if (building && !outside(spot)) {
            y = (int) Math.floor(r.y()) - 1;
        } else {
            y = ground.applyAsInt(x, z);
        }
        float yaw = childYaw;
        if (motion == EventDesign.Motion.MOVING_FRONT) {
            // Born beyond the place, rolling over it toward him.
            double dx = Double.isNaN(childX) ? 0 : r.x() - childX, dz = Double.isNaN(childX) ? 1 : r.z() - childZ;
            double len = Math.hypot(dx, dz);
            if (len < 1e-6) {
                double rad = Math.toRadians(childYaw);
                dx = -Math.sin(rad);
                dz = Math.cos(rad);
                len = 1;
            }
            dx /= len;
            dz /= len;
            int bx = (int) Math.floor(r.x() + dx * waveOut), bz = (int) Math.floor(r.z() + dz * waveOut);
            // Heading back toward him: yaw of (-dx, -dz). Minecraft: forward = (-sin yaw, cos yaw).
            yaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
            return new Aim(bx, ground.applyAsInt(bx, bz), bz, yaw);
        }
        return new Aim(x, y, z, yaw);
    }

    /** A spot that is only somewhere to stand BY a building. */
    static boolean outside(String spot) {
        return switch (spot) {
            case "front", "beside", "behind", "north", "east", "south", "west", "around" -> true;
            default -> false;
        };
    }
}
