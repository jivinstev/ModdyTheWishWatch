package com.moddingfromamod.lite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Gate A for the mint mark — the rules, none of which need a game. */
class MintMarksTest {

    /**
     * <b>The one that matters most, and the one an implementation gets wrong by being helpful.</b>
     * Every world that predates the mark has no record, and that is most of them. Answering MOD
     * there would be a guess dressed as a fact — and wrong for exactly the worlds the mark exists
     * for, the ones already minted on the substituted path.
     */
    @Test
    void aWorldThatPredatesTheMarkIsUNKNOWNRatherThanGuessedAt() {
        MintMarks m = new MintMarks();
        for (String kind : MintMarks.MARKED) {
            assertEquals(MintMarks.Mode.UNKNOWN, m.modeOf(kind), kind);
        }
        assertFalse(m.anyVanilla());
        assertTrue(m.describe().contains("UNKNOWN"), m.describe());
    }

    @Test
    void mintingRecordsThePathItWasMintedOn() {
        MintMarks m = new MintMarks();
        assertTrue(m.record("item", true), "the first record is a change");
        assertEquals(MintMarks.Mode.VANILLA, m.modeOf("item"));
        assertFalse(m.record("item", true), "minting the same way again changes nothing");
        MintMarks mod = new MintMarks();
        mod.record("item", false);
        assertEquals(MintMarks.Mode.MOD, mod.modeOf("item"));
    }

    /**
     * A child's world outlives a flag. He plays a session on one path and a session on the other,
     * and the world then holds some of each — so a mark that overwrote would say the world was
     * purely whatever it last saw, which is the one answer a migration must not be given.
     */
    @Test
    void aWorldMintedBothWaysSaysSoRatherThanTakingTheLastOne() {
        MintMarks m = new MintMarks();
        m.record("species", false);
        assertTrue(m.record("species", true), "the second path is a change");
        assertEquals(MintMarks.Mode.MIXED, m.modeOf("species"));
        assertFalse(m.record("species", false), "mixed stays mixed — the old things are still there");
        assertFalse(m.record("species", true));
        assertEquals(MintMarks.Mode.MIXED, m.modeOf("species"));
        assertTrue(m.anyVanilla(), "a mixed world still holds substituted things");
    }

    @Test
    void itSurvivesARoundTrip() {
        MintMarks before = new MintMarks();
        before.record("item", true);
        before.record("species", false);
        before.record("painting", true);
        before.record("painting", false);

        MintMarks after = new MintMarks();
        assertEquals(3, after.load(before.toJson()));
        assertEquals(MintMarks.Mode.VANILLA, after.modeOf("item"));
        assertEquals(MintMarks.Mode.MOD, after.modeOf("species"));
        assertEquals(MintMarks.Mode.MIXED, after.modeOf("painting"));
        assertEquals(MintMarks.Mode.UNKNOWN, after.modeOf("watch"), "unrecorded stays unrecorded");
    }

    /**
     * A damaged mark must cost the mark and nothing else. Defensive by hand rather than by a
     * generated codec, for the reason the migration catalogue records: a generated codec fails the
     * WHOLE decode on one bad field, which is the opposite of what a defensive store is for.
     */
    @Test
    void damagedRecordsAreSkippedNeverFatal() {
        MintMarks m = new MintMarks();
        assertEquals(0, m.load(null));
        assertEquals(0, m.load(JsonParser.parseString("\"not an object\"")));
        assertEquals(0, m.load(JsonParser.parseString("{}")), "no mintedBy at all");
        assertEquals(0, m.load(JsonParser.parseString("{\"mintedBy\":[]}")), "wrong shape");

        // One good class among three bad ones: the good one survives.
        assertEquals(1, m.load(JsonParser.parseString(
                "{\"version\":1,\"mintedBy\":{\"item\":\"VANILLA\",\"species\":{},"
                        + "\"painting\":\"SIDEWAYS\",\"\":\"MOD\"}}")));
        assertEquals(MintMarks.Mode.VANILLA, m.modeOf("item"));
        assertEquals(MintMarks.Mode.UNKNOWN, m.modeOf("species"));
        assertEquals(MintMarks.Mode.UNKNOWN, m.modeOf("painting"),
                "a mode a later build invented costs that class and no other");
    }

    /**
     * A future version is READ, not refused. Refusing would turn "you opened your world in an older
     * build" into "this world has no mark", which is the silence the mark exists to remove.
     */
    @Test
    void aVersionFromTheFutureIsStillRead() {
        MintMarks m = new MintMarks();
        assertEquals(1, m.load(JsonParser.parseString(
                "{\"version\":9999,\"mintedBy\":{\"item\":\"vanilla\"}}")));
        assertEquals(MintMarks.Mode.VANILLA, m.modeOf("item"), "and the name is case-insensitive");
    }

    /**
     * UNKNOWN is the ABSENCE of a record. Storing it would be a record saying there is none, which
     * reads back as a fact and is not one.
     */
    @Test
    void unknownIsNeverStored() {
        MintMarks m = new MintMarks();
        assertEquals(0, m.load(JsonParser.parseString("{\"mintedBy\":{\"item\":\"UNKNOWN\"}}")));
        assertFalse(m.toJson().getAsJsonObject("mintedBy").has("item"));
    }

    /** The exemptions are a decision, so they are asserted rather than left to a comment alone. */
    @Test
    void onlyTheSubstitutedClassesAreMarked() {
        assertEquals(java.util.List.of("item", "species", "painting", "watch"), MintMarks.MARKED);
        for (String exempt : java.util.List.of("build", "contraption", "train", "datapack")) {
            assertFalse(MintMarks.MARKED.contains(exempt),
                    exempt + " is identical on both paths — marking it would be a flag on a thing "
                            + "that cannot degrade");
        }
    }
}
