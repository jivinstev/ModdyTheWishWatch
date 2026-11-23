package com.moddingfromamod.agent;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A grader's EMBER-ONLY prompt, beside the one its Haiku invocation was measured on.
 *
 * <p>Tuning a prompt for Ember must never move the Haiku grader: Haiku is the fallback when Muse Code
 * gives no answer, and its floors describe ITS prompt. So a resource {@code X.md} may have a fork
 * {@code X.ember.md}; a Muse call asks with the fork ({@link #forMuse}) and the Claude fallback with the
 * original it was handed. With no fork, both ask with the original.
 *
 * <p>Keyed on the original prompt's TEXT, so a transport that is handed only the text (the shape every
 * grader transport has) can find the fork without knowing which file it came from.
 */
public final class MusePrompts {

    private static final Map<String, String> FORKS = new ConcurrentHashMap<>();

    private MusePrompts() {
    }

    /** Remember {@code ember} as the Muse fork of {@code base}; a blank fork is ignored. */
    public static void register(String base, String ember) {
        if (base != null && !base.isBlank() && ember != null && !ember.isBlank()) {
            FORKS.put(base, ember);
        }
    }

    /** The prompt a Muse model is asked with: the registered fork, else the prompt itself. */
    public static String forMuse(String prompt) {
        return prompt == null ? null : FORKS.getOrDefault(prompt, prompt);
    }

    /**
     * Read a classpath prompt ({@code /safety/x.md}) and register its {@code .ember.md} fork when one
     * exists. Returns the ORIGINAL (stripped), or "" when absent, exactly as the callers read it before.
     */
    public static String load(Class<?> anchor, String path) {
        String base = read(anchor, path);
        if (!base.isEmpty() && path.endsWith(".md")) {
            register(base, read(anchor, path.substring(0, path.length() - 3) + ".ember.md"));
        }
        return base;
    }

    private static String read(Class<?> anchor, String path) {
        try (InputStream in = anchor.getResourceAsStream(path)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return "";
        }
    }
}
