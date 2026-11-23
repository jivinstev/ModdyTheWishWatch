package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.marshal.ArtifactAffinity;
import com.moddingfromamod.marshal.FakeClock;
import com.moddingfromamod.marshal.WishAffinity;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Affinity routing <b>through the wake channel</b>, which is the seam a real parked agent actually
 * arrives at.
 *
 * <p>Deliberately not against {@code Marshal.claim}: that is the scripted pool's path, and a
 * measurement taken next to the seam rather than through it is how "concurrency proven at
 * wishslots=2" was recorded while production ran serial.
 *
 * <p>A {@link FakeClock} rather than sleeping, so the three-minute hold is asserted in microseconds.
 */
class WishQueueAffinityTest {

    private static final Map<String, String> SUBJECTS = Map.of(
            "make me zippy", "zippy",
            "make zippy taller", "zippy",
            "give zippy a gold ring", "zippy",
            "make me a castle", "castle");

    @AfterEach
    void reset() {
        WishAffinity.override(null);
    }

    private static Wish wish(String text) {
        return new Wish(UUID.randomUUID(), text, WishSource.TYPED, WishStatus.PENDING,
                0L, UUID.randomUUID(), "Steve", "minecraft:overworld", 0, 64, 0, "", "", 0L);
    }

    private static WishQueue queue(ArtifactAffinity affinity, FakeClock clock) {
        return new WishQueue(affinity, clock)
                .resolvingSubjectsWith(w -> SUBJECTS.getOrDefault(w.text(), ""));
    }

    // ================= off: the old behaviour, byte for byte =================

    @Test
    void withNoModeForcingTheFirstWorkerToAskTakesIt() throws Exception {
        ArtifactAffinity affinity = ArtifactAffinity.forTest();
        affinity.remember("zippy", "w1");
        WishQueue q = queue(affinity, new FakeClock(0));
        q.offer(wish("make zippy taller"));

        WishQueue.Taken taken = q.await(50, "w2");
        assertNotNull(taken.wish());
        assertEquals(ArtifactAffinity.Routing.ANYONE, taken.routing());
        assertEquals(0, q.deferredCount(), "nothing is ever held aside under OFF");
    }

    // ================= same =================

    @Test
    void underSameTheWishGoesBackToTheWorkerThatMadeIt() throws Exception {
        WishAffinity.override(WishAffinity.SAME);
        ArtifactAffinity affinity = ArtifactAffinity.forTest();
        affinity.remember("zippy", "w1");
        FakeClock clock = new FakeClock(0);
        WishQueue q = queue(affinity, clock);
        q.offer(wish("make zippy taller"));

        // The stranger asks first and is sent away empty-handed…
        assertTrue(q.await(20, "w2").isEmpty(), "w2 did not make Zippy");
        assertEquals(1, q.deferredCount(), "…and the wish is held aside, not lost");

        // …and the maker gets it when it comes back.
        WishQueue.Taken mine = q.await(20, "w1");
        assertEquals("make zippy taller", mine.wish().text());
        assertEquals(ArtifactAffinity.Routing.OWNER, mine.routing());
        assertEquals("zippy", mine.artifactId());
        assertTrue(mine.routing().held());
        assertEquals(0, q.deferredCount());
    }

    // ================= different =================

    @Test
    void underDifferentTheWishGoesToAnybodyElse() throws Exception {
        WishAffinity.override(WishAffinity.DIFFERENT);
        ArtifactAffinity affinity = ArtifactAffinity.forTest();
        affinity.remember("zippy", "w1");
        WishQueue q = queue(affinity, new FakeClock(0));
        q.offer(wish("make zippy taller"));

        assertTrue(q.await(20, "w1").isEmpty(), "w1 is exactly who must NOT get it");
        WishQueue.Taken theirs = q.await(20, "w2");
        assertEquals(ArtifactAffinity.Routing.STRANGER, theirs.routing());
        assertTrue(theirs.routing().held());
    }

    // ================= the whole point: two wishes about one thing =================

