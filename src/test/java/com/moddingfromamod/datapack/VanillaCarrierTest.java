package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ── TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
// Pure tests for the switch. If the route is abandoned this whole file goes; if it ships, the
// MOD ITEM becomes the legacy path instead. Gated.

/**
 * The carrier's effect on the give line — {@code docs/VANILLA_ITEM_SPIKE_ISSUES.md} A1 and A2.
 *
 * <p>Every assertion here is about a STRING that goes into a {@code /give}, which is why it needs no
 * game. The failures they guard are all silent in one: a missing {@code custom_model_data} renders
 * a plain horse armour, a missing {@code dyed_color} renders it brown, and a give line naming the
 * mod item reaches a vanilla client as nothing at all.
 */
class VanillaCarrierTest {

    @AfterEach
    void restore() {
        // A global switch in a test is exactly how one test lands inside another's measurement.
        VanillaCarrier.force(null);
    }

    @Test
    @DisplayName("off by default: every id is what it always was")
    void offByDefaultTheGiveLineIsUnchanged() {
        VanillaCarrier.force(false);
        assertFalse(VanillaCarrier.enabled());
        assertEquals(ItemBase.WISH_ITEM, VanillaCarrier.itemId());
        assertEquals(ItemBase.WISH_ITEM, ItemBase.id(null),
                "with the carrier off, a wish must resolve to the mod item exactly as before — the "
                        + "shipped path is not allowed to change");
        assertEquals(ItemBase.WISH_ITEM, ItemBase.id(ItemBase.WISH_ITEM));
    }

    @Test
    @DisplayName("on: the wish item resolves to the carrier, downstream of the agent")
    void onTheWishItemResolvesToTheCarrier() {
        VanillaCarrier.force(true);
        // A2. The agent still writes moddingfromamod:wish_item — that instruction is frozen — and
        // the swap happens here, at the one place every give line and every match predicate goes
        // through. If this ever stops being the only such place, the two will disagree and a
        // predicate will silently match nothing.
        assertEquals(VanillaCarrier.ITEM_ID, ItemBase.id(null));
        assertEquals(VanillaCarrier.ITEM_ID, ItemBase.id(ItemBase.WISH_ITEM));
    }

    @Test
    @DisplayName("a base item the child NAMED is never substituted")
    void aNamedBaseItemIsLeftAlone() {
        VanillaCarrier.force(true);
        // "make my diamond sword unbreakable" builds on a real item the child owns. Swapping that
        // for a horse armour would replace his sword, which is worse than anything the carrier
        // route is trying to fix.
        assertEquals("minecraft:golden_axe", ItemBase.id("minecraft:golden_axe"));
        assertEquals("minecraft:diamond_sword", ItemBase.id("minecraft:diamond_sword"));
    }

    @Test
    @DisplayName("only an item made from scratch wears our icons — a NAMED base never does")
    void onlyTheModItemWearsOurIcons() {
        // ⚠ THIS TEST USED TO ASSERT THE OPPOSITE FOR THE CARRIER ID, and its message called that
        // "the single most important line in the spike's issue list". It was wrong, and pinning it
        // is how the bug survived: MakeItemTool.itemArg receives the RAW base and resolves the id
        // itself, so our substitution arrives as null and never as the carrier id. The only caller
        // that can pass the carrier id is a child whose wish is BUILT ON horse armour — and his
        // horse armour must keep its own artwork.
        VanillaCarrier.force(true);
        assertTrue(ItemBase.wearsOurIcons(null), "an item made from scratch wears our icons");
        assertTrue(ItemBase.wearsOurIcons(ItemBase.WISH_ITEM));
        assertFalse(ItemBase.wearsOurIcons(VanillaCarrier.ITEM_ID),
                "a base the child NAMED keeps its own artwork, and horse armour is not special "
                        + "just because we also use it as a carrier — the substitution never "
                        + "reaches this method as the carrier id");
        assertFalse(ItemBase.wearsOurIcons("minecraft:golden_axe"),
                "a real item the child named has its own artwork; writing ours would report a "
                        + "colour he cannot see");
    }

    @Test
    @DisplayName("with the carrier off, it does not wear our icons — it is just horse armour")
    void withTheCarrierOffTheItemIsOrdinary() {
        VanillaCarrier.force(false);
        assertFalse(ItemBase.wearsOurIcons(VanillaCarrier.ITEM_ID),
                "with the route switched off a leather horse armour is an ordinary item, and a "
                        + "wish naming one as base_item must not have our icon written onto it");
    }

    @Test
    @DisplayName("a base item is still refused for the reasons it always was")
    void refusalsAreUnchangedByTheCarrier() {
        VanillaCarrier.force(true);
        assertTrue(ItemBase.refusals(null).isEmpty());
        assertFalse(ItemBase.refusals("NotAnId").isEmpty(),
                "the shape check must still run — the carrier changes which id we write, not what "
                        + "a child's own base item is allowed to be");
    }
}
