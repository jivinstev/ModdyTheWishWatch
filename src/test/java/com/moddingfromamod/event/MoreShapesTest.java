package com.moddingfromamod.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.moddingfromamod.event.shape.CrackShape;
import com.moddingfromamod.event.shape.Countdown;
import com.moddingfromamod.event.shape.SkyDrop;
import com.moddingfromamod.event.shape.SlideShape;
import org.junit.jupiter.api.Test;

/** spreading_cracks, slide, from_the_sky and countdown (E2). */
class MoreShapesTest {

    @Test
    void aCrackWandersAndIsTheSameCrackEveryTime() {
        CrackShape s = new CrackShape(1, 40, 5);
        List<CrackShape.Step> a = s.steps(7);
        assertEquals(a, s.steps(7), "seeded: a replay opens the same crack");
        CrackShape.Step first = a.get(0), last = a.get(a.size() - 1);
        double straight = Math.hypot(last.dx() - first.dx(), last.dz() - first.dz());
        Set<Integer> headings = new HashSet<>();
        for (int i = 1; i < a.size(); i++) {
            headings.add((int) Math.round(Math.toDegrees(Math.atan2(a.get(i).dz() - a.get(i - 1).dz(),
                    a.get(i).dx() - a.get(i - 1).dx()))));
        }
        assertTrue(headings.size() >= 3, "a straight line reads as a trench somebody dug");
        assertTrue(straight > 15, "but it still gets somewhere: " + straight);
        for (CrackShape.Step st : a) {
            assertTrue(st.depth() >= CrackShape.MIN_DEPTH && st.depth() <= 5);
        }
    }

    @Test
    void severalCracksGoDifferentWays() {
        List<CrackShape.Step> a = new CrackShape(4, 20, 4).steps(3);
        Set<String> quadrants = new HashSet<>();
        for (CrackShape.Step s : a) {
            if (Math.abs(s.dx()) + Math.abs(s.dz()) > 10) {
                quadrants.add((s.dx() >= 0 ? "E" : "W") + (s.dz() >= 0 ? "S" : "N"));
            }
        }
        assertTrue(quadrants.size() >= 3, "cracks spread outward all round, not in one bundle: " + quadrants);
    }

    @Test
    void aSlideScalesWithTheDropAndNeedsAHill() {
        assertEquals(0, SlideShape.runOut(SlideShape.MIN_DROP - 1), "a plain is not a hillside");
        assertTrue(SlideShape.runOut(20) > SlideShape.runOut(8));
        assertTrue(SlideShape.width(20) > SlideShape.width(8));
        assertTrue(SlideShape.depth(200) <= 4 && SlideShape.runOut(500) <= SlideShape.MAX_RUNOUT);
        assertTrue(SlideShape.pileHeight(1000) <= 5, "a heap, not a tower");
    }

    @Test
    void theCrownIsThrownFurthestAndTheRubbleFansOut() {
        assertTrue(SlideShape.landing(30, 1.0) > SlideShape.landing(30, 0.0),
                "let go with no push, a block lands back in its own footprint");
        assertTrue(SlideShape.landing(30, 0.0) > 0);
        Set<Integer> sides = new HashSet<>();
        for (long s = 0; s < 200; s++) {
            int x = SlideShape.scatter(s);
            assertTrue(Math.abs(x) <= SlideShape.MAX_SCATTER);
            sides.add(x);
        }
        assertEquals(2 * SlideShape.MAX_SCATTER + 1, sides.size(), "scattered, not restacked");
        assertTrue(SlideShape.scarHalfWidth(20, 0.0) > SlideShape.scarHalfWidth(20, 1.0),
                "the scar fans out toward the toe");
    }

    @Test
    void theSkyDropsEvenlyInsideTheAreaAndRepeatsOnAReplay() {
        SkyDrop s = new SkyDrop(20, 50, 40);
        int inner = 0, n = 0;
        for (int t = 0; t < s.ticks; t++) {
            for (SkyDrop.Drop d : s.at(9, t)) {
                double r = Math.hypot(d.dx(), d.dz());
                assertTrue(r <= 20 + 1e-9);
                inner += r <= 10 ? 1 : 0;
                n++;
            }
        }
        double share = inner / (double) n;
        assertTrue(share > 0.18 && share < 0.32,
                "the inner half-radius is a quarter of the area, so about a quarter of drops: " + share);
        assertEquals(s.at(9, 3)[0], s.at(9, 3)[0]);
        assertEquals(2000, s.total());
    }

    @Test
    void theCountdownIsFairAndGetsLouderAtTheEnd() {
        Countdown c = new Countdown(45);
        assertEquals(45, c.secondsLeft(0));
        assertEquals(0, c.secondsLeft(c.ticks()));
        for (int s = 1; s <= 10; s++) {
            assertTrue(c.announces(s), "every one of the last ten is said: " + s);
        }
        int early = 0;
        for (int s = 11; s <= 45; s++) {
            early += c.announces(s) ? 1 : 0;
        }
        assertTrue(early <= 5, "no spam early on: " + early);
        assertTrue(c.announces(45), "it says when it starts");
        assertTrue(c.pitch(1) > c.pitch(5) && c.pitch(5) > c.pitch(10));
        assertTrue(c.pitch(1) <= 2.0f && c.pitch(30) >= 0.5f);
        assertEquals(Countdown.MAX_SECONDS, new Countdown(100000).seconds);
        assertEquals(Countdown.MIN_SECONDS, new Countdown(0).seconds);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("#1047: a shower of creatures is capped per size and spread over the whole shower")
    void aShowerOfCreaturesIsCappedAndSpreadOut() {
        int ticks = com.moddingfromamod.event.design.DesignSizes.SKY_TICKS;
        for (com.moddingfromamod.event.EventScale s : com.moddingfromamod.event.EventScale.values()) {
            int most = com.moddingfromamod.event.design.DesignSizes.skyCreatures(s);
            assertTrue(most <= 100, s + ": " + most + " creatures is a herd the server has to think for");
            int sum = 0, firstHalf = 0;
            for (int t = 0; t < ticks; t++) {
                int due = com.moddingfromamod.event.shape.SkyDrop.dueAt(most, ticks, t);
                sum += due;
                if (t < ticks / 2) {
                    firstHalf += due;
                }
            }
            assertEquals(most, sum, s + ": exactly the cap over the whole shower");
            assertTrue(Math.abs(firstHalf - most / 2) <= 1, s + ": spread out, not all at the start (" + firstHalf + ")");
        }
    }
}
