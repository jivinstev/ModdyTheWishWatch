package com.moddingfromamod.wish;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.CancelOffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Gate A: the child-facing command lines, and a sweep that no string a child can click
 * or read names a command that was retired.
 */
class ChildCommandsTest {

    @Test
    void everyLinkIsAModdyVerbAndAWishIsJustWords() {
        assertEquals("/moddy again rain_chickens", ChildCommands.again("rain_chickens"));
        assertEquals("/moddy stop", ChildCommands.stop(""));
        assertEquals("/moddy stop ab12", ChildCommands.stop(" ab12 "));
        assertEquals("/moddy undo ab12", ChildCommands.undo("ab12"));
        assertEquals("/moddy go", ChildCommands.go(null));
        assertEquals("/moddy go ab12", ChildCommands.go("ab12"));
        assertEquals("/moddy back", ChildCommands.back());
        assertEquals("/moddy give Laser Bow", ChildCommands.give("Laser Bow"));
        assertEquals("/wish no more rain", ChildCommands.wish("no more rain"));
        assertEquals("/moddy which sky_castle", ChildCommands.which("sky_castle"));
        assertEquals("/moddy which \"oasis@bar~1f2e3d4c\"", ChildCommands.which("oasis@bar~1f2e3d4c"));
    }

    /**
     * The re-aim is a PROMPT: it leaves "/moddy redirect id " for his words. As a RUN link it
     * could only ever fail — the command needs the words — which is what it was.
     */
    @Test
    void theReaimLinkSuggestsAndLeavesRoomForHisWords() {
        assertTrue(CancelOffer.suggests(CancelMode.REDIRECT));
        assertTrue(!CancelOffer.suggests(CancelMode.STOP) && !CancelOffer.suggests(CancelMode.UNDO),
                "stop and undo are complete as they are, and must run on one click");
        assertEquals("/moddy redirect ab12 ", CancelOffer.command(CancelMode.REDIRECT, "ab12"));
        assertEquals("/moddy stop ab12", CancelOffer.command(CancelMode.STOP, "ab12"));
        assertEquals("/moddy undo ab12", CancelOffer.command(CancelMode.UNDO, "ab12"));
    }

    @Test
    void theHelpLineNamesTheLongWishRouteAndEveryVerb() {
        String help = ChildCommands.helpLine();
        assertTrue(help.contains("/wish"), help);
        for (String verb : ChildCommands.VERBS) {
            assertTrue(help.contains(verb), "the help line does not say '" + verb + "': " + help);
        }
    }

    @Test
    void theWishBoxIsAHiddenModdyBranch() {
        assertTrue(!ChildCommands.VERBS.contains(ChildCommands.WISHBOX),
                "the wish box's Submit runs it; a child never types it, so the help line does not name it");
    }

    /**
     * No STRING in shipped code names a retired command or a {@code /wish} control verb. Comments
     * may tell the history; a string is something a click sends or a child reads. The pattern
     * covers both spellings a link can take ("/wishgo …" and a performPrefixedCommand's "wishgo").
     */
    private static final Pattern RETIRED = Pattern.compile(
            "\"/(wishgo|wishback|wishgive|wish (again|stop|undo|redirect|no|status))\\b"
                    + "|\"(wishgo|wishback|wishgive)( |\")"
                    + "|(Command|sendCommand)\\([^)]*\"wish (again|stop|undo|redirect|no|status)\\b");

    @Test
    void noShippedStringNamesARetiredCommand() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = SourceRoots.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")
                    // the one list of retired names, which is the point of it
                    && !p.endsWith("ChildCommands.java")).toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                        continue;
                    }
                    Matcher m = RETIRED.matcher(line);
                    if (m.find()) {
                        hits.add(f + ":" + (i + 1) + ": " + line);
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "a string names a command retired by #1107 — use ChildCommands:\n"
                + String.join("\n", hits));
    }
}
