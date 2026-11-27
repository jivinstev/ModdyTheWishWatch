package com.moddingfromamod.gatec;

/**
 * When a film's empty stage has finished drawing and may become its reference frame. Pure: no Minecraft, so the rule
 * is unit-tested; {@link ClientHarness#settledStage} feeds it what the client reports and carries out its answer.
 *
 * <p>A resource reload rebuilds every chunk section, and on a slow software renderer they come back a few at a time
 * after the loading overlay has gone, while terrain at the edge of the view still arrives from the server a batch at a
 * time. So a frame is the reference only once nothing is left to build or upload, and two frames {@link #STILL_TICKS}
 * apart agree with no chunk arriving and no section drawn between them. A stage that has not settled within
 * {@link #SETTLE_MAX} ticks is never judged: the film gives up ("could not run"), it never falls back to an unsettled
 * frame.
 */
final class StageSettle {

    /** Client ticks between the two frames that must agree. */
    static final int STILL_TICKS = 20;
    /** Ticks to wait for the stage to settle; past them the film could not run. */
    static final int SETTLE_MAX = 400;
    /** A pixel that moved by more than this (summed over r, g and b) means the stage is still being drawn. */
    static final int PIXEL_DELTA = 24;

    /** What the caller does this tick. */
    enum Step {
        /** Nothing yet: call again next tick. */
        WAIT,
        /** Take a frame (and the client's counts) and {@link #offer} it. */
        SAMPLE,
        /** The stage never settled: end the film as "could not run", with {@link #lastDiff()}. Said once. */
        GIVE_UP
    }

    private int[][] candidate;
    private int candidateTick;
    private int candidateChunks;
    private int candidateSections;
    private String lastDiff;
    private boolean gaveUp;

    /**
     * @param ticks the film's tick counter (a smaller one than the last sample's starts a new film)
     * @param idle  no chunk section is queued or waiting to upload
     */
    Step step(int ticks, boolean idle) {
        if (gaveUp) {
            return Step.WAIT;
        }
        if (candidate != null && ticks < candidateTick) {
            candidate = null;
            lastDiff = null;
        }
        if (ticks >= SETTLE_MAX) {
            gaveUp = true;
            return Step.GIVE_UP;
        }
        if (!idle || (candidate != null && ticks - candidateTick < STILL_TICKS)) {
            return Step.WAIT;
        }
        return Step.SAMPLE;
    }

    /**
     * A frame taken on {@link Step#SAMPLE}, with the chunks loaded and sections drawn when it was taken.
     *
     * @return the frame when it is the settled reference, else null
     */
    int[][] offer(int[][] frame, int ticks, int chunks, int sections) {
        int[][] before = candidate;
        int chunksBefore = candidateChunks;
        int sectionsBefore = candidateSections;
        candidate = frame;
        candidateTick = ticks;
        candidateChunks = chunks;
        candidateSections = sections;
        if (before == null) {
            return null;
        }
        lastDiff = frameDiff(before, frame);
        if (lastDiff == null && (chunks != chunksBefore || sections != sectionsBefore)) {
            lastDiff = "frames agree, but the client went from " + chunksBefore + " to " + chunks
                    + " chunks loaded and " + sectionsBefore + " to " + sections + " sections drawn";
        }
        if (lastDiff == null) {
            candidate = null;
            return frame;
        }
        return null;
    }

    /** How the last two frames differed, or null when none were compared yet. */
    String lastDiff() {
        return lastDiff;
    }

    /** Null when the two frames agree; otherwise how they differ (pixels over the bar, their box, the largest change). */
    static String frameDiff(int[][] a, int[][] b) {
        if (a.length != b.length || a.length == 0 || a[0].length != b[0].length) {
            return "the frame size changed";
        }
        int n = 0;
        int max = 0;
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < a.length; y++) {
            for (int x = 0; x < a[y].length; x++) {
                int p = a[y][x];
                int q = b[y][x];
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > PIXEL_DELTA) {
                    n++;
                    max = Math.max(max, d);
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return n == 0 ? null : n + " px moved by over " + PIXEL_DELTA + " (largest " + max + ") in (" + x0 + ","
                + y0 + ")-(" + x1 + "," + y1 + ") of " + a[0].length + "x" + a.length;
    }
}
