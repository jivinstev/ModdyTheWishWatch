package com.moddingfromamod.world;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rule library and the rules-file schema — tested once, here. */
class WorldRulesTest {

    static final String OK = """
            {"store":"world-rules","v":1,"world":"closed",
             "brief":["be terse"],
             "rules":[
              {"id":"no-time","type":"deny_world_effect","effects":["time","weather"],
               "message":{"rule":"Time is shared","why":"everyone shares the sky"}},
              {"id":"own","type":"base_reach","effects":["outside_own_base","others_bases"],
               "message":{"rule":"a wish stays on your island","why":"islands belong to their child"}},
              {"id":"scoped","type":"allow_base_scoped","params":{"scopes":["own_base","pets"]}},
              {"id":"cap","type":"creature_cap","params":{"max":12}},
              {"id":"price","type":"price_by_size","params":{"small":1,"medium":2,"large":3}},
              {"id":"ms","type":"milestone_payout","params":{"milestones":[{"id":"cobble","name":"Cobblestone Generator","pays":2}]}},
              {"id":"gate","type":"value_gate","params":{"by":"island_progress"}},
              {"id":"salvage","type":"salvage_lock"}],
             "_notes":"a note is skipped"}""";

    private static List<String> errors(String json) {
        WorldRules.Parsed p = WorldRules.parse(json, null);
        assertNull(p.rules(), "should have been refused: " + json);
        return p.errors();
    }

    private static String with(String rule) {
        return "{\"store\":\"world-rules\",\"v\":1,\"world\":\"closed\",\"rules\":[" + rule + "]}";
    }

    @Test
    void aGoodFileParsesAndAnswersTheQuestionsTheGameAsks() {
        WorldRules.Parsed p = WorldRules.parse(OK, "closed");
        assertTrue(p.ok(), p.errors().toString());
        WorldRules r = p.rules();
        assertEquals("closed", r.world());
        assertEquals(WorldRules.DEFAULT_WORKSHOP, r.workshop());
        assertEquals(List.of("be terse"), r.brief());
        assertTrue(r.denies(WorldEffect.TIME) && r.denies(WorldEffect.WEATHER));
        assertFalse(r.denies(WorldEffect.RECIPES_DROPS), "not named, not denied");
        assertTrue(r.baseReach());
        assertEquals("no-time", r.ruleFor(WorldEffect.WEATHER).id());
        assertEquals(List.of("own_base", "pets"), r.allowedScopes());
        assertTrue(r.allowsCommand("gamerule") && !r.allowsCommand("time") && !r.allowsCommand("say"));
        assertEquals(40, r.sha().length());
        // the economy's types are declared, checked and NOT enforced yet
        WorldRule cap = r.rules().stream().filter(x -> x.id().equals("cap")).findFirst().orElseThrow();
        assertFalse(cap.enforced());
        assertEquals("budget", cap.type().enforcedBy());
        assertEquals(12, cap.params().get("max"));
    }

    @Test
    void theTwoFallbacksAreAllowAllAndClosed() {
        assertFalse(WorldRules.ALLOW_ALL.deniesAnything(), "the allow-all world denies nothing");
        for (WorldEffect e : WorldEffect.values()) {
            assertTrue(WorldRules.CLOSED.denies(e), "CLOSED is everything the reach rule refused: " + e);
        }
        assertTrue(WorldRules.CLOSED.baseReach());
    }

    @Test
    void strictNotSilent() {
        assertTrue(errors("not json").get(0).contains("not JSON"));
        assertTrue(errors(OK.replace("\"brief\"", "\"brieff\"")).get(0).contains("unknown key 'brieff'"));
        assertTrue(errors(OK.replace("\"v\":1", "\"v\":2")).get(0).contains("newer Moddy"));
        assertTrue(errors(OK.replace("world-rules", "rules")).get(0).contains("store"));
        assertTrue(String.join(";", errors(with("{\"id\":\"x\",\"type\":\"deny_world_effect\",\"effects\":[\"thyme\"],"
                + "\"message\":{\"rule\":\"a\",\"why\":\"b\"}}"))).contains("unknown effect"));
        assertTrue(String.join(";", errors(with("{\"id\":\"x\",\"type\":\"deny_world\",\"effects\":[\"time\"]}")))
                .contains("type must be one of"));
        assertTrue(String.join(";", errors(with("{\"id\":\"x\",\"type\":\"deny_world_effect\",\"effects\":[\"time\"]}")))
                .contains("needs a message"), "a denial must say why, in words");
        assertTrue(String.join(";", errors(with("{\"id\":\"x\",\"type\":\"base_reach\",\"effects\":[\"time\"],"
                + "\"message\":{\"rule\":\"a\",\"why\":\"b\"}}"))).contains("base_reach enforces places"));
        assertTrue(String.join(";", errors(with("{\"id\":\"x\",\"type\":\"creature_cap\",\"params\":{\"max\":0}}")))
                .contains("creature_cap.max"));
        assertTrue(String.join(";", errors(with("{\"id\":\"x\",\"type\":\"price_by_size\",\"params\":{\"small\":3,\"medium\":2,\"large\":4}}")))
                .contains("cheaper than a smaller"));
        assertTrue(String.join(";", errors(with("{\"id\":\"x\",\"type\":\"allow_base_scoped\",\"params\":{\"scopes\":[\"world\"]}}")))
                .contains("scopes are own_base"));
    }

    @Test
    void oneRulePerEffectSoARefusalNamesOneRule() {
        String twice = with("{\"id\":\"a\",\"type\":\"deny_world_effect\",\"effects\":[\"time\"],"
                + "\"message\":{\"rule\":\"a\",\"why\":\"b\"}},"
                + "{\"id\":\"b\",\"type\":\"deny_world_effect\",\"effects\":[\"time\"],"
                + "\"message\":{\"rule\":\"a\",\"why\":\"b\"}}");
        assertTrue(String.join(";", errors(twice)).contains("already denied by 'a'"));
    }

    @Test
    void theFileMustBeForThisWorld() {
        WorldRules.Parsed p = WorldRules.parse(OK, "open");
        assertFalse(p.ok());
        assertTrue(p.errors().get(0).contains("this file is for 'open'"));
    }

    @Test
    void everyWorldWideCommandIsSomeEffectsSoTheBackstopCanAskTheRules() {
        for (String head : CommandCoords.WORLD_WIDE) {
            assertNotNull(WorldEffect.ofCommand(head), head + " changes the whole world but maps to no effect");
        }
        assertEquals(WorldEffect.WORLDWIDE_DATAPACKS_STANDING_RULES, WorldEffect.ofTool("set_rule"));
        assertEquals(WorldEffect.RECIPES_DROPS, WorldEffect.ofTool("change_drops"));
        assertNull(WorldEffect.ofTool("build_structure"));
    }
}
