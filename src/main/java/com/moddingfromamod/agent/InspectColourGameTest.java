package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.InspectCreatureTool;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>{@code inspect_creature} must say what colour the thing is</b>.
 *
 * <p>Written from a measured failure, not a hunch. On the {@code snorble} ladder — an invented
 * creature, so nothing about it is guessable — rung 2's entire wish is "give him tiny black wings":
 *
 * <ul>
 *   <li>three same-worker runs kept him bright orange through a real re-sculpt;</li>
 *   <li><b>two of three different-worker runs repainted him white</b>, and rungs 3–5 then
 *       faithfully preserved the wrong creature.</li>
 * </ul>
 *
 * <p>It was not forgetfulness. All three cross-worker runs called this tool first and did exactly
 * the right thing. It answered with height, width, scale and every part position — and no colour.
 * The agent then has to declare a palette for {@code sculpt_creature} and supplies one from
 * imagination.
 *
 * <p><b>Driven through the real tool.</b> An earlier draft guarded the tool assertion behind
 * "if the species resolved", which would have skip-succeeded in exactly the case that matters — the
 * same shape as every skip-succeed this repo has been bitten by. The creature is now built for real
 * (geometry + spec + registered species) so the assertion cannot be dodged.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class InspectColourGameTest {

    private static List<CreatureSculpt.Part> parts() {
        return CreatureSculpt.parseParts(JsonParser.parseString("""
            [{"name":"body","from":[-4,0,-4],"to":[4,24,4],"colour":"orange"},
             {"name":"head","from":[-5,24,-5],"to":[5,34,5],"colour":"orange"}]
            """).getAsJsonArray());
    }

    /** The spec as sculpt_creature really writes it: parts AND the declared palette. */
    private static String spec() {
        return """
            {"id":"gt_colour","parts":[
               {"name":"body","from":[-4,0,-4],"to":[4,24,4],"colour":"orange"}],
             "palette":[{"colour":"orange","percent":55},{"colour":"green","percent":25},
                        {"colour":"purple","percent":15},{"colour":"white","percent":5}]}
            """;
    }

    @GameTest(template = "empty_test", batch = "inspectColour")
    public static void inspectCreatureSaysWhatColourItIs(GameTestHelper helper) throws Exception {
        String key = "gt_colour_" + helper.getLevel().getGameTime();
        CreatureFiles.write(key, parts(), spec());
        ModelRepository.clearCache();
        com.moddingfromamod.testing.SpeciesForTest.put(helper, Species.fromJson(
                JsonParser.parseString("{\"id\":\"" + key + "\",\"name\":\"Colour Test\","
                        + "\"health\":20,\"scale\":1.0,\"modelKey\":\"" + key + "\"}")
                        .getAsJsonObject()));

        List<String> palette = ModelRepository.palette(key);
        helper.assertTrue(palette.size() == 4, Component.literal("read 4 colours, got " + palette));
        helper.assertTrue(palette.get(0).startsWith("orange"),
                Component.literal("biggest share first — an agent skimming a list takes the first thing it reads; got "
                        + palette));

        // THE CLAIM. Everything above is a getter, and a getter nobody calls fixes nothing.
        JsonObject args = new JsonObject();
        args.addProperty("id", key);
        String answer = new InspectCreatureTool().call(args).text();
        helper.assertTrue(answer.contains("orange"),
                Component.literal("inspect_creature must say what colour it is — its absence repainted a Snorble "
                        + "white on a wish about wings (#147). Got: " + answer));
        helper.assertTrue(answer.contains("green") && answer.contains("purple"),
                Component.literal("…and every colour, not just the dominant one: a stranger re-sculpting needs the "
                        + "whole palette to pass back. Got: " + answer));
        helper.assertTrue(answer.toLowerCase(java.util.Locale.ROOT).contains("keep"),
                Component.literal("…and must say to keep them, or it is decoration rather than an instruction"));
        // Still answers the question it always answered.
        helper.assertTrue(answer.contains("blocks tall") && answer.contains("head"),
                Component.literal("the geometry it already reported must survive: " + answer));

        // A creature with no spec degrades to today's answer rather than an empty colour line
        // reading as "it has none".
        helper.assertTrue(ModelRepository.palette("gt_colour_nonexistent").isEmpty(),
                Component.literal("a creature with no spec has no palette to report"));
        helper.succeed();
    }
}
