package com.moddingfromamod.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G: proportions in a start_from edit, on the real cat starter. */
class SculptReshapeTest {

    private static List<CreatureSculpt.Part> cat() throws Exception {
        var o = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/starter-library/species/cat/sculpt.json"))).getAsJsonObject();
        return CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
    }

    private static JsonArray reshape(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static CreatureSculpt.Part part(List<CreatureSculpt.Part> ps, String name) {
        return ps.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<CreatureSculpt.Part> merged(List<CreatureSculpt.Part> base, SculptReshape.Result r) {
        return SculptBase.merge(base, r.replacements(), Set.of(), Map.of());
    }

    @Test
    void aLongerBodyTakesItsTailWithIt() throws Exception {
        var base = cat();
        var r = SculptReshape.apply(base, reshape("[{\"part\":\"body\",\"scale\":[1,1,1.5]}]"));
        var after = merged(base, r);
        float bodyBack = part(after, "body").to()[2];
        assertEquals(part(base, "body").to()[2] * 1.5f, bodyBack, 0.6f, "the body is half as long again");
        assertTrue(part(after, "tail").from()[2] > part(base, "tail").from()[2],
                "the tail hangs on the body's back end and moves back with it");
        assertEquals(base.size(), after.size(), "nothing is added or lost");
    }

    @Test
    void aBiggerHeadKeepsItsEarsAndEyesOnIt() throws Exception {
        var base = cat();
        var after = merged(base, SculptReshape.apply(base, reshape("[{\"part\":\"head\",\"scale\":1.4}]")));
        var head = part(after, "head");
        var ear = part(after, "earL");
        assertTrue(ear.from()[1] >= head.to()[1] - 2 && ear.from()[1] <= head.to()[1] + 1,
                "the ear still sits on the bigger head's top: ear y " + ear.from()[1] + ", head top " + head.to()[1]);
    }

    @Test
    void aPrefixReshapesEveryLegAndFeetStayOnTheGround() throws Exception {
        var base = cat();
        var r = SculptReshape.apply(base, reshape("[{\"part\":\"leg\",\"scale\":[1,1.5,1]}]"));
        var names = Set.copyOf(r.changes().stream().map(SculptReshape.Change::part).toList());
        assertTrue(names.containsAll(Set.of("legL", "legR")) && names.stream().allMatch(n -> n.startsWith("leg")),
                "every leg and nothing else: " + names);
        for (var p : r.replacements()) {
            assertEquals(0f, p.from()[1], 1e-4, p.name() + " keeps its foot on the ground");
            assertTrue(p.to()[1] > part(base, p.name()).to()[1], p.name() + " is taller");
        }
    }

    @Test
    void anUnknownPartIsSaidNotGuessed() throws Exception {
        var r = SculptReshape.apply(cat(), reshape("[{\"part\":\"trunk\",\"scale\":2}]"));
        assertTrue(r.replacements().isEmpty());
        assertTrue(r.note().contains("NOT RESHAPED") && r.note().contains("trunk"), r.note());
    }

    @Test
    void scaleIsClampedAndNeverShrinksAPartAway() throws Exception {
        var base = cat();
        var r = SculptReshape.apply(base, reshape("[{\"part\":\"tail\",\"scale\":[0.01,99,1]}]"));
        var t = r.replacements().get(0);
        assertTrue(t.to()[0] - t.from()[0] >= 0.99f, "never thinner than 1");
        var was = part(base, "tail");
        assertTrue(t.to()[1] - t.from()[1] <= (was.to()[1] - was.from()[1]) * 4 + 1e-3, "at most ×4");
    }
}
