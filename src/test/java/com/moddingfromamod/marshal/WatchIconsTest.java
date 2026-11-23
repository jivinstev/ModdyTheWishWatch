package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.moddingfromamod.wish.OldBuiltInsFixture;
import com.moddingfromamod.wish.WishCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a child can tell apart at a glance — the thing a row of identical icons is not.
 *
 * <p>The keyword-scan cases run over the six retired panel buttons ({@link OldBuiltInsFixture}):
 * the buttons are deleted, but they remain a fair sample of a child's words for the scan,
 * which still draws every recipe that names no icon of its own.
 */
class WatchIconsTest {

    @Test
    @DisplayName("a row of typical wishes does not all get the same icon")
    void presetsAreDistinguishable() {
        Set<String> icons = new HashSet<>();
        for (WishCatalog.Preset p : OldBuiltInsFixture.PRESETS) {
            icons.add(WatchIcons.forWish(p.label(), p.wishText()));
        }
        // The bar is deliberately "most of them differ" rather than "all", because two presets can
        // honestly be the same kind of wish. What is being prevented is the state this replaced:
        // ONE icon for every preset, which is a menu a child cannot read.
        assertTrue(icons.size() >= Math.max(3, OldBuiltInsFixture.PRESETS.size() * 2 / 3),
                "only " + icons.size() + " distinct icons across " + OldBuiltInsFixture.PRESETS.size()
                        + " presets: " + icons);
    }

    @Test
    @DisplayName("every preset resolves to something, and nothing falls through to the fallback")
    void noPresetIsUnclaimed() {
        for (WishCatalog.Preset p : OldBuiltInsFixture.PRESETS) {
            assertNotEquals(WatchIcons.UNKNOWN, WatchIcons.forWish(p.label(), p.wishText()),
                    "no rule claims the shipped preset \"" + p.label() + "\"");
        }
    }

    @Test
    @DisplayName("a wish for chickens is about chickens, not about rain")
    void theMostSpecificWordWins() {
        // "make it rain chickens" contains BOTH "rain" and "chickens". Order is the only thing that
        // decides it, so this is the test that fails if the rule list is ever sorted.
        assertEquals("minecraft:chicken_spawn_egg",
                WatchIcons.forWish("Make it rain chickens!", "make it rain chickens"));
    }

    @Test
    @DisplayName("every kind of made thing has its own icon")
    void everyKindIsMapped() {
        List<String> kinds = List.of(MadeKind.CREATURE, MadeKind.MOB, MadeKind.BUILD,
                MadeKind.DATAPACK, MadeKind.ITEM, MadeKind.PAINTING, MadeKind.EVENT);
        for (String kind : kinds) {
            assertNotEquals(WatchIcons.UNKNOWN, WatchIcons.forKind(kind),
                    "kind '" + kind + "' has no icon of its own");
        }
        // A castle and a creature must not look alike; a creature and a mob may, and do.
        assertNotEquals(WatchIcons.forKind(MadeKind.BUILD), WatchIcons.forKind(MadeKind.CREATURE));
    }

    @Test
    @DisplayName("an unknown or missing kind degrades instead of throwing")
    void unknownKindIsSafe() {
        assertEquals(WatchIcons.UNKNOWN, WatchIcons.forKind(null));
        assertEquals(WatchIcons.UNKNOWN, WatchIcons.forKind("something-we-added-later"));
        // ...and one we took away: an old watch may still hold a Create kind.
        assertEquals(WatchIcons.UNKNOWN, WatchIcons.forKind("train"));
        assertEquals(WatchIcons.UNKNOWN, WatchIcons.forKind("contraption"));
        assertEquals(WatchIcons.UNKNOWN, WatchIcons.forWish(null, null));
    }

    @Test
    @DisplayName("the three controls are three different things, and none of them is a wish icon")
    void controlsAreTheirOwn() {
        Set<String> controls = new HashSet<>(
                List.of(WatchIcons.REJECT, WatchIcons.CLOSE, WatchIcons.COMPOSE));
        assertEquals(3, controls.size(), "two controls share an icon: " + controls);
        // A control that looked like a preset would be pressed as one.
        for (WishCatalog.Preset p : OldBuiltInsFixture.PRESETS) {
            assertTrue(!controls.contains(WatchIcons.forWish(p.label(), p.wishText())),
                    "the preset \"" + p.label() + "\" wears a CONTROL's icon");
        }
    }

    @Test
    @DisplayName("every icon is a namespaced id this repo could resolve")
    void idsAreWellFormed() {
        for (String id : WatchIcons.everyIcon()) {
            assertTrue(id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"), "malformed item id: " + id);
            assertTrue(id.startsWith("minecraft:"),
                    id + " is not a VANILLA item — the whole point is that it needs no pack");
        }
    }
    @Test
    @DisplayName("an explicit icon wins, and nonsense still falls back to the words")
    void explicitIconBeatsTheKeywordScan() {
        // BRANCH 1 — the template named one. This is the whole of the icon work: a curated
        // (or learned) recipe says what its button looks like and nothing guesses over it.
        assertEquals("minecraft:cake",
                WatchIcons.forWish("Sparkly Cheese Island", "sparkly cheese island",
                        "minecraft:cake"));
        // ...even when the words would have scanned to something else. "rain chickens" scans to a
        // chicken egg; an owner who chose a water bucket gets a water bucket.
        assertEquals("minecraft:water_bucket",
                WatchIcons.forWish("Rain Chickens", "make it rain chickens",
                        "minecraft:water_bucket"));

        // BRANCH 2 — no icon: the keyword scan, exactly as before.
        assertEquals("minecraft:chicken_spawn_egg",
                WatchIcons.forWish("Rain Chickens", "make it rain chickens", ""));
        assertEquals("minecraft:chicken_spawn_egg",
                WatchIcons.forWish("Rain Chickens", "make it rain chickens", null));
        assertEquals("minecraft:chicken_spawn_egg",
                WatchIcons.forWish("Rain Chickens", "make it rain chickens", "   "));
        // and a recipe id that matches no rule is still the shard — the gap named sprites exist to close
        assertEquals(WatchIcons.UNKNOWN,
                WatchIcons.forWish("Sparkly Cheese Island", "sparkly cheese island", ""));

        // The two-argument form is the scan, so every existing caller is unchanged.
        assertEquals(WatchIcons.forWish("Rain Chickens", "make it rain chickens"),
                WatchIcons.forWish("Rain Chickens", "make it rain chickens", ""));
    }

    @Test
    @DisplayName("a STORED amethyst is re-assessed, never shown as a choice")
    void aStoredAmethystIsReadAsUnset() {
        assertTrue(WatchIcons.isUnset(null));
        assertTrue(WatchIcons.isUnset(" "));
        assertTrue(WatchIcons.isUnset(WatchIcons.UNKNOWN));
        assertTrue(!WatchIcons.isUnset("minecraft:cake"), "a real choice is kept");
        // A learned button saved with the old fallback falls through to its words, as a blank would.
        assertEquals(WatchIcons.forWish("My Dragon", "make me a pet dragon", ""),
                WatchIcons.forWish("My Dragon", "make me a pet dragon", WatchIcons.UNKNOWN));
        assertNotEquals(WatchIcons.UNKNOWN,
                WatchIcons.forWish("My Dragon", "make me a pet dragon", WatchIcons.UNKNOWN));
        assertEquals("minecraft:cake", WatchIcons.forWish("x", "y", "minecraft:cake"));
    }
}
