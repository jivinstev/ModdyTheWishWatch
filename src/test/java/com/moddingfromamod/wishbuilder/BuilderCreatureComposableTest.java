package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.ui.PickPage;
import org.junit.jupiter.api.Test;

/**
 * <b>Every creature and ride starter is a body plus choices the menus offer</b> (every
 * starter must be possible to create from the UI choices). A starter used to arrive with its own powers and
 * name; now a body brings only its look and how it moves. For every starter, a child who picks that body, ticks its
 * old powers on the powers page and types its name gets the same creature as the starter's saved make: the same
 * sculpt, the same moves, the same herd. Only the narration differs (the plan line and wish card say the name he
 * typed, where an unnamed make said the body's id), so those three fields are left out of the comparison.
 */
class BuilderCreatureComposableTest {

    /** What a starter's saved make is, read as it is now (its old powers written out as picks). */
    static BuilderSpec saved(BuilderSpec.Kind kind, String preset) {
        return BuilderSpec.read(BuilderStarterGolden.saved(kind, preset));
    }

    /** The same make through the menus: the kind, the body, its powers ticked, the dialog with the body's name. */
    static BuilderSpec composed(BuilderSpec.Kind kind, String preset, List<String> powers, String name) {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press(kind.id());
        assertEquals(BuilderFlow.Step.FROM, f.step(), kind.id() + " opens on its bodies");
        assertTrue(f.page().title().startsWith("Pick a body"), "the page is called " + f.page().title());
        assertTrue(f.press(preset).say().isEmpty(), preset + " is a body he can pick");
        if (f.step() == BuilderFlow.Step.PARTS) {
            f.press(PickPage.NEXT_ID);
        }
        assertEquals(BuilderFlow.Step.POWERS, f.step(), preset + ": the powers page");
        // only what the body does by itself (a dragon flies) comes ticked, and it is not his to press.
        List<String> natural = f.naturalPowers();
        assertTrue(f.page().tiles().stream().filter(t -> t.id().startsWith("power:"))
                        .allMatch(t -> t.selected() == natural.contains(Catalogue.bare(t.id()))),
                preset + ": a body brings only what it does by itself ticked");
        for (String p : powers) {
            if (natural.contains(Catalogue.bare(p))) {
                continue;
            }
            assertTrue(f.press(p).say().isEmpty(), preset + ": " + p + " is a power he can tick");
        }
        while (f.step() == BuilderFlow.Step.POWERS || f.step() == BuilderFlow.Step.ABILITIES) {
            f.press(PickPage.NEXT_ID);
        }
        assertEquals(BuilderFlow.Step.TUNE, f.step(), preset);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("size", "2");
        values.put("count", "1");
        values.put("scheme", "as_is");
        // Moddy names a composed make; the comparison is of everything else, under the ready-made's name.
        return f.spec(values).withName(name);
    }

    /** The calls without their narration (the plan line, the card label, the sculpt's note); powers as a set. */
    static JsonArray made(BuilderSpec spec) {
        JsonArray out = BuilderStarterGoldenTest.normal(BuilderStarterGolden.lowered(spec));
        JsonArray kept = new JsonArray();
        for (JsonElement c : out) {
            JsonObject o = c.getAsJsonObject();
            String tool = o.get("tool").getAsString();
            if (tool.equals("plan_wish") || tool.equals("finish_building")) {
                continue;
            }
            o.getAsJsonObject("args").remove("note");
            kept.add(o);
        }
        return kept;
    }

    @Test
    void everyStarterIsABodyPlusTheMenusChoices() {
        List<String> wrong = new ArrayList<>();
        int n = 0;
        for (BuilderSpec.Kind kind : List.of(BuilderSpec.Kind.CREATURE, BuilderSpec.Kind.RIDE)) {
            for (String p : BuilderStarterGolden.starters(kind)) {
                BuilderSpec old = saved(kind, p);
                String name = sculptName(p);
                BuilderSpec menus = composed(kind, p, old.powers(), name.isEmpty() ? "Sparky" : name);
                JsonArray a = made(old);
                JsonArray b = made(menus);
                if (name.isEmpty()) {
                    // A ready-made whose sculpt has no name was sent with none (the tool named it); the menus' make has
                    // the name he typed. Everything else must match.
                    unnamed(a);
                    unnamed(b);
                }
                if (!a.equals(b)) {
                    wrong.add(kind.id() + "/" + p + "\n  starter: " + a + "\n  menus:   " + b);
                }
                n++;
            }
        }
        assertTrue(n >= 100, "every creature and ride starter: " + n);
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    static void unnamed(JsonArray calls) {
        for (JsonElement c : calls) {
            c.getAsJsonObject().getAsJsonObject("args").remove("name");
        }
    }

    /** The name an unnamed starter make was called: its sculpt's own (the lowering's fallback). */
    static String sculptName(String preset) {
        JsonObject s = LibraryPresets.SHIPPED.read(preset);
        return s.has("name") ? s.get("name").getAsString() : "";
    }

    @Test
    void aBodyBringsNoNameAndOnlyWhatItDoesByItself() {
        for (String p : List.of("species/cat", "bodies/truck", "bodies/jellyfish")) {
            BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
            f.press("creature");
            f.press(p);
            BuilderFlowTest.toPowers(f);
            BuilderFlowTest.pastPowers(f);
            String moddys = f.spec(Map.of()).name();
            org.junit.jupiter.api.Assertions.assertFalse(moddys.equalsIgnoreCase(LibraryPresets.SHIPPED.label(p)), p + ": the body's own word is not its name: " + moddys);
            BuilderSpec s = composed(BuilderSpec.Kind.CREATURE, p, List.of(), "Sparky");
            assertTrue(s.powers().isEmpty(), p);
            JsonObject sculpt = BuilderLowering.lower(s, "mine_1", LibraryPresets.SHIPPED).stream()
                    .filter(c -> c.tool().equals("sculpt_creature")).findFirst().orElseThrow().args();
            List<String> powers = new ArrayList<>();
            sculpt.getAsJsonArray("powers").forEach(e -> powers.add(e.getAsString()));
            powers.remove("friendly");
            powers.removeAll(BuilderNatural.of(p, LibraryPresets.SHIPPED.read(p)));   //: what it does by itself
            assertEquals(List.of(), powers, p + " brought powers he did not pick");
        }
    }
}
