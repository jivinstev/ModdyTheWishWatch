package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Gate A for "can the agent actually see what it made?".
 *
 * <p>These numbers decide whether the self-check works at all. A photograph taken too close is a
 * wall of colour that still reads as a successful check, which is worse than no check — so the
 * distance is derived from the camera, and asserted here rather than judged by eye.
 */
class FramingTest {

    private static List<CreatureSculpt.Part> parts(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    /** A player-sized creature: 32 units tall = 2 blocks. */
    private static final String PLAYER_SIZED = """
        [{"name":"body","from":[-4,0,-2],"to":[4,32,2],"colour":"red"}]
        """;

    /** Built tall rather than scaled up: 80 units = 5 blocks at scale 1. */
    private static final String TALL_AT_SCALE_ONE = """
        [{"name":"body","from":[-4,0,-2],"to":[4,80,2],"colour":"red"}]
        """;

    @Test
    void heightIsTheSameMeasuredFromPartsOrFromWrittenGeometry() {
        // The two overloads must agree, or the spawn distance and the photo's fit check would
        // disagree about how tall the same creature is.
        var p = parts(PLAYER_SIZED);
        var baked = CreatureSculpt.bake(p);
        var geo = BedrockGeometry.fromJson(baked.geometry());
        assertEquals(Framing.modelHeightUnits(p), Framing.modelHeightUnits(geo), 1e-4);
    }

    @Test
    void heightComesFromTheTallestBox() {
        assertEquals(32f, Framing.modelHeightUnits(parts(PLAYER_SIZED)), 1e-4);
        // Several parts: the top of the highest one wins, not the last one listed.
        assertEquals(38f, Framing.modelHeightUnits(parts("""
            [{"name":"body","from":[-4,0,-2],"to":[4,26,2],"colour":"red"},
             {"name":"hair","from":[-5,34,-5],"to":[5,38,5],"colour":"red"},
             {"name":"legL","from":[0,0,-2],"to":[4,12,2],"colour":"red"}]
            """)), 1e-4);
    }

    @Test
    void apparentHeightIsTheModelTimesTheScale() {
        assertEquals(2.0, Framing.heightInBlocks(32f, 1.0), 1e-6);
        assertEquals(6.0, Framing.heightInBlocks(32f, 3.0), 1e-6);
        // The case the old formula got wrong: TALL but not scaled.
        assertEquals(5.0, Framing.heightInBlocks(80f, 1.0), 1e-6);
    }

    @Test
    void aTallCreatureAtScaleOneIsNotTreatedAsSmall() {
        // This is the whole point of using real geometry instead of the scale multiplier.
        double smallDistance = Framing.distanceFor(parts(PLAYER_SIZED), 1.0);
        double tallDistance = Framing.distanceFor(parts(TALL_AT_SCALE_ONE), 1.0);
        assertTrue(tallDistance > smallDistance,
                "a creature built 5 blocks tall needs more room than a 2-block one, "
                        + "even though both are scale 1.0 (" + tallDistance + " vs " + smallDistance + ")");
    }

    @Test
    void whateverDistanceIsChosenTheCreatureActuallyFits() {
        // The property that matters, across the whole range the tool allows.
        for (float units : new float[]{8, 32, 48, 80, 120}) {
            for (double scale : new double[]{0.2, 1.0, 3.0, 8.0}) {
                double h = Framing.heightInBlocks(units, scale);
                double d = Framing.distanceFor(h);
                // Anything below the far clamp must genuinely fit in frame.
                if (d < Framing.MAX_DISTANCE) {
                    assertTrue(Framing.fitsInFrame(h, d),
                            "height " + h + " blocks does not fit from " + d + " blocks away");
                }
            }
        }
    }

    @Test
    void itDoesNotStandAbsurdlyCloseOrFarAway() {
        // A tiny creature still needs enough distance that the camera isn't inside it.
        assertEquals(Framing.MIN_DISTANCE, Framing.distanceFor(parts("""
                [{"name":"b","from":[0,0,0],"to":[2,2,2],"colour":"red"}]
                """), 0.2), 1e-6);
        // And an enormous one is clamped rather than putting the subject in another chunk.
        assertEquals(Framing.MAX_DISTANCE, Framing.distanceFor(1000), 1e-6);
    }

    @Test
    void tooCloseIsReportableRatherThanSilent() {
        // 8 blocks tall, photographed from 2 away: does not fit, and the tool should say so.
        assertFalse(Framing.fitsInFrame(8.0, 2.0));
        assertTrue(Framing.fitsInFrame(8.0, Framing.distanceFor(8.0)));
    }

    @Test
    void nothingToMeasureStillGivesAUsableNumber() {
        assertEquals(0f, Framing.modelHeightUnits((List<CreatureSculpt.Part>) null), 1e-6);
        assertEquals(0f, Framing.modelHeightUnits((BedrockGeometry) null), 1e-6);
        assertEquals(0f, Framing.modelHeightUnits(List.of()), 1e-6);
        assertTrue(Framing.distanceFor(List.of(), 1.0) >= Framing.MIN_DISTANCE);
    }
}
