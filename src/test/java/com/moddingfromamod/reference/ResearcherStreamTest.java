package com.moddingfromamod.reference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the researcher's reply is read as a stream, so the pages it opens are started early and
 * its answer is taken the moment it is written. Lines are shaped like a recorded real Plumo lookup
 * (claude -p --output-format stream-json --verbose, 2026-11-10). Offline.
 */
@org.junit.jupiter.api.extension.ExtendWith(com.moddingfromamod.config.MagicianOn.class)   // fake CLIs run only with Magician on
class ResearcherStreamTest {

    static final String ANSWER = "{\"colours\":[{\"colour\":\"red\",\"percent\":40},"
            + "{\"colour\":\"blue\",\"percent\":30}],\"parts\":[\"head\",\"hat\"],"
            + "\"confidence\":\"HIGH\",\"sources\":[\"https://www.plumowiki.com/Plumo\"]}";

    static String toolUse(String name, String url) {
        return "{\"type\":\"assistant\",\"message\":{\"stop_reason\":null,\"content\":[{\"type\":"
                + "\"tool_use\",\"name\":\"" + name + "\",\"input\":{\"url\":\"" + url
                + "\",\"prompt\":\"Describe it\"}}]}}";
    }

    static String text(String t) {
        return "{\"type\":\"assistant\",\"message\":{\"stop_reason\":null,\"content\":[{\"type\":"
                + "\"text\",\"text\":" + new com.google.gson.JsonPrimitive(t) + "}]}}";
    }

    static String result(String t) {
        return "{\"type\":\"result\",\"subtype\":\"success\",\"result\":"
                + new com.google.gson.JsonPrimitive(t) + "}";
    }

    @Test
    @DisplayName("pages are handed on as the researcher opens them, before its answer")
    void pagesStartEarly() {
        List<String> started = new ArrayList<>();
        ResearcherStream s = new ResearcherStream(started::add);
        assertFalse(s.accept("{\"type\":\"system\",\"subtype\":\"init\"}"));
        assertFalse(s.accept(toolUse("WebSearch", "https://ignored.example/")));
        assertFalse(s.accept(toolUse("WebFetch", "https://www.plumowiki.com/Plumo")));
        assertEquals(List.of("https://www.plumowiki.com/Plumo"), started);
        assertFalse(s.accept(text("Let me check one more page.")), "prose is not the answer");
    }

    @Test
    @DisplayName("the answer is taken when it is written, not when the process exits")
    void answerIsTakenFromTheMessage() {
        ResearcherStream s = new ResearcherStream(u -> { });
        s.accept(toolUse("WebFetch", "https://www.plumowiki.com/Plumo"));
        assertTrue(s.accept(text(ANSWER)));
        assertTrue(s.answeredEarly());
        assertEquals(ANSWER, s.answer());
        assertFalse(ReferenceSpec.parse(s.answer()).isEmpty());
    }

    @Test
    @DisplayName("with no answer-shaped message, the result event's text is the reply")
    void resultEventIsTheFallback() {
        ResearcherStream s = new ResearcherStream(u -> { });
        assertFalse(s.accept(text("No canonical design exists.")));
        assertTrue(s.accept(result("No canonical design exists.")));
        assertFalse(s.answeredEarly());
        assertEquals("No canonical design exists.", s.answer());
    }

    @Test
    @DisplayName("a CLI that ignores stream-json still works: the plain reply is kept verbatim")
    void plainTextReplyStillParses() {
        ResearcherStream s = new ResearcherStream(u -> { });
        for (String line : ANSWER.replace(",", ",\n").split("\n")) {
            assertFalse(s.accept(line));
        }
        assertFalse(ReferenceSpec.parse(s.answer()).isEmpty(), s.answer());
    }

    @Test
    @DisplayName("only the first few https pages start early, each once; nothing else does")
    void earlyStartsAreBounded() {
        List<String> started = new ArrayList<>();
        ResearcherStream s = new ResearcherStream(started::add);
        s.accept(toolUse("WebFetch", "http://insecure.example/page"));
        s.accept(toolUse("WebFetch", "https://a.example/one two"));
        s.accept(toolUse("WebFetch", "https://a.example/1"));
        s.accept(toolUse("WebFetch", "https://a.example/1"));
        s.accept(toolUse("WebFetch", "https://a.example/2"));
        s.accept(toolUse("WebFetch", "https://a.example/3"));
        s.accept(toolUse("WebFetch", "https://a.example/4"));
        assertEquals(List.of("https://a.example/1", "https://a.example/2", "https://a.example/3"),
                started);
    }

    @Test
    @DisplayName("a callback that throws never breaks the lookup")
    void callbackFailureIsContained() {
        ResearcherStream s = new ResearcherStream(u -> {
            throw new IllegalStateException("boom");
        });
        s.accept(toolUse("WebFetch", "https://a.example/1"));
        assertTrue(s.accept(text(ANSWER)));
    }

    /**
     * The real entry point, driven through a stand-in CLI that writes the stream and then lingers,
     * as the real one does for ~3 s on its post-turn summary. askRaw must return the answer
     * without waiting for that, and must have started the page it opened.
     */
    @Test
    @DisplayName("askRaw returns as soon as the answer streams in, not when the CLI exits")
    void askRawDoesNotWaitForTheExit(@TempDir Path dir) throws Exception {
        Path cli = dir.resolve("fake-claude");
        // 127.0.0.1 is refused by ReferenceImages.allowed, so the early start stays offline.
        String lines = String.join("\n",
                "{\"type\":\"system\",\"subtype\":\"init\"}",
                toolUse("WebFetch", "https://127.0.0.1/wiki/Plumo"),
                text(ANSWER));
        Files.writeString(cli, "#!/bin/sh\ncat <<'EOF'\n" + lines + "\nEOF\nsleep 8\n"
                + "echo '" + result("late").replace("'", "") + "'\n");
        assertTrue(cli.toFile().setExecutable(true));
        String before = System.getProperty("moddingfromamod.cli");
        System.setProperty("moddingfromamod.cli", cli.toString());
        ReferenceResearcher.cacheDir(dir.resolve("cache"));
        try {
            long t0 = System.nanoTime();
            String out = ReferenceResearcher.askRaw("Plumo");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(ANSWER, out);
            assertTrue(ms < 5000, "waited for the CLI to exit: " + ms + " ms");
        } finally {
            if (before == null) {
                System.clearProperty("moddingfromamod.cli");
            } else {
                System.setProperty("moddingfromamod.cli", before);
            }
            ReferenceResearcher.quiesceForTest();
        }
    }
}
