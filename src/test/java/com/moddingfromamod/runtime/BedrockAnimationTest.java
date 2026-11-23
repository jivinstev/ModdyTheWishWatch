package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Gate A for the animation player. The fixture mirrors the shape of a REAL shipped animation (Battle
 * of Lord's baby dragon: looping idle/walk with rotation + position channels, a one-shot attack), so
 * these tests prove we can play animations authored for a real GeckoLib mob.
 */
class BedrockAnimationTest {

    /** Same structure as a Blockbench 1.8.0 export. */
    private static final String DRAGON = """
        {
          "format_version": "1.8.0",
          "animations": {
            "animation.baby_dragon.idle": {
              "loop": true,
              "animation_length": 2.0,
              "bones": {
                "head": {"rotation": {"0.0": [0,0,0], "1.0": [-4,3,0], "2.0": [0,0,0]}},
                "body": {"position": {"0.0": [0,0,0], "1.0": [0,0.4,0], "2.0": [0,0,0]}}
              }
            },
            "animation.baby_dragon.walk": {
              "loop": true,
              "animation_length": 1.0,
              "bones": {
                "legFL": {"rotation": {"0.0": [20,0,0], "0.5": [-20,0,0], "1.0": [20,0,0]}}
              }
            },
            "animation.baby_dragon.attack": {
              "loop": false,
              "animation_length": 0.5,
              "bones": {
                "snout": {"rotation": {"0.0": [0,0,0], "0.25": [30,0,0], "0.5": [0,0,0]}}
              }
            }
          }
        }
        """;

    private static Map<String, BedrockAnimation> parse(String json) {
        return BedrockAnimation.parseAll(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void parsesEveryClip() {
        Map<String, BedrockAnimation> clips = parse(DRAGON);
        assertEquals(3, clips.size());
        BedrockAnimation idle = clips.get("animation.baby_dragon.idle");
        assertNotNull(idle);
        assertTrue(idle.loop());
        assertEquals(2.0f, idle.length(), 1e-6);
        assertEquals(2, idle.boneCount());
        assertTrue(idle.animates("head"));
        assertFalse(idle.animates("tail"));
    }

    @Test
    void interpolatesBetweenKeyframes() {
        BedrockAnimation idle = parse(DRAGON).get("animation.baby_dragon.idle");
        // Exactly on a keyframe.
        assertEquals(-4f, idle.pose(1.0f).get("head").rotation()[0], 1e-5);
        // Halfway between 0.0 [0,0,0] and 1.0 [-4,3,0].
        BedrockAnimation.BonePose half = idle.pose(0.5f).get("head");
        assertEquals(-2f, half.rotation()[0], 1e-5);
        assertEquals(1.5f, half.rotation()[1], 1e-5);
        // Position channel on a different bone, halfway to 0.4.
        assertEquals(0.2f, idle.pose(0.5f).get("body").position()[1], 1e-5);
        // Scale defaults to 1 when the clip doesn't animate it.
        assertEquals(1f, half.scale()[0], 1e-6);
    }

    @Test
    void loopingWrapsAndOneShotHolds() {
        Map<String, BedrockAnimation> clips = parse(DRAGON);
        BedrockAnimation idle = clips.get("animation.baby_dragon.idle");
        // t=2.5 on a 2s loop == t=0.5
        assertEquals(idle.pose(0.5f).get("head").rotation()[0],
                idle.pose(2.5f).get("head").rotation()[0], 1e-5);

        BedrockAnimation attack = clips.get("animation.baby_dragon.attack");
        assertFalse(attack.loop());
        // Past the end, a one-shot holds its final keyframe rather than wrapping.
        assertEquals(0f, attack.pose(9.0f).get("snout").rotation()[0], 1e-5);
        assertEquals(30f, attack.pose(0.25f).get("snout").rotation()[0], 1e-5);
    }

    @Test
    void findsClipsByForgivingName() {
        Map<String, BedrockAnimation> clips = parse(DRAGON);
        assertSame(clips.get("animation.baby_dragon.walk"),
                BedrockAnimation.find(clips, BedrockAnimation.Clip.WALK));
        assertSame(clips.get("animation.baby_dragon.attack"),
                BedrockAnimation.find(clips, BedrockAnimation.Clip.ATTACK));

        // Bare names and odd casing also resolve.
        Map<String, BedrockAnimation> bare = parse("""
            {"animations":{"Idle":{"loop":true,"bones":{"b":{"rotation":[0,5,0]}}}}}
            """);
        assertNotNull(BedrockAnimation.find(bare, BedrockAnimation.Clip.IDLE));
        assertNull(BedrockAnimation.find(bare, BedrockAnimation.Clip.WALK));
    }

    @Test
    void exactSuffixBeatsSubstringSoAttackIsNeverShadowed() {
        // "walk_attack" contains "attack"; the properly-named clip must still win.
        Map<String, BedrockAnimation> clips = parse("""
            {"animations":{
              "animation.x.walk_attack":{"bones":{"a":{"rotation":[1,0,0]}}},
              "animation.x.attack":{"bones":{"b":{"rotation":[2,0,0]}}}
            }}
            """);
        BedrockAnimation found = BedrockAnimation.find(clips, BedrockAnimation.Clip.ATTACK);
        assertNotNull(found);
        assertTrue(found.animates("b"), "should resolve the exact .attack clip");
    }

    @Test
    void staticVectorChannelIsAConstantPose() {
        Map<String, BedrockAnimation> clips = parse("""
            {"animations":{"animation.x.idle":{"loop":true,"bones":{
              "arm":{"rotation":[0,0,15],"scale":[2,2,2]}}}}}
            """);
        BedrockAnimation a = clips.get("animation.x.idle");
        assertEquals(15f, a.pose(0f).get("arm").rotation()[2], 1e-6);
        assertEquals(15f, a.pose(99f).get("arm").rotation()[2], 1e-6);
        assertEquals(2f, a.pose(3f).get("arm").scale()[0], 1e-6);
    }

    @Test
    void handlesPrePostKeyframesAndMolangAndGarbage() {
        // {"pre":…,"post":…} step keyframes: we take post.
        Map<String, BedrockAnimation> step = parse("""
            {"animations":{"animation.x.idle":{"animation_length":1.0,"bones":{
              "b":{"rotation":{"0.0":{"pre":[0,0,0],"post":[90,0,0]},"1.0":[90,0,0]}}}}}}
            """);
        assertEquals(90f, step.get("animation.x.idle").pose(0f).get("b").rotation()[0], 1e-5);

        // A Molang expression we can't evaluate must not break the clip — that component stays neutral.
        Map<String, BedrockAnimation> molang = parse("""
            {"animations":{"animation.x.idle":{"animation_length":1.0,"bones":{
              "b":{"rotation":{"0.0":["math.sin(query.anim_time*90)",10,0]}}}}}}
            """);
        BedrockAnimation.BonePose p = molang.get("animation.x.idle").pose(0f).get("b");
        assertEquals(0f, p.rotation()[0], 1e-6);
        assertEquals(10f, p.rotation()[1], 1e-6);

        // Nothing usable anywhere -> no clips, never an exception.
        assertTrue(BedrockAnimation.parseAll(null).isEmpty());
        assertTrue(parse("{}").isEmpty());
        assertTrue(parse("{\"animations\":{}}").isEmpty());
        assertTrue(parse("{\"animations\":{\"a\":{\"bones\":{}}}}").isEmpty());
    }

    @Test
    void lengthIsDerivedWhenMissingAndNeverZero() {
        BedrockAnimation a = parse("""
            {"animations":{"animation.x.idle":{"loop":true,"bones":{
              "b":{"rotation":{"0.0":[0,0,0],"3.0":[10,0,0]}}}}}}
            """).get("animation.x.idle");
        assertEquals(3.0f, a.length(), 1e-6);

        // A single static pose still gets a usable non-zero length (callers divide by it).
        BedrockAnimation stat = parse("""
            {"animations":{"animation.x.idle":{"bones":{"b":{"rotation":[0,0,0]}}}}}
            """).get("animation.x.idle");
        assertTrue(stat.length() > 0);
    }

    @Test
    void realWorldFixtureFromAShippedMobParses() {
        // The exact bone/channel mix a real mod's baby dragon ships with.
        Map<String, BedrockAnimation> clips = parse(DRAGON);
        BedrockAnimation walk = BedrockAnimation.find(clips, BedrockAnimation.Clip.WALK);
        assertNotNull(walk);
        // A leg swings forward and back across the cycle.
        float atStart = walk.pose(0f).get("legFL").rotation()[0];
        float atMid = walk.pose(0.5f).get("legFL").rotation()[0];
        assertEquals(20f, atStart, 1e-5);
        assertEquals(-20f, atMid, 1e-5);
    }
}
