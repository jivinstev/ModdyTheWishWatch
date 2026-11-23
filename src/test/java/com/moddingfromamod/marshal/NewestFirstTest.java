package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.store.ArtifactTimes;
import org.junit.jupiter.api.Test;

/**
 * the order rule every wish history follows: newest first across every
 * kind, unknown ages after, the library's starters last; and an edit moves a thing to the front.
 */
class NewestFirstTest {

    private record Thing(String id, String kind, long when, boolean starter) {
    }

    private static List<String> ids(List<Thing> things) {
        return things.stream().map(Thing::id).toList();
    }

    @Test
    void oneSortAcrossEveryKindNotOneBlockPerKind() {
        // Listed the way the watch used to BUILD them: all items, then buildings, then creatures.
        var built = List.of(
                new Thing("old_wand", "item", 100, false),
                new Thing("new_sword", "item", 900, false),
                new Thing("castle", "build", 500, false),
                new Thing("dragon", "creature", 1000, false),
                new Thing("slime", "creature", 200, false));
        assertEquals(List.of("dragon", "new_sword", "castle", "slime", "old_wand"),
                ids(NewestFirst.sort(built, Thing::when, Thing::starter)));
    }

    @Test
    void startersComeAfterAllOfHisOwnThingsEvenTheUnknownOnes() {
        var built = List.of(
                new Thing("starter_castle", "preset", 0, true),
                new Thing("legacy_spikor", "creature", 0, false),
                new Thing("starter_rain", "preset", 0, true),
                new Thing("rocket", "build", 300, false));
        assertEquals(List.of("rocket", "legacy_spikor", "starter_castle", "starter_rain"),
                ids(NewestFirst.sort(built, Thing::when, Thing::starter)),
                "known first, then his things of unknown age, then the starters in owner order");
    }

    @Test
    void anEditMovesTheThingToTheFront() {
        var times = new ArtifactTimes.Book();
        times.touch("dragon", 1_000);
        times.touch("castle", 2_000);
        times.touch("wand", 3_000);
        List<String> ids = List.of("dragon", "castle", "wand");
        List<Thing> before = new ArrayList<>();
        ids.forEach(id -> before.add(new Thing(id, "x", times.get(id).recency(), false)));
        assertEquals(List.of("wand", "castle", "dragon"),
                ids(NewestFirst.sort(before, Thing::when)));

        times.touch("dragon", 4_000);   // "give him wings"
        assertEquals(1_000, times.get("dragon").createdAt(), "an edit never moves the making");
        List<Thing> after = new ArrayList<>();
        ids.forEach(id -> after.add(new Thing(id, "x", times.get(id).recency(), false)));
        assertEquals(List.of("dragon", "wand", "castle"), ids(NewestFirst.sort(after, Thing::when)));
    }

    @Test
    void equalTimesKeepTheCallersOrder() {
        var built = List.of(new Thing("a", "x", 5, false), new Thing("b", "x", 5, false),
                new Thing("c", "x", 0, false), new Thing("d", "x", 0, false));
        assertEquals(List.of("a", "b", "c", "d"), ids(NewestFirst.sort(built, Thing::when)));
    }

    @Test
    void eachItemsRecencyIsReadOnceNotOnEveryComparison() {
        java.util.List<Integer> items = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            items.add(i);
        }
        int[] reads = {0};
        java.util.List<Integer> out = NewestFirst.sort(items, t -> {
            reads[0]++;
            return t % 7 == 0 ? 0 : 1000L + (t * 37L) % 500;
        });
        org.junit.jupiter.api.Assertions.assertEquals(200, reads[0],
                "a recency that looks itself up must be paid once per item (2026-10-31)");
        org.junit.jupiter.api.Assertions.assertEquals(200, out.size());
        for (int k = 1; k < out.size(); k++) {
            long a = out.get(k - 1) % 7 == 0 ? 0 : 1000L + (out.get(k - 1) * 37L) % 500;
            long b = out.get(k) % 7 == 0 ? 0 : 1000L + (out.get(k) * 37L) % 500;
            org.junit.jupiter.api.Assertions.assertTrue(a > 0 && b > 0 ? a >= b : !(a <= 0 && b > 0),
                    "out of order at " + k);
        }
    }
}
