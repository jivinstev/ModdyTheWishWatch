package com.moddingfromamod.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B: a dead boss must not leave its health bar on screen.
 *
 * <p>The bug was an order-of-operations trap inside one tick. {@code super.tick()} drives the death
 * sequence and, at {@code deathTime == 20}, calls {@code remove()} — which correctly clears the boss
 * bar. Our {@code tick()} then <b>carried on</b> and called {@code tickBossBar}, which saw a null bar
 * and built a fresh one on an entity that would never tick again. Health was 0, so it rendered as an
 * empty bar that nothing could ever clean up: one per death. A session with four dead Spikors left
 * four of them stacked on screen.
 *
 * <p>What makes this worth a runtime test rather than a unit test is that every individual piece was
 * correct — {@code remove()} cleaned up properly, {@code tickBossBar} built a valid bar. Only the
 * order was wrong, and order only exists when something is actually ticking.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishMobBossBarGameTest {

    /** A boss-power creature — the only kind that gets a bar at all. */
    private static Species bossSpecies(String id) {
        return Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"name\":\"Bar Boss\",\"health\":20,"
                        + "\"powers\":[\"boss\"]}").getAsJsonObject());
    }

    private static WishMobEntity spawn(GameTestHelper helper, String speciesId) {
        ServerLevel level = helper.getLevel();
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(mob != null, Component.literal("the wish mob entity type should create"));
        net.minecraft.core.BlockPos at = helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2));
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setSpeciesId(speciesId);
        level.addFreshEntity(mob);
        return mob;
    }

    /** Ticking a live boss gives it a bar — the baseline the real assertion depends on. */
    @GameTest(template = "empty_test")
    public static void aLiveBossHasABar(GameTestHelper helper) {
        com.moddingfromamod.testing.SpeciesForTest.put(helper, bossSpecies("gt_bar_live"));
        WishMobEntity mob = spawn(helper, "gt_bar_live");
        mob.tick();
        helper.assertTrue(mob.hasBossBar(), Component.literal("a living BOSS creature should show a health bar"));
        mob.discard();
        helper.succeed();
    }

    /**
     * The regression. Tick the creature through its whole death sequence and assert nothing is left
     * behind — specifically that the tick AFTER removal did not quietly build a new bar.
     */
    @GameTest(template = "empty_test")
    public static void aDeadBossLeavesNoBarBehind(GameTestHelper helper) {
        com.moddingfromamod.testing.SpeciesForTest.put(helper, bossSpecies("gt_bar_dead"));
        WishMobEntity mob = spawn(helper, "gt_bar_dead");
        mob.tick();
        helper.assertTrue(mob.hasBossBar(), Component.literal("precondition: it had a bar while alive"));

        // Kill it and run past deathTime == 20, which is where vanilla calls remove().
        mob.setHealth(0f);
        mob.die(mob.damageSources().generic());
        for (int i = 0; i < 30; i++) {
            mob.tick();
        }

        helper.assertTrue(mob.isRemoved(), Component.literal("the creature should have been removed by now"));
        helper.assertTrue(!mob.hasBossBar(),
                Component.literal("a dead creature must leave NO boss bar — an orphaned one can never be cleaned up, "
                        + "because the entity that owns it will never tick again"));
        helper.succeed();
    }

    /**
     * The same, one tick at a time around the exact moment of removal — the single tick where the
     * old code did its damage.
     */
    @GameTest(template = "empty_test")
    public static void theTickThatRemovesItDoesNotRebuildTheBar(GameTestHelper helper) {
        com.moddingfromamod.testing.SpeciesForTest.put(helper, bossSpecies("gt_bar_edge"));
        WishMobEntity mob = spawn(helper, "gt_bar_edge");
        mob.tick();

        mob.setHealth(0f);
        mob.die(mob.damageSources().generic());
        boolean sawRemoval = false;
        for (int i = 0; i < 30 && !sawRemoval; i++) {
            mob.tick();
            if (mob.isRemoved()) {
                sawRemoval = true;
                helper.assertTrue(!mob.hasBossBar(),
                        Component.literal("on the very tick it was removed, no new bar may be created"));
            }
        }
        helper.assertTrue(sawRemoval, Component.literal("the creature should have been removed within 30 ticks"));
        helper.succeed();
    }
}
