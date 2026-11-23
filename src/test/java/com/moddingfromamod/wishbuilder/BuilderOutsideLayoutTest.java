package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.moddingfromamod.builder.BuildShape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>What goes outside, laid out from the front door</b>. He marked two outside pieces bad:
 * the garden was a square ring all round the house, and "a path all round" had no path to the door. So, for every
 * grounded type at every size: the front is found from the building's own door, gate, hatch, steps or ladder (a gap
 * in its wall for the maze); the path leads to it (it starts at the door, or where the building's own ground begins
 * in front of it); the garden is ONE bed, on one side of the house, never on the path; and no two things
 * outside are drawn over each other.
 */
class BuilderOutsideLayoutTest {

    static final String ALL = "garden,lamps,path,pool,flag";

    static JsonArray body(BuilderBuildings.Type t, int size) {
        BuilderSpec spec = BuilderBuildingComposableTest.composed(t.preset(), BuilderBuildings.NOTHING, "", size, "X");
        return BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED).get(1).args().getAsJsonArray("shapes");
    }

    static long k(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    /** Every cell of each named shape. */
    static Map<String, Set<Long>> cells(JsonArray shapes) {
        Map<String, Set<Long>> out = new HashMap<>();
        for (JsonElement e : shapes) {
            BuildShape s = BuildShape.fromJson(e.getAsJsonObject());
            Set<Long> c = out.computeIfAbsent(s.name(), n -> new HashSet<>());
            s.forEachBlock((x, y, z) -> c.add(k(x, y, z)));
        }
        return out;
    }

    @Test
    void thePathLeadsToTheFrontDoor() {
        List<String> bad = new ArrayList<>();
        List<String> noDoor = new ArrayList<>();
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            if (!t.takesOutside()) {
                continue;
            }
            for (int size = 1; size <= 4; size++) {
                JsonArray body = body(t, size);
                int[] fp = BuilderBuildings.footprint(body);
                BuilderBuildings.Front fr = BuilderBuildings.front(body, fp);
                if (fr.tier() >= 2) {
                    noDoor.add(t.id() + " size " + size + " (" + (fr.tier() == 2 ? "a gap in its wall" : "the south side") + ")");
                }
                Set<Long> path = cells(BuilderBuildings.shapes("path", body)).get("path_1");   // the strip in line with the door
                // Walk out of the door: the first cell in front of it that is not the building is path.
                int x = fr.doorX();
                int z = fr.doorZ();
                boolean reached = false;
                for (int step = 1; step <= 20 && !reached; step++) {
                    x += fr.fx();
                    z += fr.fz();
                    boolean building = false;
                    for (int y = -1; y <= fr.doorY() + 2; y++) {
                        building |= fr.cells().get(k(x, y, z)) != null;
                    }
                    if (!building) {
                        reached = path.contains(k(x, -1, z));
                        break;
                    }
                }
                if (!reached) {
                    bad.add(t.id() + " size " + size + ": the ground in front of its door (" + fr.doorX() + "," + fr.doorZ()
                            + ") is not the path");
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
        // Every grounded type but the maze (its way in is a gap in the hedge) and the well (no way in: it IS the
        // well) draws its own door, gate, hatch, steps or ladder at the ground.
        List<String> expected = new ArrayList<>();
        for (int size = 1; size <= 4; size++) {
            expected.add("hedge_maze size " + size + " (a gap in its wall)");
        }
        noDoor.removeAll(expected);
        noDoor.removeIf(n -> n.startsWith("stone_well "));
        assertTrue(noDoor.isEmpty(), "types whose front was not found from their own door: " + noDoor);
    }

    @Test
    void theGardenIsOneBedBesideTheHouseNeverOnThePath() {
        List<String> bad = new ArrayList<>();
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            if (!t.takesOutside()) {
                continue;
            }
            for (int size = 1; size <= 4; size++) {
                JsonArray body = body(t, size);
                int[] fp = BuilderBuildings.footprint(body);
                Map<String, Set<Long>> c = cells(BuilderBuildings.shapes(ALL, body));
                Set<Long> garden = new HashSet<>();
                Set<Long> path = new HashSet<>();
                c.forEach((n, cs) -> (n.startsWith("garden") ? garden : n.startsWith("path") ? path : new HashSet<Long>()).addAll(cs));
                boolean west = true;
                boolean east = true;
                boolean north = true;
                boolean south = true;
                for (long g : garden) {
                    int x = (int) ((g >> 40) & 0xFFFFF) - 512;
                    int z = (int) (g & 0xFFFFF) - 512;
                    west &= x < fp[0];
                    east &= x > fp[2];
                    north &= z < fp[1];
                    south &= z > fp[3];
                }
                if (!(west || east || north || south)) {
                    bad.add(t.id() + " size " + size + ": the garden is not on one side of the house (a ring?)");
                }
                Set<Long> columns = new HashSet<>();
                path.forEach(p -> columns.add(p & ~(0xFFFFFL << 20)));
                for (long g : garden) {
                    if (columns.contains(g & ~(0xFFFFFL << 20))) {
                        bad.add(t.id() + " size " + size + ": the garden is on the path");
                        break;
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void nothingOutsideIsDrawnOverAnythingElseOutside() {
        List<String> bad = new ArrayList<>();
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            if (!t.takesOutside()) {
                continue;
            }
            for (int size = 1; size <= 4; size++) {
                Map<String, Set<Long>> byPiece = new HashMap<>();
                cells(BuilderBuildings.shapes(ALL, body(t, size))).forEach((n, cs) ->
                        byPiece.computeIfAbsent(n.replaceFirst("_.*", ""), p -> new HashSet<>()).addAll(cs));
                List<String> pieces = new ArrayList<>(byPiece.keySet());
                for (int i = 0; i < pieces.size(); i++) {
                    for (int j = i + 1; j < pieces.size(); j++) {
                        Set<Long> both = new HashSet<>(byPiece.get(pieces.get(i)));
                        both.retainAll(byPiece.get(pieces.get(j)));
                        if (!both.isEmpty()) {
                            bad.add(t.id() + " size " + size + ": " + pieces.get(i) + " and " + pieces.get(j) + " share " + both.size() + " block(s)");
                        }
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }
}
