package com.moddingfromamod.agent.tools;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The orbit holds its subject still, and gives it back as it found it.</b>
 *
 * <p>A real wish's four stills were shot of a creature that walked and turned between stops,
 * because nothing on the shipped path froze it — only the eval harness did, and its freeze named
 * {@code WishMobEntity}, which a rig carrier is not. This pins the hold on a plain vanilla mob, the
 * shape a carrier has.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SubjectHoldGameTest {

    @GameTest(template = "empty_test")
    public static void aLiveMobIsHeldAndThenGivenItsAiBack(GameTestHelper helper) {
        var cow = helper.spawn(EntityTypes.COW, new BlockPos(1, 1, 1));
        helper.assertTrue(!cow.isNoAi(), Component.literal("a fresh cow has its AI"));
        cow.setYRot(90f);          // caught mid-turn: the body has not caught up with the entity
        cow.setYBodyRot(45f);
        cow.setYHeadRot(120f);
        SubjectHold hold = SubjectHold.freeze(cow);
        helper.assertTrue(cow.isNoAi(), Component.literal("held for the stills: it cannot walk or turn between stops"));
        helper.assertValueEqual(cow.getYRot(), cow.yBodyRot,
                Component.literal("nothing left for the body to ease towards, so it cannot keep turning"));
        helper.assertValueEqual(cow.getYHeadRot(), cow.yBodyRot, Component.literal("nor towards its head"));
        hold.close();
        helper.assertTrue(!cow.isNoAi(), Component.literal("released: the child gets a live creature back"));
        hold.close();
        helper.assertTrue(!cow.isNoAi(), Component.literal("releasing twice changes nothing"));
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void aMobThatWasAlreadyStillStaysStill(GameTestHelper helper) {
        var cow = helper.spawn(EntityTypes.COW, new BlockPos(1, 1, 1));
        cow.setNoAi(true);   // a statue the agent made on purpose, or a harness's frozen subject
        SubjectHold.freeze(cow).close();
        helper.assertTrue(cow.isNoAi(), Component.literal("the orbit must not wake a creature somebody else froze"));
        helper.succeed();
    }
}
