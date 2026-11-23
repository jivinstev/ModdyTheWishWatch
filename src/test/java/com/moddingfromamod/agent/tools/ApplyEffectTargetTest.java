package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

/**
 * TARGET on an effect that has none (chunk 2). Spark passed it to {@code periodic} and
 * {@code proximity} — which run their command as the creature itself — was told only "has no
 * parameter 'TARGET'", and went round three more times with a command that hurt nothing. The answer
 * must name the effects that DO take it.
 */
class ApplyEffectTargetTest {

    @Test
    void targetOnAnEffectWithoutOneSaysWhichEffectsTakeIt() {
        JsonObject args = new JsonObject();
        args.addProperty("effect", "periodic");
        args.addProperty("tag", "wishmob_gt");
        JsonObject params = new JsonObject();
        params.addProperty("COMMAND", "damage @s 6");
        params.addProperty("TARGET", "monsters");
        args.add("params", params);
        var r = new ApplyEffectTool().call(args);
        assertTrue(r.isError(), r.text());
        assertTrue(r.text().contains("on_hit") && r.text().contains("breath_cone")
                && r.text().contains("runs your COMMAND as the creature"), r.text());
    }

    @Test
    void aHealHandedToTheVictimsIsSaidToHealThemNotItself() {
        // routing arm: 8 of 8 "heals itself when hurt" built as on_hit + regeneration on @s.
        java.util.Map<String, String> p = new java.util.LinkedHashMap<>();
        p.put("TAG", "wishmob_gt");
        p.put("COMMAND", "effect give @s minecraft:regeneration 8 1 true");
        assertTrue(ApplyEffectTool.healsWhomNote("on_hit", p).contains("abilities: [\"heal\"]"));
        p.put("TARGET", "players");
        assertTrue(ApplyEffectTool.healsWhomNote("on_hit", p).isEmpty(), "a pet that heals HIM is the wish");
        p.remove("TARGET");
        p.put("COMMAND", "damage @s 4");
        assertTrue(ApplyEffectTool.healsWhomNote("on_hit", p).isEmpty(), "a bite is not a heal");
        java.util.Map<String, String> q = new java.util.LinkedHashMap<>();
        q.put("EFFECT", "minecraft:regeneration");
        assertTrue(!ApplyEffectTool.healsWhomNote("inflict_nearby", q).isEmpty());
        assertTrue(ApplyEffectTool.healsWhomNote("periodic", p).isEmpty(), "periodic runs as the creature");
    }

    private static com.moddingfromamod.species.Species species(String json) {
        return com.moddingfromamod.species.Species.fromJson(
                com.google.gson.JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void aStepOrLandingOnACreatureThatFightsStandingStillIsSaid() {
        // chunk 3: on_step chosen right, on a guardian, which fought the monsters while held
        // still — so "only when it walks" was never true.
        var guardian = species("{\"id\":\"gt_g\",\"base\":\"guardian\",\"powers\":[\"friendly\"]}");
        var slammer = species("{\"id\":\"gt_s\",\"base\":\"wild\",\"powers\":[\"friendly\"],\"abilities\":[\"slam\"]}");
        var wild = species("{\"id\":\"gt_w\",\"base\":\"wild\",\"powers\":[\"friendly\"]}");
        assertTrue(ApplyEffectTool.onlyWhenNote("on_step", guardian).contains("guardian instinct"));
        assertTrue(ApplyEffectTool.onlyWhenNote("on_land", slammer).contains("its slam"));
        assertTrue(ApplyEffectTool.onlyWhenNote("on_step", wild).isEmpty());
        assertTrue(ApplyEffectTool.onlyWhenNote("on_hit", guardian).isEmpty(),
                "on_hit is not an 'only when' trigger");
    }
}
