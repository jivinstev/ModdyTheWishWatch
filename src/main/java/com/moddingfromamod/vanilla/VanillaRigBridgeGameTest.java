package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Display;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>D4/B9 — the wiring, which is the thing the whole spike did not have.</b>
 *
 * <p>Every rig in this spike has been built by a harness calling {@link DisplayRig#spawn} directly.
 * {@code VanillaRigFlags.enabled()} had <b>no callers at all</b> in production — scoreboard row B9,
 * recorded as "the capability is held and the wiring is absent, which look identical from outside".
 * These tests are the wiring: a creature a WISH spawns has to become a rig, and the displays a dead
 * creature leaves behind have to be cleared by something that runs in a game.
 *
 * <h2>The over-reach guard is the important test here, not the feature</h2>
 *
 * <p>The spike's hardest constraint is that {@code wish_mob} + {@code RuntimeGeoModel} stays the
 * default and behaves <b>identically</b>. A bridge on {@code EntityJoinLevelEvent} runs on every
 * entity that joins any level, so a flag test that stopped working would quietly convert every
 * wished creature in a shipped build. {@link #withTheFlagOffNothingIsIntercepted} is what makes
 * that a failing test rather than a review comment.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class VanillaRigBridgeGameTest {

    private static final BlockPos SPOT = new BlockPos(2, 2, 2);

    /**
     * A real species with a real sculpt on disk, which is what the bridge has to look up.
     *
     * <p>🔴 <b>The SPEC, not just the baked model.</b> {@code CreatureFiles.write(key, parts, null)}
     * writes geometry, texture and animations and <em>no</em> {@code sculpt.json} — and a rig is
     * built from the parts LIST, which only the spec carries. The first cut of this fixture passed
     * {@code null} and the bridge correctly declined to convert a creature it could not rig, which
     * presented as "the wish mob should have been intercepted" and reads as a dead bridge.
     *
     * <p>The spec used is the corpus's own {@code sculpt.json} — a file a real agent really wrote —
     * so this is not a hand-built approximation of what the bridge will meet in a wish.
     */
    private static String plantSpecies(GameTestHelper helper, String id) {
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        try {
            java.nio.file.Path spec = SpikeFixture.find(
                    SpikeFixture.CORPUS_DIR + "/" + SpikeFixture.SPIKOR + "/sculpt.json");
            if (spec == null) {
                helper.fail(Component.literal("the corpus sculpt is missing -- this test cannot measure the bridge"));
            }
            CreatureFiles.write(id, parts, java.nio.file.Files.readString(spec));
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the sculpt: " + e));
        }
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"modelKey\":\"" + id + "\",\"health\":40,"
                        + "\"speed\":0.0,\"scale\":1.2}").getAsJsonObject()));
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

    /** Every entity of ours inside the plot, so a test can say what really landed. */
    /**
     * The rig's carrier in this plot, found by its tag.
     *
     * <p>By TAG rather than by type, because which vanilla mob {@code RigBase.choose} picks depends
     * on the creature's proportions — a test that named the type would be asserting on the base
     * chooser rather than on the rig, and would go red the day a creature got taller.
     */
    private static List<net.minecraft.world.entity.LivingEntity> carriers(GameTestHelper helper) {
        List<net.minecraft.world.entity.LivingEntity> out = new ArrayList<>();
        for (Entity e : inPlot(helper)) {
            if (e instanceof net.minecraft.world.entity.LivingEntity le
                    && e.getTags().contains(DisplayRig.RIG_TAG)) {
                out.add(le);
            }
        }
        return out;
    }

    private static net.minecraft.world.entity.LivingEntity carrier(GameTestHelper helper) {
        for (Entity e : inPlot(helper)) {
            if (e instanceof net.minecraft.world.entity.LivingEntity le
                    && e.getTags().contains(DisplayRig.RIG_TAG)) {
                return le;
            }
        }
        return null;
    }

    /**
     * Hatch a species' egg the way a child does: the REAL {@link com.moddingfromamod.item.WishEggItem#useOn}
     * on a real block face.
     *
     * <p><b>No mock player, deliberately.</b> {@code useOn} guards its only player use
     * ({@code instabuild}, to decide whether to shrink the stack), so a null player drives the whole
     * spawn path honestly — and this repo's mock players are a documented hazard (a leaked one has
     * already indicted seventeen innocent tests). A test that needs no player should not borrow one.
     */
    private static void hatchEgg(GameTestHelper helper, String id) {
        BlockPos anvil = SPOT.below();
        helper.setBlock(anvil, Blocks.STONE);
        BlockPos abs = helper.absolutePos(anvil);
        ItemStack egg = com.moddingfromamod.item.WishEggItem.of(
                com.moddingfromamod.item.ModItems.WISH_EGG.get(), id);
        var hit = new BlockHitResult(
                new Vec3(abs.getX() + 0.5, abs.getY() + 1, abs.getZ() + 0.5),
                Direction.UP, abs, false);
        egg.getItem().useOn(new UseOnContext(
                helper.getLevel(), null, InteractionHand.MAIN_HAND, egg, hit));
    }

    private static List<Entity> inPlot(GameTestHelper helper) {
        List<Entity> out = new ArrayList<>();
        var box = helper.getBounds();
        for (Entity e : helper.getLevel().getEntities().getAll()) {
            if (box.contains(e.getX(), e.getY(), e.getZ())) {
                out.add(e);
            }
        }
        return out;
    }

    @GameTest(template = "empty_test")
    public static void aWishedCreatureBecomesARigWhenTheFlagIsOn(GameTestHelper helper) {
        String id = plantSpecies(helper, "bridge_on");
        WishMobEntity mob = wish(helper, id);

        helper.succeedWhen(() -> {
            // 🔴 NOT `mob.isRemoved()`. Cancelling EntityJoinLevelEvent means the entity NEVER
            // JOINED -- it is not removed, it was never added, and `isRemoved()` is false on a
            // perfectly intercepted creature. Asking the LEVEL whether it holds the entity is
            // the question that matches what happened, and it is also the question a player
            // cares about. The first cut asserted `isRemoved()` and reported a dead bridge
            // while the log showed it writing a 41-model pack.
            helper.assertTrue(mob == null
                            || helper.getLevel().getEntity(mob.getUUID()) == null,
                    Component.literal("the wish mob is still in the level -- the bridge did not intercept it"));
            long displays = inPlot(helper).stream().filter(e -> e instanceof Display).count();
            helper.assertTrue(displays > 0,
                    Component.literal("the flag is on, so the wish should have manifested as display entities; "
                            + "found none"));
            // D1 again, at the seam that actually matters: a CLIENT must see nothing modded.
            for (Entity e : inPlot(helper)) {
                helper.assertFalse(e instanceof WishMobEntity,
                        Component.literal("a wish mob survived the bridge -- a stock client would see a modded "
                                + "entity type"));
            }
        });
    }

    /**
     * 🔴 <b>The spawn egg is a SECOND route into the world, and nothing was testing it.</b>
     *
     * <p>The bridge hooks {@code EntityJoinLevelEvent}, so it catches every route at once — which is
     * the right design and is exactly why it is easy to believe without checking. The egg was never
     * driven by any test: {@code WishMobRuntimeGameTest.spawnEggCarriesItsCreature} asserts the
     * stack's metadata and never uses it on a block, and no test anywhere called an egg's
     * {@code useOn}. So "an egg hatches a rig" was a reading of the code, not a measurement.
     *
     * <p>This drives the real item. Its sibling below turns the pair into an oracle: with the flag
     * off the SAME call must produce the legacy creature, so a green run here cannot be a test that
     * would pass whatever the bridge did.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void aSpawnEggHatchesARigRatherThanAWishMob(GameTestHelper helper) {
        String id = plantSpecies(helper, "egg_on");
        hatchEgg(helper, id);

        helper.succeedWhen(() -> {
            for (Entity e : inPlot(helper)) {
                helper.assertFalse(e instanceof WishMobEntity,
                        Component.literal("an egg-spawned wish mob survived the bridge -- the egg is a route into "
                                + "the world that the rig swap does not cover, so a child who "
                                + "uses an egg on a stock client sees a modded entity type"));
            }
            long displays = inPlot(helper).stream().filter(e -> e instanceof Display).count();
            helper.assertTrue(displays > 0,
                    Component.literal("the egg hatched nothing a stock client could see: 0 display entities"));
            helper.assertTrue(carrier(helper) != null,
                    Component.literal("displays exist but no tagged carrier -- the rig has no body to follow"));
        });
    }



    /**
     * B9 — and the interaction with tier 4.1 that nobody had noticed.
     *
     * <p>A reaper hung on {@code LivingDeathEvent} clears the displays the INSTANT the creature
     * dies, which deletes the twenty-tick death flop the previous tier was built to produce. The
     * hook has to be removal, not death.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void theRigIsReapedWhenItsCarrierIsREMOVEDRatherThanWhenItDies(
            GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        var at = helper.absolutePos(SPOT);
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(),
                new net.minecraft.world.phys.Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5),
                0f, parts, 1.0, 2.0, 2.0);
        int made = rig.displays().size();
        helper.assertTrue(made > 0, Component.literal("the fixture should have produced displays"));

        rig.base().hurt(rig.base().damageSources().generic(),
                rig.base().getMaxHealth() * 10f);

        // The flop needs the displays to still be there while deathTime runs.
        helper.runAfterDelay(5, () -> {
            long alive = inPlot(helper).stream().filter(e -> e instanceof Display).count();
            helper.assertTrue(alive == made,
                    Component.literal("the displays were reaped DURING the death animation (" + alive + " of "
                            + made + " left) -- the death flop has nothing left to animate"));
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(rig.base().isRemoved(), Component.literal("the carrier has not been removed yet"));
            long alive = inPlot(helper).stream().filter(e -> e instanceof Display).count();
            helper.assertTrue(alive == 0,
                    Component.literal(alive + " of " + made + " displays outlived their carrier -- a display "
                            + "entity has no despawn rule, so those are permanent"));
        });
    }

    // ─────────────────────────────────────────────────────── tier 5: the live driver ──────

    /**
     * 5.1 / 5.4 — a creature that is standing still still ANIMATES, and one that moves changes clip.
     *
     * <p>Idle is the cheapest row on the ladder and the one most likely to be silently absent: a
     * rig that never moves looks exactly like a rig whose driver was never started, and every
     * other check in this file passes on both.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void aStandingCreatureIsDrivenAndAMovingOneChangesClip(GameTestHelper helper) {
        String id = plantSpecies(helper, "bridge_idle");
        wish(helper, id);
        helper.succeedWhen(() -> {
            helper.assertTrue(VanillaRigAnimator.driving() > 0,
                    Component.literal("nothing is driving the rig -- it was spawned and left frozen, which "
                            + "looks identical to a creature with no animation at all"));
            Mob carrier = null;
            for (Entity e : inPlot(helper)) {
                if (e instanceof Mob m && m.getTags().contains(DisplayRig.RIG_TAG)) {
                    carrier = m;
                }
            }
            helper.assertTrue(carrier != null, Component.literal("no rig carrier"));
            helper.assertTrue(VanillaRigAnimator.isDriving(carrier),
                    Component.literal("the carrier that exists is not the one being driven"));

            // Standing still selects IDLE off wall time; the same creature with limb speed
            // selects WALK. This is the whole of rows 5.1 and 5.4, and it is pure.
            var standing = com.moddingfromamod.runtime.AnimationState.select(
                    false, 0f, 0f, 0f, carrier.tickCount / 20f);
            var moving = com.moddingfromamod.runtime.AnimationState.select(
                    false, 0f, 1.0f, 4.0f, carrier.tickCount / 20f);
            helper.assertTrue(standing.clip()
                            == com.moddingfromamod.runtime.BedrockAnimation.Clip.IDLE,
                    Component.literal("a standing creature should be on the idle clip, got " + standing.clip()));
            helper.assertTrue(moving.clip()
                            == com.moddingfromamod.runtime.BedrockAnimation.Clip.WALK,
                    Component.literal("a moving creature should be on the walk clip, got " + moving.clip()));
        });
    }

    /**
     * 🔴 The reload gap — an in-memory driver cannot survive a server restart, and the creature
     * is left standing in the world frozen forever.
     *
     * <p>This is the shape B9 already recorded once: the displays persist (they are tagged
     * entities), the carrier persists, and the only thing that does not is the map that says
     * "drive this one". A child saves, quits, comes back, and his creature has become a statue —
     * with no error anywhere, because nothing is wrong except that nobody is driving.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void aRigIsRE_ADOPTEDAfterTheDriverLosesItsMemory(GameTestHelper helper) {
        String id = plantSpecies(helper, "bridge_adopt");
        wish(helper, id);
        // 🔴 ONE SHOT, NOT `succeedWhen`. A succeedWhen lambda is RE-RUN until it passes, so a
        // lambda that MUTATES state corrupts its own next attempt: the first cut forgot the
        // carrier inside it, and every retry after that failed at "not driven to begin with"
        // — the assertion BEFORE the one under test. The A/B still went red, at the wrong
        // line, which is the worst kind of red: it looks like the feature and is the harness.
        helper.runAfterDelay(60, () -> {
            Mob carrier = null;
            for (Entity e : inPlot(helper)) {
                if (e instanceof Mob m && m.getTags().contains(DisplayRig.RIG_TAG)) {
                    carrier = m;
                }
            }
            if (carrier == null) {
                helper.fail(Component.literal("no rig carrier 60 ticks after the wish"));
                return;
            }
            helper.assertTrue(VanillaRigAnimator.isDriving(carrier),
                    Component.literal("not driven to begin with"));

            // Exactly what a server restart does to this component: the entities survive, the
            // map does not. Scoped to THIS carrier — see the note on the sibling test.
            VanillaRigAnimator.forget(carrier.getUUID());
            helper.assertFalse(VanillaRigAnimator.isDriving(carrier),
                    Component.literal("the test could not simulate the memory loss"));

            int adopted = VanillaRigAnimator.adoptAll(helper.getLevel());
            helper.assertTrue(adopted > 0,
                    Component.literal("nothing re-adopted the orphaned rig -- after a save/quit the creature is "
                            + "a statue, and no error says so"));
            helper.assertTrue(VanillaRigAnimator.isDriving(carrier),
                    Component.literal("adoptAll ran and this carrier is still not being driven"));
            helper.succeed();
        });
    }

    // ───────────────────────────────────────────: a walking rig must not come apart

    private static List<CreatureSculpt.Part> starterDragon() {
        try (var in = VanillaRigBridgeGameTest.class.getResourceAsStream(
                "/starter-library/species/pet_dragon/sculpt.json")) {
            return CreatureSculpt.parseParts(JsonParser.parseString(
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("parts"));
        } catch (Exception e) {
            throw new IllegalStateException("the starter dragon is not on the classpath", e);
        }
    }

    private static java.util.Map<String, com.moddingfromamod.runtime.BedrockAnimation.BonePose>
            dragonIdleSway() {
        try (var in = VanillaRigBridgeGameTest.class.getResourceAsStream(
                "/starter-library/species/pet_dragon/animations.json")) {
            var clips = com.moddingfromamod.runtime.BedrockAnimation.parseAll(JsonParser.parseString(
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject());
            var idle = com.moddingfromamod.runtime.BedrockAnimation.find(clips,
                    com.moddingfromamod.runtime.BedrockAnimation.Clip.IDLE);
            for (float t = 0.1f; t < idle.length(); t += 0.1f) {
                var pose = idle.pose(t);
                var h = pose.get("head");
                if (h != null && h.rotation() != null && Math.abs(h.rotation()[0])
                        + Math.abs(h.rotation()[1]) + Math.abs(h.rotation()[2]) > 1f) {
                    return pose;
                }
            }
            throw new IllegalStateException("the dragon's idle never sways its head");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int teleportOf(Entity d) {
        return com.moddingfromamod.compat.EntityNbt.save(d).getIntOr("teleport_duration", 0);
    }

    private static java.util.Map<Integer, Integer> teleportCensus(List<Entity> displays) {
        java.util.Map<Integer, Integer> by = new java.util.TreeMap<>();
        for (Entity d : displays) {
            by.merge(teleportOf(d), 1, Integer::sum);
        }
        return by;
    }

    /**
     * 🔴 — <b>every display of a rig eases into a MOVE the same way, whatever has posed it.</b>
     *
     * <p>{@code RigDriver.follow} moves every display to the carrier's feet every tick and the client
     * eases each over its own {@code teleport_duration}. That used to be written by every POSE, as
     * the transform tween: 0 at spawn, 3 once posed. So on the starter dragon the posed body and
     * head arrived two ticks after the never-keyed back legs, and a film showed the head sliding
     * against the body. Asserted after each thing that writes a display &mdash; a clip with a look,
     * a look alone, the death flop &mdash; and after re-adopting a rig an older jar saved with a MIX,
     * which is the live server's state on the day this lands.
     */
    @GameTest(template = "empty_test")
    public static void everyDisplayOfARigEasesIntoAMoveTheSameWay(GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = starterDragon();
        List<CreatureSculpt.Part> mirrored = RigFit.intoRigSpace(parts);
        var at = helper.absolutePos(SPOT);
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5),
                0f, parts, 1.2, 2.0, 2.0);
        try {
            java.util.Map<Integer, Integer> want =
                    java.util.Map.of(DisplayRig.MOTION_TICKS, rig.displays().size());
            helper.assertTrue(teleportCensus(rig.displays()).equals(want),
                    Component.literal("at spawn: " + teleportCensus(rig.displays()) + ", want " + want));
            RigDriver.animate(rig, mirrored, dragonIdleSway(), new float[]{30f, -5f}, 3);
            helper.assertTrue(teleportCensus(rig.displays()).equals(want),
                    Component.literal("after a clip with a look: " + teleportCensus(rig.displays()) + " -- posing a "
                            + "display changed how it eases into a MOVE, so a walking rig comes "
                            + "apart (#765)"));
            RigDriver.lookAt(rig, -20f, 0f, 3);
            RigDriver.flop(rig, 40f, 3);
            helper.assertTrue(teleportCensus(rig.displays()).equals(want),
                    Component.literal("after a look and a flop: " + teleportCensus(rig.displays())));

            // A rig saved by an older jar: a MIX of 0 and 3. Adopting it must level it.
            List<Entity> ds = rig.displays();
            for (int i = 0; i < ds.size(); i += 2) {
                var t = com.moddingfromamod.compat.EntityNbt.save(ds.get(i));
                t.putInt("teleport_duration", DisplayRig.MOTION_TICKS == 0 ? 3 : 0);
                com.moddingfromamod.compat.EntityNbt.load(ds.get(i), t);
            }
            helper.assertFalse(teleportCensus(rig.displays()).equals(want),
                    Component.literal("the scramble did nothing, so the adopt half measures nothing"));
            DisplayRig adopted = DisplayRig.adopt(helper.getLevel(), rig.base(), parts, 1.2);
            helper.assertTrue(adopted != null, Component.literal("the rig could not be re-adopted"));
            helper.assertTrue(teleportCensus(adopted.displays()).equals(want),
                    Component.literal("an adopted rig kept the mixed teleport_duration an older jar saved: "
                            + teleportCensus(adopted.displays())));
            helper.succeed();
        } finally {
            for (var d : rig.all()) {
                d.discard();
            }
            rig.base().discard();
        }
    }

    /**
     * 🔴 — <b>a look COMPOSES with the clip's head; it does not replace it.</b>
     *
     * <p>Two rigs of the starter dragon. One is written the way the shipping driver now writes a
     * frame: the idle pose (which sways the head) and the look together, in one {@code animate}.
     * The other gets the idle pose, then {@code lookAt} &mdash; the old two-writer order. Every
     * display of the head subtree must hold the SAME transform on both. {@code lookAt} used to
     * compose with a null pose, which wrote the head subtree at rest + look: the sway was thrown
     * away whenever the creature looked anywhere, and kept when it looked exactly ahead.
     */
    @GameTest(template = "empty_test")
    public static void aLookKeepsTheClipsHead(GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = starterDragon();
        List<CreatureSculpt.Part> mirrored = RigFit.intoRigSpace(parts);
        var pose = dragonIdleSway();
        var at = helper.absolutePos(SPOT);
        DisplayRig one = DisplayRig.spawn(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0f, parts, 1.2, 2.0, 2.0);
        DisplayRig two = DisplayRig.spawn(helper.getLevel(),
                new Vec3(at.getX() + 3.5, at.getY(), at.getZ() + 0.5), 0f, parts, 1.2, 2.0, 2.0);
        try {
            RigDriver.animate(one, mirrored, pose, new float[]{35f, -10f}, 3);
            RigDriver.animate(two, mirrored, pose, 3);
            helper.assertTrue(RigDriver.lookAt(two, 35f, -10f, 3) > 0, Component.literal("the look wrote nothing"));
            int compared = 0;
            List<RigPlan.Group> groups = one.groups();
            for (int i = 0; i < groups.size(); i++) {
                String bone = groups.get(i).boneName();
                if (!bone.equals("head") && !bone.equals("snout") && !bone.startsWith("eye")
                        && !bone.startsWith("kit_horn")) {
                    continue;
                }
                var a = com.moddingfromamod.compat.EntityNbt.save(one.displays().get(i))
                        .get("transformation");
                var b = com.moddingfromamod.compat.EntityNbt.save(two.displays().get(i))
                        .get("transformation");
                helper.assertTrue(java.util.Objects.equals(a, b),
                        Component.literal("the " + bone + " display differs between one write of clip+look and a "
                                + "clip then lookAt -- the look is REPLACING the clip's head "
                                + "(#765):\n  one write " + a + "\n  clip, look " + b));
                compared++;
            }
            helper.assertTrue(compared >= 5, Component.literal("only " + compared + " head displays compared"));
            helper.succeed();
        } finally {
            for (DisplayRig r : List.of(one, two)) {
                for (var d : r.all()) {
                    d.discard();
                }
            }
        }
    }

    /**
     * 🔴 5.3 — <b>the head must survive the clip that overwrites it.</b>
     *
     * <p>Two pose sources write the same display and only one keeps a memo. A clip pose covers
     * every moving bone, the head among them, so a frame written by {@code RigAnimator} silently
     * replaces whatever {@code lookAt} last put there — and {@code lookAt}'s guard is "has the
     * ANGLE changed since I last wrote", which is still false. The head snaps back to the clip's
     * idea of it the moment the creature stops turning, permanently.
     *
     * <p>It was found by FILMING, not by a gate: a 55-degree turn measured 851 px in one run and
     * 71 px in the next on identical code, and the difference was only which writer happened to go
     * last on the sampled frame. This asserts the invariant directly so it cannot come back.
     */
    @GameTest(template = "empty_test")
    public static void aClipWriteDoesNotSwallowTheHeadTurn(GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        var at = helper.absolutePos(SPOT);
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(),
                new net.minecraft.world.phys.Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5),
                0f, parts, 1.0, 2.0, 2.0);
        try {
            // Turn the head — the first write is real.
            helper.assertTrue(RigDriver.lookAt(rig, 40f, -10f, 3) > 0,
                    Component.literal("the first head write did nothing -- this creature has no head group, so the "
                            + "test cannot measure the interaction it exists for"));

            // Ask again at the SAME angle: correctly a no-op, because nothing else has written.
            helper.assertTrue(RigDriver.lookAt(rig, 40f, -10f, 3) == 0,
                    Component.literal("an unchanged head should cost nothing"));

            // Now a clip writes every moving bone, the head among them...
            rig.forgetHead();

            // ...and the head must be re-asserted even though the ANGLE has not changed.
            helper.assertTrue(RigDriver.lookAt(rig, 40f, -10f, 3) > 0,
                    Component.literal("after a clip overwrote the head display, lookAt declined to rewrite it "
                            + "because the ANGLE was unchanged -- so the clip wins and the "
                            + "creature stares straight ahead forever"));
            helper.succeed();
        } finally {
            for (var d : rig.all()) {
                d.discard();
            }
            rig.base().discard();
        }
    }

    /**
     * 🔴 <b>The rig had the creature's SHAPE and a horse's NUMBERS, and every gate was green.</b>
     *
     * <p>{@code DisplayRig.spawn} overrode exactly one attribute — {@code minecraft:scale}, and only
     * so the hitbox matched the model — while {@code WishMobEntity.applySpecies} sets five. So a
     * wished creature declared at 200 health stood in the world with whatever the carrier vanilla
     * mob came with. It survived because the questions asked next door are all true of a horse: B1
     * asks whether the rig can be HURT, and B6 asks whether a resize REACHES the base by setting the
     * attribute itself and checking it took. Ladder §2.2 — <b>a control that is true of the bug is
     * not a control</b>, and "it has health" is true of every mob in the game.
     *
     * <p>The species here declares 40 health; a horse's default is ~15 and no vanilla base this rig
     * picks has 40, so the number is discriminating rather than incidental.
     */
    @GameTest(template = "empty_test")
    public static void theRigCarriesTheCREATURESNumbersRatherThanTheCarriersOwn(
            GameTestHelper helper) {
        String id = plantSpecies(helper, "bridge_vitals");
        wish(helper, id);

        helper.succeedWhen(() -> {
            var carrier = carrier(helper);
            helper.assertTrue(carrier != null, Component.literal("no rig carrier appeared"));
            helper.assertTrue(Math.abs(carrier.getMaxHealth() - 40.0) < 0.01,
                    Component.literal("the carrier's max health is " + carrier.getMaxHealth() + ", not the "
                            + "species' 40 -- the rig is wearing the base mob's statistics. A "
                            + "child's 200-health dragon would be a horse."));
            helper.assertTrue(carrier.getHealth() > 39.0,
                    Component.literal("a freshly wished creature must spawn at full health, not at the carrier's "
                            + "own: " + carrier.getHealth()));
        });
    }

    /**
     * <b>A creature halfway through a fight stays halfway through it across the swap.</b>
     *
     * <p>The bridge CANCELS the wish mob, and the wish mob is the only thing that knows what has
     * happened to this creature — {@code WishMobEntity} persists the species id and nothing else,
     * rebuilding the rest from the record. So a rig built from the species alone is a full-health,
     * unpoisoned creature, and every wounded creature in an existing save would quietly heal the
     * first time the world opened on the new path.
     */
    @GameTest(template = "empty_test")
    public static void aWoundedCreatureIsSTILLWoundedAfterTheSwap(GameTestHelper helper) {
        String id = plantSpecies(helper, "bridge_wounded");
        ServerLevel level = helper.getLevel();

        // 🔴 WOUND IT BEFORE IT JOINS. The first cut of this test wounded a wish mob, discarded
        // it and spawned a fresh one -- which of course came back at full health, because it
        // WAS a fresh creature. Building the entity, hurting it, and only then calling
        // addFreshEntity is not a workaround: it is exactly the sequence a world LOAD performs,
        // where an entity is deserialised with its saved health and joins afterwards. The
        // bridge cannot see anything that happens after the join, because there is no after.
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(mob != null, Component.literal("could not create a wish mob"));
        var at = helper.absolutePos(SPOT);
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setSpeciesId(id);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.COMMAND, null);
        float wasMax = mob.getMaxHealth();
        mob.setHealth(wasMax / 2.0f);
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.SLOWNESS, 400, 1));
        float woundedAt = mob.getHealth();

        level.addFreshEntity(mob);

        helper.succeedWhen(() -> {
            var carrier = carrier(helper);
            helper.assertTrue(carrier != null, Component.literal("no rig carrier appeared"));
            double fraction = carrier.getHealth() / carrier.getMaxHealth();
            double was = woundedAt / wasMax;
            helper.assertTrue(Math.abs(fraction - was) < 0.05,
                    Component.literal("the creature was at " + String.format("%.2f", was) + " of its health and "
                            + "came back at " + String.format("%.2f", fraction) + ". Health "
                            + "carries as a FRACTION because the maximum changes across the "
                            + "swap -- see RigVitals."));
            helper.assertTrue(carrier.hasEffect(
                            net.minecraft.world.effect.MobEffects.SLOWNESS),
                    Component.literal("the creature's Slowness did not survive the swap. Effects live on the "
                            + "ENTITY, and the bridge cancels it -- so anything not read before "
                            + "the cancel is gone."));
            // …and the rig's own hiding effect must still be there, or the horse shows up.
            helper.assertTrue(carrier.hasEffect(
                            net.minecraft.world.effect.MobEffects.INVISIBILITY),
                    Component.literal("copying the old effects must not have displaced the rig's own INVISIBILITY"));
        });
    }

    /**
     * 🔴 <b>Effects reach a rig through the SELECTOR the agent actually writes — not through a
     * direct {@code addEffect}.</b>
     *
     * <p>Scoreboard B5 is real and proves the wrong half. It calls {@code rig.living().addEffect}
     * and asserts it stuck, which is true of every mob in the game and says nothing about whether
     * an ability the agent authored can FIND a rig. That is the standing trap in CLAUDE.md — a rule
     * tested only where it is DEFINED is not tested where it RUNS — and it matters much more now
     * the rig is the default, because every ability a child has ever wished for is a datapack full
     * of these selectors.
     *
     * <p>The reason it works is a decision that predates this spike and is worth not breaking:
     * {@code WishMobTags.selector} is <b>tag-based with no {@code type=} filter</b>, and
     * {@code DisplayRig} stamps the same two tags on the carrier that {@code WishMobEntity} stamps
     * on itself. So swapping the entity type out from under every existing ability costs nothing.
     * If someone ever "tidies" a type filter back into that selector, this test is what says so.
     */
    @GameTest(template = "empty_test")
    public static void anAgentWrittenSELECTORFindsTheRigAndItsEffectLands(GameTestHelper helper) {
        String id = plantSpecies(helper, "bridge_selector");
        wish(helper, id);

        helper.succeedWhen(() -> {
            var carrier = carrier(helper);
            helper.assertTrue(carrier != null, Component.literal("no rig carrier appeared"));

            // The literal selector every agent-authored ability uses, run as a real command
            // through the real dispatcher -- not a hand-built entity list.
            String selector = com.moddingfromamod.entity.WishMobTags.selector(id);
            var server = helper.getLevel().getServer();
            var src = server.createCommandSourceStack()
                    .withLevel(helper.getLevel())
                    .withPosition(carrier.position())
                    .withSuppressedOutput()
                    .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
            server.getCommands().performPrefixedCommand(src,
                    "effect give " + selector + " minecraft:glowing 20 0 true");

            helper.assertTrue(
                    carrier.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING),
                    Component.literal("`effect give " + selector + "` did not reach the rig. Either the carrier "
                            + "lost its species tag, or the selector grew a type= filter -- "
                            + "and every ability a child has ever wished for is built out of "
                            + "this exact shape."));

            // …and the catch-all, which is what an ability with no named species uses.
            server.getCommands().performPrefixedCommand(src,
                    "effect give @e[tag=" + com.moddingfromamod.entity.WishMobTags.ALL + "] "
                            + "minecraft:levitation 20 0 true");
            helper.assertTrue(
                    carrier.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION),
                    Component.literal("the catch-all wish-mob tag did not reach the rig either"));
        });
    }

    /**
     * 🔴 <b>A SECOND wish on a creature already standing in the world must change it.</b>
     *
     * <p>`sculpt_creature` tells the agent, in its own result text, <i>"every one already in the
     * world updates"</i>. On the legacy path that is true and free: {@code WishMobEntity} compares
     * {@code SpeciesRegistry.revision()} on tick and calls {@code applySpecies} again. A rig cannot
     * do that — its geometry is baked into a resource pack and into the transform of every display
     * — so it is REBUILT instead.
     *
     * <p>The halfway state is what makes this urgent rather than cosmetic. {@code RigPackWriter}
     * reuses a creature's slot on a re-wish, so the models in the pack DO change; without a rebuild
     * the child gets the new models drawn at the old creature's proportions, which is worse than
     * nothing happening. And "nothing happening" is itself the most common second wish there is —
     * see docs/REFINEMENT_LADDERS.md, which exists because refinement is where creatures were
     * measured at 0.40 part survival.
     */
    @GameTest(template = "empty_test", timeoutTicks = 700)
    public static void aSECONDWishRebuildsTheCreatureStandingInTheWorld(GameTestHelper helper) {
        String id = plantSpecies(helper, "bridge_rewish");
        wish(helper, id);
        wish(helper, id);

        helper.runAtTickTime(40, () -> {
            var first = carriers(helper);
            helper.assertTrue(first.size() == 2,
                    Component.literal("expected two rigs of one species, found " + first.size()));
            var wasUuids = new java.util.HashSet<java.util.UUID>();
            first.forEach(c -> wasUuids.add(c.getUUID()));

            // 🔴 A GEOMETRY-ONLY re-wish: a different SPEC on disk under the same id, and a
            // Species record that is byte-identical. This is what `sculpt_creature` with the
            // same id and fixed parts actually does, and it is the case a record comparison
            // cannot see -- the record holds a modelKey, not the parts.
            //
            // ⚠ It has to be the SPEC. The first cut passed different `parts` to
            // CreatureFiles.write and reused Spikor's sculpt.json, and nothing rebuilt --
            // because ModelRepository.sculptedParts reads sculpt.json, so the geometry the rig
            // is built from never moved. The test failed with a message about the rebuild, and
            // the rebuild was innocent.
            try {
                java.nio.file.Path other = SpikeFixture.find(
                        SpikeFixture.CORPUS_DIR + "/" + SpikeFixture.real("sigmaboy-armB") + "/sculpt.json");
                if (other == null) {
                    helper.fail(Component.literal("the second corpus sculpt is missing -- this test cannot make "
                            + "a geometry-only change without one"));
                }
                CreatureFiles.write(id, SpikeFixture.parts(SpikeFixture.real("sigmaboy-armB")),
                        java.nio.file.Files.readString(other));
            } catch (Exception e) {
                helper.fail(Component.literal("could not re-sculpt: " + e));
            }
            // Same record, so only the revision counter moves.
            com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(JsonParser.parseString(
                            "{\"id\":\"" + id + "\",\"modelKey\":\"" + id + "\","
                                    + "\"health\":40,\"speed\":0.0,\"scale\":1.2}")
                            .getAsJsonObject()));

            helper.runAtTickTime(220, () -> {
                var after = carriers(helper);
                helper.assertTrue(after.size() == 2,
                        Component.literal("after the re-wish there are " + after.size() + " rigs, not 2 -- a "
                                + "rebuild that loses the creature is the one outcome worse "
                                + "than not updating it"));
                for (var c : after) {
                    helper.assertFalse(wasUuids.contains(c.getUUID()),
                            Component.literal("a rig of a RE-SCULPTED species was not rebuilt. Both were, or "
                                    + "neither was: each rig compares its OWN built print "
                                    + "against the species' current one (RigGeometryPrint, "
                                    + "#582). The pack byte-compare it replaced was only true "
                                    + "ONCE, so whichever rig looked first used to consume the "
                                    + "signal and leave its twin wearing the old body."));
                    helper.assertTrue(VanillaRigAnimator.isDriving(c),
                            Component.literal("a rebuilt rig has nobody driving it -- it will stand still "
                                    + "forever, which is the B9 shape one level in"));
                }
                helper.succeed();
            });
        });
    }

    /**
     * …and the other half: a revision bump for a DIFFERENT creature must not rebuild this one.
     *
     * <p>{@code SpeciesRegistry.revision()} is global. Rebuilding on the counter alone would rebuild
     * every rig in the world — each with its own resource reload and its own visible flicker —
     * every time a child wished for anything at all. The discriminator is two exact comparisons,
     * the {@code Species} record (a record of value types) and the geometry print, because
     * neither one implies the other: a re-sculpt changes geometry without touching the record.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void ANOTHERCreaturesEditDoesNotRebuildThisOne(GameTestHelper helper) {
        String id = plantSpecies(helper, "bridge_bystander");
        wish(helper, id);

        helper.runAtTickTime(40, () -> {
            var first = carrier(helper);
            helper.assertTrue(first != null, Component.literal("no rig carrier appeared"));
            java.util.UUID wasUuid = first.getUUID();

            // Somebody else's wish, in the same registry.
            com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, com.moddingfromamod.species.Species.fromJson(JsonParser.parseString(
                            "{\"id\":\"someone_else\",\"health\":9,\"speed\":0.0,\"scale\":1.0}")
                            .getAsJsonObject()));

            helper.runAtTickTime(120, () -> {
                var after = carrier(helper);
                helper.assertTrue(after != null, Component.literal("the bystander creature disappeared"));
                helper.assertTrue(wasUuid.equals(after.getUUID()),
                        Component.literal("a bystander rig was rebuilt because SOME OTHER creature was edited. "
                                + "The revision counter is global, so it says 'something "
                                + "changed' and never 'this creature changed' -- rebuilding on "
                                + "it alone costs every creature in the world a resource "
                                + "reload and a visible flicker every time a child wishes."));
                helper.succeed();
            });
        });
    }

    /**
     * <b>, blocker 5: a creature saved by the PACK RIG is rebuilt as atoms on load</b> — the
     * migration that let the pack rig be deleted.
     *
     * <p>The pack rig was the shipped default until 2026-09-13, so real worlds hold rigs in its
     * shape: displays tagged with their owner, NO index tag, each wearing {@code paper} with a
     * per-creature {@code custom_model_data} (base + group index) from a pack no client will ever
     * be sent again. {@link DisplayRig#adopt} cannot read that shape and returns null, and until
     * {@link VanillaRigAnimator#adoptAll} then left the creature where it stood with a WARN
     * line: a frozen heap of paper cards, for ever. The decision rule's answer was MIGRATE — the
     * world is ours to rewrite and the species still knows the creature — so the sweep now reaps
     * it and manifests it again, keeping what the child did to it.
     *
     * <p>The old shape is hand-built here from what the deleted spawn branch wrote (its owner tag,
     * its carrier, its numbering), on top of a real rig, because the code that wrote it is gone.
     * What the migration reads is only the carrier's tags, which the two rigs share.
     *
     * <p>Its own batch: {@code adoptAll} sweeps the whole level, and a sibling test's undriven rig
     * would be rebuilt too.
     */
    @GameTest(template = "empty_test", batch = "packrigsave", timeoutTicks = 400)
    public static void aRigSavedByThePackRigIsRebuiltAsAtomsOnLoad(GameTestHelper helper) {
        String id = plantSpecies(helper, "bridge_packsave");
        wish(helper, id);

        helper.runAtTickTime(40, () -> {
            var old = carrier(helper);
            helper.assertTrue(old instanceof Mob, Component.literal("no rig carrier appeared to reshape"));
            ServerLevel level = helper.getLevel();
            String owner = DisplayRig.RIG_OWNER_PREFIX + old.getUUID();
            List<Entity> displays = new ArrayList<>();
            level.getEntities().getAll().forEach(e -> {
                if (e instanceof Display && e.getTags().contains(owner)) {
                    displays.add(e);
                }
            });
            helper.assertTrue(displays.size() > 6,
                    Component.literal("the atom rig has only " + displays.size() + " displays, so reshaping it into "
                            + "a 6-group pack rig would not change its count"));

            // THE PACK RIG'S SAVE SHAPE: six groups, no index tags, paper numbered from 1.
            for (int i = 0; i < displays.size(); i++) {
                Entity d = displays.get(i);
                if (i >= 6) {
                    d.discard();
                    continue;
                }
                for (String t : new ArrayList<>(d.getTags())) {
                    if (t.startsWith(DisplayRig.RIG_INDEX_PREFIX)) {
                        d.removeTag(t);
                    }
                }
                var tag = com.moddingfromamod.compat.EntityNbt.save(d);
                var item = new net.minecraft.nbt.CompoundTag();
                item.putString("id", "minecraft:paper");
                item.putInt("count", 1);
                var comps = new net.minecraft.nbt.CompoundTag();
                com.moddingfromamod.compat.ModelData.put(comps, 1 + i);
                item.put("components", comps);
                tag.put("item", item);
                com.moddingfromamod.compat.EntityNbt.load(d, tag);
            }
            // What the child did to it, which a migration must not undo.
            old.setCustomName(net.minecraft.network.chat.Component.literal("Sparky"));
            old.setHealth(old.getMaxHealth() / 2f);
            float hp = old.getHealth();
            java.util.UUID oldUuid = old.getUUID();

            // As after a restart: the world holds it and nothing is driving it.
            VanillaRigAnimator.forget(oldUuid);
            helper.assertTrue(DisplayRig.adopt(level, (Mob) old,
                            SpikeFixture.parts(SpikeFixture.SPIKOR), 1.2) == null,
                    Component.literal("the reshaped rig was ADOPTED, so the fixture is not the pack rig's shape "
                            + "and this test would pass without the migration"));
            VanillaRigAnimator.adoptAll(level);

            helper.assertTrue(old.isRemoved(),
                    Component.literal("the pack-rig carrier is still standing: the sweep left it a statue, which is "
                            + "exactly the failure blocker 5 described"));
            List<String> left = new ArrayList<>();
            level.getEntities().getAll().forEach(e -> {
                if (e.isAlive() && e.getTags().contains(owner)) {
                    left.add(e.getType().toShortString());
                }
            });
            helper.assertTrue(left.isEmpty(),
                    Component.literal(left.size() + " of the old rig's displays survived the rebuild -- a paper heap "
                            + "standing beside the new creature: " + left));

            var now = carrier(helper);
            helper.assertTrue(now instanceof Mob && !now.getUUID().equals(oldUuid),
                    Component.literal("nothing replaced the pack-rig creature"));
            helper.assertTrue(VanillaRigAnimator.isDriving(now),
                    Component.literal("the rebuilt creature is not being driven -- a statue again, one step later"));
            int unindexed = 0;
            int total = 0;
            String nowOwner = DisplayRig.RIG_OWNER_PREFIX + now.getUUID();
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof Display && e.getTags().contains(nowOwner)) {
                    total++;
                    if (DisplayRig.indexOf(e) < 0) {
                        unindexed++;
                    }
                }
            }
            helper.assertTrue(total > 6 && unindexed == 0,
                    Component.literal("the rebuilt rig is not an atom rig: " + total + " displays, " + unindexed
                            + " without an index tag"));
            helper.assertTrue(now.hasCustomName()
                            && "Sparky".equals(now.getCustomName().getString()),
                    Component.literal("the migration lost the name the child gave it"));
            helper.assertTrue(Math.abs(now.getHealth() - hp) < 0.01f,
                    Component.literal("the migration healed the creature: health " + now.getHealth() + ", was " + hp));
            helper.succeed();
        });
    }
}
