package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moddingfromamod.marshal.ArtifactAffinity;
import com.moddingfromamod.marshal.FakeClock;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import org.junit.jupiter.api.Test;

/**
 * <b>D10 where it RUNS: the dequeue a parked agent actually blocks on</b>.
 *
 * <p>The fairness number used to be measured through {@code Marshal.claim}, which no real agent
 * calls — {@code AwaitWishTool} blocks on this queue and only then {@code assign}s. A rule proven on
 * the scripted pool's path would be proven about a fiction (the class note on {@link WishQueue} says
 * the same about affinity).
 */
class WishQueueFairnessTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static Wish wish(UUID who, String text) {
        return new Wish(UUID.randomUUID(), text, WishSource.TYPED, WishStatus.PENDING,
                0L, who, "kid", "minecraft:overworld", 0, 64, 0, "", "", 0L);
    }

    /** A queue whose "who is working" is a map the test owns — the Marshal's job in the game. */
    private static WishQueue queue(FakeClock clock, Map<UUID, Set<UUID>> working) {
        return new WishQueue(ArtifactAffinity.forTest(), clock)
                .countingWorkWith(p -> working.getOrDefault(p, Set.of()));
    }

    @Test
    void bobIsNotMadeToWaitBehindEveryOneOfAlicesWishes() throws Exception {
        FakeClock clock = new FakeClock(1_000);
        Map<UUID, Set<UUID>> working = new HashMap<>();
        WishQueue q = queue(clock, working);
        for (int i = 0; i < 6; i++) {
            q.offer(wish(ALICE, "castle " + i));
        }
        Wish bobs = wish(BOB, "a rocket ship");
        q.offer(bobs);

        List<UUID> order = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Wish w = q.await(50, "w" + i).wish();
            order.add(w.playerId());
            working.computeIfAbsent(w.playerId(), k -> new HashSet<>()).add(w.id());
        }
        assertEquals(List.of(ALICE, BOB, ALICE), order,
                "the old FIFO handed all three workers to Alice and Bob started after the FOURTH "
                        + "completion; under D10 Bob — zero pickups — is second");
    }

    @Test
    void aChildAtHisCapWaitsInHisPlaceAndNobodyElsesWishIsHeldUp() throws Exception {
        FakeClock clock = new FakeClock(1_000);
        Map<UUID, Set<UUID>> working = new HashMap<>();
        WishQueue q = queue(clock, working);
        for (int i = 0; i < 4; i++) {
            q.offer(wish(ALICE, "castle " + i));
        }
        for (int i = 0; i < 3; i++) {
            Wish w = q.await(50, "w" + i).wish();
            assertEquals(ALICE, w.playerId());
            working.computeIfAbsent(ALICE, k -> new HashSet<>()).add(w.id());
        }
        assertTrue(q.await(30, "w3").isEmpty(),
                "Alice has three in flight — her fourth must wait, not be handed out");
        assertEquals(1, q.size(), "…and it is still in the queue, in its place");

        Wish bobs = wish(BOB, "a rocket ship");
        q.offer(bobs);
        assertEquals(bobs.id(), q.await(50, "w3").wish().id(),
                "Bob's wish must not be stuck behind a wish that may not go");
    }

    @Test
    void aWishHandedOutButNotYetWorkingStillCountsAgainstTheCap() throws Exception {
        // The window AwaitWishTool's safety grade sits in: handed out by the queue, not yet
        // assign()ed in the Marshal. Nothing is ever added to `working` here.
        FakeClock clock = new FakeClock(1_000);
        WishQueue q = queue(clock, new HashMap<>());
        for (int i = 0; i < 4; i++) {
            q.offer(wish(ALICE, "castle " + i));
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(ALICE, q.await(50, "w" + i).wish().playerId());
        }
        assertTrue(q.await(30, "w3").isEmpty(),
                "three handed out in the last instant are three in flight, whatever the Marshal "
                        + "has recorded yet — or two workers racing take him to four");
        clock.advance(WishQueue.HANDOFF_MS);
        assertEquals(ALICE, q.await(50, "w3").wish().playerId(),
                "once the hand-off has had time to land, only the Marshal's count stands");
    }

    @Test
    void pickupsFadeAsTheWindowSlides() throws Exception {
        FakeClock clock = new FakeClock(1_000);
        Map<UUID, Set<UUID>> working = new HashMap<>();
        WishQueue q = queue(clock, working);
        q.offer(wish(ALICE, "one"));
        q.offer(wish(ALICE, "two"));
        q.await(50, "w0");                      // Alice: one pickup
        clock.advance(com.moddingfromamod.marshal.FairShare.windowMs());
        q.offer(wish(BOB, "rocket"));
        assertEquals(ALICE, q.await(50, "w1").wish().playerId(),
                "Alice's pickup is outside the window, so it is a tie and the older wish goes");
    }
}
