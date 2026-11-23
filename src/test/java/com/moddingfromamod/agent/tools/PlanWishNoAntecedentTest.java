package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** plan_wish's reply to `about: 'new'` on words that point back at something. */
class PlanWishNoAntecedentTest {

    @Test
    void aFollowUpPlannedAsNewIsAskedToCheck() {
        for (String w : new String[] {"give it wings too", "make the cloud bounce me higher",
                "make the black hole pull way harder", "make it sparkle when it moves"}) {
            String note = PlanWishTool.noAntecedentNote(w, "new");
            assertTrue(note.contains("CHECK `about`") && note.contains("couldn't find it")
                    && note.contains("not change HIM"), w + " -> " + note);
        }
    }

    @Test
    void aNewWishOrAnEditIsLeftAlone() {
        assertEquals("", PlanWishTool.noAntecedentNote("a sword that shoots fire", "new"));
        assertEquals("", PlanWishTool.noAntecedentNote("make me a dragon", "new"));
        assertEquals("", PlanWishTool.noAntecedentNote("give it wings too", "pet_dragon"));
        assertEquals("", PlanWishTool.noAntecedentNote("give it wings too", null));
    }
}
