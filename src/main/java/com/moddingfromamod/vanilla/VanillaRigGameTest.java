package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for the vanilla-creature spike — Q4, the question most likely to sink it.</b>
 *
 * <p>Q4 asks whether a creature rendered as a display rig on a vanilla base can still be hit, hurt
 * and aimed at. It goes first and it goes headless, because it is answerable without a client and
 * because a NO here makes the photographs pointless.
 *
 * <p><b>The precedent, already paid for once.</b> {@code WishMobEntity#getDefaultDimensions}
 * overrides its hitbox from the model. Before that it was a fixed 0.6x1.95 player box that ignored
 * the model entirely, and it LIED to an agent aiming a fire breath — see {@code CreatureAnatomy}'s
 * javadoc. A vanilla base reintroduces exactly that risk by a different door: the base carries
 * Mojang's dimensions, and the only lever is the uniform {@code minecraft:scale} attribute, which
 * cannot change a box's SHAPE. So this measures the residual instead of hoping it is small.
 *
 * <p>The tests drive the classes directly rather than through a wish.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class VanillaRigGameTest {

    /** The spike's measurements go to the log, where a Gate B run records them. */
    private static final Logger LOG = LoggerFactory.getLogger("mfam-vanilla-rig");

    /** Somewhere inside the test plot with room for a 4-block creature. */
    private static final BlockPos SPOT = new BlockPos(2, 2, 2);

    private static DisplayRig spikor(GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        double scale = SpikeFixture.scale(SpikeFixture.SPIKOR);
        var geo = RigGeometry.of(parts);
        var extents = CreatureAnatomy.extents(geo);
        double w = CreatureAnatomy.widthBlocks(extents) * scale;
        double h = CreatureAnatomy.heightBlocks(extents) * scale;
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(SPOT));
        return DisplayRig.spawn(helper.getLevel(), at, 0f, parts, scale, w, h);
    }

    // ── the control: arm B must be genuinely vanilla ─────────────────────────────────────────

    /**
     * <b>Guard 1 of the spike.</b> A rig that accidentally rendered {@code wish_mob} would take a
     * beautiful photograph and answer no question at all, because the whole claim is that a client
     * with no mod installed can see this. So: nothing the rig spawns may be ours.
     */
    @GameTest(template = "empty_test")
    public static void armBSpawnsNothingButVanillaEntities(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        try {
            List<String> ids = rig.entityTypeIds();
            helper.assertTrue(!ids.isEmpty(), Component.literal("the rig spawned nothing at all"));
            for (String id : ids) {
                helper.assertTrue(id.startsWith("minecraft:"),
                        Component.literal("arm B must be renderable by a vanilla client, but it spawned " + id));
            }
            helper.assertTrue(ids.contains("minecraft:item_display"),
                    Component.literal("the rig should carry item_display entities, spawned: " + ids));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * The entity count per creature: <b>one display per box, plus one per pattern overlay</b>.
     *
     * <p>This asserted the pack rig's rigid-group collapse (62 boxes → 6 displays)
     * deleted that rig. The atom rig pays one display per box by design —
     * {@code docs/ATOM_RIG_COST.md} is the measurement and the decision to pay it — so what
     * would now regress silently is the other direction: a box planned as TWO displays, or a
     * pattern overlay for a box that has no pattern. So the count is derived from the plan's own
     * parts rather than typed.
     */
    @GameTest(template = "empty_test")
    public static void oneDisplayPerBoxPlusOnePerPatternOverlay(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        try {
            int bases = 0;
            int overlays = 0;
            for (RigPlan.Group g : rig.groups()) {
                helper.assertTrue(g.boxCount() == 1,
                        Component.literal("atom '" + g.name() + "' carries " + g.boxCount() + " boxes, not 1"));
                if (g.patternOverlay()) {
                    overlays++;
                } else {
                    bases++;
                }
            }
            helper.assertTrue(bases == 62,
                    Component.literal("the fixture should be the measured 62-box Spikor, planned as 62 atoms; was "
                            + bases));
            helper.assertTrue(rig.displays().size() == bases + overlays,
                    Component.literal("expected " + (bases + overlays) + " displays (62 boxes + " + overlays
                            + " pattern overlays), got " + rig.displays().size()));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    // ── Q4: the six targeting checks ─────────────────────────────────────────────────────────

    /** {@code /effect} applies — i.e. the base is a real LivingEntity, not a decoration. */
    @GameTest(template = "empty_test")
    public static void effectApplies(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        try {
            boolean ok = rig.living().addEffect(
                    new MobEffectInstance(MobEffects.SLOWNESS, 100, 1));
            helper.assertTrue(ok, Component.literal("the base should accept a status effect"));
            helper.assertTrue(rig.living().hasEffect(MobEffects.SLOWNESS),
                    Component.literal("the effect should stick"));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /** A player hit lands and takes health off. */
    @GameTest(template = "empty_test")
    public static void aHitLands(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        try {
            float before = rig.living().getHealth();
            DamageSource src = helper.getLevel().damageSources().generic();
            boolean hurt = com.moddingfromamod.compat.Damage.hurt(rig.living(), src, 3.0f);
            helper.assertTrue(hurt, Component.literal("the creature should be hurtable"));
            helper.assertTrue(rig.living().getHealth() < before,
                    Component.literal("health should drop: was " + before + ", now " + rig.living().getHealth()));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * Another mob can target it.
     *
     * <p>Asserted through {@code Mob.setTarget}/{@code getTarget} rather than by waiting for AI to
     * notice: a GameTest server ticks as fast as it can, so "wait 40 ticks for aggro" is not a
     * duration this gate can express. What matters for Q4 is that the base is a legal target at
     * all, which a display entity would not be.
     */
    @GameTest(template = "empty_test")
    public static void anotherMobCanTargetIt(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        Zombie hunter = null;
        try {
            hunter = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, SPOT.offset(3, 0, 0));
            hunter.setTarget(rig.living());
            helper.assertTrue(hunter.getTarget() == rig.living(),
                    Component.literal("a vanilla mob should be able to hold the creature as its target"));
            helper.assertTrue(rig.living().canBeSeenAsEnemy(),
                    Component.literal("the creature should be attackable at all"));
            helper.succeed();
        } finally {
            if (hunter != null) {
                hunter.discard();
            }
            rig.discard();
        }
    }

    /**
     * An {@code @e}-style selector finds it — checked as a real AABB query, which is what a selector
     * compiles to and what every ability, explosion and area effect actually uses.
     */
    @GameTest(template = "empty_test")
    public static void selectorsFindIt(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        try {
            ServerLevel level = helper.getLevel();
            AABB near = rig.base().getBoundingBox().inflate(4.0);
            List<Entity> found = level.getEntities((Entity) null, near,
                    e -> e.getUUID().equals(rig.base().getUUID()));
            helper.assertTrue(found.size() == 1,
                    Component.literal("an area query should find the creature exactly once, found " + found.size()));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * <b>The measured hitbox against the rendered model</b> — the check
     * {@code getDefaultDimensions} was fixed for, asked of the vanilla base.
     *
     * <p>This does not assert the box is right, because it is not: it asserts the box is the one
     * {@link RigBase} predicted, and REPORTS the residual so the spike quotes a number instead of a
     * hope. A too-NARROW box is called out separately, because that is the failure that matters —
     * shoulders you can see and cannot hit.
     */
    @GameTest(template = "empty_test")
    public static void theHitboxIsMeasuredNotAssumed(GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        double scale = SpikeFixture.scale(SpikeFixture.SPIKOR);
        var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
        double wantW = CreatureAnatomy.widthBlocks(extents) * scale;
        double wantH = CreatureAnatomy.heightBlocks(extents) * scale;

        DisplayRig rig = spikor(helper);
        try {
            double[] got = rig.measuredHitbox();
            RigBase.Choice c = rig.choice();
            helper.assertTrue(c != null, Component.literal("no vanilla base could reach the creature's height"));

            String report = String.format(
                    "Q4 hitbox: model wants %.3fx%.3f blocks; base '%s' at scale %.3f gives "
                            + "%.3fx%.3f (measured %.3fx%.3f); width error %+.3f blocks (%.1f%%)%s",
                    wantW, wantH, c.id(), c.scale(), c.actualWidth(), c.height(),
                    got[0], got[1], c.widthErrorBlocks(), 100 * c.widthErrorFraction(),
                    c.tooNarrow() ? " — TOO NARROW" : "");
            LOG.info(report);

            // The prediction must match the world, or the number the spike reports is fiction.
            helper.assertTrue(Math.abs(got[1] - c.height()) < 0.05,
                    Component.literal("predicted height should match the live hitbox: " + report));
            helper.assertTrue(Math.abs(got[0] - c.actualWidth()) < 0.05,
                    Component.literal("predicted width should match the live hitbox: " + report));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * The rig follows the base — the claim D2 rests on.
     *
     * <p>If a display does not track the creature, locomotion is not free and Q2's cost model is
     * wrong. Checked by moving the base and asking whether the rig went with it.
     */
    @GameTest(template = "empty_test")
    public static void theRigTravelsWithTheCreature(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        try {
            Vec3 before = rig.displays().get(0).position();
            Vec3 to = rig.base().position().add(5, 0, 0);
            RigDriver.follow(rig, to, rig.base().getYRot());
            Vec3 after = rig.displays().get(0).position();
            helper.assertTrue(after.distanceTo(before) > 4.5,
                    Component.literal("the rig should travel with the creature; moved "
                            + String.format("%.2f", after.distanceTo(before)) + " blocks"));
            helper.assertTrue(after.distanceTo(to) < 0.01,
                    Component.literal("the rig should land on the creature, off by "
                            + String.format("%.3f", after.distanceTo(to))));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * <b>A rig that WALKS must face the way a rig that STANDS STILL faces.</b>
     *
     * <p>This is the regression for a bug that shipped and that no gate could see. The half turn
     * that cancels the world-space mirror was applied in {@code DisplayRig.spawn} and simply
     * absent from {@link RigDriver#follow}, so a creature was correct until it took one step and
     * then rendered back-to-front for the rest of its life.
     *
     * <p>Why every existing check missed it, which is the part worth keeping: the static A/B
     * photographs a rig that never moves, so {@code follow} is not on that path at all; the
     * animation oracles (loop closure, the client tween, frames between keyframes) all measure
     * TRANSFORMS, which live in the display's own frame and are identical whichever way the body
     * points; and the walk run's own controls — distance travelled, which clip was selected, that
     * arm B was wearing real models — are every one of them true of a creature moon-walking. It
     * took a person watching the film.
     *
     * <p>So the invariant is stated where it is cheap: after a {@code follow} at the yaw the rig
     * was spawned with, the displays must carry the yaw they were spawned with. It fails by
     * naming both numbers, because "180 out" and "not turning at all" want opposite fixes.
     */
    @GameTest(template = "empty_test")
    public static void aRigThatMovesKeepsTheFacingItWasSpawnedWith(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        try {
            float bodyYaw = rig.base().getYRot();
            float atSpawn = rig.displays().get(0).getYRot();
            helper.assertTrue(Math.abs(Mth.degreesDifference(atSpawn,
                            DisplayRig.displayYaw(bodyYaw))) < 0.01f,
                    Component.literal("spawn should place a display at displayYaw(" + bodyYaw + ")="
                            + DisplayRig.displayYaw(bodyYaw) + ", got " + atSpawn));

            RigDriver.follow(rig, rig.base().position().add(3, 0, 0), bodyYaw);

            for (Entity d : rig.displays()) {
                float now = d.getYRot();
                helper.assertTrue(Math.abs(Mth.degreesDifference(now, atSpawn)) < 0.01f,
                        Component.literal("a display turned by "
                                + String.format("%.1f", Mth.degreesDifference(now, atSpawn))
                                + " degrees just by MOVING: spawned facing " + atSpawn
                                + ", after follow " + now
                                + ". follow must go through DisplayRig.displayYaw."));
            }
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * <b>The rig must disappear at the same distance the creature would</b> — tier-2 row 2.3, and
     * the reason it is a GameTest rather than a photograph is that it is pure arithmetic on two
     * formulas that are one factor apart.
     *
     * <p>{@code Entity.shouldRenderAtSqrDistance} is {@code d < (boundingBox.getSize() × 64 ×
     * viewScale)²}; {@code Display} overrides it to {@code d < (view_range × 64 × viewScale)²}.
     * Setting {@code view_range} to the carrier's own box size makes the two identical, which is
     * what {@link DisplayRig#matchViewRange} does — replacing a literal {@code 4.0f} that was
     * right for one creature and arbitrary for every other. A 0.5-block critter got 256 blocks of
     * range and a 12-block one got 256 as well.
     *
     * <p>Asserted as a RANGE MATCH rather than as a number, so it keeps holding for a creature of
     * any size: the same distance is put to both entities and they must answer the same.
     */
    @GameTest(template = "empty_test")
    public static void aDisplayCullsAtTheSameRangeAsItsCarrier(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        try {
            Entity carrier = rig.base();
            // Straddle the boundary: the carrier's own cut-off, well inside it, a hair either
            // side, and well outside. A single sample at one distance passes for a range that is
            // merely LARGE, which is exactly what the old 4.0f literal was.
            double edge = Math.pow(carrier.getBoundingBox().getSize() * 64.0
                    * net.minecraft.world.entity.Entity.getViewScale(), 2);
            double[] probes = {0.0, edge * 0.5, edge * 0.99, edge * 1.01, edge * 4.0};
            for (Entity display : rig.displays()) {
                for (double dSqr : probes) {
                    boolean base = carrier.shouldRenderAtSqrDistance(dSqr);
                    boolean disp = display.shouldRenderAtSqrDistance(dSqr);
                    helper.assertTrue(base == disp,
                            Component.literal("at d^2=" + String.format("%.0f", dSqr) + " the carrier says "
                                    + (base ? "render" : "cull") + " and a display says "
                                    + (disp ? "render" : "cull")
                                    + " -- view_range is not derived from the carrier's box size "
                                    + "(" + String.format("%.3f", carrier.getBoundingBox()
                                    .getSize()) + ")"));
                }
            }
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * <b>Q3 — the emission point is the same both ways</b>, measured between two entities that
     * really are in the world rather than between two calls with the same arguments.
     *
     * <p>The first draft of this test compared {@code emissionPoint(base, ...)} with
     * {@code emissionPoint(base, ...)} and was a tautology — it could not fail, which makes it
     * worse than no test. What Q3 actually risks is that the two RENDERERS put their entity in
     * different places or face it differently: {@code CreatureAnatomy} is pure and reads geometry
     * only, so the anatomy cannot disagree with itself, but a vanilla base could sit at a different
     * Y, or carry a different yaw convention, and the jet would come out somewhere else.
     *
     * <p>So this spawns BOTH renderers at the same requested spot and measures between their live
     * positions, in blocks. The bar is the one the spike asks for: a world-space delta, reported.
     */
    @GameTest(template = "empty_test")
    public static void theSameAbilityFiresFromTheSamePlace(GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        double scale = SpikeFixture.scale(SpikeFixture.SPIKOR);
        var landmarks = CreatureAnatomy.landmarks(RigGeometry.of(parts), scale);
        helper.assertTrue(!landmarks.isEmpty(), Component.literal("Spikor should have aimable landmarks"));

        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(SPOT));
        float yaw = 37.5f;   // deliberately not 0 or 90: a yaw convention bug hides at both

        // ARM A: the shipped renderer's entity, placed the same way.
        var armA = EntityType.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        helper.assertTrue(armA != null, Component.literal("could not create the arm-A stand-in"));
        armA.snapTo(at.x, at.y, at.z, yaw, 0f);
        armA.setInvisible(true);
        helper.getLevel().addFreshEntity(armA);

        // ARM B: the display rig on its vanilla base.
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(), at, yaw, parts, scale,
                spikorWidth(parts, scale), spikorHeight(parts, scale));
        try {
            double worst = -1;
            String worstName = "(none)";
            for (CreatureAnatomy.Landmark lm : landmarks) {
                Vec3 modPath = RigAnchor.emissionPoint(armA.position(), armA.getYRot(), lm);
                Vec3 rigPath = RigAnchor.emissionPoint(
                        rig.base().position(), rig.base().getYRot(), lm);
                double delta = modPath.distanceTo(rigPath);
                if (delta > worst) {
                    worst = delta;
                    worstName = lm.name();
                }
            }
            LOG.info(String.format(
                    "Q3 emission delta: worst %.6f blocks at '%s' over %d landmarks",
                    worst, worstName, landmarks.size()));
            // A tenth of a block is already smaller than the bug CreatureAnatomy was written for
            // (0.55 up, 1.2 forward). Anything above it means the two entities are not in the same
            // place, which is a placement bug and not an anatomy one.
            helper.assertTrue(worst < 0.1,
                    Component.literal("Q3: emission points should agree; worst " + worst + " blocks at " + worstName));
            helper.succeed();
        } finally {
            armA.discard();
            rig.discard();
        }
    }

    private static double spikorWidth(List<CreatureSculpt.Part> parts, double scale) {
        return CreatureAnatomy.widthBlocks(CreatureAnatomy.extents(RigGeometry.of(parts))) * scale;
    }

    private static double spikorHeight(List<CreatureSculpt.Part> parts, double scale) {
        return CreatureAnatomy.heightBlocks(CreatureAnatomy.extents(RigGeometry.of(parts))) * scale;
    }

    /**
     * <b>Q4 across every creature in the corpus, not just the one the spike is written against.</b>
     *
     * <p>Spikor alone would answer the wrong question. The residual depends entirely on how close
     * some vanilla mob's proportions happen to be to a given creature's, and the nine committed
     * tier-experiment sculpts span W/H ratios from 0.43 to 2.55 — a 5.9x spread that no single base
     * can serve. So the table is computed here, in-game, from the LIVE registry, and logged.
     *
     * <p>Live registry rather than a table in the source, deliberately: an attempt to lift vanilla's
     * dimensions out of {@code EntityType}'s bytecode produced a confidently wrong answer twice, and
     * a table that is wrong in the reassuring direction would make this error look smaller than it
     * is. {@code EntityType.getDimensions()} cannot be stale.
     *
     * <p>The assertion is the one claim that can actually fail: every real creature must be
     * matchable to SOME base inside vanilla's scale range. A creature that cannot be is one the rig
     * could not carry at all.
     */
    @GameTest(template = "empty_test")
    public static void everyCorpusCreatureCanBeCarriedByAVanillaBase(GameTestHelper helper) {
        List<RigBase.Candidate> cands = DisplayRig.candidates(helper.getLevel());
        StringBuilder sb = new StringBuilder("Q4 corpus hitbox error (live registry):\n");
        sb.append("  vanilla bases offered: ");
        for (RigBase.Candidate c : cands) {
            sb.append(String.format("%s %.3fx%.3f(r=%.2f)%s ",
                    c.id(), c.width(), c.height(), c.ratio(),
                    c.peacefulSafe() ? "" : "[hostile-REFUSED]"));
        }
        sb.append('\n');

        int measured = 0;
        double worstErr = 0;
        String worstCase = "";
        int tooNarrow = 0;
        for (String name : SpikeFixture.CASES) {
            if (!SpikeFixture.available(name)) {
                continue;   // corpus not reachable from this working directory
            }
            List<CreatureSculpt.Part> parts = SpikeFixture.parts(name);
            double scale = SpikeFixture.scale(name);
            double w = spikorWidth(parts, scale);
            double h = spikorHeight(parts, scale);
            RigBase.Choice c = RigBase.choose(cands, w, h);
            helper.assertTrue(c != null,
                    Component.literal(name + " (" + String.format("%.2fx%.2f", w, h)
                            + ") cannot be carried by any vanilla base within scale limits"));
            int groups = RigPlan.atoms(RigTransform.mirrorIntoWorldSpace(parts)).size();
            sb.append(String.format(
                    "  %-18s %2d boxes -> %d displays | wants %.2fx%.2f (r=%.2f) | %s x%.2f "
                            + "-> %.2f wide | err %+.3f (%.1f%%)%s%n",
                    name, parts.size(), groups, w, h, w / h, c.id(), c.scale(),
                    c.actualWidth(), c.widthErrorBlocks(), 100 * c.widthErrorFraction(),
                    c.tooNarrow() ? "  TOO NARROW" : ""));
            measured++;
            if (c.widthErrorFraction() > worstErr) {
                worstErr = c.widthErrorFraction();
                worstCase = name;
            }
            if (c.tooNarrow()) {
                tooNarrow++;
            }
        }
        sb.append(String.format("  => %d creatures; worst width error %.1f%% (%s); "
                + "%d of %d too narrow to hit where they look",
                measured, 100 * worstErr, worstCase, tooNarrow, measured));
        LOG.info(sb.toString());
        helper.assertTrue(measured > 0,
                Component.literal("no corpus creature was reachable — the Q4 table would be vacuous"));
        helper.succeed();
    }

    /**
     * <b>Q4's sixth check: {@code modify_mob} still adjusts it</b> — and the half of that which is
     * NOT free.
     *
     * <p>{@code ModifyMobTool.apply} falls through to its direct route for anything that is not a
     * {@code WishMobEntity}, and that route is plain {@code LivingEntity} work — attribute, health,
     * name — so it reaches a vanilla base unchanged. That much is a pass.
     *
     * <p>The half worth recording is that resizing the base does <b>not</b> resize the rig: the
     * group transforms were baked at the creature's old scale, so a scaled creature ends up with a
     * correct hitbox around a wrong-sized model. On the shipped path that cannot happen, because
     * the model and the hitbox are both derived from the species every tick. Asserted in both
     * directions so the gap is a recorded fact rather than a surprise.
     *
     * <p><b>Note whose bug this was.</b> {@code ModifyMobTool} has carried
     * {@code refreshDimensions()} after a scale change for a long time, with a comment saying a
     * giant mob would otherwise be "punchable only where its small self used to be". The rig
     * reintroduced exactly that defect because it is a different call path — the same shape as
     * {@code docs/CAMERA.md}'s guard that was complete, correct, and wired to one path only.
     */
    @GameTest(template = "empty_test")
    public static void modifyMobStillAdjustsIt(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        try {
            double widthBefore = rig.base().getBbWidth();
            org.joml.Vector3f rigBefore =
                    rig.placed(rig.groups().get(0)).translation();

            // The direct route modify_mob takes for a non-species entity.
            var attr = rig.base().getAttribute(
                    net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
            helper.assertTrue(attr != null, Component.literal("a vanilla base must expose minecraft:scale"));
            attr.setBaseValue(attr.getBaseValue() * 1.5);
            rig.base().refreshDimensions();
            rig.living().setCustomName(net.minecraft.network.chat.Component.literal("Spikor"));
            rig.living().setHealth(rig.living().getMaxHealth());

            helper.assertTrue(rig.base().getBbWidth() > widthBefore * 1.4,
                    Component.literal("modify_mob's resize should reach a vanilla base: " + widthBefore
                            + " -> " + rig.base().getBbWidth()));
            helper.assertTrue(rig.living().getCustomName() != null,
                    Component.literal("modify_mob's rename should reach a vanilla base"));

            // …and the recorded gap: the rig did not follow, because its transforms are baked.
            org.joml.Vector3f rigAfter =
                    rig.placed(rig.groups().get(0)).translation();
            helper.assertTrue(rigBefore.equals(rigAfter, 1e-6f),
                    Component.literal("the baked rig transform is unchanged by a base resize — a rig-aware "
                            + "modify_mob would have to re-place all " + rig.displays().size()
                            + " displays"));
            LOG.info("Q4 modify_mob: base resized {} -> {} wide; rig transforms unchanged, so a "
                    + "rig-aware path must re-place {} displays",
                    String.format("%.3f", widthBefore),
                    String.format("%.3f", rig.base().getBbWidth()), rig.displays().size());
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * <b>Q2 — what the rig costs, in bytes actually on the wire and nanoseconds actually spent.</b>
     *
     * <p>Two numbers, neither of them a tick count. A GameTest server ticks as fast as it can, so
     * anything timed in ticks here would be meaningless — this repo has measured 120 ticks going by
     * in a fifth of a second. Bytes and nanoseconds are real regardless of tick rate.
     *
     * <p>The claim being checked is the one the whole cost model rests on: an <b>idle</b> creature
     * costs ZERO transform updates. If articulation were unconditional, a standing creature would
     * cost six writes a frame and the "how many creatures" answer would be wrong by the bone count.
     *
     * <p>The moved-bone count is structural rather than typed: on the pack rig (deleted) a
     * bone's whole subtree was one display, so it read 1; on the atom rig it is however many atoms
     * the subtree holds, whose exact counts live in {@code RigAtomAnimationTest}. <b>The idle claim
     * is the half this test exists for</b>, and zero is zero.
     */
    @GameTest(template = "empty_test")
    public static void anIdleCreatureCostsNothingAndAMovingOneIsPriced(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        try {
            // 1. IDLE: no pose, no writes.
            helper.assertTrue(RigDriver.articulate(rig, java.util.Map.of(), 2) == 0,
                    Component.literal("an idle creature must cost zero transform updates"));
            helper.assertTrue(RigDriver.articulate(rig, null, 2) == 0,
                    Component.literal("…and so must a null pose"));

            // 2. A pose that moves ONE bone writes its subtree -- something, and never everything.
            var head = new com.moddingfromamod.runtime.BedrockAnimation.BonePose(
                    new float[]{0, 25, 0}, new float[]{0, 0, 0}, new float[]{1, 1, 1});
            int wrote = RigDriver.articulate(rig, java.util.Map.of("head", head), 2);
            helper.assertTrue(wrote > 0 && wrote < rig.displays().size(),
                    Component.literal("turning one bone should rewrite that bone's atoms and no others; rewrote "
                            + wrote + " of " + rig.displays().size()));

            // 3. BYTES: measure a real transform update on the wire, not an estimate.
            int bytes = measureTransformBytes(rig.displays().get(0));

            // 4. NANOSECONDS: the server-side cost of applying one bone's transform.
            //
            // WARMED FIRST, and the warm-up is not a formality: the un-warmed average over 200
            // reps came out at 147 us, which is JIT plus a cold NBT codec as much as it is the
            // work. Quoting that as "the cost of a transform update" would have overstated the
            // mechanism by whatever the compiler had not got round to yet. Median of a warmed
            // batch, not a mean, so one GC pause does not become the headline.
            for (int i = 0; i < 500; i++) {
                RigDriver.articulate(rig, java.util.Map.of("head", head), 2);
            }
            int reps = 400;
            long[] each = new long[reps];
            for (int i = 0; i < reps; i++) {
                long s0 = System.nanoTime();
                RigDriver.articulate(rig, java.util.Map.of("head", head), 2);
                each[i] = System.nanoTime() - s0;
            }
            java.util.Arrays.sort(each);
            long perWrite = each[reps / 2];

            LOG.info("Q2 cost: one transform update = {} bytes on the wire, {} ns of server time "
                            + "(NBT round-trip; an access transformer would pay a field write "
                            + "instead). Rig = {} displays.",
                    bytes, perWrite, rig.displays().size());
            helper.assertTrue(bytes > 0, Component.literal("a transform update must actually put bytes on the wire"));
            helper.succeed();
        } finally {
            rig.discard();
        }
    }

    /**
     * How big a display's synced-data update really is, encoded through the real packet.
     *
     * <p>Estimating this from "14 floats plus a couple of ints" would be close and unfalsifiable.
     * Encoding {@code ClientboundSetEntityDataPacket} against a real registry buffer is the number
     * the client is actually sent.
     */
    private static int measureTransformBytes(Entity display) {
        var dirty = display.getEntityData().packDirty();
        var all = display.getEntityData().getNonDefaultValues();
        var values = dirty != null ? dirty : all;
        if (values == null) {
            return 0;
        }
        var pkt = new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(
                display.getId(), values);
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(),
                display.level().registryAccess());
        net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket.STREAM_CODEC
                .encode(buf, pkt);
        return buf.readableBytes();
    }
}
