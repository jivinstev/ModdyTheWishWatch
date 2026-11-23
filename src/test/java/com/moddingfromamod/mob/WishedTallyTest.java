package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.moddingfromamod.mob.SpawnBudget.Kind;

/** the wished-creature count is re-derived from the level each tick, so nothing it got wrong outlives the tick. */
class WishedTallyTest {

    @Test
    void theCensusIsTakenOncePerTickAndJoinsSinceItAreAddedOnTop() {
        WishedTally<Kind> t = new WishedTally<>(Kind.class);
        AtomicInteger asked = new AtomicInteger();
        assertEquals(3, t.live(Kind.VANILLA, 10, () -> {
            asked.incrementAndGet();
            return Map.of(Kind.VANILLA, 3);
        }));
        t.joined(Kind.VANILLA, 10);
        t.joined(Kind.VANILLA, 10);
        t.joined(Kind.FLYING, 10);
        assertEquals(5, t.live(Kind.VANILLA, 10, () -> {
            asked.incrementAndGet();
            return Map.of(Kind.VANILLA, 99);
        }));
        assertEquals(1, t.live(Kind.FLYING, 10, Map::of));
        assertEquals(1, asked.get(), "one census per tick");
    }

    /**
     * The drift itself: a join that never becomes a tracked entity (a hidden chunk, a duplicate
     * UUID) used to stay counted forever. Here it counts for the tick it joined in and is gone the
     * next, because the next tick asks the level again.
     */
    @Test
    void aJoinTheLevelNeverTrackedIsForgottenTheNextTick() {
        WishedTally<Kind> t = new WishedTally<>(Kind.class);
        assertEquals(0, t.live(Kind.VANILLA, 1, Map::of));
        for (int i = 0; i < 400; i++) {
            t.joined(Kind.VANILLA, 1);   // 400 summons the level never kept
        }
        assertEquals(400, t.live(Kind.VANILLA, 1, Map::of), "within the tick, the ceiling still holds");
        assertEquals(0, t.live(Kind.VANILLA, 2, Map::of), "the next tick, only what the level holds counts");
    }

    @Test
    void aJoinBeforeTheTicksCensusIsTheCensussToSee() {
        WishedTally<Kind> t = new WishedTally<>(Kind.class);
        t.live(Kind.VANILLA, 1, () -> Map.of(Kind.VANILLA, 7));
        t.joined(Kind.VANILLA, 2);   // a chunk loading a wished cow, before anyone asks in tick 2
        assertEquals(8, t.live(Kind.VANILLA, 2, () -> Map.of(Kind.VANILLA, 8)), "not counted twice");
    }

    @Test
    void aRemovalShowsTheNextTickWhateverEventItFiredOrDidNot() {
        WishedTally<Kind> t = new WishedTally<>(Kind.class);
        assertEquals(5, t.live(Kind.BOSS, 1, () -> Map.of(Kind.BOSS, 5)));
        assertEquals(2, t.live(Kind.BOSS, 2, () -> Map.of(Kind.BOSS, 2)));
    }
}
