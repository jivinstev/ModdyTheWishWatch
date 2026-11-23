package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderSpecTest.j;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** a snack machine composed from a look, a power, an ingredient and a fill lowers to two whole make_items. */
class BuilderSnacksTest {

    static BuilderSpec composed(String look, List<String> powers, String ingredient, int fill, String name) {
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.MACHINE, "", "", 2, List.of(), "", 1, "", name,
                BuilderSnacks.opts(look, powers, 2, ingredient, fill));
    }

    // ------------------------------------------------------------------ the table

    @Test
    void theStockLooksAreB5sOwnFoodsAndIngredients() {
        // The 21 foods B5's machines pour are kept under outputs/<id>_food; their machine's INPUT is the look's ingredient.
        List<String> wrong = new ArrayList<>();
        Map<String, String> inputByOutput = new java.util.HashMap<>();
        for (String p : Catalogue.presets()) {
            JsonObject m = p.startsWith("items/") ? LibraryPresets.SHIPPED.read(p) : null;
            if (m != null && "machine".equals(str(m, "shape")) && m.getAsJsonObject("params").has("FROM")) {
                inputByOutput.put(m.getAsJsonObject("params").get("TO").getAsString(),
                        m.getAsJsonObject("params").get("FROM").getAsString());
            }
        }
        for (BuilderSnacks.Look l : BuilderSnacks.LOOKS.subList(0, BuilderSnacks.STOCK)) {
            String out = l.id().equals("cookie") ? "minecraft:cookie" : l.id().equals("cake") ? "minecraft:cake" : l.id() + "_food";
            if (!out.startsWith("minecraft:")) {
                JsonObject food = LibraryPresets.SHIPPED.read("outputs/" + out);
                JsonObject icon = LibraryPresets.SHIPPED.icon("outputs/" + out);
                if (food == null || icon == null) {
                    wrong.add(l.id() + ": no outputs/" + out);
                    continue;
                }
                if (!str(food, "name").equals(l.label())) {
                    wrong.add(l.id() + ": called " + l.label() + ", the stock food is " + str(food, "name"));
                }
                if (!str(icon, "sprite").equals(l.sprite()) || !str(icon, "tint").equalsIgnoreCase(l.tint())) {
                    wrong.add(l.id() + ": wears " + l.sprite() + " " + l.tint() + ", the stock food " + icon);
                }
                if (!str(food, "base_item").equals(l.base())) {
                    wrong.add(l.id() + ": made on " + l.base() + ", the stock food on " + str(food, "base_item"));
                }
            }
            if (!l.ingredient().equals(inputByOutput.get(out))) {
                wrong.add(l.id() + ": takes " + l.ingredient() + ", its stock machine " + inputByOutput.get(out));
            }
        }
        assertEquals(23, inputByOutput.size(), "B5 ships 23 one-in-one-out (turn_into) snack machines");
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void everyLookIsOneChestOfIconsMakeItemKnows() {
        Set<String> seen = new HashSet<>();
        for (BuilderSnacks.Look l : BuilderSnacks.LOOKS) {
            assertTrue(seen.add(l.id()), "two looks are " + l.id());
            assertTrue(ItemCatalogue.ICONS.containsKey(l.sprite()), l.id() + ": no icon '" + l.sprite() + "'");
            assertTrue(l.base().equals("minecraft:cookie") || l.base().equals("minecraft:potion"), l.id() + ": " + l.base());
            assertTrue(l.tile().startsWith("minecraft:"), l.id());
            assertTrue(l.tint().matches("#[0-9A-Fa-f]{6}"), l.id() + ": " + l.tint());
            assertTrue(BuilderSnacks.INGREDIENTS.contains(l.ingredient()), l.id() + "'s own ingredient is on the list");
            assertNull(Catalogue.problem("look:" + l.id()));
        }
        assertTrue(BuilderSnacks.LOOKS.size() <= PickPage.MAX_TILES, "the looks fit one chest: " + BuilderSnacks.LOOKS.size());
        assertTrue(BuilderSnacks.LOOKS.size() > BuilderSnacks.STOCK, "more than the stock foods");
    }

    @Test
    void whatGoesInIsEverydayThingsOnly() {
        // Nothing an operator holds back, nothing that hurts (the issue's "nothing we never hand out").
        List<String> never = List.of("command_block", "spawn_egg", "barrier", "structure", "jigsaw", "debug", "bedrock",
                "tnt", "lava", "fire_charge", "end_portal", "light", "knowledge_book", "end_crystal", "wither");
        Set<String> seen = new HashSet<>();
        for (String i : BuilderSnacks.INGREDIENTS) {
            assertTrue(seen.add(i), "twice: " + i);
            assertTrue(i.matches("minecraft:[a-z_]+"), i);
            for (String n : never) {
                assertFalse(i.contains(n), i + " is never handed out");
            }
            assertNull(Catalogue.problem("ingredient:" + i));
        }
        assertTrue(BuilderSnacks.INGREDIENTS.size() <= PickPage.MAX_TILES, "fits one chest with the look's own first");
        assertTrue(Catalogue.problem("ingredient:minecraft:command_block") != null);
        assertTrue(Catalogue.problem("look:a_grenade") != null);
        assertEquals("minecraft:snowball", BuilderSnacks.ingredientsFor(BuilderSnacks.look("ice_cream")).get(0));
        assertEquals("Wheat Seeds", BuilderSnacks.words("minecraft:wheat_seeds"));
    }

    @Test
    void theSnacksIdIsTheOneMakeItemMints() {
        for (String name : List.of("Zoom Pizza", "Pizza", "  9 Lives!! ", "", "A very long snack name that goes on and on forever")) {
            for (List<String> fx : List.of(List.of("speed"), List.of("speed", "jump"), List.of("grow"))) {
                assertEquals(MakeItemTool.featureId(name, String.join("_", fx)), BuilderSnacks.snackId(name, fx), name + " " + fx);
            }
        }
        assertEquals("pizza_food", BuilderSnacks.snackId("Pizza", List.of("food")), "B5's foods ride the same rule");
    }

    // ------------------------------------------------------------------ the lowering

    @Test
    void aComposedMachineIsItsSnackThenTheFillMachineThatPoursIt() {
        BuilderSpec spec = BuilderSpec.read(j(BuilderExamples.COMPOSED));
        assertTrue(BuilderSnacks.composed(spec));
        List<BuilderLowering.Call> calls = BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED);
        assertEquals(List.of("plan_wish", "make_item", "make_item", "finish_building"), BuilderLoweringTest.tools(calls));

        JsonObject snack = calls.get(1).args();
        assertEquals("speed+jump", snack.get("effect").getAsString());
        assertEquals("on_consume", snack.get("shape").getAsString());
        assertEquals("minecraft:cookie", snack.get("base_item").getAsString());
        assertEquals("pizza_slice", snack.get("icon").getAsString());
        assertEquals("#F4C542", snack.get("colour").getAsString());
        assertEquals("Zoom Pizza", snack.get("name").getAsString());
        assertTrue(snack.get("as_new").getAsBoolean());
        // Two effects: the parameters grouped by effect, each exactly what the catalogue asks at strength 2.
        JsonObject params = snack.getAsJsonObject("params");
        assertEquals(new TreeSet<>(List.of("speed", "jump")), new TreeSet<>(params.keySet()));
        for (String fx : List.of("speed", "jump")) {
            assertEquals(new TreeSet<>(ItemCatalogue.load().get(fx).params()), new TreeSet<>(params.getAsJsonObject(fx).keySet()), fx);
        }

        JsonObject machine = calls.get(2).args();
        assertEquals("load_and_pour", machine.get("effect").getAsString());
        assertEquals("machine", machine.get("shape").getAsString());
        assertEquals("Zoom Pizza Machine", machine.get("name").getAsString());
        assertTrue(machine.get("only_looks_like").getAsBoolean());
        JsonObject mp = machine.getAsJsonObject("params");
        assertEquals("minecraft:wheat", mp.get("INGREDIENTS").getAsString());
        assertEquals("3", mp.get("MAX").getAsString(), "how many per fill is load_and_pour's MAX");
        assertEquals("none", mp.get("EXTRAS").getAsString());
        assertEquals("hand", mp.get("WHERE").getAsString());
        assertEquals(MakeItemTool.featureId("Zoom Pizza", "speed_jump"), mp.get("OUTPUT").getAsString(),
                "the machine pours the snack made just before it");
        assertEquals("minecraft:wheat 12", mp.getAsJsonObject("_kit").get("items").getAsString());
        // Every parameter load_and_pour asks for (the catalogue has no defaults), and the kit beside them.
        Set<String> given = new TreeSet<>(mp.keySet());
        given.remove("_kit");
        assertEquals(new TreeSet<>(ItemCatalogue.load().get("load_and_pour").params()), given);

        assertEquals("Zoom Pizza Machine", BuilderLowering.label(spec));
        assertEquals("Zoom Pizza Machine", BuilderLowering.refOf(calls, "mine_1"), "an edit finds the machine");
        assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty(), BuilderAllowlist.REVIEWED.problems(spec).toString());
    }

    @Test
    void oneStrongPowerIsAFlatParameterList() {
        BuilderSpec spec = new BuilderSpec(1, BuilderSpec.Kind.MACHINE, "", "", 2, List.of(), "", 1, "", "",
                BuilderSnacks.opts("ice_pop", List.of("giant"), 3, "minecraft:snowball", 8));
        List<BuilderLowering.Call> calls = BuilderLowering.lower(spec, "x", LibraryPresets.SHIPPED);
        JsonObject snack = calls.get(1).args();
        assertEquals("grow", snack.get("effect").getAsString());
        assertEquals("4", snack.getAsJsonObject("params").get("SCALE").getAsString(), "strength 3 of grow giant");
        assertEquals("Ice Pop", snack.get("name").getAsString(), "unnamed: the look's own name");
        assertEquals("ice_pop_grow", calls.get(2).args().getAsJsonObject("params").get("OUTPUT").getAsString());
        assertEquals("8", calls.get(2).args().getAsJsonObject("params").get("MAX").getAsString());
        assertEquals("minecraft:snowball 32", calls.get(2).args().getAsJsonObject("params").getAsJsonObject("_kit").get("items").getAsString());
    }

    @Test
    void aSpecThatDoesNotComposeIsRefusedNamingWhy() {
        assertThrows(IllegalArgumentException.class, () -> lower("pizza", List.of(), "minecraft:wheat", 3), "no power");
        assertThrows(IllegalArgumentException.class, () -> lower("pizza", List.of("giant", "tiny"), "minecraft:wheat", 3), "both grow");
        assertThrows(IllegalArgumentException.class, () -> lower("pizza", List.of("speed", "jump", "heal", "fly"), "minecraft:wheat", 3),
                "make_item's limit: three effects");
        assertThrows(IllegalArgumentException.class, () -> lower("pizza", List.of("lightning"), "minecraft:wheat", 3), "a sword's");
        assertThrows(IllegalArgumentException.class, () -> lower("pizza", List.of("speed"), "minecraft:wheat", 9), "fill 1-8");
        assertThrows(IllegalArgumentException.class, () -> lower("pizza", List.of("speed"), "minecraft:wheat", 0));
        assertThrows(IllegalArgumentException.class, () -> lower("pizza", List.of("speed"), "minecraft:command_block", 3));
        assertNotNull(BuilderSnacks.clash("giant", "tiny"));
        assertNull(BuilderSnacks.clash("speed", "fly"));
    }

    @Test
    void anOffListPickIsNamedByTheAllowlist() {
        BuilderSpec spec = composed("pizza", List.of("speed"), "minecraft:wheat", 3, "Zoom");
        BuilderAllowlist narrow = new BuilderAllowlist(BuilderAllowlist.REVIEWED.kinds(),
                BuilderAllowlist.REVIEWED.ids().stream().filter(i -> !i.equals("look:pizza")).collect(java.util.stream.Collectors.toSet()),
                BuilderAllowlist.REVIEWED.tempers(), 5);
        assertTrue(narrow.problems(spec).stream().anyMatch(p -> p.contains("look:pizza")), narrow.problems(spec).toString());
        assertTrue(BuilderLowering.ids(spec).containsAll(List.of("look:pizza", "effect:speed", "ingredient:minecraft:wheat")));
    }

    @Test
    void anOlderBuildRefusesAComposedSpecRatherThanMakingTheWrongMachine() {
        // A pre-#1938 build lowers a MACHINE with no preset as "from parts": opt("effect") or opt("power"). The composer
        // writes neither, so that build finds no effect and refuses, never a machine that does the snack's power.
        BuilderSpec spec = BuilderSpec.read(j(BuilderExamples.COMPOSED));
        assertEquals("", spec.opt("effect"));
        assertEquals("", spec.opt("power"));
        assertEquals(BuilderSpec.CURRENT, spec.toJson().get("builder").getAsInt(), "the shape is v1 with more opts");
        assertEquals(spec, BuilderSpec.read(spec.toJson()), "it round-trips");
    }

    @Test
    void anEditRemakesTheSnackAndChangesTheSameMachine() {
        BuilderSpec spec = BuilderSpec.read(j(BuilderExamples.COMPOSED));
        List<BuilderLowering.Call> calls = BuilderLowering.lower(spec, "x", LibraryPresets.SHIPPED,
                new BuilderLowering.Edit("Zoom Pizza Machine", "new"));
        assertEquals(List.of("plan_wish", "make_item", "make_item", "finish_building"), BuilderLoweringTest.tools(calls));
        assertFalse(calls.get(1).args().has("start_from"), "the snack is made again (a new power is a new snack)");
        assertEquals("Zoom Pizza Machine", calls.get(2).args().get("start_from").getAsString());
        assertFalse(calls.get(2).args().has("as_new"));
    }

    // ------------------------------------------------------------------ the menus

    @Test
    void aChildComposesOneThroughTheMenus() {
        BuilderFlow f = BuilderFlowTest.flow(BuilderAllowlist.REVIEWED);
        f.press("machine");
        assertEquals(BuilderFlow.Step.SNACK_LOOK, f.step(), "#1993: the machine kind opens on its looks (no ready-mades)");
        assertEquals(BuilderSnacks.LOOKS.size(), f.page().tiles().size(), "every look, one chest");
        f.press("look:pizza");
        assertEquals(BuilderFlow.Step.SNACK_POWER, f.step());
        assertEquals("Pick what eating it does first.", f.press(PickPage.NEXT_ID).say());
        f.press("snackpower:giant");
        // grow giant and grow tiny are one make_item effect, which it refuses twice: grey with the reason.
        assertEquals("Can't with Grow giant", f.page().blocked(tile(f, "snackpower:tiny")));
        assertEquals("Can't with Grow giant. Tap one to take it off first.", f.press("snackpower:tiny").say());
        f.press("snackpower:speed");
        f.press("snackpower:jump");
        assertEquals(BuilderFlow.Step.SNACK_POWER, f.step(), "no count fills the page: it waits for Done");
        // make_item's own limit, ItemCatalogue.MAX_EFFECTS: a fourth is grey and refused, in words
        assertEquals("One snack does 3 things at most", f.page().blocked(tile(f, "snackpower:fly")));
        assertEquals("One snack does 3 things at most. Tap one to take it off first.", f.press("snackpower:fly").say());
        f.press("snackpower:giant");   // off again
        assertTrue(f.page().tiles().stream().filter(PickPage.Tile::selected).map(PickPage.Tile::id).toList()
                .containsAll(List.of("snackpower:speed", "snackpower:jump")));
        assertEquals("What eating it does", f.page().title());
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.SNACK_INGREDIENT, f.step());
        assertEquals("ingredient:minecraft:wheat", f.page().tiles().get(0).id(), "the pizza's own ingredient first");
        f.press("ingredient:minecraft:honey_bottle");
        assertEquals(BuilderFlow.Step.TUNE, f.step());

        TuneDialog d = f.tune();
        assertEquals(List.of("strength", "fill", "how"), d.keys());
        assertEquals("Tune your Pizza", d.title());
        BuilderSpec spec = f.spec(f.values("3 5 pour"));
        assertTrue(BuilderSnacks.composed(spec));
        assertEquals(BuilderNames.name(spec, LibraryPresets.SHIPPED, List.of()), spec.name(), "Moddy names the snack");
        assertTrue(spec.name().endsWith("Pizza"), spec.name());
        assertEquals("3", spec.opt("strength"));
        assertEquals("5", spec.opt("fill"));
        assertEquals("ingredient:minecraft:honey_bottle", spec.opt("ingredient"));
        assertEquals(List.of("speed", "jump"), BuilderSnacks.chosenPowers(spec));
        assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty());
        assertEquals(4, BuilderLowering.lower(spec, "x", LibraryPresets.SHIPPED).size());

    }

    @Test
    void backWalksTheComposerToTheKinds() {
        BuilderFlow f = BuilderFlowTest.flow(BuilderAllowlist.REVIEWED);
        f.press("machine");
        f.press("look:taco");
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.SNACK_LOOK, f.step());
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.KIND, f.step(), "#1993: the looks are the machine kind's first page");
    }

    @Test
    void justYummyIsOnlyForAPlainFoodAndRidesAlone() {
        BuilderFlow f = BuilderFlowTest.flow(BuilderAllowlist.REVIEWED);
        f.press("machine");
        f.press("look:pizza");
        assertFalse(BuilderFlowTest.ids(f.page()).contains("snackpower:yummy"), "a pizza is not a vanilla food");
        f.press(PickPage.BACK_ID);
        f.press("look:cookie");
        assertTrue(BuilderFlowTest.ids(f.page()).contains("snackpower:yummy"));
        f.press("snackpower:yummy");
        assertEquals("Can't with Just yummy. Tap one to take it off first.", f.press("snackpower:speed").say());
        f.press(PickPage.NEXT_ID);
        f.press("ingredient:minecraft:wheat");
        BuilderSpec spec = f.spec(f.values("2 3 convert"));
        var calls = BuilderLowering.lower(spec, "x", LibraryPresets.SHIPPED);
        var made = calls.stream().filter(c -> c.tool().equals("make_item")).toList();
        assertEquals(1, made.size(), "no snack of its own: it pours cookies");
        assertEquals("minecraft:cookie", made.get(0).args().getAsJsonObject("params").get("TO").getAsString());
        assertEquals("Cookie Oven", made.get(0).args().get("name").getAsString());
        assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty(), BuilderAllowlist.REVIEWED.problems(spec).toString());
    }

    @Test
    void anEditReopensAtTheDialogWithEveryPick() {
        BuilderSpec was = BuilderSpec.read(j(BuilderExamples.COMPOSED));
        BuilderFlow f = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "zoom_1", was);
        assertEquals(BuilderFlow.Step.SNACK_LOOK, f.step(), "an edit reopens at the snack's first page (#2281)");
        EditWalk.toTune(f);
        BuilderSpec now = f.spec(f.values("1 3 pour"));
        assertEquals("1", now.opt("strength"));
        assertEquals(was.opt("look"), now.opt("look"));
        assertEquals(was.opt("ingredient"), now.opt("ingredient"));
        assertEquals(BuilderSnacks.chosenPowers(was), BuilderSnacks.chosenPowers(now));
    }

    @Test
    void aWorldWithNoLooksHasNoComposer() {
        BuilderAllowlist narrow = new BuilderAllowlist(BuilderAllowlist.REVIEWED.kinds(),
                BuilderAllowlist.REVIEWED.ids().stream().filter(i -> !i.startsWith("look:")).collect(java.util.stream.Collectors.toSet()),
                BuilderAllowlist.REVIEWED.tempers(), 5);
        BuilderFlow f = BuilderFlowTest.flow(narrow);
        assertFalse(BuilderFlowTest.ids(f.page()).contains("machine"), "#1993: with no composer there is no machine kind");
    }

    static List<BuilderLowering.Call> lower(String look, List<String> powers, String ingredient, int fill) {
        return BuilderLowering.lower(composed(look, powers, ingredient, fill, "Snacky"), "x", LibraryPresets.SHIPPED);
    }

    static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static PickPage.Tile tile(BuilderFlow f, String id) {
        return f.page().tiles().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }
}
