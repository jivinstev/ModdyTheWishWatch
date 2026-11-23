package com.moddingfromamod.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The pure half of the staged-events foundation (E1). */
class EventFoundationTest {

    // ---- the journal -------------------------------------------------------------------------

    @Test
    void theFirstPriorWinsWhenACellIsWrittenTwice() {
        EventJournal<String> j = new EventJournal<>();
        assertTrue(j.record(1, "grass", "water"));
        assertFalse(j.record(1, "water", "rubble"), "a rewrite is not a new cell");
        assertEquals(1, j.size());
        Map<Long, String> world = new HashMap<>(Map.of(1L, "rubble"));
        List<EventJournal.Restore<String>> back = j.drain(10, world::get);
        assertEquals(List.of(new EventJournal.Restore<>(1L, "grass")), back,
                "undo gives back what was there BEFORE the event, not the event's first write");
    }

    @Test
    void aCellTheChildChangedIsLeftAloneAndForgotten() {
        EventJournal<String> j = new EventJournal<>();
        j.record(1, "air", "water");
        j.record(2, "air", "water");
        Map<Long, String> world = new HashMap<>(Map.of(1L, "water", 2L, "glass"));
        List<EventJournal.Restore<String>> back = j.drain(10, world::get);
        assertEquals(List.of(new EventJournal.Restore<>(1L, "air")), back);
        assertTrue(j.isEmpty(), "the child's glass is forgotten, not remembered for later");
    }

    @Test
    void theNewestWriteComesOffFirstAndTheBudgetIsRespected() {
        EventJournal<String> j = new EventJournal<>();
        for (long p = 1; p <= 5; p++) {
            j.record(p, "air", "water" + p);
        }
        List<EventJournal.Restore<String>> first = j.drain(2, p -> "water" + p);
        assertEquals(List.of(5L, 4L), first.stream().map(EventJournal.Restore::pos).toList(),
                "a flood drains from the top");
        assertEquals(3, j.size());
    }

    @Test
    void releasingACellGivesItBackOnlyIfItIsStillOurs() {
        EventJournal<String> j = new EventJournal<>();
        j.record(7, "flower", "water");
        assertEquals("flower", j.release(7, "water"));
        j.record(8, "flower", "water");
        assertNull(j.release(8, "stone"));
        assertNull(j.release(9, "water"), "a cell we never wrote");
        assertTrue(j.isEmpty());
    }

    @Test
    void theJournalSurvivesFlatteningAndSkipsWhatNoLongerDecodes() {
        EventJournal<String> j = new EventJournal<>();
        j.record(1, "grass", "water");
        j.record(2, "grass", "water");
        j.record(3, "modded:gone", "water");
        EventJournal.Flat<String> flat = j.flatten(s -> s);
        assertEquals(3, flat.palette().size(), "states are pooled, not repeated per cell");
        EventJournal<String> back = EventJournal.unflatten(flat,
                s -> s.startsWith("modded:") ? null : s);
        assertEquals(List.of(1L, 2L), back.positions(),
                "a block from a removed mod costs only its own cell");
    }

    @Test
    void aDamagedSaveCostsOnlyItsBadCells() {
        EventJournal.Flat<String> flat = new EventJournal.Flat<>(List.of("air", "water"),
                new long[]{1, 2, 3}, new int[]{0, 5, 0}, new int[]{1, 1});
        EventJournal<String> back = EventJournal.unflatten(flat, s -> s);
        assertEquals(List.of(1L), back.positions());
    }

    // ---- the guard ---------------------------------------------------------------------------

    private static EventGuard.Cell cell(boolean blockEntity, boolean unbreakable, boolean fluid,
                                        boolean open, boolean natural) {
        return new EventGuard.Cell(true, true, blockEntity, unbreakable, fluid, open, natural);
    }

    @Test
    void aChestAndBedrockAreNeverWritten() {
        for (EventGuard.Policy p : EventGuard.Policy.values()) {
            assertEquals("block entity", EventGuard.refusal(cell(true, false, false, false, false), p));
            assertEquals("unbreakable", EventGuard.refusal(cell(false, true, false, false, true), p));
            assertEquals("fluid", EventGuard.refusal(cell(false, false, true, true, false), p));
        }
    }

    @Test
    void aWaveOnlyFillsOpenSpaceAndACraterMayAlsoCutGround() {
        EventGuard.Cell air = cell(false, false, false, true, false);
        EventGuard.Cell dirt = cell(false, false, false, false, true);
        EventGuard.Cell planks = cell(false, false, false, false, false);
        assertTrue(EventGuard.mayWrite(air, EventGuard.Policy.INTO_OPEN));
        assertFalse(EventGuard.mayWrite(dirt, EventGuard.Policy.INTO_OPEN));
        assertTrue(EventGuard.mayWrite(dirt, EventGuard.Policy.TERRAIN));
        assertFalse(EventGuard.mayWrite(planks, EventGuard.Policy.TERRAIN),
                "a crater that puts itself back never breaks what he built");
    }

