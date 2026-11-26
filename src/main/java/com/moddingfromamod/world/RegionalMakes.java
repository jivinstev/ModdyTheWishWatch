package com.moddingfromamod.world;

import java.util.UUID;

import com.moddingfromamod.wish.WishAsk;
import com.moddingfromamod.wishbuilder.MakeFacts;

/**
 * <b>The make limits, asked region first</b> (#182): each question goes to the makes of the region it is about
 * ({@link Regions#of(WishAsk)}: the place it carries, else where the player stands), and to the world's own
 * ({@code world}) when there is none or the region leaves them. Only used while some plug-in has a region
 * ({@link WorldMakes#get()}). A view, like every answer of {@link WorldMakes}: nothing is written anywhere.
 */
public final class RegionalMakes implements WorldMakes {

    private final WorldMakes world;

    RegionalMakes(WorldMakes world) {
        this.world = world;
    }

    private WorldMakes of(WorldRegion r) {
        WorldMakes m = r == null ? null : r.makes();
        return m != null ? m : world;
    }

    @Override public MakeView view(MakeFacts facts, WishAsk ask) { return of(Regions.of(ask)).view(facts, ask); }
    @Override public WorldLimits limits(UUID player) { return of(Regions.ofPlayer(player)).limits(player); }
    @Override public Landing landing(MakeFacts facts, WishAsk ask) { return of(Regions.of(ask)).landing(facts, ask); }
    @Override public void handedOver(HandedOver what) { of(Regions.ofPlayer(what == null ? null : what.player())).handedOver(what); }
    @Override public void notKept(NotKept what) { of(Regions.ofPlayer(what == null ? null : what.player())).notKept(what); }

    /** The limits for {@code ask}: its place's region's (a creature fitted where it stands), else as {@link #limits}. */
    WorldLimits limits(WishAsk ask) {
        return of(Regions.of(ask)).limits(ask.player());
    }

    /**
     * The limits a question is answered with, region first when regions exist: what {@code MakeGate} asks, so a
     * question carrying a place ({@link WishAsk#where}) is limited by that place's region. An internal step of the
     * core, not a hook.
     */
    public static WorldLimits limitsFor(WorldMakes answering, WishAsk ask) {
        return answering instanceof RegionalMakes r ? r.limits(ask) : answering.limits(ask.player());
    }
}
