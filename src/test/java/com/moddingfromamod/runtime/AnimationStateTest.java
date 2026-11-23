package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Gate A for the clip state machine: priority, distance-driven walk, and graceful fallback. */
class AnimationStateTest {

    private static Map<String, BedrockAnimation> clips(String json) {
        return BedrockAnimation.parseAll(JsonParser.parseString(json).getAsJsonObject());
    }

    private static final String ALL_THREE = """
        {"animations":{
          "animation.x.idle":{"loop":true,"animation_length":2.0,
            "bones":{"head":{"rotation":{"0.0":[0,0,0],"2.0":[10,0,0]}}}},
          "animation.x.walk":{"loop":true,"animation_length":1.0,
            "bones":{"leg":{"rotation":{"0.0":[20,0,0],"0.5":[-20,0,0],"1.0":[20,0,0]}}}},
          "animation.x.attack":{"animation_length":0.5,
            "bones":{"jaw":{"rotation":{"0.0":[0,0,0],"0.5":[45,0,0]}}}}
        }}
        """;

    @Test
    void attackBeatsWalkBeatsIdle() {
        // Attacking while running still plays the attack.
        assertEquals(BedrockAnimation.Clip.ATTACK,
                AnimationState.select(true, 0.5f, 1.0f, 10f, 100f).clip());
        assertEquals(BedrockAnimation.Clip.WALK,
                AnimationState.select(false, 0f, 1.0f, 10f, 100f).clip());
        assertEquals(BedrockAnimation.Clip.IDLE,
                AnimationState.select(false, 0f, 0f, 10f, 100f).clip());
    }

    @Test
    void aCreatureBarelyDriftingCountsAsStandingStill() {
        assertEquals(BedrockAnimation.Clip.IDLE,
                AnimationState.select(false, 0f, AnimationState.WALK_THRESHOLD - 0.001f, 5f, 3f).clip());
        assertEquals(BedrockAnimation.Clip.WALK,
                AnimationState.select(false, 0f, AnimationState.WALK_THRESHOLD + 0.001f, 5f, 3f).clip());
    }

    @Test
    void walkIsDrivenByDistanceNotWallClock() {
        // Same age, different distance travelled -> different point in the walk cycle.
        float a = AnimationState.select(false, 0f, 1f, 4f, 99f).seconds();
        float b = AnimationState.select(false, 0f, 1f, 8f, 99f).seconds();
        assertTrue(b > a, "further travelled should be further through the clip");
        assertEquals(4f * AnimationState.WALK_CYCLE_SCALE, a, 1e-6);
    }

    @Test
    void attackProgressIsScaledToTheClipLength() {
        Map<String, BedrockAnimation> c = clips(ALL_THREE);
        // Halfway through the swing = halfway through a 0.5s attack clip = 0.25s = 22.5 degrees.
        var pose = AnimationState.pose(c,
                new AnimationState.Selection(BedrockAnimation.Clip.ATTACK, 0.5f));
        assertEquals(22.5f, pose.get("jaw").rotation()[0], 1e-4);

        // End of the swing = the clip's final keyframe.
        var end = AnimationState.pose(c,
                new AnimationState.Selection(BedrockAnimation.Clip.ATTACK, 1.0f));
        assertEquals(45f, end.get("jaw").rotation()[0], 1e-4);
    }

    @Test
    void aMissingClipFallsBackInsteadOfFreezing() {
        // Only an idle: a walking creature still breathes rather than going rigid.
        Map<String, BedrockAnimation> idleOnly = clips("""
            {"animations":{"animation.x.idle":{"loop":true,"animation_length":2.0,
              "bones":{"head":{"rotation":{"0.0":[0,0,0],"2.0":[10,0,0]}}}}}}
            """);
        var pose = AnimationState.pose(idleOnly,
                new AnimationState.Selection(BedrockAnimation.Clip.WALK, 1.0f));
        assertFalse(pose.isEmpty(), "should fall back to the idle clip");
        assertTrue(pose.containsKey("head"));

        // Attack with no attack clip falls back too.
        assertFalse(AnimationState.pose(idleOnly,
                new AnimationState.Selection(BedrockAnimation.Clip.ATTACK, 0.5f)).isEmpty());
    }

    @Test
    void noClipsMeansNoPoseAndNeverAnException() {
        assertTrue(AnimationState.pose(Map.of(),
                new AnimationState.Selection(BedrockAnimation.Clip.IDLE, 1f)).isEmpty());
        assertTrue(AnimationState.pose(null,
                new AnimationState.Selection(BedrockAnimation.Clip.IDLE, 1f)).isEmpty());
        assertTrue(AnimationState.pose(clips(ALL_THREE), null).isEmpty());
    }

    @Test
    void theRightClipIsActuallySampled() {
        Map<String, BedrockAnimation> c = clips(ALL_THREE);
        var walking = AnimationState.pose(c,
                new AnimationState.Selection(BedrockAnimation.Clip.WALK, 0.5f));
        assertTrue(walking.containsKey("leg"), "walk clip animates the leg");
        assertEquals(-20f, walking.get("leg").rotation()[0], 1e-4);

        var idling = AnimationState.pose(c,
                new AnimationState.Selection(BedrockAnimation.Clip.IDLE, 2.0f));
        assertTrue(idling.containsKey("head"), "idle clip animates the head");
    }
}
