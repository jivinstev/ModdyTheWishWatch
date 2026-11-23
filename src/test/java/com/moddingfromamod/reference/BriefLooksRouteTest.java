package com.moddingfromamod.reference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * describer routes: the Haiku CLI sees the pictures in its FIRST message, in one turn,
 * with no tool of any kind; Ember through Muse Code is a knob that is OFF unless set.
 */
class BriefLooksRouteTest {

    @TempDir
    Path tmp;

    @Test
    void theHaikuCliIsOneTurnWithNoToolsNoMcpAndNoSettings() {
        List<String> a = BriefLooks.haikuArgv("/bin/claude", "claude-haiku-5-5", null);
        assertEquals("", a.get(a.indexOf("--tools") + 1), "no tool at all: not even Read");
        assertEquals("1", a.get(a.indexOf("--max-turns") + 1));
        assertEquals("stream-json", a.get(a.indexOf("--input-format") + 1), "the pictures ride in the message");
        assertEquals("", a.get(a.indexOf("--setting-sources") + 1));
        assertTrue(a.contains("--strict-mcp-config") && a.contains("--disable-slash-commands"));
        assertFalse(a.contains("--allowed-tools"), "nothing to allow");
        assertFalse(a.contains("--bare"), "--bare skips the sign-in: measured, it answered 'Authentication error'");
        assertFalse(String.join(" ", a).contains("Read tool"), "the prompt no longer tells it to read files");
        assertFalse(a.contains("--effort"), "with no effort given, the CLI's own");
        List<String> low = BriefLooks.haikuArgv("/bin/claude", "claude-haiku-5-5", "low");
        assertEquals("low", low.get(low.indexOf("--effort") + 1));
    }

    @Test
    void theEffortKnobTakesOnlyAnEffortWord() {
        String before = System.getProperty(BriefLooks.EFFORT_PROPERTY);
        try {
            System.clearProperty(BriefLooks.EFFORT_PROPERTY);
            assertEquals("minimal", BriefLooks.effort(EmberVision.EFFORT), "unset: Ember at minimal");
            assertEquals("low", BriefLooks.effort(BriefLooks.HAIKU_EFFORT), "unset: Haiku at low");
            assertNull(BriefLooks.effort(null));
            System.setProperty(BriefLooks.EFFORT_PROPERTY, "minimal");
            assertEquals("minimal", BriefLooks.effort("low"));
            System.setProperty(BriefLooks.EFFORT_PROPERTY, "max; rm -rf");
            assertEquals("low", BriefLooks.effort("low"), "anything else is ignored");
        } finally {
            if (before == null) {
                System.clearProperty(BriefLooks.EFFORT_PROPERTY);
            } else {
                System.setProperty(BriefLooks.EFFORT_PROPERTY, before);
            }
        }
    }

    @Test
    void theMessageCarriesEachPictureThenTheAsk() throws Exception {
        Path p1 = tmp.resolve("picture1.png"), p2 = tmp.resolve("picture2.png");
        Files.write(p1, new byte[] {1, 2, 3});
        Files.write(p2, new byte[] {4});
        JsonObject line = JsonParser.parseString(
                BriefLooks.message(List.of(p1.toString(), p2.toString()), "Describe it.")).getAsJsonObject();
        assertEquals("user", line.get("type").getAsString());
        var content = line.getAsJsonObject("message").getAsJsonArray("content");
        assertEquals(3, content.size());
        JsonObject img = content.get(0).getAsJsonObject();
        assertEquals("image", img.get("type").getAsString());
        assertEquals("AQID", img.getAsJsonObject("source").get("data").getAsString());
        assertEquals("image/png", img.getAsJsonObject("source").get("media_type").getAsString());
        assertEquals("Describe it.", content.get(2).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void onlyASuccessfulResultIsAnAnswer() {
        String ok = "{\"type\":\"system\",\"subtype\":\"init\"}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"It stands upright.\"}\n";
        assertEquals("It stands upright.", BriefLooks.result(ok));
        assertNull(BriefLooks.result("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
                + "\"result\":\"Authentication error\"}"), "an error result is not a look");
        assertNull(BriefLooks.result("{\"type\":\"result\",\"subtype\":\"error_max_turns\"}"));
        assertNull(BriefLooks.result("not json\n"));
    }

    @Test
    void theDefaultChainIsEmberThenHaiku() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/reference/BriefLooks.java"));
        assertTrue(src.contains("!\"haiku\".equals(System.getProperty(DESCRIBER_PROPERTY, \"ember\"))"),
                "Ember goes first unless the haiku route is asked for");
        assertTrue(src.contains("HelperRoute.Job.DESCRIBER"),
                "the describer's steps and their order are the route's (HelperRoute), not its own");
        assertTrue(src.indexOf("EmberVision.describe(SYSTEM") < src.indexOf("ask(pdir, files, hint, st)"),
                "Ember (the Muse steps) is tried in the Muse handler, Haiku in the Claude one");
    }
}
