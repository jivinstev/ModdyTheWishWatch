package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Gate A for the "I'm working on it" messages.
 *
 * <p>Trivial-looking, and it exists because the wording is the entire feature. A child watching a
 * 9,468-block aeroplane being composed for him saw one line saying the watch didn't know how, and
 * concluded — reasonably — that nothing had happened.
 */
class WishProgressTest {

    private static String at(long seconds) {
        return WishProgress.renderLine(seconds * 1000, null, WishProgress.Phase.CASTING, 0).getString();
    }

    /** The bar for a wish submitted at 0, claimed and planned when given (ms; -1 = never). */
    private static String bar(long nowS, long claimedS, long plannedS, String step) {
        WishStage.Track t = new WishStage.Track(0);
        if (claimedS >= 0) {
            t.claimed(claimedS * 1000);
        }
        if (plannedS >= 0) {
            t.planned(plannedS * 1000);
        }
        return WishProgress.render(nowS * 1000, nowS * 1000, t, step, null, null, 0).getString();
    }

    @Test
    void itAlwaysSaysSomethingIsHappening() {
        for (long s : new long[]{0, 5, 10, 30, 90, 300}) {
            String m = at(s);
            assertFalse(m.isBlank(), "must always say something at " + s + "s");
            assertTrue(m.contains("(" + s + "s)"),
                    "and show how long it's been, so the wait feels measured: " + m);
        }
    }

    /** the bar names the stage the mod has SEEN, and the seconds and spinner stay. */
    @Test
    void theBarNamesTheStageNotATimerTier() {
        assertEquals("✨ Reading your wish… (4s)", bar(4, -1, -1, ""));
        assertEquals("⭐ Working out how to make it… (9s)", bar(9, 7, -1, ""));
        assertEquals("⭐ Building the floating island… (21s)",
                bar(21, 7, 20, "Building the floating island"));
        assertEquals("✨ Still working on it — thanks for waiting! (40s)", bar(40, 7, -1, ""),
                "33 s with nothing new");
        assertEquals("✨ Still going — some wishes take a minute or two! (80s)", bar(80, 7, 20, "Building"),
                "60 s with nothing new");
    }

    /** No line on the bar may claim a size or a difficulty the mod cannot know. */
    @Test
    void itNeverSaysBigOrHugeOrTricky() {
        for (long s : new long[]{0, 8, 25, 60, 90, 300}) {
            for (String m : new String[] {at(s), bar(s, -1, -1, ""), bar(s, 1, -1, ""),
                    bar(s, 1, 2, "Building it")}) {
                String l = m.toLowerCase();
                for (String claim : new String[] {"big", "huge", "tricky", "really hard"}) {
                    assertFalse(l.contains(claim), "claims " + claim + " at " + s + "s: " + m);
                }
            }
        }
    }

    /** It must never claim to be nearly finished — it has no idea when the agent will be done. */
    @Test
    void itNeverPretendsToKnowHowCloseItIs() {
        for (long s : new long[]{0, 10, 30, 55, 120, 300}) {
            for (String m : new String[] {at(s), bar(s, 1, -1, ""), bar(s, 1, 2, "Building it")}) {
                String l = m.toLowerCase();
                assertFalse(l.contains("nearly"), "must not claim progress it cannot know: " + m);
                assertFalse(l.contains("almost"), m);
                assertFalse(l.contains("%"), m);
            }
        }
    }

    @Test
    void itNeverSoundsLikeARefusal() {
        // The original bug was tone: "I don't know how to do that one yet" reads as no.
        for (long s : new long[]{0, 10, 45, 120}) {
            for (String m : new String[] {at(s), bar(s, 1, -1, ""), bar(s, -1, -1, "")}) {
                String l = m.toLowerCase();
                assertFalse(l.contains("don't know"), "must not read as a refusal: " + m);
                assertFalse(l.contains("can't"), "nor as a failure: " + m);
                assertFalse(l.contains("sorry"), "nor as an apology: " + m);
            }
        }
    }

