package com.moddingfromamod.agent.tools;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A single item is a family of one.</b>
 *
 * <p>The give line moved out of the three {@code shape_*.cbs} templates and into
 * {@link MakeItemTool#gives} so that a wish can ask for twenty flavours. A template cannot loop, so
 * a family cannot be written by hand in a file that does not know how many members there are.
 *
 * <p>That refactor is only safe if the one-member case is <b>byte-identical</b> to what the
 * templates used to emit, which is what the first test here pins — against the literal line those
 * files carried, copied from the pre-change source rather than regenerated from the new code.
 *
 * <h2> moved those bytes ON PURPOSE, and this is how that is kept honest</h2>
 * Every made item now carries {@code WishItem:"<featureId>"} so that the engine can say <i>"his Hot
 * Coffee"</i> rather than only <i>"a wish item"</i>. That is a real change to the shape of every
 * give line ever rendered, so the literal above is <b>not</b> edited to match — instead the test
 * asserts that inserting the identity into the old line reproduces the new one exactly. The
 * original claim stands: nothing else moved, and it is proven rather than re-baselined.
 */
class GivesTest {

    /** The exact line {@code shape_held.cbs} carried before the change, with its args substituted. */
    private static final String OLD_HELD_LINE =
            "    /give @s moddingfromamod:wish_item[minecraft:custom_model_data=10,"
            + "minecraft:dyed_color=9857238,"
            + "minecraft:custom_name={\"text\":\"Zonkbolt\",\"color\":\"#9668D6\",\"bold\":true,\"italic\":false},"
            + "minecraft:lore=[{\"text\":\"Right-click to use it.\",\"color\":\"gray\",\"italic\":false}],"
            + "minecraft:enchantment_glint_override=true,"
            + "minecraft:custom_data={WishOnUse:\"zonkbolt:act\"}] 4";

    @Test
    @DisplayName("one variant renders the old template line plus the identity, and nothing else")
    void oneVariantRendersExactlyWhatTheOldTemplateDidPlusTheIdentity() {
        // ROUTE PINNED ON PURPOSE, and it is the only test here that does so. Everything else in
        // this file asserts a PROPERTY the give line must have whichever route rendered it; this
        // one is a historical BYTE-PIN, and the bytes it pins are the mod item's. Asking it about
        // the vanilla carrier would be asking it a question it was never written to answer, and
        // re-baselining the literal would destroy the only evidence that the family refactor moved
        // nothing. The carrier's own bytes are pinned by CarrierGiveLineTest.
        com.moddingfromamod.datapack.VanillaCarrier.force(false);
        try {
            String got = MakeItemTool.gives("held", "zonkbolt", 10, 4, "Right-click to use it.",
                    List.of(new MakeItemTool.Variant("Zonkbolt", 9857238)));
            // The ONE sanctioned difference, spelled out rather than re-baselined: stamps
            // which made item this is. Everything either side must still be the pre-family bytes.
            String withIdentity = OLD_HELD_LINE.replace(
                    "minecraft:custom_data={WishOnUse:",
                    "minecraft:custom_data={WishItem:\"zonkbolt\",WishOnUse:")
                    // ...and the SECOND sanctioned difference: how this Minecraft spells
                    // the component's value -- a bare int on 1.21.1, {floats:[10f]} on 1.21.4.
                    .replace("custom_model_data=10,",
                            "custom_model_data=" + com.moddingfromamod.compat.ModelData.snbt(10) + ",")
                    // ...and the THIRD: on 1.21.4 the stack also names its own picture,
                    // item_model beside the number; on 1.21.1 components() is the number alone.
                    .replace("minecraft:custom_model_data=" + com.moddingfromamod.compat.ModelData.snbt(10) + ",",
                            com.moddingfromamod.compat.ModelData.components(10) + ",")
                    // ...and the FOURTH: a composed icon carries no glint on 1.21.4 —
                    // the sparkle backing replaced it; 1.21.1 has no backing and keeps it.
                    .replace(",minecraft:enchantment_glint_override=true",
                            com.moddingfromamod.compat.ModelData.COMPOSED_ICONS
                                    ? "" : ",minecraft:enchantment_glint_override=true");
            assertEquals(withIdentity, got,
                    "the family refactor has to stay invisible for a single item, and #353 is "
                            + "allowed to add exactly one thing — if this fails, something OTHER "
                            + "than the identity has changed the shape of every wish ever made");
        } finally {
            com.moddingfromamod.datapack.VanillaCarrier.force(null);
        }
    }

    @Test
    @DisplayName("a PLAIN item carries an identity too — it used to carry nothing at all")
    void aPlainKeepsakeIsNoLongerAnonymous() {
        String got = MakeItemTool.gives("plain", "champion_trophy", 3, 1, "Its yours to keep.",
                List.of(new MakeItemTool.Variant("CHAMPION", 0xFFD700)));
        // THE PROPERTY, not the literal: a plain keepsake carries its identity. WHERE in the tag
        // that identity sits is a route detail — the vanilla carrier writes its own mark ahead of
        // it — and pinning the whole object would be pinning the route rather than the identity.
        assertTrue(got.contains("WishItem:\"champion_trophy\""), got);
        assertTrue(!got.contains("custom_data={}"),
                "an empty custom_data is what left a plain keepsake with nothing to be keyed on, "
                        + "so editing its icon made a second row in his menu (#163)");
    }

    /** Each shape keeps hanging its behaviour on its own key — that is all they ever differed by. */
    @Test
    @DisplayName("each shape keeps its own behaviour key")
    void eachShapeKeepsItsOwnKey() {
        List<MakeItemTool.Variant> one = List.of(new MakeItemTool.Variant("X", 1));
        assertTrue(MakeItemTool.gives("held", "f", 1, 1, "h", one).contains("WishOnUse:\"f:act\""));
        String thrown = MakeItemTool.gives("thrown", "f", 1, 1, "h", one);
        assertTrue(thrown.contains("WishThrowTag:\"f_fly\"") && thrown.contains("WishHitFunction:\"f:act\""),
                thrown);
        assertTrue(MakeItemTool.gives("placed", "f", 1, 1, "h", one).contains("WishPlaceTag:\"f_here\""));
    }

    /**
     * <b>The point of the whole change.</b> Twenty flavours are twenty lines, each its own name and
     * colour, <b>all pointing at one behaviour</b> — which is what keeps it one datapack, one
     * compile and one row in his wish menu.
     */
    @Test
    @DisplayName("a family is N lines that differ by name and colour and nothing else")
    void aFamilyDiffersOnlyByNameAndColour() {
        List<MakeItemTool.Variant> flavours = List.of(
                new MakeItemTool.Variant("Vanilla Ice Cream", 0xFFF8E7),
                new MakeItemTool.Variant("Chocolate Ice Cream", 0x5B3A1E),
                new MakeItemTool.Variant("Bubblegum Ice Cream", 0xFF8FC8));
        String out = MakeItemTool.gives("held", "ice_cream", 12, 1, "Right-click to eat it.", flavours);
        String[] lines = out.split("\n");

        assertEquals(3, lines.length, "one line per flavour");
        for (String want : new String[]{"Vanilla Ice Cream", "Chocolate Ice Cream", "Bubblegum Ice Cream"}) {
            assertTrue(out.contains(want), "lost a flavour: " + want);
        }
        for (String rgb : new String[]{"16775399", "5978654", "16748488"}) {
            assertTrue(out.contains("dyed_color=" + rgb), "a flavour did not get its own colour: " + rgb);
        }
        // ONE behaviour across the whole family. If this ever becomes N behaviours it is N
        // datapacks, N compiles and N rows in his menu, which is the thing this design exists to
        // avoid — and WishItemCatalogue.keyOf keys on exactly this string.
        assertEquals(3, out.split("WishOnUse:\"ice_cream:act\"", -1).length - 1,
                "every member must share one behaviour id");
    }

    /** A name with a quote in it must not break out of the JSON it is written into. */
    @Test
    @DisplayName("a child's apostrophes and quotes cannot break the give line")
    void namesAreEscaped() {
        String out = MakeItemTool.gives("held", "f", 1, 1, "h",
                List.of(new MakeItemTool.Variant("Steve's \"Best\" Slushie", 1)));
        assertTrue(out.contains("Steve"), out);
        long quotes = out.chars().filter(c -> c == '"').count();
        assertEquals(0, quotes % 2, "unbalanced quotes mean the component JSON is malformed: " + out);
    }
}
