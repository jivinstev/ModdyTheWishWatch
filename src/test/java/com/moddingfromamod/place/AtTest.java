package com.moddingfromamod.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Gate A 's shared {@code at} grammar: one parse for every placing tool. */
class AtTest {

    @Test
    void aThingAndAWhere() {
        assertEquals(new At("castle", "throne"), At.parse("castle:throne"));
        assertEquals(new At("castle", "throne_room"), At.parse(" Castle : Throne Room "));
        assertEquals(new At("castle", ""), At.parse("castle"), "no where: the tool's own default");
        assertEquals(new At("fort~1a2b3c4d", "gate"), At.parse("fort~1a2b3c4d:gate"), "a fork keeps its ~");
        assertEquals(new At("my_house", "roof"), At.parse("\"my house:roof\""));
    }

    @Test
    void everyWayOfNamingTheChildIsMe() {
        for (String s : List.of("me", "Me:front", "him", "player", "@s", "the_player", "myself")) {
            assertTrue(At.parse(s).isMe(), s);
        }
        assertEquals(new At("me", "front"), At.parse("me:front"));
    }

    @Test
    void nothingIsNull() {
        assertNull(At.parse(null));
        assertNull(At.parse(""));
        assertNull(At.parse("   "));
        assertNull(At.parse(":roof"), "a where with no thing");
    }

    @Test
    void hisAndTheAreTriedAfterTheNameAsWritten() {
        assertEquals(List.of("my_castle", "castle"), At.parse("my castle").lookups());
        assertEquals(List.of("the_shire", "shire"), At.parse("the_shire").lookups(),
                "a building really called the_shire is found first");
        assertEquals(List.of("castle"), At.parse("castle").lookups());
    }

    @Test
    void hisWordFindsTheOneBuildingThatHasIt() {
        java.util.Map<String, String> standing = new java.util.LinkedHashMap<>();
        standing.put("red_barn", "Little Red Barn");
        standing.put("pirate_ship", "Pirate Ship");
        standing.put("cozy_place~1a2b3c4d", "My Cottage");
        assertEquals(List.of("red_barn"), At.parse("my barn:inside").wordMatches(standing));
        assertEquals(List.of("cozy_place~1a2b3c4d"), At.parse("cottage").wordMatches(standing), "by its display name");
        assertEquals(List.of("pirate_ship"), At.parse("ships").wordMatches(standing), "a plural");
        assertEquals(List.of(), At.parse("bar").wordMatches(standing), "a fragment of a word is not the word");
        standing.put("big_barn", "Big Barn");
        assertEquals(List.of("red_barn", "big_barn"), At.parse("barn").wordMatches(standing),
                "two barns: both, so the caller asks rather than guesses");
    }

    @Test
    void orElseFillsOnlyAnEmptyWhere() {
        assertEquals(new At("castle", "top"), At.parse("castle").orElse("top"));
        assertEquals(new At("castle", "inside"), At.parse("castle:inside").orElse("top"));
    }

    @Test
    void theSchemaLineNamesEveryAutomaticSpot() {
        String d = At.describe("it", "its front", "");
        for (String spot : List.of("front", "top", "inside", "north", "beside", "behind", "around", "me")) {
            assertTrue(d.contains(spot), spot);
        }
    }
}
