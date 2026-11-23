package com.moddingfromamod.event;

/**
 * <b>Where an event is, from where he stands</b> (E1). Pure.
 *
 * <p>An event is announced with the direction to look ("…90 blocks north-east") and the camera is
 * turned toward it, because a player's own report of an earlier mod's meteor was "we can never see where it's
 * crashing down". Minecraft's axes: +X is east, +Z is SOUTH, yaw 0 faces south and grows clockwise
 * from above, pitch is positive looking DOWN.
 */
public final class EventAim {

    private EventAim() {
    }

    private static final String[] EIGHT = {
            "south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};

    /** The eight-way compass word for a displacement; "here" when there is none. */
    public static String compass(double dx, double dz) {
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) {
            return "here";
        }
        double yaw = yaw(dx, dz);
        int i = (int) Math.floorMod(Math.round(yaw / 45.0), 8);
        return EIGHT[i];
    }

    /** The yaw that faces along (dx, dz). */
    public static float yaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** The pitch that looks along (dx, dy, dz): positive looks down. */
    public static float pitch(double dx, double dy, double dz) {
        return (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
    }
}
