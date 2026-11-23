package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.datapack.VanillaCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>An icon is a sprite with a NAME, and the number is derived.</b>
 *
 * <p>Two questions, and they fail differently. <i>Is the table well formed</i> is answered at
 * class-init by {@link IconSprites} itself, because a duplicate name or a reissued number is not a
 * thing that should reach a caller. <i>Does it still say what shipped</i> is answered here, against
 * the numbers a stored wish already carries.
 */
class IconSpritesTest {

    @Test
    @DisplayName("the library derives exactly the numbers that shipped")
    void derivedNumbersMatchTheContract() {
        List<IconSprites.Sprite> lib = IconSprites.library();
        int drawn = IconSprites.drawn().size();
        assertEquals(27 + drawn, lib.size(), "the 27 originals plus the #1052 drawn library");
        for (int i = 0; i < 27; i++) {
            assertEquals(IconSprites.FIRST + i, lib.get(i).customModelData(),
                    "the library's numbers are its POSITIONS; " + lib.get(i).name()
                            + " does not sit where it is numbered");
        }
        // Spot the two ends and the seam added, because these are the ones a stored wish
        // actually carries and an off-by-one here is a repoint nobody would notice.
        assertEquals(1, IconSprites.numberFor("machine"));
        assertEquals(9, IconSprites.numberFor("orb"));
        assertEquals(24, IconSprites.numberFor("key"));
        assertEquals(27, IconSprites.numberFor("snowball"));
    }

    @Test
    @DisplayName("WishIcons still answers exactly what it used to, derived rather than typed")
    void wishIconsIsUnchangedByTheDerivation() {
        assertEquals(27 + IconSprites.drawn().size(), WishIcons.all().size());
        assertEquals(1, WishIcons.byName("machine").id());
        assertEquals(17, WishIcons.byName("SWORD").id());
        assertEquals(10, WishIcons.byName("  wand ").id());
        assertNull(WishIcons.byName("teleporter"));
        for (WishIcons.Icon icon : WishIcons.all()) {
            assertEquals(icon.id(), IconSprites.numberFor(icon.name()),
                    "WishIcons and IconSprites disagree about " + icon.name()
                            + ", which is two sources of truth for the same contract");
        }
    }

    @Test
    @DisplayName("the pack's bindings are the library plus Moddy, ascending, with no collision")
    void bindingsAreTheWholePackAndAreOrdered() {
        List<IconSprites.Sprite> all = IconSprites.bindings();
        assertEquals(IconSprites.library().size() + 5, all.size(),
                "bindings() is the icon library plus the watch, the wish anvil's wand (#764), "
                        + "the Wish Bag, the menus' back arrow (#2231) and Moddy's idea bulb (#2445) "
                        + "— nothing else rides this table");

        Set<Integer> numbers = new HashSet<>();
        Set<String> names = new HashSet<>();
        int previous = 0;
        for (IconSprites.Sprite s : all) {
            assertTrue(s.customModelData() > previous,
                    "bindings() must be strictly ascending: the 1.21.1 override table matches on "
                            + ">= and is scanned so the LAST match wins, so an unsorted list does "
                            + "not error — it hands back some other wish's icon");
            previous = s.customModelData();
            assertTrue(numbers.add(s.customModelData()), "two sprites share " + s.customModelData());
            assertTrue(names.add(s.name()), "two sprites named " + s.name());
        }

        // The watch is in the table (that is the asset rule: everything is a NAMED sprite) and
        // it is NOT in the library (the library is what a wish may pick, and Moddy is not).
        IconSprites.Sprite watch = IconSprites.byName(VanillaCarrier.WATCH_ICON);
        assertNotNull(watch, "Moddy is not in the sprite table, so nothing names his artwork");
        assertEquals(IconSprites.Band.WATCH, watch.band());
        assertEquals(VanillaCarrier.WATCH_MODEL_DATA, watch.customModelData());
        IconSprites.Sprite wand = IconSprites.byName(VanillaCarrier.WISH_WAND_ICON);
        assertNotNull(wand, "the wish anvil's wand is not in the sprite table (#764)");
        assertEquals(IconSprites.Band.ANVIL, wand.band());
        assertEquals(VanillaCarrier.WISH_WAND_MODEL_DATA, wand.customModelData());
        IconSprites.Sprite bag = IconSprites.byName(VanillaCarrier.WISH_BAG_ICON);
        assertNotNull(bag, "the Wish Bag is not in the sprite table, so a stock client draws a horse armour ");
        assertEquals(IconSprites.Band.BAG, bag.band());
        assertEquals(VanillaCarrier.WISH_BAG_MODEL_DATA, bag.customModelData());
        for (IconSprites.Sprite s : IconSprites.library()) {
            assertTrue(!s.name().equals(VanillaCarrier.WISH_BAG_ICON),
                    "the Wish Bag is in the WISH library, so a wished thing could look like the bag");
            assertTrue(!s.name().equals(VanillaCarrier.WISH_WAND_ICON),
                    "the anvil's button is in the WISH library, so a wish could pick it");
            assertTrue(!s.name().equals(VanillaCarrier.WATCH_ICON),
                    "the watch is in the WISH library, so a wished thing could come back looking "
                            + "like the thing you wish with");
        }
    }