    @Test
    void itAnimatesSoItLooksAlive() {
        // A frozen string looks like a hung game; the spinner is how he can tell it's still going.
        assertNotEquals(at(0).charAt(0), at(1).charAt(0), "the spinner should move each second");
    }

    @Test
    void theAgentsNewestLineStillShowsWithItsMagic() {
        WishStage.Track t = new WishStage.Track(0);
        t.claimed(5_000);
        String m = WishProgress.render(20_000, 20_000, t, "", "Shaping it out of blocks…",
                WishProgress.Phase.BUILDING, 18_000).getString();
        assertTrue(m.startsWith("🏗 Shaping it out of blocks…"), m);
        assertTrue(m.endsWith("(20s)"), m);
    }

    // ---- the agent's own narration replaces the generic line ----

    private static String line(long seconds, String doing, WishProgress.Phase phase, int queued) {
        return WishProgress.renderLine(seconds * 1000, doing, phase, queued).getString();
    }

    @Test
    void whenTheAgentNarratesItShowsThatNotTheGenericLine() {
        String m = line(42, "Building your purple crystal lighthouse", WishProgress.Phase.BUILDING, 0);
        assertTrue(m.contains("Building your purple crystal lighthouse"),
                "the agent's words must show: " + m);
        assertTrue(m.contains("(42s)"), "and still show the elapsed time: " + m);
        assertTrue(m.contains("🏗"), "with the building phase's bit of magic: " + m);
        assertFalse(m.toLowerCase().contains("amazing"), "the generic line is replaced: " + m);
    }

    @Test
    void eachPhaseGetsItsOwnMagic() {
        assertTrue(line(5, "Dreaming up your dragon", WishProgress.Phase.DREAMING, 0).contains("🔮"));
        assertTrue(line(5, "Checking it breathes fire", WishProgress.Phase.CHECKING, 0).contains("🔍"));
        assertTrue(line(5, "Adding the last sparkles", WishProgress.Phase.FINISHING, 0).contains("✨"));
    }

    @Test
    void phaseWordingIsTolerant() {
        // The agent may say "verifying", "testing", "making" — all map to a sensible phase.
        assertEquals(WishProgress.Phase.CHECKING, WishProgress.Phase.parse("verifying it works"));
        assertEquals(WishProgress.Phase.CHECKING, WishProgress.Phase.parse("testing"));
        assertEquals(WishProgress.Phase.BUILDING, WishProgress.Phase.parse("making the wings"));
        assertEquals(WishProgress.Phase.DREAMING, WishProgress.Phase.parse("planning it out"));
        assertEquals(WishProgress.Phase.FINISHING, WishProgress.Phase.parse("polishing"));
    }

    @Test
    void withNoNarrationAndNoStageItSaysItIsReadingTheWish() {
        assertEquals("🌟 Reading your wish… (30s)",
                WishProgress.renderLine(30_000, null, WishProgress.Phase.CASTING, 0).getString());
    }

    // ---- a wish waiting behind the current one is surfaced in the persistent bar ----

    @Test
    void theBarNEVERTellsHimHisWishIsBehindAnother() {
        // This used to assert the OPPOSITE: once every five refreshes the bar reminded him his next
        // wish was held. That line, and its chat twin, were queue management in a friendly voice —
        // and a seven-year-old never sees a queue. They were kept while a waiting wish had nothing
        // else to show, which stopped being true when a wish began raising its own bar and beam
        // within a tick of being made.
        //
        // Found in a Gate-C SCREENSHOT of a two-wish sequence, with every assertion in that run
        // green.
        for (long s = 0; s < 20; s++) {
            String held = line(s, "Building your castle", WishProgress.Phase.BUILDING, 1);
            assertFalse(held.toLowerCase().contains("next wish"),
                    "the bar told him his wish was held, at second " + s + ": " + held);
            assertFalse(held.toLowerCase().contains("after this one"),
                    "at second " + s + ": " + held);
        }
        assertTrue(line(9, "Building your castle", WishProgress.Phase.BUILDING, 1)
                        .contains("Building your castle"),
                "…and it shows the work instead, at the second it used to interrupt");
    }

