package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 *, machines you fill: the rules a child meets, pinned without booting the game. The
 * GameTest ({@code MachineFillGameTest}) proves the rendered pack does what these strings say.
 */
class MachineFillTest {

    private static MachineFill.Ingredient ing(String id, String name) {
        return new MachineFill.Ingredient(id, name);
    }

    private static MachineFill.Spec spec(int max, boolean chest, MachineFill.Ingredient... extras) {
        return new MachineFill.Spec(List.of(ing("minecraft:ice", "Ice"), ing("minecraft:sugar", "Sugar")),
                List.of(extras), "minecraft:potion", "Slushie", null, max, chest);
    }

    @Test
    void theSizesAreTheSlushieRuleAndRoundDown() {
        // 1 small, 3 medium, 5 big for a machine that holds five; 2 and 4 round DOWN.
        var s = MachineFill.sizes(5);
        assertEquals(List.of(new MachineFill.Size("Small", 1, 2), new MachineFill.Size("Medium", 3, 4),
                new MachineFill.Size("Big", 5, 5)), s);
        assertEquals(List.of(new MachineFill.Size("", 1, 1)), MachineFill.sizes(1),
                "a machine that holds one has no sizes at all");
        for (int max = 1; max <= MachineFill.MAX_MAIN; max++) {
            var sizes = MachineFill.sizes(max);
            // EVERY load from 1 to max makes exactly one size — no gap, no overlap.
            for (int n = 1; n <= max; n++) {
                int hits = 0;
                for (var z : sizes) {
                    if (n >= z.lo() && n <= z.hi()) {
                        hits++;
                    }
                }
                assertEquals(1, hits, "max " + max + ", load " + n);
            }
        }
    }

    @Test
    void theNameSaysTheSizeTheThingAndTheExtras() {
        assertEquals("Big Slushie", MachineFill.nameFor("Big", "Slushie", List.of()));
        assertEquals("Slushie", MachineFill.nameFor("", "Slushie", List.of()));
        assertEquals("Small Slushie with Whipped Cream and Sprinkles",
                MachineFill.nameFor("Small", "Slushie", List.of("Whipped Cream", "Sprinkles")));
    }

    @Test
    void aMadeOutputIsRenamedNeverNamedTwice() {
        String made = "minecraft:potion[minecraft:custom_name='{\"text\":\"Slushie\",\"color\":\"#FF0000\"}']";
        String renamed = MachineFill.withName(made, "Slushie", "Big Slushie");
        assertEquals(1, renamed.split("custom_name", -1).length - 1, renamed);
        assertTrue(renamed.contains("\"text\":\"Big Slushie\""), renamed);
        assertTrue(renamed.contains("#FF0000"), "the made colour survives: " + renamed);
        assertEquals("minecraft:cookie[minecraft:custom_name={\"text\":\"Big Cookie\",\"italic\":false}]",
                MachineFill.withName("minecraft:cookie", null, "Big Cookie"));
        // a made output with VARIANTS is handed over under its first variant's name
        String variant = "minecraft:potion[minecraft:custom_name='{\"text\":\"Small Slushie\",\"color\":\"#81D4FA\"}',"
                + "minecraft:lore=['{\"text\":\"x\"}']]";
        String r2 = MachineFill.withName(variant, "Slushie", "Big Slushie");
        assertEquals(1, r2.split("custom_name", -1).length - 1, r2);
        assertTrue(r2.contains("\"text\":\"Big Slushie\",\"color\":\"#81D4FA\""), r2);
        String withComponents = MachineFill.withName("minecraft:potion[minecraft:potion_contents={}]", null, "X");
        assertTrue(withComponents.startsWith("minecraft:potion[minecraft:custom_name="), withComponents);
        assertTrue(withComponents.endsWith("minecraft:potion_contents={}]"), withComponents);
    }

