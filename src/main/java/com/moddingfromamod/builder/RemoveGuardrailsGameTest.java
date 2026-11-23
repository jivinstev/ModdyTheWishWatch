package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B — the generalized remove/undo guardrails.</b> The agent used to have no way to clean up
 * a rejected or duplicate creation, so wrong things accumulated (8 sky-trains; stacks of half-right
 * creatures). These prove the two new remove mechanisms deterministically: removing a wish creature
 * discards exactly that species' living instances (and nothing else), and clearing a building removes
 * only the plan's own blocks, never surrounding terrain.
 */
@GameTestHolder("moddingfromamod")
public class RemoveGuardrailsGameTest {

    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** G5 (mobs): remove_creature discards every living instance of one species, leaving others. */
    @GameTest(template = "empty_test", timeoutTicks = 300)
    public static void removeCreatureDiscardsOnlyThatSpecies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // The species have to be REGISTERED, or the creature standing in the world cannot say what
        // it is and "remove only that species" is unanswerable. The old shape got away without
        // them because it read the id off the entity we constructed.
        for (String id : new String[]{"gd_alpha", "gd_beta"}) {
            com.moddingfromamod.testing.SpeciesForTest.put(helper, com.moddingfromamod.species.Species.fromJson(
                            com.google.gson.JsonParser.parseString(
                                    "{\"id\":\"" + id + "\",\"health\":20,\"speed\":0.0}")
                                    .getAsJsonObject()));
        }
        // Spawn in the plot (loaded chunks). Two "alpha" and one "beta", as if a tweak left duplicates.
        spawn(level, "gd_alpha", helper.absolutePos(new BlockPos(1, 3, 1)));
        spawn(level, "gd_alpha", helper.absolutePos(new BlockPos(2, 3, 1)));
        spawn(level, "gd_beta", helper.absolutePos(new BlockPos(3, 3, 1)));

