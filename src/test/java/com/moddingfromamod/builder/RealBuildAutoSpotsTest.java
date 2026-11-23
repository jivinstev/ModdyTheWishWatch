package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.wish.RealBuilds;
import org.junit.jupiter.api.Test;
import com.moddingfromamod.UsesCorpus;

/**
 * <b>an earlier issue's automatic spots over the buildings the agent really built</b>, not only hand-made
 * fixtures (CLAUDE.md, "Building an eval": a check tuned on fixtures caught 0 of 14 real cases).
 * Prints every building's spots, because whether {@code inside} found the hall a child would call
 * "inside" can only be judged by reading them.
 */
@UsesCorpus
class RealBuildAutoSpotsTest {

    @Test
    void everyRealBuildingHasATopFourSidesAndAroundAndItsSpotsAreNotInsideItsWalls() {
        List<String> rows = new ArrayList<>();
        int withInside = 0;
        for (RealBuilds.Real r : com.moddingfromamod.CorpusSource.realBuilds()) {
            BuildPlan plan = r.plan();
            BuildSpots.Cells c = BuildSpots.Cells.of(plan);
            long t0 = System.nanoTime();
            List<BuildSpot> auto = BuildAutoSpots.of(plan);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            StringBuilder row = new StringBuilder(r.id() + " (" + ms + " ms):");
            for (String name : List.of("top", "north", "east", "south", "west", "around")) {
                assertNotNull(auto.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null),
                        r.id() + " has no " + name);
            }
            for (BuildSpot s : auto) {
                row.append(' ').append(s.name()).append('@').append(s.dx()).append(',').append(s.dy())
                        .append(',').append(s.dz());
                if (s.name().startsWith("inside") || s.name().equals("top")) {
                    assertTrue(!c.solid(s.dx(), s.dy(), s.dz()) && !c.solid(s.dx(), s.dy() + 1, s.dz()),
                            r.id() + "'s " + s.name() + " at " + s.dx() + "," + s.dy() + "," + s.dz()
                                    + " is inside a block — nothing could stand there");
                }
            }
            if (auto.stream().anyMatch(s -> s.name().equals("inside"))) {
                withInside++;
            }
            assertTrue(ms < 3_000, r.id() + " took " + ms + " ms");
            rows.add(row.toString());
        }
        System.out.println("[#1379 auto spots over the real builds] " + withInside + " of " + rows.size()
                + " have an inside\n  " + String.join("\n  ", rows));
        assertTrue(rows.size() >= 15, "the real-build corpus did not load");
    }
}
