package com.moddingfromamod.species;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * Gate B for the Creature Maker: the claims that only a running server can prove — that a species'
 * numbers really land on a live entity, that a giant creature really is giant, and that editing a
 * species updates creatures already in the world.
 */
@GameTestHolder(GateShards.CI)
public class SpeciesGameTest {

    private static Species species(String json) {
        return Species.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static WishMobEntity spawn(GameTestHelper helper, Species s) {
        ServerLevel level = helper.getLevel();
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, s);
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        mob.setSpeciesId(s.id());
        level.addFreshEntity(mob);
        return mob;
    }

    /** The entity type exists and can be spawned at all (a missing attribute would crash here). */
    @GameTest(template = "empty_test")
    public static void wishCreatureCanSpawn(GameTestHelper helper) {
        WishMobEntity mob = spawn(helper, species(
                "{\"id\":\"testy\",\"name\":\"Testy\",\"health\":30}"));
        helper.assertTrue(mob.isAlive(), Component.literal("the creature should be alive after spawning"));
        helper.succeed();
    }

    /** A species' numbers are actually applied to the live entity. */
    @GameTest(template = "empty_test")
    public static void speciesStatsAreAppliedToTheEntity(GameTestHelper helper) {
        WishMobEntity mob = spawn(helper, species(
                "{\"id\":\"tank\",\"name\":\"Tank\",\"health\":120,\"damage\":9,\"armor\":6,"
                        + "\"speed\":0.4,\"scale\":3.0,\"knockbackResistance\":0.8}"));

        helper.assertTrue(mob.getAttributeValue(Attributes.MAX_HEALTH) == 120.0,
                Component.literal("health should be 120, was " + mob.getAttributeValue(Attributes.MAX_HEALTH)));
        helper.assertTrue(mob.getAttributeValue(Attributes.ATTACK_DAMAGE) == 9.0, Component.literal("damage"));
        helper.assertTrue(mob.getAttributeValue(Attributes.ARMOR) == 6.0, Component.literal("armor"));
        helper.assertTrue(mob.getAttributeValue(Attributes.SCALE) == 3.0,
                Component.literal("scale should be 3.0, was " + mob.getAttributeValue(Attributes.SCALE)));
        helper.assertTrue(mob.getHealth() == 120.0f, Component.literal("current health should start full"));
        helper.succeed();
    }

    /** A big scale really does produce a big creature — the hitbox grows with the model. */
    @GameTest(template = "empty_test")
    public static void scaleGrowsTheActualHitbox(GameTestHelper helper) {
        WishMobEntity small = spawn(helper, species("{\"id\":\"smol\",\"scale\":0.5}"));
        float smallWidth = small.getBbWidth();
        WishMobEntity big = spawn(helper, species("{\"id\":\"chonky\",\"scale\":4.0}"));
        float bigWidth = big.getBbWidth();
        helper.assertTrue(bigWidth > smallWidth * 2,
                Component.literal("a scale-4 creature should be much wider than a scale-0.5 one ("
                        + bigWidth + " vs " + smallWidth + ")"));
        helper.succeed();
    }

    /**
     * Editing a species updates creatures ALREADY in the world — this is what makes "make it bigger!"
     * work without the child having to lose the creature he already has.
     */
    @GameTest(template = "empty_test", timeoutTicks = 120)
    public static void editingASpeciesUpdatesExistingCreatures(GameTestHelper helper) {
        WishMobEntity mob = spawn(helper, species("{\"id\":\"grower\",\"health\":20,\"scale\":1.0}"));
        helper.assertTrue(mob.getAttributeValue(Attributes.MAX_HEALTH) == 20.0, Component.literal("starts at 20 health"));

        // The agent re-defines the same id with bigger numbers.
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, species("{\"id\":\"grower\",\"health\":90,\"scale\":2.5}"));
        mob.applySpecies();

