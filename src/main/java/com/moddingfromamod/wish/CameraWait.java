package com.moddingfromamod.wish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * <b>A camera that SLEEPS between wishes, and how long a wish waited for it to wake.</b>
 *
 * <p>The policy: <i>zero RUNNING cameras at rest — cameras sit suspended and a
 * wish resumes one.</i> So on a world whose cameras live on VMs ({@code moddingfromamod.camera.demand
 * = wishes}) a camera is WANTED
 * while a wish is in flight, not while a child is merely online, and it lingers {@link #lingerMs()}
 * (60 s there) after the last one. The wish starts the camera when it is SUBMITTED — the agent's
 * first photograph is usually a minute or more later, so a resume (~10–20 s) is mostly paid while
 * nobody waits.
 *
 * <p>"Mostly" is the number that matters: <b>how long the wish's FIRST photograph waited on a
 * camera resume or create</b> — 0 when a camera was already there. When the first photograph comes
 * before the camera has joined, it WAITS for it (bounded by {@link #maxWaitMs()}) rather than borrow
 * the child's eyes: in this mode the camera is not optional, it is late. Each wish's first wait is
 * one line of {@code <home>/camera-waits.jsonl}, which an operator's tool can fold into p50/p95/%
 * delayed.
 *
 * <p>Minecraft-free: the supervisor's state comes in as suppliers, the clock and the sleep are
 * injected, and the file is a parameter — so every branch is a plain unit test.
 */
public final class CameraWait {

    private CameraWait() {
    }

    /** {@code children} (the default: a camera while a child is online) or {@code wishes}. */
    public static final String DEMAND = "moddingfromamod.camera.demand";
    public static final String LINGER_MS = "moddingfromamod.camera.lingerMs";
    public static final String WAIT_MS = "moddingfromamod.camera.waitMs";

    public static boolean demandIsWishes() {
        return "wishes".equalsIgnoreCase(System.getProperty(DEMAND, "children").trim());
    }

    /** How long the camera stays after the last child (children mode) or the last wish (wishes mode). */
    public static long lingerMs() {
        return Math.max(0, Long.getLong(LINGER_MS, CameraLifecycle.LINGER_MS));
    }

    /** The longest a first photograph waits for a sleeping camera — CameraLifecycle's start bound. */
    public static long maxWaitMs() {
        return Math.max(0, Long.getLong(WAIT_MS, CameraLifecycle.START_TIMEOUT_MS));
    }

    /** How the first photograph of a wish found its camera. */
    public enum How {
        /** A camera was in the world: no wait. */
        FREE,
        /** It waited for one to resume / be created, and got it. */
        WOKEN,
        /** It waited the whole bound and none came: it fell back (the child's eyes, or no photo). */
        TIMED_OUT,
        /** No camera in this mode, or the supervisor gave up: nothing to wait for. */
        NONE
    }

    /** What happened, and how long it took. */
    public record Result(How how, long waitedMs) {
    }

    /**
     * Should a photograph wait for a camera now? Only in wishes mode, only while none is serving, and
     * never once the supervisor has given up (a camera that will not start is not worth a wish's
     * minute). Pure.
     */
    public static boolean shouldWait(boolean demandWishes, boolean serving, boolean gaveUp) {
        return demandWishes && !serving && !gaveUp;
    }

    /**
     * How long after its start a camera that has not joined is still worth waiting for. Measured
     * joins on a dedicated server (2026-09-24 … 10-21, n=15): median 20.0 s, p90 23.3 s, one at 98 s, and one
     * never (the gold-dragon demo, a zone with no capacity).
     */
    public static final long LATE_JOIN_MS = 60_000;

    /**
     * <b>How long this photograph may wait for a camera that has not joined.</b>
     *
     * <p>Since 2026-10-21 the photograph is taken AFTER the wish has ended for the child
     * ({@code FinishBuildingTool}: the end plays first), so waiting here no longer holds up his
     * pillar or his "done". It holds up only the description line and the picture on his account
     * page, and a wait that gives up costs both. So the bound is generous: up to
     * {@link #LATE_JOIN_MS} from the camera's start, which covers every measured join but the
     * 98 s one, and a camera that has had that long and still is not here is not waited for.
     * (The first cut of this, while the photo still came first, was 25 s, on
     * the faster Haiku 5.5 grades bringing the first photo sooner after submit.) A camera that is
     * merely busy with another wish's shot is a different wait, the capture lease's.
     *
     * @param maxMs         the configured ceiling ({@link #maxWaitMs()})
     * @param startedAgoMs  how long ago the camera was started; 0 when it is being started now
     */
    public static long budgetMs(long maxMs, long startedAgoMs) {
        long left = LATE_JOIN_MS - Math.max(0, startedAgoMs);
        return Math.max(0, Math.min(maxMs, left));
    }

    /**
     * Wait — or not — for a camera, polling {@code serving} every {@code pollMs}. Pure apart from the
     * injected clock and sleep.
     */
    public static Result await(boolean demandWishes, BooleanSupplier serving, BooleanSupplier gaveUp,
                               long maxMs, long pollMs, LongSupplier clockMs, Sleeper sleep) {
        if (serving.getAsBoolean()) {
            return new Result(How.FREE, 0);
        }
        if (!shouldWait(demandWishes, false, gaveUp.getAsBoolean())) {
            return new Result(How.NONE, 0);
        }
        long t0 = clockMs.getAsLong();
        while (clockMs.getAsLong() - t0 < maxMs) {
            if (gaveUp.getAsBoolean()) {
                return new Result(How.TIMED_OUT, clockMs.getAsLong() - t0);
            }
            sleep.sleep(pollMs);
            if (serving.getAsBoolean()) {
                return new Result(How.WOKEN, clockMs.getAsLong() - t0);
            }
        }
        return new Result(How.TIMED_OUT, clockMs.getAsLong() - t0);
    }

    /** A sleep that a test can replace with advancing a fake clock. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms);
    }

    // ── one line per wish ───────────────────────────────────────────────────────────────────────

    private static final Set<UUID> RECORDED = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> e) {
            return size() > 4096;
        }
    });

    /** Is this the wish's FIRST photograph (and so the one to record)? Marks it. */
    public static synchronized boolean firstFor(UUID wish) {
        return wish != null && RECORDED.add(wish);
    }

    /** The JSONL line for one wish. Pure. */
    public static String line(long atMs, UUID wish, Result r, boolean demandWishes) {
        return line(atMs, wish, r, demandWishes, null);
    }

    /** The same with the wish's camera class ({@link CameraNeed}), so a wait can be read per class. */
    public static String line(long atMs, UUID wish, Result r, boolean demandWishes, String cls) {
        String c = cls == null || cls.isEmpty() || !cls.matches("[a-z]{1,16}") ? "" : ",\"cls\":\"" + cls + "\"";
        return "{\"at\":" + atMs / 1000 + ",\"wish\":\"" + wish + "\",\"how\":\"" + r.how().name().toLowerCase()
                + "\",\"waitedMs\":" + r.waitedMs() + ",\"mode\":\"" + (demandWishes ? "wishes" : "children")
                + "\"" + c + "}";
    }

    /** Append it; a failure to write never touches the wish. */
    public static void record(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // the metric is worth less than the wish
        }
    }

    public static Path file() {
        return com.moddingfromamod.runtime.RuntimeConfig.home().resolve("camera-waits.jsonl");
    }
}
