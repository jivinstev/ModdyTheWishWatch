package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** the answering rules of the agent's ask_which question. */
class WhichQuestionTest {

    private static final UUID HIM = UUID.nameUUIDFromBytes("which-test".getBytes());

    @AfterEach
    void tidy() {
        WhichQuestion.clear();
    }

    @Test
    void anOfferedIdAnswersItAndOnlyOnce() throws Exception {
        var q = WhichQuestion.ask(HIM, List.of("sky_castle", "stone_castle"));
        assertFalse(WhichQuestion.answer(HIM, "pegasus"), "an id that was not offered answers nothing");
        assertTrue(WhichQuestion.answer(HIM, "Stone_Castle"));
        assertEquals("stone_castle", q.answer().get());
        assertNull(WhichQuestion.openFor(HIM), "an answered question is closed");
        assertFalse(WhichQuestion.answer(HIM, "sky_castle"), "a second click answers nothing");
    }

    @Test
    void noneIsAnAnswer() throws Exception {
        var q = WhichQuestion.ask(HIM, List.of("a", "b"));
        assertTrue(WhichQuestion.answer(HIM, "none"));
        assertEquals(WhichQuestion.NONE, q.answer().get());
    }

    @Test
    void aNewQuestionReleasesTheOldAsker() throws Exception {
        var first = WhichQuestion.ask(HIM, List.of("a", "b"));
        var second = WhichQuestion.ask(HIM, List.of("c", "d"));
        assertEquals(WhichQuestion.NONE, first.answer().get(), "the older asker must stop waiting");
        assertFalse(WhichQuestion.answer(HIM, "a"), "a stale click cannot answer the new question");
        assertTrue(WhichQuestion.answer(HIM, "d"));
        assertEquals("d", second.answer().get());
    }

    @Test
    void closingForgetsTheQuestion() {
        var q = WhichQuestion.ask(HIM, List.of("a", "b"));
        WhichQuestion.close(HIM, q);
        assertFalse(WhichQuestion.answer(HIM, "a"));
    }

    /**
     * The stock which-gate's live finding: two wishes asked about the same castles; the first
     * chest, closed by the second opening over it, answered the SECOND question "none". A chest
     * answers only the question it was opened for.
     */
    @Test
    void aChestClosedByANewerOneCannotAnswerTheNewerQuestion() throws Exception {
        var first = WhichQuestion.ask(HIM, List.of("blue_castle", "red_castle"));
        var second = WhichQuestion.ask(HIM, List.of("red_castle", "blue_castle"));
        assertFalse(WhichQuestion.answer(HIM, first, WhichQuestion.NONE),
                "the old chest's Esc must not reach the new question");
        assertTrue(WhichQuestion.answer(HIM, second, "red_castle"));
        assertEquals("red_castle", second.answer().get());
        assertEquals(WhichQuestion.NONE, first.answer().get(), "the older asker was still released");
    }

    @Test
    void escOnTheOpenChestIsStillNone() throws Exception {
        var q = WhichQuestion.ask(HIM, List.of("a", "b"));
        assertTrue(WhichQuestion.answer(HIM, q, WhichQuestion.NONE));
        assertEquals(WhichQuestion.NONE, q.answer().get());
    }
}
