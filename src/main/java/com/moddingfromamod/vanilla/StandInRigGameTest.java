package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>an earlier issue's first blocker, driven where it RUNS rather than read where it is defined.</b>
 *
 * <p>The issue records the fallback as a <i>READ claim, not a run</i>, and asks for it to be
 * measured. Two creatures could not be rigged and were left on {@code WishMobRenderer} — a species
 * with no custom model, and a model with no sculpt spec. Both branches of the trade that justified
 * that are client-side mod code, and v1 ships to a stock client, so for the child this is built for
 * the fallback produced <b>an invisible creature</b>.
 *
 * <p>{@code StandInSculptTest} proves the parts exist. This proves the <b>bridge</b> uses them: the
 * wish mob is gone from the level and displays a stock client can draw are standing in its place.
 * Those are two different questions, and the second is the one asks — the guard and its caller
 * (CLAUDE.md's <em>"a rule tested only where it is DEFINED is not tested where it RUNS"</em>).
 *
 * <p>⚠ Each test names its own {@code batch}. These plant species into a shared registry and wait
 * on entity counts inside their own plot; a sibling in the same batch doing the same thing is the
 * concurrency hazard CLAUDE.md names, and batches are strictly sequential.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class StandInRigGameTest {

    private static final BlockPos SPOT = new BlockPos(2, 2, 2);

    /** A species with NO model key at all — an earlier issue's fallback 1, and `define_creature`'s own output. */
    private static String speciesWithNoModel(GameTestHelper helper, String id, String body) {
        com.moddingfromamod.testing.SpeciesForTest.put(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"body\":\"" + body + "\",\"tint\":\"#AA55AA\","
                        + "\"health\":20,\"speed\":0.0,\"scale\":1.0}").getAsJsonObject()));
        return id;
    }

    /** A species that CLAIMS a model nothing was ever written for — an earlier issue's fallback 2. */
    private static String speciesWithAMissingModel(GameTestHelper helper, String id) {
        com.moddingfromamod.testing.SpeciesForTest.put(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"modelKey\":\"never_written_" + id + "\","
                        + "\"body\":\"quadruped\",\"tint\":\"#22455E\","
                        + "\"health\":20,\"speed\":0.0,\"scale\":1.0}").getAsJsonObject()));
        return id;
    }

    private static WishMobEntity wish(GameTestHelper helper, String id) {
        ServerLevel level = helper.getLevel();
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        if (mob == null) {
            helper.fail(Component.literal("could not create a wish mob"));
            return null;
        }
        var at = helper.absolutePos(SPOT);
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setSpeciesId(id);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.COMMAND, null);
        level.addFreshEntity(mob);
        return mob;
    }

    private static List<Entity> inPlot(GameTestHelper helper) {
        var box = helper.getBounds().inflate(2);
        List<Entity> out = new ArrayList<>();
        for (Entity e : helper.getLevel().getEntities().getAll()) {
            if (box.contains(e.getX(), e.getY(), e.getZ())) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * The assertion both cases share, written once because they are one claim: <b>nothing a stock
     * client cannot draw is left standing, and something it can draw is.</b>
     */
    private static void assertManifested(GameTestHelper helper, WishMobEntity mob, String what) {
        helper.succeedWhen(() -> {
            helper.assertTrue(mob == null || helper.getLevel().getEntity(mob.getUUID()) == null,
                    Component.literal(what + ": the wish mob is still in the level. Before #497 this was the "
                            + "DOCUMENTED behaviour -- the bridge returned early and left the "
                            + "creature on the legacy renderer, which a stock client does not have"));
            for (Entity e : inPlot(helper)) {
                helper.assertFalse(e instanceof WishMobEntity,
                        Component.literal(what + ": a wish mob survived the bridge"));
            }
            long displays = inPlot(helper).stream().filter(e -> e instanceof Display).count();
            helper.assertTrue(displays > 0,
                    Component.literal(what + ": 0 display entities -- the creature is invisible to a stock client, "
                            + "which is exactly the disparity #497 asked to be measured"));
        });
    }

    @GameTest(template = "empty_test", batch = "standInNoModel", timeoutTicks = 400)
    public static void aSpeciesWithNoModelStillManifestsAsARig(GameTestHelper helper) {
        String id = speciesWithNoModel(helper, "standin_nomodel", "quadruped");
        assertManifested(helper, wish(helper, id), "a species with no custom model");
    }

    @GameTest(template = "empty_test", batch = "standInNoSpec", timeoutTicks = 400)
    public static void aModelWithNoSculptSpecStillManifestsAsARig(GameTestHelper helper) {
        String id = speciesWithAMissingModel(helper, "standin_nospec");
        assertManifested(helper, wish(helper, id), "a model with no sculpt spec");
    }

    /**
     * Every body shape, through the real bridge — because the stand-in's shapes are a map over an
     * enum, and a map over an enum is exactly the thing that goes stale when a value is added.
     * {@code StandInSculptTest} covers the enum; this covers the enum <i>through the bridge</i>,
     * where a shape that produces a degenerate box would spawn zero displays and still look fine
     * from a unit test.
     */
    @GameTest(template = "empty_test", batch = "standInBodies", timeoutTicks = 600)
    public static void everyBodyShapeManifests(GameTestHelper helper) {
        List<WishMobEntity> mobs = new ArrayList<>();
        for (Species.Body body : Species.Body.values()) {
            String id = speciesWithNoModel(helper, "standin_b_" + body.name().toLowerCase(
                    java.util.Locale.ROOT), body.name().toLowerCase(java.util.Locale.ROOT));
            mobs.add(wish(helper, id));
        }
        helper.succeedWhen(() -> {
            for (WishMobEntity mob : mobs) {
                helper.assertTrue(mob == null || helper.getLevel().getEntity(mob.getUUID()) == null,
                        Component.literal("a body shape left its wish mob standing"));
            }
            long displays = inPlot(helper).stream().filter(e -> e instanceof Display).count();
            helper.assertTrue(displays >= Species.Body.values().length,
                    Component.literal("expected at least one display per body shape ("
                            + Species.Body.values().length + "); found " + displays));
        });
    }
}
