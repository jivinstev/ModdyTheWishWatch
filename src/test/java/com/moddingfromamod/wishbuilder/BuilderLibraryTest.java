package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * (B2), (B3): the Builder's own buildings and pictures, granted by Opus low and harvested by
 * tools/builder-harvest.py. Like the bodies, the wish worker cannot see them until B6.
 */
class BuilderLibraryTest {

    static final Path ROOT = Path.of("src/main/resources/builder-library");

    static TreeSet<String> onDisk() throws Exception {
        TreeSet<String> out = new TreeSet<>();
        for (String sub : new String[] {"buildings", "pictures", "items", "outputs"}) {
            if (!Files.isDirectory(ROOT.resolve(sub))) {
                continue;
            }
            try (var ids = Files.list(ROOT.resolve(sub))) {
                for (Path d : ids.toList()) {
                    try (var fs = Files.list(d)) {
                        fs.forEach(f -> out.add(sub + "/" + d.getFileName() + "/" + f.getFileName()));
                    }
                }
            }
        }
        return out;
    }

    @Test
    void theIndexAndTheDirectoryAgree() throws Exception {
        TreeSet<String> index = new TreeSet<>();
        for (String l : Files.readAllLines(ROOT.resolve("index.txt"))) {
            if (!l.isBlank() && !l.startsWith("#")) {
                index.add(l.strip());
            }
        }
        assertEquals(onDisk(), index, "a preset missing from the index would silently not ship");
    }

    @Test
    void theWishLibraryNeverSeesThemAndNoIdCollides() throws Exception {
        String starters = Files.readString(Path.of("src/main/resources/starter-library/index.txt"));
        for (String f : onDisk()) {
            String dir = f.substring(0, f.lastIndexOf('/') + 1);
            assertFalse(starters.contains(dir), dir + " is also a starter: the worker would see it, or one would hide the other");
        }
    }

    @Test
    void everyPresetIsOfferedAndLowers() throws Exception {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        int n = 0;
        java.util.Map<String, Integer> byDir = new java.util.TreeMap<>();
        for (String file : new TreeSet<>(onDisk().stream().filter(x -> !x.endsWith("/icon.json")).toList())) {
            String preset = file.substring(0, file.lastIndexOf('/'));
            if (preset.startsWith("outputs/")) {
                continue;   // a machine's food: made by its machine's lowering, never a tile
            }
            byDir.merge(preset.substring(0, preset.indexOf('/')), 1, Integer::sum);
            BuilderSpec.Kind kind = preset.startsWith("buildings/") ? BuilderSpec.Kind.BUILDING
                    : preset.startsWith("pictures/") ? BuilderSpec.Kind.PICTURE : BuilderSpec.Kind.MACHINE;
            // a stock machine is not a tile any more; it is a composition of the composer's picks.
            // nor a building: each is a type (BuilderBuildings), picked on "What will you build?".
            assertTrue(kind == BuilderSpec.Kind.MACHINE ? BuilderSnacks.READY_MADES.containsKey(preset)
                    : kind == BuilderSpec.Kind.BUILDING ? BuilderBuildings.type(preset) != null
                    : f.subjectIds().contains(preset), preset + " is offered");   //: a picture's subject
            var spec = BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"" + kind.id()
                    + "\",\"from\":\"preset:" + preset + "\",\"scheme\":\"royal\",\"size\":3}").getAsJsonObject());
            var calls = BuilderLowering.lower(spec, "x_1", LibraryPresets.SHIPPED);
            assertFalse(calls.isEmpty(), preset);
            if (kind == BuilderSpec.Kind.MACHINE) {
                // the food first, then the machine whose OUTPUT is that food's id.
                // A vanilla OUTPUT (a cake) needs no food of its own.
                var items = calls.stream().filter(c -> c.tool().equals("make_item")).toList();
                com.google.gson.JsonObject mp = items.get(items.size() - 1).args().getAsJsonObject("params");
                String out = (mp.has("TO") ? mp.get("TO") : mp.get("OUTPUT")).getAsString();   //: turn_into names it TO
                boolean ours = Files.isRegularFile(ROOT.resolve("outputs/" + out + "/item.json"));
                assertTrue(ours || out.startsWith("minecraft:"), preset + " pours " + out + ", which nothing makes");
                assertEquals(ours ? 2 : 1, items.size(), preset + " makes its food first, then itself");
            }
            n++;
        }
        assertEquals(java.util.Map.of("buildings", 18, "pictures", 22, "items", 23), byDir,
                "18 buildings (#2184: four working builds), 22 pictures and 23 snack machines");
    }
}
