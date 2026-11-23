package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The aim ray is cut where it would first enter a chunk that is not loaded. */
class LoadedRayTest {

    private static LoadedRay.ChunkLoaded upTo(int maxChunkX) {
        return (cx, cz) -> cx <= maxChunkX;
    }

    @Test
    void aRayThroughLoadedChunksIsWhole() {
        assertEquals(1.0, LoadedRay.loadedFraction(1, 1, 40, 30, (cx, cz) -> true));
    }

    @Test
    void aRayIsCutJustShortOfTheFirstUnloadedChunk() {
        // eye at x=8 looking east 24 blocks: chunk 0 loaded, chunk 1 (x >= 16) not
        double t = LoadedRay.loadedFraction(8, 8, 32, 8, upTo(0));
        double x = 8 + t * 24;
        assertTrue(x < 16 && x > 15.99, "cut at the chunk edge, inside chunk 0: x=" + x);
    }

    @Test
    void anUnloadedStartIsNothing() {
        assertEquals(0.0, LoadedRay.loadedFraction(20, 8, 40, 8, upTo(0)));
    }

    @Test
    void negativeCoordinatesAndWestwardRaysUseFloorNotTruncation() {
        // x = -0.5 is chunk -1; a westward ray from x=-1 into chunk -2 (x < -16) is cut there
        LoadedRay.ChunkLoaded onlyMinusOne = (cx, cz) -> cx == -1 && cz == 0;
        double t = LoadedRay.loadedFraction(-1, 3, -25, 3, onlyMinusOne);
        double x = -1 - t * 24;
        assertTrue(x > -16 && x < -15.99, "cut inside chunk -1: x=" + x);
    }

    @Test
    void aVerticalGazeNeverLeavesItsChunk() {
        assertEquals(1.0, LoadedRay.loadedFraction(5, 5, 5, 5, upTo(0)));
    }

    @Test
    void chunksAreAskedInRayOrderAndNoneBeyondTheCut() {
        List<String> asked = new ArrayList<>();
        LoadedRay.loadedFraction(2, 2, 60, 2, (cx, cz) -> {
            asked.add(cx + "," + cz);
            return cx < 2;
        });
        assertEquals(List.of("0,0", "1,0", "2,0"), asked);
    }

    @Test
    void aDiagonalRayVisitsEveryChunkItCrosses() {
        List<String> asked = new ArrayList<>();
        double t = LoadedRay.loadedFraction(14, 1, 18, 17, (cx, cz) -> {
            asked.add(cx + "," + cz);
            return true;
        });
        assertEquals(1.0, t);
        assertEquals("0,0", asked.get(0));
        assertEquals("1,1", asked.get(asked.size() - 1));
        assertEquals(3, asked.size(), "one boundary in x, one in z: " + asked);
    }
}
