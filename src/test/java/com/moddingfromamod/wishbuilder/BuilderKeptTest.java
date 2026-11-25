package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** A make a world keeps (#70): which kinds can be kept, and a kept creature's calls place nothing. */
class BuilderKeptTest {

    private static BuilderSpec cats(int count) {
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:species/cat", "", 2, List.of(),
                "friendly", count, "", "", Map.of());
    }

    @Test
    void aKeptCreatureIsSculptedAndNeverSpawned() {
        var calls = BuilderLowering.lower(cats(3), "cat_1", LibraryPresets.SHIPPED);
        assertTrue(calls.stream().anyMatch(c -> c.args().toString().contains("wishmob spawn")), "three cats: more spawns");
        var held = BuilderMake.held(calls);
        assertFalse(held.stream().anyMatch(c -> c.args().toString().contains("wishmob spawn")), "no spawns: " + held);
        var sculpts = held.stream().filter(c -> c.tool().equals("sculpt_creature")).toList();
        assertEquals(1, sculpts.size());
        assertFalse(sculpts.get(0).args().get("spawn").getAsBoolean(), "sculpted, not spawned");
        assertTrue(calls.stream().filter(c -> c.tool().equals("sculpt_creature")).findFirst().orElseThrow()
                .args().get("spawn").getAsBoolean(), "the lowering itself is untouched");
    }

    @Test
    void onlyCreaturesRidesAndItemsKeptOnTheShelfAreKept() {
        assertTrue(BuilderMake.keepable(cats(1)));
        assertTrue(BuilderMake.keepable(new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.ITEM, "", "", 2, List.of(),
                "", 1, "", "", Map.of("stock", "sword"))));
        assertFalse(BuilderMake.keepable(new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "mob:pig", "", 2,
                List.of(), "", 1, "", "", Map.of())), "a real mob is not kept on his shelf");
        for (BuilderSpec.Kind k : List.of(BuilderSpec.Kind.BUILDING, BuilderSpec.Kind.PICTURE, BuilderSpec.Kind.EVENT,
                BuilderSpec.Kind.RULE)) {
            assertFalse(BuilderMake.keepable(new BuilderSpec(BuilderSpec.CURRENT, k, "", "", 2, List.of(), "", 1, "", "",
                    Map.of())), k + " lands (not yet kept)");
        }
    }
}
