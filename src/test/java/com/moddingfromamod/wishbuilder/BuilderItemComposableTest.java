package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * <b>Every ready-made item is a composition of the made-up options</b> (every existing ready-made
 * item must be composable). For each item-kind ready-made in the starter library, the spec that names it and the
 * spec a child gets by picking its stock item, its power and its strength in the menus lower to the SAME tool calls —
 * argument for argument. And a spec saved from the ready-made reads (SpecMigrations) as exactly that composition.
 */
class BuilderItemComposableTest {

    /** The item-kind ready-mades: items/ presets that are neither a machine nor a magic block. */
    static List<String> readyMadeItems() {
        List<String> out = new ArrayList<>();
        for (String p : Catalogue.presets()) {
            if (!p.startsWith("items/")) {
                continue;
            }
            JsonObject j = LibraryPresets.SHIPPED.read(p);
            String shape = j != null && j.has("shape") ? j.get("shape").getAsString() : "";
            if (j != null && !shape.equals("machine") && !shape.equals("magic_block")) {
                out.add(p);
            }
        }
        return out;
    }

    /** The ready-made's spec, lowered by the preset path (no migration: built at the current version directly). */
    static List<BuilderLowering.Call> fromPreset(String preset, String name, int count) {
        BuilderSpec spec = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.ITEM, "preset:" + preset, "", 2,
                List.of(), "", count, "", name, Map.of());
        return BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED);
    }

    /** The composed spec, as the menus make it: KIND → the stock item → the power → Make, at the strength. */
    static List<BuilderLowering.Call> composed(BuilderItemPowers.Composed c, String name, int count, String scheme) {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("item");
        assertEquals(BuilderFlow.Step.SHAPE, f.step(), "the item kind opens on its stock items");
        f.press(BuilderFlow.STOCK_PREFIX + c.stock());
        assertEquals(BuilderFlow.Step.ITEM_POWER, f.step(), c.stock() + " is on the page");
        f.press("itempower:" + c.power());
        assertEquals(BuilderFlow.Step.TUNE, f.step(), c.power() + " fits " + c.stock());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("count", String.valueOf(count));
        values.put("strength", String.valueOf(c.strength()));
        values.put("scheme", scheme.isEmpty() ? "as_is" : scheme);
        // Moddy names a composed make; the comparison is of everything else, under the ready-made's name.
        BuilderSpec spec = f.spec(values).withName(name);
        return BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED);
    }

    /** How many he asks for in the dialog: the Builder's count, never the preset file's (the lowering's rule). */
    static int count(String preset) {
        return preset.contains("cookie") ? 3 : 1;
    }

    static String name(String preset) {
        return LibraryPresets.SHIPPED.read(preset).get("name").getAsString();
    }

    @Test
    void everyReadyMadeItemHasItsComposition() {
        List<String> missing = new ArrayList<>();
        for (String p : readyMadeItems()) {
            if (!BuilderItemPowers.READY_MADES.containsKey(p)) {
                missing.add(p);
            }
        }
        assertTrue(missing.isEmpty(), "ready-made items with no row in BuilderItemPowers.READY_MADES: " + missing);
        assertTrue(readyMadeItems().size() >= 5, "the starter library's items: " + readyMadeItems());
    }

    @Test
    void everyReadyMadeLowersToTheSameCallsAsItsComposition() {
        List<String> wrong = new ArrayList<>();
        for (String p : readyMadeItems()) {
            BuilderItemPowers.Composed c = BuilderItemPowers.READY_MADES.get(p);
            for (String scheme : new String[] {"", "candy"}) {
                List<BuilderLowering.Call> a = fromPreset(p, name(p), count(p));
                List<BuilderLowering.Call> b = composed(c, name(p), count(p), scheme);
                if (!scheme.isEmpty()) {
                    a = BuilderLowering.lower(new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.ITEM, "preset:" + p,
                            scheme, 2, List.of(), "", count(p), "", name(p), Map.of()), "mine_1", LibraryPresets.SHIPPED);
                }
                if (!a.equals(b)) {
                    wrong.add(p + (scheme.isEmpty() ? "" : " (" + scheme + ")") + "\n  ready-made: " + a + "\n  composed:   " + b);
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void aSpecSavedFromAReadyMadeReadsAsItsComposition() {
        for (String p : readyMadeItems()) {
            // As BuilderSpecStore kept it: no name (the ready-made's own), the count he picked.
            JsonObject saved = JsonParser.parseString("{\"builder\":1,\"kind\":\"item\",\"from\":\"preset:" + p
                    + "\",\"count\":" + count(p) + "}").getAsJsonObject();
            BuilderSpec read = BuilderSpec.read(saved);
            assertEquals("", read.from(), p + " still names a ready-made");
            BuilderItemPowers.Composed c = BuilderItemPowers.READY_MADES.get(p);
            assertEquals(c.stock(), read.opt("stock"), p);
            assertNotNull(BuilderItemPowers.power(read.opt("power")), p);
            assertEquals(fromPreset(p, name(p), count(p)),
                    BuilderLowering.lower(read, "mine_1", LibraryPresets.SHIPPED), p + ": the migrated spec makes something else");
            // And the edit he reopens it with is the made-up flow, every pick already made (walked to its dialog).
            BuilderFlow edit = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "id1", read);
            assertEquals(BuilderFlow.Step.SHAPE, edit.step(), p + ": an edit reopens at the item's first page (#2281)");
            EditWalk.toTune(edit);
            assertTrue(edit.tune().keys().contains("strength"), p + ": the edit is not the made-up item's dialog");
        }
    }

    @Test
    void aMadeUpItemSavedBeforeStockItemsStillLowersAsItDid() {
        // an earlier issue's four shapes: a spec that names only the trigger is the stock item that shape was.
        String[][] rows = {{"on_hit", "sword", "lightning"}, {"held", "wand", "teleport"},
                {"worn_feet", "boots", "jump"}, {"on_consume", "snack", "tiny"}};
        for (String[] r : rows) {
            {
                JsonObject old = JsonParser.parseString("{\"builder\":1,\"kind\":\"item\",\"from\":\"\",\"name\":\"X\","
                        + "\"opts\":{\"shape\":\"shape:" + r[0] + "\",\"effect\":\"effect:"
                        + BuilderItemPowers.power(r[2]).effect() + "\",\"power\":\"" + r[2] + "\",\"strength\":\"2\"}}")
                        .getAsJsonObject();
                BuilderSpec read = BuilderSpec.read(old);
                assertEquals(r[1], BuilderItemPowers.of(read).id(), r[0]);
                List<BuilderLowering.Call> calls = BuilderLowering.lower(read, "mine_1", LibraryPresets.SHIPPED);
                JsonObject make = calls.stream().filter(c -> c.tool().equals("make_item")).findFirst().orElseThrow().args();
                assertEquals(r[0], make.get("shape").getAsString());
            }
        }
    }
}
