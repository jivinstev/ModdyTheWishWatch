package com.moddingfromamod.builder;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The buildings edit language. {@link #aReplacedShapeGoesBackWhereItWas()} is the load-bearing one. */
class BuildBaseTest {

    private static BuildShape named(String name, int dx, int dy, int dz,
                                    int w, int h, int d, String block) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, false,
                BuildShape.Anchor.CORNER, name);
    }

    /** wall, then a doorway carved THROUGH it, then a roof — order is the whole point. */
    private static List<BuildShape> castle() {
        return List.of(named("keep", 0, 0, 0, 24, 14, 24, "minecraft:purple_concrete"),
                       named("doorway", 10, 0, 0, 4, 6, 1, "minecraft:air"),
                       named("roof", 0, 14, 0, 24, 6, 24, "minecraft:gold_block"));
    }

    @Test
    @DisplayName("an unnamed shape is appended, so it is placed last and overwrites")
    void anUnnamedShapeIsAppended() {
        BuildShape dome = named("", 4, 20, 4, 16, 10, 16, "minecraft:glass");
        List<BuildShape> out = BuildBase.merge(castle(), List.of(dome), Set.of());
        assertEquals(4, out.size());
        assertEquals(dome, out.get(3), "a dome goes ON the castle, which means last");
    }

    @Test
    @DisplayName("a replaced shape goes back WHERE IT WAS, not on the end")
    void aReplacedShapeGoesBackWhereItWas() {
        // "Make it much taller" is one shape. If the taller keep were appended instead of replacing
        // in place, it would be drawn AFTER the doorway and fill the doorway back in — a castle you
        // cannot walk into, that looks right from every side, and that nothing reports.
        BuildShape taller = named("keep", 0, 0, 0, 24, 40, 24, "minecraft:purple_concrete");
        List<BuildShape> out = BuildBase.merge(castle(), List.of(taller), Set.of());
        assertEquals(3, out.size(), "a replacement is not an addition");
        assertEquals(taller, out.get(0), "…and it holds the keep's own position");
        assertEquals("doorway", out.get(1).name(), "so the doorway is still carved after it");
        assertEquals("roof", out.get(2).name());
    }

    @Test
    @DisplayName("remove drops a base shape by name")
    void removeDropsABaseShapeByName() {
        List<BuildShape> out = BuildBase.merge(castle(), List.of(), Set.of("roof"));
        assertEquals(2, out.size());
        assertTrue(out.stream().noneMatch(s -> "roof".equals(s.name())));
    }

    @Test
    @DisplayName("names match the way a child's agent writes them")
    void namesMatchLoosely() {
        BuildShape taller = named("Keep", 0, 0, 0, 24, 40, 24, "minecraft:purple_concrete");
        assertEquals(3, BuildBase.merge(castle(), List.of(taller), Set.of()).size(),
                "'Keep' and 'keep' are the same wall");
        assertEquals(2, BuildBase.merge(castle(), List.of(), Set.of("ROOF")).size());
    }

    @Test
    @DisplayName("with no base, the sent shapes are the building")
    void withNoBaseTheSentShapesAreTheBuilding() {
        List<BuildShape> sent = List.of(named("keep", 0, 0, 0, 4, 4, 4, "minecraft:stone"));
        assertEquals(sent, BuildBase.merge(List.of(), sent, Set.of()));
        assertEquals(sent, BuildBase.merge(null, sent, null));
    }

    @Test
    @DisplayName("an edit that sends nothing keeps the building")
    void anEditThatSendsNothingKeepsTheBuilding() {
        // The failure this whole exercise is about, in its smallest form: a refinement that names
        // no shapes must leave the castle standing, never empty it.
        assertEquals(castle(), BuildBase.merge(castle(), List.of(), Set.of()));
    }

    @Test
    @DisplayName("a base with unnamed shapes is preserved, not silently replaced")
    void aBaseWithUnnamedShapesIsPreserved() {
        // Every plan saved before names existed has none. Those shapes must survive an edit, or
        // the first refinement of an old building deletes it.
        List<BuildShape> old = List.of(
                new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 20, 10, 20, "minecraft:stone", false),
                new BuildShape(BuildShape.Kind.BOX, 4, 10, 4, 12, 8, 12, "minecraft:stone", false));
        BuildShape spire = named("spire", 8, 18, 8, 4, 20, 4, "minecraft:diamond_block");
        List<BuildShape> out = BuildBase.merge(old, List.of(spire), Set.of());
        assertEquals(3, out.size(), "both nameless shapes survived: " + out);
        assertEquals(spire, out.get(2));
    }
}
