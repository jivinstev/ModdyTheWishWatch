package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.marshal.WatchPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchPanelSnapshotTest {

    private static List<WatchPanel.PresetRow> rows(int n) {
        List<WatchPanel.PresetRow> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new WatchPanel.PresetRow("Wish " + i, "wish " + i, "r" + i, "minecraft:cake"));
        }
        return out;
    }

    /** Recipe ids of every preset cell, page after page, in slot order. */
    private static List<String> recipes(JsonObject snap) {
        List<String> out = new ArrayList<>();
        for (JsonElement page : snap.getAsJsonArray("pages")) {
            for (JsonElement c : page.getAsJsonObject().getAsJsonArray("cells")) {
                JsonObject o = c.getAsJsonObject();
                if ("PRESET".equals(o.get("family").getAsString())) {
                    out.add(o.get("recipeId").getAsString());
                }
            }
        }
        return out;
    }

    @Test
    void everyButtonIsInTheSnapshotInTheOrderAChildSeesIt() {
        JsonObject snap = WatchPanelSnapshot.of(rows(40), 123L);
        assertEquals("watch-panel", snap.get("store").getAsString());
        assertEquals(WatchPanelSnapshot.SCHEMA, snap.get("v").getAsInt());
        List<String> want = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            want.add("r" + i);
        }
        assertEquals(want, recipes(snap), "every page, every button, nothing dropped or reordered");
        assertTrue(snap.getAsJsonArray("pages").size() > 1, "forty buttons need a second page");
    }

    @Test
    void aCellCarriesWhatTheChildReadsOnIt() {
        JsonObject first = WatchPanelSnapshot.of(rows(1), 0L).getAsJsonArray("pages").get(0)
                .getAsJsonObject().getAsJsonArray("cells").get(0).getAsJsonObject();
        assertEquals(0, first.get("slot").getAsInt());
        assertEquals("✨ Wish 0", first.get("label").getAsString(), "the name exactly as in game");
        assertTrue(first.getAsJsonArray("lore").size() > 0, "its hover text");
        assertTrue(first.has("icon"));
    }

    @Test
    void itIsWrittenWholeOrNotAtAll(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("watch-panel.json");
        WatchPanelSnapshot.write(f, WatchPanelSnapshot.of(rows(3), 7L));
        JsonObject back = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        assertEquals(3, back.get("buttons").getAsInt());
        assertTrue(Files.notExists(tmp.resolve("watch-panel.json.tmp")), "no temp file left behind");
    }
}
