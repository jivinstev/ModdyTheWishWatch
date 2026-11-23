package com.moddingfromamod.reference;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The check that exists because telling the agent did not work, twice.</b>
 *
 * <p>Every case here is taken from a real run rather than invented.
 */
class ReferencePartCheckTest {

    /** Omega Lad's real reference, and the real sculpt it produced. Twice. */
    private static final List<String> SIGMA_REF =
            List.of("head", "body", "beak", "eye", "hair", "wing", "leg");
    private static final List<String> MAN_IN_SHADES = List.of(
            "armL", "armR", "body", "browL", "browR", "collarL", "collarR", "hair", "hairswoop",
            "handL", "handR", "head", "legL", "legR", "shades", "shine", "shirt", "shoeL",
            "shoeR", "smirk1", "smirk2", "smirk3", "smirk4");

    @Test
    @DisplayName("THE MEASURED FAILURE: a penguin's reference against a man in sunglasses")
    void itCatchesTheRunThatShippedTwice() {
        var gone = ReferencePartCheck.missing(SIGMA_REF, MAN_IN_SHADES);
        assertTrue(gone.contains("beak"), "the whole point: " + gone);
        assertTrue(gone.contains("wing"), gone.toString());
        // ...and it does NOT complain about the parts a man and a penguin share, or the warning
        // fires on every sculpt ever made and stops being read.
        assertFalse(gone.contains("head"));
        assertFalse(gone.contains("body"));
        assertFalse(gone.contains("leg"));
        assertFalse(gone.contains("hair"), "the sculpt really does have hair");

        String said = ReferencePartCheck.report(SIGMA_REF, MAN_IN_SHADES, "Omega Lad");
        assertTrue(said.contains("beak"), said);
        // It must not read as "add a beak to your man in sunglasses" -- the creature is the wrong
        // KIND of thing, and saying only "you are missing a beak" invites bolting one on.
        assertTrue(said.contains("wrong KIND"), said);
    }

    @Test
    @DisplayName("a sculpt that DID build the part is silent")
    void aGoodSculptIsNotNagged() {
        var penguin = List.of("head", "body", "beakUpper", "beakLower", "wingL", "wingR",
                "footL", "footR", "eyeL", "eyeR");
        assertTrue(ReferencePartCheck.missing(SIGMA_REF, penguin).isEmpty(),
                "a real penguin was flagged, so the check would be ignored within a day");
        assertEquals("", ReferencePartCheck.report(SIGMA_REF, penguin, "Omega Lad"));
    }

    @Test
    @DisplayName("a builder's own word for the same feature counts")
    void synonymsCount() {
        // The cost of a missing synonym is one unnecessary nudge. The cost of an over-eager one is
        // a check that silently passes a creature with no beak, so the list is small on purpose.
        assertTrue(ReferencePartCheck.missing(List.of("beak"), List.of("bill", "body")).isEmpty());
        assertTrue(ReferencePartCheck.missing(List.of("wing"), List.of("flipperL")).isEmpty());
        assertTrue(ReferencePartCheck.missing(List.of("tooth"), List.of("fang1")).isEmpty());
        assertTrue(ReferencePartCheck.missing(List.of("snout"), List.of("muzzle")).isEmpty());
        // ...but an unrelated word is still a miss.
        assertEquals(List.of("beak"), ReferencePartCheck.missing(List.of("beak"),
                List.of("shades", "collarL")));
    }

    @Test
    @DisplayName("Spikor: the distinctive parts are checked and the shared ones are not")
    void itWorksOnACharacterThatIsBuiltRight() {
        var ref = List.of("head", "horn", "eye", "tooth", "snout", "body", "hair", "shell",
                "spike", "tail", "arm", "claw");
        var built = List.of("head", "hornL", "hornR", "eyeL", "eyeR", "toothL", "snout", "body",
                "maneA", "shell", "spike1", "spike2", "tail", "armL", "clawR");
        assertTrue(ReferencePartCheck.missing(ref, built).isEmpty(),
                "a faithful Spikor was flagged: " + ReferencePartCheck.missing(ref, built));
        // Drop the shell and it must say so -- that is the one that makes him a Kroak.
        var shellless = new java.util.ArrayList<>(built);
        shellless.remove("shell");
        assertEquals(List.of("shell"), ReferencePartCheck.missing(ref, shellless));
    }

    @Test
    @DisplayName("a part built as a KIT counts — or the check nags a correct Spikor")
    void kitPartsCount() {
        // SculptKits names its output `kit_<kit>`, and the tool checks the EXPANDED part list, so
        // horns asked for as {kit:horn} arrive as `kit_horn`. Taken from a real Spikor that
        // carried {kit:horn} and three {kit:spike_row} and whose hand-drawn parts named neither.
        var ref = List.of("head", "body", "horn", "spike", "shell", "tail", "claw");
        var expanded = List.of("head", "body", "shell", "tail", "clawL",
                "kit_horn", "kit_spike_row", "kit_spike_row2");
        assertTrue(ReferencePartCheck.missing(ref, expanded).isEmpty(),
                "a Spikor with real horns and spikes was flagged: "
                        + ReferencePartCheck.missing(ref, expanded));
        // The hand-drawn list ALONE -- what the archived sculpt json records -- would miss them,
        // which is why the check must run on the expanded list and not on the tool arguments.
        var handDrawnOnly = List.of("head", "body", "shell", "tail", "clawL");
        assertEquals(List.of("horn", "spike"),
                ReferencePartCheck.missing(ref, handDrawnOnly));
    }

    @Test
    @DisplayName("nothing to compare is silence, never a warning")
    void emptyInputsAreSafe() {
        assertTrue(ReferencePartCheck.missing(null, MAN_IN_SHADES).isEmpty());
        assertTrue(ReferencePartCheck.missing(SIGMA_REF, null).isEmpty());
        assertTrue(ReferencePartCheck.missing(List.of(), List.of()).isEmpty());
        assertEquals("", ReferencePartCheck.report(List.of(), MAN_IN_SHADES, "X"));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#1219 r3: the grown words are checked, with the spellings a builder uses")
    void grownWords() {
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("gill"),
                ReferencePartCheck.missing(java.util.List.of("gill", "hoof", "tentacle"),
                        java.util.List.of("hoovesFL", "tendril1", "body")),
                "hooves is a hoof and a tendril a tentacle; a missing gill is reported");
        org.junit.jupiter.api.Assertions.assertTrue(ReferencePartCheck.missing(
                java.util.List.of("mouth", "neck", "cheek"), java.util.List.of("body")).isEmpty(),
                "every face has a mouth and a neck: never the telling detail");
    }
}
