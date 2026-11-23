package com.moddingfromamod.lite;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.vanilla.RigCourse;
import com.moddingfromamod.vanilla.RigTurn;
import org.junit.jupiter.api.Test;

/**
 * <b>Does a flier face the way it travels?</b> (the owner on the fly film: "which one is pointed the direction
 * they should?"). The fly stage's loop drives the rig's own heading machinery (RigCourse picks where it is going,
 * RigTurn turns the drawn body toward it), and the drawn yaw is compared, every tick, with the direction the flier is
 * really travelling. Pure: the same classes the server runs, on the stage's own path.
 */
class FlyStageHeadingTest {

    static float apart(float a, float b) {
        float d = (a - b) % 360f;
        if (d > 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return Math.abs(d);
    }

    /** {mean, worst} degrees between the drawn yaw and the travel direction over laps 2 and 3. */
    static float[] error() {
        RigCourse course = new RigCourse();
        RigTurn turn = new RigTurn();
        double[] first = FlyStage.at(0);
        turn.snap((float) first[3]);
        course.reset(first[0], first[2], (float) first[3]);
        double sum = 0;
        float worst = 0;
        int n = 0;
        // every tick, as VanillaRigAnimator drives it (onServerTick -> driveOne): RigCourse's window is in ticks
        for (int t = 1; t < 3 * FlyStage.LAP_TICKS; t++) {
            double[] p = FlyStage.at(t);
            float c = course.at(p[0], p[2], (float) p[3]);
            float drawn = course.drawn(turn, c);
            if (t >= FlyStage.LAP_TICKS) {
                float e = apart(drawn, (float) p[3]);
                sum += e;
                worst = Math.max(worst, e);
                n++;
            }
        }
        return new float[] {(float) (sum / n), worst};
    }

    /**
     * No jitter: round the loop the
     * drawn yaw never turns more than RigTurn.MAX in one tick, and never turns back and forth. The first version snapped
     * 15 degrees in one tick on the tick it started travelling.
     */
    @Test
    void itTurnsSmoothlyWithNoSnapAndNoFlicker() {
        com.moddingfromamod.vanilla.RigCourse course = new com.moddingfromamod.vanilla.RigCourse();
        com.moddingfromamod.vanilla.RigTurn turn = new com.moddingfromamod.vanilla.RigTurn();
        double[] first = FlyStage.at(0);
        turn.snap((float) first[3]);
        course.reset(first[0], first[2], (float) first[3]);
        float last = turn.drawn();
        float lastD = 0;
        float max = 0;
        int reversals = 0;
        for (int t = 1; t < 3 * FlyStage.LAP_TICKS; t++) {
            double[] p = FlyStage.at(t);
            float drawn = course.drawn(turn, course.at(p[0], p[2], (float) p[3]));
            float d = drawn - last;
            while (d > 180f) {
                d -= 360f;
            }
            while (d < -180f) {
                d += 360f;
            }
            max = Math.max(max, Math.abs(d));
            if (d != 0 && lastD != 0 && Math.signum(d) != Math.signum(lastD)) {
                reversals++;
            }
            if (d != 0) {
                lastD = d;
            }
            last = drawn;
        }
        System.out.printf("fly stage turning: at most %.1f deg in a tick, %d reversals%n", max, reversals);
        assertTrue(max <= com.moddingfromamod.vanilla.RigTurn.MAX + 0.01f, "a snap of " + max + " deg in one tick");
        assertTrue(reversals == 0, reversals + " back-and-forth turns on a steady loop");
    }

    @Test
    void theFlierFacesWhereItTravels() {
        float[] e = error();
        System.out.printf("fly stage heading: drawn against travel, mean %.1f deg, worst %.1f deg%n", e[0], e[1]);
        assertTrue(e[1] <= 5f, String.format("the flier faces %.1f deg (worst) off where it flies", e[1]));
    }
}
