package com.moddingfromamod.event;

/**
 * <b>May an event write this cell?</b> (E1). Pure: the Minecraft side reads the facts, this
 * decides.
 *
 * <p>An event protects a child's builds by what it is ALLOWED to replace, not by tracking what he
 * built: planks, bricks, glass, wool and everything else a build is made of are never on the list.
 * Every rule here was a bug in an earlier mod.
 */
public final class EventGuard {

    private EventGuard() {
    }

    /** What an event may replace. */
    public enum Policy {
        /** Only open space: air and replaceable blocks (grass, flowers, snow layers). A wave, a flood,
         *  a flying body. Walls are flowed around, never through. */
        INTO_OPEN,
        /** Open space, and natural terrain too (dirt, stone, sand, gravel…). A crater or a crack
         *  that puts itself back afterwards. */
        TERRAIN,
        /** Anything solid, his builds included: a crater, a crack or a slide that STAYS (a
         *  disaster that does not restore itself is a major event, and may break what
         *  he built). The hard guards still hold, so a chest, a fluid, bedrock and an unloaded chunk
         *  are never touched, and every block is journaled, so undo rebuilds his house exactly. */
        EVERYTHING
    }

    /** What a digging event (crater, crack, slide) may replace: his builds only when it stays. */
    public static Policy digging(boolean restores) {
        return restores ? Policy.TERRAIN : Policy.EVERYTHING;
    }

    /** The facts about one cell. */
    public record Cell(boolean loaded, boolean aboveFloor, boolean blockEntity, boolean unbreakable,
                       boolean fluid, boolean open, boolean natural) {
    }

    /** Why a cell was refused, or null when it may be written. */
    public static String refusal(Cell c, Policy p) {
        if (!c.loaded()) {
            return "unloaded"; // never generate or write a chunk nobody is near
        }
        if (!c.aboveFloor()) {
            return "floor"; // a crater once dug out of the bottom of the world
        }
        if (c.blockEntity()) {
            return "block entity"; // a chest's contents would be deleted
        }
        if (c.unbreakable()) {
            return "unbreakable"; // bedrock, barriers, end portals
        }
        if (c.fluid()) {
            return "fluid"; // the sea stays the sea; a flood never eats a lake
        }
        if (c.open()) {
            return null;
        }
        if (p == Policy.EVERYTHING || (p == Policy.TERRAIN && c.natural())) {
            return null;
        }
        return "not " + (p == Policy.TERRAIN ? "open or natural" : "open");
    }

    public static boolean mayWrite(Cell c, Policy p) {
        return refusal(c, p) == null;
    }
}
