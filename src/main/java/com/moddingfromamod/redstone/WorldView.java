package com.moddingfromamod.redstone;

/**
 * What the planner may know about the world, without Minecraft types. The tool fills it from the level; tests fill
 * it from a map.
 */
public interface WorldView {

    /** How the planner may treat a block. */
    enum Kind {
        /** Air (or a cave pocket): free. */
        AIR,
        /** Natural ground (stone, dirt, grass, sand, gravel, deepslate, …): may be dug and replaced. */
        NATURAL,
        /** A full solid block that is not natural ground (part of a build): replaced only where the plan says so. */
        SOLID,
        /** Any other block (glass, slabs, stairs, plants, doors, torches…): never used as support. */
        OTHER,
        /** Water or lava, or a waterlogged block: kept away from every wire. */
        FLUID,
        /** Never touched: block entities (chests, furnaces…), existing redstone, bedrock, portals. */
        LOCKED
    }

    Kind kind(RsPos p);

    /** The block's id, for reports and for matching a wall's material. */
    String blockId(RsPos p);

    /** True when the block conducts redstone power (a full, opaque, solid block). */
    default boolean conductor(RsPos p) {
        Kind k = kind(p);
        return k == Kind.NATURAL || k == Kind.SOLID;
    }
}
