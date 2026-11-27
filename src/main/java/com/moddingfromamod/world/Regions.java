package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishAsk;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Which region answers, and for what</b> (#182): the one lookup behind {@link WorldRegions}. The core's questions
 * ({@code WishCharge.get()}, {@link WorldMakes#get()}, the reach checks) come here first; with no region registered,
 * or none at the spot, the answer is the world's module, exactly as before.
 *
 * <p><b>A region is chosen by a PLACE, never by who is asking.</b>
 * <ul>
 *   <li>a wish: the spot it was made at ({@link #ofWish}), the same spot from submit to refund, so the region that
 *       charged it is the one that refunds it, even after he has walked off;</li>
 *   <li>a question with a place on it ({@link WishAsk#where()}: a living creature being fitted) that place;</li>
 *   <li>any other question about a player (a menu, a make he is pressing): where he stands now ({@link #ofPlayer});</li>
 *   <li>reach: the caster's region, else, for a caster in none, the region his wish is aimed into ({@link #reachFor}).</li>
 * </ul>
 *
 * <p><b>Overlap:</b> the first registered {@link WorldRegions} whose {@code at} answers wins (plug-in registration
 * order); within one plug-in, the one its {@code at} returns. <b>Cost:</b> with no plug-in that has a region
 * ({@link #any} false) nothing is wrapped and nothing else here is asked.
 */
public final class Regions {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID);

    private Regions() {
    }

    /** The registered region plug-ins that have a region now, in registration order. */
    private static List<WorldRegions> live() {
        List<WorldRegions> registered = Plugins.shared().all(WorldRegions.class);
        if (registered.isEmpty()) {
            return List.of();
        }
        List<WorldRegions> out = new ArrayList<>(registered.size());
        for (WorldRegions w : registered) {
            try {
                if (w.any()) {
                    out.add(w);
                }
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("a WorldRegions threw on any(): {}", e.toString());   // a broken plug-in has no region
            }
        }
        return out;
    }

    /** True when some plug-in has a region now. False: every lookup is skipped and nothing is wrapped. */
    public static boolean any() {
        return !live().isEmpty();
    }

    /** The region at this spot, or null: the first plug-in (in registration order) whose {@code at} answers. */
    public static WorldRegion at(String dimension, double x, double y, double z) {
        return dimension == null ? null : at(live(), dimension, x, y, z);
    }

    private static WorldRegion at(List<WorldRegions> live, String dimension, double x, double y, double z) {
        for (WorldRegions w : live) {
            try {
                WorldRegion r = w.at(dimension, x, y, z);
                if (r != null) {
                    return r;
                }
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("a WorldRegions threw at {} {} {} {}: {}", dimension, x, y, z, e.toString());
            }
        }
        return null;
    }

    /**
     * True when some region that says where it is ({@link WorldRegion#area}) covers any column of {@code area} in
     * {@code dimension}: what a world that lays things out (islands, plots) asks before it puts one there.
     */
    public static boolean overlaps(String dimension, Region area) {
        if (area == null || dimension == null) {
            return false;
        }
        for (WorldRegions w : live()) {
            List<WorldRegion> listed;
            try {
                listed = w.all();
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("a WorldRegions threw on all(): {}", e.toString());
                continue;
            }
            for (WorldRegion r : listed == null ? List.<WorldRegion>of() : listed) {
                Region a = r.area();
                if (a != null && dimension.equals(r.dimension()) && a.minX() <= area.maxX() && area.minX() <= a.maxX()
                        && a.minZ() <= area.maxZ() && area.minZ() <= a.maxZ()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The region the online player {@code id} stands in, or null (offline, or in none). */
    public static WorldRegion ofPlayer(UUID id) {
        List<WorldRegions> live = id == null ? List.of() : live();
        if (live.isEmpty()) {
            return null;
        }
        MinecraftServer s = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        ServerPlayer p = s == null ? null : s.getPlayerList().getPlayer(id);
        return p == null ? null : at(live, p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ());
    }

    /** The region {@code wish} was made in (its own spot, locked when it was made), or null. */
    public static WorldRegion ofWish(Wish wish) {
        List<WorldRegions> live = wish == null ? List.of() : live();
        if (live.isEmpty()) {
            return null;
        }
        return at(live, wish.dimension() == null || wish.dimension().isEmpty() ? Bases.OVERWORLD : wish.dimension(),
                wish.x(), wish.y(), wish.z());
    }

    /** The region a question is about: the place it carries ({@link WishAsk#where}), else where its player stands. */
    public static WorldRegion of(WishAsk ask) {
        if (ask == null) {
            return null;
        }
        Bases.Pos w = ask.where();
        return w != null ? at(w.dimension(), w.x(), w.y(), w.z()) : ofPlayer(ask.player());
    }

    /** The bases and reach rules a wish is judged by: a region's, else the world's. */
    public record Reach(Bases bases, WorldRules rules) {
    }

    /**
     * The reach a wish cast by {@code caster} and aimed at {@code anchor} (null: not aimed) is judged by: the region he
     * stands in, else the region the wish is aimed into, else the world's. So a wish cast inside a fenced region stays
     * in it, and one cast from outside into it is the region's to admit ({@code bases().baseOf(caster)}).
     */
    public static Reach reachFor(UUID caster, com.moddingfromamod.marshal.Spot anchor, Bases world, WorldRules rules) {
        if (caster == null || !any()) {
            return new Reach(world, rules);
        }
        WorldRegion r = fencing(ofPlayer(caster));
        if (r == null && anchor != null) {
            r = fencing(at(anchor.dimension() == null || anchor.dimension().isEmpty() ? Bases.OVERWORLD
                    : anchor.dimension(), anchor.x() + 0.5, anchor.y(), anchor.z() + 0.5));
        }
        return reach(r, world, rules);
    }

    /** As {@link #reachFor} with no aimed spot: a search round the caster ({@code WorldModuleHost.fenceFor}). */
    public static Reach reachFor(UUID caster, Bases world, WorldRules rules) {
        return reachFor(caster, null, world, rules);
    }

    /**
     * As {@link #reachFor}, for a command run at a spot (a replayed button, template or rule): the caster's region,
     * else the spot's (a standing rule with nobody near is the rule of the place it fires at).
     */
    public static Reach reachAt(UUID caster, String dimension, double x, double y, double z, Bases world,
                                WorldRules rules) {
        if (!any()) {
            return new Reach(world, rules);
        }
        WorldRegion r = fencing(ofPlayer(caster));
        if (r == null) {
            r = fencing(at(dimension, x, y, z));
        }
        return reach(r, world, rules);
    }

    /** The region if it answers for reach (it has bases), else null: a region that leaves reach to the world. */
    private static WorldRegion fencing(WorldRegion r) {
        try {
            return r != null && r.bases() != null ? r : null;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("a WorldRegion threw on bases(): {}", e.toString());
            return null;
        }
    }

    private static Reach reach(WorldRegion r, Bases world, WorldRules rules) {
        if (r == null) {
            return new Reach(world, rules);
        }
        WorldRules own = r.rules();
        return new Reach(r.bases(), own != null ? own : rules);
    }
}
