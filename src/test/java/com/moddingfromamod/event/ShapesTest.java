package com.moddingfromamod.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.event.shape.FrontShape;
import com.moddingfromamod.event.shape.LayerShape;
import org.junit.jupiter.api.Test;

/** The feel rules of the event shapes (E2), ported from a mod repo's disasters and kept as rules, not tuning. */
class ShapesTest {

    // ---- moving_front ------------------------------------------------------------------------

    @Test
    void aFrontRearsUpHoldsThenEndsAtNothing() {
        assertTrue(FrontShape.heightFactor(0.0) < 1.0, "it rears up, it does not blink into being");
        assertEquals(1.0, FrontShape.heightFactor(0.4), 1e-9);
        assertEquals(0.0, FrontShape.heightFactor(1.0), 1e-9, "every front ends at nothing");
        double prev = 2;
        for (double p = FrontShape.BREAK_AT; p <= 1.0; p += 0.01) {
            double h = FrontShape.heightFactor(p);
            assertTrue(h <= prev + 1e-9, "after it breaks it only ever comes down");
            prev = h;
        }
    }

    @Test
    void itRollsForMostOfItsJourneyBeforeItBreaks() {
        assertTrue(FrontShape.BREAK_AT > 0.5, "break at once and what you built is a splash");
        assertEquals(FrontShape.Phase.ROLLING, FrontShape.phaseAt(0.3));
        assertEquals(FrontShape.Phase.TUMBLING, FrontShape.phaseAt(0.7));
        assertEquals(FrontShape.Phase.WASH, FrontShape.phaseAt(0.9));
        assertEquals(FrontShape.Phase.GONE, FrontShape.phaseAt(1.0));
        assertTrue(FrontShape.justBroke(0.57, 0.58) && !FrontShape.justBroke(0.6, 0.61),
                "the crash is announced exactly once");
    }

    @Test
    void aBreakingFrontCurlsOutPastItsOwnFaceAndANonBreakingOneDoesNot() {
        FrontShape wave = new FrontShape(20, 40, true);
        FrontShape wall = new FrontShape(20, 40, false);
        double p = (FrontShape.BREAK_AT + FrontShape.WASH_AT) / 2;
        double lean = wave.overhang(p) * wave.height * FrontShape.heightFactor(p);
        assertTrue(lean > FrontShape.THICKNESS,
                "the crest must end up further forward than the wall is thick, or it is a ramp");
        assertEquals(0.0, wall.overhang(p), 1e-9, "a sandstorm wall slumps, it does not curl");
        assertEquals(0.0, wave.overhang(0.3), 1e-9, "a rolling wall stands up straight");
    }

    @Test
    void theLeanSitsAtTheTop() {
        double low = FrontShape.leanAt(10, 2, 11);
        double high = FrontShape.leanAt(10, 10, 11);
        assertEquals(10.0, high, 1e-9);
        assertTrue(low < high / 10, "spread evenly it would be a ramp; concentrated at the top it is a curl");
    }

    @Test
    void theCrestIsBehindTheFaceAndTheShouldersAreShort() {
        assertTrue(FrontShape.depthFactor(1) > FrontShape.depthFactor(0), "a lip to curl over");
        assertTrue(FrontShape.crossFactor(0.6) > 0.95, "full height across most of the span");
        assertEquals(0.0, FrontShape.crossFactor(1.0), 1e-9);
    }

    @Test
    void theWashSpreadsWiderAndABiggerFrontGoesFurtherButStops() {
        assertTrue(FrontShape.widthFactor(0.95) > 1.0);
        assertTrue(new FrontShape(30, 40, true).travel() > new FrontShape(6, 40, true).travel());
        assertTrue(new FrontShape(500, 40, true).travel() <= FrontShape.MAX_TRAVEL);
    }

    @Test
    void theBiggestFrontFitsTheGiganticBudget() {
        FrontShape gigantic = new FrontShape(30, EventScale.GIGANTIC.radius, true);
        assertTrue(gigantic.peakCells() <= EventScale.GIGANTIC.totalBlocks,
                "the most a front holds at once: " + gigantic.peakCells());
    }

    // ---- layer -------------------------------------------------------------------------------

    @Test
    void aLayerRisesHoldsAndDrainsFromTheTopToNothing() {
        LayerShape s = new LayerShape(10, 30);
        assertEquals(1, s.layersAt(0));
        int peak = 0, prev = 0;
        boolean fell = false;
        for (int t = 0; t <= s.lifeTicks() + 10; t++) {
            int n = s.layersAt(t);
            if (n < prev) {
                fell = true;
                assertEquals(prev - 1, n, "it drains a layer at a time, visibly, not all at once");
            } else if (fell) {
                assertEquals(prev, n, "once it starts draining it never rises again");
            }
            peak = Math.max(peak, n);
            prev = n;
        }
        assertEquals(10, peak);
        assertEquals(0, s.layersAt(s.lifeTicks() + 1), "it ends at nothing");
    }

    @Test
    void everyDepthTakesAboutTheSameTimeToComeIn() {
        int shallow = (new LayerShape(3, 20).depth - 1) * new LayerShape(3, 20).riseEvery();
        int deep = (new LayerShape(16, 20).depth - 1) * new LayerShape(16, 20).riseEvery();
        assertTrue(Math.abs(shallow - deep) <= LayerShape.RISE_TICKS / 2,
                "watching it climb is the point; waiting for it is not");
    }

    @Test
    void aDeeperLayerIsWiderButNeverOverspends() {
        LayerShape shallow = LayerShape.fit(3, 64, EventScale.GIGANTIC.totalBlocks);
        LayerShape deep = LayerShape.fit(12, 64, EventScale.GIGANTIC.totalBlocks);
        assertTrue(deep.reach > shallow.reach, "water finds its level");
        for (EventScale scale : EventScale.values()) {
            for (int d = 1; d <= 60; d++) {
                LayerShape s = LayerShape.fit(d, scale.radius, scale.totalBlocks);
                assertTrue((long) s.cellsPerLayer() * s.depth <= scale.totalBlocks,
                        scale + " depth " + d + " overspends: " + s.depth + " x " + s.cellsPerLayer());
            }
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#1047: a wave set off near him is born inside the world he can see, and breaks on him")
    void aWaveIsBornWhereHeCanSeeItComing() {
        for (com.moddingfromamod.event.EventScale s : com.moddingfromamod.event.EventScale.values()) {
            FrontShape f = com.moddingfromamod.event.design.DesignSizes.wave(s, true);
            assertTrue(f.breaksAt() >= 30, s + ": born " + f.breaksAt() + " blocks out is too close to see it come");
            assertTrue(f.breaksAt() <= 120, s + ": born " + f.breaksAt()
                    + " blocks out is past what a client has loaded at a small view distance");
            assertTrue(f.breaksAt() < f.travel(), s + ": it must still have somewhere to go after it breaks");
        }
    }
}
