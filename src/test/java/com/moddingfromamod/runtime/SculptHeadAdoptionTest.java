package com.moddingfromamod.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** everything that sits ON the head hangs FROM the head. See {@link SculptHeadAdoption}. */
class SculptHeadAdoptionTest {

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    private static final String HEAD = """
            {"name":"head","from":[-4,24,-4],"to":[4,32,4],"colour":"pink"}""";
    private static final String BODY = """
            {"name":"body","from":[-4,12,-2],"to":[4,24,2],"colour":"blue"}""";

    @Test
    @DisplayName("a root hair box over the top and back of the head is hung from the head")
    void rootHairIsAdopted() {
        var parts = parse("[" + BODY + "," + HEAD + """
                ,{"name":"hair","from":[-5,27,-2],"to":[5,34,5],"colour":"brown"}]""");
        assertEquals(Map.of("hair", "head"), SculptHeadAdoption.adoptions(parts));
        var fixed = SculptHeadAdoption.adopt(parts);
        assertEquals("head", fixed.get(2).parent());
        assertArrayEquals(parts.get(2).from(), fixed.get(2).from(), "geometry is untouched");
    }

    @Test
    @DisplayName("a hair box hung from BODY is re-hung from the head too")
    void hairOnTheBodyIsAdopted() {
        var parts = parse("[" + BODY + "," + HEAD + """
                ,{"name":"hairTop","parent":"body","from":[-5,32,-5],"to":[5,35,5],"colour":"brown"}]""");
        assertEquals(Map.of("hairTop", "head"), SculptHeadAdoption.adoptions(parts));
    }

    @Test
    @DisplayName("an unnamed box sitting on top of the head is adopted by geometry alone")
    void anUnnamedTopIsAdopted() {
        var parts = parse("[" + BODY + "," + HEAD + """
                ,{"name":"top","from":[-5,32,-5],"to":[5,36,5],"colour":"brown"}]""");
        assertEquals(Map.of("top", "head"), SculptHeadAdoption.adoptions(parts));
    }

    @Test
    @DisplayName("the body, a collar, a hood and a raised arm are NOT dragged into the head")
    void neckAndLimbsStayPut() {
        var parts = parse("[" + BODY + "," + HEAD + """
                ,{"name":"collar","parent":"body","from":[-5,22,-4],"to":[5,26,4],"colour":"white"}
                ,{"name":"hood","parent":"body","from":[-6,23,2],"to":[6,30,5],"colour":"grey"}
                ,{"name":"armL","from":[-8,24,-2],"to":[-4,34,2],"colour":"pink"}
                ,{"name":"scarf","from":[-5,22,-5],"to":[5,25,5],"colour":"red"}]""");
        assertEquals(Map.of(), SculptHeadAdoption.adoptions(parts));
    }

    @Test
    @DisplayName("parts already under a head bone, and features far from it, are left alone")
    void alreadyRightOrFarAway() {
        var parts = parse("[" + BODY + "," + HEAD + """
                ,{"name":"eyeL","parent":"head","from":[-3,28,-5],"to":[-1,30,-4],"colour":"black"}
                ,{"name":"hat","from":[-6,40,-6],"to":[6,42,6],"colour":"red"}]""");
        assertEquals(Map.of(), SculptHeadAdoption.adoptions(parts),
                "a hat floating 8 units above the head is not on it");
        assertSame(parts, SculptHeadAdoption.adopt(parts), "no change -> the same list");
    }

    @Test
    @DisplayName("a tilted head adopts nothing, because a child inherits the tilt and would MOVE")
    void aTiltedHeadAdoptsNothing() {
        var parts = parse("[" + BODY + """
                ,{"name":"head","from":[-4,24,-4],"to":[4,32,4],"colour":"pink","rotation":[10,0,0]}
                ,{"name":"hair","from":[-5,27,-2],"to":[5,34,5],"colour":"brown"}]""");
        assertEquals(Map.of(), SculptHeadAdoption.adoptions(parts));
    }

    @Test
    @DisplayName("a feature named as one is adopted when it touches the head from the side")
    void aSideEarIsAdopted() {
        var parts = parse("[" + BODY + "," + HEAD + """
                ,{"name":"earL","from":[-6,27,-1],"to":[-4,30,1],"colour":"pink"}]""");
        assertEquals(Map.of("earL", "head"), SculptHeadAdoption.adoptions(parts));
    }

    @Test
    @DisplayName("the sculpt tool saves the adopted creature")
    void theToolAdopts() {
        String json = "[" + BODY + "," + HEAD + """
                ,{"name":"hair","from":[-5,27,-2],"to":[5,34,5],"colour":"brown"}]""";
        var saved = SculptCreatureTool.withKits(parse(json), new JsonObject());
        assertEquals("head", saved.stream().filter(p -> p.name().equals("hair"))
                .findFirst().orElseThrow().parent());
    }

    /**
     * <b>Every committed sculpt:</b> adoption never changes a part's box, never touches a limb or
     * torso, and on this corpus finds exactly the one real miss the sweep found (tralalelo-armA's
     * root {@code snout}). If this set grows, look at what was added before widening it.
     */
    @Test
    @DisplayName("across the committed sculpts it finds only the real miss, and moves no box")
    void theCommittedCorpus() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path dir = root.resolve("corpus/tier-experiment");
        if (!Files.isDirectory(dir)) {
            dir = root.getParent().resolve("corpus/tier-experiment");
        }
        java.util.Map<String, Map<String, String>> found = new java.util.TreeMap<>();
        int sculpts = 0;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().equals("sculpt.json")).toList()) {
                var obj = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                if (!obj.has("parts")) {
                    continue;
                }
                var parts = CreatureSculpt.parseParts(obj.getAsJsonArray("parts"));
                sculpts++;
                var moves = SculptHeadAdoption.adoptions(parts);
                var fixed = SculptHeadAdoption.adopt(parts);
                for (int i = 0; i < parts.size(); i++) {
                    assertArrayEquals(parts.get(i).from(), fixed.get(i).from());
                    assertArrayEquals(parts.get(i).to(), fixed.get(i).to());
                }
                if (!moves.isEmpty()) {
                    found.put(dir.relativize(f).toString(), moves);
                }
            }
        }
        // Two datasets, told apart by SpikeFixture: here the nine private sculpts and their four public
        // stand-ins; in the public repo the stand-ins alone. legfish-a is the shark's stand-in: the same boxes,
        // so the same one miss.
        boolean real = com.moddingfromamod.vanilla.SpikeFixture.PRIVATE_CORPUS;
        assertTrue(sculpts >= (real ? 9 : 4), "the corpus went missing: " + sculpts + " sculpts under " + dir);
        assertEquals(real ? Map.of("tralalelo-armA/sculpt.json", Map.of("snout", "head"),
                "legfish-a/sculpt.json", Map.of("snout", "head"))
                : Map.of("legfish-a/sculpt.json", Map.of("snout", "head")), found);
    }
}