    @Test
    void aDisasterThatStaysMayBreakHisBuildsButNeverAChest() {
        EventGuard.Cell planks = cell(false, false, false, false, false);
        assertEquals(EventGuard.Policy.TERRAIN, EventGuard.digging(true));
        assertEquals(EventGuard.Policy.EVERYTHING, EventGuard.digging(false));
        assertTrue(EventGuard.mayWrite(planks, EventGuard.digging(false)),
                "an earthquake that stays is a major event and may break his house");
        assertFalse(EventGuard.mayWrite(planks, EventGuard.digging(true)));
        assertEquals("block entity", EventGuard.refusal(cell(true, false, false, false, false),
                EventGuard.digging(false)), "a chest's contents would be lost, and undo cannot refill it");
    }

    @Test
    void unloadedChunksAndTheWorldFloorAreRefused() {
        assertEquals("unloaded", EventGuard.refusal(
                new EventGuard.Cell(false, true, false, false, false, true, false),
                EventGuard.Policy.INTO_OPEN));
        assertEquals("floor", EventGuard.refusal(
                new EventGuard.Cell(true, false, false, false, false, true, true),
                EventGuard.Policy.TERRAIN));
    }

    // ---- the budget and the scale ------------------------------------------------------------

    @Test
    void theBudgetCapsATickAndTheWholeEvent() {
        EventBudget b = new EventBudget(5, 3);
        assertTrue(b.tryTake(0, true));
        assertTrue(b.tryTake(1, true));
        assertTrue(b.tryTake(2, true));
        assertFalse(b.tryTake(3, true), "three a tick");
        b.newTick();
        assertTrue(b.tryTake(3, true));
        assertTrue(b.tryTake(4, true));
        assertFalse(b.tryTake(5, true), "five in all");
        assertTrue(b.tryTake(5, false), "rewriting its own cell does not grow the event");
    }

    @Test
    void theOwnersBudgetIsTheCeilingAndScalesGrow() {
        // D2: about 250k in all and 8k a tick for the biggest.
        assertEquals(250_000, EventScale.GIGANTIC.totalBlocks);
        assertEquals(8_000, EventScale.GIGANTIC.perTick);
        EventScale prev = null;
        for (EventScale s : EventScale.values()) {
            assertTrue(s.totalBlocks <= 250_000 && s.perTick <= 8_000);
            if (prev != null) {
                assertTrue(s.totalBlocks > prev.totalBlocks && s.radius > prev.radius);
            }
            prev = s;
        }
        assertEquals(EventScale.GIGANTIC, EventScale.parse(" HUGE "));
        assertNull(EventScale.parse("biggest ever"));
    }

    // ---- where to look -----------------------------------------------------------------------

    @Test
    void theCompassFollowsMinecraftsAxes() {
        assertEquals("north", EventAim.compass(0, -10));
        assertEquals("south", EventAim.compass(0, 10));
        assertEquals("east", EventAim.compass(10, 0));
        assertEquals("west", EventAim.compass(-10, 0));
        assertEquals("north-east", EventAim.compass(10, -10));
        assertEquals("here", EventAim.compass(0, 0));
    }

    @Test
    void theCameraTurnFacesTheEvent() {
        assertEquals(0f, EventAim.yaw(0, 10), 1e-3, "yaw 0 faces south");
        assertEquals(-90f, EventAim.yaw(10, 0), 1e-3, "east");
        assertEquals(180f, Math.abs(EventAim.yaw(0, -10)), 1e-3, "north");
        assertTrue(EventAim.pitch(10, 20, 0) < 0, "something above: look up");
        assertTrue(EventAim.pitch(10, -20, 0) > 0, "something below: look down");
    }

    // ---- whose event the wish camera films (the vortex leak) -----------------------------------------------

    @Test
    void aChildsPhotographFilmsOnlyHisOwnEventNeverAnOwnerlessOne() {
        assertTrue(EventDirector.filmableBy("steve", "steve"), "his own event");
        assertFalse(EventDirector.filmableBy("sam", "steve"), "another child's");
        assertFalse(EventDirector.filmableBy("", "steve"),
                "nobody's event (a command with nobody near, a GameTest) is never his: it used to be everybody's");
        assertFalse(EventDirector.filmableBy(null, "steve"), "nor one with no owner recorded");
        assertTrue(EventDirector.filmableBy("", null), "a photograph with no child behind it may film any event");
        assertTrue(EventDirector.filmableBy("sam", ""), "likewise");
    }
}
