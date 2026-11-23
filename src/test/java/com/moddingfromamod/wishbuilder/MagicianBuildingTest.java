package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ui.PickPage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * slice 2: the Builder's changes on a building the Magician made — its colours (re-blocked) and what goes outside
 * it — and never its size, which is shown blocked with why. Only what changed is sent, under its own id.
 */
class MagicianBuildingTest {

    static final MagicianBuilding.Thing HUT = new MagicianBuilding.Thing("mud_hut", "Mud Hut", JsonParser.parseString(
            "{\"id\":\"mud_hut\",\"name\":\"Mud Hut\",\"shapes\":["
                    + "{\"kind\":\"box\",\"name\":\"walls\",\"block\":\"minecraft:mud_bricks\",\"anchor\":\"centre\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":7,\"height\":4,\"depth\":7,\"hollow\":true},"
                    + "{\"kind\":\"box\",\"name\":\"roof\",\"block\":\"minecraft:spruce_planks\",\"anchor\":\"centre\",\"dx\":0,\"dy\":4,\"dz\":0,\"width\":7,\"height\":1,\"depth\":7,\"hollow\":false},"
                    + "{\"kind\":\"box\",\"name\":\"window\",\"block\":\"minecraft:glass\",\"anchor\":\"centre\",\"dx\":3,\"dy\":1,\"dz\":0,\"width\":1,\"height\":1,\"depth\":1,\"hollow\":false}],"
                    + "\"spots\":[{\"name\":\"bed\",\"dx\":0,\"dy\":0,\"dz\":0}]}").getAsJsonObject());

    static JsonObject build(List<BuilderLowering.Call> calls) {
        return calls.stream().filter(c -> c.tool().equals("build_structure")).findFirst().orElseThrow().args();
    }

    @Test
    void coloursReblockItsOwnBlocksAndKeepTheGlass() {
        JsonObject a = build(MagicianBuilding.calls(HUT, "ice", List.of()));
        assertEquals("mud_hut", a.get("id").getAsString(), "its own id: rebuilt where it stands");
        assertFalse(a.has("at"), "it is not moved");
        JsonArray shapes = a.getAsJsonArray("shapes");
        assertEquals(3, shapes.size(), "no shape added or lost");
        assertFalse(shapes.get(0).getAsJsonObject().get("block").getAsString().contains("mud"), "the walls are re-blocked");
        assertEquals("minecraft:glass", shapes.get(2).getAsJsonObject().get("block").getAsString(), "glass is never re-blocked");
        assertTrue(a.has("spots"), "its marked spots are kept");
    }

    @Test
    void outsidePiecesAreLaidRoundItAndNothingIsChangedWhenNothingIsAsked() {
        JsonObject a = build(MagicianBuilding.calls(HUT, "as_is", List.of("outside:pool", "outside:garden")));
        JsonArray shapes = a.getAsJsonArray("shapes");
        assertTrue(shapes.size() > 3, "the pool and garden are added round it");
        assertEquals("minecraft:mud_bricks", shapes.get(0).getAsJsonObject().get("block").getAsString(), "its own blocks untouched");
        assertTrue(MagicianBuilding.calls(HUT, "as_is", List.of()).isEmpty(), "nothing asked, nothing sent");
        assertThrows(IllegalArgumentException.class, () -> MagicianBuilding.calls(HUT, "plaid", List.of()));
    }

    @Test
    void theBuilderOpensOnItsOutsideWithItsSizeBlockedAndAsksItsColours() {
        BuilderFlow f = BuilderFlow.editingMagicianBuilding(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, HUT);
        assertEquals(BuilderFlow.Step.BUILD_OUTSIDE, f.step());
        PickPage page = f.page();
        assertTrue(page.title().endsWith(MagicianCreature.MARK), page.title());
        assertTrue(page.tiles().stream().anyMatch(t -> t.id().equals(MagicianBuilding.SIZE_ID)), "its size is shown");
        assertEquals(MagicianBuilding.SIZE_SAY, f.press(MagicianBuilding.SIZE_ID).say(), "and never changed: it says why");
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.BUILD_OUTSIDE, f.step(), "no page before it: its type is the Magician's");
        f.press("outside:pool");
        f.press(PickPage.NEXT_ID);
        assertEquals(List.of("scheme"), f.tune().keys(), "the dialog asks its colours only");
        List<BuilderLowering.Call> calls = f.magicianBuildingCalls(Map.of("scheme", "candy"));
        assertTrue(build(calls).getAsJsonArray("shapes").size() > 3, "the pool he picked is added");
    }
}
