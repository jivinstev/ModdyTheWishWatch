package com.moddingfromamod.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starting a creature from one that already exists. */
class SculptBaseTest {

    private static final String DRAGON = """
            [{"name":"body","from":[-6,8,-10],"to":[6,22,10],"colour":"green"},
             {"name":"head","from":[-4,20,-18],"to":[4,28,-10],"colour":"green"},
             {"name":"snout","from":[-3,20,-24],"to":[3,25,-18],"colour":"green"},
             {"name":"wingL","from":[6,16,-4],"to":[20,18,8],"colour":"green"},
             {"name":"wingR","from":[-20,16,-4],"to":[-6,18,8],"colour":"green"},
             {"name":"tail","from":[-2,12,10],"to":[2,16,24],"colour":"green"},
             {"name":"spike1","from":[-1,22,0],"to":[1,26,4],"colour":"white"},
             {"name":"spike2","from":[-1,22,6],"to":[1,26,10],"colour":"white"}]""";

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @AfterEach
    void clearRepo() {
        System.clearProperty("moddingfromamod.modelDir");
        ModelRepository.clearCache();
    }

    // ===================== the merge, pure =====================

    @Test
    void withNoEditsTheBaseComesThroughIntact() {
        var base = parse(DRAGON);
        assertEquals(base, SculptBase.merge(base, List.of(), Set.of(), Map.of()));
    }

    @Test
    void anAddedPartWithABasePartsNameReplacesIt() {
        // "make his head bigger" should be one box, not a re-sculpt.
        var base = parse(DRAGON);
        var bigger = parse("""
                [{"name":"head","from":[-8,20,-24],"to":[8,34,-10],"colour":"green"}]""");
        var merged = SculptBase.merge(base, bigger, Set.of(), Map.of());
        assertEquals(base.size(), merged.size(), "a replacement must not also append");
        var head = named(merged, "head");
        assertEquals(34f, head.to()[1], "the bigger head should have won");
    }

    @Test
    void removeDropsBasePartsLooselyByName() {
        var merged = SculptBase.merge(parse(DRAGON), List.of(), Set.of("spike", "tail"), Map.of());
        assertTrue(merged.stream().noneMatch(p -> p.name().startsWith("spike")),
                "\"spike\" must drop spike1 and spike2");
        assertTrue(merged.stream().noneMatch(p -> p.name().equals("tail")));
        assertNotNull(named(merged, "body"), "everything else survives");
    }

    @Test
    void recolourRepaintsBasePartsWithoutMovingThem() {
        var merged = SculptBase.merge(parse(DRAGON), List.of(), Set.of(),
                Map.of("wing", 0xFF0000));
        var wing = named(merged, "wingL");
        assertEquals(0xFF0000, wing.colour());
        assertEquals(20f, wing.to()[0], "recolour must not move anything");
        assertEquals(named(parse(DRAGON), "body").colour(), named(merged, "body").colour(),
                "an unnamed part keeps its colour");
    }

    @Test
    void theLongestRecolourQueryWins() {
        // Asked to paint the body green and the head red, "head" must not be caught by "hea"...
        var merged = SculptBase.merge(parse(DRAGON), List.of(), Set.of(),
                new java.util.LinkedHashMap<>(Map.of("s", 0x0000FF, "snout", 0xFFFF00)));
        assertEquals(0xFFFF00, named(merged, "snout").colour(),
                "the more specific query should win");
    }

    @Test
    void additionsAreAppendedAfterTheBase() {
        var merged = SculptBase.merge(parse(DRAGON),
                parse("[{\"name\":\"crown\",\"from\":[-3,28,-16],\"to\":[3,32,-12],"
                        + "\"colour\":\"yellow\"}]"), Set.of(), Map.of());
        assertEquals(parse(DRAGON).size() + 1, merged.size());
        assertEquals("crown", merged.get(merged.size() - 1).name());
    }

