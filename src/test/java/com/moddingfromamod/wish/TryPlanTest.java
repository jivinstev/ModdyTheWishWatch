package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TryPlanTest {

    @Test
    void aFullIdAndItsFirstEightCharactersAreBothIds() {
        UUID u = UUID.fromString("1a2b3c4d-0000-4000-8000-00000000abcd");
        var full = TryPlan.parse(u.toString()).orElseThrow();
        assertTrue(full.isFull());
        assertEquals(u, full.full());
        assertEquals("1a2b3c4d", full.shortId());
        var shortId = TryPlan.parse("  1A2B3C4D ").orElseThrow();
        assertFalse(shortId.isFull());
        assertEquals("1a2b3c4d", shortId.shortId());
    }

    @Test
    void anythingElseIsNotAnId() {
        for (String bad : new String[]{"", "   ", "hello", "1a2b3c4", "1a2b3c4d5", "zzzzzzzz",
                "1a2b3c4d-0000-4000-8000-00000000abc", "1a2b3c4d_0000_4000_8000_00000000abcd",
                "@p", "../../x"}) {
            assertEquals(Optional.empty(), TryPlan.parse(bad), bad);
        }
        assertEquals(Optional.empty(), TryPlan.parse(null));
    }

    @Test
    void eachKindIsReplayedByTheVerbAPressWouldRun() {
        assertEquals(Optional.of("wishmob spawn pet_dragon"), TryPlan.commandFor("creature", "pet_dragon"));
        assertEquals(Optional.of("wishbuild lava_castle"), TryPlan.commandFor("build", "lava_castle"));
        assertEquals(Optional.of("wishdatapack zap_wand give"), TryPlan.commandFor("item", "zap_wand"));
        assertEquals(Optional.of("wishpaint heart"), TryPlan.commandFor("painting", "heart"));
        // Create's two kinds have no replay verb any more.
        assertEquals(Optional.empty(), TryPlan.commandFor("contraption", "crusher"));
        assertEquals(Optional.empty(), TryPlan.commandFor("train", "express"));
    }

    @Test
    void everyVerbItEmitsIsOneTheGuardAllows() {
        for (String kind : new String[]{"creature", "build", "item", "painting"}) {
            String cmd = TryPlan.commandFor(kind, "x_1").orElseThrow();
            assertTrue(CommandGuard.isAllowed(cmd), cmd);
        }
    }

    @Test
    void aKindWithNoReplayAndAnIdThatCouldCarryACommandAreRefused() {
        assertEquals(Optional.empty(), TryPlan.commandFor("datapack", "eternal_day"));
        assertEquals(Optional.empty(), TryPlan.commandFor("mob", "zombie_king"));
        assertEquals(Optional.empty(), TryPlan.commandFor("", "thing"));
        // An artifact id is never allowed to smuggle a second argument or a selector in.
        assertEquals(Optional.empty(), TryPlan.commandFor("creature", "dragon @a"));
        assertEquals(Optional.empty(), TryPlan.commandFor("build", "x; kill @e"));
        assertEquals(Optional.empty(), TryPlan.commandFor("item", ""));
    }

    @Test
    void aBareIdIsTheKindOfTheFirstStoreThatKnowsIt() {
        assertEquals("creature", TryPlan.inferKind(true, true, false, false));
        assertEquals("build", TryPlan.inferKind(false, true, true, false));
        assertEquals("item", TryPlan.inferKind(false, false, true, true));
        assertEquals("painting", TryPlan.inferKind(false, false, false, true));
        assertEquals("", TryPlan.inferKind(false, false, false, false));
    }
}
