package com.moddingfromamod.agent;

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
 * smoke: the reference researcher on Muse Code ({@link MuseExec#research}) takes the graders' chain (a server's
 * subscription through the shim, then the API key) with exactly the two WEB tools left and every other one shut, and
 * never the graders' {@code --preset empty} / {@code --disable-web-tools} (which would leave it no way to look). Fake
 * shim and fake {@code muse}; no real binary runs.
 */
@org.junit.jupiter.api.extension.ExtendWith(com.moddingfromamod.config.MagicianOn.class)
class MuseExecResearchTest {

    @TempDir
    Path tmp;

    private static final String DONE = "{\"payload_type\":\"run.terminal.completed\",\"payload\":"
            + "{\"terminal\":\"completed\",\"text\":\"{\\\"parts\\\":[\\\"head\\\"],\\\"confidence\\\":\\\"HIGH\\\"}\"}}";

    private final String museBefore = System.getProperty("moddingfromamod.muse.bin");
    private final String shimBefore = System.getProperty("moddingfromamod.muse.shim");
    private final java.util.function.Supplier<String> keyBefore = MuseExec.keySource;

    @AfterEach
    void restore() {
        put("moddingfromamod.muse.bin", museBefore);
        put("moddingfromamod.muse.shim", shimBefore);
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

    private static void webOnly(List<String> argv) {
        assertEquals("run.toolset=[\"web_search\",\"web_fetch\"]", argv.get(argv.indexOf("-c") + 1),
                "exactly the two web tools: " + argv);
        assertTrue(argv.containsAll(List.of("--disable-shell", "--disable-write", "--disable-request-user-input",
                "--no-foreign-personal-context", "--no-session-log")), "nothing else it could use: " + argv);
        assertEquals("off", argv.get(argv.indexOf("--contributor-mode") + 1));
        assertFalse(argv.contains("--disable-web-tools") || argv.contains("--preset"),
                "the graders' lockdown would take the web away too: " + argv);
        assertFalse(argv.stream().anyMatch(a -> a.contains("mcp")), "no MCP server, so no wish tool: " + argv);
        assertEquals("muse-ember-1.0", argv.get(argv.indexOf("--model") + 1));
        assertEquals("high", argv.get(argv.indexOf("--reasoning-effort") + 1));
    }

    @Test
    void theSubscriptionResearchesWithTheWebToolsOnly() throws Exception {
        Path muse = script("muse", "echo nope");
        Path shim = script("muse-cli-shim.py", "printf '%s\\n' \"$@\" > '" + tmp.resolve("shim-argv") + "'\necho '"
                + DONE + "'");
        System.setProperty("moddingfromamod.muse.bin", muse.toString());
        System.setProperty("moddingfromamod.muse.shim", shim.toString());
        MuseExec.keySource = () -> "LLM_realkey";
        MuseExec.Answer a = MuseExec.research("SYSTEM", "Find out what Tabbycat looks like.", "muse-ember-1.0", "high",
                10_000, List.of(com.moddingfromamod.config.HelperRoute.Step.MUSE_SUBSCRIPTION, com.moddingfromamod.config.HelperRoute.Step.MUSE_KEY));
        assertNotNull(a);
        assertEquals(MuseExec.ARM_SUBSCRIPTION, a.arm());
        assertTrue(a.text().contains("\"confidence\":\"HIGH\""), a.text());
        List<String> argv = Files.readAllLines(tmp.resolve("shim-argv"));
        assertEquals("grade", argv.get(0), "the shim's subscription step, by the graders' sticky policy");
        webOnly(argv);
        assertFalse(argv.contains("--api-key-stdin"));
    }

    @Test
    void aSkippedSubscriptionResearchesOnTheKey() throws Exception {
        Path muse = script("muse", "cat > '" + tmp.resolve("muse-stdin") + "'\nprintf '%s\\n' \"$@\" > '"
                + tmp.resolve("muse-argv") + "'\necho '" + DONE + "'");
        Path shim = script("muse-cli-shim.py", "echo '{\"shim\":\"grade\",\"arm\":\"api\"}'\nexit "
                + MuseExec.SHIM_SKIPPED);
        System.setProperty("moddingfromamod.muse.bin", muse.toString());
        System.setProperty("moddingfromamod.muse.shim", shim.toString());
        MuseExec.keySource = () -> "LLM_realkey";
        MuseExec.Answer a = MuseExec.research("SYSTEM", "Find it.", "muse-ember-1.0", "high", 10_000, List.of(com.moddingfromamod.config.HelperRoute.Step.MUSE_SUBSCRIPTION, com.moddingfromamod.config.HelperRoute.Step.MUSE_KEY));
        assertNotNull(a);
        assertEquals(MuseExec.ARM_API, a.arm());
        List<String> argv = Files.readAllLines(tmp.resolve("muse-argv"));
        webOnly(argv);
        assertTrue(argv.contains("--api-key-stdin"));
        assertEquals("LLM_realkey", Files.readString(tmp.resolve("muse-stdin")));
    }

    @Test
    void noAnswerIsNull() throws Exception {
        Path muse = script("muse", "echo 'not json'");
        System.setProperty("moddingfromamod.muse.bin", muse.toString());
        System.setProperty("moddingfromamod.muse.shim", "none");
        MuseExec.keySource = () -> "LLM_realkey";
        assertNull(MuseExec.research("SYSTEM", "Find it.", "muse-ember-1.0", "high", 10_000, List.of(com.moddingfromamod.config.HelperRoute.Step.MUSE_SUBSCRIPTION, com.moddingfromamod.config.HelperRoute.Step.MUSE_KEY)),
                "no answer: the caller caches nothing and the wish goes on");
    }
}
