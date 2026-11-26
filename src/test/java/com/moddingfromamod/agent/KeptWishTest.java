package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.world.Landing;
import org.junit.jupiter.api.Test;

/** #70: a Magician wish a world keeps does its work and places nothing. */
class KeptWishTest {

    private static JsonObject j(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void onlyANewThingWithAReplayIsKept() {
        for (String k : new String[]{"creature", "building", "event", "item"}) {
            assertEquals(Landing.KEPT, KeptWish.decide(k, true, Landing.KEPT), k);
        }
        assertEquals(Landing.LANDS, KeptWish.decide("picture", true, Landing.KEPT), "a picture lands");
        assertEquals(Landing.LANDS, KeptWish.decide("other", true, Landing.KEPT), "no recipe places it");
        assertEquals(Landing.LANDS, KeptWish.decide("creature", false, Landing.KEPT), "an edit lands where it stands");
        assertEquals(Landing.LANDS, KeptWish.decide("creature", true, Landing.LANDS), "the default");
        assertEquals(Landing.KEPT, KeptWish.decide("creature", true, Landing.KEPT_AS_ITEM), "only an item is real");
        assertEquals(Landing.KEPT_AS_ITEM, KeptWish.decide("item", true, Landing.KEPT_AS_ITEM));
    }

    @Test
    void placingIsRefusedAndTheWorkIsNot() {
        assertNotNull(KeptWish.refusal("sculpt_creature", j("{\"id\":\"d\"}"), Landing.KEPT), "spawn defaults to true");
        assertNull(KeptWish.refusal("sculpt_creature", j("{\"id\":\"d\",\"spawn\":false}"), Landing.KEPT));
        assertNotNull(KeptWish.refusal("build_structure", j("{\"id\":\"b\"}"), Landing.KEPT));
        assertNull(KeptWish.refusal("build_structure", j("{\"id\":\"b\",\"build\":false}"), Landing.KEPT));
        assertNotNull(KeptWish.refusal("stage_event", j("{\"name\":\"e\",\"now\":true}"), Landing.KEPT));
        assertNull(KeptWish.refusal("stage_event", j("{\"name\":\"e\",\"now\":false}"), Landing.KEPT));
        assertNotNull(KeptWish.refusal("make_item", j("{\"name\":\"x\",\"at\":\"me:front\"}"), Landing.KEPT));
        assertNull(KeptWish.refusal("make_item", j("{\"name\":\"x\"}"), Landing.KEPT));
        assertNotNull(KeptWish.refusal("paint_surface", j("{}"), Landing.KEPT));
        assertNull(KeptWish.refusal("add_template", j("{\"commands\":[\"wishmob spawn d\"]}"), Landing.KEPT),
                "teaching the replay is the work");
        assertNull(KeptWish.refusal("plan_wish", j("{}"), Landing.KEPT));
        assertNull(KeptWish.refusal("sculpt_creature", j("{\"id\":\"d\"}"), Landing.LANDS), "nothing kept: nothing refused");
    }

    @Test
    void aPlacingCommandIsRefusedThroughExecute() {
        assertNotNull(KeptWish.refusal("run_command", j("{\"command\":[\"summon pig ~ ~ ~\"]}"), Landing.KEPT));
        assertNotNull(KeptWish.refusal("run_command", j("{\"command\":[\"time set day\",\"execute as @p at @s run setblock ~ ~ ~ stone\"]}"),
                Landing.KEPT));
        assertNotNull(KeptWish.refusal("run_command", j("{\"command\":\"wishmob spawn d\"}"), Landing.KEPT));
        assertNotNull(KeptWish.refusal("run_command", j("{\"command\":[\"execute as @a run summon minecraft:pig ~ ~ ~\"]}"),
                Landing.KEPT), "execute as @a run summon: parsed through the chain");
        assertNotNull(KeptWish.refusal("run_command", j("{\"command\":[\"execute at @s positioned ~ ~1 ~ run execute as @s run minecraft:setblock ~ ~ ~ stone\"]}"),
                Landing.KEPT), "a nested chain, a namespaced root");
        assertNotNull(KeptWish.refusal("run_command", j("{\"command\":[\"function mypack:build_tower\"]}"), Landing.KEPT),
                "a function may place anything: refused");
        assertNotNull(KeptWish.refusal("run_command", j("{\"command\":[\"execute as @s run function mypack:build_tower\"]}"),
                Landing.KEPT));
        assertNull(KeptWish.refusal("run_command", j("{\"command\":[\"execute if entity @s run time set day\"]}"), Landing.KEPT));
        assertNull(KeptWish.refusal("run_command", j("{\"command\":[\"time set day\"]}"), Landing.KEPT));
        assertNull(KeptWish.refusal("run_command", j("{\"command\":[\"say summon\"]}"), Landing.KEPT), "a word, not a command");
        assertNotNull(KeptWish.refusal("run_command", j("{\"command\":[\"wishdatapack sword give\"]}"), Landing.KEPT));
        assertNull(KeptWish.refusal("run_command", j("{\"command\":[\"wishdatapack sword give\"]}"), Landing.KEPT_AS_ITEM),
                "the real item is handed over by its own give");
        assertNotNull(KeptWish.refusal("run_command", j("{\"command\":[\"wishdatapack sword trigger\"]}"), Landing.KEPT_AS_ITEM));
    }

    @Test
    void theBriefSaysPlaceNothingAndTeachIt() {
        assertEquals("", KeptWish.brief(Landing.LANDS));
        String b = KeptWish.brief(Landing.KEPT);
        assertTrue(b.contains("PLACE NOTHING") && b.contains("add_template") && b.contains("finish_wish"), b);
    }
}