    @Test
    void aBaseCannotBeAWayAroundThePartBudget() {
        var huge = new java.util.ArrayList<CreatureSculpt.Part>();
        for (int i = 0; i < CreatureSculpt.MAX_PARTS; i++) {
            huge.addAll(parse("[{\"name\":\"b" + i + "\",\"from\":[0," + i + ",0],\"to\":[2,"
                    + (i + 2) + ",2],\"colour\":\"green\"}]"));
        }
        var merged = SculptBase.merge(huge, parse(DRAGON), Set.of(), Map.of());
        assertTrue(merged.size() <= CreatureSculpt.MAX_PARTS);
    }

    @Test
    void nullsAndEmptiesAreSafe() {
        assertTrue(SculptBase.merge(null, null, null, null).isEmpty());
        assertEquals(parse(DRAGON).size(),
                SculptBase.merge(parse(DRAGON), null, null, null).size());
    }

    // ===================== end to end, through the repository =====================

    /** Write a creature to a temp repo exactly as a real sculpt does, then start from it. */
    @Test
    void aRealSavedCreatureCanBeStartedFrom(@TempDir Path repo) throws Exception {
        System.setProperty("moddingfromamod.modelDir", repo.toString());
        ModelRepository.clearCache();
        var base = parse(DRAGON);
        String spec = "{\"id\":\"dragon\",\"parts\":" + DRAGON + "}";
        CreatureFiles.write("dragon", base, spec);
        assertTrue(Files.isRegularFile(ModelRepository.sculptFile("dragon")));

        // "a baby version of my dragon, but red, with no wings"
        var built = SculptCreatureTool.withBase(
                parse("[{\"name\":\"crown\",\"from\":[-3,28,-16],\"to\":[3,32,-12],"
                        + "\"colour\":\"yellow\"}]"),
                args("""
                    {"start_from":"dragon","remove":["wing"],"recolour":{"body":"red"}}"""));

        assertTrue(built.size() > 5, "the body should have been inherited, got " + built.size());
        assertTrue(built.stream().noneMatch(p -> p.name().startsWith("wing")), "wings removed");
        assertEquals(com.moddingfromamod.species.Species.parseColour("red"),
                named(built, "body").colour(), "body repainted");
        assertNotNull(named(built, "crown"), "the addition survived");
        assertNotNull(named(built, "snout"), "an untouched base part survived");
    }

    /**
     * <b>The economics.</b> The whole point is that a variant costs a diff rather than a creature.
     */
    @Test
    void aVariantIsFarSmallerToWriteThanTheCreature(@TempDir Path repo) throws Exception {
        System.setProperty("moddingfromamod.modelDir", repo.toString());
        ModelRepository.clearCache();
        CreatureFiles.write("dragon", parse(DRAGON), "{\"id\":\"dragon\",\"parts\":" + DRAGON + "}");

        String fromScratch = DRAGON;
        String asDiff = "{\"start_from\":\"dragon\",\"remove\":[\"wing\"],"
                + "\"recolour\":{\"body\":\"red\"},\"scale\":0.5}";
        var built = SculptCreatureTool.withBase(List.of(), args(asDiff));

        assertTrue(built.size() >= 5, "and it still produces a whole creature");
        assertTrue(asDiff.length() < fromScratch.length() / 3,
                "the diff (" + asDiff.length() + " chars) should be far smaller than the creature ("
                        + fromScratch.length() + ")");
    }

    @Test
    void anUnknownBaseDegradesToAPlainCreatureRatherThanFailing() {
        // A wish must not die because a child named a creature that does not exist.
        var built = SculptCreatureTool.withBase(parse(DRAGON), args("{\"start_from\":\"nope\"}"));
        assertEquals(parse(DRAGON).size(), built.size());
    }

