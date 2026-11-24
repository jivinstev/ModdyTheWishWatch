package com.moddingfromamod.event;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.event.catching.BlockHits;
import com.moddingfromamod.event.catching.Catch;
import com.moddingfromamod.event.catching.CatchRules;
import com.moddingfromamod.event.catching.CatchingMotion;
import com.moddingfromamod.event.catching.Caught;
import com.moddingfromamod.event.catching.Effects;
import com.moddingfromamod.event.motion.MovingFrontMotion;
import com.moddingfromamod.event.shape.FrontShape;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>What an event does to what it catches, in a real world</b> (E3). Each test has its own
 * lane, on a different line from the motion tests' so the two never meet. Every "it moved" claim has
 * a control beside it that must NOT move: an effect that reached everything would pass a test that
 * only looked at the thing it was meant to reach.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class EventCatchGameTest {

    private static final String BATCH = "eventcatch";
    private static final AtomicInteger LANE = new AtomicInteger();

    private static BlockPos lane() {
        return new BlockPos(90_000 + LANE.incrementAndGet() * 400, 0, 96_000);
    }

    private static void force(ServerLevel level, int x0, int z0, int x1, int z1) {
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    private static int top(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static ItemEntity item(ServerLevel level, double x, double y, double z) {
        ItemEntity e = new ItemEntity(level, x, y, z, new ItemStack(Items.DIAMOND));
        e.setDeltaMovement(Vec3.ZERO);
        e.setPickUpDelay(32767);
        level.addFreshEntity(e);
        return e;
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aWaveCarriesWhatItCatchesAndLeavesTheRestAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        FrontShape shape = new FrontShape(6, 16, true);
        force(level, o.getX() - 8, o.getZ() - 40, o.getX() + shape.travel() + 16, o.getZ() + 40);
        int g = top(level, o.getX(), o.getZ());
        MovingFrontMotion front = new MovingFrontMotion(shape, Blocks.WATER.defaultBlockState(),
                new BlockPos(o.getX(), g, o.getZ()), -90f);

        ItemEntity inPath = item(level, o.getX() + 20.5, g + 1, o.getZ() + 0.5);
        Cow cow = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
        cow.snapTo(o.getX() + 25.5, g + 1, o.getZ() + 2.5, 0, 0);
        level.addFreshEntity(cow);
        // The control: well off to the side of a 16-wide wave.
        ItemEntity aside = item(level, o.getX() + 20.5, g + 1, o.getZ() + 30.5);
        double itemX = inPath.getX(), cowX = cow.getX();
        Vec3 asideAt = aside.position();

        String id = "carry_" + o.getX();
        CatchingMotion m = new CatchingMotion(front)
                .on(Catch.inside(front.yaw(), front::strength), Effects.carry(), Effects.lift(front::slamming));
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, true, m);
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);

        helper.succeedWhen(() -> {
            helper.assertTrue(EventDirector.get(level.getServer(), id) == null, Component.literal("the wave has gone"));
            helper.assertTrue(m.caught() > 0, Component.literal("the wave caught something"));
            helper.assertTrue(inPath.getX() - itemX > 12,
                    Component.literal("the diamond in its path was carried east: moved " + (inPath.getX() - itemX)));
            helper.assertTrue(cow.isAlive() && cow.getX() - cowX > 8,
                    Component.literal("the cow was carried east: moved " + (cow.getX() - cowX)));
            helper.assertTrue(cow.getHealth() == cow.getMaxHealth(),
                    Component.literal("and set down unhurt: " + cow.getHealth()));
            helper.assertTrue(aside.position().distanceTo(asideAt) < 0.5,
                    Component.literal("the diamond off to the side never moved: " + aside.position().distanceTo(asideAt)));
            inPath.discard();
            aside.discard();
            cow.discard();
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300)
    public static void aQuakeShakesItsMiddleAndNothingPastItsReach(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(level, o.getX() - 40, o.getZ() - 40, o.getX() + 40, o.getZ() + 40);
        int g = top(level, o.getX(), o.getZ());
        BlockPos centre = new BlockPos(o.getX(), g + 1, o.getZ());
        ItemEntity middle = item(level, o.getX() + 2.5, g + 1, o.getZ() + 0.5);
        ItemEntity edge = item(level, o.getX() + 26.5, g + 1, o.getZ() + 0.5);
        double[] path = {0, 0};
        Vec3[] last = {middle.position(), edge.position()};

        String id = "quake_" + o.getX();
        CatchingMotion m = new CatchingMotion((w, age) -> {
            path[0] += middle.position().distanceTo(last[0]);
            path[1] += edge.position().distanceTo(last[1]);
            last[0] = middle.position();
            last[1] = edge.position();
            return age >= 60;
        }).on(Catch.near(centre, 20).every(2), Effects.shake());
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, true, m);
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);

        helper.succeedWhen(() -> {
            helper.assertTrue(EventDirector.get(level.getServer(), id) == null, Component.literal("the quake is over"));
            helper.assertTrue(path[0] > 1.0, Component.literal("the middle was thrown about: " + path[0]));
            helper.assertTrue(path[1] < 0.01, Component.literal("past its reach, nothing: " + path[1]));
            middle.discard();
            edge.discard();
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void itNeverKillsHimAndOnlyKindEffectsAreOffered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player child = helper.makeMockPlayer(GameType.SURVIVAL);
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        Cow cow = helper.spawn(EntityType.COW, new BlockPos(3, 2, 3));

        Effects.hurt(100).apply(level, new Caught(child, 1, 0, 0), 0);
        helper.assertTrue(child.getHealth() == CatchRules.PLAYER_FLOOR,
                Component.literal("one heart left, never none: " + child.getHealth()));
        Effects.hurt(100).apply(level, new Caught(child, 1, 0, 0), 1);
        helper.assertTrue(child.getHealth() == CatchRules.PLAYER_FLOOR, Component.literal("and no further: " + child.getHealth()));

        Effects.hurt(100).apply(level, new Caught(zombie, 1, 0, 0), 0);
        helper.assertTrue(zombie.isDeadOrDying(), Component.literal("a monster gets the lot"));

        Effects.ignite().apply(level, new Caught(cow, 1, 0, 0), 0);
        helper.assertTrue(cow.isOnFire(), Component.literal("ignite sets it alight"));
        cow.clearFire();

        Effects.status("slow_falling", 5).apply(level, new Caught(cow, 1, 0, 0), 0);
        helper.assertTrue(cow.hasEffect(MobEffects.SLOW_FALLING), Component.literal("a kind effect is given"));
        helper.assertTrue(Effects.status("poison", 5) == null && Effects.status("wither", 5) == null,
                Component.literal("a cruel one is not on offer at all"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void hailBreaksGlassAndLeavesButNeverStoneOrAChestAndUndoMendsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(level, o.getX() - 16, o.getZ() - 16, o.getX() + 16, o.getZ() + 16);
        int g = top(level, o.getX(), o.getZ());
        BlockPos centre = new BlockPos(o.getX(), g + 1, o.getZ());
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        Map<BlockPos, BlockState> laid = new HashMap<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos p = centre.offset(dx, 0, dz);
                BlockState s = switch (Math.floorMod(dx + dz, 3)) {
                    case 0 -> Blocks.GLASS.defaultBlockState();
                    case 1 -> leaves;
                    default -> Blocks.STONE_BRICKS.defaultBlockState();
                };
                if (dx == 0 && dz == 0) {
                    s = Blocks.CHEST.defaultBlockState();
                }
                level.setBlock(p, s, 3);
                laid.put(p, level.getBlockState(p));
            }
        }
        ((ChestBlockEntity) level.getBlockEntity(centre)).setItem(0, new ItemStack(Items.DIAMOND, 3));

        String id = "hail_" + o.getX();
        int[] broke = {0, 0, 0}; // glass, leaves, anything else
        CatchingMotion m = new CatchingMotion((w, age) -> age >= 60)
                .blocks(BlockHits.breakFragile(age -> centre, 4, 25, 1.0));
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, false,
                (w, age) -> {
                    boolean done = m.step(w, age);
                    if (done) {
                        for (Map.Entry<BlockPos, BlockState> e : laid.entrySet()) {
                            if (level.getBlockState(e.getKey()) != e.getValue()) {
                                broke[e.getValue().is(Blocks.GLASS) ? 0
                                        : e.getValue().is(Blocks.OAK_LEAVES) ? 1 : 2]++;
                            }
                        }
                    }
                    return done;
                });
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);

        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            StagedEvent e = EventDirector.get(level.getServer(), id);
            if (!undone[0]) {
                helper.assertTrue(e != null && e.phase() == EventPhase.KEPT, Component.literal("the hail is over and kept"));
                helper.assertTrue(broke[0] > 0 && broke[1] > 0,
                        Component.literal("it broke glass and stripped leaves: " + broke[0] + " / " + broke[1]));
                helper.assertTrue(broke[2] == 0, Component.literal("and nothing else, not a brick or the chest: " + broke[2]));
                helper.assertTrue(((ChestBlockEntity) level.getBlockEntity(centre)).getItem(0).getCount() == 3,
                        Component.literal("his diamonds are still in the chest"));
                helper.assertTrue(broke[0] < 27 || broke[1] < 27,
                        Component.literal("sometimes, not always: it did not break every single pane AND every leaf"));
                EventDirector.undo(level.getServer(), id);
                undone[0] = true;
                helper.fail(Component.literal("undo started"));
            }
            helper.assertTrue(e == null, Component.literal("the undo finished"));
            for (Map.Entry<BlockPos, BlockState> en : laid.entrySet()) {
                helper.assertTrue(level.getBlockState(en.getKey()) == en.getValue(),
                        Component.literal("mended at " + en.getKey()));
            }
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 300)
    public static void firesAreLitOnBareGroundAndNeverBesideHisWood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = lane();
        force(level, o.getX() - 16, o.getZ() - 16, o.getX() + 16, o.getZ() + 16);
        int g = top(level, o.getX(), o.getZ());
        BlockPos centre = new BlockPos(o.getX(), g, o.getZ());
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                level.setBlock(centre.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 1; y <= 6; y++) {
                    level.setBlock(centre.offset(dx, y, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        // His little wooden post, on the stone.
        BlockPos post = centre.offset(4, 1, 0);
        level.setBlock(post, Blocks.OAK_PLANKS.defaultBlockState(), 3);
        level.setBlock(post.above(), Blocks.OAK_PLANKS.defaultBlockState(), 3);

        String id = "fire_" + o.getX();
        int[] lit = {0};
        boolean[] besideWood = {false};
        CatchingMotion m = new CatchingMotion((w, age) -> {
            for (BlockPos p : BlockPos.betweenClosed(centre.offset(-6, 1, -6), centre.offset(6, 1, 6))) {
                if (level.getBlockState(p).is(Blocks.FIRE)) {
                    lit[0]++;
                    if (Math.abs(p.getX() - post.getX()) <= 1 && Math.abs(p.getZ() - post.getZ()) <= 1) {
                        besideWood[0] = true;
                    }
                }
            }
            return age >= 30;
        }).blocks(BlockHits.lightFires(age -> centre, 6, 20, 1.0));
        EventDirector.start(level, id, "", EventScale.LITTLE, EventGuard.Policy.INTO_OPEN, false, m);
        com.moddingfromamod.testing.EventsForTest.endsWithTheTest(helper, id);

        boolean[] undone = {false};
        helper.succeedWhen(() -> {
            StagedEvent e = EventDirector.get(level.getServer(), id);
            if (!undone[0]) {
                helper.assertTrue(e != null && e.phase() == EventPhase.KEPT, Component.literal("the fires are lit and kept"));
                helper.assertTrue(lit[0] > 5, Component.literal("fire on the bare stone: " + lit[0]));
                helper.assertTrue(!besideWood[0], Component.literal("never beside his wooden post"));
                helper.assertTrue(level.getBlockState(post).is(Blocks.OAK_PLANKS)
                        && level.getBlockState(post.above()).is(Blocks.OAK_PLANKS), Component.literal("the post stands"));
                EventDirector.undo(level.getServer(), id);
                undone[0] = true;
                helper.fail(Component.literal("undo started"));
            }
            helper.assertTrue(e == null, Component.literal("the undo finished"));
            List<BlockPos> still = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(centre.offset(-6, 1, -6), centre.offset(6, 1, 6))) {
                if (level.getBlockState(p).is(Blocks.FIRE)) {
                    still.add(p.immutable());
                }
            }
            helper.assertTrue(still.isEmpty(), Component.literal("the undo put every fire out: " + still));
        });
    }
}
