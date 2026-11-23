package com.moddingfromamod.runtime;

import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for creature fidelity — the half a unit test cannot prove.
 *
 * <p>The unit tests establish that {@link CreatureSculpt} <i>computes</i> a tilted, patterned,
 * kit-built creature. What they cannot show is that the result survives the round trip through real
 * disk I/O and comes back out of {@link BedrockGeometry} — the parser the client actually feeds — as
 * the same creature. A geometry document that is well-formed but that the parser drops on the floor
 * would pass every unit test and render as nothing, which is exactly the failure mode that made the
 * tilt go on the bone instead of the cube.
 *
 * <p>Rendering itself is client-side and is Gate C ({@code TESTMODE=fidelity}).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SculptFidelityGameTest {

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    /**
     * A tilted part is written to disk and read back by the same parser the renderer uses, with the
     * tilt intact and on the bone.
     */
    @GameTest(template = "empty_test")
    public static void aTiltedPartSurvivesTheRoundTripToDisk(GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = parse("""
                [{"name":"body","from":[-6,0,-4],"to":[6,20,4],"colour":"green"},
                 {"name":"spike","from":[-1,18,-6],"to":[1,28,-4],"colour":"white",
                  "rotation":[-45,0,0],"inflate":1}]""");
        helper.assertTrue(parts.size() == 2, Component.literal("both parts should parse"));

        Path dir;
        try {
            dir = CreatureFiles.write("gt_tilted", parts, "{}");
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the creature: " + e));
            return;
        }
        helper.assertTrue(dir != null, Component.literal("the creature should have been written"));

        BedrockGeometry geo;
        try {
            geo = BedrockGeometry.fromJson(JsonParser.parseString(
                    Files.readString(dir.resolve("geometry.json"))).getAsJsonObject());
        } catch (Exception e) {
            helper.fail(Component.literal("the geometry we just wrote could not be read back: " + e));
            return;
        }
        helper.assertTrue(geo != null && geo.bones().size() == 2,
                Component.literal("the parser the renderer uses should see both bones"));

        BedrockGeometry.Bone spike = geo.bones().stream()
                .filter(b -> b.name().equals("spike")).findFirst().orElse(null);
        helper.assertTrue(spike != null, Component.literal("the spike bone should have survived"));
        // The whole point: a tilt that arrives as zero here renders as an upright box, and the
        // creature is a staircase again with every test still green.
        helper.assertTrue(Math.abs(spike.rotation()[0] + 45f) < 0.01f,
                Component.literal("the spike's tilt should be -45 on the bone, was " + spike.rotation()[0]));
        helper.assertTrue(spike.cubes().size() == 1
                        && Math.abs(spike.cubes().get(0).inflate() - 1f) < 0.01f,
                Component.literal("inflate should have survived on the cube"));
        helper.succeed();
    }

    /** A patterned part writes a texture that is genuinely more than one colour. */
    @GameTest(template = "empty_test")
    public static void aPatternedPartWritesMoreThanOneColour(GameTestHelper helper) {
        List<CreatureSculpt.Part> flat = parse("""
                [{"name":"skirt","from":[-6,0,-4],"to":[6,12,4],"colour":"green"}]""");
        List<CreatureSculpt.Part> striped = parse("""
                [{"name":"skirt","from":[-6,0,-4],"to":[6,12,4],"colour":"green",
                  "pattern":"stripes","pattern_colour":"yellow"}]""");
        try {
            int flatColours = distinctColours(CreatureFiles.write("gt_flat", flat, "{}"));
            int stripedColours = distinctColours(CreatureFiles.write("gt_striped", striped, "{}"));
            helper.assertTrue(flatColours == 1,
                    Component.literal("a flat part should paint exactly one colour, painted " + flatColours));
            helper.assertTrue(stripedColours > flatColours,
                    Component.literal("a striped part must actually put a second colour on the skin, painted "
                            + stripedColours));
        } catch (Exception e) {
            helper.fail(Component.literal("pattern round trip failed: " + e));
            return;
        }
        helper.succeed();
    }

    /**
     * A kit produces a creature the parser accepts, with tilted bones and the adoption marker
     * intact — the number gates on has to survive being written and read.
     */
    @GameTest(template = "empty_test")
    public static void aKitBuiltCreatureRoundTripsWithItsAdoptionMarker(GameTestHelper helper) {
        List<CreatureSculpt.Part> body = parse("""
                [{"name":"body","from":[-8,10,-6],"to":[8,32,6],"colour":"green"}]""");
        JsonObject args = JsonParser.parseString("""
                {"kits":[{"kit":"spike_row","on":"body","count":5,"colour":"white"}]}""")
                .getAsJsonObject();
        List<CreatureSculpt.Part> parts =
                com.moddingfromamod.agent.tools.SculptCreatureTool.withKits(body, args);
        helper.assertTrue(parts.size() > body.size(), Component.literal("the kit should have added parts"));
        helper.assertTrue(SculptKits.usedBy(parts) == parts.size() - body.size(),
                Component.literal("every added part should be marked as kit-built"));

        try {
            Path dir = CreatureFiles.write("gt_kitted", parts, "{}");
            BedrockGeometry geo =
                    BedrockGeometry.fromJson(JsonParser.parseString(
                    Files.readString(dir.resolve("geometry.json"))).getAsJsonObject());
            helper.assertTrue(geo.bones().size() == parts.size(),
                    Component.literal("every kit bone should survive the round trip"));
            long tilted = geo.bones().stream().filter(b ->
                    b.rotation()[0] != 0 || b.rotation()[1] != 0 || b.rotation()[2] != 0).count();
            helper.assertTrue(tilted >= 5,
                    Component.literal("a spike row should come back tilted, not axis-aligned — tilted bones: "
                            + tilted));
            long marked = geo.bones().stream()
                    .filter(b -> b.name().startsWith(SculptKits.MARK)).count();
            helper.assertTrue(marked == parts.size() - body.size(),
                    Component.literal("the adoption marker must survive on disk, found " + marked));
        } catch (Exception e) {
            helper.fail(Component.literal("kit round trip failed: " + e));
            return;
        }
        helper.succeed();
    }

    /**
     * A creature started from one that already exists, through the real repository.
     *
     * <p>Headless because that is where it can fail: the base is read back off disk from the
     * {@code sculpt.json} a previous sculpt wrote, so this exercises write → read → merge → bake,
     * which a unit test with an in-memory list does not.
     */
    @GameTest(template = "empty_test")
    public static void aCreatureCanBeStartedFromOneAlreadySaved(GameTestHelper helper) {
        List<CreatureSculpt.Part> base = parse("""
                [{"name":"body","from":[-6,8,-10],"to":[6,22,10],"colour":"green"},
                 {"name":"head","from":[-4,20,-18],"to":[4,28,-10],"colour":"green"},
                 {"name":"wingL","from":[6,16,-4],"to":[20,18,8],"colour":"green"},
                 {"name":"wingR","from":[-20,16,-4],"to":[-6,18,8],"colour":"green"}]""");
        try {
            CreatureFiles.write("gt_basedragon", base,
                    "{\"id\":\"gt_basedragon\",\"parts\":["
                    + "{\"name\":\"body\",\"from\":[-6,8,-10],\"to\":[6,22,10],"
                    + "\"colour\":\"green\"},"
                    + "{\"name\":\"head\",\"from\":[-4,20,-18],\"to\":[4,28,-10],"
                    + "\"colour\":\"green\"},"
                    + "{\"name\":\"wingL\",\"from\":[6,16,-4],\"to\":[20,18,8],"
                    + "\"colour\":\"green\"},"
                    + "{\"name\":\"wingR\",\"from\":[-20,16,-4],\"to\":[-6,18,8],"
                    + "\"colour\":\"green\"}]}");
        } catch (Exception e) {
            helper.fail(Component.literal("could not save the base: " + e));
            return;
        }
        helper.assertTrue(!ModelRepository.sculptedParts("gt_basedragon").isEmpty(),
                Component.literal("the saved base should be readable back as parts"));

        JsonObject args = JsonParser.parseString(
                "{\"start_from\":\"gt_basedragon\",\"remove\":[\"wing\"],"
                + "\"recolour\":{\"body\":\"red\"}}").getAsJsonObject();
        List<CreatureSculpt.Part> variant =
                com.moddingfromamod.agent.tools.SculptCreatureTool.withKits(
                        com.moddingfromamod.agent.tools.SculptCreatureTool.withBase(
                                List.of(), args), args);

        helper.assertTrue(variant.size() == 2,
                Component.literal("body + head should survive the wing removal, got " + variant.size()));
        helper.assertTrue(variant.stream().noneMatch(p -> p.name().startsWith("wing")),
                Component.literal("the wings should be gone"));
        int red = com.moddingfromamod.species.Species.parseColour("red");
        helper.assertTrue(variant.stream().anyMatch(
                        p -> p.name().equals("body") && p.colour() == red),
                Component.literal("the body should have been repainted"));

        try {
            Path dir = CreatureFiles.write("gt_variant", variant, "{}");
            BedrockGeometry geo = BedrockGeometry.fromJson(JsonParser.parseString(
                    Files.readString(dir.resolve("geometry.json"))).getAsJsonObject());
            helper.assertTrue(geo.bones().size() == variant.size(),
                    Component.literal("the variant should bake into a real model"));
        } catch (Exception e) {
            helper.fail(Component.literal("the variant did not bake: " + e));
            return;
        }
        helper.succeed();
    }

    /** How many distinct opaque colours the written skin contains. */
    private static int distinctColours(Path dir) throws Exception {
        var img = javax.imageio.ImageIO.read(dir.resolve("texture.png").toFile());
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) != 0) {
                    seen.add(argb & 0xFFFFFF);
                }
            }
        }
        return seen.size();
    }
}
