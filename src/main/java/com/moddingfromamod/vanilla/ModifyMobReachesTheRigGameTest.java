package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"Make it bigger" has to reach the creature that actually ships.</b>
 *
 * <p>{@code ModifyMobTool.persistToSpecies} asked {@code e instanceof WishMobEntity} and nothing
 * else. That was complete for exactly as long as a wish creature could only BE a
 * {@code WishMobEntity}, and stopped being complete the day made the display rig the default:
 * {@code VanillaRigBridge.onJoin} cancels that join and dresses a vanilla base instead. So on the
 * shipping path the species record was never edited — and the tool's own javadoc says what that
 * costs: <i>"the next one the child asks for comes back the original size, because the saved
 * definition never learned anything."</i>
 *
 * <p><b>The assertion is on the SPECIES, not on the entity.</b> Setting {@code minecraft:scale} on
 * the thing standing there is what the fallback route already did and is not the property in
 * question — worse, on a rig that number is a stretch factor for the CARRIER (the shadow-row lesson
 * {@code docs/VANILLA_RIG_ANIMATION_LADDER.md} records, and an earlier issue's second half). What "make it
 * bigger" promises is that it STICKS, and the only thing that can keep that promise is the record.
 *
 * <p>Verified to fail: with the {@code instanceof} gate restored this reports the species scale
 * unchanged at its planted value.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ModifyMobReachesTheRigGameTest {

    private static final String ID = "mfam_rig_scale_test";

    // ⚠ ONE NAMED BATCH PER TEST, and the reason is a STATIC. Both tests here flip
    // VanillaRigFlags.forceForTest — one to "on", the other to "off" — and tests in the SAME batch
    // run concurrently (CLAUDE.md), so the "on" test rigged the legacy test's freshly-spawned wish
    // mob onto a horse carrier a tick after it was placed, and modify_mob then reported
    // "No mob matching wish_mob within 24 blocks. Nearby right now: minecraft:horse" — the horse
    // being that very creature. (Both also work at WORLD SPAWN and reap each other's mobs, which
    // is the second face, "Changed Bot (@wish_mob2)".) Measured 2026-09-11/12: red in 7 of 22
    // default-tier runs across six commits and 4 of the last 6, always green alone; one shared
    // batch for the class did NOT fix it (a batch serialises against OTHER batches, not within
    // itself). Batches are strictly sequential, so two names is the isolation; ~3 s each.
    private static final double PLANTED_SCALE = 1.0;
    private static final double ASKED_SCALE = 3.0;

    @GameTest(template = "empty_test", batch = "modifymob_rig_on", timeoutTicks = 600)
    public static void makeItBiggerReachesARiggedCreature(GameTestHelper helper) {
        plantSpecies(helper);
        LivingEntity carrier = riggedCreatureBesideTheChild(helper);
        if (carrier == null) {
            // NOT a pass. The bridge declining to rig is the one thing that would make every
            // assertion below vacuous, so it is named rather than skipped.
            helper.fail(Component.literal("the bridge did not produce a rig for '" + ID + "', so there is "
                    + "nothing on the shipping path to modify"));
            return;
        }
        String type = EntityType.getKey(carrier.getType()).toString();
        helper.assertFalse(carrier instanceof WishMobEntity,
                Component.literal("the whole point is that the shipping creature is NOT a WishMobEntity — it is "
                        + "a " + type));

        // BY ITS OWN NAME. This targeted the carrier's TYPE — production's
        // workaround, which also reaches every real mob of that type — and stayed green while
        // `target:"spikor"` answered "No mob matching". CreatureResolverGameTest stands a real mob
        // of the carrier's type beside the creature; this one only asks that the edit STICKS.
        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(() -> reply.set(new ModifyMobTool().call(args(
                "{\"scope\":\"one\",\"target\":\"" + ID + "\",\"count\":1,"
                        + "\"scale\":" + ASKED_SCALE + "}"))));

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            helper.assertFalse(r.isError(), Component.literal("modify_mob should have worked: " + r.text()));
            Species after = SpeciesRegistry.get(ID);
            helper.assertTrue(after != null, Component.literal("the species vanished mid-test"));
            helper.assertTrue(after.scale() > PLANTED_SCALE,
                    Component.literal("\"make it bigger\" must be SAVED to the creature's design, or the next one "
                            + "comes back the original size. planted " + PLANTED_SCALE
                            + ", still " + after.scale() + " after modify_mob said: " + r.text()));
        });
    }


    /**
     * <b>"Make it rideable" is SAVED to the design</b> (2026-10-11). Live: a rideable T-Rex was made
     * rideable on the animal only, so the copy his button brought back could not be ridden. The
     * assertion is on the species, the thing every later spawn is built from.
     */
    @GameTest(template = "empty_test", batch = "modifymob_rig_ride", timeoutTicks = 600)
    public static void makeItRideableIsSavedToTheDesign(GameTestHelper helper) {
        String id = "mfam_rig_ride_test";
        plantSpecies(helper, id);
        LivingEntity carrier = riggedCreatureBesideTheChild(helper, id);
        if (carrier == null) {
            helper.fail(Component.literal("the bridge did not produce a rig for '" + id + "'"));
            return;
        }
        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(() -> reply.set(new ModifyMobTool().call(args(
                "{\"scope\":\"one\",\"target\":\"" + id + "\",\"count\":1,\"rideable\":true}"))));
        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            helper.assertFalse(r.isError(), Component.literal("modify_mob should have worked: " + r.text()));
            String worn = com.moddingfromamod.vanilla.DisplayRig.speciesOn(carrier) == null ? id
                    : com.moddingfromamod.vanilla.DisplayRig.speciesOn(carrier).id();
            Species after = SpeciesRegistry.get(worn);
            helper.assertTrue(after != null && after.has(Species.Power.RIDEABLE),
                    Component.literal("\"make it rideable\" must be SAVED to the creature's design, or the copy his "
                            + "button brings back cannot be ridden. species '" + worn + "' powers "
                            + (after == null ? "none" : after.powers()) + " after: " + r.text()));
        });
    }

    /**
     * <b>A temperament, a power and a move are SAVED to the design</b>: the same words a real
     * mob takes ({@code RealMobBehaviours}) land, on a sculpted creature, where it keeps them — so
     * "make it fight monsters for me" means the same on a sculpted dragon as on a real enderman, and
     * his button's copy fights too.
     */
    @GameTest(template = "empty_test", batch = "modifymob_rig_guard", timeoutTicks = 600)
    public static void aTemperamentIsSavedToTheDesign(GameTestHelper helper) {
        String id = "mfam_rig_guard_test";
        plantSpecies(helper, id);
        LivingEntity carrier = riggedCreatureBesideTheChild(helper, id);
        if (carrier == null) {
            helper.fail(Component.literal("the bridge did not produce a rig for '" + id + "'"));
            return;
        }
        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(() -> reply.set(new ModifyMobTool().call(args(
                "{\"scope\":\"one\",\"target\":\"" + id + "\",\"count\":1,\"base\":\"guardian\","
                        + "\"powers\":[\"fireproof\"],\"abilities\":[\"slam\"]}"))));
        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            helper.assertFalse(r.isError(), Component.literal("modify_mob should have worked: " + r.text()));
            String worn = com.moddingfromamod.vanilla.DisplayRig.speciesOn(carrier) == null ? id
                    : com.moddingfromamod.vanilla.DisplayRig.speciesOn(carrier).id();
            Species after = SpeciesRegistry.get(worn);
            helper.assertTrue(after != null && after.base() == Species.Base.GUARDIAN
                            && after.has(Species.Power.FIREPROOF)
                            && after.abilities().contains(com.moddingfromamod.runtime.Abilities.Kind.SLAM),
                    Component.literal("base, powers and abilities must be SAVED to the design. species '" + worn + "': "
                            + (after == null ? "none" : after.base() + " " + after.powers() + " " + after.abilities())
                            + " after: " + r.text()));
        });
    }

    // ===================== helpers =====================

    private static void plantSpecies(GameTestHelper helper) {
        plantSpecies(helper, ID);
    }

    /** A real species with a real sculpt on disk — a rig is built from the parts LIST. */
    private static void plantSpecies(GameTestHelper helper, String id) {
        java.util.List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        try {
            java.nio.file.Path spec = SpikeFixture.find(
                    SpikeFixture.CORPUS_DIR + "/" + SpikeFixture.SPIKOR + "/sculpt.json");
            if (spec == null) {
                helper.fail(Component.literal("the corpus sculpt is missing — this test cannot measure the tool"));
            }
            CreatureFiles.write(id, parts, java.nio.file.Files.readString(spec));
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the sculpt: " + e));
        }
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(
                JsonParser.parseString("{\"id\":\"" + id + "\",\"modelKey\":\"" + id + "\","
                        + "\"health\":40,\"speed\":0.0,\"scale\":" + PLANTED_SCALE + "}")
                        .getAsJsonObject()));
    }

    /**
     * Spawn the creature the way a wish does and let the bridge convert it.
     *
     * <p>In front of a stand-in child in the test's own plot ({@code PlotChild.standHere}), because
     * {@code modify_mob} searches around the child. It used to be world spawn, where the child is with
     * nobody online, and nothing keeps world spawn loaded from 1.21.9. The world is reused between
     * runs, so any rig left near here by an earlier one is cleared first — by TAG, which touches only
     * this feature's own leftovers rather than a sibling test's animal.
     */
    private static LivingEntity riggedCreatureBesideTheChild(GameTestHelper helper) {
        return riggedCreatureBesideTheChild(helper, ID);
    }

    private static LivingEntity riggedCreatureBesideTheChild(GameTestHelper helper, String id) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        Vec3 at = clearAroundTheChild(helper, overworld);
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(overworld, EntitySpawnReason.COMMAND);
        helper.assertTrue(mob != null, Component.literal("could not create a wish mob"));
        mob.snapTo(at.x, at.y, at.z, 0f, 0f);
        mob.setSpeciesId(id);
        mob.finalizeSpawn(overworld, overworld.getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.COMMAND, null);
        overworld.addFreshEntity(mob);
        // onJoin cancels and re-spawns on the server's own queue, so the carrier arrives a tick
        // later. Run that queue rather than guessing at a delay.
        helper.getLevel().getServer().managedBlock(() -> carrierAt(overworld, at) != null);
        return carrierAt(overworld, at);
    }

    private static LivingEntity carrierAt(ServerLevel level, Vec3 at) {
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(at, at).inflate(24))) {
            if (e.getTags().contains(DisplayRig.RIG_TAG)) {
                return e;
            }
        }
        return null;
    }

    private static Vec3 clearAroundTheChild(GameTestHelper helper, ServerLevel overworld) {
        Vec3 child = com.moddingfromamod.testing.PlotChild.standHere(helper);
        // THE THIRD FACE of this test's flake, found only once the batches were split: "Which one?
        // This wish has already changed @wish_mob5 (Bot), @wish_mob4 (Bot)". modify_mob's referent
        // lives in the process-wide EntityHandles, so every wish mob any EARLIER test
        // changed is still "the one this conversation is about", and a bare type query is then a
        // refusal by design. This test is about a creature it plants itself, so it starts from no
        // conversation. Safe here because a named batch runs alone: nothing else is mid-wish.
        com.moddingfromamod.mob.EntityHandles.global().clear();
        Vec3 at = child.add(4, 0, 0);
        for (LivingEntity stale : overworld.getEntitiesOfClass(LivingEntity.class,
                new AABB(at, at).inflate(64),
                e -> e.getTags().contains(DisplayRig.RIG_TAG) || e instanceof WishMobEntity)) {
            DisplayRig.reap(overworld, stale.getUUID());
            stale.discard();
        }
        return at;
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void offThread(Runnable work) {
        Thread t = new Thread(work, "modify-mob-rig-gametest");
        t.setDaemon(true);
        t.start();
    }
}
