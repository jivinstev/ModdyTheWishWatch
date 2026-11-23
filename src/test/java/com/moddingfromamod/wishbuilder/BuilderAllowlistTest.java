package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderSpecTest.j;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** what a world offers is reviewed, and every offered id is one a tool really takes. */
class BuilderAllowlistTest {

    @Test
    void everyReviewedIdNamesSomethingAToolTakes() {
        for (String id : BuilderAllowlist.REVIEWED.ids()) {
            assertNull(Catalogue.problem(id), id);
        }
    }

    @Test
    void theReviewedListHoldsNothingThatHurtsOtherChildren() {
        for (String bad : new String[] {"power:hostile", "power:boss", "power:explodes"}) {
            assertFalse(BuilderAllowlist.REVIEWED.ids().contains(bad), bad);
        }
        assertFalse(BuilderAllowlist.REVIEWED.tempers().contains("boss"));
    }

    @Test
    void aPickOffTheListIsNamed() {
        var spec = BuilderSpec.read(j("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\","
                + "\"powers\":[\"power:explodes\"],\"temper\":\"boss\",\"count\":99}"));   //: 99 cats is past a Builder body's 50
        var problems = BuilderAllowlist.REVIEWED.problems(spec);
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(BuilderAllowlist.REVIEWED.problems(BuilderSpec.read(j(BuilderLoweringTest.ONE_PER_KIND.get(0)))).isEmpty());
    }

    @Test
    void aWorldBlockNarrowsAndIsCheckedWhenRead() {
        var narrow = BuilderAllowlist.fromJson(j("{\"kinds\":[\"creature\"],\"temperaments\":[\"friendly\"],\"maxCount\":1}"),
                BuilderAllowlist.REVIEWED);
        assertEquals(1, narrow.kinds().size());
        assertThrows(IllegalArgumentException.class,
                () -> BuilderAllowlist.fromJson(j("{\"ids\":[\"power:teleports\"]}"), BuilderAllowlist.REVIEWED));
        assertThrows(IllegalArgumentException.class,
                () -> BuilderAllowlist.fromJson(j("{\"temperaments\":[\"grumpy\"]}"), BuilderAllowlist.REVIEWED));
    }
}
