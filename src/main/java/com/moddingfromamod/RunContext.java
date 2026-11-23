package com.moddingfromamod;

/**
 * Is this a real play session, or a test?
 *
 * <p>Exists because the improvement loop was being fed its own test traffic. Every wish and agent
 * action is logged to one file, and the gates write to it too — so a report meant to answer
 * <i>"what does the child keep asking for that we can't do?"</i> was counting deliberate failure
 * probes ({@code always_fails}, {@code boom}, the {@code execute as @a run stop} allowlist test) as
 * real agent failures. Thirteen of seventeen "failures" in one report were fixtures.
 *
 * <p>The fix has to be <b>at the source</b>. Filtering by tool name in the reporting script is a
 * denylist that silently rots the moment someone adds a new fixture, and it cannot tell a real
 * {@code run_command} in a test from a real one in play. The game knows perfectly well which it is,
 * so it stamps every record.
 */
public final class RunContext {

    private RunContext() {
    }

    /**
     * True when this JVM is running tests rather than someone actually playing.
     *
     * <p>Deliberately independent of whether an agent was force-enabled: a client test run with
     * {@code -Ptestprops="agent=on"} is still a test, and its traffic must not pollute the signal.
     */
    /**
     * Test-only escape hatch: assert that this JVM should be treated as real play.
     *
     * <p>Needed because {@code RuntimeConfig.persistDir()} deliberately returns null on a test run
     * so a gate can never publish into the real Mod Repo — which also blocks the one unit test
     * whose job is to prove config-file layering works. That test points at a temp directory, so
     * it is safe; it just needs a way to say so.
     *
     * <p>Named for what it does and documented as test-only rather than hidden, because a
     * mechanism nobody can find gets reinvented worse.
     */
    public static final String FORCE_REAL_RUN = ModdingFromAModMod.MODID + ".forceRealRun";

    public static boolean isTestRun() {
        if ("true".equals(System.getProperty(FORCE_REAL_RUN))) {
            return false;
        }
        // The automated client test (Gate C) drives the game itself.
        if ("true".equals(System.getProperty(ModdingFromAModMod.MODID + ".boottest"))) {
            return true;
        }
        // The headless GameTest server (Gate B).
        if (System.getProperty("forge.gameTestServer") != null
                || System.getProperty("neoforge.gameTestServer") != null) {
            return true;
        }
        if (System.getProperty("sun.java.command", "").toLowerCase().contains("gametest")) {
            return true;
        }
        // PLAIN JUNIT (Gate A). Missed by every check above, and it is where the deliberate
        // failure fixtures live -- always_fails, boom, no_such_tool. A census of the log found
        // them still present at 198 calls each AFTER filtering on this flag, because a Gradle
        // test worker is neither boottest nor a gametest server. Gradle stamps its workers, and
        // the JUnit API is only on the classpath when tests are running.
        if (System.getProperty("org.gradle.test.worker") != null) {
            return true;
        }
        try {
            Class.forName("org.junit.jupiter.api.Test", false,
                    RunContext.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
