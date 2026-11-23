package com.moddingfromamod.runtime;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Editing a creature must not destroy the thing you edit it from.</b>
 *
 * <p>Written to reproduce a bug read off the code and off thirteen real ladder runs, before fixing
 * it — because "the code looks like it does this" has been wrong more than once today.
 *
 * <h2>The bug</h2>
 * {@code persistAndPublish} saves {@code SculptSpec.editable(args, key)} — <b>the raw tool
 * arguments</b>. The merged parts are a local variable and are never written back. So when the
 * agent edits a creature with {@code start_from}:
 *
 * <ul>
 *   <li>the baked model is correct (it is built from the merged parts);</li>
 *   <li>but {@code sculpt.json} — the documented "re-editable source" — is overwritten with just
 *       the diff.</li>
 * </ul>
 *
 * The next edit reads that spec as its base and gets the diff. <b>The first edit destroys the
 * source; the second collapses the creature.</b>
 *
 * <p>It predicts the sequence in every one of thirteen ladder runs exactly:
 *
 * <pre>
 * 0 parts + start_from  -> model fine, spec becomes parts: []
 * 2 parts + start_from  -> base reads as [], so the creature IS two wings
 * 19-34 parts, no base  -> the agent rebuilds from scratch to recover
 * </pre>
 *
 * <p>Which is why no ladder in either arm ever completed an edit, and why the same worker looked
 * like the "ceiling": it was not editing either, it was re-typing the whole body from memory.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SculptEditGameTest {

    /** A body worth keeping: four named parts, none of which an edit should remove. */
    private static JsonObject creation(String id) {
        return JsonParser.parseString("""
            {"id":"%s","parts":[
              {"name":"body","from":[-4,0,-4],"to":[4,16,4],"colour":"orange"},
              {"name":"head","from":[-5,16,-5],"to":[5,26,5],"colour":"orange"},
              {"name":"legL","from":[-4,0,-2],"to":[-1,4,2],"colour":"green"},
              {"name":"legR","from":[1,0,-2],"to":[4,4,2],"colour":"green"}]}
            """.formatted(id)).getAsJsonObject();
    }

    /** The wish that broke it: "give him tiny black wings" — two parts, nothing else. */
    private static JsonObject wingsEdit(String id) {
        return JsonParser.parseString("""
            {"id":"%s","start_from":"%s","parts":[
              {"name":"wingL","from":[-8,10,-1],"to":[-4,16,1],"colour":"black"},
              {"name":"wingR","from":[4,10,-1],"to":[8,16,1],"colour":"black"}]}
            """.formatted(id, id)).getAsJsonObject();
    }

    private static java.util.Set<String> names(List<CreatureSculpt.Part> parts) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        parts.forEach(p -> out.add(p.name()));
        return out;
    }

    /**
     * <b>The persisted palette must describe the CREATURE, not the diff.</b>
     *
     * <p>Found in a run's recorded data, not by a gate: after an edit the saved palette read
     * {@code black 8%} for a creature that is 60% orange, because the agent's declaration
     * ("black 100%", describing two wings) was persisted verbatim beside the resolved parts. That
     * is exactly the misinformation removed from {@code inspect_creature}, reintroduced by the
     * fix for the persistence bug above — one fix breaking another.
     */
    @GameTest(template = "empty_test", batch = "sculptEdit")
    public static void thePersistedPaletteDescribesTheCreature(GameTestHelper helper)
            throws Exception {
        String id = "gt_pal_" + helper.getLevel().getGameTime();
        JsonObject create = creation(id);
        SculptCreatureTool.persistAndPublish(id,
                CreatureSculpt.parseParts(create.getAsJsonArray("parts")), create);
        ModelRepository.clearCache();

        JsonObject edit = JsonParser.parseString("""
            {"id":"%s","start_from":"%s","palette":[{"colour":"red","percent":100}],
             "parts":[{"name":"wingL","from":[-8,10,-1],"to":[-4,16,1],"colour":"red"},
                      {"name":"wingR","from":[4,10,-1],"to":[8,16,1],"colour":"red"}]}
            """.formatted(id, id)).getAsJsonObject();
        SculptCreatureTool.persistAndPublish(id,
                SculptCreatureTool.withBase(
                        CreatureSculpt.parseParts(edit.getAsJsonArray("parts")), edit), edit);
        ModelRepository.clearCache();

        String palette = String.join(", ", ModelRepository.palette(id));
        helper.assertTrue(palette.contains("orange"),
                Component.literal("the creature is mostly orange and its palette must say so — inspect_creature reads "
                        + "this to answer 'what colour is it'. Got: " + palette));
        helper.assertFalse(palette.startsWith("red"),
                Component.literal("the wings' own declaration must not become the creature's palette: " + palette));
        helper.succeed();
    }

    /**
     * Edit twice. The body must still be there — which is the whole promise of an editable source.
     */
    @GameTest(template = "empty_test", batch = "sculptEdit")
    public static void editingACreatureTwiceKeepsItsBody(GameTestHelper helper) throws Exception {
        String id = "gt_edit_" + helper.getLevel().getGameTime();

        // 1. Create it.
        JsonObject create = creation(id);
        SculptCreatureTool.persistAndPublish(id,
                CreatureSculpt.parseParts(create.getAsJsonArray("parts")), create);
        ModelRepository.clearCache();
        helper.assertTrue(names(ModelRepository.sculptedParts(id)).contains("body"),
                Component.literal("the creation must be readable as a base at all"));

        // 2. Edit it: add wings, exactly as the agent does.
        JsonObject edit = wingsEdit(id);
        List<CreatureSculpt.Part> merged = SculptCreatureTool.withBase(
                CreatureSculpt.parseParts(edit.getAsJsonArray("parts")), edit);
        helper.assertTrue(names(merged).containsAll(List.of("body", "head", "legL", "legR")),
                Component.literal("the MERGE itself is fine — the model built from it is correct. Got " + names(merged)));
        SculptCreatureTool.persistAndPublish(id, merged, edit);
        ModelRepository.clearCache();

        // 3. THE CLAIM. After one edit the source must still describe the whole creature.
        java.util.Set<String> after = names(ModelRepository.sculptedParts(id));
        helper.assertTrue(after.containsAll(List.of("body", "head", "legL", "legR")),
                Component.literal("one edit destroyed the editable source: it now reads " + after + ". The next edit "
                        + "takes that as its base, so the creature collapses to whatever the last "
                        + "diff happened to be — which is why no ladder ever completed an edit."));
        helper.assertTrue(after.containsAll(List.of("wingL", "wingR")),
                Component.literal("…and the wings must be in it too: " + after));

        // 4. A SECOND edit, which is where the collapse actually bit.
        JsonObject second = JsonParser.parseString("""
            {"id":"%s","start_from":"%s","parts":[
              {"name":"tail","from":[-1,4,4],"to":[1,8,9],"colour":"purple"}]}
            """.formatted(id, id)).getAsJsonObject();
        List<CreatureSculpt.Part> merged2 = SculptCreatureTool.withBase(
                CreatureSculpt.parseParts(second.getAsJsonArray("parts")), second);
        helper.assertTrue(names(merged2).containsAll(List.of("body", "head", "wingL", "tail")),
                Component.literal("the second edit must build on a whole creature, not on the last diff. Got "
                        + names(merged2)));
        helper.succeed();
    }

    /**
     * <b> G: an edit that RESHAPES a base part, through the real merge and the real save.</b>
     * A longer body must take its tail with it, and the saved source must hold the reshaped body —
     * so the next edit starts from the new proportions, not the old ones.
     */
    @GameTest(template = "empty_test", batch = "sculptEdit")
    public static void aReshapedBodyCarriesItsTailAndIsSaved(GameTestHelper helper) throws Exception {
        String id = "gt_reshape_" + helper.getLevel().getGameTime();
        JsonObject create = JsonParser.parseString("""
            {"id":"%s","parts":[
              {"name":"body","from":[-4,6,-8],"to":[4,13,8],"colour":"orange"},
              {"name":"head","from":[-4,9,-14],"to":[4,16,-8],"colour":"orange"},
              {"name":"legL","from":[-3,0,-6],"to":[-1,6,-4],"colour":"orange"},
              {"name":"tail","from":[-1,10,8],"to":[1,12,16],"colour":"orange","parent":"body"}]}
            """.formatted(id)).getAsJsonObject();
        SculptCreatureTool.persistAndPublish(id,
                CreatureSculpt.parseParts(create.getAsJsonArray("parts")), create);
        ModelRepository.clearCache();

        JsonObject edit = JsonParser.parseString("""
            {"id":"%s","start_from":"%s","parts":[],
             "reshape":[{"part":"body","scale":[1,1,1.5]}]}
            """.formatted(id, id)).getAsJsonObject();
        List<CreatureSculpt.Part> merged = SculptCreatureTool.withBase(List.of(), edit);
        SculptCreatureTool.persistAndPublish(id, merged, edit);
        ModelRepository.clearCache();

        List<CreatureSculpt.Part> saved = ModelRepository.sculptedParts(id);
        CreatureSculpt.Part body = saved.stream().filter(x -> x.name().equals("body")).findFirst().orElseThrow();
        CreatureSculpt.Part tail = saved.stream().filter(x -> x.name().equals("tail")).findFirst().orElseThrow();
        helper.assertTrue(body.to()[2] - body.from()[2] >= 23,
                Component.literal("the saved body must be half as long again (16 -> 24), was " + (body.to()[2] - body.from()[2])));
        helper.assertTrue(tail.from()[2] >= 11,
                Component.literal("the tail hangs on the body's back end and must have moved back with it, starts at z " + tail.from()[2]));
        helper.assertTrue(names(saved).containsAll(List.of("head", "legL")), Component.literal("nothing else lost: " + names(saved)));
        helper.succeed();
    }
}
