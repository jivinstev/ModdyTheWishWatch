package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * When a wish is saved as a button, the child is told so in plain words — and a CHANGE to something
 * he already had reads differently from a brand-new wish, so an edit never silently "just happens".
 */
class LearnedTemplatesMessageTest {

    @Test
    void newAndUpdateReadDifferentlyAndBothPromiseItIsQuick() {
        String fresh = LearnedTemplates.savedMessage("McTrain", false);
        String edit = LearnedTemplates.savedMessage("McTrain", true);

        assertNotEquals(fresh, edit, "an edit must not read the same as a first-time save");
        assertTrue(fresh.contains("McTrain") && edit.contains("McTrain"), "both name the thing");
        for (String m : new String[]{fresh, edit}) {
            assertTrue(m.toLowerCase().contains("in a flash"),
                    "both must promise it'll be quick next time: " + m);
            // A button now has a few seconds of pillar and reveal, so "instant" would
            // make the moment read as lag.
            assertTrue(!m.toLowerCase().contains("instant"), "no 'instant' promise any more: " + m);
        }
        assertTrue(edit.toLowerCase().contains("change"), "the edit line calls out the change: " + edit);
    }
}
