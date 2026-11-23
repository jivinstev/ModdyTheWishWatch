package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>D6 — what does the second child wait?</b> Measured against the real scheduler rather than
 * argued about, because the spec asks for a number and a policy sentence is not one.
 *
 * <p>The wait a child experiences is <i>(where his wish sits in the admission order)</i> × <i>how
 * long a wish takes</i>. The second factor is already measured and written down in
 * {@link WishSlots}' own javadoc — <b>p50 117 s</b>, arriving one every 66 s. So the only thing that
 * needed measuring here is the first, and it is a property of {@link Marshal#claim} that a unit test
 * can drive exactly, with no agent, no server and no waiting.
 */
class TwoChildrenFairnessTest {

    /** {@link WishSlots#DEFAULT}. Read rather than typed, so the number cannot drift from the pool. */
    private static final int SLOTS = WishSlots.DEFAULT;

        /**
     * Play the scheduler forward: {@code SLOTS} workers, each finishing its wish and immediately
     * claiming the next, and report which completion-wave each wish is admitted in.
     *
     * @return for each wish, BY ID, the number of completions that happened before it started.
     *         By id and not by position: the first version returned a list in ADMISSION order and
     *         read "Bob's" wave off its last element — which was only Bob while the queue was FIFO
     *         and Bob was last. Under D10 the last admitted wish is one of Alice's, and the old
     *         assertion went on passing about the wrong child.
     */
    private static Map<UUID, Integer> admissionWaves(Marshal m, int expected) {
        Map<UUID, Integer> startedAfter = new HashMap<>();
        // (worker, the wish it holds) — a worker may hold only one wish at a time, which is a real
        // rule of the marshal (OneWishPerWorkerTest), so the simulation has to free the SAME worker
        // that finished rather than inventing a new id. Getting that wrong stalls at SLOTS
        // admissions and reads as "the queue never drains".
        List<String> workers = new ArrayList<>();
        List<UUID> holding = new ArrayList<>();
        int completions = 0;
        for (int i = 0; i < SLOTS; i++) {
            WishRun r = m.claim("w" + i);
            if (r != null) {
                workers.add("w" + i);
                holding.add(r.wishId());
                startedAfter.put(r.wishId(), 0);
            }
        }
        while (startedAfter.size() < expected && !holding.isEmpty()) {
            String freed = workers.remove(0);
            m.finish(holding.remove(0));
            completions++;
            WishRun next = m.claim(freed);
            if (next == null) {
                continue;
            }
            workers.add(freed);
            holding.add(next.wishId());
            startedAfter.put(next.wishId(), completions);
        }
        return startedAfter;
    }

    /**
     * <b>THE NUMBER, under D10.</b> A submits six wishes, B submits one.
     *
     * <p>⚠ <b>It was FOUR completions</b> — about four minutes before B's wish even started — while
     * the marshal admitted strictly oldest-first with no notion of whose wish it was; this test
     * pinned that number as the D6 measurement. D10 is the answer: B has had
     * no pickups and A has, so B is admitted in the FIRST wave.
     */
    @Test
    @DisplayName("A submits six, B submits one — B no longer waits behind all of them")
    void bIsAdmittedInTheFirstWave() {
        Marshal m = new Marshal(() -> 0L, () -> SLOTS);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            m.submit(UUID.randomUUID(), alice, "castle " + i);
        }
        UUID bobsWish = UUID.randomUUID();
        m.submit(bobsWish, bob, "a rocket ship");

        Map<UUID, Integer> waves = admissionWaves(m, 7);
        assertEquals(7, waves.size(), "every wish should eventually be admitted: " + waves);
        assertEquals(0, waves.get(bobsWish),
                "Bob's wish must start with the first " + SLOTS + " — it waited "
                        + waves.get(bobsWish) + " completions (it was 4 before D10)");
    }

    /**
     * The state a child cannot see is still real — it just no longer falls on the second child by
     * construction. With ONE slot, Alice's wish is working and Bob's is live and waiting.
     */
    @Test
    @DisplayName("a queued wish is admitted but not yet working")
    void aWaitingWishIsLiveButNotWorking() {
        Marshal m = new Marshal(() -> 0L, () -> 1);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        m.submit(UUID.randomUUID(), alice, "a castle");
        UUID bobsWish = UUID.randomUUID();
        m.submit(bobsWish, bob, "a rocket ship");
        assertEquals(alice, m.claim("w0").playerId(), "equal pickups: the older wish goes");
        assertTrue(m.working().stream().noneMatch(r -> r.wishId().equals(bobsWish)),
                "one slot, held by Alice — Bob's wish cannot be working");
        assertTrue(m.liveFor(bob).stream().anyMatch(r -> r.wishId().equals(bobsWish)),
                "…but it IS live: the mod knows he is waiting (made that wait visible)");
    }

    /** D10's cap on the scripted pool's path too: a fourth of one child's wishes waits its turn. */
    @Test
    @DisplayName("one child never has more than the cap in flight, however many slots are free")
    void oneChildIsCappedEvenWithFreeSlots() {
        Marshal m = new Marshal(() -> 0L, () -> WishSlots.MAX);
        UUID alice = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            m.submit(UUID.randomUUID(), alice, "castle " + i);
        }
        int got = 0;
        for (int i = 0; i < WishSlots.MAX; i++) {
            if (m.claim("w" + i) != null) {
                got++;
            }
        }
        assertEquals(Math.min(FairShare.DEFAULT_PER_CHILD, WishSlots.MAX), got,
                "D10: three of one child's wishes in flight, however quiet the server");
    }
}
