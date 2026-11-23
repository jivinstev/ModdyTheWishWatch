package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerCliConfigTest {

    private static final WishModel.Resolved SPARK =
            new WishModel.Resolved("muse", "muse-spark-1.2-contributor", "https://api.meta.ai", "LLM_x");
    private static final WishModel.Resolved OPUS = new WishModel.Resolved("", "opus", "", "");

    @Test
    @DisplayName("a redirected worker gets its own config dir per provider; one on this machine's sign-in keeps the default")
    void dirPerRedirect(@TempDir Path home) {
        assertEquals(home.resolve("cli").resolve("muse"), WorkerCliConfig.dirFor(home, SPARK));
        assertNull(WorkerCliConfig.dirFor(home, OPUS));
        assertNull(WorkerCliConfig.dirFor(home, null));
        assertEquals(home.resolve("cli").resolve("redirect"), WorkerCliConfig.dirFor(home,
                new WishModel.Resolved("../..", "m", "https://x", "k")));
    }

    @Test
    @DisplayName("the transcripts stay where every reader looks: projects/ links to the default one")
    void projectsAreShared(@TempDir Path tmp) throws Exception {
        Path def = tmp.resolve("dot-claude");
        Path own = tmp.resolve("mod/cli/muse");
        WorkerCliConfig.prepare(own, def);
        assertTrue(Files.isSymbolicLink(own.resolve("projects")));
        Files.writeString(own.resolve("projects").resolve("t.jsonl"), "{}");
        assertTrue(Files.exists(def.resolve("projects").resolve("t.jsonl")));
        WorkerCliConfig.prepare(own, def);   // idempotent
        assertTrue(Files.isSymbolicLink(own.resolve("projects")));
    }

    @Test
    @DisplayName("an existing real projects/ is never replaced (its transcripts would be lost)")
    void realProjectsLeftAlone(@TempDir Path tmp) throws Exception {
        Path own = tmp.resolve("own");
        Files.createDirectories(own.resolve("projects"));
        WorkerCliConfig.prepare(own, tmp.resolve("def"));
        assertFalse(Files.isSymbolicLink(own.resolve("projects")));
    }

    @Test
    @DisplayName("the variable is set after the redirect's CLAUDE* scrub, so it survives it")
    void survivesTheRedirectScrub() {
        Map<String, String> env = new HashMap<>();
        env.put("CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR", "3");
        WishModel.applyTo(env, SPARK);
        WorkerCliConfig.applyTo(env, Path.of("/m/cli/muse"));
        assertEquals("/m/cli/muse", env.get("CLAUDE_CONFIG_DIR"));
        assertFalse(env.containsKey("CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR"));
        Map<String, String> plain = new HashMap<>();
        WorkerCliConfig.applyTo(plain, null);
        assertTrue(plain.isEmpty());
    }
}
