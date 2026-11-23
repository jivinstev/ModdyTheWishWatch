package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.marshal.WatchPanel;
import org.junit.jupiter.api.Test;

/**
 * A player's own watch tiles can be exported (2026-10-12). This pins the exported SHAPE to
 * {@code corpus/watch-view-golden.json} (public, made-up tiles), the file a reader of the export is tested
 * against too, so the exporter and its reader cannot drift apart without one of the two tests going red.
 */
class WatchViewGoldenTest {

    @Test
    void theExportIsTheGoldenTheAccountPageReads() throws Exception {
        JsonObject golden = JsonParser.parseString(
                Files.readString(Path.of("corpus/watch-view-golden.json"))).getAsJsonObject();
        golden.remove("_comment");
        Map<String, JsonObject> faces = Map.of(
                "minecraft:egg", face(golden, 0), "stack:id:tiny_cookie_grow", face(golden, 1),
                "wish:balloon#e33bc8", face(golden, 2));
        List<WatchPanel.Cell> cells = List.of(
                cell(WatchPanel.Family.CONTROL, "▶ More wishes", "", "", "minecraft:arrow", List.of()),
                cell(WatchPanel.Family.CREATION, "Giant T-Rex  ✨", "giant_trex", "t_rex_ride_1a2b",
                        "minecraft:egg", lore(golden, 0)),
                cell(WatchPanel.Family.CREATION, "Tiny Cookie", "id:tiny_cookie_grow", "",
                        "stack:id:tiny_cookie_grow", lore(golden, 1)),
                cell(WatchPanel.Family.HAPPENING, "⏳ a dragon", "ab12", "", "minecraft:clock", List.of()),
                cell(WatchPanel.Family.PRESET, "✨ Rainbow castle", "make me a rainbow castle",
                        "rainbow_castle_9f8e", "wish:balloon#e33bc8", lore(golden, 2)));
        JsonObject out = WatchPanelSnapshot.own("00000000-0000-0000-0000-0000000000aa", cells,
                1760241600000L, faces::get);
        assertEquals(golden, out);
    }

    private static JsonObject face(JsonObject g, int i) {
        return g.getAsJsonArray("cells").get(i).getAsJsonObject().getAsJsonObject("face");
    }

    private static List<String> lore(JsonObject g, int i) {
        List<String> out = new java.util.ArrayList<>();
        g.getAsJsonArray("cells").get(i).getAsJsonObject().getAsJsonArray("lore")
                .forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static WatchPanel.Cell cell(WatchPanel.Family f, String label, String arg, String recipe,
                                        String icon, List<String> lore) {
        return new WatchPanel.Cell(0, f, WatchPanel.Action.CARD, label, arg, recipe, "", icon, lore);
    }
}
