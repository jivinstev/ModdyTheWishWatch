package com.moddingfromamod.world;

/**
 * <b>One region's answers</b> (#182, {@link WorldRegions}): the same three contracts a {@link WorldModule} answers, for
 * the part of the world this region covers. Each defaults to {@code null}, meaning "ask the world's module as usual",
 * so a region overrides only what it cares about. Part of the documented extension point {@link WorldRegions}.
 */
public interface WorldRegion {

    /**
     * A short id, unique among your regions while it stands ({@code arena/north}): the log's, and how the core tells
     * that a creature crossed from one region into another.
     */
    String id();

    /**
     * The columns this region covers, for a planner that must keep clear of it (a world that lays out islands or plots
     * skips a spot inside one); {@code null} when it does not say. {@link Regions#overlaps} reads it.
     */
    default Region area() {
        return null;
    }

    /** The dimension it is in ({@code minecraft:overworld} unless it says otherwise). */
    default String dimension() {
        return Bases.OVERWORLD;
    }

    /** Who may wish here and what it costs; {@code null}: the module's. */
    default com.moddingfromamod.wish.WishCharge charge() {
        return null;
    }

    /** How strong a make may be here; {@code null}: the module's. */
    default WorldMakes makes() {
        return null;
    }

    /**
     * Where a wish cast from here, or aimed in here from outside every region, may land: this region's {@link Bases} (a
     * caster's "base" is the area he may wish into; {@code baseOf} null for a player it does not admit) and the reach
     * rules they are judged by ({@link #rules}). {@code null}: the module's. The reach rule measures bases in the
     * overworld, so a fenced region elsewhere refuses every placed wish.
     */
    default Bases bases() {
        return null;
    }

    /**
     * The rules a wish judged by {@link #bases} is held to (base_reach and friends); {@code null}: the world's, or,
     * where the world has none, {@link WorldRules#CLOSED} (wishes stay on their base, nothing world-wide).
     */
    default WorldRules rules() {
        return null;
    }
}
