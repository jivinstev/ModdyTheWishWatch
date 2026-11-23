package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** a compound wish's notes reach every part, the main one first. */
class HandoverTargetsTest {

    private static final List<WishRun.Made> MADE = List.of(
            new WishRun.Made(MadeKind.CREATURE, "seahorse"),
            new WishRun.Made(MadeKind.BUILD, "underwater_sand_castle"));

    @Test
    void severalNamedAreEachRecordedTheFirstIsTheMainOne() {
        assertEquals(List.of("underwater_sand_castle", "seahorse"),
                HandoverTargets.of(" underwater_sand_castle , seahorse,", List.of(), MADE));
    }

    @Test
    void namingNoneOfACompoundWishRecordsEveryPart() {
        assertEquals(List.of("underwater_sand_castle", "seahorse"),
                HandoverTargets.of("", List.of("underwater_sand_castle", "seahorse"), MADE));
    }

    @Test
    void oneThingIsAsBefore() {
        assertEquals(List.of("spikor"), HandoverTargets.of("spikor", List.of(), MADE));
        assertEquals(List.of("seahorse"), HandoverTargets.of("", List.of(), MADE),
                "naming none of a single-thing wish: the thing it made");
        assertEquals(List.of(), HandoverTargets.of(null, null, List.of()), "made nothing, hands over nothing");
    }

    @Test
    void aLongListIsCapped() {
        String many = String.join(",", java.util.Collections.nCopies(1, "a")) + ",b,c,d,e,f,g,h,i,j,k";
        assertEquals(HandoverTargets.MAX, HandoverTargets.of(many, List.of(), MADE).size(),
                "each id is store writes on the server thread");
    }
}