    @Test
    @DisplayName("the whole table sits below the atom rig's reserved band")
    void nothingHereWalksIntoTheAtomBand() {
        for (IconSprites.Sprite s : IconSprites.bindings()) {
            assertTrue(s.customModelData() < VanillaCarrier.ATOM_MODEL_DATA_FIRST,
                    s.name() + " is at " + s.customModelData() + ", inside the atom rig's band "
                            + "starting at " + VanillaCarrier.ATOM_MODEL_DATA_FIRST
                            + " — a creature would silently start rendering as a wish icon");
        }
    }

    @Test
    @DisplayName("an unknown name is refused, never quietly turned into icon 1")
    void anUnknownNameIsRefusedRatherThanDefaulted() {
        assertNull(IconSprites.byName("teleporter"));
        assertNull(IconSprites.byName(null));
        assertNotNull(IconSprites.byName("  SWORD "), "lookup is trimmed and case-insensitive");
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> IconSprites.numberFor("xylophone"));
        assertTrue(e.getMessage().contains("xylophone"), "the refusal must name what was asked for");
    }

    @Test
    @DisplayName("a sprite with no name, or bound at 0, is refused by the record itself")
    void aSpriteMustHaveANameAndAUsableNumber() {
        assertThrows(IllegalArgumentException.class,
                () -> new IconSprites.Sprite("", "wish/x", 3, IconSprites.Band.WISH),
                "a sprite with no name is a number again, which is the whole thing named sprites remove");
        assertThrows(IllegalArgumentException.class,
                () -> new IconSprites.Sprite("x", "wish/x", 0, IconSprites.Band.WISH),
                "0 is what a stack with NO custom_model_data reports, so a binding at 0 claims "
                        + "every plain leather horse armour already in a child's world");
    }

    /**
     * The three table rules, driven through the same {@code build} the real table walks.
     *
     * <p>They live in a static initialiser, so this is why {@code build} takes its tables as
     * arguments: a test that could only assert "the real table is fine" is a test that never sees
     * the guard fire, and a guard nobody has watched fail is a comment.
     */
    @Test
    @DisplayName("a sprite named twice, a reissued number and a deleted name each throw")
    void theTableRulesEachFire() {
        Map<String, Integer> frozen = Map.of("cup", 1, "cone", 2);

        // Sanity: the fixture itself is a table that BUILDS, or the three failures below prove
        // nothing — each could be failing for the fixture's own reasons.
        assertEquals(2, IconSprites.build(List.of("cup", "cone"), List.of(), frozen).size());

        IllegalStateException twice = assertThrows(IllegalStateException.class,
                () -> IconSprites.build(List.of("cup", "cone", "cup"), List.of(), frozen));
        assertTrue(twice.getMessage().contains("named twice"), twice.getMessage());

        IllegalStateException moved = assertThrows(IllegalStateException.class,
                () -> IconSprites.build(List.of("cone", "cup"), List.of(), frozen),
                "swapping two names repoints BOTH, which is the exact shape of a child's cup "
                        + "turning into a cone overnight");
        assertTrue(moved.getMessage().contains("derives to"), moved.getMessage());

        // Deleting the LAST name shifts nothing, so the position check cannot see it at all — this
        // is the case the frozen table exists for, and dropping `cone` here is the only one of the
        // three that reaches the second loop.
        IllegalStateException gone = assertThrows(IllegalStateException.class,
                () -> IconSprites.build(List.of("cup"), List.of(), frozen),
                "deleting a shipped name stops the pack binding a number a stored wish still "
                        + "carries — the wish then draws the plain carrier, silently");
        assertTrue(gone.getMessage().contains("no longer in the library"), gone.getMessage());

        // RETIREMENT: slot 1 is retired, so it may only be held by a `retired_` name...
        IllegalStateException reissued = assertThrows(IllegalStateException.class,
                () -> IconSprites.build(List.of("cup", "cone"), List.of(1), Map.of("cone", 2)),
                "custom_model_data 1 was issued once; a thing already standing in a child's world "
                        + "carries it, and handing it to new artwork repaints that thing");
        assertTrue(reissued.getMessage().contains("RETIRED"), reissued.getMessage());

        // ...and a held slot binds nothing while every later number stays exactly where it was.
        List<IconSprites.Sprite> held = IconSprites.build(
                List.of(IconSprites.RETIRED_PREFIX + "1", "cone"), List.of(1), Map.of("cone", 2));
        assertEquals(1, held.size(), "a retired slot must bind no artwork at all");
        assertEquals("cone", held.get(0).name());
        assertEquals(2, held.get(0).customModelData(),
                "the point of holding the slot is that the numbers after it do not move");
    }

    @Test
    @DisplayName("no retired slot ever reaches the pack")
    void aRetiredSlotIsServedToNobody() {
        for (IconSprites.Sprite s : IconSprites.bindings()) {
            assertTrue(!s.name().startsWith(IconSprites.RETIRED_PREFIX),
                    "a retired slot reached the pack as " + s.name() + " — it must hold its number "
                            + "and bind nothing, or the number is being reissued");
        }
    }
}
