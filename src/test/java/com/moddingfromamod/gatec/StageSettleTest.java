package com.moddingfromamod.gatec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * On 1.21.10 a film's empty-stage reference frame was taken before the stage had finished drawing, and every body box
 * became its whole column strip. A frame is the reference only once the world is idle and two frames
 * {@link StageSettle#STILL_TICKS} apart agree with no chunk arriving and no section drawn; a stage that never settles
 * gives up, never falls back to an unsettled frame.
 */
class StageSettleTest {

    private static int[][] frame(int rgb) {
        int[][] f = new int[4][6];
        for (int[] row : f) {
            java.util.Arrays.fill(row, rgb);
        }
        return f;
    }

    /** Drive one stage tick by tick until it answers a frame or gives up; -1 when it gave up. */
    private static int settleTick(StageSettle s, java.util.function.IntFunction<int[][]> frameAt,
            java.util.function.IntPredicate idleAt, java.util.function.IntUnaryOperator chunksAt) {
        for (int t = 11; t < 1000; t++) {
            switch (s.step(t, idleAt.test(t))) {
                case GIVE_UP:
                    return -1;
                case SAMPLE:
                    if (s.offer(frameAt.apply(t), t, chunksAt.applyAsInt(t), 100) != null) {
                        return t;
                    }
                    break;
                default:
                    break;
            }
        }
        throw new AssertionError("neither settled nor gave up");
    }

    @Test
    @DisplayName("a still, idle stage is the reference after two frames STILL_TICKS apart")
    void stillStageSettles() {
        StageSettle s = new StageSettle();
        int[][] f = frame(0x336699);
        assertEquals(11 + StageSettle.STILL_TICKS, settleTick(s, t -> f, t -> true, t -> 49));
    }

    @Test
    @DisplayName("the returned frame is the second, matching one")
    void returnsTheAgreeingFrame() {
        StageSettle s = new StageSettle();
        int[][] a = frame(0x336699);
        int[][] b = frame(0x336699);
        assertEquals(StageSettle.Step.SAMPLE, s.step(20, true));
        assertNull(s.offer(a, 20, 49, 100));
        assertEquals(StageSettle.Step.WAIT, s.step(20 + StageSettle.STILL_TICKS - 1, true));
        assertEquals(StageSettle.Step.SAMPLE, s.step(20 + StageSettle.STILL_TICKS, true));
        assertSame(b, s.offer(b, 20 + StageSettle.STILL_TICKS, 49, 100));
    }

    @Test
    @DisplayName("no frame is taken while sections are still building or uploading")
    void busyRendererWaits() {
        StageSettle s = new StageSettle();
        int[][] f = frame(0x336699);
        assertEquals(StageSettle.Step.WAIT, s.step(50, false));
        assertEquals(120 + StageSettle.STILL_TICKS, settleTick(s, t -> f, t -> t >= 120, t -> 49));
    }

    @Test
    @DisplayName("the floor still drawing (pixels moving) keeps it unsettled until two frames agree")
    void movingPixelsWait() {
        StageSettle s = new StageSettle();
        int[][] late = frame(0x336699);      // until tick 100 each frame is brighter than the last: still drawing
        int settled = settleTick(s, t -> t < 100 ? frame((t * 2) << 16) : late, t -> true, t -> 49);
        assertTrue(settled >= 100 + StageSettle.STILL_TICKS, "settled at " + settled);
    }

    @Test
    @DisplayName("frames that agree while chunks are still arriving are not yet the reference")
    void chunksArrivingWait() {
        StageSettle s = new StageSettle();
        int[][] f = frame(0x336699);
        int settled = settleTick(s, t -> f, t -> true, t -> Math.min(t, 150));
        assertTrue(settled > 150, "settled at " + settled);
        assertNull(s.lastDiff(), "the last comparison agreed");
    }

    @Test
    @DisplayName("a stage that never settles gives up once, with the last diff, and never answers a frame")
    void neverSettlesGivesUp() {
        StageSettle s = new StageSettle();
        int[][][] frames = {frame(0x000000), frame(0xFFFFFF)};
        assertEquals(-1, settleTick(s, t -> frames[(t / StageSettle.STILL_TICKS) % 2], t -> true, t -> 49));
        assertNotNull(s.lastDiff());
        assertTrue(s.lastDiff().contains("px moved"), s.lastDiff());
        assertEquals(StageSettle.Step.WAIT, s.step(StageSettle.SETTLE_MAX + 1, true), "GIVE_UP is said once");
    }

    @Test
    @DisplayName("a renderer that never goes idle gives up at SETTLE_MAX")
    void neverIdleGivesUp() {
        StageSettle s = new StageSettle();
        assertEquals(StageSettle.Step.WAIT, s.step(StageSettle.SETTLE_MAX - 1, false));
        assertEquals(StageSettle.Step.GIVE_UP, s.step(StageSettle.SETTLE_MAX, false));
        assertNull(s.lastDiff(), "no two frames were taken");
    }

    @Test
    @DisplayName("frameDiff: small noise is not movement; a change past the bar is boxed")
    void frameDiff() {
        int[][] a = frame(0x101010);
        int[][] b = frame(0x101010);
        b[1][2] = 0x101010 + 0x050505;      // 15 summed: noise
        assertNull(StageSettle.frameDiff(a, b));
        b[2][3] = 0xFFFFFF;
        assertEquals("1 px moved by over " + StageSettle.PIXEL_DELTA + " (largest 717) in (3,2)-(3,2) of 6x4",
                StageSettle.frameDiff(a, b));
        assertEquals("the frame size changed", StageSettle.frameDiff(a, new int[3][6]));
    }
}
