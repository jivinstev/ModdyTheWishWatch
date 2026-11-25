package com.moddingfromamod.world;

import java.util.UUID;

import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.wish.WishAsk;
import com.moddingfromamod.wishbuilder.MakeFacts;

/**
 * <b>What a world lets be made here, what it keeps for later, and how big</b> (#14). A documented extension point
 * ({@code docs/EXTENDING.md}, {@code WorldMakes}), read with {@code first}: the running module's own
 * ({@link WorldModule#makes}) first, else the first registered one, else {@link #NONE}, which allows everything (the
 * plain game, exactly as before).
 *
 * <p>The core asks it about one make at a time, given the make's {@link MakeFacts} and the same {@link WishAsk} a
 * charge is told, and the answer is a {@link MakeView}: allowed, hidden here, locked with the world's own words, or
 * fitted to limits. A world may also state limits up front ({@link #limits}) that hold for every make. The core never
 * compares the numbers itself except to fit a make to the limits a world gave; the world decides.
 *
 * <p>Everything here is a VIEW, computed when asked and never written anywhere: a thing on a child's shelf follows him
 * to other worlds, and each world sees it its own way. Asked on the server thread, often (a menu asks once per tile per
 * draw): keep both methods cheap and free of side effects. Default methods only.
 */
public interface WorldMakes {

    /** Nothing registered: every make is allowed. */
    WorldMakes NONE = new WorldMakes() {
    };

    /**
     * What this world says about the make {@code facts} describes, for {@code ask}. Default: allowed. A tag, number or
     * kind you do not know is best allowed: the vocabulary grows, and a world should never need to change when the
     * core adds Builder content.
     */
    default MakeView view(MakeFacts facts, WishAsk ask) {
        return MakeView.ALLOWED;
    }

    /**
     * The limits that hold for every make of {@code player}'s here, with a stamp that changes when they do. Default:
     * none. Joined with a {@link MakeView.State#FIT} answer's own limits, the tighter of each winning.
     */
    default WorldLimits limits(UUID player) {
        return WorldLimits.NONE;
    }

    /**
     * <b>Tell the core this player's limits changed</b> (a round began, a level was earned): every living creature his
     * wishes made is fitted again from its own unfitted numbers, its health kept as hurt as it was on a raise and held
     * to its new most on a lower, and he is told once for the change. The core never polls {@link #limits}: call this
     * whenever what it answers for {@code player} changes. Server thread; with no server running it does nothing.
     */
    static void limitsChanged(UUID player) {
        com.moddingfromamod.wish.LiveFit.refit(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer(), player);
    }

    /**
     * <b>Where a finished make goes</b> (#70), asked before its first placing step: {@link Landing#LANDS} (the default,
     * the plain game), or KEPT: made and kept on his shelf as usual, not placed, and put on his hotbar as a wish (or in
     * his Wish Bag when the hotbar is full), to be aimed and fired like any hotbar wish. {@link Landing#KEPT_AS_ITEM}
     * hands an item make over as the real item. Asked with the make's facts (as this world fits it) and the same
     * {@link WishAsk}; cheap and free of side effects. Today the core keeps a Builder or Apprentice creature, ride or
     * item; any other make lands as before (see {@code docs/EXTENDING.md}).
     */
    default Landing landing(MakeFacts facts, WishAsk ask) {
        return Landing.LANDS;
    }

    /** A kept make was handed over (#70): what it is and where it went, so the world can show it its own way. */
    default void handedOver(HandedOver what) {
    }

    /**
     * A make this world asked to keep was not kept (#70): it landed (no replay recipe, or a change to his thing), or a
     * kept Magician wish taught nothing to hand over. Told once per make, so a world can tell and react.
     */
    default void notKept(NotKept what) {
    }

    /** The one this world answers with. */
    static WorldMakes get() {
        WorldModule m = WorldModuleHost.liveModule();
        WorldMakes own = m == null ? null : m.makes();
        return own != null ? own : Plugins.shared().first(WorldMakes.class).orElse(NONE);
    }
}
