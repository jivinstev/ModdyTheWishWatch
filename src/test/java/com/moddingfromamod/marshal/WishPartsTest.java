package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Gate A 's rule, one shape at a time (the real records are {@code WishPartsRealDataTest}). */
class WishPartsTest {

    private static WishRun.Made m(String kind, String id) {
        return new WishRun.Made(kind, id);
    }

    @Test
    void anItemRemadeUnderAnotherIdKeepsTheNewestByName() {
        // newest first, as WishRun.made() hands it over
        var made = List.of(m("item", "potion_heal"), m("item", "potion_plain"), m("item", "cup_plain"));
        Map<String, String> names = Map.of("potion_heal", "Potion", "potion_plain", "Potion", "cup_plain", "Cup");
        assertEquals(List.of(m("item", "cup_plain"), m("item", "potion_heal")),
                WishParts.of(made, Set.of(), Set.of(), names::get));
    }

    @Test
    void somethingRemovedThenMadeAgainInTheSameWishIsKept() {
        WishRun run = new WishRun(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "w", 0L);
        run.made("build", "tower");
        run.removed("build", "tower");
        assertEquals(List.of(), WishParts.of(run.made(), run.removedThings(), run.existedBefore(), null));
        run.made("build", "tower");
        assertEquals(List.of(m("build", "tower")),
                WishParts.of(run.made(), run.removedThings(), run.existedBefore(), null));
    }

    @Test
    void onlyTheFirstRecordInARunDecidesWhetherItWasAnEdit() {
        WishRun run = new WishRun(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "w", 0L);
        run.made("creature", "dragon", false);
        run.made("creature", "dragon", true);   // e.g. a second finish_wish already logged it
        assertEquals(Set.of(), run.existedBefore());
        run.made("build", "garage", true);
        assertEquals(Set.of(m("build", "garage")), run.existedBefore());
    }

    @Test
    void aPackIsFoldedOnlyIntoTheThingItIsNamedFor() {
        var made = List.of(m("datapack", "rain_rule"), m("datapack", "spikor_fire"), m("creature", "spikor"));
        assertEquals(List.of(m("creature", "spikor"), m("datapack", "rain_rule")),
                WishParts.of(made, Set.of(), Set.of(), null));
        // "spikors_castle" does not belong to "spikor": the owner must be a whole leading word
        assertEquals(2, WishParts.of(List.of(m("datapack", "spikors_castle_doors"), m("creature", "spikor")),
                Set.of(), Set.of(), null).size());
    }

    @Test
    void everyKindThatCanBeRemadeHasItsReplayCommandAndTheJoinReadsIt() {
        for (var p : List.of(m("build", "a"), m("creature", "b"), m("item", "c"), m("painting", "f"))) {
            String cmd = WishParts.replayCommand(p);
            assertEquals(List.of(p.id()), TaughtJoin.artifactsNamed(List.of(cmd)),
                    "the join must read the command a part is remade by: " + cmd);
        }
        assertEquals("", WishParts.replayCommand(m("mob", "@x")));
        assertEquals("", WishParts.replayCommand(m("event", "wave_1")));
        // ...and the kinds that went with Create, which an old run may still carry.
        assertEquals("", WishParts.replayCommand(m("contraption", "d")));
        assertEquals("", WishParts.replayCommand(m("train", "e")));
    }

    @Test
    void theRefusalAsksOnceForAnUntaughtWishAndNeverForOneThing() {
        assertNull(WishParts.teachRefusal(List.of(m("build", "hut")), false, List.of()));
        String r = WishParts.teachRefusal(List.of(m("build", "court"), m("creature", "player")), false, List.of());
        assertNotNull(r);
        assertTrue(r.contains("\"wishbuild court\"") && r.contains("\"wishmob spawn player\""), r);
        assertNull(WishParts.teachRefusal(List.of(m("build", "court"), m("creature", "player")), true,
                List.of("wishbuild court", "wishmob spawn player")));
        String shortRecipe = WishParts.teachRefusal(List.of(m("creature", "dog"), m("item", "flower_plain")),
                true, List.of("wishmob spawn dog"));
        assertNotNull(shortRecipe);
        assertTrue(shortRecipe.contains("\"wishdatapack flower_plain give\""), shortRecipe);
    }
}
