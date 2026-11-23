package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptOrientation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * <b>Fly, swim, hop, sit, lie and sleep, measured on the geometry they produce</b> (
 *).
 *
 * <p>Every check here is on the BOXES as the rig would draw them ({@link RigLocomotion#corners},
 * the same composition {@link RigPose} and {@link RigTransform#place} use), never on the angles the
 * generator chose — an angle with the wrong sign is a perfectly good number. So "it sits" is the
 * rump on the ground and the front feet on the ground, "it flaps" is the wing TIP going up and
 * down while the shoulder stays put, "it swims" is the tail tip sweeping while every segment stays
 * joined to the last.
 *
 * <p>The fixtures are the starter library's real sculpts, as the rig is handed them (kits expanded,
 * faced forward, mirrored and mass-centred).
 */
class RigLocomotionTest {

    static List<CreatureSculpt.Part> rig(String name) {
        return RigFit.intoRigSpace(SculptOrientation.faceForward(RigSeparationTest.load(name)));
    }

    static RigLocomotion.Plan plan(String name) {
        return RigLocomotion.plan(RigAnatomy.of(rig(name)));
    }

    private static Map<String, float[][]> drawn(RigLocomotion.Plan p, RigLocomotion.Out o) {
        return RigLocomotion.corners(p.anatomy().parts(), o.pose(), o.root());
    }

    private static float[] box(Map<String, float[][]> c, String bone) {
        return RigAnatomy.bounds(c.get(bone));
    }

    private static float lowest(Map<String, float[][]> c) {
        float m = Float.MAX_VALUE;
        for (float[][] cs : c.values()) {
            for (float[] v : cs) {
                m = Math.min(m, v[1]);
            }
        }
        return m;
    }

    private static RigLocomotion.Weights w(float fly, float swim, float sit, float lie, float sleep) {
        return new RigLocomotion.Weights(fly, swim, sit, lie, sleep, 0, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------- reading the anatomy

    @Test
    void theCatsHindLegsAreItsARMSBecauseFrontOrHindIsAPositionNotAName() {
        RigAnatomy a = RigAnatomy.of(rig("cat"));
        assertTrue(a.quadruped(), "the cat should stand on four legs: " + a.legs());
        for (RigAnatomy.Leg l : a.legs()) {
            boolean named = l.bone().startsWith("arm") || l.bone().endsWith("2");
            assertEquals(!named, l.front(), l.bone() + " is on the wrong end");
        }
        assertEquals(-1, a.front(), "a stored sculpt faces -Z");
    }

    @Test
    void theDragonHasThreeWingPanelsASideHingedAtTheBodyAndAFourPartTail() {
        RigAnatomy a = RigAnatomy.of(rig("pet_dragon"));
        assertEquals(6, a.wings().size(), "wings: " + a.wings());
        assertEquals(4, a.tails().size(), "tail: " + a.tails());
        for (RigAnatomy.Wing wg : a.wings()) {
            RigAnatomy.Wing first = a.wings().stream()
                    .filter(x -> x.side() == wg.side() && x.rank() == 0).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.equals(first.hinge(), wg.hinge()),
                    "every panel of a side turns about ONE shoulder");
        }
        assertTrue(a.quadruped());
    }

    @Test
    void aBipedsArmsAreNotLegsAndItsHangingWingsHingeAtTheTop() {
        RigAnatomy a = RigAnatomy.of(rig("chicken"));
        assertFalse(a.quadruped(), "a chicken stands on two legs: " + a.legs());
        assertEquals(2, a.legs().size());
        assertEquals(2, a.wings().size());
        for (RigAnatomy.Wing wg : a.wings()) {
            assertTrue(wg.hanging(), wg.bone() + " hangs at its side");
        }
    }

    // ------------------------------------------------------------------- the differential oracle

    @Test
    void noLayerIsExactlyTheClipItWasGiven() {
        RigLocomotion.Plan p = plan("pet_dragon");
        Map<String, BedrockAnimation.BonePose> base = new HashMap<>();
        base.put("legL", new BedrockAnimation.BonePose(new float[]{25, 0, 0}, new float[]{0, 0, 0},
                new float[]{1, 1, 1}));
        RigLocomotion.Out o = RigLocomotion.pose(p, base, RigLocomotion.Weights.NONE, 0f);
        assertNull(o.root(), "nothing on means the body stays where it stands");
        assertEquals(1f, o.lookKeep());
        BedrockAnimation.BonePose leg = o.pose().get("legL");
        assertEquals(25f, leg.rotation()[0], 1e-4);
        // the resting wing twitch is the ONLY generated motion, and it is tiny
        float[][] restTip = RigLocomotion.corners(p.anatomy().parts(), Map.of(), null).get("kit_wing2");
        float[][] tip = drawn(p, o).get("kit_wing2");
        for (int i = 0; i < 8; i++) {
            for (int ax = 0; ax < 3; ax++) {
                assertEquals(restTip[i][ax], tip[i][ax], 1.5f, "a grounded wing barely moves");
            }
        }
    }

    @Test
    void aMoveOnTopOfAClipIsTheRigidMoveAppliedToTheClipsBoneExactly() {
        CreatureSculpt.Part part = new CreatureSculpt.Part("armL", null, new float[]{3, 9, -1},
                new float[]{2, 2, -2}, new float[]{5, 9, 1}, 0xffffff, new float[]{10, -20, 5}, 0f,
                "", 0);
        BedrockAnimation.BonePose clip = new BedrockAnimation.BonePose(new float[]{13, -27, 41},
                new float[]{2, -3, 1.5f}, new float[]{1, 1, 1});
        Quaternionf q = RigTransform.rotation(new float[]{-31, 17, -9});
        Vector3f t = new Vector3f(1.25f, -0.5f, 3f);
        BedrockAnimation.BonePose both = RigLocomotion.onTop(part, clip, q, t);
        float[][] clipOnly = RigLocomotion.corners(List.of(part), Map.of("armL", clip), null)
                .get("armL");
        float[][] composed = RigLocomotion.corners(List.of(part), Map.of("armL", both), null)
                .get("armL");
        // 5e-3 model units (a hundredth of a pixel): 1.21.1's older JOML extracts the Euler angles
        // a little less precisely, measured 1.5e-3 there and under 1e-3 on 1.21.4.
        for (int i = 0; i < 8; i++) {
            Vector3f want = new Vector3f(clipOnly[i][0], clipOnly[i][1], clipOnly[i][2])
                    .rotate(q).add(t);
            assertEquals(want.x, composed[i][0], 5e-3);
            assertEquals(want.y, composed[i][1], 5e-3);
            assertEquals(want.z, composed[i][2], 5e-3);
        }
    }

    // ------------------------------------------------------------------- FLY

    @Test
    void inTheAirTheWingTipsBeatAboutAShoulderThatStaysPut() {
        RigLocomotion.Plan p = plan("pet_dragon");
        RigAnatomy.Wing inner = p.anatomy().wings().stream()
                .filter(x -> x.bone().equals("kit_wing0")).findFirst().orElseThrow();
        float hi = -Float.MAX_VALUE;
        float lo = Float.MAX_VALUE;
        float hiR = -Float.MAX_VALUE;
        float loR = Float.MAX_VALUE;
        for (int t = 0; t < RigLocomotion.FLAP_TICKS; t++) {
            RigLocomotion.Out o = RigLocomotion.pose(p, Map.of(), w(1, 0, 0, 0, 0), t);
            Map<String, float[][]> c = drawn(p, o);
            float tip = box(c, "kit_wing2")[4];
            hi = Math.max(hi, tip);
            lo = Math.min(lo, tip);
            float tipR = box(c, "kit_wing5")[4];
            hiR = Math.max(hiR, tipR);
            loR = Math.min(loR, tipR);
            // the shoulder: the inner panel's corner nearest the hinge, relative to the body
            float[][] in = c.get("kit_wing0");
            float near = Float.MAX_VALUE;
            float[] bodyRest = box(RigLocomotion.corners(p.anatomy().parts(), Map.of(), null), "body");
            float[] body = box(c, "body");
            for (float[] v : in) {
                float dx = v[0] - inner.hinge()[0];
                float dy = (v[1] - (body[1] - bodyRest[1])) - inner.hinge()[1];
                near = Math.min(near, (float) Math.hypot(dx, dy));
            }
            assertTrue(near < 3f, "the wing came off its shoulder at t=" + t + ": " + near);
        }
        assertTrue(hi - lo > 8f, "a beat should move the tip a long way: " + (hi - lo));
        assertEquals(hi - lo, hiR - loR, 0.5f, "both wings beat alike");
    }

    @Test
    void aGroundedWingTwitchesAtATenthOfTheBeatAtMost() {
        RigLocomotion.Plan p = plan("pet_dragon");
        float air = sweep(p, 1f);
        float ground = sweep(p, 0f);
        assertTrue(ground * 10 < air, "grounded " + ground + " against airborne " + air);
    }

    private static float sweep(RigLocomotion.Plan p, float fly) {
        float hi = -Float.MAX_VALUE;
        float lo = Float.MAX_VALUE;
        for (int t = 0; t < RigLocomotion.REST_WING_TICKS; t++) {
            float tip = box(drawn(p, RigLocomotion.pose(p, Map.of(), w(fly, 0, 0, 0, 0), t)),
                    "kit_wing2")[4];
            hi = Math.max(hi, tip);
            lo = Math.min(lo, tip);
        }
        return hi - lo;
    }

    @Test
    void theBeatReturnsToItsOwnFirstFrame() {
        RigLocomotion.Plan p = plan("pet_dragon");
        Map<String, float[][]> a = drawn(p, RigLocomotion.pose(p, Map.of(), w(1, 0, 0, 0, 0), 0));
        Map<String, float[][]> b = drawn(p, RigLocomotion.pose(p, Map.of(), w(1, 0, 0, 0, 0),
                RigLocomotion.FLAP_TICKS * 10));
        for (String bone : a.keySet()) {
            for (int i = 0; i < 8; i++) {
                for (int ax = 0; ax < 3; ax++) {
                    assertEquals(a.get(bone)[i][ax], b.get(bone)[i][ax], 1e-3, bone);
                }
            }
        }
    }

    // ------------------------------------------------------------------- SWIM

    @Test
    void inWaterTheTailSweepsAndEverySegmentStaysJoinedToTheLast() {
        RigLocomotion.Plan p = plan("pet_dragon");
        List<RigAnatomy.Tail> tail = p.anatomy().tails();
        String tip = tail.get(tail.size() - 1).bone();
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        Map<String, float[][]> rest = RigLocomotion.corners(p.anatomy().parts(), Map.of(), null);
        for (int t = 0; t < RigLocomotion.SWIM_TICKS; t++) {
            Map<String, float[][]> c = drawn(p, RigLocomotion.pose(p, Map.of(), w(0, 1, 0, 0, 0), t));
            float[] b = box(c, tip);
            left = Math.min(left, (b[0] + b[3]) / 2);
            right = Math.max(right, (b[0] + b[3]) / 2);
            for (int i = 1; i < tail.size(); i++) {
                // the gap between neighbouring segments' centres keeps its rest length
                float dRest = dist(centre(box(rest, tail.get(i - 1).bone())),
                        centre(box(rest, tail.get(i).bone())));
                float dNow = dist(centre(box(c, tail.get(i - 1).bone())),
                        centre(box(c, tail.get(i).bone())));
                assertEquals(dRest, dNow, 0.6f, "segment " + i + " came apart at t=" + t);
            }
        }
        assertTrue(right - left > 3f, "the tail tip should sweep: " + (right - left));
    }

    @Test
    void swimmingUpwardsPitchesTheNoseUp() {
        RigLocomotion.Plan p = plan("pet_dragon");
        RigLocomotion.Out up = RigLocomotion.pose(p, Map.of(),
                new RigLocomotion.Weights(0, 1, 0, 0, 0, 0, 0, 0, 30f, 1f), 0);
        Map<String, float[][]> c = drawn(p, up);
        Map<String, float[][]> rest = RigLocomotion.corners(p.anatomy().parts(), Map.of(), null);
        float headRise = centre(box(c, "head"))[1] - centre(box(rest, "head"))[1];
        String last = p.anatomy().tails().get(p.anatomy().tails().size() - 1).bone();
        float tailRise = centre(box(c, last))[1] - centre(box(rest, last))[1];
        assertTrue(headRise > 2f && tailRise < -2f, "head " + headRise + " tail " + tailRise);
    }

    // ------------------------------------------------------------------- JUMP

    @Test
    void aJumpStartsAndEndsWithTheLegsWhereTheyHangAndReachesInTheMiddle() {
        RigLocomotion.Plan p = plan("cat");
        String hind = p.anatomy().legs().stream().filter(l -> !l.front()).findFirst()
                .orElseThrow().bone();
        Map<String, float[][]> rest = RigLocomotion.corners(p.anatomy().parts(), Map.of(), null);
        for (float pr : new float[]{0f, 1f}) {
            Map<String, BedrockAnimation.BonePose> pose = RigLocomotion.pose(p, Map.of(),
                    new RigLocomotion.Weights(0, 0, 0, 0, 0, 0, 1, pr, 0, 0), 0).pose();
            BedrockAnimation.BonePose leg = pose.get(hind);
            assertEquals(0f, leg.rotation()[0], 1e-3, "legs at rest at progress " + pr);
        }
        Map<String, float[][]> mid = drawn(p, RigLocomotion.pose(p, Map.of(),
                new RigLocomotion.Weights(0, 0, 0, 0, 0, 0, 1, 0.5f, 0, 0), 0));
        float behind = -p.anatomy().front()
                * (centre(box(mid, hind))[2] - centre(box(rest, hind))[2]);
        assertTrue(behind > 1f, "the hind legs push back at the top: " + behind);
    }

    // ------------------------------------------------------------------- SIT / LIE / SLEEP

    @Test
    void aSittingQuadrupedHasItsRumpAndItsFrontFeetOnTheGround() {
        for (String name : new String[]{"cat", "wolf", "pet_dragon", "turtle"}) {
            RigLocomotion.Plan p = plan(name);
            RigLocomotion.Out o = RigLocomotion.pose(p, Map.of(), w(0, 0, 1, 0, 0), 0);
            Map<String, float[][]> c = drawn(p, o);
            float ground = p.anatomy().groundY();
            assertTrue(lowest(c) > ground - 1.2f, name + " sits through the floor: " + lowest(c));
            float front = Float.MAX_VALUE;
            float hind = Float.MAX_VALUE;
            for (RigAnatomy.Leg l : p.anatomy().legs()) {
                float y = box(c, l.bone())[1];
                if (l.front()) {
                    front = Math.min(front, y);
                } else {
                    hind = Math.min(hind, y);
                }
            }
            assertEquals(ground, front, 1.2f, name + "'s front feet should be on the ground");
            Map<String, float[][]> rest = RigLocomotion.corners(p.anatomy().parts(), Map.of(), null);
            float rumpDrop = box(rest, p.anatomy().body())[1] - box(c, p.anatomy().body())[1];
            assertTrue(rumpDrop > 1f, name + " should have sat down: " + rumpDrop);
            assertTrue(p.sitPitch() > 4f, name + " sits up at the front: " + p.sitPitch());
        }
    }

    @Test
    void sittingDownIsMonotonicNeverASnap() {
        RigLocomotion.Plan p = plan("wolf");
        float last = Float.MAX_VALUE;
        for (int i = 0; i <= 10; i++) {
            Map<String, float[][]> c = drawn(p,
                    RigLocomotion.pose(p, Map.of(), w(0, 0, i / 10f, 0, 0), 0));
            float body = box(c, "body")[1];
            assertTrue(body <= last + 1e-4, "the rump went UP at weight " + i / 10f);
            if (i > 0 && i < 10) {
                assertTrue(last - body < 2.5f, "a step of " + (last - body) + " is a snap");
            }
            last = body;
        }
    }

    /**
     * The owner saw a held sit TWITCH. The pose was steady; the standing idle clip was still
     * running on top of it, bobbing the body against its root-bone legs and tail. A resting
     * creature holds still: with the idle clip playing, not one box moves between writes once it
     * is down, while the same creature standing does (the control, so the instrument can see it).
     */
    @Test
    void aHeldRestPoseDoesNotPlayTheStandingIdle() throws Exception {
        RigLocomotion.Plan p = plan("cat");
        Map<String, BedrockAnimation> clips = BedrockAnimation.parseAll(
                com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                        java.nio.file.Path.of("src/main/resources/starter-library/species/cat/"
                                + "animations.json"))).getAsJsonObject());
        float standing = idleMotion(p, clips, w(0, 0, 0, 0, 0));
        assertTrue(standing > 0.02f, "the control: a standing cat's idle should move: " + standing);
        for (RigLocomotion.Weights rest : List.of(w(0, 0, 1, 0, 0), w(0, 0, 0, 1, 0))) {
            assertEquals(0f, idleMotion(p, clips, rest), 1e-5,
                    "a held rest pose moved between writes: " + rest);
        }
    }

    /** The largest corner move between consecutive pose writes (every 3 ticks) over 6 seconds. */
    private static float idleMotion(RigLocomotion.Plan p, Map<String, BedrockAnimation> clips,
                                    RigLocomotion.Weights w) {
        Map<String, float[][]> prev = null;
        float worst = 0f;
        for (int t = 0; t < 120; t += 3) {
            var base = com.moddingfromamod.runtime.AnimationState.pose(clips,
                    com.moddingfromamod.runtime.AnimationState.select(false, 0, 0, 0, t / 20f));
            Map<String, float[][]> c = drawn(p, RigLocomotion.pose(p, base, w, t));
            if (prev != null) {
                for (var e : c.entrySet()) {
                    for (int i = 0; i < 8; i++) {
                        worst = Math.max(worst, dist(e.getValue()[i], prev.get(e.getKey())[i]));
                    }
                }
            }
            prev = c;
        }
        return worst;
    }

    @Test
    void lyingDownPutsTheBellyOnTheGround() {
        for (String name : new String[]{"cat", "wolf", "pet_dragon"}) {
            RigLocomotion.Plan p = plan(name);
            Map<String, float[][]> c = drawn(p, RigLocomotion.pose(p, Map.of(), w(0, 0, 0, 1, 0), 0));
            float belly = box(c, p.anatomy().body())[1];
            // Legs folded flat lie beside and under it, so the belly is up by at most a leg's
            // thickness -- never by a leg's LENGTH, which is standing.
            float thick = 0f;
            for (RigAnatomy.Leg l : p.anatomy().legs()) {
                CreatureSculpt.Part part = p.anatomy().parts().stream()
                        .filter(x -> x.name().equals(l.bone())).findFirst().orElseThrow();
                thick = Math.max(thick, Math.min(part.to()[0] - part.from()[0],
                        part.to()[2] - part.from()[2]));
            }
            assertTrue(belly - p.anatomy().groundY() <= thick + 0.5f,
                    name + " lies on its belly: " + belly + " with legs " + thick + " thick");
            assertTrue(lowest(c) > p.anatomy().groundY() - 1.5f, name + " lies through the floor");
        }
    }

    @Test
    void asleepItRollsOnItsSideShutsItsEyesAndStopsLooking() {
        RigLocomotion.Plan p = plan("cat");
        RigLocomotion.Out o = RigLocomotion.pose(p, Map.of(), w(0, 0, 0, 0, 1), 0);
        assertEquals(0f, o.lookKeep(), "a sleeping head does not follow anyone");
        Map<String, float[][]> c = drawn(p, o);
        assertTrue(lowest(c) > p.anatomy().groundY() - 1.2f, "asleep through the floor");
        float[] body = box(c, "body");
        float[] bodyRest = box(RigLocomotion.corners(p.anatomy().parts(), Map.of(), null), "body");
        // on its side: the body's width is now its height
        assertEquals(bodyRest[3] - bodyRest[0], body[4] - body[1], 2.5f);
        assertFalse(p.anatomy().eyes().isEmpty());
        float[] head = box(c, "head");
        for (RigAnatomy.Eye e : p.anatomy().eyes()) {
            float[] eye = centre(box(c, e.bone()));
            for (int ax = 0; ax < 3; ax++) {
                assertTrue(eye[ax] >= head[ax] - 0.01f && eye[ax] <= head[ax + 3] + 0.01f,
                        e.bone() + " is still outside the head");
            }
        }
    }

    @Test
    void aBipedSitsOnItsSeatWithItsLegsOutInFront() {
        RigLocomotion.Plan p = plan("chicken");
        Map<String, float[][]> c = drawn(p, RigLocomotion.pose(p, Map.of(), w(0, 0, 1, 0, 0), 0));
        assertTrue(lowest(c) > p.anatomy().groundY() - 1.2f, "sat through the floor");
        assertTrue(p.sitDrop() > 1f, "a chicken should come down: " + p.sitDrop());
    }

    private static float[] centre(float[] b) {
        return new float[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
    }

    private static float dist(float[] a, float[] b) {
        return (float) Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1])
                + (a[2] - b[2]) * (a[2] - b[2]));
    }
}
