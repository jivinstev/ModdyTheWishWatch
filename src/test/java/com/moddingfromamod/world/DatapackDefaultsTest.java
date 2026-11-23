package com.moddingfromamod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * <b>A world's own defaults for a pack's settings </b> — the pure half of
 * {@link DatapackDefaults}: what the manifest says, and that a score already set is never overruled.
 */
class DatapackDefaultsTest {

    private static final List<String> NEW_IN_ADDITIONS_1_3_TO_1_4 = List.of(
            "calcite_from_dead_coral", "deepslate_from_tuff", "more_discs_from_vaults", "sand_from_gravel",
            "sandstone_from_packing_sand", "sandstone_mason_trade", "tuff_from_magma");

    // A real world's own section is checked where that manifest lives;
    // this public test reads a manifest of the same shape inline.
    private static final String MANIFEST = "{\"id\": \"x\", \"datapackDefaults\": {\"skyvoid_config\": {"
            + String.join(", ", NEW_IN_ADDITIONS_1_3_TO_1_4.stream().map(k -> "\"" + k + "\": 0").toList()) + "}}}";

    @Test
    void aManifestsDefaultsAreReadAsWritten() {
        Map<String, Integer> cfg = DatapackDefaults.parse(MANIFEST).get("skyvoid_config");
        assertEquals(Set.copyOf(NEW_IN_ADDITIONS_1_3_TO_1_4), cfg.keySet());
        assertTrue(cfg.values().stream().allMatch(v -> v == 0), cfg.toString());
    }

    @Test
    void aManifestWithNoneDeclaresNone() {
        assertTrue(DatapackDefaults.parse("{\"id\": \"open\"}").isEmpty());
    }

    @Test
    void onlyAnUnsetScoreIsWritten() {
        Map<String, Map<String, Integer>> d = Map.of("skyvoid_config", Map.of("sand_from_gravel", 0));
        assertEquals(List.of(new DatapackDefaults.Set("skyvoid_config", "sand_from_gravel", 0)),
                DatapackDefaults.plan(d, (o, h) -> false), "unset: written");
        assertTrue(DatapackDefaults.plan(d, (o, h) -> true).isEmpty(),
                "a score a grown-up set in game (or the pack set earlier) is never overruled");
    }

    @Test
    void aMalformedSectionIsIgnoredNotThrown() {
        assertTrue(DatapackDefaults.parse("{\"datapackDefaults\": 3}").isEmpty());
        assertTrue(DatapackDefaults.parse("not json").isEmpty());
        assertTrue(DatapackDefaults.parse("{\"datapackDefaults\": {\"bad name\": {\"x\": 0}}}").isEmpty(),
                "a name with a space would be a second command");
        assertEquals(Map.of("o", Map.of("ok", 1)),
                DatapackDefaults.parse("{\"datapackDefaults\": {\"o\": {\"ok\": 1, \"@a\": 2, \"s\": \"3\"}}}"),
                "only valid names and integer values survive");
    }
}
