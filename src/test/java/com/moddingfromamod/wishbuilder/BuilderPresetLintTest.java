package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moddingfromamod.builder.BuildDoors;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.DarkInterior;
import com.moddingfromamod.builder.WalkProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * <b>Lints over every building the Builder offers</b> (from the grading films): nothing that burns
 * stands where lava sets it alight (the castle's wooden bridge over its moat caught fire), and no room a child walks
 * into is dark ({@link DarkInterior}, the check every agent build already gets). Writes {@code build/preset-lint.md}.
 */
class BuilderPresetLintTest {

    /** A maze keeps one thing at every size it is offered: he can walk from outside to its treasure. */
    @Test
    void theMazeCanBeSolvedAtEverySize() {
        com.google.gson.JsonArray shapes = LibraryPresets.SHIPPED.read("buildings/hedge_maze").getAsJsonArray("shapes");
        for (int size = 1; size <= 4; size++) {
            List<com.moddingfromamod.builder.BuildShape> list = com.moddingfromamod.builder.BuildScale.scale(
                    shapes(shapes), BuilderLowering.BUILDING_SCALE[size - 1]);
            Map<Long, String> c = WalkProbe.compose(list);
            c.values().removeIf(Objects::isNull);
            long chest = c.entrySet().stream().filter(e -> e.getValue().contains("chest")).findFirst().orElseThrow().getKey();
            org.junit.jupiter.api.Assertions.assertTrue(
                    WalkProbe.standsBeside(c, WalkProbe.kx(chest), WalkProbe.ky(chest), WalkProbe.kz(chest)),
                    "at size " + size + " the walk from outside does not reach the maze's chest");
        }
    }

    static boolean burns(String b) {
        String n = b.replaceFirst("\\[.*", "").replaceFirst("^minecraft:", "");
        return n.endsWith("_planks") || n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_leaves") || n.endsWith("_wool")
                || n.endsWith("_carpet") || n.endsWith("_fence") || n.endsWith("_fence_gate") || n.endsWith("_slab") && wood(n)
                || n.endsWith("_stairs") && wood(n) || n.equals("hay_block") || n.equals("bookshelf") || n.equals("tnt")
                || n.endsWith("_door") && wood(n) || n.endsWith("_trapdoor") && wood(n) || n.equals("bamboo") || n.equals("vine");
    }

    static boolean wood(String n) {
        for (String w : new String[] {"oak", "spruce", "birch", "jungle", "acacia", "cherry", "mangrove", "bamboo"}) {
            if (n.startsWith(w) || n.startsWith("dark_" + w) || n.startsWith("stripped_" + w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Flammable blocks lava can set alight. Lava lights air next to something that burns, up to one block out and
     * one up, or two out and two up (a 3x3 at +1 and a 5x5 at +2 over it). Here, kept simple and a little wide: a
     * block that burns within two blocks of lava across and from one below it to three above.
     */
    static List<String> burning(Map<Long, String> c) {
        List<long[]> lava = new ArrayList<>();
        for (Map.Entry<Long, String> e : c.entrySet()) {
            if (e.getValue().contains("lava") || e.getValue().replaceFirst("\\[.*", "").endsWith(":fire")) {
                lava.add(new long[] {WalkProbe.kx(e.getKey()), WalkProbe.ky(e.getKey()), WalkProbe.kz(e.getKey())});
            }
        }
        java.util.Set<Long> hot = new java.util.HashSet<>();
        for (long[] l : lava) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 3; dy++) {
                        hot.add(WalkProbe.key((int) l[0] + dx, (int) l[1] + dy, (int) l[2] + dz));
                    }
                }
            }
        }
        Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (Map.Entry<Long, String> e : c.entrySet()) {
            if (hot.contains(e.getKey()) && burns(e.getValue())) {
                kinds.merge(e.getValue().replaceFirst("\\[.*", ""), 1, Integer::sum);
            }
        }
        List<String> out = new ArrayList<>();
        kinds.forEach((k, v) -> out.add(v + " " + k));
        return out;
    }

    @Test
    void nothingBurnsAndNoRoomIsDark() throws IOException {
        List<String> fire = new ArrayList<>();
        List<String> dark = new ArrayList<>();
        Map<String, Integer> authored = new java.util.TreeMap<>();
        Map<String, Integer> replaced = new java.util.TreeMap<>();
        com.google.gson.JsonObject baked = new com.google.gson.JsonObject();
        StringBuilder rep = new StringBuilder("# Builder preset lints\n");
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            BuildPlan p = BuildPlan.fromJson(LibraryPresets.SHIPPED.read(t.preset()));
            Map<Long, String> c = BuildDoors.applied(p, BuildDoors.of(p));
            c.values().removeIf(Objects::isNull);
            List<String> b = burning(c);
            if (!b.isEmpty()) {
                fire.add(t.id() + " " + b);
            }
            // as the Builder builds it, at every size, with the lights the lowering sets into dark floor.
            for (int size = 1; size <= 4; size++) {
                com.google.gson.JsonObject o = LibraryPresets.SHIPPED.read(t.preset()).deepCopy();
                com.google.gson.JsonArray shapes = o.getAsJsonArray("shapes");
                if (size != 2) {
                    BuilderLowering.scaleAll(shapes, BuilderLowering.BUILDING_SCALE[size - 1]);
                }
                if (size != 2) {
                    o.remove("spots");   // stored at the made size; the lowering does not move them
                }
                int had = shapes.size();
                com.moddingfromamod.builder.ComposedPlan was = com.moddingfromamod.builder.ComposedPlan.of(BuildPlan.fromJson(o));
                BuilderLowering.light(shapes);
                for (int i = had; i < shapes.size(); i++) {
                    com.google.gson.JsonObject l = shapes.get(i).getAsJsonObject();
                    String under = was.blockAt(l.get("dx").getAsInt(), l.get("dy").getAsInt(), l.get("dz").getAsInt());
                    replaced.merge(String.valueOf(under).replaceFirst("\\[.*", "") + " -> " + l.get("block").getAsString(), 1, Integer::sum);
                }
                String note = DarkInterior.note(DarkInterior.of(BuildPlan.fromJson(o)));
                rep.append(String.format("%s size %d: %d light(s) set into the floor%n", t.id(), size, shapes.size() - had));
                if (size == 2) {
                    authored.put(t.id(), shapes.size() - had);
                    com.google.gson.JsonArray lights = new com.google.gson.JsonArray();
                    for (int i = had; i < shapes.size(); i++) {
                        lights.add(shapes.get(i));
                    }
                    baked.add(t.preset(), lights);
                }
                if (!note.isBlank() && !t.working()) {   //: the monster cave is dark ON PURPOSE (and never lit)
                    dark.add(t.id() + ":" + size);
                    rep.append("\n").append(t.id()).append(" size ").append(size).append(": ").append(note.strip()).append('\n');
                }
            }
        }
        rep.append("\nfloor replaced by a light: ").append(replaced).append('\n');
        rep.append("\nburns by lava: ").append(fire).append("\ndark inside: ").append(dark).append('\n');
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/preset-lights.json"), baked.toString());
        Files.writeString(Path.of("build/preset-lint.md"), rep.toString());
        System.out.println(rep);
        assertEquals(List.of(), fire, "a Builder building has something that burns beside lava");
        assertEquals(List.of(), dark, "a Builder building has a dark room (build/preset-lint.md says where and at which size)");
        // The stored file lights itself at the size it was made: the lowering's lights are for the grown sizes.
        authored.values().removeIf(n -> n == 0);
        BuilderBuildings.TYPES.stream().filter(BuilderBuildings.Type::working).forEach(t -> authored.remove(t.id()));
        assertEquals(Map.of(), authored, "a stored Builder building needs lights added at its own size: "
                + "python3 tools/bake-preset-lights.py adds what build/preset-lights.json lists");
    }

    /** A build's shapes, read the way the Builder's lowering reads them. */
    private static java.util.List<com.moddingfromamod.builder.BuildShape> shapes(com.google.gson.JsonArray a) {
        java.util.List<com.moddingfromamod.builder.BuildShape> out = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement e : a) {
            com.moddingfromamod.builder.BuildShape s = com.moddingfromamod.builder.BuildShape.fromJson(e.getAsJsonObject());
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }
}
