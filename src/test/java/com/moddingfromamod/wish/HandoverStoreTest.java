package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.marshal.AliasIndex;
import com.moddingfromamod.marshal.HandoverRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The handover record on disk, and which records a waking agent is actually handed.
 *
 * <p>In {@code com.moddingfromamod.wish} because the serialisation is package-private — the store's
 * JSON shape is an implementation detail, and the one thing worth asserting about it from outside is
 * that a record survives the round trip. Which is exactly what these do.
 */
class HandoverStoreTest {

    @AfterEach
    void tearDown() {
        HandoverStore.clear();
    }

    private static HandoverRecord spikor() {
        return new HandoverRecord("spikor", "creature", "a boss Spikor", List.of("king kroak"),
                "canon palette only, because he asked for the REAL Spikor",
                "tried scaling the head to 3 — it clipped the ceiling",
                "120,70,-340 in minecraft:overworld", 1, "aim the fire from the snout", 1000L);
    }

    @Test
    void aRecordSurvivesARoundTripThroughItsOwnJson() {
        assertEquals(spikor(), HandoverStore.fromJson(HandoverStore.toJson(spikor())),
                "it has to survive save/load, or it is a session's memory in disguise");
    }

    @Test
    void damagedStoredDataIsSkippedRatherThanFatal() {
        assertNull(HandoverStore.fromJson(
                        JsonParser.parseString("{\"artifactId\":[1,2,3]}").getAsJsonObject()),
                "losing one artifact's notes is small; failing to open a world over them is not");
    }

    @Test
    void aSecondWishAboutTheSameThingAccumulatesOntoTheFirst() {
        HandoverStore.write(spikor());
        HandoverRecord merged = HandoverStore.write(new HandoverRecord("spikor", "creature", "",
                List.of(), "made him bigger", "tried scale 4 — he could not fit his own gate",
                "", 1, "", 2000L));

        assertEquals(2, merged.revision());
        assertTrue(merged.triedAndRejected().contains("scale 4"));
        assertTrue(merged.triedAndRejected().contains("head to 3"), "the older dead end is kept");
    }

    // ---- which records a wake actually carries -------------------------------------------------

    private static AliasIndex world() {
        return new AliasIndex()
                .add(new AliasIndex.Artifact("spikor", "creature", "spikor", List.of("king kroak")))
                .add(new AliasIndex.Artifact("dinko", "creature", "dinko", List.of()));
    }

    @Test
    void aWishThatNamesAThingCarriesItsNotes() {
        HandoverStore.write(spikor());
        String brief = HandoverBrief.forWish("give King Kroak a bigger fire breath", world());
        assertTrue(brief.contains("clipped the ceiling"),
                "the dead end reaches the next agent on the wake itself, not on request");
    }

    @Test
    void aWishThatNamesNothingCarriesNoWallOfText() {
        HandoverStore.write(spikor());
        assertEquals("", HandoverBrief.forWish("make it bigger", world()),
                "sending everything on every wake teaches the agent the brief is noise to skim");
    }

    @Test
    void aThingWeHaveNoNotesOnContributesNothing() {
        HandoverStore.write(spikor());
        assertEquals("", HandoverBrief.forWish("make dinko green", world()));
    }

    @Test
    void aWishNamingTwoThingsCarriesBoth() {
        HandoverStore.write(spikor());
        HandoverStore.write(new HandoverRecord("dinko", "creature", "a green Dinko", List.of(),
                "green because that is the canon one", "tried a red one; he said no", "", 1, "", 0L));

        String brief = HandoverBrief.forWish("make spikor fight dinko", world());
        assertTrue(brief.contains("clipped the ceiling"));
        assertTrue(brief.contains("tried a red one"),
                "a two-artifact wish is exactly where knowing about BOTH matters most");
    }

    @Test
    void theBriefIsBounded() {
        for (int i = 0; i < 6; i++) {
            HandoverStore.write(new HandoverRecord("thing" + i, "creature", "thing " + i,
                    List.of(), "why " + i, "tried " + i, "", 1, "", 0L));
        }
        AliasIndex big = HandoverBrief.indexOfKnownThings();
        String brief = HandoverBrief.forWish("make thing0 and thing1 and thing2 bigger", big);
        assertTrue(brief.split("── what you already know").length - 1 <= HandoverBrief.MAX_RECORDS,
                "more than a couple on one wake is a wall of text nobody reads");
    }
}
