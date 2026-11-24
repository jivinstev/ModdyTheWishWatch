package com.moddingfromamod.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.store.WishStoreFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** {@link BlockRenames}: a stored plan's block ids, read on the Minecraft this build runs. */
class BlockRenamesTest {

    /** A {@code buildings.json} as a 1.21.8 Moddy wrote it: no marker, {@code minecraft:chain} and a bare {@code chain}. */
    private static List<JsonObject> records1218() throws IOException {
        String text;
        try (InputStream in = BlockRenamesTest.class.getResourceAsStream("/block-renames/buildings-1.21.8.json")) {
            assertNotNull(in, "the 1.21.8 fixture is on the test classpath");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        WishStoreFile.Read read = WishStoreFile.read(text, "buildings", 1);
        JsonObject root = WishStoreFile.rootOf(read);
        assertNotNull(root, "the fixture is a readable buildings store: " + read);
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("buildings")) {
            out.add(e.getAsJsonObject());
        }
        return out;
    }

    private static BuildPlan byId(List<JsonObject> records, String id) {
        for (JsonObject r : records) {
            if (id.equals(r.get("id").getAsString())) {
                return BuildPlan.fromStored(r);
            }
        }
        throw new AssertionError("no " + id + " in the fixture");
    }

    @Test
    void the_table_names_the_chain_rename_at_1_21_9() {
        assertTrue(BlockRenames.ROWS.contains(new BlockRenames.Row("minecraft:chain", "minecraft:iron_chain", "1.21.9")));
        for (BlockRenames.Row r : BlockRenames.ROWS) {
            assertTrue(r.from().contains(":") && r.to().contains(":"), "a row names namespaced ids: " + r);
            assertTrue(r.since().matches("\\d+(\\.\\d+)*"), "a row's version is a version: " + r);
        }
    }

    @Test
    void a_1_21_8_plan_reads_with_iron_chain() throws IOException {
        List<JsonObject> records = records1218();
        BuildPlan well = byId(records, "stone_well");
        assertNotNull(well);
        assertTrue(well.blockIds().contains("minecraft:iron_chain"), "the well's rope is iron_chain: " + well.blockIds());
        assertFalse(well.blockIds().contains("minecraft:chain"), "no minecraft:chain is left: " + well.blockIds());

        BuildPlan swing = byId(records, "tire_swing");
        // a bare id stays bare and its block states are kept
        assertTrue(swing.blockIds().contains("iron_chain[axis=y]"), "the swing's rope: " + swing.blockIds());
        assertFalse(swing.blockIds().stream().anyMatch(b -> b.startsWith("chain")), swing.blockIds().toString());
    }

    @Test
    void reading_changes_only_the_renamed_ids_and_never_the_record_it_was_handed() throws IOException {
        JsonObject stored = byIdRecord(records1218(), "stone_well");
        JsonObject before = stored.deepCopy();
        JsonObject read = BlockRenames.plan(stored, "1.21.10");
        assertEquals(before, stored, "the stored record is not rewritten on read");
        assertFalse(read.has(BlockRenames.MARKER), "the marker is the store's to write, on save");
        var a = read.getAsJsonArray("shapes");
        var b = before.getAsJsonArray("shapes");
        assertEquals(b.size(), a.size());
        for (int i = 0; i < a.size(); i++) {
            JsonObject was = b.get(i).getAsJsonObject().deepCopy();
            JsonObject now = a.get(i).getAsJsonObject().deepCopy();
            if ("minecraft:chain".equals(was.get("block").getAsString())) {
                assertEquals("minecraft:iron_chain", now.get("block").getAsString());
                was.remove("block");
                now.remove("block");
            }
            assertEquals(was, now, "shape " + i + " is otherwise untouched");
        }
    }

    @Test
    void a_plan_saved_with_the_marker_is_not_remapped_again() throws IOException {
        // A table whose second row would undo the first proves the marker, not idempotence, stops a second pass.
        List<BlockRenames.Row> rows = List.of(new BlockRenames.Row("minecraft:chain", "minecraft:iron_chain", "1.21.9"),
                new BlockRenames.Row("minecraft:iron_chain", "minecraft:chain", "1.21.9"));
        assertEquals("minecraft:chain", BlockRenames.remap(rows, "minecraft:chain", "1.21.8", "1.21.10"),
                "a pre-marker id runs through every row");
        assertEquals("minecraft:iron_chain", BlockRenames.remap(rows, "minecraft:iron_chain", "1.21.10", "1.21.10"),
                "an id stamped by this build runs through none");

        // the real round trip: read a 1.21.8 plan, save it the way a store does, read it back
        BuildPlan well = byId(records1218(), "stone_well");
        JsonObject saved = well.toStoredJson();
        assertEquals(Target.MINECRAFT, saved.get(BlockRenames.MARKER).getAsString(), "a store writes this build's marker");
        assertSame(saved, BlockRenames.plan(rows, saved, Target.MINECRAFT), "nothing to do for a marked plan");
        assertEquals(well.blockIds(), BuildPlan.fromStored(saved).blockIds());

        // a plan marked as written for 1.21.9 already speaks 1.21.9's ids: an old id there is left for the game to refuse
        JsonObject at1219 = byIdRecord(records1218(), "stone_well");
        at1219.addProperty(BlockRenames.MARKER, "1.21.9");
        assertTrue(BuildPlan.fromStored(at1219).blockIds().contains("minecraft:chain"));
    }

