package com.moddingfromamod.event;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TitleFitTest {

    @Test
    @DisplayName("#1047: a short title is left alone")
    void aShortTitleIsLeftAlone() {
        assertArrayEquals(new String[] {"TIDAL WAVE!", ""}, TitleFit.fit("TIDAL WAVE!"));
    }

    @Test
    @DisplayName("#1047: a title too wide for the screen keeps whole words and moves the rest down")
    void aLongTitleKeepsWholeWordsAndMovesTheRestDown() {
        assertArrayEquals(new String[] {"IT'S RAINING", "CHICKENS!"}, TitleFit.fit("IT'S RAINING CHICKENS!"));
        for (String t : new String[] {"THE BIGGEST TIDAL WAVE IN THE WHOLE WORLD", "a  b   c", "METEOR SHOWER TIME NOW"}) {
            String[] f = TitleFit.fit(t);
            assertTrue(f[0].length() <= TitleFit.FITS, t + " -> " + f[0]);
            assertTrue((f[0] + " " + f[1]).trim().equals(t.trim().replaceAll("\\s+", " ")),
                    "no word is lost: " + t);
        }
    }

    @Test
    @DisplayName("#1047: one word wider than the screen is kept whole rather than cut")
    void oneEnormousWordIsKeptWhole() {
        assertArrayEquals(new String[] {"SUPERCALIFRAGILISTIC", "BOOM"}, TitleFit.fit("SUPERCALIFRAGILISTIC BOOM"));
    }
}
