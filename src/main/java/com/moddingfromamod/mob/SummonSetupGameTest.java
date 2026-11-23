package com.moddingfromamod.mob;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.delivery.DeliveryPlan;
import com.moddingfromamod.wish.WishExecutor;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * A wished mob summoned WITH NBT keeps the spawn setup that matters ({@link SummonSetup}), through
 * the same path the agent's {@code run_command} takes ({@link WishExecutor#runOne} inside a wish's
 * {@link SpawnScope}) — and a wish whose boss vanished may summon it again.
 *
 * <p>🔴 Without {@link SummonRepairs}: the tagged warden has no {@code DIG_COOLDOWN}, digs at once
 * and is discarded within ~100 ticks, so the 200-tick check finds nothing. Without
 * {@link SpawnScope#released}: the second summon is turned away as a full wave.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SummonSetupGameTest {

    private SummonSetupGameTest() {
    }

    private static WishExecutor.Result summon(GameTestHelper helper, String wish, String command) {
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 2, 1)));
        return SpawnScope.during(wish, DeliveryPlan.Kind.HANDOVER, () ->
                WishExecutor.runOne(helper.getLevel().getServer(), helper.getLevel(), at, null, command));
    }

    @GameTest(template = "empty_test", batch = "summonsetup", timeoutTicks = 400)
    public static void aTaggedWardenStaysAndItsWishMayRetry(GameTestHelper helper) {
        String wish = "gametest-summonsetup-" + System.nanoTime();
        String cmd = "summon minecraft:warden ~ ~ ~ {Tags:[\"summonsetup_test\"],PersistenceRequired:1b}";
        WishExecutor.Result r = summon(helper, wish, cmd);
        helper.assertTrue(r.success(), Component.literal("the summon must run: " + r.joined()));
        List<Warden> w = helper.getEntities(EntityType.WARDEN);
        helper.assertTrue(w.size() == 1, Component.literal("one warden expected, found " + w.size()));
        Warden first = w.get(0);
        helper.assertTrue(first.getBrain().hasMemoryValue(MemoryModuleType.DIG_COOLDOWN),
                Component.literal("a warden summoned with NBT must get finalizeSpawn's dig cooldown back"));
        helper.assertTrue(first.getTags().contains("summonsetup_test"), Component.literal("the NBT the agent asked for is kept"));

        // The budget: a wish's boss that is GONE gives its slot back (the warden that dug away).
        first.discard();
        WishExecutor.Result again = summon(helper, wish, cmd);
        List<Warden> after = helper.getEntities(EntityType.WARDEN);
        helper.assertTrue(again.success() && after.size() == 1,
                Component.literal("a retry after the first boss vanished must be admitted: " + again.joined()
                        + " (wardens now " + after.size() + ")"));
        Warden second = after.get(0);
        helper.runAfterDelay(200, () -> {
            helper.assertTrue(second.isAlive() && !second.isRemoved(),
                    Component.literal("200 ticks on the warden is gone (removal " + second.getRemovalReason() + ")"));
            helper.assertTrue(second.getPose() != Pose.DIGGING, Component.literal("200 ticks on the warden is digging away"));
            helper.getEntities(EntityType.WARDEN).forEach(Entity::discard);
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "summonsetup", timeoutTicks = 100)
    public static void aTaggedPhantomCirclesWhereItWasMade(GameTestHelper helper) {
        WishExecutor.Result r = summon(helper, "gametest-summonsetup-p-" + System.nanoTime(),
                "summon minecraft:phantom ~ ~3 ~ {Tags:[\"summonsetup_test\"]}");
        helper.assertTrue(r.success(), Component.literal("the summon must run: " + r.joined()));
        var phantoms = helper.getEntities(EntityType.PHANTOM);
        helper.assertTrue(phantoms.size() == 1, Component.literal("one phantom expected, found " + phantoms.size()));
        var p = phantoms.get(0);
        CompoundTag own = com.moddingfromamod.compat.EntityNbt.save(p);
        BlockPos want = p.blockPosition().above(SummonSetup.PHANTOM_ANCHOR_ABOVE);
        var anchor = own.read("anchor_pos", BlockPos.CODEC);   // AX/AY/AZ before 1.21.5
        helper.assertTrue(anchor.isPresent() && anchor.get().equals(want),
                Component.literal("its anchor must be above where it appeared, not the world origin: anchor_pos "
                        + anchor.map(BlockPos::toShortString).orElse("absent") + ", wanted " + want.toShortString()));
        helper.assertTrue(p.getTags().contains("summonsetup_test"), Component.literal("the NBT the agent asked for is kept"));
        p.discard();
        helper.succeed();
    }
}