    @Test
    void ids_not_in_the_table_pass_through_untouched() {
        for (String b : new String[] {"minecraft:stone", "minecraft:chainmail_helmet", "minecraft:chain_command_block",
                "minecraft:ladder[facing=north]", "oak_planks", "#minecraft:chains", "somemod:chain", "",
                "minecraft:iron_chain[axis=x]"}) {
            assertEquals(b, BlockRenames.remap(b, "1.21.8", "1.21.10"), b);
        }
        assertEquals(null, BlockRenames.remap(null, "1.21.8", "1.21.10"));
        JsonObject plan = JsonParser.parseString("{\"id\":\"p\",\"shapes\":[{\"kind\":\"box\",\"block\":\"minecraft:stone\"}]}")
                .getAsJsonObject();
        assertSame(plan, BlockRenames.plan(plan, "1.21.10"), "a plan with nothing renamed is the same object");
    }

    @Test
    void a_row_applies_only_between_the_version_written_for_and_the_version_running() {
        assertEquals("minecraft:iron_chain", BlockRenames.remap("minecraft:chain", "1.21.8", "1.21.9"));
        assertEquals("minecraft:iron_chain{x:1}", BlockRenames.remap("minecraft:chain{x:1}", "1.21.8", "1.21.10"));
        // on a build that still has the old id (1.21.8), nothing moves
        assertEquals("minecraft:chain", BlockRenames.remap("minecraft:chain", "1.21.8", "1.21.8"));
        // written for a NEWER Minecraft than this build: read as it is, never mapped backwards
        JsonObject newer = JsonParser.parseString("{\"mc\":\"1.22\",\"shapes\":[{\"block\":\"minecraft:chain\"}]}")
                .getAsJsonObject();
        assertSame(newer, BlockRenames.plan(newer, "1.21.10"));
    }

    @Test
    void versions_compare_numerically() {
        assertTrue(BlockRenames.compare("1.21.10", "1.21.9") > 0);
        assertTrue(BlockRenames.compare("1.21.8", "1.21.9") < 0);
        assertEquals(0, BlockRenames.compare("1.21", "1.21.0"));
        assertTrue(BlockRenames.compare("1.22", "1.21.10") > 0);
    }

    @Test
    void a_marker_that_is_not_a_version_is_refused() {
        for (String bad : new String[] {"{\"mc\":\"latest\"}", "{\"mc\":4556}", "{\"mc\":\"\"}", "{\"mc\":{}}"}) {
            JsonObject o = JsonParser.parseString(bad).getAsJsonObject();
            o.add("shapes", JsonParser.parseString("[{\"block\":\"minecraft:chain\"}]"));
            assertThrows(IllegalArgumentException.class, () -> BlockRenames.plan(o, "1.21.10"), bad);
            assertThrows(IllegalArgumentException.class, () -> BuildPlan.fromStored(o), bad);
        }
        assertEquals(BlockRenames.PRE_MARKER, BlockRenames.writtenFor(new JsonObject()), "no marker is 1.21.8");
    }

    /** The port applied every rename already in force to what ships (renames.tsv), so only a player's files need this. */
    @Test
    void no_shipped_resource_names_a_block_renamed_before_this_build() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src/main/resources"))) {
            for (Path f : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (BlockRenames.Row r : BlockRenames.ROWS) {
                    if (BlockRenames.compare(Target.MINECRAFT, r.since()) >= 0
                            && (text.contains("\"" + r.from() + "\"") || text.contains("\"" + r.from() + "["))) {
                        hits.add(f + ": " + r.from());
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "shipped files still name renamed blocks: " + hits);
    }

    private static JsonObject byIdRecord(List<JsonObject> records, String id) {
        return records.stream().filter(r -> id.equals(r.get("id").getAsString())).findFirst().orElseThrow().deepCopy();
    }
}
