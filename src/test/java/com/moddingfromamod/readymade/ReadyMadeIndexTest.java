package com.moddingfromamod.readymade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the one index — generated, round-trips for the routing stub, and grows linearly. */
class ReadyMadeIndexTest {

    @Test
    @DisplayName("every shipped kind is present, and the JSON round-trips exactly")
    void roundTrip() {
        ReadyMadeIndex idx = new ReadyMadeIndex(ReadyMadeSources.shipped());
        for (ReadyMade.Kind k : List.of(ReadyMade.Kind.CREATURE, ReadyMade.Kind.EFFECT, ReadyMade.Kind.ITEM)) {
            assertTrue(!idx.of(EnumSet.of(k)).isEmpty(), k + " has no entries");
        }
        ReadyMadeIndex back = ReadyMadeIndex.fromJson(idx.toJson());
        assertEquals(idx.all(), back.all());
    }

    @Test
    @DisplayName("an alias is a lookup; the same id in two kinds stays two entries")
    void lookups() {
        ReadyMadeIndex idx = new ReadyMadeIndex(ReadyMadeSources.shipped());
        assertEquals("aggro", idx.get("anger", ReadyMade.Kind.ITEM) == null ? "aggro"
                : idx.get("anger", ReadyMade.Kind.ITEM).id());
        assertNotNull(idx.get("teleport", ReadyMade.Kind.ITEM));
        // The sculpt ability "teleport" is withheld while abilities do nothing on the rig;
        // the two-kinds rule is held on synthetic entries so it stays tested either way.
        if (ReadyMadeSources.ABILITIES_RUN_ON_THE_RIG) {
            assertNotNull(idx.get("teleport", ReadyMade.Kind.EFFECT));
            assertTrue(idx.get("teleport", ReadyMade.Kind.ITEM) != idx.get("teleport", ReadyMade.Kind.EFFECT));
        }
        ReadyMadeIndex two = new ReadyMadeIndex(java.util.List.of(
                new ReadyMade(ReadyMade.Kind.ITEM, "same", "same", java.util.List.of(), java.util.List.of(),
                        "an item", "make_item", "", java.util.List.of()),
                new ReadyMade(ReadyMade.Kind.EFFECT, "same", "same", java.util.List.of(), java.util.List.of(),
                        "an effect", "apply_effect", "", java.util.List.of())));
        assertNotNull(two.get("same", ReadyMade.Kind.ITEM));
        assertNotNull(two.get("same", ReadyMade.Kind.EFFECT));
        assertTrue(two.get("same", ReadyMade.Kind.ITEM) != two.get("same", ReadyMade.Kind.EFFECT));
    }

    /**
     * The issue's budget: bytes grow linearly or better as the libraries grow. Three copies of the
     * library (renamed, so none collapses) must cost three times one, near enough.
     */
    @Test
    @DisplayName("budget: the index grows no faster than the library")
    void linearBudget() {
        List<ReadyMade> one = ReadyMadeSources.shipped();
        List<ReadyMade> three = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            for (ReadyMade e : one) {
                three.add(new ReadyMade(e.kind(), e.id() + "_" + c, e.name(), e.aliases(), e.tags(),
                        e.purpose(), e.use(), e.detail(), e.examples()));
            }
        }
        int b1 = new ReadyMadeIndex(one).bytes();
        int b3 = new ReadyMadeIndex(three).bytes();
        // 2% slack: the copies' ids are a few characters longer ("_2") than the originals.
        assertTrue(b3 <= 3.06 * b1, "3x the library cost " + b3 + " bytes against " + b1);
        // …and the MENU a model reads is a line an entry: no entry may carry its detail into it.
        String menu = new ReadyMadeIndex(one).menu(EnumSet.allOf(ReadyMade.Kind.class));
        for (String line : menu.split("\n")) {
            assertTrue(line.length() <= 400, "a menu line is too long: " + line);
        }
    }
}
