package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The face kit.
 *
 * <p>These assert the thing that actually carries the mood — <b>relative height</b> — rather than
 * any rotation. That is the design: a brow whose inner end is <i>placed</i> lower is a scowl under
 * every renderer and every sign convention, whereas a brow <i>rotated</i> to look like a scowl
 * depends on a Z-roll sign this project has repeatedly got backwards. So the tests can be trusted
 * offline, which a rotation-based face's could not.
 */
class SculptFaceTest {

    /** A head: 14 wide, 12 tall, front at z=+6. */
    private static final float[] HEAD = {-7, 20, -6, 7, 32, 6};

    private static List<CreatureSculpt.Part> face(String mood) {
        return SculptFace.build("kit_face", "head", HEAD, mood, 0x222222, 0xFFFFFF);
    }

    private static CreatureSculpt.Part part(List<CreatureSculpt.Part> ps, String suffix) {
        return ps.stream().filter(p -> p.name().endsWith(suffix)).findFirst().orElseThrow();
    }

    private static float midY(CreatureSculpt.Part p) {
        return (p.from()[1] + p.to()[1]) / 2f;
    }

    private static float height(CreatureSculpt.Part p) {
        return p.to()[1] - p.from()[1];
    }

    @Test
    void everyMoodBuildsTwoEyesTwoBrowsAndAMouth() {
        for (String mood : SculptFace.MOODS) {
            var f = face(mood);
            assertEquals(2, f.stream().filter(p -> p.name().contains("eye")).count(), mood);
            assertTrue(f.stream().filter(p -> p.name().contains("brow")).count() >= 4, mood);
            assertEquals(3, f.stream().filter(p -> p.name().contains("mouth")).count(), mood);
        }
    }

    /** The whole point: an angry brow's inner end sits LOWER than its outer end. */
    @Test
    void angryBrowsScowlAndSadBrowsWorry() {
        float angryInner = midY(part(face("angry"), "browRi"));
        float angryOuter = midY(part(face("angry"), "browRo"));
        assertTrue(angryInner < angryOuter,
                "an angry brow's inner end must sit lower: " + angryInner + " vs " + angryOuter);

        float sadInner = midY(part(face("sad"), "browRi"));
        float sadOuter = midY(part(face("sad"), "browRo"));
        assertTrue(sadInner > sadOuter,
                "a sad brow's inner end must sit higher: " + sadInner + " vs " + sadOuter);
    }

    /** And the mouth: ends up is a smile, ends down is a frown. No rotation involved. */
    @Test
    void happyMouthsSmileAndAngryMouthsFrown() {
        var happy = face("happy");
        assertTrue(midY(part(happy, "mouth0")) > midY(part(happy, "mouth1")),
                "a happy mouth's ends must sit above its centre");
        var angry = face("angry");
        assertTrue(midY(part(angry, "mouth0")) < midY(part(angry, "mouth1")),
                "an angry mouth's ends must sit below its centre");
    }

    @Test
    void sleepyEyesAreNarrowerThanSurprisedOnes() {
        float sleepy = height(part(face("sleepy"), "eyeR"));
        float surprised = height(part(face("surprised"), "eyeR"));
        assertTrue(sleepy < surprised, "sleepy " + sleepy + " should be under surprised " + surprised);
    }

    @Test
    void fierceShowsTeethAndHappyDoesNot() {
        assertTrue(face("fierce").stream().anyMatch(p -> p.name().contains("tooth")));
        assertFalse(face("happy").stream().anyMatch(p -> p.name().contains("tooth")));
    }

    /** Every face part must sit on the FRONT of the head, or it is a face on the back of a skull. */
    @Test
    void theWholeFaceSitsOnTheFrontOfTheHead() {
        for (String mood : SculptFace.MOODS) {
            for (var p : face(mood)) {
                assertTrue(p.to()[2] > HEAD[5] - 2f,
                        mood + ": " + p.name() + " is not on the front face (z=" + p.to()[2] + ")");
                assertTrue(p.from()[1] >= HEAD[1] && p.to()[1] <= HEAD[4] + 1f,
                        mood + ": " + p.name() + " ran off the head vertically");
            }
        }
    }

    @Test
    void everyFacePartIsDrawableSoNoneAreSilentlyDropped() {
        for (String mood : SculptFace.MOODS) {
            for (var p : face(mood)) {
                assertTrue(p.width() >= 1 && p.height() >= 1 && p.depth() >= 1,
                        mood + ": " + p.name() + " would be dropped by parseParts");
            }
        }
    }

    /**
     * An unrecognised mood becomes HAPPY, never a scowl. An unexpected smile on a monster is funny;
     * an unexpected scowl on a child's mascot is the bug this kit exists to fix.
     */
    @Test
    void anUnknownMoodIsFriendly() {
        assertEquals("happy", SculptFace.sanitize("sproingy"));
        assertEquals("happy", SculptFace.sanitize(null));
        assertEquals("happy", SculptFace.sanitize(""));
        assertEquals("angry", SculptFace.sanitize("grumpy"));
        assertEquals("surprised", SculptFace.sanitize("shocked"));
    }

    /** Through the real kit path, with the marker that makes adoption countable. */
    @Test
    void theTableIsNoLongerReachableAsAKit() {
        // WITHDRAWN ON PURPOSE. A nine-box prefab makes every face in the game a member of a
        // six-item family and takes the most expressive part of a creature away from the only
        // thing that knows what the character looks like. Correct-by-construction is right for
        // questions with ONE right answer; a face has none, so the rung below applies -- the
        // mood-to-shape research is now guidance in lessons/creature.md, and this class is kept
        // for the table it encodes rather than as a stamp.
        var head = CreatureSculpt.parseParts(JsonParser.parseString(
                "[{\"name\":\"head\",\"from\":[-7,20,-6],\"to\":[7,32,6],\"colour\":\"white\"}]")
                .getAsJsonArray());
        var made = SculptKits.expand(JsonParser.parseString(
                "[{\"kit\":\"face\",\"on\":\"head\",\"mood\":\"happy\"}]").getAsJsonArray(), head);
        assertTrue(made.isEmpty(), "the face kit must no longer build anything: " + made);
        assertFalse(SculptKits.NAMES.contains("face"), "and must not be offered in the vocabulary");
    }
}
