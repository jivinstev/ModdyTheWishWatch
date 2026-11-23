package com.moddingfromamod.mob;


import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * lighting far more TNT than {@link TntBudget#MAX_LIVE} leaves at most that many lit in the
 * level. The fuses are longer than the test, and every one is removed at the end, so nothing ever
 * explodes into a neighbouring test's structure; its own batch keeps other tests' TNT out of the count.
 *
 * <p>🔴 <b>Verified to fail</b> with {@code PrimedTntLimit} not registered: every lit TNT joins.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PrimedTntLimitGameTest {

    private PrimedTntLimitGameTest() {
    }

    @GameTest(template = "empty_test", batch = "tntlimit", timeoutTicks = 200)
    public static void threeThousandLitTntNeverBecomeMoreThanTheCeiling(GameTestHelper helper) {
        int before = PrimedTntLimit.live(helper.getLevel().dimension());
        for (int i = 0; i < 3000; i++) {      // well over the ceiling of 2,048
            PrimedTnt tnt = helper.spawn(EntityType.TNT, new BlockPos(1 + i % 3, 2, 1 + (i / 3) % 3));
            tnt.setFuse(30_000);
        }
        helper.runAfterDelay(2, () -> {
            int inWorld = helper.getEntities(EntityType.TNT).size();
            helper.assertTrue(inWorld <= TntBudget.MAX_LIVE,
                    Component.literal("the level holds " + inWorld + " lit TNT, more than the ceiling of " + TntBudget.MAX_LIVE));
            helper.assertTrue(inWorld >= TntBudget.MAX_LIVE - before - 1,
                    Component.literal("only " + inWorld + " lit TNT got in — the ceiling must admit up to " + TntBudget.MAX_LIVE));
            helper.getEntities(EntityType.TNT).forEach(t -> t.discard());
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(PrimedTntLimit.live(helper.getLevel().dimension()) == before,
                        Component.literal("removing them did not give the budget back ("
                                + PrimedTntLimit.live(helper.getLevel().dimension()) + " still counted)"));
                helper.succeed();
            });
        });
    }
}