        helper.assertTrue(mob.getAttributeValue(Attributes.MAX_HEALTH) == 90.0,
                Component.literal("health should have grown to 90, was " + mob.getAttributeValue(Attributes.MAX_HEALTH)));
        helper.assertTrue(mob.getAttributeValue(Attributes.SCALE) == 2.5, Component.literal("scale should have grown"));
        helper.succeed();
    }

    /** An unknown species must not crash the entity — it just stays a default creature. */
    @GameTest(template = "empty_test")
    public static void anUnknownSpeciesIsSurvivable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        mob.setSpeciesId("does_not_exist");
        level.addFreshEntity(mob);
        helper.assertTrue(mob.isAlive(), Component.literal("a creature with a missing species should still be alive"));
        helper.assertTrue(mob.species() == null, Component.literal("and simply have no species"));
        helper.succeed();
    }

    /**
     * <b>The persistence question.</b> A creature must come back as ITSELF after the world is saved
     * and reloaded — same species, same size, same stats — and must NOT silently heal.
     */
    @GameTest(template = "empty_test")
    public static void creaturesSurviveSaveAndReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WishMobEntity original = spawn(helper, species(
                "{\"id\":\"survivor\",\"name\":\"Survivor\",\"body\":\"spider\","
                        + "\"texture\":\"cave_spider\",\"scale\":2.5,\"health\":100,\"damage\":7}"));
        original.setHealth(40.0f); // the child fought it

        // Exactly what the game does on save: write to NBT, then read it back into a new instance.
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        com.moddingfromamod.compat.EntityNbt.saveWithId(original, tag);

        WishMobEntity reloaded = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        com.moddingfromamod.compat.EntityNbt.load(reloaded, tag);

        helper.assertTrue("survivor".equals(reloaded.speciesId()),
                Component.literal("species must survive the save, got '" + reloaded.speciesId() + "'"));
        helper.assertTrue(reloaded.species() != null
                        && reloaded.species().body() == Species.Body.SPIDER,
                Component.literal("it must come back as the same KIND of creature"));
        helper.assertTrue(reloaded.getAttributeValue(Attributes.SCALE) == 2.5,
                Component.literal("and the same size, got " + reloaded.getAttributeValue(Attributes.SCALE)));
        helper.assertTrue(reloaded.getAttributeValue(Attributes.MAX_HEALTH) == 100.0, Component.literal("same max health"));
        helper.assertTrue(reloaded.getHealth() == 40.0f,
                Component.literal("a damaged creature must NOT heal on reload — got " + reloaded.getHealth()));
        helper.succeed();
    }

    /** Many different creatures all persist independently — not just the last one. */
    @GameTest(template = "empty_test")
    public static void manyDifferentCreaturesAllPersist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String[] bodies = {"humanoid", "slime", "spider", "quadruped", "bird"};
        java.util.List<net.minecraft.nbt.CompoundTag> saved = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Species s = species("{\"id\":\"multi" + i + "\",\"name\":\"Creature " + i
                    + "\",\"body\":\"" + bodies[i % bodies.length]
                    + "\",\"scale\":" + (0.5 + i * 0.3) + ",\"health\":" + (20 + i * 10) + "}");
            WishMobEntity mob = spawn(helper, s);
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            com.moddingfromamod.compat.EntityNbt.saveWithId(mob, tag);
            saved.add(tag);
        }
        for (int i = 0; i < saved.size(); i++) {
            WishMobEntity back = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
            com.moddingfromamod.compat.EntityNbt.load(back, saved.get(i));
            helper.assertTrue(("multi" + i).equals(back.speciesId()),
                    Component.literal("creature " + i + " came back as '" + back.speciesId() + "'"));
            helper.assertTrue(back.getAttributeValue(Attributes.MAX_HEALTH) == 20 + i * 10,
                    Component.literal("creature " + i + " lost its stats"));
        }
        helper.succeed();
    }

    /** Species survive a save/load round trip through the world folder. */
    @GameTest(template = "empty_test")
    public static void speciesPersistToTheWorld(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, species(
                "{\"id\":\"persisted\",\"name\":\"Persisted\",\"body\":\"slime\",\"scale\":2.0}"));
        SpeciesRegistry.load(server); // re-read from disk
        Species back = SpeciesRegistry.get("persisted");
        helper.assertTrue(back != null, Component.literal("the species should have been written to the world"));
        helper.assertTrue(back.body() == Species.Body.SLIME && back.scale() == 2.0,
                Component.literal("and survive the round trip intact"));
        helper.succeed();
    }
}