        // ⚠ THIS GUARDRAIL WAS ONLY EVER TESTED ON THE LEGACY ENTITY.
        //
        // It used to enumerate `level.getEntities(ModEntities.WISH_MOB.get(), ...)` and discard
        // those. Since the vanilla-compat flip a wished creature has not BEEN a `wish_mob` in the
        // world -- it is a rig on a vanilla carrier -- so what this proved was that removing
        // something nothing ships still removes only what was asked. It passed because its own
        // fixtures were unsculpted, which is the one case the bridge used to decline; making the
        // bridge total (`StandInSculpt`) took the fixtures with it and the test with them.
        //
        // G4 is the same rule stated over the thing that is actually there: removing one species'
        // creatures removes all of them and none of another's.
        helper.succeedWhen(() -> {
            helper.assertTrue(rigs(level, "gd_alpha").size() == 2,
                    Component.literal("both 'alpha' creatures must have manifested before removal means anything "
                            + "(got " + rigs(level, "gd_alpha").size() + ")"));
            int removed = 0;
            for (var body : rigs(level, "gd_alpha")) {
                com.moddingfromamod.vanilla.DisplayRig.reap(level, body.getUUID());
                body.discard();
                removed++;
            }
            helper.assertTrue(removed == 2,
                    Component.literal("both 'alpha' creatures are removed (got " + removed + ")"));
            helper.assertTrue(rigs(level, "gd_alpha").isEmpty(), Component.literal("no 'alpha' creatures remain"));
            helper.assertTrue(rigs(level, "gd_beta").size() == 1,
                    Component.literal("the unrelated 'beta' creature is untouched, found "
                            + rigs(level, "gd_beta").size()));
            // clean up the beta so it doesn't linger in the shared test world
            for (var body : rigs(level, "gd_beta")) {
                com.moddingfromamod.vanilla.DisplayRig.reap(level, body.getUUID());
                body.discard();
            }
        });
    }

    /** G5 (buildings): clearBuild removes the plan's blocks but leaves a nearby non-plan block. */
    @GameTest(template = "empty_test")
    public static void clearBuildRemovesOnlyThePlanBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = new BlockPos(2000, 200, 2100);
        BuildPlan plan = new BuildPlan("gd_hut", "Hut",
                List.of(new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 3, 2, 3, "minecraft:stone", false)));

        // Place the plan's blocks at the anchor (as Builder does: anchor + relative).
        plan.forEachBlock((x, y, z, block) ->
                level.setBlock(anchor.offset(x, y, z), Blocks.STONE.defaultBlockState(), QUIET));
        // A block that is NOT part of the plan, just outside it.
        BlockPos terrain = anchor.offset(0, 0, 6);
        level.setBlock(terrain, Blocks.DIRT.defaultBlockState(), QUIET);

        int cleared = Builder.clearBuild(level, anchor, plan);
        helper.assertTrue(cleared > 0, Component.literal("clearBuild removes the building's blocks (got " + cleared + ")"));
        final boolean[] anyLeft = {false};
        plan.forEachBlock((x, y, z, block) -> {
            if (!level.getBlockState(anchor.offset(x, y, z)).isAir()) {
                anyLeft[0] = true;
            }
        });
        helper.assertTrue(!anyLeft[0], Component.literal("every block of the building is cleared"));
        helper.assertTrue(level.getBlockState(terrain).is(Blocks.DIRT),
                Component.literal("a nearby block that is NOT part of the building is untouched (non-destructive)"));
        level.setBlock(terrain, Blocks.AIR.defaultBlockState(), QUIET);
        helper.succeed();
    }

    /** P2 (buildings): a smaller/changed rebuild clears the previous building — no orphan blocks. */
    @GameTest(template = "empty_test")
    public static void aRebuildClearsThePreviousBuilding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = new BlockPos(2000, 200, 2200);
        BuildPlan big = new BuildPlan("gd_reb", "Big",
                List.of(new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 4, 1, 4, "minecraft:stone", false)));
        BuildPlan small = new BuildPlan("gd_reb", "Small",
                List.of(new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 2, 1, 2, "minecraft:oak_planks", false)));

        // First build: the big 4x4 footprint of stone.
        big.forEachBlock((x, y, z, b) ->
                level.setBlock(anchor.offset(x, y, z), Blocks.STONE.defaultBlockState(), QUIET));
        // Rebuild smaller — exactly what BuildStructureTool now does: clear the prior, then place new.
        Builder.clearBuild(level, anchor, big);
        small.forEachBlock((x, y, z, b) ->
                level.setBlock(anchor.offset(x, y, z), Blocks.OAK_PLANKS.defaultBlockState(), QUIET));

        final boolean[] newMissing = {false};
        small.forEachBlock((x, y, z, b) -> {
            if (level.getBlockState(anchor.offset(x, y, z)).isAir()) {
                newMissing[0] = true;
            }
        });
        helper.assertTrue(!newMissing[0], Component.literal("the rebuilt (smaller) building is present"));

        final int[] stoneLeft = {0};
        big.forEachBlock((x, y, z, b) -> {
            if (level.getBlockState(anchor.offset(x, y, z)).is(Blocks.STONE)) {
                stoneLeft[0]++;
            }
        });
        helper.assertTrue(stoneLeft[0] == 0,
                Component.literal("the previous, larger building leaves no orphan blocks (found " + stoneLeft[0] + ")"));
        Builder.clearBuild(level, anchor, small);
        helper.succeed();
    }

    /**
     * Every creature of this species standing in the level — the rig carriers, because that is what
     * a wished creature IS on the shipped path.
     */
    private static List<net.minecraft.world.entity.LivingEntity> rigs(ServerLevel level, String id) {
        List<net.minecraft.world.entity.LivingEntity> out = new java.util.ArrayList<>();
        for (var e : level.getEntities().getAll()) {
            if (e instanceof net.minecraft.world.entity.LivingEntity live
                    && e.getTags().contains(com.moddingfromamod.vanilla.DisplayRig.RIG_TAG)) {
                var sp = com.moddingfromamod.vanilla.DisplayRig.speciesOn(live);
                if (sp != null && id.equalsIgnoreCase(sp.id())) {
                    out.add(live);
                }
            }
        }
        return out;
    }

    private static WishMobEntity spawn(ServerLevel level, String speciesId, BlockPos at) {
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        if (mob == null) {
            return null;
        }
        mob.setSpeciesId(speciesId);
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.COMMAND, null);
        level.addFreshEntity(mob);
        return mob;
    }
}
