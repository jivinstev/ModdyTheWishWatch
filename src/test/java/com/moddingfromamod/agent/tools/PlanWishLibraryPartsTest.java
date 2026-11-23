package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * {@code plan_wish}'s reply names the library's ready-made parts and how to start from each,
 * because a picture or building wish never opens its listing before it acts (measured,
 * {@code tools/routing-arm.py}, world=library).
 */
class PlanWishLibraryPartsTest {

    @Test
    void namesEachKindWithTheArgumentThatStartsFromIt() {
        String out = PlanWishTool.libraryParts(List.of("wolf", "cat"), List.of("heart"), List.of("underwater_dome"));
        assertTrue(out.contains("READY-MADE PARTS in the library"), out);
        assertTrue(out.contains("bodies: cat, wolf → sculpt_creature start_from '<key>'"), out);
        assertTrue(out.contains("pictures: heart → paint_surface start_from '<key>'"), out);
        assertTrue(out.contains("buildings: underwater_dome → build_structure start_from '<key>'"), out);
    }

    @Test
    void aKindWithNothingIsLeftOutAndAnEmptyLibrarySaysNothing() {
        String out = PlanWishTool.libraryParts(List.of(), List.of("heart"), List.of());
        assertTrue(!out.contains("bodies") && !out.contains("buildings"), out);
        assertEquals("", PlanWishTool.libraryParts(List.of(), List.of(), List.of()));
    }

    @Test
    void aLongLibraryIsSummarisedAndPointsAtItsListing() {
        List<String> many = IntStream.range(0, PlanWishTool.PARTS_SHOWN + 5).mapToObj(i -> "b" + (100 + i)).toList();
        String out = PlanWishTool.libraryParts(many, List.of(), List.of());
        assertTrue(out.contains("and 5 more (list_creatures)"), out);
        assertTrue(!out.contains("b" + (100 + PlanWishTool.PARTS_SHOWN)), out);
    }
}
