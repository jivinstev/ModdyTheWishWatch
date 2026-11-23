package com.moddingfromamod.runtime.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a re-authored datapack stopped doing — the class with no appearance, so nothing else can see
 * it.
 *
 * <p>Every case is taken from the real {@code raincod} ladder, because the failure is invisible in
 * the one place a child would look: a rule that stopped happening is identical, from where he is
 * standing, to a rule he never asked for.
 */
class DatapackDriftTest {

    /** Rung 4 as a stranger really left it: cod, only at night, exploding on landing. */
    private static final String RUNG4 = """
            desc "rains real cod at night — and now every cod explodes when it lands"
            function rain()
                /execute store result score #t cr_day run time query daytime
                /execute if score #t cr_day matches 13000..23000 run summon minecraft:cod ~ ~40 ~
            end
            function explodecheck()
                /particle minecraft:explosion_emitter ~ ~ ~ 0 0 0 0 1 force
                /playsound minecraft:entity.generic.explode master @a ~ ~ ~ 2 1
                /kill @e[type=minecraft:item,distance=..3]
            end
            """;

    /** …and rung 5 as the same stranger rewrote it: chickens added, the rest gone. */
    private static final String RUNG5_LOSSY = """
            desc "rains cod and chickens"
            function rain()
                /summon minecraft:cod ~ ~40 ~
                /summon minecraft:chicken ~ ~40 ~
            end
            """;

    // ================= the failure it exists for =================

    @Test
    @DisplayName("a rewrite that drops the night check and the explosion is refused")
    void theMeasuredLossIsCaught() {
        String note = DatapackDrift.between(RUNG4, RUNG5_LOSSY,
                "can it rain chickens as well as the fish");
        assertFalse(note.isEmpty(), "this is the exact rewrite the ladder measured");
        // The explosion, by the ids that ARE it.
        assertTrue(note.contains("minecraft:explosion_emitter"), note);
        // The night condition, which has no id of its own — it lived in a scoreboard, and the
        // command verb is the only mechanical trace of it. This is why commands are a marker.
        assertTrue(note.contains("/scoreboard") || note.contains("/time")
                        || note.contains("explodecheck"),
                "the night check and the exploding routine must be named somehow: " + note);
        assertTrue(note.contains("WHOLE program"), "and how to fix it: " + note);
    }

    @Test
    @DisplayName("a whole named routine disappearing is reported")
    void aLostFunctionIsNamed() {
        String note = DatapackDrift.between(RUNG4, RUNG5_LOSSY, "add chickens");
        assertTrue(note.contains("explodecheck"),
                "a function that vanished is a behaviour that vanished: " + note);
    }

    // ================= what must NOT be refused =================

    @Test
    @DisplayName("adding to the script is not dropping from it")
    void aGoodEditIsSilent() {
        String grown = RUNG4 + "\nfunction chickens()\n    /summon minecraft:chicken ~ ~40 ~\nend\n";
        assertEquals("", DatapackDrift.between(RUNG4, grown, "can it rain chickens too"));
    }

    @Test
    @DisplayName("a brand-new feature has nothing to lose")
    void aNewFeatureIsNotRefused() {
        assertEquals("", DatapackDrift.between(null, RUNG5_LOSSY, "make it rain cod"));
        assertEquals("", DatapackDrift.between("", RUNG5_LOSSY, "make it rain cod"));
    }

    @Test
    @DisplayName("when he asks for something to STOP, stopping it is the wish")
    void aRequestedRemovalIsNotDrift() {
        assertEquals("", DatapackDrift.between(RUNG4, RUNG5_LOSSY,
                "get rid of the exploding, i dont like it"));
        assertEquals("", DatapackDrift.between(RUNG4, RUNG5_LOSSY, "stop the exploding"));
        // "only" counts, because "can it only do it at night" really is asking for it to stop the
        // rest of the time — and refusing that would fire on a correct edit.
        assertTrue(DatapackDrift.removalRequested("can it only do it at night"));
    }

    @Test
    @DisplayName("the removal list is narrow ON PURPOSE, and this is what that costs")
    void theBiasIsTowardReporting() {
        // "take the exploding OFF" is a real way a child says it and is NOT on the list, so this
        // correct edit gets refused once. That is the deliberate trade, not an oversight:
        //
        //   a false refusal costs ONE redirect — the gate yields on the retry, BuildGate-style
        //   a missed report costs him a wish he already made, and he cannot see that it is gone
        //
        // Widening the list is how the colour gate swallowed "give him tiny black wings". Bare
        // "off" would be worst of all: "set it off", "show it off", "far off".
        assertFalse(DatapackDrift.removalRequested("take the exploding off"),
                "if this ever becomes true, check it did not also start matching \"set it off\"");
        assertFalse(DatapackDrift.between(RUNG4, RUNG5_LOSSY, "take the exploding off").isEmpty(),
                "so this correct edit is refused once, and goes through on the second attempt");
    }

    @Test
    @DisplayName("saying the same thing differently is not a loss")
    void aRewordedScriptIsNotDrift() {
        // Same ids, same commands, same functions — different whitespace and comments. A textual
        // diff would scream; this must not, or every tidy-up is refused.
        String reworded = RUNG4.replace("desc \"rains real cod at night", "desc \"cod rain at night")
                .replace("    ", "  ") + "\n# tidied\n";
        assertEquals("", DatapackDrift.between(RUNG4, reworded, "make it neater"));
    }

    @Test
    @DisplayName("an unreadable or empty previous script refuses nothing")
    void nothingIsRefusedOnAGuess() {
        assertEquals("", DatapackDrift.between(null, "anything", "a wish"));
        assertEquals("", DatapackDrift.between("   ", "anything", "a wish"));
    }
}
