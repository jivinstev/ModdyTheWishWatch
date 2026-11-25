package com.moddingfromamod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** #75: the two markers a wished body carries, and what is not one. The names are pinned: worlds were told them. */
class WishedTest {

    @Test
    void eitherMarkerIsAWishedBodyAndNothingElseIs() {
        assertTrue(Wished.byTags(Set.of("wishmob", "wishmob_spikor")), "a sculpted creature or a rig's carrier");
        assertTrue(Wished.byTags(Set.of("moddy_wished")), "a plain mob a wish summoned");
        assertFalse(Wished.byTags(Set.of("wishmob_spikor")), "a species tag alone is a name, not the marker");
        assertFalse(Wished.byTags(Set.of()), "a natural spawn");
        assertFalse(Wished.byTags(null));
    }

    @Test
    void theMarkersAreTheDocumentedNames() {
        assertEquals("wishmob", com.moddingfromamod.entity.WishMobTags.ALL);
        assertEquals("moddy_wished", com.moddingfromamod.mob.WishMobLimit.WISHED_TAG);
    }
}
