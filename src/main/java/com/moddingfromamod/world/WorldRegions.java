package com.moddingfromamod.world;

/**
 * <b>Regions of a world with rules of their own</b> (#182). A documented extension point ({@code docs/EXTENDING.md},
 * "WorldRegions"), read with {@code all}.
 *
 * <p>A world runs one {@link WorldModule}, and the core asks it three things about every wish: may it be made and what
 * does it cost ({@link com.moddingfromamod.wish.WishCharge}), how strong may it be ({@link WorldMakes}), and where may
 * it land ({@link Bases} plus the world's reach rules). A <b>region</b> answers the same three for a part of the world:
 * a battle arena built inside a survival world or an island world, a minigame zone, a protected square.
 *
 * <p>The core chooses the region by a place ({@link Regions}): a wish by the spot it was made at, a living creature by
 * where it stands, any other question about a player by where he stands, reach by the caster's region or, from none,
 * the region the wish is aimed into. Where no region answers, or a region's answer is {@code null}, the world's module
 * answers exactly as before.
 *
 * <p>{@link #at} is asked often (per wish, per menu draw, per reach check, and for each wish creature twice a second
 * while you have a region): keep it a cheap lookup, free of side effects. Server thread. Return {@code null} for a spot
 * no region of yours covers. Several plug-ins may each register one: where they overlap, the first registered that
 * answers wins.
 */
public interface WorldRegions {

    /** The region at this spot, or {@code null}. {@code dimension} is a dimension id ({@code minecraft:overworld}). */
    WorldRegion at(String dimension, double x, double y, double z);

    /**
     * True while this plug-in has at least one region in the running world. While none does, the core asks nobody and
     * answers exactly as it did before regions existed (a world with your jar installed and nothing built in it).
     * Asked as often as {@link #at}: answer from a field. When you add the first region or take away the last, every
     * wish creature is fitted again within half a second; when a region's limits change, call
     * {@code WorldMakes.limitsChanged(player)} for each player they hold, as a world's module does.
     */
    default boolean any() {
        return true;
    }

    /** Every region of yours in the running world, for {@link Regions#overlaps}; empty when you do not list them. */
    default java.util.List<WorldRegion> all() {
        return java.util.List.of();
    }
}
