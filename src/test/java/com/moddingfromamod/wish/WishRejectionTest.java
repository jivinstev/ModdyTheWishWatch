package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moddingfromamod.safety.SafetyLexicon;
import org.junit.jupiter.api.Test;

/**
 * Gate A for "that's not right" —, JUST UNDO. The dangerous parts are all about picking
 * the <b>wrong</b> wish — un-teaching a template the child was happy with, taking away an earlier
 * wish he never complained about, or one belonging to someone else — so that's what these tests are
 * mostly about. Where it RUNS (the undo, the refund, nothing re-queued) is
 * {@code WishBankGameTest.thumbsDownUndoesTheWishRefundsItOnceAndNeverRetries}.
 */
class WishRejectionTest {

    private static final UUID STEVE = UUID.randomUUID();
    private static final UUID SOMEONE_ELSE = UUID.randomUUID();
    private static final long NOW = 1_000_000_000L;

    private static Wish wish(String text, UUID player, long createdMs, String templateId) {
        return new Wish(UUID.randomUUID(), text, WishSource.TYPED, WishStatus.DONE, createdMs,
                player, "Steve", "minecraft:overworld", 0, 64, 0, "", templateId, 0L);
    }

    private static Wish undone(Wish w) {
        return w.withStatus(WishStatus.STOPPED, WishRejection.undoneNote("", w.templateId()));
    }

    @Test
    void picksTheMostRecentWish() {
        List<Wish> recent = List.of(
                wish("make it rain chickens", STEVE, NOW - 5000, "rain_chickens"),
                wish("make a wither storm", STEVE, NOW - 1000, "wither_storm"));
        Optional<Wish> got = WishRejection.pick(recent, STEVE, NOW);
        assertTrue(got.isPresent());
        assertEquals("make a wither storm", got.get().text(),
                "he is complaining about the thing he just saw, not an earlier one");
        assertEquals("wither_storm", got.get().templateId());
    }

    @Test
    void neverPicksAnotherPlayersWish() {
        List<Wish> recent = List.of(
                wish("my good wish", STEVE, NOW - 5000, "mine"),
                wish("someone else's wish", SOMEONE_ELSE, NOW - 1000, "theirs"));
        Optional<Wish> got = WishRejection.pick(recent, STEVE, NOW);
        assertTrue(got.isPresent());
        assertEquals("mine", got.get().templateId(),
                "one player must not be able to un-teach another player's wish");
    }

    @Test
    void staleWishesAreOutOfReach() {
        // Rejecting something from a previous play session would silently delete a template the
        // child has been happily using since.
        List<Wish> recent = List.of(
                wish("ancient wish", STEVE, NOW - WishRejection.MAX_AGE_MS - 1, "ancient"));
        assertTrue(WishRejection.pick(recent, STEVE, NOW).isEmpty());
    }

    @Test
    void aSecondPressLandsOnTheSameWishItAlreadyUndid() {
        // the live incident was two presses in a row. The second must find the wish it
        // already undid (and the caller then does nothing) — never walk back to an earlier wish he
        // was happy with and take THAT away too.
        Wish older = wish("older wish", STEVE, NOW - 5000, "older");
        Wish gone = undone(wish("bad wish", STEVE, NOW - 1000, "bad"));
        assertTrue(WishRejection.alreadyRejected(gone));
        Optional<Wish> got = WishRejection.pick(List.of(older, gone), STEVE, NOW);
        assertTrue(got.isPresent());
        assertEquals("bad", got.get().templateId(), "a double press walked back to an earlier wish");
        assertTrue(WishRejection.alreadyRejected(got.get()));
        assertFalse(WishRejection.alreadyRejected(older), "a wish he never pressed 👎 on is not undone");
    }

    @Test
    void aWishTheSafetyLayerRefusedIsNotTheOneHeMeans() {
        // REJECTED is "refused before doing anything" — nothing to undo, so 👎 means the one before.
        Wish older = wish("older wish", STEVE, NOW - 5000, "older");
        Wish refused = wish("refused", STEVE, NOW - 1000, "").withStatus(WishStatus.REJECTED, "no");
        assertEquals("older", WishRejection.pick(List.of(older, refused), STEVE, NOW).orElseThrow().templateId());
    }

    @Test
    void nothingToRejectIsHandledCleanly() {
        assertTrue(WishRejection.pick(List.of(), STEVE, NOW).isEmpty());
        assertTrue(WishRejection.pick(null, STEVE, NOW).isEmpty());
        assertFalse(WishRejection.nothingToUndo().isBlank());
    }

    // ===================== the book's record =====================

    @Test
    void theNoteRecordsAnUndoNotARetry() {
        String note = WishRejection.undoneNote("it's just a spider", "wither_storm");
        assertTrue(note.startsWith(WishRejection.MARKER), note);
        assertTrue(note.contains("it's just a spider"), "his own words are the quality signal");
        assertTrue(note.contains("wither_storm"), "name the template that was deleted");
        assertTrue(note.contains("NOT tried again"), note);
        assertFalse(note.contains("find_content"), "no agent reads this any more: " + note);
    }

    @Test
    void theNoteWorksWithNoComplaintAndNoTemplate() {
        // A 7-year-old will usually just hit the button and say nothing.
        String note = WishRejection.undoneNote("", null);
        assertTrue(note.startsWith(WishRejection.MARKER), note);
        assertFalse(note.contains("The child says"), "don't invent a complaint he didn't make");
        assertFalse(note.contains("DELETED"), "nothing was deleted, so don't claim it was");
        Wish w = wish("x", STEVE, NOW, "t").withStatus(WishStatus.STOPPED, note);
        assertTrue(WishRejection.alreadyRejected(w), "the note must be recognisable as the marker");
    }

    // ===================== what the child hears =====================

    @Test
    void theChildHearsItIsGoneAndThatHeGotHisWishBack() {
        assertEquals("Gone! I took it away and gave you your wish back — ✨ 7/10.",
                WishRejection.sayToChild(true, "✨ 7/10"));
        assertEquals("Gone! I took it away.", WishRejection.sayToChild(true, null),
                "no bank, never charged or already refunded: no refund is claimed");
    }

    @Test
    void neverClaimsItTookSomethingAwayWhenNothingCameAway() {
        String said = WishRejection.sayToChild(false, null);
        assertFalse(said.contains("took it away"), said);
        assertTrue(WishRejection.sayToChild(false, "✨ 3/10").contains("gave you your wish back"));
    }

    @Test
    void neverPromisesToTryAgain() {
        // the whole point. "I'll try again" is what kept him pressing a button that re-ran it.
        for (String s : everyLine()) {
            assertFalse(s.toLowerCase().contains("try again"), s);
        }
    }

    @Test
    void everyLineAChildReadsPassesTheLexicon() {
        SafetyLexicon lex = SafetyLexicon.shared();
        for (String s : everyLine()) {
            assertFalse(s.isBlank());
            assertTrue(lex.check(s).isEmpty(), "the lexicon flags a 👎 line: " + s);
        }
    }

    private static List<String> everyLine() {
        List<String> out = new ArrayList<>();
        for (boolean took : new boolean[] {true, false}) {
            out.add(WishRejection.sayToChild(took, null));
            out.add(WishRejection.sayToChild(took, "✨ 7/10"));
        }
        out.add(WishRejection.alreadyUndone());
        out.add(WishRejection.nothingToUndo());
        return out;
    }
}
