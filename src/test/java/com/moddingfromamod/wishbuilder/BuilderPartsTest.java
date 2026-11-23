package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.runtime.SculptKits;
import com.moddingfromamod.ui.PickPage;
import org.junit.jupiter.api.Test;

/** every part goes on every ready-made creature, onto a part it really has, through the menus. */
class BuilderPartsTest {

    @Test
    void everyPartIsARealKitAndHangsOnEveryReadyMadeCreature() {
        List<String> wrong = new ArrayList<>();
        for (BuilderParts.Part p : BuilderParts.ALL) {
            assertTrue(SculptKits.available().contains(p.kit()), p.kit() + " is a real kit");
        }
        int creatures = 0;
        for (String preset : Catalogue.presets()) {
            if (!preset.startsWith("species/") && !preset.startsWith("bodies/")) {
                continue;
            }
            JsonObject j = LibraryPresets.SHIPPED.read(preset);
            if (j == null || !j.has("parts")) {
                continue;
            }
            creatures++;
            JsonArray parts = j.getAsJsonArray("parts");
            Set<String> names = new HashSet<>();
            for (JsonElement e : parts) {
                if (e.isJsonObject() && e.getAsJsonObject().has("name")) {
                    names.add(e.getAsJsonObject().get("name").getAsString());
                }
            }
            for (BuilderParts.Part p : BuilderParts.ALL) {
                try {
                    JsonObject k = BuilderParts.kits(List.of(p.id()), parts, "").get(0).getAsJsonObject();
                    if (!names.contains(k.get("on").getAsString())) {
                        wrong.add(preset + ": " + p.kit() + " on '" + k.get("on").getAsString() + "', which it has not got");
                    }
                } catch (IllegalArgumentException e) {
                    wrong.add(preset + ": " + e.getMessage());
                }
            }
        }
        assertTrue(creatures > 20, "the ready-made creatures were read (" + creatures + ")");
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void partsArePickedRightAfterTheCreatureAndLowerAsKits() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("creature");
        f.press("species/cat");
        assertEquals(BuilderFlow.Step.PARTS, f.step());
        f.press("kit:snout");
        f.press("kit:wing");
        f.press("kit:tail");
        // no count of parts — a fourth is taken while the body has room, and the row says how much is left.
        assertEquals(BuilderFlow.Step.PARTS, f.step(), "three parts do not fill a page that has no count");
        assertEquals("Parts", f.page().title());
        assertTrue(f.page().drawn().values().stream().anyMatch(t -> t.label().startsWith("Body room ")),
                "the picks row shows the body's real room");
        f.press("kit:fin");
        assertEquals(4, f.page().chosen().size(), "a fourth part that fits is taken: " + f.page().drawn());
        f.press("kit:fin");
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.POWERS, f.step());
        BuilderFlowTest.pastPowers(f);
        BuilderSpec spec = f.spec(Map.of("scheme", "ocean"));
        assertEquals(List.of("snout", "wing", "tail"), BuilderParts.chosen(spec));
        assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty(), BuilderAllowlist.REVIEWED.problems(spec).toString());
        var sculpt = BuilderLowering.lower(spec, "cat_1", LibraryPresets.SHIPPED).stream()
                .filter(c -> c.tool().equals("sculpt_creature")).findFirst().orElseThrow();
        JsonArray kits = sculpt.args().getAsJsonArray("kits");
        assertEquals(3, kits.size());
        assertEquals("head", kits.get(0).getAsJsonObject().get("on").getAsString(), "a snout goes on the head");
        assertEquals(Schemes.byId("ocean").accent(), kits.get(1).getAsJsonObject().get("colour").getAsString(),
                "a scheme colours the parts");
    }

    @Test
    void tooMuchOnOneCreatureIsSaidBeforeMakeNotDroppedAfter() {
        // F1: the sculpt tool keeps kits to a share of the creature and drops the rest silently.
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("creature");
        f.press("species/cat");
        f.press("kit:antlers");
        f.press("kit:dragon_wings");
        // the page says so BEFORE he presses — the tile is grey with the reason — and a press says it too.
        int had = f.page().chosen().size();
        PickPage page = f.page();
        PickPage.Tile peacock = page.tiles().stream().filter(t -> t.id().equals("kit:peacock_tail")).findFirst().orElseThrow();
        assertEquals("Too big for this cat", page.blocked(peacock), "the heavy part is greyed with its reason");
        assertEquals(PickPage.BLOCKED_ITEM, page.drawn().get(page.tiles().indexOf(peacock)).item());
        assertTrue(f.press("kit:peacock_tail").say().contains("Too big for this cat"), "the third heavy part is refused in words");
        assertEquals(had, f.page().chosen().size(), "and not taken");
    }

    @Test
    void everyStyledPartLowersWithItsKitAndStyle() {
        JsonArray cat = LibraryPresets.SHIPPED.read("species/cat").getAsJsonArray("parts");
        for (BuilderParts.Part p : BuilderParts.ALL) {
            JsonObject k = BuilderParts.kits(List.of(p.id()), cat, "").get(0).getAsJsonObject();
            assertEquals(p.kit(), k.get("kit").getAsString(), p.id());
            assertEquals(p.style(), k.has("style") ? k.get("style").getAsString() : "", p.id());
            assertTrue(k.has("colour"), p.id() + " has a colour (its own, or the creature's)");
            assertTrue(BuilderParts.boxes(List.of(p.id()), cat, "") <= BuilderParts.budget(cat.size()),
                    p.id() + " alone fits on a cat");
        }
    }
}
