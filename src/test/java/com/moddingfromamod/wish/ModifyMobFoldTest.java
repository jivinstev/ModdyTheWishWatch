package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**, the real-mob row: a modify_mob change in a button is folded into wishsummon, never lost. */
class ModifyMobFoldTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    @DisplayName("the archived snow-golem button: summon + a hand-written modify_mob fold into one wishsummon, the tag kept")
    void theArchivedButton() {
        var r = ModifyMobFold.fold(List.of(
                "summon minecraft:snow_golem ~ ~ ~ {Tags:[\"snow_pet\"]}",
                "modify_mob scope:one target:snow_golem follow:true persistent:true"));
        assertNull(r.refusal(), r.refusal());
        assertEquals(List.of(
                "wishsummon minecraft:snow_golem {\"follow\":true,\"persistent\":true}",
                "tag @e[type=minecraft:snow_golem,sort=nearest,limit=1] add snow_pet"), r.commands());
        assertEquals(1, r.notes().size(), r.notes().toString());
        assertTrue(CommandGuard.isAllowed(r.commands().get(0)) && CommandGuard.isAllowed(r.commands().get(1)),
                "the folded lines must pass the template guard");
    }

    @Test
    @DisplayName("JSON form, a handle target, a position kept, and two changes to one summon merged")
    void shapes() {
        var r = ModifyMobFold.fold(List.of(
                "summon wolf ~2 ~ ~",
                "say hi",
                "modify_mob @wolf {\"scope\":\"one\",\"follow\":true}",
                "modify_mob target=@wolf2 scale=2.5"));
        assertNull(r.refusal(), r.refusal());
        assertEquals(List.of("execute positioned ~2 ~ ~ run wishsummon wolf {\"follow\":true,\"scale\":2.5}",
                "say hi"), r.commands());
    }

    @Test
    @DisplayName("a change is matched to ITS mob's summon, nearest before it; @last takes the nearest summon")
    void matching() {
        var r = ModifyMobFold.fold(List.of("summon cow ~ ~ ~", "summon pig ~ ~ ~",
                "modify_mob target:cow scale:3", "summon sheep ~ ~ ~", "modify_mob target:@last glow:red"));
        assertNull(r.refusal(), r.refusal());
        assertEquals(List.of("wishsummon cow {\"scale\":3}", "summon pig ~ ~ ~",
                "wishsummon sheep {\"glow\":\"red\"}"), r.commands());
    }

    @Test
    @DisplayName("no summon to ride on: refused, naming the wishsummon line that makes the mob WITH the change")
    void noSummonRefused() {
        var r = ModifyMobFold.fold(List.of("modify_mob scope:one target:@snow_golem follow:true"));
        assertNotNull(r.refusal());
        assertTrue(r.refusal().contains("wishsummon minecraft:snow_golem {\"follow\":true}"), r.refusal());
    }

    @Test
    @DisplayName("summon NBT wishsummon cannot carry: refused with the line to write, nothing half-folded")
    void otherNbtRefused() {
        var r = ModifyMobFold.fold(List.of("summon cat ~ ~ ~ {CustomName:'\"Tom\"'}", "modify_mob target:cat friendly:true"));
        assertNotNull(r.refusal());
        assertTrue(r.refusal().contains("wishsummon cat {\"friendly\":true}"), r.refusal());
    }

    @Test
    @DisplayName("a list with no modify_mob is returned untouched")
    void untouched() {
        List<String> in = List.of("summon cow ~ ~ ~ {NoAI:1b}", "give @s cake");
        var r = ModifyMobFold.fold(in);
        assertNull(r.refusal());
        assertEquals(in, r.commands());
        assertTrue(r.notes().isEmpty());
    }

    @Test
    @DisplayName("TeachDraft: a run's summon then modify_mob tool call drafts one wishsummon carrying the spec")
    void teachDraftFolds() {
        TeachDraft.Draft d = TeachDraft.of(List.of("summon minecraft:snow_golem ~ ~ ~"), null,
                CommandGuard::isAllowed, List.of(new ModifyMobFold.Change("@snow_golem",
                        json("{\"follow\":true,\"stay\":8}"))));
        assertEquals(List.of("wishsummon minecraft:snow_golem {\"follow\":true,\"stay\":8}"), d.commands());
        assertTrue(d.warnings().isEmpty(), d.warnings().toString());
    }

    @Test
    @DisplayName("TeachDraft: a change with no summon in the run is a warning, the draft otherwise unchanged")
    void teachDraftWarns() {
        TeachDraft.Draft d = TeachDraft.of(List.of("effect give @s night_vision 999 0 true"), null,
                CommandGuard::isAllowed, List.of(new ModifyMobFold.Change("cow", json("{\"scale\":3}"))));
        assertEquals(List.of("effect give @s night_vision 999 0 true"), d.commands());
        assertTrue(d.warnings().stream().anyMatch(w -> w.contains("wishsummon minecraft:cow {\"scale\":3}")),
                d.warnings().toString());
    }
}
