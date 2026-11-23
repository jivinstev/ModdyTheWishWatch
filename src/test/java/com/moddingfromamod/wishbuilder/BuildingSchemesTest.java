package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderSpecTest.j;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.ui.TuneDialog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** a building's size and colours, and a picture's colours — translations in the lowering, no new tool. */
class BuildingSchemesTest {

    static JsonObject building(String spec) {
        return BuilderLowering.lower(BuilderSpec.read(j(spec)), "mine_1", LibraryPresets.SHIPPED).get(1).args();
    }

    @Test
    void sizeFourDoublesEveryOffsetAndDimensionAndNothingFallsBelowABlock() {
        JsonArray base = LibraryPresets.SHIPPED.read("buildings/secret_treehouse").getAsJsonArray("shapes");
        // The multiply-every-number arm (partscale=off), kept as the A/B seam; the shipped scaler is BuildScale.
        JsonArray big = base.deepCopy();
        BuilderLowering.scaleAll(big, 2.0, false);
        JsonArray small = base.deepCopy();
        BuilderLowering.scaleAll(small, 0.75, false);
        assertEquals(base.size(), big.size());
        for (int i = 0; i < base.size(); i++) {
            JsonObject b = base.get(i).getAsJsonObject(), x = big.get(i).getAsJsonObject(), s = small.get(i).getAsJsonObject();
            if (BuilderLowering.keepsSize(b) || BuilderLowering.bare(b).matches("ladder|vine|scaffolding|air|cave_air")) {
                continue;   //: what a player uses keeps a player's size (BuildScalingCorpusTest)
            }
            for (String k : new String[] {"width", "height", "depth"}) {
                assertEquals(Math.max(1, Math.round(b.get(k).getAsDouble() * 2)), x.get(k).getAsInt(), k);
                assertTrue(s.get(k).getAsInt() >= 1, "never below one block: " + k);
            }
            assertEquals(Math.round(b.get("dx").getAsDouble() * 2), x.get("dx").getAsInt());
        }
    }

    @Test
    void theDefaultSizeIsTheBuildingAsMade() {
        JsonArray base = LibraryPresets.SHIPPED.read("buildings/lava_moat_castle").getAsJsonArray("shapes");
        JsonArray same = building("{\"builder\":1,\"kind\":\"building\",\"from\":\"preset:buildings/lava_moat_castle\"}")
                .getAsJsonArray("shapes");
        assertEquals(base, same, "size 2, no scheme: exactly the ready-made");
    }

    @Test
    void aSchemeRebuildsTheBiggestBlocksAndLeavesTheNaturalOnesAlone() {
        Schemes.Scheme royal = Schemes.byId("royal");
        for (String preset : Catalogue.presets()) {
            if (!preset.startsWith("buildings/")) {
                continue;
            }
            JsonArray shapes = LibraryPresets.SHIPPED.read(preset).getAsJsonArray("shapes");
            Map<String, String> to = Schemes.reblock(shapes, royal);
            assertFalse(to.isEmpty(), preset + " has blocks to rebuild");
            assertTrue(to.containsValue(royal.mainBlock()), preset);
            for (String from : to.keySet()) {
                assertFalse(Schemes.keepsItsBlock(from), preset + ": " + from + " should keep its block");
            }
        }
        JsonArray tree = building("{\"builder\":1,\"kind\":\"building\",\"from\":\"preset:buildings/secret_treehouse\",\"scheme\":\"royal\"}")
                .getAsJsonArray("shapes");
        boolean leaves = false;
        for (JsonElement e : tree) {
            leaves |= e.getAsJsonObject().get("block").getAsString().contains("leaves");
        }
        assertTrue(leaves, "a Royal treehouse is still a tree");
    }

    @Test
    void aPictureTakesTheSchemeByArea() {
        JsonObject a = building("{\"builder\":1,\"kind\":\"picture\",\"from\":\"preset:pictures/castle\",\"scheme\":\"sunset\"}");
        String pic = a.get("picture").getAsString();
        Schemes.Scheme sunset = Schemes.byId("sunset");
        assertTrue(pic.contains(sunset.main()), "the biggest colour is the scheme's main");
        Map<String, String> to = Schemes.recolourRects(
                LibraryPresets.SHIPPED.read("pictures/castle").getAsJsonArray("rects"), sunset);
        assertEquals(3, to.size());
    }

    @Test
    void theTuneDialogNowAsksABuildingItsSizeAndBothTheirColours() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("building");
        f.press("buildings/secret_treehouse");
        f.press("outside:nothing");   //
        assertEquals(List.of("size", "scheme"), f.tune().keys());
        BuilderFlow p = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        p.press("picture");
        p.press("pictures/castle");
        p.press("background:drawn");   //
        TuneDialog d = p.tune();
        assertEquals(List.of("frame", "scheme"), d.keys(), "a picture asks how big it hangs (#1996)");
    }
}
