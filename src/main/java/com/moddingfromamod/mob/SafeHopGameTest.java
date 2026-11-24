package com.moddingfromamod.mob;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.vanilla.SafePlacement;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The follower hop, on a running server</b>: vanilla's ten checked tries, never while
 * he is airborne. Driven through {@link PetBehaviours#follow} — the path a pet really takes — and
 * each case asserts where it ended up and that it is not inside a block.
 *
 * <p>Everything happens inside one tick on purpose: the lava here is real lava hung in the air,
 * and it is taken away before it can flow.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SafeHopGameTest {

    private static final String BATCH = "safespot";

    private static PetBehaviour follows() {
        return PetBehaviour.fromJson(JsonParser.parseString("{follow:true}").getAsJsonObject());
    }

    private static void fill(GameTestHelper helper, int y, net.minecraft.world.level.block.Block b) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, y, z), b);
            }
        }
    }

    /**
     * <b>A hop to a flying owner.</b> Spikor was set to follow at 20:06 and fell to his death at
     * 20:07: the hop put a walker at the height of a child flying in Creative. Now: no hop while he
     * flies; once he lands on a ledge 17 blocks up, it hops onto the ledge beside him.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aWalkerDoesNotHopToAFlyingOwnerAndCatchesUpWhenHeLands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int f = PetBehavioursGameTest.floor(helper);
        int ledge = f + 17;
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "safehop-flier");
        Cow cow = helper.spawn(EntityType.COW, new BlockPos(4, f + 1, 4));
        try {
            fill(helper, ledge, Blocks.STONE);
            BlockPos at = helper.absolutePos(new BlockPos(4, ledge + 1, 4));
            child.getAbilities().mayfly = true;
            child.getAbilities().flying = true;
            child.teleportTo(at.getX() + 0.5, at.getY() + 4, at.getZ() + 0.5);
            PetBehaviours.apply(cow, follows(), child.getUUID());
            double y0 = cow.getY();
            helper.assertTrue(cow.distanceTo(child) > PetMoves.FOLLOW_TELEPORT,
                    Component.literal("the owner is not far enough away to make it hop"));
            helper.assertTrue(SafePlacement.airborne(level, child), Component.literal("a flying child did not read as airborne"));
            for (int i = 0; i < 10; i++) {
                PetBehaviours.follow(level, cow);
            }
            helper.assertTrue(cow.getY() == y0,
                    Component.literal("the cow hopped to a FLYING owner: y " + y0 + " → " + cow.getY()));

            // He lands on the ledge.
            child.getAbilities().flying = false;
            child.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            helper.assertTrue(!SafePlacement.airborne(level, child), Component.literal("a child standing on a ledge read as airborne"));
            for (int i = 0; i < 10 && cow.getY() == y0; i++) {
                PetBehaviours.follow(level, cow);
            }
            helper.assertTrue(cow.getY() == at.getY(),
                    Component.literal("after he landed it should have hopped onto the ledge (y=" + at.getY() + "), it is at y="
                            + cow.getY()));
            helper.assertTrue(Math.abs(cow.getX() - child.getX()) <= 3.5 && Math.abs(cow.getZ() - child.getZ()) <= 3.5,
                    Component.literal("the hop landed out of reach of him: " + cow.position() + " vs " + child.position()));
            helper.assertTrue(!cow.isInWall(), Component.literal("the cow hopped into a block"));
            helper.succeed();
        } finally {
            fill(helper, ledge, Blocks.AIR);
            MockPlayerNetwork.remove(child);
        }
    }

    /**
     * <b>A hop beside lava.</b> The Pickleball Player burned 3 blocks from where he stood beside the
     * lava castle. He stands on a one-block causeway across a lava lake; every hop lands on the
     * causeway, never in the lava.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void aHopBesideLavaLandsOnTheCausewayNeverInTheLava(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int f = PetBehavioursGameTest.floor(helper);
        int lake = f + 20;
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "safehop-lava");
        Cow cow = helper.spawn(EntityType.COW, new BlockPos(4, f + 1, 4));
        try {
            fill(helper, lake, Blocks.LAVA);
            for (int x = 0; x <= 8; x++) {
                helper.setBlock(new BlockPos(x, lake, 4), Blocks.STONE);
            }
            BlockPos at = helper.absolutePos(new BlockPos(4, lake + 1, 4));
            child.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            PetBehaviours.apply(cow, follows(), child.getUUID());
            BlockPos home = cow.blockPosition();
            int hops = 0;
            for (int i = 0; i < 40; i++) {
                PetBehaviours.follow(level, cow);
                if (cow.getY() == home.getY()) {
                    continue;
                }
                hops++;
                helper.assertTrue(cow.blockPosition().getZ() == at.getZ() && cow.getY() == at.getY(),
                        Component.literal("hop " + hops + " did not land on the causeway: " + cow.position()));
                helper.assertTrue(!cow.isInLava(), Component.literal("hop " + hops + " landed in the lava"));
                helper.assertTrue(!cow.isInWall(), Component.literal("hop " + hops + " landed in a block"));
                cow.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            }
            helper.assertTrue(hops > 0, Component.literal("it never hopped to him at all in 40 tries"));
            helper.succeed();
        } finally {
            fill(helper, lake, Blocks.AIR);
            MockPlayerNetwork.remove(child);
        }
    }
}
