package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** one wish leaves ONE of a thing — the rule, without a game. */
class NoDuplicateTest {

    @Test
    void aSecondBuildingFromNothingInOneWishIsRefusedAndNamesTheFirst() {
        String r = NoDuplicate.rebuildRefusal(List.of("giant_volcano"), "giant_ocean_volcano",
                false, false, false);
        assertNotNull(r, "the volcano case: a second fresh id in one wish must be refused");
        assertTrue(r.contains("id 'giant_volcano'") && r.contains("as_new: true")
                && r.startsWith("Nothing was built"), r);
    }

    @Test
    void theSameIdAnEditAnExistingIdOrAsNewGoesThrough() {
        List<String> built = List.of("giant_volcano");
        assertNull(NoDuplicate.rebuildRefusal(built, "Giant Volcano", false, false, false),
                "the same id (as typed) replaces it in place");
        assertNull(NoDuplicate.rebuildRefusal(built, "castle", true, false, true), "an edit");
        assertNull(NoDuplicate.rebuildRefusal(built, "castle", false, false, true),
                "an id that exists is replaced, not duplicated");
        assertNull(NoDuplicate.rebuildRefusal(built, "tower", false, true, false), "as_new");
        assertNull(NoDuplicate.rebuildRefusal(List.of(), "tower", false, false, false),
                "the first building of a wish");
    }

    @Test
    void aRefusedEditBlocksAFreshItemUntilAsNew() {
        String r = NoDuplicate.freshAfterRefusedEdit("grappling_hook_plain", false, false);
        assertNotNull(r, "the grappling hook case: a fresh item after a refused edit must be refused");
        assertTrue(r.contains("'grappling_hook_plain'") && r.contains("as_new: true")
                && r.contains("cannot change"), r);
        assertNull(NoDuplicate.freshAfterRefusedEdit("grappling_hook_plain", true, false),
                "another try at the edit is fine");
        assertNull(NoDuplicate.freshAfterRefusedEdit("grappling_hook_plain", false, true), "as_new");
        assertNull(NoDuplicate.freshAfterRefusedEdit(null, false, false), "no refused edit");
        assertTrue(NoDuplicate.refusedEditSuffix("hook").contains("Do NOT make a new item"));
    }

    @Test
    void theRunRemembersARefusedEditUntilItLands() {
        WishRun run = WishRun.detachedForTest(UUID.randomUUID(), UUID.randomUUID(), "a hook");
        run.editRefused("hook");
        run.editLanded("other");
        assertTrue("hook".equals(run.refusedEdit()), "another thing's edit does not clear it");
        run.editLanded("HOOK");
        assertNull(run.refusedEdit(), "the same thing's edit landing clears it");
        run.builtFresh("a");
        run.builtFresh("a");
        run.builtFresh("b");
        assertTrue(run.freshBuilds().equals(List.of("a", "b")));
    }
}
