package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class AfterFinishTest {

    @Test
    void nothingIsRefusedBeforeTheFinish() {
        assertNull(AfterFinish.refusal("sculpt_creature", false));
        assertNull(AfterFinish.refusal("run_command", false));
    }

    @Test
    void afterTheFinishEveryWorldChangingToolRefusesNamingFinishWish() {
        for (String t : PlanFirst.NEEDS_PLAN) {
            String r = AfterFinish.refusal(t, true);
            assertNotNull(r, t + " must refuse after finish_building");
            assertTrue(r.contains("finish_wish"));
        }
        // what the archive saw after a finish: all refused now
        for (String t : new String[] {"run_command", "notify_player", "photograph", "finish_building"}) {
            assertNotNull(AfterFinish.refusal(t, true), t);
        }
    }

    @Test
    void finishingTeachingAndReadingStillRun() {
        for (String t : new String[] {"finish_wish", "add_template", "inspect_creature", "await_wish"}) {
            assertNull(AfterFinish.refusal(t, true), t);
        }
        for (String t : AfterFinish.ALLOWED) {
            assertTrue(!PlanFirst.NEEDS_PLAN.contains(t) && !PlanFirst.EXEMPT.contains(t)
                    || "add_template".equals(t), t + " is allowed after finish but changes the world");
        }
    }

    // ── polish in place, never replace ─────────────────────────────────────

    private static final List<String> MADE = List.of("spikor", "slime_bow_place", "spikor_fire");

    private static JsonObject a(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static boolean runs(String tool, String json) {
        return AfterFinish.refusal(tool, true, a(json), MADE) == null;
    }

    @Test
    void hisCreatureMayBePolishedForWhatItDoes() {
        assertTrue(runs("modify_mob", "{scope:'one', target:'@spikor', follow:true}"));
        assertTrue(runs("modify_mob", "{scope:'one', target:'spikor', friendly:true, speed:0.3}"));
        assertTrue(runs("modify_mob", "{scope:'one', target:'wishmob_spikor', glow:'gold'}"));
        assertTrue(runs("modify_mob", "{scope:'one', target:'@spikor2', health:80}"));
        assertNotNull(AfterFinish.polish("modify_mob", a("{scope:'one', target:'@trex_42'}"),
                List.of("trex_42")), "an id that ends in a number");
    }

    @Test
    void neverItsSizeOrASaddleWhichRebuildIt() {
        assertTrue(!runs("modify_mob", "{scope:'one', target:'@spikor', scale:2}"));
        assertTrue(!runs("modify_mob", "{scope:'one', target:'@spikor', rideable:true}"));
    }

    @Test
    void neverSomebodyElsesMobOrMoreThanOne() {
        assertTrue(!runs("modify_mob", "{scope:'one', target:'zombie', follow:true}"), "a type word");
        assertTrue(!runs("modify_mob", "{scope:'one', target:'@last', follow:true}"), "@last");
        assertTrue(!runs("modify_mob", "{scope:'one', follow:true}"), "the nearest anything");
        assertTrue(!runs("modify_mob", "{scope:'group', target:'spikor', follow:true}"));
        assertTrue(!runs("modify_mob", "{scope:'one', target:'spikor', count:3, follow:true}"));
    }

    @Test
    void hisItemMayBeChangedWhereHeHoldsItButNeverHandedAgain() {
        assertTrue(runs("make_item", "{start_from:'slime_bow_place', colour:'lime'}"));
        assertTrue(!runs("make_item", "{start_from:'slime_bow_place', count:3}"), "more of it");
        assertTrue(!runs("make_item", "{start_from:'slime_bow_place', as_new:true}"), "a second one");
        assertTrue(!runs("make_item", "{start_from:'slime_bow_place', variants:[]}"), "a new family");
        assertTrue(!runs("make_item", "{start_from:'slime_bow_place', at:'me:front'}"), "put elsewhere");
        assertTrue(!runs("make_item", "{name:'Magic Bread', effect:'heal'}"), "a new item");
        assertTrue(!runs("make_item", "{start_from:'someone_elses_sword'}"), "not this wish's");
    }

    @Test
    void itsOwnRuleMayBeRewrittenAndNoOther() {
        assertTrue(runs("author_datapack", "{feature_id:'spikor_fire', cbscript:'...'}"));
        assertTrue(!runs("author_datapack", "{feature_id:'something_new', cbscript:'...'}"));
    }

    @Test
    void whatTheArchiveSawAfterAFinishStillRefuses() {
        // the duplicate give, the duplicate spawn, the second volcano, the kill, the extra gift
        assertTrue(!runs("run_command", "{command:['wishdatapack slime_bow_place give']}"));
        assertTrue(!runs("build_structure", "{id:'giant_ocean_volcano'}"));
        assertTrue(!runs("give_items", "{item:'minecraft:oak_boat'}"));
        assertTrue(!runs("sculpt_creature", "{id:'spikor'}"), "a new shape is a rebuild");
        assertTrue(!runs("paint_creature", "{id:'spikor', colour:'red'}"), "a repaint is a rebuild");
    }

    @Test
    void aWishThatMadeNothingHasNothingToPolish() {
        assertNull(AfterFinish.polish("modify_mob", a("{scope:'one', target:'spikor'}"), List.of()));
    }
}
