package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * the closing line may not promise a part no call built. Every positive case is a real
 * closing line from the step-6 archive (synthetic wishes); most of the file is about what must NOT
 * fire, because a false send-back costs a turn on a good wish.
 */
class SayClaimTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static SayClaim.Evidence ev(Object... toolThenArgs) {
        SayClaim.Evidence e = new SayClaim.Evidence();
        for (int i = 0; i < toolThenArgs.length; i += 2) {
            e.add((String) toolThenArgs[i], json((String) toolThenArgs[i + 1]), null);
        }
        return e;
    }

    private static List<String> families(String say, SayClaim.Evidence e) {
        return SayClaim.check(say, e).stream().map(SayClaim.Unbacked::family).toList();
    }

    @Test
    @DisplayName("a glowing part nothing lights is sent back, and the complaint quotes it")
    void glowingHeart() {
        SayClaim.Evidence e = ev("make_item", "{\"name\":\"Hot Chocolate Maker\",\"effect\":"
                + "\"load_and_pour\",\"shape\":\"machine\",\"params\":{\"INGREDIENTS\":"
                + "\"minecraft:cocoa_beans\"}}");
        var u = SayClaim.check("Your Hot Chocolate Maker is here! A cozy brown machine with a "
                + "glowing red heart — put it down!", e);
        assertEquals(1, u.size());
        assertEquals("glow", u.get(0).family());
        assertTrue(u.get(0).clause().contains("glowing red heart"));
        assertTrue(SayClaim.refusal(u).contains("glowing"));
    }

    @Test
    @DisplayName("a glow is backed by a light block, a glow flag or a finds power")
    void glowBacked() {
        String say = "Your base is here, with glowing lights inside!";
        assertEquals(List.of(), families(say, ev("build_structure",
                "{\"shapes\":[{\"block\":\"minecraft:sea_lantern\"}]}")));
        assertEquals(List.of(), families("It glows over diamonds!",
                ev("modify_mob", "{\"finds\":\"diamonds\"}")));
        assertEquals(List.of(), families("You're glowing now!",
                ev("modify_player", "{\"glow\":\"gold\"}")));
    }

    @Test
    @DisplayName("glowing EYES are not a promise (an enderman's are lit by vanilla)")
    void glowingEyes() {
        assertEquals(List.of(), families("Tall black Enderman with glowing purple eyes!",
                ev("sculpt_creature", "{\"parts\":[{\"name\":\"eyeL\",\"colour\":\"purple\"}]}")));
    }

    @Test
    @DisplayName("the agent's own NAME and NOTE never back a claim — the above-ground base")
    void narrationIsNotEvidence() {
        SayClaim.Evidence e = ev("build_structure", "{\"id\":\"underground_glass_base\","
                + "\"name\":\"Cool Underground Glass Base\",\"note\":\"Underground hidden base\","
                + "\"shapes\":[{\"block\":\"minecraft:glass\",\"dy\":0},{\"block\":"
                + "\"minecraft:polished_deepslate\",\"dy\":6}]}");
        assertEquals(List.of("underground"),
                families("Your cool underground base is here — a big glass box!", e));
        SayClaim.Evidence sunk = ev("build_structure",
                "{\"shapes\":[{\"block\":\"minecraft:glass\",\"dy\":-8}]}");
        assertEquals(List.of(), families("Your cool underground base is here!", sunk));
    }

    @Test
    @DisplayName("a setting word on a wish that placed nothing is about something he already has")
    void settingNeedsAPlacement() {
        assertEquals(List.of(), families("Put on your helmet and explore your underwater dome!",
                ev("make_item", "{\"effect\":\"breathe\",\"shape\":\"worn\"}")));
        assertEquals(List.of("underwater"), families("A glass dome under the sea!",
                ev("build_structure", "{\"shapes\":[{\"block\":\"minecraft:glass\"}]}")));
        assertEquals(List.of(), families("A glass dome under the sea!", ev("build_structure",
                "{\"shapes\":[{\"block\":\"minecraft:water\"}]}")));
    }

    @Test
    @DisplayName("'upgraded' over a brand-new thing is sent back; over any edit it is not")
    void upgradedNeedsAnEdit() {
        String say = "Your black hole now pulls WAY harder!";
        assertEquals(List.of("edit"), families(say,
                ev("author_datapack", "{\"cbscript\":\"clock tick\"}")));
        assertEquals(List.of(), families(say,
                ev("make_item", "{\"start_from\":\"black_hole\",\"params\":{\"STRENGTH\":\"huge\"}}")));
        assertEquals(List.of(), families("You're GIANT now — 4 times bigger!",
                ev("modify_player", "{\"scale\":4}")));
        SayClaim.Evidence existed = ev("make_item", "{\"effect\":\"pull\"}");
        existed.markEdited();
        assertEquals(List.of(), families("Your tractor beam is upgraded!", existed));
        // Not a change claim: what the new thing does to HIM.
        assertEquals(List.of(), families("Now more ice makes YOU bigger!",
                ev("author_datapack", "{\"cbscript\":\"x\"}")));
    }

    @Test
    @DisplayName("behaviours: a pet that brings, stays, follows or guards needs a call that tried")
    void behaviours() {
        SayClaim.Evidence nothing = new SayClaim.Evidence();
        assertEquals(List.of("stay"),
                families("He's cheerful and stays happily where you left him.", nothing));
        SayClaim.Evidence follow = ev("modify_mob", "{\"follow\":true,\"friendly\":true}");
        assertEquals(List.of("fetch"), families("He follows you everywhere and loves picking up "
                + "blocks to bring to you!", follow));
        assertEquals(List.of(), families("He follows you and drops blocks for you!",
                ev("modify_mob", "{\"follow\":true}", "apply_effect",
                        "{\"effect\":\"drops\",\"params\":{\"ITEM\":\"minecraft:dirt\"}}")));
        assertEquals(List.of(), families("Every zombie now drops a diamond for you!",
                ev("change_drops", "{\"mob\":\"zombie\"}")));
        assertEquals(List.of("follow"), families("Your snow golem follows you everywhere!",
                ev("run_command", "{\"command\":[\"summon minecraft:snow_golem\"]}")));
        assertEquals(List.of(), families("Hold it and pigs and horses will follow you.",
                nothing), "a plural 'will follow' is a carrot — vanilla — and not a pet claim");
        // archive (2026-11-18): the Call horn bringing HIM is the creature coming, not a fetch...
        assertEquals(List.of(), families("Blow the Call Snorgasaur horn in your hotbar to bring him "
                + "to you anytime", nothing));
        // ...but bringing a THING to you still needs a call that tried.
        assertEquals(List.of("fetch"), families("He brings diamonds to you every morning.", nothing));
        // "they stay put" is the monsters, not him...
        assertEquals(List.of(), families("Walk with it and monsters bump out of its path, but when it "
                + "stands still they stay put", nothing));
        // ...while "it stays put" is still his claim.
        assertEquals(List.of("stay"), families("When you leave, it stays put.", nothing));
    }

    @Test
    @DisplayName("an honest line is untouched")
    void honest() {
        SayClaim.Evidence e = ev("build_structure", "{\"shapes\":[{\"block\":\"minecraft:oak_planks\"}]}");
        assertEquals(List.of(), families("Your cozy little wooden house is here! Wooden walls, "
                + "windows, a door and a pointy roof — go inside!", e));
        assertEquals(List.of(), families("", e));
        assertEquals(List.of(), families(null, e));
    }

    @Test
    @DisplayName("template and conjure_at arguments repeat the say, so they back nothing")
    void templatesAreNarration() {
        SayClaim.Evidence e = ev("add_template", "{\"commands\":[\"glow\"],\"note\":\"glowing heart\"}",
                "conjure_at", "{\"message\":\"a glowing machine\"}");
        assertEquals(List.of("glow"), families("A machine with a glowing heart!", e));
    }

    @Test
    @DisplayName("a creature built on one of his older ones keeps its behaviour: 'follows you' is not judged")
    void inheritedBehaviourIsNotJudged() {
        // panel: his robot puppy was sculpted base:companion by an EARLIER wish.
        assertEquals(List.of(), families("Your Robo Pup is now bright blue — a bouncy blue robot puppy "
                + "that follows you everywhere!", ev("sculpt_creature",
                "{\"id\":\"robot_puppy\",\"start_from\":\"robot_puppy\",\"recolour\":{\"body\":\"blue\"}}")));
        assertEquals(List.of(), families("It hops and naps and follows you everywhere just like your puppy",
                ev("sculpt_creature", "{\"id\":\"robo_kitten\",\"start_from\":\"robot_puppy\"}")));
        // ...but built on one THIS wish made, nothing was inherited: still judged.
        assertEquals(List.of("follow"), families("Your kitten follows you everywhere!",
                ev("sculpt_creature", "{\"id\":\"kitten\",\"parts\":[]}",
                        "sculpt_creature", "{\"id\":\"kitten\",\"start_from\":\"kitten\"}")));
        // ...and only behaviour is waived: a glow still needs a light.
        assertEquals(List.of("glow"), families("Your Robo Pup now has a glowing red heart!",
                ev("sculpt_creature", "{\"id\":\"robot_puppy\",\"start_from\":\"robot_puppy\"}")));
    }

    @Test
    @DisplayName("a wish that made only a PICTURE: 'a big glowing sun' describes the picture, so it stands")
    void aPicturesGlowIsWhatItShows() {
        String say = "A big glowing yellow sun is sinking into the dark blue sea!";
        assertEquals(List.of(), families(say, ev("paint_surface", "{\"id\":\"sunset\",\"picture\":\"{}\"}")));
        // ...but a picture beside a build still needs a light for its glow
        assertEquals(List.of("glow"), families(say, ev("paint_surface", "{\"id\":\"sunset\"}",
                "build_structure", "{\"shapes\":[{\"block\":\"minecraft:stone\"}]}")));
    }
}
