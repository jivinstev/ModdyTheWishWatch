package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

/**
 * <b>The animation spike's cheap oracles.</b>
 *
 * <p>Two questions, both answerable with no Minecraft and no client, and between them they cover
 * what a photograph cannot: does an animated frame agree with what the MOD's renderer would compute
 * for the same frame, and does a loop actually close.
 *
 * <p>The loop-closure one is why {@code docs/VANILLA_RIG_PARITY.md} §9.8 picks a walk cycle for the
 * spike. A walk returns to its own first frame, so frame 0 and frame N must be identical with no
 * second renderer to compare against at all. A composition error accumulates and shows as drift; a
 * single held pose hides it completely.
 */
class RigPoseTest {

    /** The clip every wished creature ships — legs in opposite phase, arms counter-swinging. */
    private static final String WALK = """
            {"format_version":"1.8.0","animations":{"animation.sculpted.walk":{
              "loop":true,"animation_length":1.0,"bones":{
                "legR":{"rotation":{"0.0":[25,0,0],"0.5":[-25,0,0],"1.0":[25,0,0]}},
                "legL":{"rotation":{"0.0":[-25,0,0],"0.5":[25,0,0],"1.0":[-25,0,0]}},
                "armR":{"rotation":{"0.0":[-20,0,0],"0.5":[20,0,0],"1.0":[-20,0,0]}},
                "armL":{"rotation":{"0.0":[20,0,0],"0.5":[-20,0,0],"1.0":[20,0,0]}}}}}}
            """;

    /** A clip that moves a bone in every channel and on every axis — the general case. */
    private static final String AWKWARD = """
            {"format_version":"1.8.0","animations":{"animation.sculpted.walk":{
              "loop":true,"animation_length":2.0,"bones":{
                "armL":{"rotation":{"0.0":[13,-27,41],"1.0":[-31,17,-9],"2.0":[13,-27,41]},
                        "position":{"0.0":[0,0,0],"1.0":[2,-3,1.5],"2.0":[0,0,0]}},
                "head":{"rotation":{"0.0":[0,-34,0],"1.0":[11,22,-7],"2.0":[0,-34,0]}},
                "body":{"position":{"0.0":[0,0,0],"1.0":[0,1.75,0],"2.0":[0,0,0]}}}}}}
            """;

    private static BedrockAnimation clip(String json) {
        Map<String, BedrockAnimation> all =
                BedrockAnimation.parseAll(JsonParser.parseString(json).getAsJsonObject());
        BedrockAnimation a = BedrockAnimation.find(all, BedrockAnimation.Clip.WALK);
        assertTrue(a != null, "the fixture clip did not parse");
        return a;
    }

    private static List<CreatureSculpt.Part> spikor() {
        return RigTransform.mirrorIntoWorldSpace(SpikeFixture.parts(SpikeFixture.SPIKOR));
    }

    // ------------------------------------------------------- agreeing with the PROVEN static path

