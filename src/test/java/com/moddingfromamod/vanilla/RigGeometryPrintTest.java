package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.moddingfromamod.runtime.CreatureSculpt.Part;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RigGeometryPrint} — the file-free re-sculpt oracle that replaced the deleted pack
 * writer's "did the model files change" answer (blocker 1).
 *
 * <p>Two directions, and both are failures a child sees. A print that does not move when the
 * geometry does leaves a re-sculpted creature wearing its old body; a print that moves when nothing
 * did rebuilds every rig in the world, each a visible flicker, on every wish anybody makes.
 */
class RigGeometryPrintTest {

    private static Part part(String name, String parent, float y) {
        return new Part(name, parent, new float[] { 0, y, 0 },
                new float[] { -2, y, -2 }, new float[] { 2, y + 4, 2 }, 0x336699,
                new float[] { 0, 15, 0 }, 0.25f, "stripes", 0xffffff);
    }

    private static List<Part> body() {
        return List.of(part("body", null, 0), part("head", "body", 4));
    }

    private static String print(List<Part> parts) {
        return RigGeometryPrint.of(parts, 1.0, Set.of("head"));
    }

    @Test
    @DisplayName("the same geometry read twice prints the same, although every float[] is a new array")
    void freshlyReadIdenticalGeometryIsUnchanged() {
        // THE TRAP THIS CLASS EXISTS TO AVOID: Part is a record over float[], so equals() is
        // reference equality and two reads of one file are "different". A print built on that
        // would rebuild every rig on every revision bump.
        List<Part> a = body();
        List<Part> b = body();
        assertNotEquals(a, b, "precondition: the record equality really is by reference");
        assertEquals(print(a), print(b));
    }

    @Test
    @DisplayName("it is a sha1 in form, because the reveal state it stands in for was one")
    void looksLikeASha1() {
        assertTrue(PackDelivery.isSha1(print(body())));
        assertTrue(PackDelivery.isSha1(RigGeometryPrint.combine(Map.of("k", print(body())))));
    }

    @Test
    @DisplayName("every field of every part moves the print")
    void everyFieldIsGeometry() {
        String base = print(body());
        Map<String, UnaryOperator<Part>> edits = new LinkedHashMap<>();
        edits.put("name", p -> new Part(p.name() + "x", p.parent(), p.pivot(), p.from(), p.to(),
                p.colour(), p.rotation(), p.inflate(), p.pattern(), p.patternColour()));
        edits.put("parent", p -> new Part(p.name(), "other", p.pivot(), p.from(), p.to(),
                p.colour(), p.rotation(), p.inflate(), p.pattern(), p.patternColour()));
        edits.put("pivot", p -> new Part(p.name(), p.parent(), new float[] { 1, 0, 0 }, p.from(),
                p.to(), p.colour(), p.rotation(), p.inflate(), p.pattern(), p.patternColour()));
        edits.put("from", p -> new Part(p.name(), p.parent(), p.pivot(),
                new float[] { -3, 0, -2 }, p.to(), p.colour(), p.rotation(), p.inflate(),
                p.pattern(), p.patternColour()));
        edits.put("to", p -> new Part(p.name(), p.parent(), p.pivot(), p.from(),
                new float[] { 2, 5, 2 }, p.colour(), p.rotation(), p.inflate(), p.pattern(),
                p.patternColour()));
        edits.put("colour", p -> new Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(),
                0x336698, p.rotation(), p.inflate(), p.pattern(), p.patternColour()));
        edits.put("rotation", p -> new Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(),
                p.colour(), new float[] { 0, 16, 0 }, p.inflate(), p.pattern(), p.patternColour()));
        edits.put("inflate", p -> new Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(),
                p.colour(), p.rotation(), 0.5f, p.pattern(), p.patternColour()));
        edits.put("pattern", p -> new Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(),
                p.colour(), p.rotation(), p.inflate(), "spots", p.patternColour()));
        edits.put("patternColour", p -> new Part(p.name(), p.parent(), p.pivot(), p.from(),
                p.to(), p.colour(), p.rotation(), p.inflate(), p.pattern(), 0x000000));
        Set<String> seen = new HashSet<>();
        seen.add(base);
        for (var e : edits.entrySet()) {
            List<Part> edited = List.of(e.getValue().apply(body().get(0)), body().get(1));
            String p = print(edited);
            assertNotEquals(base, p, "changing '" + e.getKey() + "' did not move the print -- a "
                    + "creature re-sculpted only in that field would keep its old body");
            assertTrue(seen.add(p), "'" + e.getKey() + "' collided with an earlier edit");
        }
    }

    @Test
    @DisplayName("scale, the animated bones, the part count and the part ORDER are geometry too")
    void theRestOfTheInputs() {
        String base = print(body());
        assertNotEquals(base, RigGeometryPrint.of(body(), 1.5, Set.of("head")), "scale");
        assertNotEquals(base, RigGeometryPrint.of(body(), 1.0, Set.of()), "animated bones");
        assertNotEquals(base, print(List.of(body().get(0))), "a part removed");
        assertNotEquals(base, print(List.of(body().get(1), body().get(0))), "parts reordered");
    }

    @Test
    @DisplayName("animated bones are a set: their order is not geometry")
    void boneOrderIsNotGeometry() {
        assertEquals(RigGeometryPrint.of(body(), 1.0, List.of("head", "body")),
                RigGeometryPrint.of(body(), 1.0, List.of("body", "head")));
    }

    @Test
    @DisplayName("null and empty strings are different parents, and names cannot run together")
    void noCollisionsFromConcatenation() {
        Part noParent = new Part("a", null, new float[3], new float[3], new float[3], 0);
        Part emptyParent = new Part("a", "", new float[3], new float[3], new float[3], 0);
        assertNotEquals(print(List.of(noParent)), print(List.of(emptyParent)));
        Part ab = new Part("ab", "c", new float[3], new float[3], new float[3], 0);
        Part a = new Part("a", "bc", new float[3], new float[3], new float[3], 0);
        assertNotEquals(print(List.of(ab)), print(List.of(a)));
    }

    @Test
    @DisplayName("the combined token moves with any creature and not with publish order")
    void theRevealToken() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("spikor", print(body()));
        one.put("robot", print(List.of(body().get(0))));
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("robot", one.get("robot"));
        reversed.put("spikor", one.get("spikor"));
        assertEquals(RigGeometryPrint.combine(one), RigGeometryPrint.combine(reversed),
                "publishing the same creatures in another order must not look like a re-sculpt");

        Map<String, String> resculpted = new LinkedHashMap<>(one);
        resculpted.put("robot", print(List.of(body().get(1))));
        assertNotEquals(RigGeometryPrint.combine(one), RigGeometryPrint.combine(resculpted),
                "re-sculpting ONE creature must move the reveal token, or the veil would treat "
                        + "the new sculpt as already revealed");

        Map<String, String> added = new LinkedHashMap<>(one);
        added.put("dragon", print(body()));
        assertNotEquals(RigGeometryPrint.combine(one), RigGeometryPrint.combine(added),
                "a new creature must move it too");
    }
}
