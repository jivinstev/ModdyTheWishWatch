package com.moddingfromamod.world;

import java.util.List;
import java.util.UUID;

import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.plugin.PluginScope;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.wish.WishCharge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Which region answers</b> (#182), with no server: by a place, the first registered plug-in winning where regions
 * overlap, a plug-in with no region costing nothing, and reach judged by the caster's region or, from none, by the
 * region the wish is aimed into. The live half (a player, a creature walking in and out) is
 * {@code ContractRegionGameTest}.
 */
@ExtendWith(PluginScope.class)
class RegionsTest {

    private static final String OW = Bases.OVERWORLD;

    private static Bases bases(String noun) {
        return new Bases() {
            @Override public String noun() { return noun; }
            @Override public Base baseOf(UUID player) { return null; }
            @Override public UUID ownerAt(String d, double x, double z) { return null; }
            @Override public Pos positionOf(UUID player) { return null; }
        };
    }

    private static WorldRegion region(String id, Region area, Bases b) {
        return new WorldRegion() {
            @Override public String id() { return id; }
            @Override public Region area() { return area; }
            @Override public Bases bases() { return b; }
        };
    }

    private static WorldRegions plugin(WorldRegion r, boolean any) {
        return new WorldRegions() {
            @Override public WorldRegion at(String d, double x, double y, double z) {
                return OW.equals(d) && r.area().contains(x, z) ? r : null;
            }
            @Override public boolean any() { return any; }
            @Override public List<WorldRegion> all() { return List.of(r); }
        };
    }

    @Test
    void withNoRegionNothingIsWrappedOrAsked() {
        assertFalse(Regions.any());
        assertSame(WorldMakes.NONE, WorldMakes.get());
        assertFalse(WishCharge.get().getClass().getSimpleName().startsWith("Regional"));
        // a plug-in installed with nothing built: still nothing
        Plugins.shared().add(WorldRegions.class, new WorldRegions() {
            @Override public WorldRegion at(String d, double x, double y, double z) {
                throw new AssertionError("asked while it has no region");
            }
            @Override public boolean any() { return false; }
        });
        assertFalse(Regions.any());
        assertNull(Regions.at(OW, 0, 64, 0));
        assertSame(WorldMakes.NONE, WorldMakes.get());
        Regions.Reach r = Regions.reachFor(UUID.randomUUID(), new Spot(OW, 0, 64, 0), null, WorldRules.ALLOW_ALL);
        assertNull(r.bases());
        assertSame(WorldRules.ALLOW_ALL, r.rules());
    }

    @Test
    void whereRegionsOverlapTheFirstRegisteredWins() {
        WorldRegion first = region("first", new Region(0, 10, 0, 10), bases("first"));
        WorldRegion second = region("second", new Region(5, 20, 5, 20), bases("second"));
        Plugins.shared().add(WorldRegions.class, plugin(first, true));
        Plugins.shared().add(WorldRegions.class, plugin(second, true));
        assertSame(first, Regions.at(OW, 7, 64, 7), "the overlap");
        assertSame(second, Regions.at(OW, 15, 64, 15), "only the second");
        assertNull(Regions.at(OW, 30, 64, 30), "in none");
        assertNull(Regions.at("minecraft:the_nether", 7, 64, 7), "another dimension");
        assertTrue(Regions.any());
        assertTrue(WorldMakes.get() instanceof RegionalMakes, "a region exists: the makes are asked region first");
    }

    @Test
    void aBrokenPluginIsNoRegion() {
        WorldRegion ok = region("ok", new Region(0, 10, 0, 10), bases("ok"));
        Plugins.shared().add(WorldRegions.class, new WorldRegions() {
            @Override public WorldRegion at(String d, double x, double y, double z) { throw new IllegalStateException("boom"); }
        });
        Plugins.shared().add(WorldRegions.class, plugin(ok, true));
        assertSame(ok, Regions.at(OW, 1, 64, 1));
    }

    @Test
    void reachIsTheCastersRegionElseTheOneTheWishIsAimedInto() {
        Bases arena = bases("arena");
        WorldRegion r = region("arena", new Region(0, 10, 0, 10), arena);
        Plugins.shared().add(WorldRegions.class, plugin(r, true));
        Bases world = bases("island");
        UUID outsider = UUID.randomUUID();   // not online: he stands in no region
        Regions.Reach in = Regions.reachFor(outsider, new Spot(OW, 3, 64, 3), world, null);
        assertSame(arena, in.bases(), "aimed into the arena from none: the arena judges");
        assertNull(in.rules(), "the arena names no rules, nor the world: CLOSED at the judge");
        Regions.Reach out = Regions.reachFor(outsider, new Spot(OW, 30, 64, 30), world, WorldRules.ALLOW_ALL);
        assertSame(world, out.bases(), "aimed outside every region: the world's");
        assertSame(WorldRules.ALLOW_ALL, out.rules());
        assertSame(world, Regions.reachFor(outsider, world, null).bases(), "no aim, in no region: the world's");
        assertSame(arena, Regions.reachAt(null, OW, 2, 64, 2, world, null).bases(), "a rule firing inside, nobody near");
        assertEquals("island", Regions.reachAt(null, OW, 40, 64, 40, world, null).bases().noun());
    }

    @Test
    void aRegionThatLeavesReachToTheWorldIsNotAFence() {
        WorldRegion noFence = region("zone", new Region(0, 10, 0, 10), null);
        Plugins.shared().add(WorldRegions.class, plugin(noFence, true));
        Bases world = bases("island");
        assertSame(world, Regions.reachFor(UUID.randomUUID(), new Spot(OW, 3, 64, 3), world, null).bases());
    }

    @Test
    void overlapsReadsOnlyRegionsThatSayWhereTheyAre() {
        WorldRegion r = region("arena", new Region(0, 10, 0, 10), null);
        Plugins.shared().add(WorldRegions.class, plugin(r, true));
        assertTrue(Regions.overlaps(OW, new Region(10, 20, 0, 10)), "touching its edge");
        assertFalse(Regions.overlaps(OW, new Region(11, 20, 0, 10)), "beside it");
        assertFalse(Regions.overlaps("minecraft:the_nether", new Region(0, 10, 0, 10)), "another dimension");
    }

    @Test
    void aCreatureCrossingIsSeenOnceAndAnUnseenOneIsNot() {
        RegionCrossings c = new RegionCrossings();
        UUID wolf = UUID.randomUUID();
        assertFalse(c.moved(wolf, "arena"), "never fitted: not watched, not moved");
        c.fitted(wolf, RegionCrossings.NONE);
        assertFalse(c.moved(wolf, null), "still in none");
        assertTrue(c.moved(wolf, "arena"), "walked in");
        assertFalse(c.moved(wolf, "arena"), "told once");
        assertTrue(c.moved(wolf, RegionCrossings.NONE), "walked out");
        c.forget(wolf);
        assertFalse(c.watches(wolf));
        assertEquals(0, c.size());
    }
}
