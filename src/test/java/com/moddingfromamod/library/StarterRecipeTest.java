package com.moddingfromamod.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.ApplyEffectTool;
import com.moddingfromamod.agent.tools.MakeItemTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>No starter pack ships with only its zip</b> (issue #28).
 *
 * <p>Every {@code starter-library/datapacks/<id>/} keeps a {@code recipe.json} ({@link StarterRecipe}): the tool calls
 * that made it, the compiler commit and the date, so {@code StarterRegenGameTest} can rebuild it on any version. A
 * pack without one could only be patched inside its zip, which is how the 1.21.11 port found five of them.
 */
class StarterRecipeTest {

    private static final Path SRC = Path.of("src/main/resources/starter-library");
    private static final Path PACKS = SRC.resolve("datapacks");
    private static final String FIX = "add datapacks/<id>/recipe.json (docs/STARTER_LIBRARY.md says what goes in it) "
            + "and list it in starter-library/index.txt";

    private static List<String> ids() throws Exception {
        try (Stream<Path> s = Files.list(PACKS)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static JsonObject json(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void everyStarterPackHasARecipe() throws Exception {
        assertFalse(ids().isEmpty(), "no starter packs found at " + PACKS);
        List<String> problems = StarterRecipe.problems(PACKS);
        assertTrue(problems.isEmpty(), "starter packs that cannot be rebuilt from the tree: " + problems + ". " + FIX);
    }

    @Test
    void everyRecipesLastCallMakesItsOwnPack() throws Exception {
        List<String> bad = new ArrayList<>();
        for (String id : ids()) {
            StarterRecipe.Recipe r = StarterRecipe.parse(Files.readString(PACKS.resolve(id).resolve(StarterRecipe.FILE)));
            assertTrue(Files.isRegularFile(PACKS.resolve(id).resolve(id + ".zip")), id + " has a recipe and no zip");
            StarterRecipe.Call last = r.calls().get(r.calls().size() - 1);
            JsonObject a = last.args();
            String makes;
            if ("apply_effect".equals(last.tool())) {
                makes = ApplyEffectTool.featureId(a.get("effect").getAsString(), a.get("tag").getAsString());
            } else if (a.has("start_from")) {
                makes = a.get("start_from").getAsString();
            } else {
                makes = MakeItemTool.featureId(a.get("name").getAsString(), a.get("effect").getAsString().replace('+', '_'));
            }
            if (!id.equals(makes)) {
                bad.add(id + ": its last call makes '" + makes + "'");
            }
        }
        assertTrue(bad.isEmpty(), "recipes whose calls make a different pack: " + bad);
    }

    /** An item's recipe and its design are one thing written twice; they may never say different things. */
    @Test
    void anItemsRecipeIsItsDesign() throws Exception {
        List<String> checked = new ArrayList<>();
        for (String id : ids()) {
            Path item = SRC.resolve("items").resolve(id).resolve("item.json");
            if (!Files.isRegularFile(item)) {
                continue;
            }
            StarterRecipe.Recipe r = StarterRecipe.parse(Files.readString(PACKS.resolve(id).resolve(StarterRecipe.FILE)));
            assertEquals(1, r.calls().size(), id + ": an item is one make_item call");
            assertEquals("make_item", r.calls().get(0).tool(), id + ": an item is made by make_item");
            JsonObject want = StarterRecipe.makeItemCall(json(item), json(SRC.resolve("items").resolve(id).resolve("icon.json")));
            JsonObject got = r.calls().get(0).args().deepCopy();
            want.remove("from_scratch");
            got.remove("from_scratch");
            got.remove("start_from");
            assertEquals(want, got, id + ": recipe.json and items/" + id + "/item.json + icon.json disagree");
            checked.add(id);
        }
        assertTrue(checked.size() >= 6, "fewer starter items than expected were checked: " + checked);
    }

    @Test
    void aPackWithNoRecipeIsCaught(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("new_pack"));
        Files.write(tmp.resolve("new_pack/new_pack.zip"), new byte[] {0});
        List<String> problems = StarterRecipe.problems(tmp);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("new_pack: no recipe.json"), problems.toString());
    }

    @Test
    void aRecipeForAnotherPackIsCaught(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("a"));
        JsonObject args = new JsonObject();
        args.addProperty("effect", "periodic");
        args.addProperty("tag", "b");
        String json = StarterRecipe.write("b", List.of(new StarterRecipe.Call("apply_effect", args)),
                "0123456789abcdef0123456789abcdef01234567", "2026-11-24", null).toString();
        Files.writeString(tmp.resolve("a/recipe.json"), json);
        assertEquals(List.of("a: its recipe says it makes 'b'"), StarterRecipe.problems(tmp));
    }

    @Test
    void aRecipeMissingWhatARebuildNeedsIsRefused() {
        JsonObject args = new JsonObject();
        args.addProperty("effect", "periodic");
        args.addProperty("tag", "t");
        JsonObject ok = StarterRecipe.write("periodic_t", List.of(new StarterRecipe.Call("apply_effect", args)),
                "0123456789abcdef0123456789abcdef01234567", "2026-11-24", "a note");
        assertEquals("periodic_t", StarterRecipe.parse(ok.toString()).id());
        for (String field : new String[] {"compiler", "date", "calls", "id", "recipe", "v"}) {
            JsonObject broken = ok.deepCopy();
            broken.remove(field);
            assertThrows(IllegalArgumentException.class, () -> StarterRecipe.parse(broken.toString()),
                    "a recipe without '" + field + "' must be refused");
        }
        JsonObject v2 = ok.deepCopy();
        v2.addProperty("v", 2);
        assertThrows(IllegalArgumentException.class, () -> StarterRecipe.parse(v2.toString()), "an unknown version");
        JsonObject otherTool = ok.deepCopy();
        otherTool.getAsJsonArray("calls").get(0).getAsJsonObject().addProperty("tool", "run_command");
        assertThrows(IllegalArgumentException.class, () -> StarterRecipe.parse(otherTool.toString()), "a tool no pack is made by");
    }
}
