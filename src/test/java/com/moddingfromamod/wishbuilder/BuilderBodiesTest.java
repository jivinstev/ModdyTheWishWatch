package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * (B1): the 18 hand-picked bodies are the BUILDER's, and the wish worker cannot see them until B0c
 * says it should (an earlier issue's blind vote: a base hurt a different species' wish).
 */
class BuilderBodiesTest {

    static final Path ROOT = Path.of("src/main/resources/builder-bodies/species");

    @Test
    void theIndexAndTheDirectoryAgree() throws Exception {
        TreeSet<String> dirs = new TreeSet<>();
        try (var ds = Files.list(ROOT)) {
            ds.forEach(d -> dirs.add("species/" + d.getFileName()));
        }
        TreeSet<String> index = new TreeSet<>();
        for (String l : Files.readAllLines(Path.of("src/main/resources/builder-bodies/index.txt"))) {
            if (!l.isBlank() && !l.startsWith("#")) {
                index.add(l.strip());
            }
        }
        assertEquals(dirs, index, "a body missing from the index would silently not ship");
        assertEquals(18 + 26, dirs.size(), "the 19 hand picks less the bee (B1, #1838), and B4's 16 bodies and 10 vehicles (#1841)");
    }

    @Test
    void theWishLibraryNeverSeesThem() throws Exception {
        String starters = Files.readString(Path.of("src/main/resources/starter-library/index.txt"));
        try (var ds = Files.list(ROOT)) {
            for (Path d : ds.toList()) {
                String id = d.getFileName().toString();
                assertFalse(starters.contains("species/" + id + "/"),
                        id + " is in the starter library, where the wish worker's start_from list sees it");
            }
        }
    }

    @Test
    void everyBodyIsOfferedAndLowersToAWholeSculpt() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        List<String> creatures = f.fromIds(BuilderSpec.Kind.CREATURE);
        for (String body : List.of("bodies/horse", "bodies/car", "bodies/t_rex", "bodies/person")) {
            assertTrue(creatures.contains(body), body + " is offered");
        }
        BuilderSpec ride = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"ride\",\"from\":\"preset:bodies/horse\",\"scheme\":\"royal\",\"temper\":\"wild\"}")
                .getAsJsonObject());
        var calls = BuilderLowering.lower(ride, "mine_1", LibraryPresets.SHIPPED);
        JsonObject a = calls.get(1).args();
        assertEquals("sculpt_creature", calls.get(1).tool());
        assertFalse(a.has("start_from"), "a body is not in the wish library, so it is sent whole");
        assertTrue(a.getAsJsonArray("parts").size() > 10);
        assertTrue(a.getAsJsonArray("parts").toString().contains(Schemes.byId("royal").main()), "the scheme is on the parts");
        List<String> powers = BuilderLoweringTest.strings(a.getAsJsonArray("powers"));
        assertTrue(powers.contains("rideable"));
        assertFalse(powers.contains("friendly"), "wild means wild: the body's own 'friendly' is the temperament's to decide");
    }
}
