package com.moddingfromamod.vanilla;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b> — "a copy of me" whose hair turned the opposite way to its face.</b>
 *
 * <p>Two rules, one test each, and the oracle is the one the issue names: <b>the hair stays on the
 * same side of the face as it does at rest</b>. In the head's own frame the face is in front and
 * the hair behind; a head turn that is applied to both keeps them there, a turn applied to only one
 * swings the face round to the hair's side. Measured on where the rig actually puts each display
 * (the same {@code place} + {@code atomise} + chain composer {@code RigDriver.lookAt} uses), not on
 * a model of it.
 *
 * <p>⚠ The oracle is FALSE of the bug, deliberately, and the first test proves it: on the sculpt as
 * the agent sent it (hair a root) the same measurement fails. "The head turned" and "the face
 * moved" are both true of the bug and are not controls (VANILLA_RIG_ANIMATION_LADDER's playbook).
 */
class HairFollowsHeadTest {

    /**
     * The shape the filmed frames show: a head whose eyes and mouth are its children, and a big
     * hair box over its top and back that is NOT — the one case every committed sculpt happened to
     * get right (18 of 18 parent hair under {@code head}) and a live one did not.
     */
    static final String COPY_OF_ME = """
            [{"name":"legL","from":[-4,0,-2],"to":[0,12,2],"colour":"#2B3F8C"},
             {"name":"legR","from":[0,0,-2],"to":[4,12,2],"colour":"#2B3F8C"},
             {"name":"body","from":[-4,12,-2],"to":[4,24,2],"colour":"#2B3F8C"},
             {"name":"armL","from":[-8,12,-2],"to":[-4,24,2],"colour":"#E8A08C"},
             {"name":"armR","from":[4,12,-2],"to":[8,24,2],"colour":"#E8A08C"},
             {"name":"head","from":[-4,24,-4],"to":[4,32,4],"colour":"#E8A08C"},
             {"name":"eyeL","parent":"head","from":[-3,28,-5],"to":[-1,30,-4],"colour":"#101010"},
             {"name":"eyeR","parent":"head","from":[1,28,-5],"to":[3,30,-4],"colour":"#101010"},
             {"name":"mouth","parent":"head","from":[-2,25,-5],"to":[2,26,-4],"colour":"#D02020"},
             {"name":"hair","from":[-5,27,-2],"to":[5,34,5],"colour":"#6B3A1E"}]""";

    static List<CreatureSculpt.Part> asSent() {
        return CreatureSculpt.parseParts(JsonParser.parseString(COPY_OF_ME).getAsJsonArray());
    }

    /** The creature the sculpt tool actually saves — every assembly step, kits or not. */
    static List<CreatureSculpt.Part> asSaved() {
        return SculptCreatureTool.withKits(asSent(), new JsonObject());
    }

    @Test
    @DisplayName("control: on the sculpt as SENT, a head turn swings the face round to the hair's side")
    void theInstrumentSeesTheBug() {
        for (float yaw : new float[]{75f, 120f, 180f}) {
            float drift = hairDriftAgainstFace(asSent(), yaw);
            assertTrue(Math.abs(drift) > 30f,
                    "the instrument must SEE the bug: with the hair a root a " + yaw + "-degree "
                            + "turn should leave it behind the face's turn, but they differed by "
                            + drift + " degrees");
        }
        assertFalse(hairStaysBehindTheFace(asSent(), 120f),
                "at 120 degrees the face of an unparented head is on the hair's side -- the "
                        + "filmed frame");
    }

    @Test
    @DisplayName("the hair turns WITH the face, and stays behind it, at every head angle")
    void theHairFollowsTheHead() {
        for (float yaw : new float[]{-75f, -35f, 20f, 35f, 55f, 75f}) {
            float drift = hairDriftAgainstFace(asSaved(), yaw);
            assertEquals(0f, drift, 0.5f,
                    "at " + yaw + " degrees the hair turned " + drift + " degrees away from the "
                            + "face's turn -- hair and face are on different transforms");
            assertTrue(hairStaysBehindTheFace(asSaved(), yaw),
                    "at " + yaw + " degrees the hair is no longer behind the face");
        }
    }

