package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.plant;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.TestPlots;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.mob.PetMoves;
import com.moddingfromamod.testing.MockPlayerNetwork;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: only a creature that should follow the child, does.</b>
 *
 * <p>Production, 2026-09-25: two pickleball players were sculpted {@code companion} "so they don't
 * attack", and walked off their court after the child. The fix is in what the agent is told
 * (schema, lesson, the tool's reply). These tests pin the runtime half so it cannot drift: through
 * the real bridge and rig, a {@code wild} + {@code friendly} creature stays where it was put while
 * the child stands 14 blocks away, and a {@code companion} in the identical setup comes to him (the
 * control that proves this geometry tells the two apart).
 *
 * <p>Open ground in a {@link TestPlots} lane, never the 9-block plot: a plot's barrier walls stop
 * a follower as surely as a stay-at-home, and a test there cannot tell them apart. Each test reaps
 * its own rigs, because a test that succeeds clears only its plot, not a lane. A companion follows
 * the NEAREST player, so the control has a batch of its own.
 *
 * <p><b>What "follows" means here is the DECISION, not the distance.</b> A rig carrier is
 * steered at a player by exactly two things: {@link PetBehaviours}' follow, gated on {@code
 * PetBehaviours.of(carrier).follow()}, and {@link RigInstincts}' chase, held in {@link
 * RigInstincts#targetOf}. The stay tests assert that neither is ever pointed at the child, on every
 * tick. They used to assert that the creature never came within {@link #WITH_HIM}, and a cow out
 * for a stroll breaks that without following anybody: probed in a full {@code ci,default} run, a
 * court player's carrier (a cow) wandered up to 9.3 blocks from the court in 150 ticks with {@code
 * follow=null} throughout, ~0.2 blocks short of failing; with the window widened to 600 ticks the
 * distance check failed 2 of 40 with no follower anywhere. The control asserts the same probe reads
 * true AND that the companion really arrives, so the probe is shown to be the thing that moves it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class InstinctGameTest {

    /** How far away the child stands: well inside a follower's reach, short of its 16-block hop. */
    static final int AWAY = 14;
    /**
     * How long we watch. A companion closes a 12-block walk in 28–43 ticks (150 runs), so 150
     * is over three times its slowest. It is kept SHORT on purpose: also measured a pure
     * wanderer on a narrower floor drifting to a child 12 blocks off in 104–1120 ticks — which is
     * what the stay tests used to trip on even inside this window, and why they now read the
     * decision rather than the distance.
     */
    static final int TICKS = 150;

    /** Is anything of ours steering this carrier at {@code kid}? See the class note. */
    static boolean steeredAt(Mob c, ServerPlayer kid) {
        return Boolean.TRUE.equals(PetBehaviours.of(c).follow())
                || kid.getUUID().equals(RigInstincts.targetOf(c));
    }
    /** "With him": a follower stops about 3 blocks off, so this is generous to a follower. */
    static final double WITH_HIM = PetMoves.FOLLOW_CLOSE + 1.5;

    /** A floor of stone around a lane site, so a creature has somewhere to stand. */
    private static BlockPos ground(GameTestHelper helper, int site, int radius) {
        BlockPos centre = TestPlots.INSTINCTS.at(helper, site, 0, 0, 0);
        ServerLevel level = helper.getLevel();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                level.setBlockAndUpdate(centre.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy < 4; dy++) {
                    level.setBlockAndUpdate(centre.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        return centre;
    }

    private static void paint(GameTestHelper helper, BlockPos centre, int half, Block block) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                helper.getLevel().setBlockAndUpdate(centre.offset(dx, -1, dz), block.defaultBlockState());
            }
        }
    }

    private static void wishAt(GameTestHelper helper, String id, BlockPos at) {
        ServerLevel level = helper.getLevel();
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setSpeciesId(id);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.COMMAND, null);
        level.addFreshEntity(mob);
    }

    private static List<Mob> carriers(GameTestHelper helper, String id, BlockPos around) {
        String tag = WishMobTags.forSpecies(id);
        List<Mob> out = new ArrayList<>();
        for (Entity e : helper.getLevel().getEntities((Entity) null, new AABB(around).inflate(48),
                e -> e.entityTags().contains(DisplayRig.RIG_TAG) && e.entityTags().contains(tag))) {
            if (e instanceof Mob m && m.isAlive()) {
                out.add(m);
            }
        }
        return out;
    }

    private static void reap(GameTestHelper helper, String id, BlockPos around) {
        for (Mob m : carriers(helper, id, around)) {
            VanillaRigAnimator.forget(m.getUUID());
            DisplayRig.reap(helper.getLevel(), m.getUUID());
            m.discard();
        }
    }

    private static ServerPlayer childAt(GameTestHelper helper, String name, BlockPos at) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, name);
        kid.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return kid;
    }

    // ── wild + friendly: harmless, and it stays where it was put ────────────────────────────

    @GameTest(template = "empty_test", batch = "t6c5_stays", timeoutTicks = TICKS + 200)
    public static void aWildFriendlyCreatureDoesNotFollowTheChild(GameTestHelper helper) {
        String id = plant(helper, "t6c5_wild", "{base:'wild', powers:['friendly'], speed:0.25}");
        BlockPos centre = ground(helper, 0, AWAY + 2);
        ServerPlayer kid = childAt(helper, "mfam-t6c5-wild", centre.offset(AWAY, 0, 0));
        wishAt(helper, id, centre);
        int[] steered = {0};
        double[] closest = {Double.MAX_VALUE};
        helper.onEachTick(() -> {
            for (Mob c : carriers(helper, id, centre)) {
                closest[0] = Math.min(closest[0], c.distanceTo(kid));
                if (steeredAt(c, kid)) {
                    steered[0]++;
                }
            }
        });
        helper.runAfterDelay(TICKS, () -> {
            boolean seen = !carriers(helper, id, centre).isEmpty();
            reap(helper, id, centre);
            helper.assertTrue(seen, Component.literal("no carrier ever appeared"));
            helper.assertTrue(steered[0] == 0, Component.literal(String.format("a wild + friendly creature was "
                    + "steered at the child for %d ticks (closest %.1f)", steered[0], closest[0])));
            helper.succeed();
        });
    }

    // ── the pickleball shape: two players on a court, and the child walks away ──────────────

    @GameTest(template = "empty_test", batch = "t6c5_stays", timeoutTicks = TICKS + 200)
    public static void twoPlayersOnACourtStayOnIt(GameTestHelper helper) {
        String id = plant(helper, "t6c5_player", "{base:'wild', powers:['friendly'], speed:0.25}");
        BlockPos court = ground(helper, 1, AWAY + 2);
        paint(helper, court, 3, Blocks.CONCRETE.lime());
        ServerPlayer kid = childAt(helper, "mfam-t6c5-court", court.offset(AWAY, 0, 0));
        wishAt(helper, id, court.offset(-2, 0, 0));
        wishAt(helper, id, court.offset(2, 0, 0));
        int[] steered = {0};
        double[] farthestFromCourt = {0};
        helper.onEachTick(() -> {
            for (Mob c : carriers(helper, id, court)) {
                if (steeredAt(c, kid)) {
                    steered[0]++;
                }
                farthestFromCourt[0] = Math.max(farthestFromCourt[0],
                        Math.hypot(c.getX() - court.getX() - 0.5, c.getZ() - court.getZ() - 0.5));
            }
        });
        helper.runAfterDelay(TICKS, () -> {
            int players = carriers(helper, id, court).size();
            reap(helper, id, court);
            helper.assertTrue(players == 2, Component.literal("expected both players on the court, found " + players));
            helper.assertTrue(steered[0] == 0, Component.literal(String.format("a player was steered off the court "
                    + "at the child for %d ticks (farthest from the court %.1f)", steered[0],
                    farthestFromCourt[0])));
            helper.succeed();
        });
    }

    // ── the control: a companion in the same setup DOES come to him ─────────────────────────

    @GameTest(template = "empty_test", batch = "t6c5_follows", timeoutTicks = TICKS + 200)
    public static void aCompanionInTheSameSetupComesToTheChild(GameTestHelper helper) {
        String id = plant(helper, "t6c5_pet", "{base:'companion', powers:['friendly'], speed:0.25}");
        BlockPos centre = ground(helper, 2, AWAY + 2);
        ServerPlayer kid = childAt(helper, "mfam-t6c5-pet", centre.offset(AWAY, 0, 0));
        wishAt(helper, id, centre);
        int[] near = {0};
        int[] steered = {0};
        helper.onEachTick(() -> {
            for (Mob c : carriers(helper, id, centre)) {
                if (c.distanceTo(kid) < WITH_HIM) {
                    near[0]++;
                }
                if (steeredAt(c, kid)) {
                    steered[0]++;
                }
            }
        });
        helper.runAfterDelay(TICKS, () -> {
            reap(helper, id, centre);
            helper.assertTrue(steered[0] >= 20, Component.literal("the companion was never steered at the child ("
                    + steered[0] + " ticks) -- the probe the stay tests read cannot see a "
                    + "follower, so their zero proves nothing"));
            helper.assertTrue(near[0] >= 20, Component.literal("the companion never came to the child ("
                    + near[0] + " ticks with him) -- this geometry cannot tell a follower from a "
                    + "stay-at-home, so the tests beside it prove nothing"));
            helper.succeed();
        });
    }
}
