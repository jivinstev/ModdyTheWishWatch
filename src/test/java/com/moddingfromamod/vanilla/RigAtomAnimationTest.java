package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for HIERARCHICAL animation on the atom rig — {@code docs/ATOM_RIG_PLAN.md} Phase 2c.</b>
 *
 * <p>§5.2 records the atom rig's one known regression: <i>"each atom bakes its ancestors' rest
 * transform, so a clip that turns {@code head} moves the head's own box and not its 25 children;
 * {@code RigPose.animatedBones} cannot help because every box is already split"</i>. The second half
 * of that is exactly right about {@link RigPose#animatedBones}, which is a SPLITTING helper and has
 * nothing to give a plan where everything is already split. The first half is right about the plan
 * and wrong about the pipeline, and this class is where that is measured rather than asserted:
 * {@link RigPose#compose} recomputes every bone's chain per frame and {@link RigDriver#animate}
 * already drives it, so the clip path was hierarchical at atom granularity before this phase began.
 *
 * <p>What was NOT hierarchical was head-follow. {@link RigDriver#lookAt} applied the turn as an
 * argument to {@link RigTransform#place} on the one group {@link RigPlan#isHead} matched, which is
 * a whole head only while the head is one display.
 *
 * <h2>The oracle is the mod renderer's own pipeline, driven rather than re-derived</h2>
 *
 * <p>⚠ <b>THE CLASS THIS TRANSCRIBES NO LONGER EXISTS (2026-09-22).</b>
 * {@code RuntimeGeoModel} was deleted with the rest of the legacy renderer, so the transcription
 * below can no longer be diffed against its source. It is still a good oracle — what it encodes is
 * the BEDROCK convention (Y-up, 1u = 1/16 block, X mirrored, rotation on the bone), which is a
 * format rather than a class, and it caught real bugs. But its PROVENANCE is now unverifiable, so
 * treat a disagreement as a question about both sides rather than as the atom rig being wrong, and
 * do not add to it: a new rule belongs in a test that reads something still in the tree.
 *
 * <p>{@link #modRendererCorners} is a transcription of {@code RuntimeGeoModel.render} +
 * {@code drawBone} onto a real {@link PoseStack} — the same {@code scale(-1/16, 1/16, 1/16)}, the
 * same {@code translate(pivot)} / head yaw / head pitch / animation Z,Y,X / rest Z,Y,X /
 * {@code translate(-pivot)} order, on the RAW (un-mirrored) sculpt. Neither {@code PoseStack} nor
 * {@code Axis} needs a Minecraft bootstrap, which is the same thing {@code AtomNormalPathTest}
 * relies on.
 *
 * <p>Transcribing rather than importing is deliberate and is the reason the oracle is worth
 * anything: {@code RuntimeGeoModel} is {@code Dist.CLIENT} and wants a baked model, a vertex
 * consumer and a texture atlas. What matters is the SEQUENCE, and a sequence copied line-for-line
 * from the file under {@code git blame} is checkable by eye in a way an algebraic re-derivation is
 * not. It also exercises the mirror convention for free: the oracle mirrors at render time and the
 * rig mirrors the parts up front, so agreement is evidence about both.
 */
class RigAtomAnimationTest {

    private static final float EPS = 1e-3f;

    /** The clip every wished creature ships, plus a HEAD channel — the case §5.2 is written about. */
    private static final String WALK_WITH_HEAD = """
            {"format_version":"1.8.0","animations":{"animation.sculpted.walk":{
              "loop":true,"animation_length":1.0,"bones":{
                "legR":{"rotation":{"0.0":[25,0,0],"0.5":[-25,0,0],"1.0":[25,0,0]}},
                "legL":{"rotation":{"0.0":[-25,0,0],"0.5":[25,0,0],"1.0":[-25,0,0]}},
                "armR":{"rotation":{"0.0":[-20,0,0],"0.5":[20,0,0],"1.0":[-20,0,0]}},
                "armL":{"rotation":{"0.0":[20,0,0],"0.5":[-20,0,0],"1.0":[20,0,0]}},
                "head":{"rotation":{"0.0":[0,-18,0],"0.5":[0,18,0],"1.0":[0,-18,0]}}}}}}
            """;

    // ────────────────────────────────────────────────────── 1. the two-level fixture

    /**
     * <b>root → arm → hand, one box each, with the ARM swung 30°.</b>
     *
     * <p>The smallest fixture that can see the defect at all. One level cannot: a bone with no
     * children has nothing to leave behind. Two levels is the first shape where "the arm moved and
     * the hand stayed" is expressible, and the hand is deliberately offset from the arm's pivot so
     * a dropped chain moves it visibly rather than by a rounding error.
     */
    private static List<CreatureSculpt.Part> armChain() {
        return List.of(
                new CreatureSculpt.Part("root", null, new float[]{0, 0, 0},
                        new float[]{-4, 0, -3}, new float[]{4, 10, 3}, 0x404040,
                        CreatureSculpt.NO_ROTATION, 0f, "", 0),
                // Asymmetric in X and Z on purpose: a symmetric fixture is blind to the mirror.
                new CreatureSculpt.Part("arm", "root", new float[]{5, 10, 1},
                        new float[]{4, 4, 0}, new float[]{7, 11, 3}, 0x996633,
                        new float[]{0, 0, 12f}, 0f, "", 0),
                new CreatureSculpt.Part("hand", "arm", new float[]{6, 4, 1},
                        new float[]{4.5f, 1, 0}, new float[]{8, 4.5f, 3}, 0xCC9966,
                        CreatureSculpt.NO_ROTATION, 0.25f, "", 0));
    }

    @Test
    @DisplayName("a 30-degree arm swing carries the HAND, turns the arm about its own pivot, and "
            + "leaves the root exactly where it was")
    void aBoneSPoseReachesEveryAtomBeneathIt() {
        List<CreatureSculpt.Part> raw = armChain();
        List<CreatureSculpt.Part> mirrored = RigTransform.mirrorIntoWorldSpace(raw);
        double scale = 1.4;

        Map<String, BedrockAnimation.BonePose> pose = Map.of(
                "arm", new BedrockAnimation.BonePose(new float[]{30f, 0, 0}, null, null));

        List<RigPlan.Group> atoms = RigPlan.atoms(mirrored);
        assertEquals(3, atoms.size(), "one atom per box");

        RigPose.Frame frame = RigPose.compose(mirrored, pose);

        // The COST claim first, because it is the one a driver reads: the arm and the hand are
        // rewritten and the root is not.
        assertTrue(frame.moved().contains("arm"), "the arm has a keyframe");
        assertTrue(frame.moved().contains("hand"),
                "the hand hangs off the arm -- if this is false the arm swings and the hand stays "
                        + "behind, which is the whole of ATOM_RIG_PLAN.md §5.2");
        assertFalse(frame.moved().contains("root"), "nothing moves the root");

        // Then the GEOMETRY, against the mod renderer's own pipeline.
        for (int i = 0; i < atoms.size(); i++) {
            RigPlan.Group g = atoms.get(i);
            CreatureSculpt.Part rawPart = raw.get(i);
            Vector3f[] want = modRendererCorners(raw, pose, null, rawPart, scale);
            Vector3f[] got = placedCorners(RigSeparationTest.unseparated(RigTransform.placeAtom(
                    RigPose.withChain(g, frame), scale, g.restRotation(),
                    pose.containsKey(g.name()) ? pose.get(g.name()).rotation() : null,
                    null, null), g, scale));
            for (int c = 0; c < 8; c++) {
                assertVec(want[c], got[c], "armChain / " + g.name() + " corner " + c);
            }
        }

        // …and the two things the corner oracle proves but does not NAME, so a regression reads as
        // the defect rather than as "some corner moved".
        Vector3f restRootCentre = centre(placedCorners(RigTransform.placeAtom(atoms.get(0), scale)));
        Vector3f posedRootCentre = centre(placedCorners(RigTransform.placeAtom(
                RigPose.withChain(atoms.get(0), frame), scale, atoms.get(0).restRotation())));
        assertVec(restRootCentre, posedRootCentre, "the root must not move at all");

        Vector3f restHand = centre(placedCorners(RigTransform.placeAtom(atoms.get(2), scale)));
        Vector3f posedHand = centre(placedCorners(RigTransform.placeAtom(
                RigPose.withChain(atoms.get(2), frame), scale, atoms.get(2).restRotation())));
        assertTrue(restHand.distance(posedHand) > 0.05f,
                "a 30-degree swing at the shoulder must move the hand by more than a rounding "
                        + "error; it moved " + restHand.distance(posedHand) + " blocks");

        // The arm turns about ITS OWN pivot: the pivot point is a fixed point of the motion.
        Vector3f pivotAtRest = pivotWorld(atoms.get(1), mirrored, null, scale);
        Vector3f pivotPosed = pivotWorld(atoms.get(1), mirrored, frame, scale);
        assertVec(pivotAtRest, pivotPosed, "the arm's pivot is the one point a rotation about it "
                + "cannot move -- if this drifts the pose is being applied about the box centre");
    }

    /**
     * The same fixture, three levels deep, because a two-level chain cannot tell
     * <i>compose the ancestors</i> from <i>copy the parent's transform</i>.
     */
    @Test
    @DisplayName("a THREE-level chain composes both ancestors, not just the nearest one")
    void twoTiltedAncestorsBothReachTheLeaf() {
        List<CreatureSculpt.Part> raw = List.of(
                new CreatureSculpt.Part("torso", null, new float[]{0, 8, 0},
                        new float[]{-4, 0, -2}, new float[]{4, 16, 2}, 0x333333,
                        new float[]{0, 0, 20f}, 0f, "", 0),
                new CreatureSculpt.Part("neck", "torso", new float[]{0, 16, 0},
                        new float[]{-2, 16, -2}, new float[]{2, 22, 2}, 0x666666,
                        new float[]{13f, 0, 0}, 0f, "", 0),
                new CreatureSculpt.Part("crest", "neck", new float[]{1, 22, 0},
                        new float[]{-5, 22, -5}, new float[]{5, 30, 5}, 0x999999,
                        new float[]{0, 7f, 0}, 0f, "", 0));
        List<CreatureSculpt.Part> mirrored = RigTransform.mirrorIntoWorldSpace(raw);

        // BOTH ancestors animated, on different axes, so summing or dropping either is visible.
        Map<String, BedrockAnimation.BonePose> pose = Map.of(
                "torso", new BedrockAnimation.BonePose(new float[]{0, 25f, 0}, null, null),
                "neck", new BedrockAnimation.BonePose(new float[]{-14f, 0, 0},
                        new float[]{0, 1.5f, 0}, null));

        RigPose.Frame frame = RigPose.compose(mirrored, pose);
        List<RigPlan.Group> atoms = RigPlan.atoms(mirrored);
        assertTrue(frame.moved().containsAll(Set.of("torso", "neck", "crest")),
                "every bone at or below an animated one is moved: " + frame.moved());

        for (int i = 0; i < atoms.size(); i++) {
            RigPlan.Group g = atoms.get(i);
            BedrockAnimation.BonePose bp = pose.get(g.name());
            Vector3f[] want = modRendererCorners(raw, pose, null, raw.get(i), 1.0);
            Vector3f[] got = placedCorners(RigSeparationTest.unseparated(RigTransform.placeAtom(
                    RigPose.withChain(g, frame), 1.0, g.restRotation(),
                    bp == null ? null : bp.rotation(),
                    bp == null ? null : bp.position(),
                    bp == null ? null : bp.scale()), g, 1.0));
            for (int c = 0; c < 8; c++) {
                assertVec(want[c], got[c], "chain3 / " + g.name() + " corner " + c);
            }
        }
    }

    // ─────────────────────────────────────────────────── 2. Spikor, and the write count

    @Test
    @DisplayName("posing Spikor's head moves exactly the 26 head atoms (+1 overlay) and nothing else")
    void spikorsHeadTurnMovesTwentySixAtomsAndNoOthers() {
        List<CreatureSculpt.Part> mirrored = spikor();
        Set<String> underHead = descendantsOf(mirrored, RigPlan::isHeadBone);
        assertEquals(26, underHead.size(),
                "the fixture no longer has the 26-box head this test is about: " + underHead);

        RigPose.Frame frame = RigPose.compose(mirrored, Map.of("head",
                new BedrockAnimation.BonePose(new float[]{0, 20f, 0}, null, null)));

        assertEquals(underHead, frame.moved(),
                "a head keyframe must move the head bone and every bone under it -- EXACTLY, so a "
                        + "rig that rewrote the whole creature would fail here too");

        // The number the ledger wants: what a driver would actually write. The exact and grouped
        // columns that used to print beside it went with the pack rig.
        int atomWrites = writeCount(RigPlan.atoms(mirrored), frame);
        System.out.println("ATOM-P2c head-clip writes: atoms=" + atomWrites
                + " (of " + RigPlan.atoms(mirrored).size() + " displays)");
        // 27, not 26: Spikor's `head` is a `scales` part, so it has a §2 B pattern overlay, and the
        // overlay is a display of the SAME bone. If this ever reads 26 again the overlay has
        // stopped moving with its box — a head that turns and leaves its own scales facing forward,
        // which is exactly what RigPlan.Group.boneName() exists to prevent and exactly what nothing
        // would throw about.
        assertEquals(26, writeCount(RigPlan.atoms(mirrored, false), frame),
                "one write per head atom, with the overlays off");
        assertEquals(27, atomWrites,
                "the 26 head atoms plus `head#pattern` — the overlay animates as its own bone");
        assertEquals(1, RigPlan.atoms(mirrored).stream()
                        .filter(RigPlan.Group::patternOverlay)
                        .filter(g -> RigPose.movesGroup(g, frame)).count(),
                "exactly one overlay under the head, and it moved");
    }

    /**
     * <b>The whole-body cost, and the idle cost — the two ends of the ledger row.</b>
     *
     * <p>Printed rather than only asserted, because {@code docs/ATOM_RIG_PLAN.md} Phase 4 is "how
     * many creatures before it lags" and that question needs this number beside the arm that
     * produced it.
     */
    @Test
    @DisplayName("a walk clip's write count is the moving subtree, and an idle creature writes zero")
    void theWriteCountIsTheMovingSubtreeAndIdleIsFree() {
        List<CreatureSculpt.Part> mirrored = spikor();
        BedrockAnimation walk = clip(WALK_WITH_HEAD);

        RigPose.Frame idle = RigPose.compose(mirrored, Map.of());
        assertEquals(0, writeCount(RigPlan.atoms(mirrored), idle),
                "an idle creature must cost ZERO writes -- the claim the whole cost model rests on");

        int worst = 0;
        for (float t = 0f; t < 1.0f; t += 0.05f) {
            RigPose.Frame f = RigPose.compose(mirrored, walk.pose(t));
            worst = Math.max(worst, writeCount(RigPlan.atoms(mirrored), f));
        }
        // t=0.25 and t=0.75 are the zero crossings of every channel at once, so SOME frame of a
        // real walk is free even on a moving creature. Reporting the worst case is the honest
        // number for a traffic budget.
        //
        // The bytes are the plan's own model -- ~53 per moved display, every DEFAULT_INTERVAL
        // ticks -- turned into a rate here rather than in prose, because the arithmetic was got
        // wrong by 20x the first time it was written out by hand.
        double batchesPerSecond = 20.0 / RigAnimator.DEFAULT_INTERVAL;
        // (The exact/grouped columns went with the pack rig; atoms are the only arm.)
        System.out.printf("ATOM-P2c walk-clip writes (worst frame): atoms=%d/%d  ->  %.1f kB/s "
                        + "per viewer at %d-tick interval, 53 B a write%n",
                worst, RigPlan.atoms(mirrored).size(),
                worst * 53 * batchesPerSecond / 1000.0,
                RigAnimator.DEFAULT_INTERVAL);
        assertTrue(worst >= 26 + 4, "a walk that swings four limbs and turns the head must move at "
                + "least the head subtree plus the four limbs; it moved " + worst);
        assertTrue(worst < RigPlan.atoms(mirrored).size(),
                "a clip that touches five bones must not rewrite the WHOLE creature (" + worst
                        + " of " + RigPlan.atoms(mirrored).size() + ") -- if it does, the 'a bone "
                        + "nothing moved is not rewritten' rule has been lost");
    }

    // ───────────────────────────────────────────────── head-follow, the actual Phase 2c fix

    @Test
    @DisplayName("head-follow turns the head's whole atom set, not just the head box")
    void headFollowReachesEveryAtomUnderTheHead() {
        List<CreatureSculpt.Part> mirrored = spikor();
        Set<String> underHead = descendantsOf(mirrored, RigPlan::isHeadBone);

        RigPose.Frame frame = RigPose.compose(mirrored, null, new float[]{35f, -12f});
        assertEquals(underHead, frame.moved(),
                "head-follow is a bone transform and is therefore inherited -- exactly the head "
                        + "subtree, no more and no less");

        // …and it is the SAME turn a clip keyframe of the same angle would give the children, which
        // is what makes the two composable rather than two conventions.
        RigPlan.Group horn = named(RigPlan.atoms(mirrored), "kit_horn20");
        Vector3f rest = centre(placedCorners(RigTransform.placeAtom(horn, 1.0)));
        Vector3f followed = centre(placedCorners(RigTransform.placeAtom(
                RigPose.withChain(horn, frame), 1.0, horn.restRotation())));
        assertTrue(rest.distance(followed) > 0.05f,
                "a 35-degree head turn must move a horn on the head; it moved "
                        + rest.distance(followed));
    }

    @Test
    @DisplayName("head-follow lands where the mod renderer puts it, on the head AND on its children")
    void headFollowMatchesTheModRenderer() {
        List<CreatureSculpt.Part> raw = SpikeFixture.parts(SpikeFixture.SPIKOR);
        List<CreatureSculpt.Part> mirrored = RigTransform.mirrorIntoWorldSpace(raw);
        double scale = SpikeFixture.scale(SpikeFixture.SPIKOR);
        float[] head = {35f, -12f};

        RigPose.Frame frame = RigPose.compose(mirrored, null, head);
        List<RigPlan.Group> atoms = RigPlan.atoms(mirrored);

        java.util.Map<String, CreatureSculpt.Part> rawByName = new java.util.LinkedHashMap<>();
        for (CreatureSculpt.Part p : raw) {
            rawByName.putIfAbsent(p.name(), p);
        }

        int checked = 0;
        for (RigPlan.Group g : atoms) {
            // A §2 B pattern overlay is the same box lifted a hair, so it has nothing new to say to
            // a corner oracle and would fail it by exactly that hair. Its geometry is asserted
            // against its base in RigPlanAtomTest.theOverlayIsLiftedByAConstantInBlocks; what
            // matters HERE is that it moves at all, which theOverlayFollowsItsOwnBone checks.
            if (g.patternOverlay() || !RigPose.movesGroup(g, frame)) {
                continue;
            }
            Vector3f[] want = modRendererCorners(raw, null, head, rawByName.get(g.boneName()), scale);
            Vector3f[] got = placedCorners(RigSeparationTest.unseparated(RigTransform.placeAtom(
                    RigPose.withChain(g, frame), scale, g.restRotation(), null, null, null,
                    RigPlan.isHead(g) ? head : null), g, scale));
            for (int c = 0; c < 8; c++) {
                assertVec(want[c], got[c], "headfollow / " + g.name() + " corner " + c);
            }
            checked++;
        }
        assertEquals(26, checked, "every head atom must be checked, not merely iterated");
    }

    /**
     * <b>§2 B: the pattern overlay follows its own bone, through every lookup a driver makes.</b>
     *
     * <p>An overlay's group is named {@code "<bone>#pattern"} so it can be a distinct display, and
     * the frame it is asked about is composed from the SCULPT, which has never heard of that name.
     * Three separate lookups have to take the suffix off — {@code RigPose.movesGroup},
     * {@code RigPose.withChain} and the pose lookup in {@code RigDriver.articulate}/{@code animate}
     * — and all three fail the same way when they do not: silently, with the base cube animating and
     * the pattern standing still. A creature whose surface detaches from its body.
     *
     * <p>So this asks each of the three directly, on the box the failure would be most visible on:
     * Spikor's {@code body}, which is 26 boxes of head away from anything else that moves.
     */
    @Test
    @DisplayName("a pattern overlay animates as the bone it is drawn on, not as its own name")
    void theOverlayFollowsItsOwnBone() {
        List<CreatureSculpt.Part> mirrored = spikor();
        List<RigPlan.Group> atoms = RigPlan.atoms(mirrored, true);
        RigPlan.Group base = atoms.stream().filter(g -> g.name().equals("body")).findFirst()
                .orElseThrow(() -> new AssertionError("the fixture has no `body` box"));
        RigPlan.Group over = atoms.stream().filter(g -> g.name().equals("body" + RigPlan.PATTERN_SUFFIX))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "Spikor's `body` is a `scales` part and must have an overlay"));

        RigPose.Frame frame = RigPose.compose(mirrored, Map.of("body",
                new BedrockAnimation.BonePose(new float[]{0, 25f, 0}, null, null)));

        // 1. movesGroup — otherwise the driver `continue`s and never writes the overlay at all.
        assertTrue(RigPose.movesGroup(base, frame), "the base box moved");
        assertTrue(RigPose.movesGroup(over, frame),
                "the overlay did NOT move — the driver would skip it and the pattern would stay "
                        + "in the creature's rest pose while its skin turned");

        // THE GUARD THAT KEEPS THIS FROM BEING VACUOUS: the frame really does NOT know the
        // overlay's own name, so a lookup that forgot boneName() would genuinely have missed.
        // Without this the whole test would pass just as happily against a plan that named the
        // overlay "body" twice, which is a different (and worse) design.
        assertTrue(frame.moved().contains(over.boneName()));
        assertFalse(frame.moved().contains(over.name()),
                "the frame knows `" + over.name() + "` by name, so this test cannot tell a "
                        + "suffix-stripping lookup from a naive one");

        // 2. withChain — otherwise it keeps its baked REST chain and lands in the wrong place.
        RigPlan.Group basePosed = RigPose.withChain(base, frame);
        RigPlan.Group overPosed = RigPose.withChain(over, frame);
        assertArrayEquals(basePosed.chainRotationXyzw(), overPosed.chainRotationXyzw(), 1e-6f,
                "the overlay resolved a different chain from its own box");
        assertArrayEquals(basePosed.chainTranslation(), overPosed.chainTranslation(), 1e-6f);

        // 3. the pose lookup a driver does per display, which is keyed by bone name.
        assertNotNull(poseFor(frameBones(), base), "the base box's own keyframe");
        assertNotNull(poseFor(frameBones(), over),
                "the overlay found no keyframe — RigDriver.articulate keys `pose` by name, so a "
                        + "suffixed lookup misses and the overlay is never rewritten");

        // …and the two land in the same place, bar the hair.
        RigTransform.Placed pb = RigTransform.placeAtom(basePosed, 1.0, base.restRotation());
        RigTransform.Placed po = RigTransform.placeAtom(overPosed, 1.0, over.restRotation());
        assertVec(pb.translation(), po.translation(), "posed overlay translation");
        assertEquals(pb.leftRotation(), po.leftRotation(), "posed overlay rotation");
    }

    /**
     * <b>…and the INHERITED half, which Spikor cannot show.</b>
     *
     * <p>Both of his patterned boxes ({@code body}, {@code head}) are roots, so their ancestor
     * chain is identity whatever the pose does — a chain lookup on them cannot distinguish a
     * working {@code withChain} from one that returns the group untouched. This is the smallest
     * fixture that can: a patterned child under a bone the clip turns, where the overlay's chain
     * has to CHANGE and has to change to exactly its base's.
     */
    @Test
    @DisplayName("a patterned child inherits its parent's turn, on the overlay as well as the box")
    void theOverlayInheritsItsAncestorsChain() {
        List<CreatureSculpt.Part> parts = List.of(
                new CreatureSculpt.Part("torso", null, new float[]{0, 8, 0},
                        new float[]{-4, 0, -2}, new float[]{4, 16, 2}, 0x333333),
                new CreatureSculpt.Part("plate", "torso", new float[]{0, 16, 0},
                        new float[]{-3, 16, -3}, new float[]{3, 22, 3}, 0x00AA00,
                        CreatureSculpt.NO_ROTATION, 0f, "stripes", 0xFFCC00));
        List<CreatureSculpt.Part> mirrored = RigTransform.mirrorIntoWorldSpace(parts);
        List<RigPlan.Group> atoms = RigPlan.atoms(mirrored, true);
        assertEquals(List.of("torso", "plate", "plate" + RigPlan.PATTERN_SUFFIX),
                atoms.stream().map(RigPlan.Group::name).toList());

        RigPose.Frame frame = RigPose.compose(mirrored, Map.of("torso",
                new BedrockAnimation.BonePose(new float[]{0, 30f, 0}, null, null)));

        RigPlan.Group base = atoms.get(1);
        RigPlan.Group over = atoms.get(2);
        assertTrue(RigPose.movesGroup(over, frame), "the overlay's ANCESTOR moved, so it moves");

        RigPlan.Group basePosed = RigPose.withChain(base, frame);
        RigPlan.Group overPosed = RigPose.withChain(over, frame);
        assertFalse(java.util.Arrays.equals(over.chainRotationXyzw(),
                        overPosed.chainRotationXyzw()),
                "the overlay kept its REST chain — it would sit where the plate used to be while "
                        + "the plate itself turned with the torso");
        assertArrayEquals(basePosed.chainRotationXyzw(), overPosed.chainRotationXyzw(), 1e-6f);
        assertArrayEquals(basePosed.chainTranslation(), overPosed.chainTranslation(), 1e-6f);

        // …and they land concentric, which is the thing an eye would notice.
        assertVec(RigTransform.placeAtom(basePosed, 1.0, base.restRotation()).translation(),
                RigTransform.placeAtom(overPosed, 1.0, over.restRotation()).translation(),
                "the posed overlay is not concentric with its posed box");
    }

    private static Map<String, BedrockAnimation.BonePose> frameBones() {
        return Map.of("body", new BedrockAnimation.BonePose(new float[]{0, 25f, 0}, null, null));
    }

    /** What {@code RigDriver} looks up per display — spelt here so the KEY is what is asserted. */
    private static BedrockAnimation.BonePose poseFor(
            Map<String, BedrockAnimation.BonePose> pose, RigPlan.Group g) {
        return pose.get(g.boneName());
    }

    /**
     * <b>The head-follow rotation is spelt ONCE.</b>
     *
     * <p>It is applied in two places that must agree — {@link RigTransform#place} for the head
     * bone's own display, and {@link RigPose} for the chain its children inherit. The test above
     * would catch a disagreement in the corner numbers; this one names it, because "the face turns
     * the opposite way from the skull" and "some corner moved" want different fixes.
     */
    @Test
    @DisplayName("the head bone's own turn and the turn its children inherit are the same rotation")
    void oneHeadRotation() {
        float[] head = {41f, -7f};
        CreatureSculpt.Part solo = new CreatureSculpt.Part(
                "head", null, new float[]{0, 6, 0},
                new float[]{-3, 3, -3}, new float[]{3, 9, 3}, 0x00FF00);
        List<CreatureSculpt.Part> one = List.of(solo);

        // Route A: place() with headDeg, which is what the head's own display gets.
        RigPlan.Group g = RigPlan.atoms(one).get(0);
        org.joml.Quaternionf viaPlace = RigTransform.placeAtom(
                g, 1.0, g.restRotation(), null, null, null, head).leftRotation();

        // Route B: the chain a child would inherit, read off the composer.
        List<CreatureSculpt.Part> two = new ArrayList<>(one);
        two.add(new CreatureSculpt.Part("horn", "head", new float[]{0, 9, 0},
                new float[]{-1, 9, -1}, new float[]{1, 12, 1}, 0xFF0000));
        RigPose.Frame f = RigPose.compose(two, null, head);
        RigPose.Chain c = f.chains().get("horn");
        org.joml.Quaternionf viaChain = new org.joml.Quaternionf(
                c.rotationXyzw()[0], c.rotationXyzw()[1], c.rotationXyzw()[2], c.rotationXyzw()[3]);

        assertEquals(viaPlace.x, viaChain.x, EPS, "head rotation x");
        assertEquals(viaPlace.y, viaChain.y, EPS, "head rotation y");
        assertEquals(viaPlace.z, viaChain.z, EPS, "head rotation z");
        assertEquals(viaPlace.w, viaChain.w, EPS, "head rotation w");
    }

    // ─────────────────────────────────────── 3. the exact and grouped arms are unchanged

    /**
     * <b>The head-follow parameter is a strict no-op when it is absent or zero.</b>
     *
     * <p>Three spellings of "not looking anywhere" must produce byte-identical frames, so the arms
     * that never pass one cannot have moved. Zero specifically matters because {@code lookAt}'s own
     * guard lets a first call at {@code (0,0)} through on a rig that has never been written.
     */
    @Test
    @DisplayName("no head angle, a null head angle and a zero head angle are the same frame")
    void headFollowIsAStrictNoOpWhenItIsNotLookingAnywhere() {
        List<CreatureSculpt.Part> mirrored = spikor();
        Map<String, BedrockAnimation.BonePose> pose = clip(WALK_WITH_HEAD).pose(0.13f);

        RigPose.Frame a = RigPose.compose(mirrored, pose);
        RigPose.Frame b = RigPose.compose(mirrored, pose, null);
        RigPose.Frame c = RigPose.compose(mirrored, pose, new float[]{0f, 0f});

        assertEquals(a.moved(), b.moved(), "null head must not change what moved");
        assertEquals(a.moved(), c.moved(), "a zero head angle must not change what moved");
        for (String bone : a.chains().keySet()) {
            assertChainEquals(a.chains().get(bone), b.chains().get(bone), bone + " / null");
            assertChainEquals(a.chains().get(bone), c.chains().get(bone), bone + " / zero");
        }
    }

    /**
     * <b>The PLANS are untouched by Phase 2c.</b>
     *
     * <p>Phase 2c changes the DRIVE path, so every group's baked rest chain must still be exactly
     * what {@code RigPlan.ancestorChain} produces — the property {@code RigPlanAtomTest} measures
     * corner-by-corner and this one states as an equality, over every fixture.
     *
     * <p>This used to run over all three granularities; the two exact-plan arms went with the pack
     * rig, and the atom plan — the one that ships — is what is left.
     */
    @Test
    @DisplayName("an EMPTY frame reproduces the atom plan's baked rest chain")
    void anEmptyFrameChangesNoPlansChainFields() {
        for (String name : availableFixtures()) {
            List<CreatureSculpt.Part> mirrored =
                    RigTransform.mirrorIntoWorldSpace(SpikeFixture.parts(name));
            RigPose.Frame rest = RigPose.compose(mirrored, Map.of());
            assertTrue(rest.moved().isEmpty(), name + ": a rest frame moves nothing");

            for (RigPlan.Group g : RigPlan.atoms(mirrored)) {
                // boneName, not name: a pattern overlay is a second display of its bone, and the
                // frame knows the bone only by its own name (RigPlan.Group.boneName's javadoc).
                RigPose.Chain c = rest.chains().get(g.boneName());
                if (c == null) {
                    continue;   // no bone of its own name
                }
                assertChainEquals(new RigPose.Chain(g.chainRotationXyzw(),
                                g.chainTranslation()), c, name + " / " + g.name());
            }
        }
    }

    // ────────────────────────────────────────────── 4. the moon-walk, on atoms

    /**
     * <b>§12's moon-walk, pinned at Gate A: a display's yaw is derived in ONE place and the atom
     * arm is not a second one.</b>
     *
     * <p>The bug {@code docs/VANILLA_RIG_PARITY.md} §12 records is a half turn applied in
     * {@code spawn} and absent from {@code follow}, so a rig was perfect standing still and 180°
     * out the moment it took a step. Nothing above the eye caught it — the transforms live in the
     * display's own frame and do not care which way the body points, which is precisely why this
     * cannot be a transform assertion.
     *
     * <p>So it is a SOURCE check, the technique {@code AtomPackWiringTest} already uses for a claim
     * a running JVM cannot show: every place that writes a display's yaw must take it from
     * {@link DisplayRig#displayYaw}, and that call must not sit behind an atom test. An atom rig has
     * 62 displays where a grouped one has 6, so a driver written for it that re-derived the yaw
     * would moon-walk ten times as loudly and pass every number in this file.
     */
    @Test
    @DisplayName("every display yaw comes from displayYaw, and no arm gets its own")
    void theHalfTurnIsSpeltOnceAndAppliesToAtomsToo() {
        String driver = source("src/main/java/com/moddingfromamod/vanilla/RigDriver.java");
        String rig = source("src/main/java/com/moddingfromamod/vanilla/DisplayRig.java");

        // Both writers derive the same value from the same helper.
        assertTrue(driver.contains("float rigYaw = DisplayRig.displayYaw(yawDeg);"),
                "RigDriver.follow must derive the display yaw from DisplayRig.displayYaw -- §12");
        assertTrue(rig.contains("float rigYaw = displayYaw(yaw);"),
                "DisplayRig.spawn must derive the display yaw from displayYaw -- §12");

        // Nothing anywhere writes a display with a raw body yaw.
        for (String line : (driver + "\n" + rig).split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*")) {
                continue;
            }
            if (t.contains("d.moveTo(") || t.contains("display.moveTo(")) {
                assertTrue(t.contains("rigYaw"),
                        "a display is being placed with something other than rigYaw, which is the "
                                + "moon-walk of §12 arriving again: " + t);
            }
        }

        // …and the helper itself is not atom-aware, so the two arms cannot disagree about it.
        int at = rig.indexOf("public static float displayYaw(float bodyYaw)");
        assertTrue(at > 0, "displayYaw has been renamed; this check no longer means anything");
        String body = rig.substring(at, rig.indexOf('}', at));
        assertFalse(body.contains("atom") || body.contains("Atom"),
                "displayYaw must be one convention for every arm, not a branch: " + body);
    }

    /**
     * <b>Every per-bone driver goes through the ONE chain composer.</b>
     *
     * <p>{@code RigDriver.placed}'s javadoc already records why the granularity branch is a single
     * dispatch: <i>"five call sites is five chances for the next driver method to be written with
     * the branch missing"</i>. The chain is the same hazard one layer up, and it is worse, because
     * a driver that forgets {@link RigPose#withChain} does not draw cubes &mdash; it draws a
     * creature whose head turns and whose face stays behind, which reads as a modelling mistake.
     *
     * <p>The rule is anchored on {@code placed(rig,}, the private helper every bone pose goes
     * through, rather than on a list of method names &mdash; a list is exactly the thing the next
     * method is not added to. {@code flop} is not caught by it and should not be: it turns the whole
     * body through {@code rig.placed(group)} and rewrites every display on purpose.
     *
     * <p>A source check because it is a claim about code that is not written yet. The numbers in
     * this file measure the two drivers that exist; this is what makes the third one go red.
     */
    @Test
    @DisplayName("every driver that poses a bone composes its chain and skips what did not move")
    void thereIsOneChainComposerAndEveryPoseDriverUsesIt() {
        String src = source("src/main/java/com/moddingfromamod/vanilla/RigDriver.java");
        Map<String, String> posers = new LinkedHashMap<>();
        for (Map.Entry<String, String> m : methods(src).entrySet()) {
            if (m.getValue().contains("placed(rig,")) {
                posers.put(m.getKey(), m.getValue());
            }
        }
        assertTrue(posers.keySet().containsAll(Set.of("animate", "lookAt")),
                "the two hierarchical drivers are no longer found by this check, so it is "
                        + "measuring nothing: found " + posers.keySet());

        for (Map.Entry<String, String> m : posers.entrySet()) {
            if (m.getKey().equals("articulate")) {
                // The documented legacy path: it poses only bones with their OWN keyframe and says
                // so in `animate`'s javadoc, which is the sentence that stops a caller picking it
                // by accident.
                assertTrue(src.contains("{@link #articulate} rewrites a display when that display's"
                                + " OWN bone has a keyframe"),
                        "articulate is the non-hierarchical path and animate's javadoc is where "
                                + "that is explained; if that sentence has gone, the difference "
                                + "between the two is undocumented and the next caller will pick "
                                + "the wrong one");
                continue;
            }
            assertTrue(m.getValue().contains("RigPose.withChain("),
                    m.getKey() + " poses bones without composing their chain, so a bone under an "
                            + "animated or followed ancestor keeps its REST chain and is left "
                            + "behind -- ATOM_RIG_PLAN.md §5.2");
            assertTrue(m.getValue().contains("RigPose.movesGroup("),
                    m.getKey() + " does not skip the bones nothing moved, so an idle creature is "
                            + "no longer free and the whole cost model is gone");
        }
    }

    /**
     * Every method of a source file, by name, with overloads concatenated.
     *
     * <p>Concatenated on purpose: {@code animate} has a delegating overload that poses nothing, and
     * keying on the first match found it instead of the one that does the work &mdash; a check that
     * read a real driver as having no chain composition at all.
     */
    private static Map<String, String> methods(String src) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:public|private) static \\w[\\w<>,.\\[\\] ]* (\\w+)\\(")
                .matcher(src);
        while (m.find()) {
            int open = src.indexOf('{', m.end());
            if (open < 0) {
                continue;
            }
            int depth = 0;
            for (int i = open; i < src.length(); i++) {
                if (src.charAt(i) == '{') {
                    depth++;
                } else if (src.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) {
                        out.merge(m.group(1), src.substring(open, i + 1), (a, b) -> a + "\n" + b);
                        break;
                    }
                }
            }
        }
        return out;
    }

    // ───────────────────────────────────────────────────────────────────────── oracle

    /**
     * The eight corners of one RAW box in world space, through {@code RuntimeGeoModel}'s own pose
     * stack. Corner {@code n} is enumerated over the MIRRORED box's lo/hi so it pairs index-for-index
     * with {@link #placedCorners}: mirroring negates X, so the mirrored low corner is the raw HIGH
     * one and the x loop runs backwards here.
     */
    private static Vector3f[] modRendererCorners(List<CreatureSculpt.Part> rawParts,
                                                 Map<String, BedrockAnimation.BonePose> pose,
                                                 float[] headDeg,
                                                 CreatureSculpt.Part box,
                                                 double scale) {
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : rawParts) {
            byName.putIfAbsent(p.name(), p);
        }
        List<CreatureSculpt.Part> chain = new ArrayList<>();
        CreatureSculpt.Part at = box;
        Set<String> seen = new LinkedHashSet<>();
        while (at != null && seen.add(at.name())) {
            chain.add(0, at);
            at = at.parent() == null || at.parent().isEmpty() ? null : byName.get(at.parent());
        }

        PoseStack ps = new PoseStack();
        // RuntimeGeoModel.render: bedrock is Y-up, 1u = 1/16 block, and X is MIRRORED.
        ps.scale(-1f / 16f, 1f / 16f, 1f / 16f);
        for (CreatureSculpt.Part b : chain) {
            applyBone(ps, b, pose, headDeg);
        }

        float inf = box.inflate();
        float[] lo = new float[3];
        float[] hi = new float[3];
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(box.from()[i], box.to()[i]) - inf;
            hi[i] = Math.max(box.from()[i], box.to()[i]) + inf;
        }
        Vector3f[] out = new Vector3f[8];
        int n = 0;
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    // xi == 0 is the MIRRORED low corner, i.e. the raw HIGH one.
                    Vector3f m = new Vector3f(xi == 0 ? hi[0] : lo[0],
                            yi == 0 ? lo[1] : hi[1], zi == 0 ? lo[2] : hi[2]);
                    out[n++] = ps.last().pose().transformPosition(m).mul((float) scale);
                }
            }
        }
        return out;
    }

    /** One bone's block of {@code RuntimeGeoModel.drawBone}, transcribed line for line. */
    private static void applyBone(PoseStack ps, CreatureSculpt.Part b,
                                  Map<String, BedrockAnimation.BonePose> pose, float[] headDeg) {
        float px = b.pivot()[0];
        float py = b.pivot()[1];
        float pz = b.pivot()[2];
        BedrockAnimation.BonePose anim = pose == null ? null : pose.get(b.name());
        if (anim != null && anim.isIdentity()) {
            anim = null;
        }
        boolean isHead = headDeg != null && (headDeg[0] != 0 || headDeg[1] != 0)
                && RigPlan.isHeadBone(b.name());
        float[] rot = b.rotation() == null ? CreatureSculpt.NO_ROTATION : b.rotation();
        boolean rotates = rot[0] != 0 || rot[1] != 0 || rot[2] != 0 || isHead || anim != null;
        if (!rotates) {
            return;
        }
        ps.translate(px, py, pz);
        if (anim != null && anim.position() != null) {
            ps.translate(anim.position()[0], anim.position()[1], anim.position()[2]);
        }
        if (isHead) {
            ps.mulPose(Axis.YP.rotationDegrees(headDeg[0]));
            ps.mulPose(Axis.XP.rotationDegrees(headDeg[1]));
        }
        // Bedrock order is Z, Y, X; Y and Z negate because the top-level scale(-1,…) mirrors X.
        if (anim != null && anim.rotation() != null) {
            ps.mulPose(Axis.ZP.rotationDegrees(-anim.rotation()[2]));
            ps.mulPose(Axis.YP.rotationDegrees(-anim.rotation()[1]));
            ps.mulPose(Axis.XP.rotationDegrees(anim.rotation()[0]));
        }
        ps.mulPose(Axis.ZP.rotationDegrees(-rot[2]));
        ps.mulPose(Axis.YP.rotationDegrees(-rot[1]));
        ps.mulPose(Axis.XP.rotationDegrees(rot[0]));
        if (anim != null && anim.scale() != null) {
            ps.scale(anim.scale()[0], anim.scale()[1], anim.scale()[2]);
        }
        ps.translate(-px, -py, -pz);
    }

    // ───────────────────────────────────────────────────────────────────────── helpers

    private static List<CreatureSculpt.Part> spikor() {
        return RigTransform.mirrorIntoWorldSpace(SpikeFixture.parts(SpikeFixture.SPIKOR));
    }

    private static BedrockAnimation clip(String json) {
        Map<String, BedrockAnimation> all =
                BedrockAnimation.parseAll(JsonParser.parseString(json).getAsJsonObject());
        BedrockAnimation a = BedrockAnimation.find(all, BedrockAnimation.Clip.WALK);
        assertTrue(a != null, "the fixture clip did not parse");
        return a;
    }

    private static List<String> availableFixtures() {
        List<String> out = new ArrayList<>(List.of(
                SpikeFixture.UNIT_BOX, SpikeFixture.TILT_X, SpikeFixture.TWO_BOX,
                SpikeFixture.ASYM, SpikeFixture.LEGS3, SpikeFixture.CHAIN,
                SpikeFixture.TWO_GROUP, SpikeFixture.MULTI_TILT));
        for (String c : SpikeFixture.CASES) {
            if (SpikeFixture.available(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Every bone at or under one the predicate matches — the subtree a turn there must reach. */
    private static Set<String> descendantsOf(List<CreatureSculpt.Part> parts,
                                             java.util.function.Predicate<String> root) {
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            byName.putIfAbsent(p.name(), p);
        }
        Set<String> out = new LinkedHashSet<>();
        for (CreatureSculpt.Part p : parts) {
            String at = p.name();
            Set<String> seen = new LinkedHashSet<>();
            while (at != null && !at.isEmpty() && seen.add(at)) {
                if (root.test(at)) {
                    out.add(p.name());
                    break;
                }
                CreatureSculpt.Part a = byName.get(at);
                at = a == null ? null : a.parent();
            }
        }
        return out;
    }

    /** How many displays a driver would rewrite for this frame, on this plan. */
    private static int writeCount(List<RigPlan.Group> plan, RigPose.Frame frame) {
        int n = 0;
        for (RigPlan.Group g : plan) {
            if (RigPose.movesGroup(g, frame)) {
                n++;
            }
        }
        return n;
    }

    private static RigPlan.Group named(List<RigPlan.Group> plan, String name) {
        for (RigPlan.Group g : plan) {
            if (g.name().equals(name)) {
                return g;
            }
        }
        throw new AssertionError("the fixture no longer has a bone called " + name);
    }

    /** Where a group's pivot lands in the world, at rest or in a frame. */
    private static Vector3f pivotWorld(RigPlan.Group g, List<CreatureSculpt.Part> mirrored,
                                       RigPose.Frame frame, double scale) {
        RigPlan.Group use = frame == null ? g : RigPose.withChain(g, frame);
        RigTransform.Placed p = RigTransform.placeAtom(use, scale, use.restRotation(),
                frame == null ? null : new float[]{30f, 0, 0}, null, null);
        // The pivot in the display's own model frame, relative to the group centre.
        Vector3f local = new Vector3f(
                g.pivotUnits()[0] - g.originUnits()[0],
                g.pivotUnits()[1] - g.originUnits()[1],
                g.pivotUnits()[2] - g.originUnits()[2])
                .mul((float) scale / RigPlan.UNITS_PER_BLOCK);
        return local.rotate(p.leftRotation()).add(p.translation());
    }

    private static Vector3f[] placedCorners(RigTransform.Placed p) {
        Vector3f[] out = new Vector3f[8];
        int n = 0;
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    Vector3f c = new Vector3f(
                            (xi == 0 ? -0.5f : 0.5f) * p.scale().x(),
                            (yi == 0 ? -0.5f : 0.5f) * p.scale().y(),
                            (zi == 0 ? -0.5f : 0.5f) * p.scale().z());
                    out[n++] = c.rotate(p.leftRotation()).add(p.translation());
                }
            }
        }
        return out;
    }

    private static Vector3f centre(Vector3f[] corners) {
        Vector3f c = new Vector3f();
        for (Vector3f v : corners) {
            c.add(v);
        }
        return c.mul(1f / corners.length);
    }

    private static void assertVec(Vector3f want, Vector3f got, String what) {
        assertEquals(want.x(), got.x(), EPS, what + " x");
        assertEquals(want.y(), got.y(), EPS, what + " y");
        assertEquals(want.z(), got.z(), EPS, what + " z");
    }

    private static void assertChainEquals(RigPose.Chain want, RigPose.Chain got, String what) {
        assertTrue(got != null, what + ": no chain at all");
        for (int i = 0; i < 4; i++) {
            assertEquals(want.rotationXyzw()[i], got.rotationXyzw()[i], EPS, what + " q" + i);
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(want.translation()[i], got.translation()[i], EPS, what + " t" + i);
        }
    }

    private static String source(String repoRelative) {
        try {
            return java.nio.file.Files.readString(SpikeFixture.find(repoRelative));
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + repoRelative, e);
        }
    }

    /**
     * <b>Every atom of a REAL creature, under a pose that moves four limbs at once.</b>
     *
     * <p>{@link #aBoneSPoseReachesEveryAtomBeneathIt} and its three-level sibling check the chain
     * against the mod renderer on hand-written fixtures of two and three boxes. That is the right
     * shape for the maths and it leaves one question open, which Phase 2 had to answer with a
     * film: does the same composition hold across sixty-two boxes, seven bone levels and four
     * simultaneously animated limbs, on the creature the plan names first?
     *
     * <p>It does — corner for corner, against {@code RuntimeGeoModel}'s own pipeline. Worth having
     * as a GATE rather than as a one-off measurement because the Gate C film that asked the same
     * question came back scattered, and the cause turned out to be nowhere near the transform
     * (a selected rig pack shadowing the carrier's override table, §5.8.3). A green row here is
     * what let that be diagnosed in one step instead of by bisecting the driver.
     */
    @Test
    @DisplayName("all 62 of Spikor's atoms land where the mod renderer puts them under a "
            + "four-limb walk pose")
    void everyAtomOfARealCreatureMatchesTheModRendererUnderAWalkPose() {
        List<CreatureSculpt.Part> raw = SpikeFixture.parts(SpikeFixture.SPIKOR);
        List<CreatureSculpt.Part> mirrored = RigTransform.mirrorIntoWorldSpace(raw);
        double scale = SpikeFixture.scale(SpikeFixture.SPIKOR);
        Map<String, BedrockAnimation.BonePose> pose = Map.of(
                "armL", new BedrockAnimation.BonePose(new float[]{35f, 0, 0}, null, null),
                "armR", new BedrockAnimation.BonePose(new float[]{-35f, 0, 0}, null, null),
                "legL", new BedrockAnimation.BonePose(new float[]{-25f, 0, 0}, null, null),
                "legR", new BedrockAnimation.BonePose(new float[]{25f, 0, 0}, null, null));
        List<RigPlan.Group> full = RigPlan.atoms(mirrored);
        // ⚠ Phase 2d: `atoms` returns 62 base cubes AND 2 pattern overlays for this creature, so
        // the plan is no longer index-parallel with the sculpt's boxes. The oracle pairs atom i
        // with box i, so it must be given the BASE plan; the overlays are deliberately excluded
        // rather than tolerated, because an overlay is its base inflated by PATTERN_LIFT_BLOCKS
        // and so does NOT share its corners. `theOverlayRidesItsBase` owns that half.
        List<RigPlan.Group> atoms = full.stream().filter(g -> !g.patternOverlay()).toList();
        RigPose.Frame frame = RigPose.compose(mirrored, pose);
        assertEquals(raw.size(), atoms.size(), "one base atom per box");
        assertTrue(full.size() > atoms.size(),
                "spikor has two `scales` parts, so the full plan must carry pattern overlays -- "
                        + "if it does not, this test is silently checking the Phase 1a plan");
        // The four limbs carry children, so the moving set has to be bigger than the four bones.
        assertTrue(frame.moved().size() > pose.size(),
                "four limbs with children moved only " + frame.moved().size() + " bones");
        for (int i = 0; i < atoms.size(); i++) {
            RigPlan.Group g = atoms.get(i);
            CreatureSculpt.Part rawPart = raw.get(i);
            assertEquals(rawPart.name(), g.name(),
                    "the atom plan must stay in the sculpt's own order, or this oracle compares "
                            + "one box against another's");
            Vector3f[] want = modRendererCorners(raw, pose, null, rawPart, scale);
            Vector3f[] got = placedCorners(RigSeparationTest.unseparated(RigTransform.placeAtom(
                    RigPose.withChain(g, frame), scale, g.restRotation(),
                    pose.containsKey(g.name()) ? pose.get(g.name()).rotation() : null,
                    null, null), g, scale));
            for (int c = 0; c < 8; c++) {
                assertVec(want[c], got[c], SpikeFixture.SPIKOR + " / " + g.name() + " corner " + c);
            }
        }
    }
}