    @Test
    void theSignRecipeFitsTwoLines() {
        var lines = MachineFill.signLines(spec(5, false));
        assertEquals(List.of("ice + sugar", "→ Slushie"), lines);
        var long_ = new MachineFill.Spec(List.of(ing("a", "Blue Raspberry Syrup"), ing("b", "Crushed Ice")),
                List.of(), "x", "Blue Raspberry Slushie", null, 3, false);
        for (String l : MachineFill.signLines(long_)) {
            assertTrue(l.length() <= MachineFill.SIGN_WIDTH, "too wide for a sign: '" + l + "'");
        }
    }

    @Test
    void everyRefusalNamesANextActionAndNoId() {
        var s = spec(5, true, ing("minecraft:snowball", "Snowball"));
        Map<String, String> p = MachineFill.render(s, "Slushie Machine");
        for (String key : List.of("EMPTY_LINE", "NEEDS", "STILL_NEEDS", "CHEST_POLICY")) {
            String v = p.get(key);
            assertNotNull(v, key);
            // the WORDS he reads: everything inside "text":"…"
            for (String said : v.split("\"text\":\"")) {
                String words = said.contains("\"") ? said.substring(0, said.indexOf('"')) : "";
                assertFalse(words.contains("minecraft:"), key + " names an id to a child: " + words);
            }
        }
        assertTrue(p.get("EMPTY_LINE").contains("Hold them and pull the handle"), p.get("EMPTY_LINE"));
        assertTrue(p.get("NEEDS").contains("pull the handle"), p.get("NEEDS"));
        assertTrue(p.get("CHEST_POLICY").contains("Put a chest next to me"), p.get("CHEST_POLICY"));
        assertTrue(p.get("CHEST_POLICY").contains("Take something out"), p.get("CHEST_POLICY"));
        // a hand machine has no chest policy at all
        assertEquals("", MachineFill.render(spec(5, false), "M").get("CHEST_POLICY"));
    }

    @Test
    void everySizeAndEveryExtraSetIsItsOwnNamedPour() {
        var s = spec(5, false, ing("minecraft:snowball", "Snowball"), ing("minecraft:cocoa_beans", "Cocoa"));
        Map<String, String> p = MachineFill.render(s, "M");
        // 3 sizes x 4 extra sets
        assertEquals(12, p.get("PICK").lines().count());
        assertTrue(p.get("OUT_FUNCS").contains("Big Slushie with Snowball and Cocoa"));
        assertTrue(p.get("OUT_FUNCS").contains("Small Slushie"));
        // the top size is open-ended, so a load past MAX still pours
        assertTrue(p.get("PICK").contains("mfam_fl0 matches 5.. "), p.get("PICK"));
    }

    @Test
    void theHelpSaysWhatGoesInHowItPoursAndWhereItGoes() {
        var lines = MachineFill.help(spec(5, true, ing("x", "Snowball")), "Slushie Machine");
        assertTrue(lines.get(0).contains("Ice and Sugar"), lines.get(0));
        assertTrue(lines.get(1).contains("bigger"), lines.get(1));
        assertTrue(lines.get(lines.size() - 1).contains("chest"), lines.toString());
        assertTrue(lines.size() <= 4, "a how-to a child reads is short: " + lines);
    }

    @Test
    void aBadSpecIsRefusedByName() {
        assertNull(spec(5, false).problem());
        assertNotNull(new MachineFill.Spec(List.of(), List.of(), "x", "X", null, 5, false).problem());
        assertNotNull(spec(0, false).problem());
        assertNotNull(spec(MachineFill.MAX_MAIN + 1, false).problem());
        var twice = new MachineFill.Spec(List.of(ing("minecraft:ice", "Ice")),
                List.of(ing("minecraft:ice", "Ice")), "x", "X", null, 3, false);
        assertTrue(twice.problem().contains("twice"), twice.problem());
        assertThrows(IllegalArgumentException.class, () -> MachineFill.render(spec(0, false), "M"));
    }

