package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** a wish's recipe carries where its creatures sat, and a part re-wishes only itself. */
class SpotLinkTest {

    @Test
    void aSeatedCreatureLineGainsItsSpot() {
        List<String> notes = new ArrayList<>();
        List<String> out = SpotLink.carry(List.of("wishbuild castle", "wishmob spawn boss"),
                Map.of("boss", "castle:throne"), notes);
        assertEquals(List.of("wishbuild castle", "wishmob spawn boss at castle:throne"), out);
        assertEquals(1, notes.size());
    }

    @Test
    void aCountIsKeptAndTheAgentsOwnAtIsLeftAlone() {
        List<String> out = SpotLink.carry(List.of("wishbuild fort", "wishmob spawn knight 2",
                        "wishmob spawn dragon at fort:roof"),
                Map.of("knight", "fort:gate", "dragon", "fort:tower"), new ArrayList<>());
        assertEquals(List.of("wishbuild fort", "wishmob spawn knight 2 at fort:gate",
                "wishmob spawn dragon at fort:roof"), out);
    }

    @Test
    void theBuildingGoesUpBeforeTheCreatureSitsOnIt() {
        List<String> out = SpotLink.carry(List.of("wishmob spawn boss", "say hi", "wishbuild castle"),
                Map.of("boss", "castle:throne"), new ArrayList<>());
        assertEquals(List.of("say hi", "wishbuild castle", "wishmob spawn boss at castle:throne"), out);
    }

    @Test
    void nothingSeatedChangesNothing() {
        List<String> in = List.of("wishbuild castle", "wishmob spawn boss");
        assertSame(in, SpotLink.carry(in, Map.of(), new ArrayList<>()));
        assertSame(in, SpotLink.carry(in, Map.of("other", "castle:throne"), new ArrayList<>()));
    }

    @Test
    void theLinkNamesNoThingSoEachPartReWishesOnlyItself() {
        List<String> wish = List.of("wishbuild castle", "wishmob spawn boss at castle:throne");
        assertEquals(List.of("castle", "boss"), TaughtJoin.artifactsNamed(wish));
        assertEquals(List.of("wishbuild castle"), TaughtJoin.commandsFor(wish, "castle"),
                "re-wishing the castle alone must not spawn the boss");
        assertEquals(List.of("wishmob spawn boss at castle:throne"), TaughtJoin.commandsFor(wish, "boss"),
                "re-wishing the boss alone seats him on the throne");
        assertEquals(Map.of("boss", "castle:throne"), SpotLink.linksOf(wish));
    }
}
