package com.moddingfromamod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** What a report attaches — his recent lines, and his whispers only to the reporter. */
class ChatHistoryTest {

    @Test
    void keepsTheNewestTenByNameCaseBlind() {
        ChatHistory h = new ChatHistory();
        for (int i = 0; i < 15; i++) {
            h.said("Bob", "chat.chat", "line " + i, "", i);
        }
        List<ChatHistory.Line> ls = h.linesFor("bob", "Ann", 20);
        assertEquals(10, ls.size());
        assertEquals("line 5", ls.get(0).text());
        assertEquals("line 14", ls.get(9).text());
    }

    @Test
    void aWhisperIsAttachedOnlyToAReportByItsRecipient() {
        ChatHistory h = new ChatHistory();
        h.said("Bob", "chat.msg", "to ann", "Ann", 0);
        h.said("Bob", "chat.msg", "to cat", "Cat", 1);
        h.said("Bob", "chat.chat", "to all", "", 2);
        assertEquals(List.of("to ann", "to all"),
                h.linesFor("Bob", "ann", 3).stream().map(ChatHistory.Line::text).toList());
        assertEquals(List.of("to cat", "to all"),
                h.linesFor("Bob", "Cat", 3).stream().map(ChatHistory.Line::text).toList());
    }

    @Test
    void oldLinesAreNotAttachedAndLongOnesAreClipped() {
        ChatHistory h = new ChatHistory();
        h.said("Bob", "chat.chat", "old", "", 0);
        h.said("Bob", "chat.chat", "x".repeat(400), "", ChatHistory.MAX_AGE_MS);
        List<ChatHistory.Line> ls = h.linesFor("Bob", "Ann", ChatHistory.MAX_AGE_MS + 1);
        assertEquals(1, ls.size());
        assertEquals(ChatHistory.MAX_CHARS, ls.get(0).text().length());
        assertTrue(h.linesFor("Nobody", "Ann", 0).isEmpty());
    }
}
