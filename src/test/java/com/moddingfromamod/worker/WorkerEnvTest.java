package com.moddingfromamod.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** A worker must not inherit the launching session's identity, and must keep its sign-in. */
class WorkerEnvTest {

    private static Map<String, String> cloudSession() {
        Map<String, String> env = new HashMap<>();
        // A sample of what a cloud container really exported on 2026-09-22.
        env.put("CLAUDE_CODE_SESSION_ID", "parent");
        env.put("CLAUDE_CODE_REMOTE_SESSION_ID", "session_parent");
        env.put("CLAUDE_CODE_REMOTE", "true");
        env.put("CLAUDE_CODE_MESSAGING_SOCKET", "/tmp/sock");
        env.put("CLAUDE_CODE_MESSAGING_TOKEN", "t");
        env.put("CLAUDE_CODE_DIAGNOSTICS_FILE", "/tmp/diag");
        env.put("CLAUDECODE", "1");
        env.put("CLAUDE_EFFORT", "medium");
        env.put("CLAUDE_SESSION_INGRESS_TOKEN_FILE", "/tmp/ingress");
        // What reaches a model, and must survive.
        env.put("ANTHROPIC_BASE_URL", "http://127.0.0.1:1");
        env.put("CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat-x");
        env.put("CLAUDE_CONFIG_DIR", "/home/x/.claude");
        env.put("CLAUDE_CODE_USE_BEDROCK", "1");
        env.put("CLAUDE_CLI", "/opt/claude");
        env.put("PATH", "/usr/bin");
        env.put("HOME", "/home/x");
        return env;
    }

    @Test
    void theParentSessionsIdentityIsDropped() {
        Map<String, String> env = cloudSession();
        int removed = WorkerEnv.scrub(env);
        assertEquals(9, removed);
        for (String k : env.keySet()) {
            assertTrue(!k.startsWith("CLAUDE") || WorkerEnv.KEEP.contains(k), "leaked " + k);
        }
        assertFalse(env.containsKey("CLAUDE_CODE_SESSION_ID"));
        assertFalse(env.containsKey("CLAUDE_CODE_REMOTE"));
        assertFalse(env.containsKey("CLAUDECODE"));
    }

    @Test
    void signInConfigAndProviderSurvive() {
        Map<String, String> env = cloudSession();
        WorkerEnv.scrub(env);
        for (String k : new String[] {"ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN",
                "CLAUDE_CONFIG_DIR", "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CLI", "PATH", "HOME"}) {
            assertTrue(env.containsKey(k), k + " is needed to reach a model");
        }
    }

    /** On the home Mac the sign-in is the keychain and nothing CLAUDE-shaped is exported. */
    @Test
    void aCleanEnvironmentIsUntouched() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin", "HOME", "/Users/x"));
        assertEquals(0, WorkerEnv.scrub(env));
        assertEquals(2, env.size());
    }
}
