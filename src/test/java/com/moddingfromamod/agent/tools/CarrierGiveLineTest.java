package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.datapack.ItemBase;
import com.moddingfromamod.datapack.VanillaCarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ── TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
// The give line on both routes. If the route is abandoned this whole file goes; if it ships, the
// MOD ITEM becomes the legacy path instead. Gated.

/**
 * The actual {@code /give} argument a wish produces, with and without the carrier.
 *
 * <p>This is the end of the A1/A2 chain and the only place the two halves meet: the id comes from
 * {@link ItemBase#id} and the icon components are gated by {@link ItemBase#wearsOurIcons}. Testing
 * them separately would leave the join untested, and the join is where a two-state predicate
 * quietly drops the icon.
 */
class CarrierGiveLineTest {

    @AfterEach
    void restore() {
        VanillaCarrier.force(null);
    }

    private static String give(String baseItem) {
        return MakeItemTool.itemArg(new MakeItemTool.Variant("Hot Coffee", 0xC0FFEE),
                3, "Drink it!", null, baseItem, "WishItem:\"hot_coffee\"");
    }

    @Test
    @DisplayName("carrier ON: a vanilla id, wearing our icon and our colour")
    void theCarrierGiveLineIsCompletelyFormed() {
        VanillaCarrier.force(true);
        String arg = give(null);
        assertTrue(arg.startsWith(VanillaCarrier.ITEM_ID + "["),
                "the give line must name the vanilla carrier: " + arg);
        assertTrue(arg.contains("minecraft:custom_model_data=" + com.moddingfromamod.compat.ModelData.snbt(3)),
                "no custom_model_data means the pack's override never fires and the child gets a "
                        + "leather horse armour: " + arg);
        assertTrue(arg.contains("minecraft:dyed_color=12648430"),
                "no dyed_color means vanilla's leather handler falls back to its own brown — the "
                        + "one row of the icon A/B that fails: " + arg);
        assertTrue(arg.contains("minecraft:custom_name"),
                "the name is what makes it his thing rather than horse armour: " + arg);
        assertTrue(arg.contains("WishItem:"), "the identity tag must survive: " + arg);
    }

    @Test
    @DisplayName("carrier OFF: byte-for-byte the give line it has always been")
    void theShippedGiveLineIsUnchanged() {
        VanillaCarrier.force(false);
        String arg = give(null);
        assertTrue(arg.startsWith(ItemBase.WISH_ITEM + "["), arg);
        assertTrue(arg.contains("minecraft:custom_model_data=" + com.moddingfromamod.compat.ModelData.snbt(3)), arg);
        assertTrue(arg.contains("minecraft:dyed_color=12648430"), arg);
    }

    @Test
    @DisplayName("a real item the child named keeps its own artwork, carrier on or off")
    void aForeignBaseNeverGetsOurIcon() {
        for (boolean on : new boolean[] {true, false}) {
            VanillaCarrier.force(on);
            String arg = give("minecraft:golden_axe");
            assertTrue(arg.startsWith("minecraft:golden_axe["), arg);
            assertFalse(arg.contains("custom_model_data"),
                    "carrier=" + on + ": our icon means nothing on a golden axe and writing it "
                            + "would have the tool report a colour he cannot see: " + arg);
            assertFalse(arg.contains("dyed_color"), "carrier=" + on + ": " + arg);
        }
    }

