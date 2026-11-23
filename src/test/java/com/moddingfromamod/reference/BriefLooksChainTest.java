package com.moddingfromamod.reference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ("the Haiku backup solidly working"): the describer's chain driven end to end through the
 * real {@link BriefLooks#describePictures}. Ember fails each way it can (no key, an empty answer, a timeout, an
 * error) and the Haiku CLI answers; both fail and the describer gives nothing, which is what sends
 * {@link WishBriefs} to the structured look (WishBriefGameTest drives that half). A local server stands in for
 * Meta's API and a script for the Claude CLI; nothing leaves the machine.
 */
@org.junit.jupiter.api.extension.ExtendWith(com.moddingfromamod.config.MagicianOn.class)
class BriefLooksChainTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private final String cliBefore = System.getProperty("moddingfromamod.cli");
    private final String shimBefore = System.getProperty("moddingfromamod.muse.shim");
    private final String routeBefore = System.getProperty(BriefLooks.DESCRIBER_PROPERTY);
    private final java.util.function.Supplier<String> keyBefore = EmberVision.keySource;
    private final String endpointBefore = EmberVision.endpoint;
    private final Duration timeoutBefore = EmberVision.timeout;

    static final String HAIKU_SAYS = "It stands upright on two legs and has no tail. Pink covers about 80% of it.";
    static final String EMBER_SAYS = "It stands upright on two legs and has a long tail. Orange covers about 85% of it.";

    private final String museBefore = System.getProperty("moddingfromamod.muse.bin");

    @BeforeEach
    void noSubscription() {
        System.setProperty("moddingfromamod.muse.shim", "none");   // no shim: no Muse Code subscription step
        System.setProperty("moddingfromamod.muse.bin", tmp.resolve("no-muse-here").toString());   // and no muse at all
        com.moddingfromamod.agent.MuseExec.keyForTest(() -> null);
        System.clearProperty(BriefLooks.DESCRIBER_PROPERTY);
    }

    @AfterEach
    void restore() {
        put("moddingfromamod.cli", cliBefore);
        put("moddingfromamod.muse.shim", shimBefore);
        put(BriefLooks.DESCRIBER_PROPERTY, routeBefore);
        put("moddingfromamod.muse.bin", museBefore);
        com.moddingfromamod.agent.MuseExec.keyForTest(null);
        EmberVision.keySource = keyBefore;
        EmberVision.endpoint = endpointBefore;
        EmberVision.timeout = timeoutBefore;
        if (server != null) {
            server.stop(0);
        }
    }

    private static void put(String k, String v) {
        if (v == null) {
            System.clearProperty(k);
        } else {
            System.setProperty(k, v);
        }
    }

    private static List<BufferedImage> pictures() {
        return List.of(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
    }

    /** A fake Claude CLI: records argv and stdin, then prints a result line (or exits 1 when {@code ok} is false). */
    private void haiku(boolean ok) throws Exception {
        String result = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"" + HAIKU_SAYS + "\"}";
        Path p = tmp.resolve("claude");
        Files.writeString(p, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + tmp.resolve("cli-argv") + "'\ncat > '"
                + tmp.resolve("cli-stdin") + "'\n" + (ok ? "echo '" + result + "'\n" : "exit 1\n"));
        assertTrue(p.toFile().setExecutable(true));
        System.setProperty("moddingfromamod.cli", p.toString());
    }

    /** A local stand-in for Meta's Responses API answering {@code status} and {@code body} after {@code delayMs}. */
    private void ember(int status, String body, long delayMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            Files.write(tmp.resolve("ember-request"), in);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        EmberVision.endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses";
        EmberVision.keySource = () -> "LLM_testkey";
    }

    private static String answer(String text) {
        return "{\"output\":[{\"type\":\"reasoning\",\"summary\":[]},{\"type\":\"message\",\"content\":"
                + "[{\"type\":\"output_text\",\"text\":\"" + text + "\"}]}]}";
    }

    private void haikuWasAskedProperly() throws Exception {
        List<String> argv = Files.readAllLines(tmp.resolve("cli-argv"));
        assertEquals("low", argv.get(argv.indexOf("--effort") + 1), "the Haiku backup runs at --effort low");
        assertEquals("", argv.get(argv.indexOf("--tools") + 1), "with no tool at all");
        String stdin = Files.readString(tmp.resolve("cli-stdin"));
        assertEquals(2, stdin.split("\"type\":\"image\"", -1).length - 1, "both pictures in its first message");
    }

    @Test
    void emberAnswersFirstAtMinimal() throws Exception {
        haiku(true);
        ember(200, answer(EMBER_SAYS), 0);
        assertEquals(EMBER_SAYS, BriefLooks.describePictures("Zorbix", pictures(), ""));
        assertTrue(Files.readString(tmp.resolve("ember-request")).contains("\"effort\":\"minimal\""));
        assertFalse(Files.exists(tmp.resolve("cli-argv")), "Haiku is never asked when Ember answered");
    }

    @Test
    void noKeyMeansHaikuAnswers() throws Exception {
        haiku(true);
        EmberVision.keySource = () -> null;
        assertEquals(HAIKU_SAYS, BriefLooks.describePictures("Zorbix", pictures(), ""));
        haikuWasAskedProperly();
    }

    @Test
    void anEmptyEmberAnswerMeansHaikuAnswers() throws Exception {
        haiku(true);
        ember(200, "{\"output\":[{\"type\":\"reasoning\",\"summary\":[]}]}", 0);   // all thinking, no answer
        assertEquals(HAIKU_SAYS, BriefLooks.describePictures("Zorbix", pictures(), ""));
        haikuWasAskedProperly();
    }

    @Test
    void anEmberErrorMeansHaikuAnswers() throws Exception {
        haiku(true);
        ember(400, "{\"error\":{\"message\":\"unsupported\"}}", 0);
        assertEquals(HAIKU_SAYS, BriefLooks.describePictures("Zorbix", pictures(), ""));
    }

    @Test
    void anEmberTimeoutMeansHaikuAnswers() throws Exception {
        haiku(true);
        ember(200, answer(EMBER_SAYS), 3_000);
        EmberVision.timeout = Duration.ofMillis(300);
        long t0 = System.currentTimeMillis();
        assertEquals(HAIKU_SAYS, BriefLooks.describePictures("Zorbix", pictures(), ""));
        assertTrue(System.currentTimeMillis() - t0 < 2_500, "the timeout was kept, not waited out");
    }

    @Test
    void bothFailingGivesNothingSoTheStructuredLookGoesInstead() throws Exception {
        haiku(false);
        EmberVision.keySource = () -> null;
        assertEquals("", BriefLooks.describePictures("Zorbix", pictures(), ""));
    }

    @Test
    void theHaikuRouteSkipsEmber() throws Exception {
        haiku(true);
        ember(200, answer(EMBER_SAYS), 0);
        System.setProperty(BriefLooks.DESCRIBER_PROPERTY, "haiku");
        assertEquals(HAIKU_SAYS, BriefLooks.describePictures("Zorbix", pictures(), ""));
        assertFalse(Files.exists(tmp.resolve("ember-request")));
    }

    // ---- a LOCAL install (no shim), each with one way in (the open-source mod) ----------------

    static final String MUSE_SAYS = "It stands upright on two legs and has no tail. White covers about 85% of it.";

    /** A fake `muse` that records its argv and stdin, then prints a completed run with {@link #MUSE_SAYS}. */
    private void museLogin() throws Exception {
        Path p = tmp.resolve("muse");
        Files.writeString(p, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + tmp.resolve("muse-argv") + "'\ncat > '"
                + tmp.resolve("muse-stdin") + "'\necho '{\"payload_type\":\"run.terminal.completed\",\"payload\":"
                + "{\"terminal\":\"completed\",\"text\":\"" + MUSE_SAYS + "\"}}'\n");
        assertTrue(p.toFile().setExecutable(true));
        System.setProperty("moddingfromamod.muse.bin", p.toString());
    }

    @Test
    void localWithOnlyAMuseLoginGetsEmber() throws Exception {
        haiku(false);
        museLogin();
        EmberVision.keySource = () -> null;
        assertEquals(MUSE_SAYS, BriefLooks.describePictures("Zorbix", pictures(), ""));
        List<String> argv = Files.readAllLines(tmp.resolve("muse-argv"));
        assertFalse(argv.contains("--api-key-stdin"), "on Muse Code's own login, no key");
        assertEquals("", Files.readString(tmp.resolve("muse-stdin")));
        assertEquals(2, argv.stream().filter("--image"::equals).count(), "the pictures in its first message");
        assertEquals("minimal", argv.get(argv.indexOf("--reasoning-effort") + 1));
        assertFalse(Files.exists(tmp.resolve("cli-argv")), "Haiku never asked");
    }

    @Test
    void localWithOnlyAKeyGetsEmberDirect() throws Exception {
        haiku(false);
        ember(200, answer(EMBER_SAYS), 0);
        assertEquals(EMBER_SAYS, BriefLooks.describePictures("Zorbix", pictures(), ""));
    }

    @Test
    void localWithAKeyNeverAlsoTriesTheMuseLogin() throws Exception {
        haiku(true);
        museLogin();
        ember(500, "{}", 0);
        assertEquals(HAIKU_SAYS, BriefLooks.describePictures("Zorbix", pictures(), ""));
        assertFalse(Files.exists(tmp.resolve("muse-argv")), "the graders' rule: the key when there is one, else the login");
    }

    @Test
    void localWithOnlyAClaudeLoginGetsHaiku() throws Exception {
        haiku(true);
        EmberVision.keySource = () -> null;
        assertEquals(HAIKU_SAYS, BriefLooks.describePictures("Zorbix", pictures(), ""));
        haikuWasAskedProperly();
    }

    @Test
    void localWithNothingGivesNoLookSoTheStructuredLookGoes() throws Exception {
        haiku(false);
        EmberVision.keySource = () -> null;
        assertEquals("", BriefLooks.describePictures("Zorbix", pictures(), ""));
    }

    @Test
    void aLongLookKeepsWholeSentencesUnderTheLimit() {
        String sentence = "Orange covers about eighty five percent of it with stripes on its back and tail. ";
        String look = sentence.repeat(14).strip();
        String fitted = BriefLooks.fit(look);
        assertTrue(fitted.length() <= BriefLooks.FIT_CHARS && fitted.length() < WishBrief.MAX_LOOK_CHARS);
        assertTrue(fitted.endsWith("tail."), "never cut mid-sentence: " + fitted);
        String usual = sentence.repeat(9).strip();   // ~750 chars, what a 120-word answer runs to
        assertEquals(usual, BriefLooks.fit(usual), "an answer of the measured length is accepted whole");
    }
}
