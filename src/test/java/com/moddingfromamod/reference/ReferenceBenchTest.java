package com.moddingfromamod.reference;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** an earlier issue's bench: the scoring rule. The corpus it is scored against is checked by {@code ReferenceBenchCorpusTest}. */
class ReferenceBenchTest {

    @Test
    @DisplayName("correct = a TRUSTED reading whose #1 colour a person would name")
    void scoring() {
        var s = ReferenceBench.score(List.of("tan/orange", "green", "blue"), true,
                List.of("tan/orange", "green"), List.of("tan/orange", "green", "red"));
        assertTrue(s.correct());
        assertEquals(0.67, s.overlap(), 1e-9, "two of the top three are acceptable");
        assertFalse(ReferenceBench.score(List.of("blue"), true, List.of("white"),
                List.of("white")).correct(), "Polar bear measured blue is wrong");
        // An untrusted reading never reaches a wish, so it is never counted as right.
        assertFalse(ReferenceBench.score(List.of("white"), false, List.of("white"),
                List.of("white")).correct());
        assertFalse(ReferenceBench.score(List.of(), true, List.of("white"),
                List.of("white")).correct());
    }

}
