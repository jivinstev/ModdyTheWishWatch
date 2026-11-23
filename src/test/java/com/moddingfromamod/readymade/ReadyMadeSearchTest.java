package com.moddingfromamod.readymade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the keyword ranking — the labelled fallback for find_ready_made, never a filter. */
class ReadyMadeSearchTest {

    private static final List<ReadyMade> SHIPPED = ReadyMadeSources.shipped();

    private static String top(String text) {
        var hits = ReadyMadeSearch.rank(text, SHIPPED, 5);
        return hits.isEmpty() ? "" : hits.get(0).entry().kind().letter + ":" + hits.get(0).entry().id();
    }

    @Test
    @DisplayName("a name or an alias outranks a shared word")
    void namesWin() {
        assertEquals("d:aggro", top("a pearl that makes the zombie fight the cow"));
        assertEquals("c:breath_cone", top("breathes ice"));
        assertEquals("b:pet_dragon", top("a pet dragon"));
    }

    @Test
    @DisplayName("stop words alone match nothing")
    void stopWordsAreSilent() {
        assertTrue(ReadyMadeSearch.rank("can you make me a thing that i want", SHIPPED, 5).isEmpty());
    }

    @Test
    @DisplayName("stems: breathes / breathing / breathe read alike")
    void stems() {
        assertEquals(ReadyMadeSearch.words("breathes"), ReadyMadeSearch.words("breathing"));
        assertEquals(ReadyMadeSearch.words("breathe"), ReadyMadeSearch.words("breathes"));
        assertEquals(List.of("pony"), ReadyMadeSearch.words("ponies"));
    }

    @Test
    @DisplayName("one letter off still finds a name (a child's spelling)")
    void fuzzy() {
        assertEquals(1, ReadyMadeSearch.edits("dragn", "dragon"));
        assertEquals(2, ReadyMadeSearch.edits("dog", "dragon"));
        assertEquals("b:pet_dragon", top("a pet dragn"));
    }

    @Test
    @DisplayName("#1674: a made-up effect id names the one it meant, or nothing")
    void closestNamesWhatAnInventedIdMeant() {
        List<ReadyMade> items = new ReadyMadeIndex(ReadyMadeSources.shipped())
                .of(java.util.EnumSet.of(ReadyMade.Kind.ITEM));
        // Spark's real inventions 's (d) arm, each one a catalogue effect by another name.
        assertEquals(List.of("summon"), ReadyMadeSearch.closest("spawn", items));
        assertEquals(List.of("aggro"), ReadyMadeSearch.closest("anger", items));
        assertEquals(List.of("outcome_table"), ReadyMadeSearch.closest("random_item", items));
        assertEquals(List.of("status"), ReadyMadeSearch.closest("night_vision", items));
        // Silent when only part of the guess matches, or nothing does: a near miss is worse than none.
        assertEquals(List.of(), ReadyMadeSearch.closest("weather_clear", items));
        assertEquals(List.of(), ReadyMadeSearch.closest("time_night", items));
        assertEquals(List.of(), ReadyMadeSearch.closest("invalid_effect_xyz", items));
        assertEquals(List.of(), ReadyMadeSearch.closest("polymorph", items));
        assertEquals(List.of(), ReadyMadeSearch.closest("", items));
    }
}