    /**
     * <b>A wish BUILT ON horse armour must keep its armour — on either setting.</b>
     *
     * <p>{@code base_item} takes any full item id ("make my sword never wear out",
     * "a bow that shoots lightning"), so a child can perfectly well wish for something built on
     * {@code minecraft:leather_horse_armor}. The inert-attribute block exists to stop a wished
     * thing <i>standing in</i> on that item from reading "When on Body: +3 Armor" under a name like
     * Hot Coffee — it must never fire for horse armour the wish actually MEANT.
     *
     * <p>The first cut gated on "is the resolved id the carrier", which is true for both cases and
     * indistinguishable between them. So with the route OFF — the shipped configuration — a wish
     * for magic horse armour silently lost its armour and its tooltip. Narrow, real, and invisible:
     * the item is given, is named correctly, renders correctly, and simply does not protect the
     * horse.
     *
     * <p>The question the gate has to ask is "is this OUR item, possibly substituted", which is
     * {@link ItemBase#isWishItem} on the base the caller passed, not on the id that comes out.
     */
    @Test
    @DisplayName("a wish built ON horse armour keeps its armour, carrier on or off")
    void aDeliberateHorseArmourWishIsNotNeutralised() {
        for (Boolean route : new Boolean[] {null, Boolean.FALSE, Boolean.TRUE}) {
            VanillaCarrier.force(route);
            String arg = give(VanillaCarrier.ITEM_ID);
            assertFalse(arg.contains("carrier_inert"),
                    "route=" + route + ": a wish BUILT ON horse armour had its armour neutralised "
                            + "to 0 and its tooltip hidden — it is the child's horse armour, not "
                            + "our carrier standing in for the wish item: " + arg);
        }
    }

    /** …and the neutralising must still happen for the carrier proper, or B7 regresses. */
    @Test
    @DisplayName("the carrier standing in for the wish item IS still neutralised")
    void theSubstitutedCarrierIsStillNeutralised() {
        VanillaCarrier.force(true);
        String arg = give(null);
        assertTrue(arg.contains("carrier_inert"),
                "without the inert modifier the wished thing reads \"When on Body: +3 Armor\": "
                        + arg);
    }

    /**
     * <b>…and it must not wear OUR icon either.</b>
     *
     * <p>The sibling of the armour bug, in the half that draws the thing, and the same root: a
     * predicate that cannot tell "we substituted the carrier" from "the child asked for horse
     * armour". {@code ItemBase.wearsOurIcons} carried a clause
     * {@code || (VanillaCarrier.enabled() && isCarrierId(baseItem))}, and because {@code itemArg}
     * is handed the RAW base (it resolves the id itself, one line later), that clause can only ever
     * fire for a base the child named — never for our own substitution, which arrives as
     * {@code null} and is already caught by {@code isWishItem}.
     *
     * <p>So with the route on, "magic horse armour" was given our icon and our colour instead of
     * horse armour's own artwork. Nothing errored; it simply looked like a different item.
     */
    @Test
    @DisplayName("a wish built ON horse armour keeps its own artwork, carrier on or off")
    void aDeliberateHorseArmourWishDoesNotWearOurIcon() {
        for (Boolean route : new Boolean[] {null, Boolean.FALSE, Boolean.TRUE}) {
            VanillaCarrier.force(route);
            String arg = give(VanillaCarrier.ITEM_ID);
            assertFalse(arg.contains("custom_model_data"),
                    "route=" + route + ": a wish BUILT ON horse armour was given our icon override "
                            + "— it is his horse armour, not our carrier standing in: " + arg);
            assertFalse(arg.contains("dyed_color"),
                    "route=" + route + ": a wish BUILT ON horse armour was tinted with our colour: "
                            + arg);
        }
    }

    /** The substitution itself must still wear them, or A1 regresses the other way. */
    @Test
    @DisplayName("the carrier standing in for the wish item still wears our icon")
    void theSubstitutedCarrierStillWearsOurIcon() {
        for (String base : new String[] {null, ItemBase.WISH_ITEM}) {
            VanillaCarrier.force(true);
            String arg = give(base);
            assertTrue(arg.contains("minecraft:custom_model_data=" + com.moddingfromamod.compat.ModelData.snbt(3)),
                    "base=" + base + ": the substituted carrier lost our icon, so every wished "
                            + "thing renders as a plain leather horse armour: " + arg);
        }
    }
}
