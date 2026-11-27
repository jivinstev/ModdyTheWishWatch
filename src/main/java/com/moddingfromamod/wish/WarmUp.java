package com.moddingfromamod.wish;

import java.util.function.BooleanSupplier;

/**
 * A job that runs ahead of need on its own thread (the Apprentice's matcher, built when the world is up), and how it
 * ends. Pure: no Minecraft, so a stop during the job is driven in a unit test.
 *
 * <p>The server can stop while the job is still running: a world opened and closed at once, or a server booted
 * only to check that it boots. Classes the job has not loaded yet may then fail to load, as a
 * {@link NoClassDefFoundError} or another {@link LinkageError}, because the game is shutting down around it.
 * That is expected and is logged quietly. The same failure while the server is still running is a real fault and is
 * logged at ERROR, never swallowed. A job that never finished costs nothing: the matcher is built again at the first
 * wish.
 *
 * <p>Nothing on that path may load a class from the mod's jar, because once the server has stopped the jar can be
 * closed and the load itself fails. That load would be a second {@link NoClassDefFoundError}, thrown from inside the
 * handler, which no catch here sees. So {@link Outcome} is loaded with this class ({@link #OUTCOMES}), and the caller
 * loads this class on the server thread before the job's thread starts ({@link #load()}).
 */
public final class WarmUp {

    /** How a warm-up ended. */
    public enum Outcome {
        /** The job finished. */
        BUILT,
        /** The server had already stopped, so the job never started. */
        NOT_STARTED,
        /** The server stopped while the job ran, and the job then failed: expected, quiet. */
        STOPPED,
        /** The job failed while the server was running: logged as a fault. */
        FAILED
    }

    /** Where a warm-up's outcome is told. */
    public interface Log {
        void debug(String message);

        void warn(String message);

        void error(String message, Throwable cause);
    }

    /** Every outcome, loaded with this class so that judging a failure never loads one. */
    private static final Outcome[] OUTCOMES = Outcome.values();

    private WarmUp() {
    }

    /**
     * Load this class and everything {@link #run} needs to judge a failure. Call it on the thread that starts the
     * warm-up, while the server is up, so that a stop before the job's thread starts cannot fail to load it.
     *
     * @return how many outcomes there are (a value, so the call is not optimised away)
     */
    public static int load() {
        return OUTCOMES.length;
    }

    /**
     * Run {@code job} unless the server has stopped, and judge any failure by whether the server was still running
     * when it happened.
     *
     * @param what    what is being warmed, for the log ("the Apprentice's matcher")
     * @param running true while the server runs; read before the job and again when it fails
     */
    public static Outcome run(String what, BooleanSupplier running, Runnable job, Log log) {
        if (!running.getAsBoolean()) {
            log.debug("warm-up: the server stopped before " + what + " was built; it is built at the first wish");
            return Outcome.NOT_STARTED;
        }
        try {
            job.run();
            return Outcome.BUILT;
        } catch (Throwable t) {
            if (!running.getAsBoolean()) {
                log.debug("warm-up: the server stopped while " + what + " was being built (" + t
                        + "); it is built at the first wish");
                return Outcome.STOPPED;
            }
            if (t instanceof RuntimeException) {
                log.warn("warm-up: could not build " + what + " ahead of the first wish: " + t);
            } else {
                log.error("warm-up: " + what + " failed while the server is running", t);
            }
            return Outcome.FAILED;
        }
    }
}
