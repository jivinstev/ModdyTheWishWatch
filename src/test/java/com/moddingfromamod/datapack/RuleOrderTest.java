package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RuleOrderTest {

    private static final Set<String> LIBRARY = Set.of("lib_a", "lib_b", "lib_c", "lib_d", "lib_e",
            "lib_f", "lib_g", "lib_h", "lib_i");

    @Test
    void hisRulesComeBeforeTheLibraryNewestFirst() {
        Map<String, Long> when = Map.of("rain", 100L, "zombies_big", 300L, "sur_rule_7", 200L);
        List<String> ids = List.of("rain", "sur_rule_7", "zombies_big",
                "lib_a", "lib_b", "lib_c", "lib_d", "lib_e", "lib_f", "lib_g", "lib_h", "lib_i");
        List<String> out = RuleOrder.order(ids, id -> !LIBRARY.contains(id), when::get);
        assertEquals(List.of("zombies_big", "sur_rule_7", "rain"), out.subList(0, 3),
                "his three rules, newest first, before any of the nine library packs");
        assertEquals(12, out.size());
    }

    @Test
    void theNineLibraryPacksCanNoLongerCrowdOutHisNewestRule() {
        // took the starter library to nine datapacks; Surroundings shows ten. The rule he just
        // made must be in the first ten whatever its name sorts as.
        List<String> ids = new java.util.ArrayList<>(List.of("aaa_first_by_name", "zzz_last_by_name"));
        ids.addAll(LIBRARY.stream().sorted().toList());
        List<String> out = RuleOrder.order(ids, id -> !LIBRARY.contains(id),
                id -> id.equals("aaa_first_by_name") ? 999L : 1L);
        assertEquals("aaa_first_by_name", out.get(0));
        assertEquals("zzz_last_by_name", out.get(1));
    }

    @Test
    void duplicatesAreDroppedAndTiesAreDeterministic() {
        List<String> out = RuleOrder.order(List.of("b", "a", "b", "lib_a"),
                id -> !LIBRARY.contains(id), id -> 5L);
        assertEquals(List.of("b", "a", "lib_a"), out);
    }
}
