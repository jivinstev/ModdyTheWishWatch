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

    /** The one this world answers with. */
    static WorldMakes get() {
        WorldModule m = WorldModuleHost.liveModule();
        WorldMakes own = m == null ? null : m.makes();
        return own != null ? own : Plugins.shared().first(WorldMakes.class).orElse(NONE);
    }
}
