package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.WorldPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>: an item-specs.json written with convert and change_block loads as turn_into</b> — the
 * effect renamed, its parameters renamed (flat and grouped), the id untouched, and the file
 * re-saved at v3 so the disk says what the code means.
 */
class TurnIntoStoreMigrationTest {

    @TempDir
    Path world;

    private static final String SHELF_PROP = com.moddingfromamod.ModdingFromAModMod.MODID + ".playersDir";
    private String shelfWas;

    private Path wishes() {
        return world.resolve("wishes");
    }

    @BeforeEach
    void open() throws Exception {
        shelfWas = System.getProperty(SHELF_PROP);
        System.setProperty(SHELF_PROP, world.resolve("players").toString());
        WorldPaths.openAt(world);
        Files.createDirectories(wishes());
        ItemSpecStore.clear();
    }

    @AfterEach
    void close() throws Exception {
        try (var files = Files.list(wishes())) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }
        ItemSpecStore.load();
        ItemSpecStore.clear();
        WorldPaths.close();
        if (shelfWas == null) {
            System.clearProperty(SHELF_PROP);
        } else {
            System.setProperty(SHELF_PROP, shelfWas);
        }
    }

    @Test
    @DisplayName("old records load migrated, keep their ids, and the file is re-saved at v3")
    void oldRecordsLoadMigrated() throws Exception {
        Files.writeString(wishes().resolve("item-specs.json"), """
                {"store":"item-specs","v":2,"specs":[
                  {"id":"grinder_convert","name":"Grinder","shape":"machine","count":1,"effects":["convert"],
                   "params":{"INPUT":"minecraft:wheat","OUTPUT":"minecraft:cake"}},
                  {"id":"zapper_change_block","name":"Zapper","shape":"held","count":1,"effects":["change_block"],
                   "params":{"change_block":{"FROM":"minecraft:stone","BLOCK":"minecraft:gold_block","RADIUS":"0"}}},
                  {"id":"staff_convert_glow","name":"Staff","shape":"held","count":1,"effects":["convert","light"],
                   "params":{"convert":{"INPUT":"minecraft:sand","OUTPUT":"minecraft:glass"},"light":{"SECONDS":"5"}}},
                  {"id":"bucket_place","name":"Bucket","shape":"held","count":1,"effects":["place"],
                   "params":{"BLOCK":"minecraft:water"}},
                  {"id":"plain_block_magic_block","name":"Plain Block","shape":"magic_block","count":1,"effects":[],
                   "params":{"_block":{"look":"minecraft:prismarine_bricks"}}}]}""");
        ItemSpecStore.load();

        ItemSpecStore.Spec grinder = ItemSpecStore.get("grinder_convert");
        assertNotNull(grinder, "the id is the item's for life; the pack on disk is named by it");
        assertEquals(List.of("turn_into"), grinder.effects());
        assertEquals(JsonParser.parseString("{\"FROM\":\"minecraft:wheat\",\"TO\":\"minecraft:cake\"}"), grinder.params(),
                "a machine gains no RADIUS: it never touches a block");

        ItemSpecStore.Spec zapper = ItemSpecStore.get("zapper_change_block");
        assertEquals(List.of("turn_into"), zapper.effects());
        JsonObject g = zapper.params().getAsJsonObject("turn_into");
        assertEquals("minecraft:gold_block", g.get("TO").getAsString());
        assertEquals("0", g.get("RADIUS").getAsString());
        assertFalse(zapper.params().has("change_block"));

        ItemSpecStore.Spec staff = ItemSpecStore.get("staff_convert_glow");
        assertEquals(List.of("turn_into", "light"), staff.effects());
        assertEquals("0", staff.params().getAsJsonObject("turn_into").get("RADIUS").getAsString(),
                "a held convert gains the RADIUS its block side reads (0: the block he points at)");
        assertEquals("5", staff.params().getAsJsonObject("light").get("SECONDS").getAsString());

        ItemSpecStore.Spec bucket = ItemSpecStore.get("bucket_place");
        assertEquals("minecraft:water", bucket.params().get("BLOCK").getAsString(), "place's BLOCK is its own");

        assertNotNull(ItemSpecStore.get("plain_block_magic_block"),
                "a magic block with no effect is a real design, and the v3 converge must not drop it "
                        + "(found by the rehearsal on a Gate B world)");

        String disk = Files.readString(wishes().resolve("item-specs.json"));
        JsonObject root = JsonParser.parseString(disk).getAsJsonObject();
        assertEquals(ItemSpecStore.SCHEMA, root.get("v").getAsInt(), "re-saved at the new version: " + disk);
        assertEquals(3, ItemSpecStore.SCHEMA);
        assertFalse(disk.contains("\"convert\"") || disk.contains("\"change_block\"") || disk.contains("INPUT"),
                "the disk says turn_into: " + disk);
        assertTrue(disk.contains("grinder_convert"), "and keeps every id");
    }
}
