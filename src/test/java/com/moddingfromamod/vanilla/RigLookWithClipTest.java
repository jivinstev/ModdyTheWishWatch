package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

/**
 * <b> — a walking creature must not come apart: the head's look COMPOSES with the clip, on the
 * clip's own write, and every display eases into a move the same way.</b>
 *
 * <p>The owner filmed the starter pet dragon's head sliding against its body while it walked. Two
 * causes were traced, and this file pins both on the real creature (the starter dragon, read from
 * the bundled library) without a world:
 *
 * <ol>
 *   <li>the look was applied by a second writer that composed with a NULL pose, so the head subtree
 *       became rest + look and the clip's own head keys were thrown away &mdash; the tests below ask
 *       the composer for the head's chain with the clip AND the look and check it is
 *       {@code R(look)·R(clip)·R(rest)}, not {@code R(look)·R(rest)};</li>
 *   <li>{@code teleport_duration} was written per display as the transform tween, so displays that
 *       had been posed eased into each move over 3 ticks and the rest snapped &mdash; pinned by
 *       reading the source, because the claim is about who may write that field.</li>
 * </ol>
 */
class RigLookWithClipTest {

    private static JsonObject starter(String file) {
        String at = "/starter-library/species/pet_dragon/" + file;
        try (InputStream in = RigLookWithClipTest.class.getResourceAsStream(at)) {
            assertTrue(in != null, at + " is not on the test classpath");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<CreatureSculpt.Part> dragon() {
        return RigTransform.mirrorIntoWorldSpace(
                CreatureSculpt.parseParts(starter("sculpt.json").getAsJsonArray("parts")));
    }

    private static Map<String, BedrockAnimation> clips() {
        return BedrockAnimation.parseAll(starter("animations.json"));
    }

    /** The dragon's idle clip at a time where it keys the head (its sway) to something non-zero. */
    private static Map<String, BedrockAnimation.BonePose> idleWithHeadSway() {
        BedrockAnimation idle = BedrockAnimation.find(clips(), BedrockAnimation.Clip.IDLE);
        assertTrue(idle != null, "the starter dragon has no idle clip");
        for (float t = 0.1f; t < idle.length(); t += 0.1f) {
            Map<String, BedrockAnimation.BonePose> pose = idle.pose(t);
            BedrockAnimation.BonePose head = pose.get("head");
            if (head != null && head.rotation() != null
                    && (Math.abs(head.rotation()[0]) + Math.abs(head.rotation()[1])
                    + Math.abs(head.rotation()[2])) > 1f) {
                return pose;
            }
        }
        throw new AssertionError("the dragon's idle clip never sways its head, so this fixture "
                + "cannot tell a composed head from a replaced one");
    }

    private static Quaternionf q(float[] xyzw) {
        return new Quaternionf(xyzw[0], xyzw[1], xyzw[2], xyzw[3]);
    }

    private static CreatureSculpt.Part part(List<CreatureSculpt.Part> parts, String name) {
        return parts.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aLookComposesWithTheClipsHeadKeysRatherThanReplacingThem() {
        List<CreatureSculpt.Part> dragon = dragon();
        Map<String, BedrockAnimation.BonePose> pose = idleWithHeadSway();
        float[] look = {35f, -10f};

        // The head is a ROOT bone on this creature, so the chain handed to its children IS the
        // head's own accumulated rotation -- the one thing that differs between the two answers.
        RigPose.Frame both = RigPose.compose(dragon, pose, look, true);
        RigPose.Frame lookOnly = RigPose.compose(dragon, null, look, true);
        Quaternionf got = q(both.chains().get("snout").rotationXyzw());

        Quaternionf want = RigTransform.headRotation(look)
                .mul(RigTransform.rotation(pose.get("head").rotation()))
                .mul(RigTransform.rotation(part(dragon, "head").rotation()));
        assertEquals(0f, 1f - Math.abs(got.dot(want)), 1e-5,
                "the head's children are not placed at R(look)·R(clip)·R(rest) -- head-look "
                        + "is not composing with the clip");
        assertTrue(1f - Math.abs(got.dot(q(lookOnly.chains().get("snout").rotationXyzw()))) > 1e-6,
                "composing with the clip gave the same head as composing without it, so this "
                        + "test cannot see the #765 bug it was written for");
    }

    @Test
    void aHeldLookCostsNothingButStillTurnsWhatTheClipWrites() {
        List<CreatureSculpt.Part> dragon = dragon();
        float[] look = {35f, -10f};
        // Held (unchanged since the last write) and no clip: nothing to write.
        assertTrue(RigPose.compose(dragon, Map.of(), look, false).moved().isEmpty(),
                "a creature holding a look repays its head subtree every write cycle");
        // Changed: the whole head subtree, and nothing else.
        RigPose.Frame turned = RigPose.compose(dragon, Map.of(), look, true);
        assertTrue(turned.moved().containsAll(List.of("head", "snout", "eyeL", "kit_horn30")),
                "a look that changed did not move the head subtree: " + turned.moved());
        assertFalse(turned.moved().contains("body"), "a look moved the body");
        // Back to straight ahead is a change too, or the head stays turned forever.
        assertTrue(RigPose.compose(dragon, Map.of(), new float[]{0f, 0f}, true).moved()
                        .contains("head"),
                "a look returning to (0,0) is not written, so the head never comes back");
        // And a held look still turns every head bone the CLIP writes this frame.
        RigPose.Frame held = RigPose.compose(dragon, idleWithHeadSway(), look, false);
        RigPose.Frame noLook = RigPose.compose(dragon, idleWithHeadSway(), null, false);
        assertTrue(held.moved().contains("snout"), "the idle clip moves the head subtree");
        assertTrue(1f - Math.abs(q(held.chains().get("snout").rotationXyzw())
                        .dot(q(noLook.chains().get("snout").rotationXyzw()))) > 1e-6,
                "a held look was dropped from the clip's own head write");
    }

    @Test
    void posedIsTheClipsBonesAndNotTheLooks() {
        List<CreatureSculpt.Part> dragon = dragon();
        BedrockAnimation walk = BedrockAnimation.find(clips(), BedrockAnimation.Clip.WALK);
        RigPose.Frame f = RigPose.compose(dragon, walk.pose(0.1f), new float[]{35f, 0f}, true);
        assertTrue(f.posed().containsAll(List.of("legL", "legR")),
                "the clip's bones are not reported as posed: " + f.posed());
        // no standard clip moves the body any more (it lifted off its own legs), so the
        // body and the wings hung from it hold still under a walk.
        assertFalse(f.posed().contains("body") || f.posed().contains("kit_wing0"),
                "the walk moved the body again: " + f.posed());
        assertFalse(f.posed().contains("head"),
                "a bone moved only by the look is reported as posed, so the driver would rewrite "
                        + "the head once more after every look for nothing");
        assertTrue(f.moved().containsAll(f.posed()), "posed must be a subset of moved");
    }

    // ────────────────────────────────────────────────────────── who may write what (source pins)

    private static String source(String repoRelative) {
        try {
            return java.nio.file.Files.readString(SpikeFixture.find(repoRelative));
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + repoRelative, e);
        }
    }

    private static String method(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue(at >= 0, "'" + signature + "' has been renamed; this pin means nothing now");
        int open = src.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            if (src.charAt(i) == '{') {
                depth++;
            } else if (src.charAt(i) == '}' && --depth == 0) {
                return src.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    @Test
    void teleportDurationIsOneRigWideValueThatNoPoseWriteTouches() {
        String rig = source("src/main/java/com/moddingfromamod/vanilla/DisplayRig.java");
        String apply = method(rig, "public static void apply(Entity display");
        assertFalse(apply.contains("putInt(\"teleport_duration\""),
                "DisplayRig.apply writes teleport_duration again -- a display's POSITION "
                        + "smoothing then depends on whether it has been posed, and a walking rig "
                        + "comes apart (#765)");
        int writes = rig.split("putInt\\(\"teleport_duration\"", -1).length - 1;
        assertEquals(1, writes, "teleport_duration must have exactly ONE writer (smoothMotion), "
                + "found " + writes);
        assertTrue(method(rig, "private DisplayRig(Mob base").contains("smoothMotion("),
                "the rig no longer smooths every display where both spawn and adopt pass");
    }

    @Test
    void theShippingDriverHasOnePoseWriterPerTick() {
        String anim = source("src/main/java/com/moddingfromamod/vanilla/VanillaRigAnimator.java");
        String drive = method(anim, "private static void driveOne(");
        assertFalse(drive.contains("RigDriver.lookAt("),
                "driveOne calls lookAt again: a second writer, on its own tick and its own tween "
                        + "clock, over the head the clip just wrote (#765)");
        assertFalse(drive.contains("forgetHead"), "forgetHead only exists for a second writer");
        String driver = source("src/main/java/com/moddingfromamod/vanilla/RigDriver.java");
        String look = method(driver, "public static int lookAt(");
        assertFalse(look.contains("RigPose.compose(bones(groups), null"),
                "lookAt composes with a NULL pose again, discarding the clip's head (#765)");
        assertTrue(look.contains("rig.lastPose()"), "lookAt no longer composes with the clip");
    }
}