        @Test
    void noQueueMeansTheBarNeverMentionsANextWish() {
        for (long s = 0; s < 20; s++) {
            assertFalse(line(s, "Building your castle", WishProgress.Phase.BUILDING, 0)
                    .toLowerCase().contains("next wish"), "no queue -> no next-wish line at " + s);
        }
    }

    // ---- pure queue bookkeeping: which wish is active, how many are waiting ----

    private static Wish w(String id, UUID player, long createdMs) {
        return new Wish(UUID.fromString(id), "wish " + id, WishSource.TYPED, WishStatus.PENDING,
                createdMs, player, "kid", "minecraft:overworld", 0, 0, 0, "", "", 0L);
    }

    @Test
    void theOldestPendingWishIsTheActiveOneAndTheRestAreQueued() {
        UUID kid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Wish a = w("00000000-0000-0000-0000-0000000000a1", kid, 100);
        Wish b = w("00000000-0000-0000-0000-0000000000b2", kid, 200);
        Wish someoneElse = w("00000000-0000-0000-0000-0000000000c3", other, 150);
        List<Wish> pending = List.of(b, someoneElse, a); // deliberately out of order

        List<Wish> mine = WishProgress.pendingFor(pending, kid);
        assertEquals(List.of(a, b), mine, "this child's wishes, oldest first, not the other kid's");

        // While A is active, B is the one that gets promoted next.
        assertEquals(b, WishProgress.nextActive(pending, kid, a.id()));
        // When nothing else is pending for the child, there's nothing to promote.
        assertNull(WishProgress.nextActive(List.of(a, someoneElse), kid, a.id()));
    }

    // ----: only a wish made AFTER the active one is "queued behind" it ----

    @Test
    void onlyWishesMadeAfterTheActiveOneCountAsQueued() {
        UUID kid = UUID.randomUUID();
        Wish stale = w("00000000-0000-0000-0000-0000000000a1", kid, 100);  // left over from before
        Wish active = w("00000000-0000-0000-0000-0000000000b2", kid, 200); // the one being worked
        Wish newer = w("00000000-0000-0000-0000-0000000000c3", kid, 300);  // genuinely queued behind
        // The active wish is RUNNING (claimed), so it isn't in pending(); pending holds the others.
        List<Wish> pending = List.of(newer, stale);

        List<Wish> behind = WishProgress.queuedBehind(pending, kid, active.createdMs());
        assertEquals(List.of(newer), behind,
                "only the wish made AFTER the active one is queued; a stale OLDER pending is not");
    }

    @Test
    void aLoneFirstWishHasNothingQueuedBehindItEvenWithStalePending() {
        // The phantom "I heard your next wish!" bug: a single fresh wish, but an unfinished PENDING
        // wish from a previous session was still on the books (reloaded from the journal). It must
        // NOT be announced as this wish's backlog.
        UUID kid = UUID.randomUUID();
        Wish oldFromLastSession = w("00000000-0000-0000-0000-0000000000a1", kid, 100);
        Wish theOnlyNewWish = w("00000000-0000-0000-0000-0000000000b2", kid, 5_000);
        List<Wish> pending = List.of(oldFromLastSession); // the new wish is RUNNING, not pending

        assertTrue(WishProgress.queuedBehind(pending, kid, theOnlyNewWish.createdMs()).isEmpty(),
                "a just-made wish must have an EMPTY backlog even when stale pending wishes exist");
    }

    @Test
    void queuedBehindIgnoresOtherPlayersWishes() {
        UUID kid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Wish active = w("00000000-0000-0000-0000-0000000000b2", kid, 200);
        Wish otherKidLater = w("00000000-0000-0000-0000-0000000000c3", other, 300);
        assertTrue(WishProgress.queuedBehind(List.of(otherKidLater), kid, active.createdMs()).isEmpty(),
                "another child's later wish is not this child's backlog");
    }
}
