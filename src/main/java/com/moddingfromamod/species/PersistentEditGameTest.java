package com.moddingfromamod.species;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for edits that <b>stick</b>: "make it bigger" must change the creature standing there,
 * every other one like it, and every one made afterwards.
 *
 * <p>The first test here is a regression test for a bug that shipped and that the existing suite
 * could not have caught. Creatures re-applied their species when the species <i>id</i> changed —
 * but an edit keeps the id and changes the contents, so edits never reached creatures already in
 * the world, while the tool description and docs both promised they did. The old test passed
 * because it called {@code applySpecies()} by hand, so it validated the method rather than the
 * behaviour. These drive the <b>tick</b>, which is what actually happens in play.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PersistentEditGameTest {

    private static Species species(String json) {
        return Species.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static WishMobEntity spawn(GameTestHelper helper, Species s, int x) {
        ServerLevel level = helper.getLevel();
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, s);
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        BlockPos at = helper.absolutePos(new BlockPos(x, 2, 3));
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        mob.setSpeciesId(s.id());
        level.addFreshEntity(mob);
        return mob;
    }

    /**
     * <b>The regression.</b> Edit a species and the creature already in the world must grow — with
     * nobody calling applySpecies() by hand. Before the revision counter this failed: the id was
     * unchanged, so the entity never noticed.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void editingASpeciesReachesCreaturesAlreadyInTheWorld(GameTestHelper helper) {
        spawn(helper,
                species("{\"id\":\"pers_dragon\",\"name\":\"Pink Dragon\",\"scale\":1.0,\"health\":20}"), 3);

        // ⚠ THE SPAWNED ENTITY IS NOT WHAT STANDS THERE. `VanillaRigBridge` is
        // total: the wish mob is cancelled and a rig carrier takes its place at the end of the
        // tick. This test used to hold the mob and read `minecraft:scale` off it -- an object that
        // was never in the level, whose attributes still answer, so BOTH the "starts small" line
        // and the growth assertion were about a creature nobody could see.
        //
        // What a child means by "it grew" is the HEIGHT of the thing standing in the world, so
        // that is what is measured, on the carrier whose dimensions the rig refreshes to the
        // model's own extents. The carrier's own `minecraft:scale` is deliberately NOT read: it is
        // `RigBase.choose`'s fitting factor, a fact about the carrier rather than the creature.
        double[] small = {-1};
        helper.succeedWhen(() -> {
            var before = com.moddingfromamod.testing.RigProbe.carrierFor(
                    helper.getLevel(), "pers_dragon");
            helper.assertTrue(before != null, Component.literal("the creature has not manifested as a rig yet"));
            if (small[0] < 0) {
                small[0] = before.getBbHeight();
                // Exactly what "make it bigger" does now: rewrite the species, same id.
                // NOTE: no applySpecies() call anywhere. The world must notice on its own.
                com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, species(
                        "{\"id\":\"pers_dragon\",\"name\":\"Pink Dragon\",\"scale\":4.0,"
                                + "\"health\":20}"));
                helper.fail(Component.literal("the edit has only just been made"));
            }
            helper.assertTrue(before.getBbHeight() > small[0] + 0.05,
                    Component.literal("the creature already in the world must grow on its own; it was "
                            + small[0] + " blocks tall and is " + before.getBbHeight()));
        });
    }

    /** And the NEXT one of that kind is born big — the half the child notices tomorrow. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void theNextCreatureOfThatKindInheritsTheEdit(GameTestHelper helper) {
        spawn(helper, species("{\"id\":\"pers_blorp\",\"scale\":1.0,\"health\":20}"), 3);
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, species("{\"id\":\"pers_blorp\",\"scale\":3.0,\"health\":80}"));

        spawn(helper, SpeciesRegistry.get("pers_blorp"), 5);
        // Same correction as above, and this one used to PASS while measuring nothing: it read the
        // attributes off a mob the bridge had already cancelled, in the same tick, before the rig
        // existed. The health is the assertion that survives unchanged, because `applyVitals` puts
        // the species' own numbers on the carrier.
        helper.succeedWhen(() -> {
            var body = com.moddingfromamod.testing.RigProbe.carrierFor(
                    helper.getLevel(), "pers_blorp");
            helper.assertTrue(body != null, Component.literal("the newly summoned one has not manifested yet"));
            helper.assertTrue(body.getMaxHealth() == 80.0,
                    Component.literal("a newly summoned one must inherit the edit's health, was "
                            + body.getMaxHealth()));
        });
    }

    /** An edit must not quietly reset everything else it didn't mention. */
    @GameTest(template = "empty_test")
    public static void editingOneKnobLeavesTheRestAlone(GameTestHelper helper) {
        Species original = species(
                "{\"id\":\"pers_keep\",\"name\":\"Keeper\",\"body\":\"spider\",\"texture\":\"cave_spider\","
                        + "\"scale\":1.0,\"health\":90,\"damage\":7,\"armor\":5}");
        Species bigger = original.with(4.0, null, null, null, null, null);

        helper.assertTrue(bigger.scale() == 4.0, Component.literal("the change applies"));
        helper.assertTrue(bigger.health() == 90, Component.literal("health survives, was " + bigger.health()));
        helper.assertTrue(bigger.damage() == 7, Component.literal("damage survives"));
        helper.assertTrue(bigger.armor() == 5, Component.literal("armour survives"));
        helper.assertTrue(bigger.body() == Species.Body.SPIDER, Component.literal("it is still the same creature"));
        helper.assertTrue("Keeper".equals(bigger.name()), Component.literal("and keeps its name"));
        helper.succeed();
    }

    /**
     * A building the child edits must be rebuilt WHERE IT STANDS. Otherwise "give it crenellations"
     * puts a second castle wherever they happen to be, and they end up with two — one of them the
     * old, wrong one.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void anEditedBuildingRemembersWhereItStands(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        BuildPlan v1 = BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"pers_tower","name":"Tower","shapes":[
              {"kind":"box","width":4,"height":4,"depth":4,"block":"minecraft:stone_bricks"}]}
            """).getAsJsonObject());
        BuildRegistry.put(server, v1);

        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1));
        Builder.submit(helper.getLevel(), anchor, v1);

        Long remembered = BuildRegistry.anchorOf("pers_tower");
        helper.assertTrue(remembered != null, Component.literal("the build should have recorded where it went"));
        helper.assertTrue(BlockPos.of(remembered).equals(anchor),
                Component.literal("and it must be the anchor we built at, got " + BlockPos.of(remembered)));
        helper.succeed();
    }

    /** Editing a building updates the SAVED plan, so wishbuild produces the new version. */
    // ITS OWN BATCH: BuildRegistry.load() clears every plan in memory and reads back only what the
    // shelves hold — and a GameTest plan with no owner is never written there. So a reload mid-batch
    // deleted a neighbour's plan between its put and its use ("no saved design under that id",
    // BuildingPhotoGameTest, twice in four runs on 2026-09-27).
    @GameTest(template = "empty_test", batch = "buildreload")
    public static void editingABuildingUpdatesWhatWishbuildWillProduce(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        BuildRegistry.put(server, BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"pers_hut","name":"Hut","shapes":[
              {"kind":"box","width":4,"height":4,"depth":4,"block":"minecraft:stone_bricks"}]}
            """).getAsJsonObject()));
        int before = BuildRegistry.get("pers_hut").volume();

        // "give it crenellations" — same id, an extra shape.
        BuildRegistry.put(server, BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"pers_hut","name":"Hut","shapes":[
              {"kind":"box","width":4,"height":4,"depth":4,"block":"minecraft:stone_bricks"},
              {"kind":"box","dy":4,"width":4,"height":1,"depth":4,
               "block":"minecraft:stone_brick_wall"}]}
            """).getAsJsonObject()));

        BuildRegistry.load(server);   // what happens on the next world load
        BuildPlan after = BuildRegistry.get("pers_hut");
        helper.assertTrue(after != null && after.shapes().size() == 2,
                Component.literal("the saved building must have gained the crenellations"));
        helper.assertTrue(after.volume() > before,
                Component.literal("and be bigger than before (" + after.volume() + " vs " + before + ")"));
        helper.assertTrue(BuildRegistry.ids().stream().filter(i -> i.equals("pers_hut")).count() == 1,
                Component.literal("editing must REPLACE the building, not leave two versions behind"));
        helper.succeed();
    }
}