    /**
     * <b>At rest, the matrix layer must reproduce the chain the PLANNER already baked — exactly.</b>
     *
     * <p>This is the oracle that replaced a corner probe, and the replacement is the point. The
     * obvious check is "compute where the mod would put each box and compare", and it was written:
     * it reported <b>24 units</b> of error at REST, on a static path that photographs at 0 px. Two
     * guesses later it reported 43. The mod mirrors its whole model at render time while the rig
     * mirrors the parts up front AND reflects the element inside the model, with a half turn on the
     * display entity cancelling it — so a probe comparing LOCAL coordinates has three conventions to
     * get right and no way to tell which one it got wrong. That is the third instrument on this
     * feature to be confidently wrong (§9.6), and it was caught only because the control ran first.
     *
     * <p>So compare against something already proven instead. {@link RigPlan#atoms} bakes each
     * box's ancestor chain from the rest rotations, and that chain is part of the pipeline that is
     * pixel-exact on thirteen fixtures and on Spikor. Feed the matrix layer an EMPTY pose and it
     * must hand back the same numbers. Same coordinate system, no reimplementation of the mod, and a
     * disagreement can only be the layer's.
     *
     * <p>Re-pinned from {@code exactGroups} to the atom plan when the pack rig was deleted:
     * the atom plan is the only one that ships, and it bakes the chain on EVERY box rather than on
     * the split-out ones, so it is the stricter fixture. Pattern overlays are planned off
     * ({@code atoms(parts, false)}) because an overlay is a second display of a bone, not a bone.
     */
    @Test
    void anEmptyPoseReproducesThePlannedRestChainExactly() {
        List<CreatureSculpt.Part> mir = spikor();
        RigPose.Frame frame = RigPose.compose(mir, Map.of());
        int checked = 0;
        for (RigPlan.Group g : RigPlan.atoms(mir, false)) {
            RigPose.Chain c = frame.chains().get(g.name());
            if (c == null) {
                // A SYNTHETIC group -- `root` collects every part that hangs off nothing, and there
                // is no bone by that name. RigPose.withChain leaves such a group on its planned
                // chain rather than snapping it to the origin, which is what would drop that piece
                // of the creature to its feet on the first animated tick.
                assertEquals("root", g.name(), "an unexpected group has no bone of its own name");
                continue;
            }
            for (int i = 0; i < 4; i++) {
                assertEquals(g.chainRotationXyzw()[i], c.rotationXyzw()[i], 1e-5,
                        g.name() + " rest chain rotation component " + i);
            }
            for (int i = 0; i < 3; i++) {
                assertEquals(g.chainTranslation()[i], c.translation()[i], 1e-4,
                        g.name() + " rest chain translation component " + i);
            }
            checked++;
        }
        assertTrue(checked > 30, "only " + checked + " groups checked -- the fixture shrank and "
                + "this test stopped covering the ancestor chains it was written for");
    }

    /**
     * And with a pose, a bone the clip does NOT touch keeps exactly its rest chain.
     *
     * <p>The complement of the test above: recomputing every chain per frame must not perturb the
     * bones the animation has nothing to say about. A layer that drifted them would move parts of a
     * walking creature that should be still, and the only visible symptom is a creature that
     * shimmers.
     */
    @Test
    void aBoneTheClipDoesNotTouchKeepsItsRestChain() {
        List<CreatureSculpt.Part> mir = spikor();
        RigPose.Frame frame = RigPose.compose(mir, clip(WALK).pose(0.2f));
        // head and everything under it: the walk clip names only the legs and arms.
        for (RigPlan.Group g : RigPlan.atoms(mir, false)) {
            if (frame.moved().contains(g.name())) {
                continue;
            }
            RigPose.Chain c = frame.chains().get(g.name());
            if (c == null) {
                continue;
            }
            for (int i = 0; i < 3; i++) {
                assertEquals(g.chainTranslation()[i], c.translation()[i], 1e-4,
                        g.name() + " is not animated but its chain moved");
            }
        }
    }

    /**
     * A bone the clip DOES move must have a chain that differs from its rest one — for its
     * CHILDREN.
     *
     * <p>Without this the two tests above are jointly satisfied by a layer that does nothing at all.
     */
    @Test
    void anAnimatedBoneChangesTheChainItsChildrenInherit() {
        List<CreatureSculpt.Part> mir = spikor();
        // t = 0 is a swing extreme; t = 0.25 is the zero crossing, where armL passes through its
        // own rest angle and legitimately needs no rewrite at all.
        RigPose.Frame frame = RigPose.compose(mir, clip(WALK).pose(0f));
        Map<String, RigPlan.Group> byName = new LinkedHashMap<>();
        for (RigPlan.Group g : RigPlan.atoms(mir, false)) {
            byName.put(g.name(), g);
        }
        RigPlan.Group claw = byName.get("kit_claw40");
        assertTrue(claw != null, "the fixture no longer has the claw this test is about");
        RigPose.Chain animated = frame.chains().get("kit_claw40");
        double moved = 0;
        for (int i = 0; i < 3; i++) {
            moved = Math.max(moved,
                    Math.abs(claw.chainTranslation()[i] - animated.translation()[i]));
        }
        assertTrue(moved > 0.05, "armL is swinging 20 degrees and its claw's inherited chain moved "
                + moved + " units -- if this is zero the arm swings and the claws stay behind");
    }

    // ------------------------------------------------------------------------- the loop oracle

