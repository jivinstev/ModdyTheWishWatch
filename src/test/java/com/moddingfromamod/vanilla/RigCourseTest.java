package com.moddingfromamod.vanilla;

import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A flier heads the way it has travelled, not the way it last flapped. */
class RigCourseTest {

    @Test
    @DisplayName("fluttering about one spot keeps the heading it had")
    void aHoverKeepsItsHeading() {
        RigCourse c = new RigCourse();
        c.reset(10, 10, 33f);
        Random r = new Random(5);
        for (int i = 0; i < 500; i++) {
            double x = 10 + (r.nextDouble() - 0.5) * RigCourse.STEP;
            double z = 10 + (r.nextDouble() - 0.5) * RigCourse.STEP;
            assertEquals(33f, c.at(x, z, 0f), 0f);
        }
    }

    @Test
    @DisplayName("flying east it heads east (-90), then north (180) when it turns north")
    void travelSetsTheHeading() {
        RigCourse c = new RigCourse();
        c.reset(0, 0, 33f);
        float h = 33f;
        for (int i = 1; i <= 40; i++) {
            h = c.at(i * 0.3, 0, 0f);
        }
        assertEquals(-90f, h, 1e-3f);
        for (int i = 1; i <= 40; i++) {   // longer than the window (24), so it holds only the new way
            h = c.at(12.0, -i * 0.3, 0f);
        }
        assertEquals(180f, Math.abs(h), 1e-3f);
    }

    @Test
    @DisplayName("the first call takes the yaw it is given")
    void theFirstCall() {
        assertEquals(71f, new RigCourse().at(1, 2, 71f), 0f);
    }
}
