package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "and one that…" must inherit a design without overwriting it.
 *
 * <p>The line that was the whole bug, from {@code MakeItemTool}:
 *
 * <pre>{@code String featureId = base != null ? base.id() : featureId(name, …);}</pre>
 *
 * <p>Every use of {@code start_from} took the base's id, so there was no argument in any
 * combination that meant "a second one". These tests pin the three routes apart.
 */
class ItemLineageTest {

    @Test
    @DisplayName("the three routes, and only as_new makes a second thing")
    void theRoutesAreDistinct() {
        assertEquals(ItemLineage.Route.FRESH, ItemLineage.of(false, false));
        assertEquals(ItemLineage.Route.EDIT_IN_PLACE, ItemLineage.of(true, false),
                "start_from alone is a refinement — that behaviour must not change, it is what "
                + "eight ladder cells were fixed to get");
        assertEquals(ItemLineage.Route.COPY_THEN_DIVERGE, ItemLineage.of(true, true));
        // as_new with NO start_from has nothing to copy. It is a fresh thing, not an error: the
        // agent over-stating its intention should not cost the child a refusal.
        assertEquals(ItemLineage.Route.FRESH, ItemLineage.of(false, true),
                "as_new with nothing to copy is just a new item");
    }

    @Test
    @DisplayName("only an edit keeps the old id — that one line was the bug")
    void theIdIsWhereItWentWrong() {
        assertEquals("mjolnir", ItemLineage.idFor(
                ItemLineage.Route.EDIT_IN_PLACE, "mjolnir", "lava_pearl"),
                "a refinement must land on the same design or it creates instead of editing, "
                + "which is the defect #163 and the eight-cell measurement were about");
        assertEquals("lava_pearl", ItemLineage.idFor(
                ItemLineage.Route.COPY_THEN_DIVERGE, "mjolnir", "lava_pearl"),
                "a copy MUST get the fresh id; taking the base's id is exactly the old code, and "
                + "it silently turns 'and one that…' into a change to the thing he loved");
        assertEquals("lava_pearl", ItemLineage.idFor(
                ItemLineage.Route.FRESH, null, "lava_pearl"));
    }

    @Test
    @DisplayName("a copy that would land on its own base is refused, not silently done")
    void aCopyOntoItselfIsRefused() {
        // as_new with a name that mints the same id is a copy in name only: it inherits A, writes
        // under A's id, and the child still ends with one item — the original defect wearing the
        // new flag. Refusing is safe here because it asks the agent to SAY something different (a
        // new name), which is always possible; an earlier issue's rule only forbids refusing what may be
        // impossible.
        String said = ItemLineage.refusal(
                ItemLineage.Route.COPY_THEN_DIVERGE, "noob_pearl", "noob_pearl");
        assertNotNull(said, "a copy landing on its own base would silently be the old bug");
        assertTrue(said.contains("noob_pearl"), "…and it must name the collision: " + said);
        assertTrue(said.contains("as_new"),
                "…and name the remedy, both ways round — the agent may have meant an edit: " + said);
        // Case and whitespace are not a real difference of id.
        assertNotNull(ItemLineage.refusal(
                ItemLineage.Route.COPY_THEN_DIVERGE, "Noob_Pearl", " noob_pearl "));

        assertNull(ItemLineage.refusal(
                ItemLineage.Route.COPY_THEN_DIVERGE, "noob_pearl", "lava_pearl"),
                "a genuine copy must not be refused");
        assertNull(ItemLineage.refusal(ItemLineage.Route.EDIT_IN_PLACE, "noob_pearl", "noob_pearl"),
                "an EDIT landing on its own id is the entire point of an edit");
        assertNull(ItemLineage.refusal(ItemLineage.Route.FRESH, null, "lava_pearl"));
    }

    @Test
    @DisplayName("the result says which of the two happened, and names the way back")
    void theTranscriptCarriesTheOutcome() {
        // an earlier issue's own gate is "items in his inventory afterwards" — two for a copy, one for an
        // edit — and nothing in this repo's tiers can see a child's inventory. So the transcript
        // has to carry it, or a wrong route reads as success everywhere.
        String copy = ItemLineage.note(
                ItemLineage.Route.COPY_THEN_DIVERGE, "noob_pearl", "lava_pearl");
        assertTrue(copy.contains("SECOND") && copy.contains("BOTH"),
                "a copy must say he now has two: " + copy);
        assertTrue(copy.contains("untouched"),
                "…and that the original survived, which is the outcome that was impossible before: "
                + copy);

        String edit = ItemLineage.note(ItemLineage.Route.EDIT_IN_PLACE, "noob_pearl", "noob_pearl");
        assertTrue(edit.contains("CHANGED") && edit.contains("as_new"),
                "an edit must say what it did AND how to have got the other outcome — an agent "
                + "that meant 'another one' can only notice from this line: " + edit);

        assertEquals("", ItemLineage.note(ItemLineage.Route.FRESH, null, "lava_pearl"),
                "a plain new item needs no lineage sentence; saying one on every call is how a "
                + "warning stops being read");
    }

    @Test
    @DisplayName("a child's copy of a shared item has an id a datapack can carry (#1044)")
    void aForkedItemIdIsAValidDatapackNamespace() {
        java.util.regex.Pattern valid = com.moddingfromamod.datapack.CbScriptCompiler.VALID_ID;
        String bob = "b0b0b0b0-1111-2222-3333-444444444444";
        String id = ItemLineage.forkId("lucky_block_outcome_table", bob);
        assertTrue(valid.matcher(id).matches(), id);
        assertTrue(id.startsWith("lucky_block_outcome_table_"), id);
        // two children, two copies
        String alice = "a1a1a1a1-1111-2222-3333-444444444444";
        assertTrue(!id.equals(ItemLineage.forkId("lucky_block_outcome_table", alice)));
        // the same child, the same copy — so his second edit lands on it
        assertEquals(id, ItemLineage.forkId("lucky_block_outcome_table", bob));
        // a long base is shortened, never the child's part
        String longId = ItemLineage.forkId("a_really_extremely_long_item_name_for_a_wand_outcome_table", bob);
        assertTrue(valid.matcher(longId).matches(), longId);
        assertEquals("", ItemLineage.forkId("x", ""));
    }
}
