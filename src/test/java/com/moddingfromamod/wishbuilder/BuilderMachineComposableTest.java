package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ui.PickPage;
import org.junit.jupiter.api.Test;

/**
 * <b>Every stock snack machine is a composition of the composer's picks</b> (all 23 composable).
 * For each of B5's machines, the spec that names it and the spec a child gets by picking its look, its treat, its
 * ingredient and "one in, one out" in the menus lower to the SAME tool calls — the snack and the machine, argument for
 * argument. A spec saved from the ready-made reads (SpecMigrations) as exactly that composition.
 */
class BuilderMachineComposableTest {

    /** B5's stock machines: the Builder library's items/ presets that are machines (not the slushie starter). */
    static List<String> stockMachines() {
        List<String> out = new ArrayList<>();
        for (String p : Catalogue.presets()) {
            JsonObject j = p.startsWith("items/") ? LibraryPresets.SHIPPED.read(p) : null;
            if (j != null && "machine".equals(j.has("shape") ? j.get("shape").getAsString() : "")
                    && !p.equals("items/slushie_machine_load_and_pour")) {
                out.add(p);
            }
        }
        return out;
    }

    static List<BuilderLowering.Call> fromPreset(String preset, String name) {
        return BuilderLowering.lower(new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.MACHINE, "preset:" + preset, "", 2,
                List.of(), "", 1, "", name, Map.of()), "mine_1", LibraryPresets.SHIPPED);
    }

    /** The menus: A machine → the look → its treat → Next → its own ingredient → tune: strength, one in one out. */
    static List<BuilderLowering.Call> composed(BuilderSnacks.Ready r, String name) {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("machine");
        assertEquals(BuilderFlow.Step.SNACK_LOOK, f.step(), "the machine kind opens on its looks");
        f.press("look:" + r.look());
        assertEquals(BuilderFlow.Step.SNACK_POWER, f.step(), r.look());
        f.press("snackpower:" + r.power());
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.SNACK_INGREDIENT, f.step(), r.power() + " fits " + r.look());
        f.press("ingredient:" + BuilderSnacks.look(r.look()).ingredient());
        assertEquals(BuilderFlow.Step.TUNE, f.step());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("strength", String.valueOf(r.strength()));
        values.put("how", BuilderSnacks.CONVERT);
        // Moddy names a composed make; the comparison is of everything else, under the ready-made's name.
        return BuilderLowering.lower(f.spec(values).withName(name), "mine_1", LibraryPresets.SHIPPED);
    }

    @Test
    void everyStockMachineHasItsComposition() {
        List<String> all = stockMachines();
        assertEquals(23, all.size(), all.toString());
        List<String> missing = all.stream().filter(p -> !BuilderSnacks.READY_MADES.containsKey(p)).toList();
        assertTrue(missing.isEmpty(), "stock machines with no row in BuilderSnacks.READY_MADES: " + missing);
    }

    @Test
    void everyStockMachineLowersToTheSameCallsAsItsComposition() {
        List<String> wrong = new ArrayList<>();
        for (String p : stockMachines()) {
            BuilderSnacks.Ready r = BuilderSnacks.READY_MADES.get(p);
            // The tune dialog's name is the SNACK's, and it starts at the look's own label.
            List<BuilderLowering.Call> a = fromPreset(p, "");
            List<BuilderLowering.Call> b = composed(r, BuilderSnacks.look(r.look()).label());
            if (!a.equals(b)) {
                wrong.add(p + "\n  ready-made: " + a + "\n  composed:   " + b);
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void aSpecSavedFromAStockMachineReadsAsItsComposition() {
        for (String p : stockMachines()) {
            for (String named : new String[] {"", "Big Oven"}) {
                JsonObject saved = JsonParser.parseString("{\"builder\":1,\"kind\":\"machine\",\"from\":\"preset:" + p
                        + "\"" + (named.isEmpty() ? "" : ",\"name\":\"" + named + "\"") + "}").getAsJsonObject();
                BuilderSpec read = BuilderSpec.read(saved);
                assertEquals("", read.from(), p);
                assertTrue(BuilderSnacks.composed(read), p);
                assertEquals(fromPreset(p, named), BuilderLowering.lower(read, "mine_1", LibraryPresets.SHIPPED),
                        p + (named.isEmpty() ? "" : " named " + named) + ": the migrated spec makes something else");
                BuilderFlow edit = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "id1", read);
                assertEquals(BuilderFlow.Step.SNACK_LOOK, edit.step(), p + ": an edit reopens at the snack's first page (#2281)");
                EditWalk.toTune(edit);
                assertEquals(BuilderSnacks.CONVERT, ((com.moddingfromamod.ui.TuneDialog.Choice) edit.tune().inputs().stream()
                        .filter(i -> i.key().equals("how")).findFirst().orElseThrow()).initial(), p + ": the edit forgot it is one in, one out");
            }
        }
    }

    @Test
    void theMachineKindHasNoReadyMadePage() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        assertTrue(f.fromIds(BuilderSpec.Kind.MACHINE).isEmpty());
        f.press("machine");
        assertEquals(BuilderFlow.Step.SNACK_LOOK, f.step());
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.KIND, f.step());
    }
}
