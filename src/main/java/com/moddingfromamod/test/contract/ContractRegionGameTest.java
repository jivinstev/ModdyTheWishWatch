package com.moddingfromamod.test.contract;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.LiveFit;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishAsk;
import com.moddingfromamod.wish.WishCharge;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.world.Base;
import com.moddingfromamod.world.BaseReach;
import com.moddingfromamod.world.Bases;
import com.moddingfromamod.world.Region;
import com.moddingfromamod.world.RegionWatch;
import com.moddingfromamod.world.Regions;
import com.moddingfromamod.world.WorldLimits;
import com.moddingfromamod.world.WorldMakes;
import com.moddingfromamod.world.WorldRegion;
import com.moddingfromamod.world.WorldRegions;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;

/**
 * <b>A region inside a plain world, as a world jar would build one</b> (#182): the contract a {@link WorldRegions}
 * plug-in can copy. Built only from the documented hooks ({@link WorldRegions}, {@link WorldRegion}, {@link WishCharge},
 * {@link WorldMakes}, {@link Bases}). A box of the test's floor is an arena: its charge counts what it is asked, its
 * makes hold every creature in it to {@link #MOST_HEALTH} health, and its bases admit only the player playing in it.
 * <ul>
 *   <li>a question about a player is answered by the region he stands in; a wish by the region it was made in, even
 *       after he walked out (its refund goes back to the charge that took it);</li>
 *   <li>a creature is held by the region IT stands in, wherever its owner is: walked out, it comes back to its own
 *       numbers; walked back in, it is held again (the core looks twice a second);</li>
 *   <li>a wish cast from outside every region and aimed into it is the region's to judge: refused for a player it
 *       does not admit;</li>
 *   <li>with no region in the world, nothing is wrapped and the held creature is let go.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ContractRegionGameTest {

    /** The most health a creature has inside the region. */
    static final double MOST_HEALTH = 4;

    private ContractRegionGameTest() {
    }

    @GameTest(template = "empty_test", batch = "contract_region", timeoutTicks = 2000)
    public static void aRegionAnswersForWhatIsInsideItAndNothingOutside(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "gtregion");
        var level = helper.getLevel();
        var server = level.getServer();
        String dim = level.dimension().location().toString();
        var c = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        double floor = c.getY() + 1;
        Region box = new Region(c.getX() - 3, c.getX() + 5, c.getZ() - 3, c.getZ() + 5);
        double inX = c.getX() + 1.5, inZ = c.getZ() + 1.5;
        double outX = c.getX() + 40.5, outZ = c.getZ() + 40.5;

        // ── the world jar's side: one arena, admitting only p ──
        AtomicInteger pickedUp = new AtomicInteger();
        AtomicReference<Boolean> standing = new AtomicReference<>(true);
        WishCharge arenaCharge = new WishCharge() {
            @Override public Optional<String> refuses(WishAsk ask) { return Optional.of("Only hotbar wishes in the arena!"); }
            @Override public Paid atPickup(Wish wish) {
                pickedUp.incrementAndGet();
                return Paid.FREE;
            }
        };
        WorldMakes arenaMakes = new WorldMakes() {
            @Override public WorldLimits limits(UUID player) {
                return new WorldLimits(Map.of(MakeFacts.HEALTH_HALF_HEARTS, MOST_HEALTH), "arena-1");
            }
        };
        Bases arenaBases = new Bases() {
            @Override public String noun() { return "arena"; }
            @Override public Base baseOf(UUID player) {
                return p.getUUID().equals(player)
                        ? new Base(player, dim, c.getX(), c.getY(), c.getZ(), c.getY(), box, box) : null;
            }
            @Override public UUID ownerAt(String d, double x, double z) { return null; }
            @Override public Pos positionOf(UUID player) { return null; }
        };
        WorldRegion arena = new WorldRegion() {
            @Override public String id() { return "contract/arena"; }
            @Override public WishCharge charge() { return arenaCharge; }
            @Override public WorldMakes makes() { return arenaMakes; }
            @Override public Bases bases() { return arenaBases; }
            @Override public Region area() { return box; }
            @Override public String dimension() { return dim; }
        };
        WorldRegions regions = new WorldRegions() {
            @Override public WorldRegion at(String d, double x, double y, double z) {
                return d.equals(dim) && box.contains(x, z) ? arena : null;
            }
            @Override public boolean any() { return standing.get(); }
            @Override public List<WorldRegion> all() { return standing.get() ? List.of(arena) : List.of(); }
        };
        Plugins.shared().replaceForTest(WorldRegions.class, regions);
        MockPlayerNetwork.afterTest(helper, () -> Plugins.shared().replaceForTest(WorldRegions.class, null));

        // ── a player: by where he stands ──
        WishAsk typed = WishAsk.of(p.getUUID(), WishSource.TYPED, WishAsk.Magic.MAGICIAN);
        p.teleportTo(inX, floor, inZ);
        helper.assertTrue(Regions.ofPlayer(p.getUUID()) == arena, Component.literal("inside, the arena does not answer"));
        helper.assertTrue(WishCharge.get().refuses(typed).isPresent(), Component.literal("inside, the arena's charge was not asked"));
        helper.assertTrue(Double.valueOf(MOST_HEALTH).equals(
                        WorldMakes.get().limits(p.getUUID()).limits().get(MakeFacts.HEALTH_HALF_HEARTS)),
                Component.literal("inside, the arena's limits were not read"));
        // ── a wish: by the spot it was made at, wherever he is now ──
        Wish madeInside = Wish.create("a wish", WishSource.TYPED, System.currentTimeMillis(), p.getUUID(),
                p.getScoreboardName(), dim, inX, floor, inZ);
        p.teleportTo(outX, floor, outZ);
        helper.assertTrue(WishCharge.get().refuses(typed).isEmpty(), Component.literal("outside, the arena's charge refused"));
        helper.assertTrue(WorldMakes.get().limits(p.getUUID()).limits().isEmpty(),
                Component.literal("outside, the arena's limits held"));
        WishCharge.get().atPickup(madeInside);
        helper.assertTrue(pickedUp.get() == 1, Component.literal("a wish made inside was not the arena's to charge"));
        WishCharge.get().atPickup(Wish.create("a wish", WishSource.TYPED, System.currentTimeMillis(), p.getUUID(),
                p.getScoreboardName(), dim, outX, floor, outZ));
        helper.assertTrue(pickedUp.get() == 1, Component.literal("a wish made outside was charged by the arena"));

        // ── reach: from outside, aimed in, the arena judges; it admits only p ──
        UUID stranger = UUID.randomUUID();
        Spot aimedIn = new Spot(dim, (int) inX, (int) floor, (int) inZ);
        Regions.Reach in = Regions.reachFor(stranger, aimedIn, null, null);
        helper.assertTrue(in.bases() == arenaBases, Component.literal("a wish aimed into the arena is not the arena's to judge"));
        com.google.gson.JsonObject build = new com.google.gson.JsonObject();
        helper.assertTrue(BaseReach.judge("build_structure", build, stranger, aimedIn, in.bases(), in.rules()) != null,
                Component.literal("a stranger's wish aimed into the arena was let in"));
        Regions.Reach his = Regions.reachFor(p.getUUID(), aimedIn, null, null);
        helper.assertTrue(BaseReach.judge("build_structure", build, p.getUUID(), aimedIn, his.bases(), his.rules()) == null,
                Component.literal("the arena's own player was refused a wish aimed into it"));
        helper.assertTrue(Regions.reachFor(stranger, new Spot(dim, (int) outX, (int) floor, (int) outZ), null, null)
                .bases() == null, Component.literal("a wish aimed outside every region was judged by the arena"));
        helper.assertTrue(Regions.overlaps(dim, new Region(box.maxX(), box.maxX() + 9, box.minZ(), box.maxZ()))
                        && !Regions.overlaps(dim, new Region(box.maxX() + 1, box.maxX() + 9, box.minZ(), box.maxZ())),
                Component.literal("overlaps does not read the arena's area"));

        // ── a creature: held by where IT stands, its owner outside ──
        String tag = UUID.randomUUID().toString().substring(0, 6);
        Species wolf = Species.fromJson(com.google.gson.JsonParser.parseString("{\"id\":\"gt_region_wolf_" + tag
                + "\",\"name\":\"Wolf\",\"powers\":[\"friendly\"],\"health\":20}").getAsJsonObject())
                .withOwner(p.getStringUUID());
        MockPlayerNetwork.afterTest(helper, SpeciesRegistry.putForTest(List.of(wolf)));
        LivingEntity spawned = com.moddingfromamod.vanilla.CreatureSpawn.spawn(level, wolf.id(), inX, floor, inZ, 0f,
                EntitySpawnReason.COMMAND, false);
        helper.assertTrue(spawned != null, Component.literal("the wolf did not spawn"));
        UUID first = spawned.getUUID();
        AtomicInteger stage = new AtomicInteger();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            var e = level.getEntity(com.moddingfromamod.mob.EntityReplacements.settled(first));
            helper.assertTrue(e instanceof LivingEntity && e.isAlive(), Component.literal("the wolf does not stand yet"));
            LivingEntity body = (LivingEntity) e;
            double max = body.getMaxHealth();
            switch (stage.get()) {
                case 0 -> {
                    helper.assertTrue(max <= MOST_HEALTH + 1e-6, Component.literal("inside, the wolf is not held: " + max));
                    body.teleportTo(outX, floor, outZ);   // it walks out (its owner is outside too)
                    stage.set(1);
                    helper.fail(Component.literal("walking out"));
                }
                case 1 -> {
                    helper.assertTrue(max > MOST_HEALTH + 1e-6, Component.literal("outside, the wolf is still held: " + max));
                    p.teleportTo(outX + 20, floor, outZ);   // its owner stays out; the wolf walks back in alone
                    body.teleportTo(inX, floor, inZ);
                    stage.set(2);
                    helper.fail(Component.literal("walking back in"));
                }
                case 2 -> {
                    helper.assertTrue(max <= MOST_HEALTH + 1e-6,
                            Component.literal("back inside, with its owner outside, the wolf is not held: " + max));
                    standing.set(false);   // the arena is taken down
                    stage.set(3);
                    helper.fail(Component.literal("taking the arena down"));
                }
                default -> {
                    helper.assertTrue(!Regions.any() && WorldMakes.get() == WorldMakes.NONE,
                            Component.literal("with no region, the core still wrapped its answers"));
                    helper.assertTrue(max > MOST_HEALTH + 1e-6 && LiveFit.modifierOn(body,
                                    net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, LiveFit.MAX_HEALTH_ID) == null,
                            Component.literal("with the arena gone, the wolf is still held: " + max));
                    helper.assertTrue(RegionWatch.watched() == 0, Component.literal("the watch still holds creatures"));
                }
            }
        });
    }
}
