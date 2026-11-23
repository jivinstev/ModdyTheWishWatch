package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** a power word the game does not know is named, never dropped in silence. */
class UnknownPowersTest {

    private static String check(String json) {
        return Species.Power.unknownWords(JsonParser.parseString(json));
    }

    @Test
    void knownPowersPassInAnyCase() {
        assertEquals("", check("[\"friendly\", \"Rideable\", \" flies \", \"HOSTILE\"]"));
        assertEquals("", check("[]"));
        assertEquals("", Species.Power.unknownWords(null));
    }

    @Test
    void anInstinctGivenAsAPowerIsSentToBase() {
        String why = check("[\"friendly\", \"guardian\"]");
        assertTrue(why.contains("'guardian'") && why.contains("base:\"guardian\""), why);
    }

    @Test
    void aMoveGivenAsAPowerIsSentToAbilities() {
        String why = check("[\"charge\"]");
        assertTrue(why.contains("'charge' is a move: put it in abilities"), why);
    }

    @Test
    void anythingElseIsNamedAndTheRealListGiven() {
        String why = check("[\"tough\", \"fast\"]");
        assertTrue(why.contains("'tough'") && why.contains("'fast'"), why);
        assertTrue(why.contains("rideable") && why.contains("own fields"), why);
    }
}