    @Test
    void aLoadStopsAtItsCap() {
        Map<String, String> p = MachineFill.render(spec(5, false), "M");
        assertTrue(p.get("LOAD_FUNCS").contains("mfam_fl0 matches ..4 store result"), p.get("LOAD_FUNCS"));
        assertTrue(p.get("LOAD_FUNCS").contains("mfam_fl1 matches ..0 store result"), p.get("LOAD_FUNCS"));
    }

    @Test
    void theAgentIsToldTheSizesAlreadyWork() {
        // Measured: an 8-ice machine WITH sizes was described to the child as having none.
        String d = MachineFill.describe(spec(8, false, ing("minecraft:snowball", "Snowball")));
        assertTrue(d.contains("Small (1-3 Ice)") && d.contains("Medium (4-7 Ice)")
                && d.contains("Big (8 or more Ice)"), d);
        assertTrue(d.contains("already work") && d.contains("Snowball") && d.contains("hands it"), d);
        assertFalse(MachineFill.describe(spec(1, true)).contains("SIZES"), "one size has no sizes");
    }

    // ── the starter kit (follow-up) ─────────────────────────────────────────────────────

    @Test
    void theKitIsExactlyOneBiggestPourOnAnyMachine() {
        for (int max : new int[]{1, 3, 5, 8}) {
            var s = spec(max, false);
            var kit = MachineFill.kit(s, List.of("minecraft:ice", "minecraft:sugar"));
            assertEquals(2, kit.size());
            assertEquals(max, kit.get(0).count(), "the main ingredient comes as a full load");
            assertEquals(1, kit.get(1).count(), "every other one comes as the one it needs");
            // and a full load IS the biggest size this machine pours
            var sizes = MachineFill.sizes(max);
            if (!sizes.isEmpty()) {
                var big = sizes.get(sizes.size() - 1);
                assertTrue(max >= big.lo(), max + " ice does not reach " + big.word());
            }
        }
    }

    @Test
    void extrasComeOnceButNeverPastFourKinds() {
        var three = new MachineFill.Spec(List.of(ing("a", "A"), ing("b", "B"), ing("c", "C")),
                List.of(ing("d", "D"), ing("e", "E")), "minecraft:bread", "Bread", null, 3, false);
        var kit = MachineFill.kit(three, List.of("minecraft:a", "minecraft:b", "minecraft:c",
                "minecraft:d", "minecraft:e"));
        assertEquals(MachineFill.KIT_KINDS, kit.size(), "" + kit);
        assertEquals("D", kit.get(3).name(), "required first, then extras in order");
        var one = spec(3, false, ing("minecraft:snowball", "Snowball"));
        assertEquals(3, MachineFill.kit(one, List.of("minecraft:ice", "minecraft:sugar",
                "minecraft:snowball")).size());
    }

    @Test
    void aRareIngredientIsTakenButNeverHandedOver() {
        var s = new MachineFill.Spec(List.of(ing("minecraft:coal", "Coal"), ing("minecraft:diamond", "Diamond")),
                List.of(), "minecraft:bread", "Bread", null, 3, false);
        List<String> gives = List.of("minecraft:coal", "minecraft:diamond");
        var kit = MachineFill.kit(s, gives);
        assertEquals(1, kit.size(), "" + kit);
        assertEquals("minecraft:coal", kit.get(0).give());
        String said = MachineFill.kitMessage(s, gives);
        assertTrue(said.contains("3 Coal") && said.contains("Find some Diamond yourself"), said);
        assertFalse(MachineFill.kitAllows("minecraft:netherite_ingot[custom_name='x']"));
        assertFalse(MachineFill.kitAllows("diamond"), "a bare id is still a diamond");
        assertTrue(MachineFill.kitAllows("minecraft:ice"));
    }

    @Test
    void theKitMessageNamesWhatHeGot() {
        var s = spec(3, false);
        assertEquals("Here's 3 Ice and Sugar so you can try it!",
                MachineFill.kitMessage(s, List.of("minecraft:ice", "minecraft:sugar")));
    }
}