    @Test
    void aModelWithNoSavedSpecIsNotUsableAsABaseAndSaysSoQuietly(@TempDir Path repo)
            throws Exception {
        System.setProperty("moddingfromamod.modelDir", repo.toString());
        ModelRepository.clearCache();
        // Written WITHOUT a spec — an imported vanilla model looks like this.
        CreatureFiles.write("imported", parse(DRAGON));
        assertFalse(Files.isRegularFile(ModelRepository.sculptFile("imported")));
        assertTrue(ModelRepository.sculptedParts("imported").isEmpty());
        var built = SculptCreatureTool.withBase(parse(DRAGON), args("{\"start_from\":\"imported\"}"));
        assertEquals(parse(DRAGON).size(), built.size(), "falls back to the agent's own parts");
    }

    /**
     * The published archive is a fallback source of bases. Every granted species wish lands
     * in the Mod Repo as {@code wishes/species/<id>/sculpt.json}, and the models directory is
     * per-WORLD — so without this a brand-new world can see none of the creatures already made,
     * even though the specs were sitting on disk the whole time.
     */
    @Test
    void aPublishedCreatureCanBeUsedAsABaseFromAnyWorld(@TempDir Path repo, @TempDir Path models)
            throws Exception {
        System.setProperty("moddingfromamod.modelDir", models.toString());
        System.setProperty("moddingfromamod.persistDir", repo.toString());
        com.moddingfromamod.runtime.RuntimeConfig.invalidate();
        ModelRepository.clearCache();

        Path published = repo.resolve("wishes").resolve("species").resolve("archived_dragon");
        Files.createDirectories(published);
        Files.writeString(published.resolve("sculpt.json"),
                "{\"id\":\"archived_dragon\",\"parts\":" + DRAGON + "}");

        // Nothing in the per-world models dir at all — this is a fresh world.
        assertFalse(Files.isRegularFile(ModelRepository.sculptFile("archived_dragon")));
        assertFalse(ModelRepository.sculptedParts("archived_dragon").isEmpty(),
                "a published creature should still be reachable as a base");

        var built = SculptCreatureTool.withBase(List.of(),
                args("{\"start_from\":\"archived_dragon\",\"remove\":[\"wing\"]}"));
        assertTrue(built.size() >= 5, "the archived body should have been inherited");
        assertTrue(built.stream().noneMatch(p -> p.name().startsWith("wing")));
        System.clearProperty("moddingfromamod.persistDir");
        com.moddingfromamod.runtime.RuntimeConfig.invalidate();
    }

    private static CreatureSculpt.Part named(List<CreatureSculpt.Part> parts, String name) {
        return parts.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * "make his hair brown" re-sends the hair box without its parent. The merge used to swap
     * it in wholesale, turning the hair into a root that stays behind when the head turns.
     */
    @Test
    void aReplacedPartKeepsItsParentUnlessItNamesANewOne() {
        var base = parse("""
                [{"name":"head","from":[-4,24,-4],"to":[4,32,4],"colour":"pink"},
                 {"name":"hair","parent":"head","from":[-5,30,-5],"to":[5,35,5],"colour":"yellow"},
                 {"name":"hat","parent":"head","from":[-5,35,-5],"to":[5,38,5],"colour":"red"}]""");
        var merged = SculptBase.merge(base, parse("""
                [{"name":"hair","from":[-5,29,-5],"to":[5,35,5],"colour":"brown"},
                 {"name":"hat","parent":"hair","from":[-5,35,-5],"to":[5,38,5],"colour":"red"}]"""),
                Set.of(), Map.of());
        var hair = merged.stream().filter(p -> p.name().equals("hair")).findFirst().orElseThrow();
        assertEquals("head", hair.parent(), "an edit that did not mention the parent kept it");
        assertEquals(29f, hair.from()[1], "...and the replacement's own box still won");
        var hat = merged.stream().filter(p -> p.name().equals("hat")).findFirst().orElseThrow();
        assertEquals("hair", hat.parent(), "a replacement that names a parent gets that parent");
    }
}