    /**
     * The property the plan's Phase-1 gate is written in terms of, asserted at the seam: two wishes
     * about one artifact land on the SAME worker under {@code same} and on DIFFERENT workers under
     * {@code different}.
     */
    @Test
    void twoWishesAboutOneThingLandTogetherOrApartDependingOnTheArm() throws Exception {
        for (WishAffinity mode : new WishAffinity[] {WishAffinity.SAME, WishAffinity.DIFFERENT}) {
            WishAffinity.override(mode);
            ArtifactAffinity affinity = ArtifactAffinity.forTest();
            WishQueue q = queue(affinity, new FakeClock(0));

            // Wish 1 creates it: nothing owns Zippy yet, so whoever asks first gets it.
            q.offer(wish("make me zippy"));
            WishQueue.Taken first = q.await(20, "w1");
            assertNotNull(first.wish(), mode + ": a creation must reach somebody");
            affinity.remember("zippy", "w1");   // what finish_wish records

            // Wish 2 refines it.
            q.offer(wish("make zippy taller"));
            boolean w1Got = !q.await(20, "w1").isEmpty();
            boolean w2Got = !q.await(20, "w2").isEmpty();

            if (mode == WishAffinity.SAME) {
                assertTrue(w1Got, "SAME must route the refinement back to Zippy's maker");
                assertFalse(w2Got, "SAME must not let a stranger take it");
            } else {
                assertFalse(w1Got, "DIFFERENT must not route the refinement back to Zippy's maker");
                assertTrue(w2Got, "DIFFERENT must hand it to somebody else");
            }
        }
    }

    // ================= no head-of-line blocking =================

    /**
     * A wish held for somebody in particular must not block an unrelated one behind it.
     *
     * <p>Held ASIDE rather than pushed back on the queue for exactly this: the castle has nothing to
     * do with who last touched Zippy, and making it wait would charge every other wish in the world
     * for one arm's routing preference.
     */
    @Test
    void aHeldWishDoesNotBlockAnUnrelatedOneBehindIt() throws Exception {
        WishAffinity.override(WishAffinity.SAME);
        ArtifactAffinity affinity = ArtifactAffinity.forTest();
        affinity.remember("zippy", "w1");
        WishQueue q = queue(affinity, new FakeClock(0));
        q.offer(wish("make zippy taller"));
        q.offer(wish("make me a castle"));

        WishQueue.Taken taken = q.await(20, "w2");
        assertEquals("make me a castle", taken.wish().text(),
                "w2 may not have Zippy, but the castle was never Zippy's problem");
        assertEquals(1, q.deferredCount());
    }

    // ================= the starvation backstop, at the seam =================

    @Test
    void aWishHeldTooLongIsGivenToWhoeverIsFreeAndSaysTheArmDidNotHold() throws Exception {
        WishAffinity.override(WishAffinity.SAME);
        ArtifactAffinity affinity = ArtifactAffinity.forTest();
        affinity.remember("zippy", "w1");
        FakeClock clock = new FakeClock(0);
        WishQueue q = queue(affinity, clock);
        q.offer(wish("make zippy taller"));

        assertTrue(q.await(10, "w2").isEmpty());
        clock.advance(ArtifactAffinity.HOLD_MS);

        WishQueue.Taken fell = q.await(10, "w2");
        assertNotNull(fell.wish(), "a control may never wedge a child's wish");
        assertEquals(ArtifactAffinity.Routing.FALLBACK, fell.routing());
        assertFalse(fell.routing().held(),
                "and it must SAY it fell back, or it gets averaged in as evidence for an arm "
                        + "that never ran");
    }

    // ================= a held wish is still visible =================

    @Test
    void heldWishesStillCountAndAreStillReported() throws Exception {
        WishAffinity.override(WishAffinity.SAME);
        ArtifactAffinity affinity = ArtifactAffinity.forTest();
        affinity.remember("zippy", "w1");
        WishQueue q = queue(affinity, new FakeClock(0));
        q.offer(wish("make zippy taller"));
        assertTrue(q.await(10, "w2").isEmpty());

        assertEquals(1, q.size(), "held aside is still waiting — just for somebody in particular");
        assertEquals(1, q.peekRest(5).size(),
                "a forced arm must not look like a queue that emptied itself");
        q.clear();
        assertEquals(0, q.size());
        assertEquals(0, q.deferredCount());
    }

    // ================= the old signature still behaves =================

    @Test
    void theWorkerlessCallIsUnchanged() throws Exception {
        WishQueue q = queue(ArtifactAffinity.forTest(), new FakeClock(0));
        q.offer(wish("make me a castle"));
        Wish got = q.await(20);
        assertNotNull(got);
        assertEquals("make me a castle", got.text());
        assertNull(q.await(1), "and it still returns null on an empty queue");
    }
}
