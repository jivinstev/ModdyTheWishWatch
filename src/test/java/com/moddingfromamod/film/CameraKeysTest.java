package com.moddingfromamod.film;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class CameraKeysTest {

    private static final String PATH = """
            {"keys":[
              {"at_ms":0,    "pos":[0,70,0],  "look":[0,70,10], "fov":70},
              {"at_ms":1000, "pos":[10,70,0], "look":[10,70,10], "fov":50, "ease":"linear"},
              {"at_ms":2000, "pos":[10,80,0], "look":{"entity":"@e[tag=film_subject,limit=1]"}},
              {"at_ms":3020, "pos":[50,90,50], "look":[0,0,0], "ease":"cut"}
            ]}""";

    private static final CameraKeys.LookResolver FIXED = k -> k.look() != null ? k.look() : new double[] {1, 2, 3};

    @Test
    void easingCurvesStartAtZeroEndAtOneAndInOutSineIsSymmetric() {
        for (CameraKeys.Ease e : new CameraKeys.Ease[] {CameraKeys.Ease.LINEAR, CameraKeys.Ease.IN_OUT_SINE}) {
            assertEquals(0, e.apply(0), 1e-12);
            assertEquals(1, e.apply(1), 1e-12);
            assertEquals(0.5, e.apply(0.5), 1e-12);
            assertEquals(0, e.apply(-3), 1e-12, "clamped below");
            assertEquals(1, e.apply(7), 1e-12, "clamped above");
        }
        CameraKeys.Ease s = CameraKeys.Ease.IN_OUT_SINE;
        for (double u = 0; u <= 1; u += 0.05) {
            assertEquals(1, s.apply(u) + s.apply(1 - u), 1e-12, "symmetric about the middle at " + u);
        }
        // starts and ends at rest: the first 5% covers far less than 5%
        assertTrue(s.apply(0.05) < 0.01, "slow start " + s.apply(0.05));
        assertEquals(0.25, CameraKeys.Ease.LINEAR.apply(0.25), 1e-12);
        assertEquals(0, CameraKeys.Ease.CUT.apply(0.999), 0);
        assertEquals(1, CameraKeys.Ease.CUT.apply(1), 0);
    }

    @Test
    void easeNamesParseAndUnknownOnesAreRefused() {
        assertEquals(CameraKeys.Ease.IN_OUT_SINE, CameraKeys.Ease.parse("inOutSine"));
        assertEquals(CameraKeys.Ease.CUT, CameraKeys.Ease.parse("CUT"));
        assertThrows(IllegalArgumentException.class, () -> CameraKeys.Ease.parse("bounce"));
    }

    @Test
    void theKeyAtAGivenMsIsTheLastOneAtOrBeforeIt() {
        CameraKeys.Path p = CameraKeys.parse(PATH);
        assertEquals(0, CameraKeys.keyAt(p, 0));
        assertEquals(0, CameraKeys.keyAt(p, 999));
        assertEquals(1, CameraKeys.keyAt(p, 1000));
        assertEquals(2, CameraKeys.keyAt(p, 3019));
        assertEquals(3, CameraKeys.keyAt(p, 3020));
        assertEquals(3, CameraKeys.keyAt(p, 99_999));
    }

    @Test
    void positionIsInterpolatedWithTheArrivingKeysEase() {
        CameraKeys.Path p = CameraKeys.parse(PATH);
        // 0 -> 1 is linear: halfway in time is halfway in space, and the FOV eases with it
        CameraKeys.Sample half = CameraKeys.sample(p, 500);
        assertArrayEquals(new double[] {5, 70, 0}, half.pos(), 1e-12);
        assertEquals(60, half.fov(), 1e-12);
        assertArrayEquals(new double[] {5, 70, 10}, CameraKeys.look(p, half, FIXED), 1e-12);
        // 1 -> 2 defaults to inOutSine: a quarter of the time is well under a quarter of the way
        CameraKeys.Sample q = CameraKeys.sample(p, 1250);
        assertTrue(q.pos()[1] > 70 && q.pos()[1] < 72.5, "eased y " + q.pos()[1]);
        assertEquals(70 + 10 * CameraKeys.Ease.IN_OUT_SINE.apply(0.25), q.pos()[1], 1e-12);
        // exactly on a key: exactly that key
        assertArrayEquals(new double[] {10, 70, 0}, CameraKeys.sample(p, 1000).pos(), 0);
        assertArrayEquals(new double[] {10, 80, 0}, CameraKeys.sample(p, 2000).pos(), 0);
    }

    @Test
    void aCutHoldsThePreviousKeyThenJumpsWithinOneTick() {
        CameraKeys.Path p = CameraKeys.parse(PATH);
        // the cut key is at 3020 ms, between ticks 60 (3000 ms) and 61 (3050 ms)
        assertEquals(61, CameraKeys.tickReaching(3020));
        for (long t = 40; t <= 60; t++) {
            assertArrayEquals(new double[] {10, 80, 0}, CameraKeys.sample(p, CameraKeys.msAtTick(t)).pos(), 0,
                    "held exactly on the key before the cut at tick " + t);
        }
        long landed = -1;
        for (long t = 0; t < 200 && landed < 0; t++) {
            if (CameraKeys.sample(p, CameraKeys.msAtTick(t)).pos()[0] == 50) {
                landed = t;
            }
        }
        assertEquals(61, landed);
        long lateMs = CameraKeys.msAtTick(landed) - 3020;
        assertTrue(lateMs >= 0 && lateMs < CameraKeys.MS_PER_TICK, "a cut lands within one tick: +" + lateMs + " ms");
        // the look cuts with it
        CameraKeys.Sample after = CameraKeys.sample(p, CameraKeys.msAtTick(61));
        assertArrayEquals(new double[] {0, 0, 0}, CameraKeys.look(p, after, FIXED), 0);
        CameraKeys.Sample before = CameraKeys.sample(p, CameraKeys.msAtTick(60));
        assertArrayEquals(new double[] {1, 2, 3}, CameraKeys.look(p, before, FIXED), 0);
    }

    @Test
    void aKeyIsReachedOnTheFirstTickAtOrAfterItsTime() {
        assertEquals(0, CameraKeys.tickReaching(0));
        assertEquals(1, CameraKeys.tickReaching(1));
        assertEquals(1, CameraKeys.tickReaching(50));
        assertEquals(2, CameraKeys.tickReaching(51));
        assertEquals(20, CameraKeys.tickReaching(1000));
        for (long ms = 0; ms < 5000; ms += 7) {
            long t = CameraKeys.tickReaching(ms);
            assertTrue(CameraKeys.msAtTick(t) >= ms && CameraKeys.msAtTick(t - 1) < ms, "ms " + ms);
        }
    }

    @Test
    void thePathHoldsItsLastKeyUnlessItLoops() {
        CameraKeys.Path p = CameraKeys.parse(PATH);
        assertArrayEquals(new double[] {50, 90, 50}, CameraKeys.sample(p, 60_000).pos(), 0);
        CameraKeys.Path loop = CameraKeys.parse(PATH.replace("]}", "], \"loop\": true}"));
        assertTrue(loop.loop());
        assertEquals(3020, loop.durationMs());
        assertArrayEquals(CameraKeys.sample(loop, 500).pos(), CameraKeys.sample(loop, 3020 + 500).pos(), 0);
        assertArrayEquals(new double[] {0, 70, 0}, CameraKeys.sample(loop, 3020).pos(), 0);
    }

    @Test
    void beforeAFirstKeyThatIsNotAtZeroTheCameraWaitsOnIt() {
        CameraKeys.Path p = CameraKeys.parse("{\"keys\":[{\"at_ms\":400,\"pos\":[1,2,3],\"look\":[0,0,0]},"
                + "{\"at_ms\":800,\"pos\":[5,2,3]}]}");
        assertArrayEquals(new double[] {1, 2, 3}, CameraKeys.sample(p, 0).pos(), 0);
        assertArrayEquals(new double[] {0, 0, 0}, p.keys().get(1).look(), 0, "a key with no look keeps the last");
    }

    @Test
    void twoKeysAtTheSameTimeAreAJump() {
        CameraKeys.Path p = CameraKeys.parse("{\"keys\":[{\"at_ms\":0,\"pos\":[0,0,0],\"look\":[0,0,1]},"
                + "{\"at_ms\":1000,\"pos\":[10,0,0]},{\"at_ms\":1000,\"pos\":[99,0,0]},{\"at_ms\":2000,\"pos\":[100,0,0]}]}");
        assertTrue(CameraKeys.sample(p, 999).pos()[0] < 10);
        assertEquals(99, CameraKeys.sample(p, 1000).pos()[0], 0);
    }

    @Test
    void anEntityLookIsResolvedEveryTimeAndAMissingOneFallsBackToTheOtherEnd() {
        CameraKeys.Path p = CameraKeys.parse(PATH);
        CameraKeys.Sample s = CameraKeys.sample(p, 1500);   // between key 1 (point) and key 2 (entity)
        double[] moving = {0, 0, 0};
        List<double[]> seen = new ArrayList<>();
        CameraKeys.LookResolver r = k -> {
            if (k.lookEntity() == null) {
                return k.look();
            }
            seen.add(moving.clone());
            return moving.clone();
        };
        double[] first = CameraKeys.look(p, s, r);
        moving[0] = 100;
        double[] second = CameraKeys.look(p, s, r);
        assertEquals(2, seen.size(), "resolved on each call");
        assertTrue(second[0] > first[0], "follows the entity");
        assertArrayEquals(new double[] {10, 70, 10}, CameraKeys.look(p, s, k -> k.lookEntity() == null ? k.look() : null), 0);
        CameraKeys.Sample held = CameraKeys.sample(p, 2500);   // holding key 2 (entity) before the cut
        assertNull(CameraKeys.look(p, held, k -> k.lookEntity() == null ? k.look() : null));
    }

    @Test
    void theSamePathPlayedTwiceIsIdenticalOnEveryTick() {
        CameraKeys.Path a = CameraKeys.parse(PATH);
        CameraKeys.Path b = CameraKeys.parse(PATH);
        for (long t = 0; t < 100; t++) {
            CameraKeys.Sample x = CameraKeys.sample(a, CameraKeys.msAtTick(t));
            CameraKeys.Sample y = CameraKeys.sample(b, CameraKeys.msAtTick(t));
            assertArrayEquals(x.pos(), y.pos(), 0, "tick " + t);
            assertArrayEquals(CameraKeys.look(a, x, FIXED), CameraKeys.look(b, y, FIXED), 0, "tick " + t);
            assertEquals(x.fov(), y.fov(), 0);
        }
    }

    @Test
    void badFilesAreRefusedNamingTheKey() {
        assertThrows(IllegalArgumentException.class, () -> CameraKeys.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> CameraKeys.parse("{\"keys\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> CameraKeys.parse("not json {"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CameraKeys.parse(
                "{\"keys\":[{\"at_ms\":500,\"pos\":[0,0,0],\"look\":[0,0,1]},{\"at_ms\":100,\"pos\":[0,0,0]}]}"));
        assertTrue(e.getMessage().startsWith("key 1:"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CameraKeys.parse("{\"keys\":[{\"at_ms\":0,\"pos\":[0,0,0]}]}"),
                "the first key needs a look");
        assertThrows(IllegalArgumentException.class, () -> CameraKeys.parse("{\"keys\":[{\"at_ms\":0,\"pos\":[0,0],\"look\":[0,0,1]}]}"));
        assertThrows(IllegalArgumentException.class, () -> CameraKeys.parse("{\"keys\":[{\"at_ms\":0.5,\"pos\":[0,0,0],\"look\":[0,0,1]}]}"));
        assertThrows(IllegalArgumentException.class, () -> CameraKeys.parse(
                "{\"keys\":[{\"at_ms\":0,\"pos\":[0,0,0],\"look\":[0,0,1],\"ease\":\"bounce\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> CameraKeys.parse(
                "{\"keys\":[{\"at_ms\":0,\"pos\":[0,0,0],\"look\":[0,0,1],\"fov\":0}]}"));
    }

    // ---- the smooth film camera: a modded client evaluates the same path between ticks

    @Test
    void aFractionalSampleOnAWholeTickIsTheServersSampleExactly() {
        for (String json : new String[] {PATH, PATH.replace("]}", "], \"loop\":true}")}) {
            CameraKeys.Path p = CameraKeys.parse(json);
            for (long t = 0; t < 200; t++) {
                CameraKeys.Sample server = CameraKeys.sample(p, CameraKeys.msAtTick(t));
                CameraKeys.Sample client = CameraKeys.sample(p, (double) CameraKeys.msAtTick(t));
                assertArrayEquals(server.pos(), client.pos(), 0, "tick " + t);
                assertEquals(server.from(), client.from(), "tick " + t);
                assertEquals(server.to(), client.to(), "tick " + t);
                assertEquals(server.s(), client.s(), 0, "tick " + t);
                assertEquals(server.fov(), client.fov(), 0, "tick " + t);
            }
        }
    }

    @Test
    void betweenTicksALinearSegmentMovesInEqualSteps() {
        CameraKeys.Path p = CameraKeys.parse(PATH);
        // key 0 -> key 1 is linear, 10 blocks in 1000 ms: a 17.24 ms frame moves 0.1724 blocks, every frame
        double prev = CameraKeys.sample(p, 100.0).pos()[0];
        for (int f = 1; f < 40; f++) {
            double x = CameraKeys.sample(p, 100.0 + f * 17.24).pos()[0];
            assertEquals(0.1724, x - prev, 1e-9, "frame " + f);
            prev = x;
        }
        // and a cut still holds until its at_ms, then jumps, between ticks too
        assertArrayEquals(new double[] {10, 80, 0}, CameraKeys.sample(p, 3019.9).pos(), 0);
        assertArrayEquals(new double[] {50, 90, 50}, CameraKeys.sample(p, 3020.0).pos(), 0);
    }

    @Test
    void aLoopWrapsBetweenTicksToo() {
        CameraKeys.Path p = CameraKeys.parse(PATH.replace("]}", "], \"loop\":true}"));
        assertArrayEquals(CameraKeys.sample(p, 512.5).pos(), CameraKeys.sample(p, 3020 + 512.5).pos(), 1e-9);
        assertArrayEquals(CameraKeys.sample(p, 0.0).pos(), CameraKeys.sample(p, 3020.0).pos(), 0);
    }

    @Test
    void aPathWrittenOutReadsBackKeyForKey() {
        for (String json : new String[] {PATH, PATH.replace("]}", "], \"loop\":true}")}) {
            CameraKeys.Path a = CameraKeys.parse(json);
            CameraKeys.Path b = CameraKeys.parse(CameraKeys.toJson(a));
            assertEquals(a.loop(), b.loop());
            assertEquals(a.keys().size(), b.keys().size());
            for (int i = 0; i < a.keys().size(); i++) {
                CameraKeys.Key x = a.keys().get(i), y = b.keys().get(i);
                assertEquals(x.atMs(), y.atMs(), "key " + i);
                assertArrayEquals(x.pos(), y.pos(), 0, "key " + i);
                assertArrayEquals(x.look(), y.look(), 0, "key " + i);
                assertEquals(x.lookEntity(), y.lookEntity(), "key " + i);
                assertEquals(x.fov(), y.fov(), 0, "key " + i);
                assertEquals(x.ease(), y.ease(), "key " + i);
            }
            for (long t = 0; t < 80; t++) {
                assertArrayEquals(CameraKeys.sample(a, CameraKeys.msAtTick(t)).pos(),
                        CameraKeys.sample(b, CameraKeys.msAtTick(t)).pos(), 0, "tick " + t);
            }
        }
    }
}