    @Test
    @DisplayName("adopting the hair changes NOTHING at rest -- the creature looks exactly as sculpted")
    void adoptionIsInvisibleAtRest() {
        List<Vector3f> before = restCentres(asSent());
        List<Vector3f> after = restCentres(asSaved());
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertTrue(before.get(i).distance(after.get(i)) < 1e-5f,
                    "display " + i + " moved at rest: " + before.get(i) + " -> " + after.get(i));
        }
    }

    // ------------------------------------------------------------------------------ instrument

    /**
     * How many degrees the hair's horizontal offset from the head turned MINUS how many the
     * face's did, for a head turn of {@code yawDeg}. Zero when they move as one.
     */
    private static float hairDriftAgainstFace(List<CreatureSculpt.Part> parts, float yawDeg) {
        Look rest = look(parts, 0f);
        Look turned = look(parts, yawDeg);
        float face = signedAngle(rest.face, turned.face);
        float hair = signedAngle(rest.hair, turned.hair);
        return RigHeadYaw.wrap(hair - face);
    }

    /** Is the hair still on the far side of the head from the face (horizontally)? */
    private static boolean hairStaysBehindTheFace(List<CreatureSculpt.Part> parts, float yawDeg) {
        Look l = look(parts, yawDeg);
        return l.face.x * l.hair.x + l.face.y * l.hair.y < 0f;
    }

    /** Face and hair offsets from the head's centre, horizontal plane only (x, z). */
    private record Look(org.joml.Vector2f face, org.joml.Vector2f hair) {
    }

    private static Look look(List<CreatureSculpt.Part> parts, float yawDeg) {
        List<CreatureSculpt.Part> mirrored = RigTransform.mirrorIntoWorldSpace(parts);
        List<RigPlan.Group> atoms = RigPlan.atoms(mirrored, false);
        float[] head = {yawDeg, 0f};
        RigPose.Frame frame = RigPose.compose(mirrored, null, head);
        Vector3f headC = null;
        Vector3f hairC = null;
        Vector3f face = new Vector3f();
        int faceN = 0;
        for (RigPlan.Group g : atoms) {
            // Exactly the expression RigDriver.lookAt writes a display with.
            Vector3f c = RigTransform.placeAtom(RigPose.withChain(g, frame), 1.0,
                    g.restRotation(), null, null, null,
                    RigPlan.isHead(g) ? head : null).translation();
            switch (g.boneName()) {
                case "head" -> headC = c;
                case "hair" -> hairC = c;
                case "eyeL", "eyeR", "mouth" -> {
                    face.add(c);
                    faceN++;
                }
                default -> { }
            }
        }
        assertTrue(headC != null && hairC != null && faceN == 3, "fixture lost a part");
        face.mul(1f / faceN);
        return new Look(new org.joml.Vector2f(face.x - headC.x, face.z - headC.z),
                new org.joml.Vector2f(hairC.x - headC.x, hairC.z - headC.z));
    }

    private static List<Vector3f> restCentres(List<CreatureSculpt.Part> parts) {
        List<CreatureSculpt.Part> mirrored = RigTransform.mirrorIntoWorldSpace(parts);
        RigPose.Frame frame = RigPose.compose(mirrored, null, null);
        List<Vector3f> out = new java.util.ArrayList<>();
        for (RigPlan.Group g : RigPlan.atoms(mirrored, false)) {
            out.add(RigTransform.placeAtom(RigPose.withChain(g, frame), 1.0, g.restRotation())
                    .translation());
        }
        return out;
    }

    private static float signedAngle(org.joml.Vector2f a, org.joml.Vector2f b) {
        double d = Math.toDegrees(Math.atan2(b.y, b.x) - Math.atan2(a.y, a.x));
        return RigHeadYaw.wrap((float) d);
    }
}
