package com.moddingfromamod.world;

import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;

/**
 * <b>Nobody dies in a loop on their own base</b>: a player's island was covered in lava, every
 * respawn put him back in it, and he never had the seconds to open the watch. Each test drives the path a
 * real respawn takes ({@link WorldModuleHost#goHome}, {@link RespawnGuard#afterRespawn},
 * {@link RespawnGuard#died}) with a base of the test's own ({@link WorldModuleHost#forTest}, its own batch).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RespawnGuardGameTest {

    private static final String BATCH = "respawnguard";

    /** A base around the player's column, as an island is. */
    private static Bases basesFor(ServerPlayer him, int x, int y, int z) {
        UUID owner = him.getUUID();
        Base base = new Base(owner, Bases.OVERWORLD, x, y, z, y,
                new Region(x - 48, x + 48, z - 48, z + 48), new Region(x - 40, x + 40, z - 40, z + 40));
        return new Bases() {
            @Override public String noun() {
                return "island";
            }

            @Override public Base baseOf(UUID player) {
                return owner.equals(player) ? base : null;
            }

            @Override public UUID ownerAt(String dimension, double px, double pz) {
                return base.area().contains(px, pz) ? owner : null;
            }

            @Override public Bases.Pos positionOf(UUID player) {
                return new Bases.Pos(Bases.OVERWORLD, him.getX(), him.getY(), him.getZ());
            }
        };
    }

    private static boolean told(ServerPlayer p, String words) {
        List<String> chat = MockPlayerNetwork.chat(p);
        return chat.stream().anyMatch(l -> l.contains(words));
    }

    private static void clear(ServerLevel level, BlockPos c, int r, int up) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= up; dy++) {
                    level.setBlockAndUpdate(c.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /**
     * <b>A home that is lava, with nothing safe anywhere near, sends him to a glass pad above it</b> — never
     * back into the lava. In open sky high above the plot, so nothing within reach has a floor, exactly as a
     * lava-covered island in the void. Control: the home spot itself is lava (he would burn there).
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aLavaHomeWithNowhereSafeNearGetsAGlassPadAbove(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper));
        BlockPos home = helper.absolutePos(new BlockPos(4, 120, 4));
        clear(level, home, 3, 60);
        level.setBlockAndUpdate(home.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(home, Blocks.LAVA.defaultBlockState());
        try {
            helper.assertTrue(com.moddingfromamod.vanilla.SafePlacement.buriedAt(level, p, home.getX() + 0.5,
                    home.getY(), home.getZ() + 0.5), Component.literal("control: a lava home spot must read as unsafe"));
            WorldModuleHost.goHome(p, basesFor(p, home.getX(), home.getY(), home.getZ()), level,
                    home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            BlockPos at = p.blockPosition();
            helper.assertTrue(at.getY() > home.getY(), Component.literal("he was not lifted out of the lava: " + p.position()));
            helper.assertTrue(level.getBlockState(at.below()).is(Blocks.GLASS),
                    Component.literal("he is not standing on the rescue pad: " + level.getBlockState(at.below())));
            helper.assertTrue(level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir(),
                    Component.literal("the pad has no room to stand"));
            helper.assertTrue(told(p, RespawnGuard.PAD_LINE),
                    Component.literal("he was not told about the pad: " + MockPlayerNetwork.chat(p)));
            helper.succeed();
        } finally {
            clear(level, home, 3, 60);
            p.discard();
        }
    }

    /** <b>A respawn on his own base is fireproof for a few seconds, and his fire is put out.</b> Off it, nothing. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aRespawnOnHisBaseIsFireproofForAFewSeconds(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper));
        try {
            Bases mine = basesFor(p, p.getBlockX(), p.getBlockY(), p.getBlockZ());
            p.igniteForSeconds(8);
            RespawnGuard.afterRespawn(p, mine);
            helper.assertTrue(p.hasEffect(MobEffects.FIRE_RESISTANCE) && p.hasEffect(MobEffects.RESISTANCE),
                    Component.literal("no grace after a respawn on his own base"));
            helper.assertTrue(p.getRemainingFireTicks() <= 0, Component.literal("his fire was not put out"));
            int left = p.getEffect(MobEffects.FIRE_RESISTANCE).getDuration();
            helper.assertTrue(left <= RespawnGuard.GRACE_TICKS,
                    Component.literal("the grace must be short, not a power: " + left + " ticks"));

            // somebody else's base, or none: no grace
            p.removeAllEffects();
            RespawnGuard.afterRespawn(p, basesFor(p, p.getBlockX() + 5000, p.getBlockY(), p.getBlockZ()));
            helper.assertTrue(!p.hasEffect(MobEffects.FIRE_RESISTANCE),
                    Component.literal("a respawn off his base must not be graced"));
            RespawnGuard.afterRespawn(p, null);
            helper.assertTrue(!p.hasEffect(MobEffects.FIRE_RESISTANCE), Component.literal("no bases, no grace"));
            helper.succeed();
        } finally {
            p.discard();
        }
    }

    /**
     * <b>Three deaths on his base inside two minutes, and the next respawn offers a way out</b> — once. The
     * link runs {@code /moddy rescue}, which moves him and graces him.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void threeDeathsOnHisBaseOfferARescueThatWorks(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper));
        try {
            Bases mine = basesFor(p, p.getBlockX(), p.getBlockY(), p.getBlockZ());
            long t = 1_000_000L;
            RespawnGuard.died(p, mine, t);
            RespawnGuard.afterRespawn(p, mine);
            RespawnGuard.died(p, mine, t + 5_000);
            RespawnGuard.afterRespawn(p, mine);
            helper.assertTrue(!told(p, RespawnGuard.SAVE_ME), Component.literal("two deaths are not a loop yet"));
            RespawnGuard.died(p, mine, t + 10_000);
            RespawnGuard.afterRespawn(p, mine);
            helper.assertTrue(told(p, RespawnGuard.LOOP_LINE) && told(p, RespawnGuard.SAVE_ME),
                    Component.literal("the third death must offer the rescue: " + MockPlayerNetwork.chat(p)));
            helper.assertTrue(told(p, RespawnGuard.rescueCommand()),
                    Component.literal("the offer must name the command it runs: " + MockPlayerNetwork.chat(p)));
            long offers = MockPlayerNetwork.chat(p).stream().filter(l -> l.contains(RespawnGuard.LOOP_LINE)).count();
            RespawnGuard.died(p, mine, t + 12_000);
            RespawnGuard.afterRespawn(p, mine);
            helper.assertTrue(MockPlayerNetwork.chat(p).stream().filter(l -> l.contains(RespawnGuard.LOOP_LINE)).count() == offers,
                    Component.literal("a fourth death straight after must not repeat the offer"));

            // the command the link runs: registered, and it rescues
            p.removeAllEffects();
            int r = RespawnGuard.rescue(p, mine);
            helper.assertTrue(r == 1 && p.hasEffect(MobEffects.FIRE_RESISTANCE) && told(p, RespawnGuard.RESCUED_LINE),
                    Component.literal("/moddy rescue did not rescue: " + r + " " + MockPlayerNetwork.chat(p)));
            helper.assertTrue(helper.getLevel().getServer().getCommands().getDispatcher().getRoot()
                            .getChild(com.moddingfromamod.wish.ChildCommands.MODDY)
                            .getChild(com.moddingfromamod.wish.ChildCommands.RESCUE) != null,
                    Component.literal("/moddy rescue is not registered"));
            helper.assertTrue(RespawnGuard.rescue(p, null) == 0 && told(p, RespawnGuard.NOTHING_TO_RESCUE),
                    Component.literal("a world with no bases has nothing to rescue him from"));
            helper.succeed();
        } finally {
            RespawnGuard.forgetForTest(p.getUUID());
            p.discard();
        }
    }

    /** A death somewhere that is not his base counts for nothing. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void deathsOffHisBaseAreNotALoop(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper));
        try {
            Bases elsewhere = basesFor(p, p.getBlockX() + 5000, p.getBlockY(), p.getBlockZ());
            for (int i = 0; i < 5; i++) {
                RespawnGuard.died(p, elsewhere, 2_000_000L + i * 1_000L);
            }
            RespawnGuard.afterRespawn(p, elsewhere);
            helper.assertTrue(!told(p, RespawnGuard.SAVE_ME), Component.literal("deaths off his base were counted"));
            helper.succeed();
        } finally {
            RespawnGuard.forgetForTest(p.getUUID());
            p.discard();
        }
    }
}
