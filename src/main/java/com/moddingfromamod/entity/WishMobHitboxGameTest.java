package com.moddingfromamod.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for "a creature's collision box matches the creature".
 *
 * <p>Every wish mob used to inherit the registered {@code EntityType} box — {@code 0.6 x 1.95}, a
 * player — scaled only by the SCALE attribute. Spikor is 56 model units tall at scale 2.6, so he was
 * drawn 9.10 blocks high with a 5.07-block hitbox: arrows and sword swings passed straight through
 * his head and shoulders, and the wish agent (which can read the hitbox with {@code run_command})
 * was told a creature nearly twice that height was player-sized.
 *
 * <p>Unit tests cover the arithmetic ({@code CreatureAnatomyTest}). Only a real server proves the
 * two halves are actually wired together: that {@code getDefaultDimensions} is reached, that vanilla
 * applies SCALE on top exactly once, and that the box lands on a spawned entity.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishMobHitboxGameTest {

    /** A deliberately tall, narrow creature: 48 units (3 blocks) high, 8 units (0.5) wide. */
    private static List<CreatureSculpt.Part> tallParts() {
        return CreatureSculpt.parseParts(JsonParser.parseString("""
            [{"name":"body","from":[-4,0,-4],"to":[4,48,4],"colour":"#88aa33"}]
            """).getAsJsonArray());
    }

    private static Species speciesWithModel(String id, String modelKey, double scale) {
        return Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"name\":\"Hitbox Test\",\"health\":20,"
                        + "\"scale\":" + scale + ",\"modelKey\":\"" + modelKey + "\"}")
                .getAsJsonObject());
    }

    private static WishMobEntity spawn(GameTestHelper helper, String speciesId) {
        ServerLevel level = helper.getLevel();
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(mob != null, Component.literal("the wish mob entity type should create"));
        mob.setSpeciesId(speciesId);
        mob.applySpecies(true);
        net.minecraft.core.BlockPos at = helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2));
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(mob);
        return mob;
    }

    /**
     * The headline claim: the box comes from the MODEL, not from the registered player-sized box.
     */
    @GameTest(template = "empty_test")
    public static void hitboxIsMeasuredFromTheSculptedModel(GameTestHelper helper) throws Exception {
        String key = "gt_hitbox_tall";
        CreatureFiles.write(key, tallParts());
        ModelRepository.clearCache();
        com.moddingfromamod.testing.SpeciesForTest.put(helper, speciesWithModel(key, key, 1.0));

        WishMobEntity mob = spawn(helper, key);
        // 48 units / 16 = 3.0 blocks tall, 8 units / 16 = 0.5 wide — its own shape, at scale 1.
        helper.assertTrue(Math.abs(mob.getBbHeight() - 3.0f) < 0.05f,
                Component.literal("height should be the model's 3.0 blocks, was " + mob.getBbHeight()));
        helper.assertTrue(Math.abs(mob.getBbWidth() - 0.5f) < 0.05f,
                Component.literal("width should be the model's 0.5 blocks, was " + mob.getBbWidth()));
        // The regression itself: the old behaviour would have reported the 1.95-block player box.
        helper.assertTrue(Math.abs(mob.getBbHeight() - 1.95f) > 0.5f,
                Component.literal("the old fixed player-sized box must NOT be what we get back"));
        mob.discard();
        helper.succeed();
    }

    /**
     * SCALE is applied exactly once. Vanilla's {@code LivingEntity#getDimensions} is final and
     * already multiplies by {@code getScale()}, so returning an already-scaled box from
     * {@code getDefaultDimensions} would square the scale — at 2.6 that is a 6.8x creature.
     */
    @GameTest(template = "empty_test")
    public static void scaleIsAppliedOnceNotTwice(GameTestHelper helper) throws Exception {
        String key = "gt_hitbox_scaled";
        CreatureFiles.write(key, tallParts());
        ModelRepository.clearCache();
        com.moddingfromamod.testing.SpeciesForTest.put(helper, speciesWithModel(key, key, 2.0));

        WishMobEntity mob = spawn(helper, key);
        float h = mob.getBbHeight();
        helper.assertTrue(Math.abs(h - 6.0f) < 0.1f,
                Component.literal("3.0 blocks of model at scale 2.0 should be 6.0 blocks, was " + h));
        helper.assertTrue(h < 10.0f, Component.literal("a double-scaled box would be 12 blocks, was " + h));
        mob.discard();
        helper.succeed();
    }

    /**
     * A species with no sculpted model still gets a working box. This is the fallback that keeps a
     * {@code define_creature} creature — or one whose model folder has gone missing — spawnable.
     */
    @GameTest(template = "empty_test")
    public static void aCreatureWithNoModelFallsBackToTheRegisteredBox(GameTestHelper helper) {
        String id = "gt_hitbox_nomodel";
        com.moddingfromamod.testing.SpeciesForTest.put(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"name\":\"No Model\",\"health\":20,\"scale\":1.0}")
                .getAsJsonObject()));

        WishMobEntity mob = spawn(helper, id);
        helper.assertTrue(Math.abs(mob.getBbHeight() - 1.95f) < 0.05f,
                Component.literal("with no model to measure it keeps the registered box, was " + mob.getBbHeight()));
        mob.discard();
        helper.succeed();
    }

    /**
     * A species pointing at a model that isn't on disk must fall back rather than throw. Sizing is
     * never allowed to be the thing that stops a creature existing.
     */
    @GameTest(template = "empty_test")
    public static void aMissingModelDoesNotBreakSpawning(GameTestHelper helper) {
        String id = "gt_hitbox_ghost";
        com.moddingfromamod.testing.SpeciesForTest.put(helper, speciesWithModel(id, "gt_model_that_does_not_exist", 1.0));

        WishMobEntity mob = spawn(helper, id);
        helper.assertTrue(mob.isAlive(), Component.literal("the creature should still spawn and live"));
        helper.assertTrue(mob.getBbHeight() > 0.1f,
                Component.literal("and have some usable box, was " + mob.getBbHeight()));
        mob.discard();
        helper.succeed();
    }
}
