package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ui.PickPage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>Every magic-block ready-made is a composition of the composer's picks</b>. The Lucky Block's spec and the
 * spec a child gets by picking gold, "when I break it" and "a lucky surprise" lower to the SAME tool calls, argument
 * for argument, with and without a colour scheme; a spec saved from the ready-made reads (SpecMigrations) as that
 * composition.
 */
class BuilderBlockComposableTest {

    /** The Builder library's magic-block ready-mades. */
    static List<String> readyBlocks() {
        List<String> out = new ArrayList<>();
        for (String p : Catalogue.presets()) {
            JsonObject j = p.startsWith("items/") ? LibraryPresets.SHIPPED.read(p) : null;
            if (j != null && "magic_block".equals(j.has("shape") ? j.get("shape").getAsString() : "")) {
                out.add(p);
            }
        }
        return out;
    }

    static List<BuilderLowering.Call> fromPreset(String preset, String scheme, String name) {
        return BuilderLowering.lower(new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.MAGIC_BLOCK, "preset:" + preset, scheme,
                2, List.of(), "", 1, "", name, Map.of()), "mine_1", LibraryPresets.SHIPPED);
    }

    /** The menus: A magic block → what it is made of → when → what it does → tune: strength, colours, name. */
    static List<BuilderLowering.Call> composed(BuilderBlocks.Ready r, String scheme, String name) {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("magic_block");
        assertEquals(BuilderFlow.Step.BLOCK_LOOK, f.step(), "the magic-block kind opens on what it is made of");
        f.press("blocklook:" + r.look());
        assertEquals(BuilderFlow.Step.BLOCK_WHEN, f.step());
        f.press("blockwhen:" + r.when());
        assertEquals(BuilderFlow.Step.BLOCK_DOES, f.step());
        f.press("blockdoes:" + r.does());
        assertEquals(BuilderFlow.Step.TUNE, f.step(), r.does() + " fits " + r.when());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("strength", String.valueOf(r.strength()));
        values.put("scheme", scheme.isEmpty() ? "as_is" : scheme);
        // Moddy names a composed make; the comparison is of everything else, under the ready-made's name.
        return BuilderLowering.lower(f.spec(values).withName(name), "mine_1", LibraryPresets.SHIPPED);
    }

    @Test
    void everyReadyMadeBlockHasItsComposition() {
        List<String> all = readyBlocks();
        assertTrue(!all.isEmpty());
        List<String> missing = all.stream().filter(p -> !BuilderBlocks.READY_MADES.containsKey(p)).toList();
        assertTrue(missing.isEmpty(), "magic-block ready-mades with no row in BuilderBlocks.READY_MADES: " + missing);
    }

    @Test
    void everyReadyMadeBlockLowersToTheSameCallsAsItsComposition() {
        List<String> wrong = new ArrayList<>();
        for (String p : readyBlocks()) {
            BuilderBlocks.Ready r = BuilderBlocks.READY_MADES.get(p);
            for (String scheme : new String[] {"", "ocean"}) {
                List<BuilderLowering.Call> a = fromPreset(p, scheme, r.name());
                List<BuilderLowering.Call> b = composed(r, scheme, r.name());
                if (!a.equals(b)) {
                    wrong.add(p + " " + scheme + "\n  ready-made: " + a + "\n  composed:   " + b);
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void aSpecSavedFromAReadyMadeBlockReadsAsItsComposition() {
        for (String p : readyBlocks()) {
            BuilderBlocks.Ready r = BuilderBlocks.READY_MADES.get(p);
            for (String named : new String[] {"", "Mystery Box"}) {
                JsonObject saved = JsonParser.parseString("{\"builder\":1,\"kind\":\"magic_block\",\"from\":\"preset:" + p
                        + "\"" + (named.isEmpty() ? "" : ",\"name\":\"" + named + "\"") + "}").getAsJsonObject();
                BuilderSpec read = BuilderSpec.read(saved);
                assertEquals("", read.from(), p);
                assertTrue(BuilderBlocks.composed(read), p);
                assertEquals(fromPreset(p, "", named.isEmpty() ? r.name() : named),
                        BuilderLowering.lower(read, "mine_1", LibraryPresets.SHIPPED), p + ": the migrated spec makes something else");
                BuilderFlow edit = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "id1", read);
                assertEquals(BuilderFlow.Step.BLOCK_LOOK, edit.step(), p + ": an edit reopens at the block's first page (#2281)");
                EditWalk.toTune(edit);
                String now = edit.spec(Map.of()).name();
                assertTrue(named.isEmpty() ? BuilderNames.valid(now) : now.equals(named), p + ": an edit keeps its name: " + now);
            }
        }
    }

    @Test
    void theMagicBlockKindHasNoReadyMadePage() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        assertTrue(f.fromIds(BuilderSpec.Kind.MAGIC_BLOCK).isEmpty());
        f.press("magic_block");
        assertEquals(BuilderFlow.Step.BLOCK_LOOK, f.step());
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.KIND, f.step());
    }
}