    @Test
    void aWalkCycleReturnsToItsOwnFirstFrame() {
        // No second renderer involved: a loop must close. A composition error accumulates and
        // shows here as drift, which is exactly what a single held pose cannot reveal.
        List<CreatureSculpt.Part> mir = spikor();
        BedrockAnimation a = clip(WALK);
        RigPose.Frame first = RigPose.compose(mir, a.pose(0f));
        RigPose.Frame last = RigPose.compose(mir, a.pose(a.length()));
        assertEquals(first.chains().keySet(), last.chains().keySet());
        for (String bone : first.chains().keySet()) {
            RigPose.Chain x = first.chains().get(bone);
            RigPose.Chain y = last.chains().get(bone);
            for (int i = 0; i < 4; i++) {
                assertEquals(x.rotationXyzw()[i], y.rotationXyzw()[i], 1e-5,
                        bone + " rotation drifted over one cycle");
            }
            for (int i = 0; i < 3; i++) {
                assertEquals(x.translation()[i], y.translation()[i], 1e-4,
                        bone + " translation drifted over one cycle");
            }
        }
    }

    @Test
    void andStillClosesAfterTenCycles() {
        List<CreatureSculpt.Part> mir = spikor();
        BedrockAnimation a = clip(AWKWARD);
        RigPose.Frame first = RigPose.compose(mir, a.pose(0f));
        RigPose.Frame tenth = RigPose.compose(mir, a.pose(a.length() * 10f));
        for (String bone : first.chains().keySet()) {
            for (int i = 0; i < 3; i++) {
                assertEquals(first.chains().get(bone).translation()[i],
                        tenth.chains().get(bone).translation()[i], 1e-3,
                        bone + " drifted over ten cycles");
            }
        }
    }

    // --------------------------------------------------------------- what the layer must NOT do

    @Test
    void aBoneWhoseANCESTORMovedIsReportedAsMoved() {
        // The whole reason this class exists. armL swings; its four claws are separate displays.
        List<CreatureSculpt.Part> mir = spikor();
        RigPose.Frame f = RigPose.compose(mir, clip(WALK).pose(0.2f));
        assertTrue(f.moved().contains("armL"), "armL has a keyframe and must be moved");
        assertTrue(f.moved().contains("kit_claw40"),
                "a claw hangs off armL and must move with it -- if this fails the arm swings "
                        + "and the claws stay behind, which is the bug this layer was written for");
        assertFalse(f.moved().contains("head"), "nothing in the walk clip touches the head");
    }

    @Test
    void anIdleCreatureCostsNothing() {
        RigPose.Frame f = RigPose.compose(spikor(), Map.of());
        assertTrue(f.moved().isEmpty(), "a creature with no pose must rewrite no displays");
    }

    @Test
    void everyBoneAWalkMovesGetsItsOwnDisplay() {
        // legL and legR are both unrotated, so the old grouped planner folded them into one model
        // together -- and a clip that swings them in opposite phase then had nothing to drive.
        // Re-pinned on the atom plan: every box is its own display there, so this now holds
        // by construction, and the test is what says so if a later plan ever groups boxes again.
        List<CreatureSculpt.Part> mir = spikor();
        java.util.Set<String> animated = RigPose.animatedBones(Map.of("walk", clip(WALK)));
        java.util.Set<String> planned = new java.util.LinkedHashSet<>();
        for (RigPlan.Group g : RigPlan.atoms(mir)) {
            planned.add(g.name());
        }
        for (String bone : animated) {
            assertTrue(planned.contains(bone), bone + " is animated but has no display of its own");
        }
    }

    @Test
    void aScaleChannelIsREFUSEDRatherThanQuietlyDropped() {
        // Section 9.2: a non-unit scale on a display makes its transform non-orthonormal and
        // vanilla shades that bone's side faces differently. Applying it would animate the
        // creature and change its colour; dropping it silently would read as a dead channel.
        String squash = """
                {"format_version":"1.8.0","animations":{"animation.sculpted.walk":{
                  "loop":true,"animation_length":1.0,"bones":{
                    "body":{"scale":{"0.0":[1,1,1],"0.5":[1.3,0.7,1.3],"1.0":[1,1,1]}}}}}}
                """;
        RigPose.Frame f = RigPose.compose(spikor(), clip(squash).pose(0.5f));
        assertTrue(f.scaledBones().contains("body"),
                "a scale channel must be NAMED, so nobody has to guess why the squash did nothing");
    }
}
