package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.agent.tools.MakeItemTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>One trophy, one row — the half that its own fix left open.</b>
 *
 * <p>{@code WishItemCatalogue.keyOf} was fixed for items with a behaviour: it keys on the function
 * id, which is stable for the item's life, so <i>"make the wand blue"</i> updates the row it
 * already had. Its own comment then named what it could not do:
 *
 * <blockquote>"a plain named keepsake. Nothing stable to key on, so this is the old identity, and
 * an icon change on one of these still makes a second row."</blockquote>
 *
 * <p>A {@code plain} item ran no function, so it rendered {@code custom_data={}} — nothing at all —
 * and its identity fell back to name + icon + that empty tag. Change the icon and it is a different
 * key: <i>"make my trophy gold instead of silver"</i> left him one trophy and two rows in his menu,
 * the older one no longer matching the thing in his hand.
 *
 * <p>an earlier issue's identity component is what closes it, and this test works in RENDERED text rather than
 * on a live {@code ItemStack} deliberately: {@code keyOf} needs a real registry, and the question
 * here is whether the give line carries something stable to key on at all.
 */
class PlainKeepsakeIdentityTest {

    private static String customData(String rendered) {
        int at = rendered.indexOf("minecraft:custom_data=");
        return at < 0 ? "" : rendered.substring(at, rendered.indexOf("]", at));
    }

    /** The same trophy, before and after "make it gold" — one wish, two icons, two colours. */
    private static String trophy(int icon, int rgb, String name) {
        return MakeItemTool.gives("plain", "champion_trophy", icon, 1, "Its yours to keep.",
                java.util.List.of(new MakeItemTool.Variant(name, rgb)));
    }

    @Test
    @DisplayName("editing a plain keepsake's icon and colour leaves its identity unchanged")
    void anIconChangeIsNotANewThing() {
        String silver = trophy(3, 0xC0C0C0, "CHAMPION");
        String gold = trophy(7, 0xFFD700, "CHAMPION");

        assertNotEquals(silver, gold, "the two really are different items to look at, or this "
                + "test would pass by measuring nothing");
        assertEquals(customData(silver), customData(gold),
                "the icon and the colour changed and the IDENTITY must not — otherwise he ends up "
                        + "with one trophy and two rows in his wish menu (#163)");
        // AND IT IS NOT EMPTY, which is the actual regression was: a plain keepsake used to
        // render custom_data={} and had nothing to key on. Asserted as a property rather than as
        // the whole object, because the vanilla carrier writes its own mark into the same tag —
        // a route detail WishItemCatalogue.keyOf strips before keying, and one this test is not
        // about. What must hold on every route is that the feature id is in there.
        assertTrue(customData(gold).contains("WishItem:\"champion_trophy\""), customData(gold));
        assertNotEquals("minecraft:custom_data={}", customData(gold));
    }

    @Test
    @DisplayName("two different plain keepsakes are still told apart")
    void twoThingsAreStillTwoThings() {
        assertNotEquals(customData(trophy(3, 0xC0C0C0, "CHAMPION")),
                customData(MakeItemTool.gives("plain", "lucky_rock", 3, 1, "Its yours to keep.",
                        java.util.List.of(new MakeItemTool.Variant("CHAMPION", 0xC0C0C0)))),
                "keying everything the same way is the opposite failure: two things sharing a row "
                        + "silently drops one from his menu");
    }
}
