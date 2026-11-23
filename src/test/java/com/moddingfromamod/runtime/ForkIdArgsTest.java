package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** a replay command's line, read with a forked id ({@code <id>~<short uuid>}) in it. */
class ForkIdArgsTest {

    private static final int MAX = 8;

    @Test
    void aForkedKeyIsOneWord() {
        ForkIdArgs.Spawn s = ForkIdArgs.spawn("dragon~1a2b3c4d", MAX);
        assertNull(s.error());
        assertEquals("dragon~1a2b3c4d", s.key());
        assertEquals(1, s.count());
        assertEquals("", s.at());
    }

    @Test
    void theTrailingCountAndSeatStillParse() {
        // an earlier issue's grammar: [n] at <build>:<spot>, with both ids forked
        ForkIdArgs.Spawn s = ForkIdArgs.spawn("boss~1a2b3c4d 3 at castle~380df991:throne", MAX);
        assertNull(s.error());
        assertEquals("boss~1a2b3c4d", s.key());
        assertEquals(3, s.count());
        assertEquals("castle~380df991:throne", s.at());

        ForkIdArgs.Spawn noCount = ForkIdArgs.spawn("  boss~1a2b3c4d   at  castle:throne ", MAX);
        assertNull(noCount.error());
        assertEquals(1, noCount.count());
        assertEquals("castle:throne", noCount.at());

        ForkIdArgs.Spawn countOnly = ForkIdArgs.spawn("boss 2", MAX);
        assertEquals(2, countOnly.count());
        assertEquals("", countOnly.at());
    }

    @Test
    void aKeyNamedLikeTheKeywordIsStillTheKey() {
        ForkIdArgs.Spawn s = ForkIdArgs.spawn("at at castle:throne", MAX);
        assertNull(s.error());
        assertEquals("at", s.key());
        assertEquals("castle:throne", s.at());
    }

    @Test
    void whatBrigadierRefusedIsStillRefused() {
        assertNotNull(ForkIdArgs.spawn("", MAX).error());
        assertNotNull(ForkIdArgs.spawn("boss 0", MAX).error());
        assertNotNull(ForkIdArgs.spawn("boss " + (MAX + 1), MAX).error());
        assertNotNull(ForkIdArgs.spawn("boss 99999999999999999999", MAX).error());
        assertNotNull(ForkIdArgs.spawn("boss at", MAX).error());
        assertNotNull(ForkIdArgs.spawn("boss please", MAX).error());
        assertNotNull(ForkIdArgs.spawn("boss 2 3", MAX).error());
    }

    @Test
    void oneIdAndNothingElse() {
        assertEquals("dragon~1a2b3c4d", ForkIdArgs.one(" dragon~1a2b3c4d ", "creature").id());
        assertNotNull(ForkIdArgs.one("", "creature").error());
        assertNotNull(ForkIdArgs.one("dragon extra", "creature").error());
    }

    @Test
    void paintReadsAForkedKeyAndKeepsTheOldTrailingWord() {
        ForkIdArgs.Paint p = ForkIdArgs.paint("dragon~1a2b3c4d wings purple");
        assertNull(p.error());
        assertEquals(List.of("dragon~1a2b3c4d", "wings", "purple"), List.of(p.key(), p.part(), p.colour()));
        assertNull(ForkIdArgs.paint("dragon~1a2b3c4d wings purple tint").error());
        assertNull(ForkIdArgs.paint("dragon~1a2b3c4d wings purple fill").error());
        assertNotNull(ForkIdArgs.paint("dragon~1a2b3c4d wings").error());
        assertNotNull(ForkIdArgs.paint("dragon~1a2b3c4d wings purple glossy").error());
    }

    @Test
    void headSplitsAtTheFirstSpace() {
        ForkIdArgs.Head h = ForkIdArgs.head("face~1a2b3c4d {\"rows\": [\"ab c\"]}");
        assertEquals("face~1a2b3c4d", h.id());
        assertEquals("{\"rows\": [\"ab c\"]}", h.tail());
        assertEquals("", ForkIdArgs.head("face").tail());
    }

    @Test
    void theCursorNamesTheWordBeingTyped() {
        assertEquals(0, ForkIdArgs.cursor("").index());
        assertEquals(0, ForkIdArgs.cursor("drag").index());
        ForkIdArgs.Cursor c = ForkIdArgs.cursor("dragon~1a 2 at cas");
        assertEquals(3, c.index());
        assertEquals("dragon~1a 2 at ".length(), c.start());
        assertEquals(List.of("dragon~1a", "2", "at"), c.before());
        ForkIdArgs.Cursor next = ForkIdArgs.cursor("dragon~1a ");
        assertEquals(1, next.index());
        assertEquals("dragon~1a ".length(), next.start());
    }
}
