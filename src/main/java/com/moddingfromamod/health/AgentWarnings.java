package com.moddingfromamod.health;

/**
 * <b>The last thing the agent pool complained about, kept where a health snapshot can see it.</b>
 *
 * <p>{@link com.moddingfromamod.agent.AgentSession}'s startup failures are {@code WARN} lines on the
 * server console and nowhere else — plan §2.7: <i>"the child sees a progress bar that never
 * finishes"</i>. The CLI's own {@code agent*.log} cannot carry them, because in every one of those
 * cases <b>no CLI was ever started</b>: there is no file to read. So the one process that knows
 * remembers the sentence, and {@link HealthService} feeds it to {@link AgentFailureClassifier}
 * beside the logs it can read.
 *
 * <p>Pure, static and tiny on purpose. It is not a log: it keeps exactly one line, because the
 * question it answers is "what is wrong NOW", and a buffer would invite somebody to build a second
 * logging system next to slf4j.
 */
public final class AgentWarnings {

    private static volatile String last = "";
    private static volatile long lastAtMs;

    private AgentWarnings() {
    }

    /** Remember one warning, exactly as it was logged. Blank text is ignored. */
    public static void note(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        last = line;
        lastAtMs = System.currentTimeMillis();
    }

    /** The last warning, or {@code ""}. */
    public static String last() {
        return last;
    }

    /** When it was noted, or 0. */
    public static long lastAtMs() {
        return lastAtMs;
    }

    /** The pool is healthy again — used on a successful start, and by tests. */
    public static void clear() {
        last = "";
        lastAtMs = 0L;
    }
}
