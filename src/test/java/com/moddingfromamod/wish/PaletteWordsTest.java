package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The palette shape the scorer reads, and the colour rule that keeps it material-agnostic. */
class PaletteWordsTest {

    @Test
    @DisplayName("a colour is a whole leading segment, never a substring")
    void aColourIsAWholeSegment() {
        assertEquals("purple", PaletteWords.colourOf("minecraft:purple_concrete"));
        assertEquals("green", PaletteWords.colourOf("minecraft:green_stained_glass"));
        assertEquals("gold", PaletteWords.colourOf("minecraft:gold_block"));
        // TWO-WORD COLOURS FIRST, or light_blue reads as blue and two different trains look alike.
        assertEquals("light_blue", PaletteWords.colourOf("minecraft:light_blue_wool"));
        // AND NOT A SUBSTRING: redstone is not red. Same whole-word rule MeatFilter needed for
        // `cod` against `cocoa_beans` — without it almost every block has a colour in it somewhere.
        assertEquals("redstone_lamp", PaletteWords.colourOf("minecraft:redstone_lamp"));
    }

    @Test
    @DisplayName("a block with no colour keeps its own name, so it is still scoreable")
    void anUncolouredBlockKeepsItsName() {
        assertEquals("glowstone", PaletteWords.colourOf("minecraft:glowstone"));
        // A blockstate is not part of the name, or `controls[facing=east]` never matches anything.
        assertEquals("controls", PaletteWords.colourOf("create:controls[facing=east]"));
    }

    @Test
    @DisplayName("the palette is a list of name:percent, biggest share first")
    void theShapeIsWhatTheScorerReads() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("minecraft:gold_block", 20);
        counts.put("minecraft:purple_concrete", 70);
        counts.put("minecraft:glowstone", 10);
        List<String> pal = PaletteWords.of(counts);
        assertEquals(List.of("purple:70", "gold:20", "glowstone:10"), pal,
                "score-continuity's palette_colours splits on the LAST colon and sorts by share; "
                        + "a Map of {id: fraction} parsed every share as 0 and matched nothing");
    }

    @Test
    @DisplayName("materials of the same colour are ONE entry, so a wool/concrete swap is not a loss")
    void colourAggregatesAcrossMaterials() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("minecraft:purple_concrete", 30);
        counts.put("minecraft:purple_wool", 30);
        counts.put("minecraft:gold_block", 40);
        List<String> pal = PaletteWords.of(counts);
        // 60% purple beats 40% gold, which only works if the two purples were added together.
        assertEquals("purple:60", pal.get(0),
                "arm B choosing purple_wool where arm A chose purple_concrete must not read as a "
                        + "lost property — that punishes the stranger for having taste");
        assertTrue(pal.contains("gold:40"));
    }

    @Test
    @DisplayName("an empty design has an empty palette rather than a division by zero")
    void emptyIsEmpty() {
        assertEquals(List.of(), PaletteWords.of(new LinkedHashMap<>()));
    }
}
