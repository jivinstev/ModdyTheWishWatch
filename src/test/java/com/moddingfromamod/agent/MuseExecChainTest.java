package com.moddingfromamod.agent;

import com.moddingfromamod.safety.SafetyGrader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ember chain: the house's Muse Code SUBSCRIPTION, then the API key,
 * then the job's pinned Haiku. Each step driven through the real {@link MuseExec} (and, for the last,
 * the real {@link SafetyGrader.CliTransport}) with a fake shim, a fake {@code muse} and a fake
 * {@code claude}; nothing reaches a real binary.
 *
 * <p>The POLICY (whether the subscription is tried, and what a failure writes to the account's files)
 * is the shim's, and is tested there ({@code tools/test-muse-cli-shim.py} §19–24). What is pinned here
 * is the Java half of the contract: the shim's exit code decides the next step, and nothing else.
 */
@org.junit.jupiter.api.extension.ExtendWith(com.moddingfromamod.config.MagicianOn.class)   // fake CLIs run only with Magician on
class MuseExecChainTest {

    @TempDir
    Path tmp;

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"violence\":{\"type\":\"string\"}},"
            + "\"required\":[\"violence\"]}";
    private static final String DONE_LIKELY = "{\"payload_type\":\"run.terminal.completed\",\"payload\":"
            + "{\"terminal\":\"completed\",\"text\":\"{\\\"violence\\\":\\\"LIKELY\\\"}\"}}";
    private static final String DONE_FINE = "{\"payload_type\":\"run.terminal.completed\",\"payload\":"
            + "{\"terminal\":\"completed\",\"text\":\"{\\\"violence\\\":\\\"FINE\\\"}\"}}";

    private final String museBefore = System.getProperty("moddingfromamod.muse.bin");
    private final String shimBefore = System.getProperty("moddingfromamod.muse.shim");
    private final String cliBefore = System.getProperty("moddingfromamod.cli");
    private final java.util.function.Supplier<String> keyBefore = MuseExec.keySource;

    @AfterEach
    void restore() {
        put("moddingfromamod.muse.bin", museBefore);
        put("moddingfromamod.muse.shim", shimBefore);
        put("moddingfromamod.cli", cliBefore);
        MuseExec.keySource = keyBefore;
    }

    private static void put(String k, String v) {
        if (v == null) {
            System.clearProperty(k);
        } else {
            System.setProperty(k, v);
        }
    }

    private Path script(String name, String body) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    /** A fake `muse` that records its argv and stdin, then prints {@code say}. */
    private Path muse(String say) throws Exception {
        return script("muse", "cat > '" + tmp.resolve("muse-stdin") + "'\nprintf '%s\\n' \"$@\" > '"
                + tmp.resolve("muse-argv") + "'\necho '" + say + "'");
    }

    /** A fake shim that records argv, stdin and MUSE_BIN, prints {@code say} and exits {@code rc}. */
    private Path shim(String say, int rc) throws Exception {
        return script("muse-cli-shim.py", "cat > '" + tmp.resolve("shim-stdin") + "'\nprintf '%s\\n' \"$@\" > '"
                + tmp.resolve("shim-argv") + "'\necho \"$MUSE_BIN\" > '" + tmp.resolve("shim-bin") + "'\necho '"
                + say + "'\nexit " + rc);
    }

    private void wire(Path muse, Path shim, String key) {
        System.setProperty("moddingfromamod.muse.bin", muse.toString());
        System.setProperty("moddingfromamod.muse.shim", shim == null ? "none" : shim.toString());
        MuseExec.keySource = () -> key;
    }

    private MuseExec.Answer ember() {
        return MuseExec.grade("PROMPT", "a wish", SCHEMA, "muse-ember-1.0", "low", 10_000);
    }

    @Test
    void step1TheSubscriptionAnswersThroughTheShim() throws Exception {
        Path muse = muse(DONE_FINE);
        wire(muse, shim(DONE_LIKELY, 0), "LLM_realkey");
        MuseExec.Answer a = ember();
        assertNotNull(a);
        assertEquals("{\"violence\":\"LIKELY\"}", a.text(), "the shim's answer, not the key step's");
        assertEquals(MuseExec.ARM_SUBSCRIPTION, a.arm());
        assertEquals(0.0, a.costUsd(), "the plan's flat fee is never priced as API spend");
        assertFalse(Files.exists(tmp.resolve("muse-argv")), "the key step never ran");

        List<String> argv = Files.readAllLines(tmp.resolve("shim-argv"));
        assertEquals(List.of("grade", "--timeout", "10", "--", "--json", "--model", "muse-ember-1.0"),
                argv.subList(0, 7), "grade --timeout S -- <the muse exec args>");
        assertFalse(argv.contains("--api-key-stdin"), "the subscription arm is never sent a key");
        assertTrue(argv.containsAll(MuseGrade.FLAGS.subList(0, 2)) && argv.contains("--contributor-mode")
                && argv.get(argv.indexOf("--contributor-mode") + 1).equals("off"),
                "the graders' lockdown flags ride through unchanged, contributor mode off: " + argv);
        assertEquals("", Files.readString(tmp.resolve("shim-stdin")), "nothing on the shim's stdin");
        assertEquals(muse.toString(), Files.readString(tmp.resolve("shim-bin")).strip(),
                "the shim runs the same muse binary MuseExec resolved");
    }

    @Test
    void step2NotTriedOnTheSubscriptionMeansTheApiKey() throws Exception {
        wire(muse(DONE_FINE), shim("{\"shim\":\"grade\",\"arm\":\"api\",\"state\":\"quota\"}", MuseExec.SHIM_SKIPPED),
                "LLM_realkey");
        MuseExec.Answer a = ember();
        assertNotNull(a);
        assertEquals("{\"violence\":\"FINE\"}", a.text());
        assertEquals(MuseExec.ARM_API, a.arm());
        assertTrue(Files.readAllLines(tmp.resolve("muse-argv")).contains("--api-key-stdin"));
        assertEquals("LLM_realkey", Files.readString(tmp.resolve("muse-stdin")), "the key on stdin, never argv");
    }

    @Test
    void step2ASubscriptionThatFailedAlsoMeansTheApiKey() throws Exception {
        wire(muse(DONE_FINE), shim("{\"shim\":\"grade\",\"arm\":\"subscription\",\"outcome\":\"model\"}", 4),
                "LLM_realkey");
        MuseExec.Answer a = ember();
        assertNotNull(a);
        assertEquals(MuseExec.ARM_API, a.arm());
    }

    @Test
    void step3NoKeyAfterTheSubscriptionMeansTheCallersHaiku() throws Exception {
        wire(muse(DONE_FINE), shim("{\"shim\":\"grade\",\"arm\":\"api\",\"state\":\"nologin\"}", 3), null);
        assertNull(ember(), "no key: no API step; null sends the caller to its Haiku");
        assertFalse(Files.exists(tmp.resolve("muse-argv")), "muse is not run on its own login on a house");
    }

    @Test
    void theWholeChainEndsOnTheJobsHaikuWithTheOriginalPrompt() throws Exception {
        MusePrompts.register("ORIGINAL chain prompt", "EMBER fork of the chain prompt");
        wire(muse("{\"payload_type\":\"run.terminal.failed\",\"payload\":{\"terminal\":\"failed\",\"reason\":\"API error 500\"}}"),
                shim("{\"shim\":\"grade\",\"outcome\":\"quota\"}", 4), "LLM_realkey");
        Path saw = tmp.resolve("claude-saw");
        Path claude = script("claude", "while [ $# -gt 0 ]; do [ \"$1\" = --system-prompt ] && echo \"$2\" >> '" + saw
                + "'; [ \"$1\" = --model ] && echo \"$2\" >> '" + saw + "'; shift; done\n"
                + "echo '{\"structured_output\":{\"violence\":\"FINE\"},\"modelUsage\":{\"claude-haiku-5-5\":{}}}'");
        System.setProperty("moddingfromamod.cli", claude.toString());
        SafetyGrader.Reply r = new SafetyGrader.CliTransport(() -> MuseGrade.EMBER, () -> SafetyGrader.HAIKU_PIN)
                .grade("ORIGINAL chain prompt", "a wish", SCHEMA, 10_000);
        assertNotNull(r);
        assertEquals(MuseExec.ARM_HAIKU, r.arm(), "the record names the third step");
        assertEquals("claude-haiku-5-5", r.modelId());
        assertEquals(List.of("ORIGINAL chain prompt", SafetyGrader.HAIKU_PIN), Files.readAllLines(saw),
                "Haiku asked with the job's ORIGINAL prompt, pinned");
        assertTrue(Files.readString(tmp.resolve("shim-argv")).contains("run.system_prompt=\"EMBER fork of the chain prompt\""),
                "both Ember steps asked with the fork");
    }

    @Test
    void anEmberAnswerCarriesItsArmIntoTheGradersReply() throws Exception {
        wire(muse(DONE_FINE), shim(DONE_LIKELY, 0), "LLM_realkey");
        SafetyGrader.Reply r = new SafetyGrader.CliTransport(() -> MuseGrade.EMBER, () -> SafetyGrader.HAIKU_PIN)
                .grade("PROMPT", "a wish", SCHEMA, 10_000);
        assertNotNull(r);
        assertEquals("muse-ember-1.0", r.modelId(), "the model id is the same on either Ember arm");
        assertEquals(MuseExec.ARM_SUBSCRIPTION, r.arm());
    }

    @Test
    void theNudgeTakesTheSameChain() throws Exception {
        String schema = "{\"type\":\"object\",\"properties\":{\"picks\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}}},\"required\":[\"picks\"]}";
        String done = "{\"payload_type\":\"run.terminal.completed\",\"payload\":{\"terminal\":\"completed\","
                + "\"text\":\"{\\\"picks\\\":[\\\"a:castle\\\"]}\"}}";
        // 1. the subscription answers
        wire(muse(done), shim(done, 0), "LLM_realkey");
        String env = new com.moddingfromamod.readymade.ReadyMadeNudge.CliTransport("none", MuseGrade.EMBER, "low", tmp)
                .ask("P", "a wish", schema, 10_000);
        assertNotNull(env);
        assertTrue(env.contains("\"arm\":\"subscription\"") && env.contains("a:castle"), env);
        // 2. not tried on the plan: the key
        wire(muse(done), shim("{\"shim\":\"grade\",\"state\":\"quota\"}", 3), "LLM_realkey");
        env = new com.moddingfromamod.readymade.ReadyMadeNudge.CliTransport("none", MuseGrade.EMBER, "low", tmp)
                .ask("P", "a wish", schema, 10_000);
        assertNotNull(env);
        assertTrue(env.contains("\"arm\":\"api\""), env);
        // 3. neither: the pinned Haiku on the Claude CLI
        wire(muse("{}"), shim("{}", 4), null);
        Path saw = tmp.resolve("nudge-claude-model");
        Path claude = script("claude", "while [ $# -gt 0 ]; do [ \"$1\" = --model ] && echo \"$2\" > '" + saw
                + "'; shift; done\necho '{\"structured_output\":{\"picks\":[]}}'");
        env = new com.moddingfromamod.readymade.ReadyMadeNudge.CliTransport(claude.toString(), MuseGrade.EMBER, "low", tmp)
                .ask("P", "a wish", schema, 10_000);
        assertNotNull(env, "the nudge's last step is its Haiku");
        assertEquals(SafetyGrader.HAIKU_PIN, Files.readString(saw).strip());
    }

    @Test
    void aSubscriptionThatHangsSpendsTheBudgetAndDoesNotDoubleIt() throws Exception {
        wire(muse(DONE_FINE), script("muse-cli-shim.py", "cat >/dev/null\nsleep 30"), "LLM_realkey");
        long t0 = System.currentTimeMillis();
        MuseExec.Answer a = MuseExec.grade("PROMPT", "a wish", SCHEMA, "muse-ember-1.0", "low", 2_000);
        long took = System.currentTimeMillis() - t0;
        assertNull(a, "no time left for the key step: the caller's Haiku is next");
        assertTrue(took < 6_000, "bounded by the budget plus the shim backstop, took " + took + " ms");
        assertFalse(Files.exists(tmp.resolve("muse-argv")));
    }

    @Test
    void noShimIsTodaysPath() throws Exception {
        wire(muse(DONE_FINE), null, "LLM_realkey");
        MuseExec.Answer a = ember();
        assertNotNull(a);
        assertEquals(MuseExec.ARM_API, a.arm());
        assertTrue(Files.readAllLines(tmp.resolve("muse-argv")).contains("--api-key-stdin"));
    }

    @Test
    void theShimIsFoundBesideAHousesCliAndNowhereElse() throws Exception {
        System.clearProperty("moddingfromamod.muse.shim");
        Path s = shim("", 0);
        System.setProperty("moddingfromamod.cli", s.toString());
        assertEquals(s.toString(), MuseExec.shim(), "a server pointing moddingfromamod.cli at bin/muse-cli-shim.py has the shim");
        System.setProperty("moddingfromamod.cli", script("claude", "true").toString());
        assertNull(MuseExec.shim(), "a plain Claude CLI is not the shim");
    }
}
