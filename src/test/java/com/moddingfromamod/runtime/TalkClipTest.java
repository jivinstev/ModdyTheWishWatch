package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Gate A for the TALK clip (Moddy Media Stage 2): a server flag picks it above walk and idle,
 * below attack; a species without one idles; and a talk clip is found by its name's suffix.
 */
class TalkClipTest {

    private static Map<String, BedrockAnimation> clips(String json) {
        return BedrockAnimation.parseAll(JsonParser.parseString(json).getAsJsonObject());
    }

    private static final String WITH_TALK = """
        {"animations":{
          "animation.x.idle":{"loop":true,"animation_length":4.0,
            "bones":{"head":{"rotation":{"0.0":[0,0,0],"4.0":[8,0,0]}}}},
          "animation.x.talk":{"loop":true,"animation_length":1.2,
            "bones":{"jaw":{"rotation":{"0.0":[0,0,0],"0.6":[30,0,0],"1.2":[0,0,0]}}}},
          "animation.x.walk":{"loop":true,"animation_length":1.0,
            "bones":{"leg":{"rotation":{"0.0":[20,0,0],"0.5":[-20,0,0],"1.0":[20,0,0]}}}},
          "animation.x.attack":{"animation_length":0.5,
            "bones":{"arm":{"rotation":{"0.0":[0,0,0],"0.5":[-90,0,0]}}}}
        }}
        """;

    private static final String NO_TALK = """
        {"animations":{
          "animation.y.idle":{"loop":true,"animation_length":4.0,
            "bones":{"head":{"rotation":{"0.0":[0,0,0],"4.0":[8,0,0]}}}},
          "animation.y.walk":{"loop":true,"animation_length":1.0,
            "bones":{"leg":{"rotation":{"0.0":[20,0,0],"0.5":[-20,0,0],"1.0":[20,0,0]}}}}
        }}
        """;

    private static BedrockAnimation.Clip pick(boolean attacking, float walkSpeed, boolean talking) {
        return AnimationState.select(attacking, 0.5f, walkSpeed, 3f, 10f, talking, 0.3f).clip();
    }

    @Test
    void talkRanksAboveWalkAndIdleAndBelowAttack() {
        assertEquals(BedrockAnimation.Clip.TALK, pick(false, 0f, true), "standing and talking");
        assertEquals(BedrockAnimation.Clip.TALK, pick(false, 1f, true), "walking and talking");
        assertEquals(BedrockAnimation.Clip.ATTACK, pick(true, 1f, true), "a hit lands mid-sentence");
        // The flag off: the motion rules exactly as before.
        assertEquals(BedrockAnimation.Clip.IDLE, pick(false, 0f, false));
        assertEquals(BedrockAnimation.Clip.WALK, pick(false, 1f, false));
        assertEquals(BedrockAnimation.Clip.ATTACK, pick(true, 0f, false));
    }

    @Test
    void theOldFiveArgumentSelectNeverTalks() {
        assertEquals(BedrockAnimation.Clip.IDLE, AnimationState.select(false, 0f, 0f, 0f, 5f).clip());
    }

    @Test
    void talkRunsFromTheTickTheFlagWentOn() {
        var sel = AnimationState.select(false, 0f, 0f, 0f, 99f, true, 0.6f);
        assertEquals(0.6f, sel.seconds(), 1e-6);
        assertEquals(99f, sel.idleSeconds(), 1e-6);
        // 0.6 s into a 1.2 s talk: the jaw at its widest.
        var pose = AnimationState.pose(clips(WITH_TALK), sel);
        assertEquals(30f, pose.get("jaw").rotation()[0], 1e-4);
        assertFalse(pose.containsKey("head"), "the talk clip, not the idle, is sampled");
    }

    @Test
    void aSpeciesWithNoTalkClipIdlesAtTheIdlesOwnTime() {
        Map<String, BedrockAnimation> c = clips(NO_TALK);
        var sel = AnimationState.select(false, 0f, 0f, 0f, 2f, true, 0.1f);
        var pose = AnimationState.pose(c, sel);
        assertTrue(pose.containsKey("head"), "falls back to the idle");
        assertFalse(pose.containsKey("leg"), "never to the walk");
        // At the age (2 s of a 4 s idle → 4 degrees), not restarted at the talk's 0.1 s.
        var idle = AnimationState.pose(c, AnimationState.select(false, 0f, 0f, 0f, 2f));
        assertEquals(idle.get("head").rotation()[0], pose.get("head").rotation()[0], 1e-6);
        assertEquals(4f, pose.get("head").rotation()[0], 1e-4);
    }

    @Test
    void aTalkSelectionWithNoClipsAtAllIsEmptyNotAnException() {
        assertTrue(AnimationState.pose(Map.of(),
                new AnimationState.Selection(BedrockAnimation.Clip.TALK, 1f, 1f)).isEmpty());
    }

    @Test
    void talkIsMatchedBySuffixAndNeverShadowedByWalk() {
        Map<String, BedrockAnimation> c = clips(WITH_TALK);
        assertEquals("animation.x.talk", BedrockAnimation.find(c, BedrockAnimation.Clip.TALK).name());
        assertEquals("animation.x.walk", BedrockAnimation.find(c, BedrockAnimation.Clip.WALK).name());
        assertNull(BedrockAnimation.find(clips(NO_TALK), BedrockAnimation.Clip.TALK));
    }
}
