package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ── TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
// Pure tests for the pack. If the route is abandoned this whole file goes; if it ships, the
// MOD ITEM becomes the legacy path instead. Gated.

/**
 * The pack's shape, checked without a game.
 *
 * <p>Every rule here fails SILENTLY in a real client — a wrong entry order renders another
 * wish's icon, a missing fallback renders nothing, a mod-namespaced texture id makes the spike's
 * control unreadable. None of them throw, so none of them can be found by running it.
 */
class VanillaItemPackTest {

    private static VanillaItemPack.Spec spec(VanillaItemPack.Icon... icons) {
        return new VanillaItemPack.Spec("leather_horse_armor", List.of(icons));
    }

    private static VanillaItemPack.Icon icon(int id, String name) {
        return new VanillaItemPack.Icon(id, name,
                List.of("wish/" + name, "wish/" + name + "_detail"));
    }

    @Test
    @DisplayName("range_dispatch entries come out ascending, whatever order they went in")
    void entriesAreSortedAscending() {
        // THE rule this class exists for. RangeSelectItemModel binary-searches its thresholds at
        // 16 entries or more, so feed it 27, 3, 14 unsorted and a stack asking for 3 can get 27's
        // artwork: a real model, correctly rendered, of the wrong thing.
        String json = VanillaItemPack.itemDefinition(spec(icon(27, "snowball"), icon(3, "cup"),
                icon(14, "lamp")));
        int cup = json.indexOf("\"threshold\": 3,");
        int lamp = json.indexOf("\"threshold\": 14,");
        int snow = json.indexOf("\"threshold\": 27,");
        assertTrue(cup > 0 && lamp > 0 && snow > 0, "all three entries present:\n" + json);
        assertTrue(cup < lamp && lamp < snow,
                "entries must be ascending or a wish wears another wish's icon:\n" + json);
    }

    @Test
    @DisplayName("an unwished carrier falls back to vanilla's own model, so it still renders")
    void theDefinitionFallsBackToTheCarriersOwnModel() {
        String json = VanillaItemPack.itemDefinition(spec(icon(1, "machine")));
        assertTrue(json.contains("\"fallback\": { \"type\": \"minecraft:model\", \"model\": "
                        + "\"minecraft:item/leather_horse_armor\""),
                "a pack definition REPLACES the vanilla one; without a fallback every ordinary "
                        + "leather horse armour in the world renders as nothing:\n" + json);
    }

    @Test
    @DisplayName("nothing in the pack names the mod — this is the spike's control")
    void nothingIsNamespacedToTheMod() {
        Map<String, String> files = VanillaItemPack.textFiles(
                spec(icon(1, "machine"), icon(3, "cup")));
        for (Map.Entry<String, String> e : files.entrySet()) {
            assertFalse(e.getValue().contains("moddingfromamod"),
                    e.getKey() + " names the mod, which makes 'is arm B genuinely vanilla?' "
                            + "unanswerable from the resolved model:\n" + e.getValue());
            assertFalse(e.getKey().contains("moddingfromamod"), "path names the mod: " + e.getKey());
        }
    }

    @Test
    @DisplayName("layer 0 is the body and layer 1 the outline, in that order")
    void layersAreEmittedInIndexOrder() {
        // Not cosmetic. Vanilla's leather handler tints layer 0 and refuses every other layer, and
        // the icon library is drawn greyscale-body-on-0 to match. Swap them and the outline gets
        // the colour while the body stays grey — which still renders, and still looks like art.
        String json = VanillaItemPack.iconModel(
                new VanillaItemPack.Icon(4, "cone", List.of("wish/cone", "wish/cone_detail")));
        int zero = json.indexOf("\"layer0\"");
        int one = json.indexOf("\"layer1\"");
        assertTrue(zero > 0 && one > zero, json);
        assertTrue(json.contains("\"layer0\": \"wishpack:item/wish/cone\""), json);
        assertTrue(json.contains("\"layer1\": \"wishpack:item/wish/cone_detail\""), json);
    }

    @Test
    @DisplayName("a single-layer icon is allowed — not every sprite has a detail pass")
    void oneLayerIsFine() {
        String json = VanillaItemPack.iconModel(
                new VanillaItemPack.Icon(9, "orb", List.of("wish/orb")));
        assertTrue(json.contains("\"layer0\""), json);
        assertFalse(json.contains("\"layer1\""), json);
    }

    @Test
    @DisplayName("layer10 sorts after layer2, not between layer1 and layer2")
    void layersSortNumericallyNotAlphabetically() {
        List<String> sprites = new java.util.ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            sprites.add("wish/s" + i);
        }
        String json = VanillaItemPack.layersModel(sprites);
        assertTrue(json.indexOf("\"layer0\"") < json.indexOf("\"layer2\""), json);
        assertTrue(json.indexOf("\"layer2\"") < json.indexOf("\"layer10\""), json);
    }

    @Test
    @DisplayName("custom_model_data 0 is refused — it would claim every plain base item")
    void zeroIsRefused() {
        // A stack with NO custom_model_data reports 0.0 from the property function, and the match
        // is threshold `<=` value. An entry at 0 therefore fires for every leather horse armour.
        assertThrows(IllegalArgumentException.class,
                () -> new VanillaItemPack.Icon(0, "machine", List.of("machine")));
        assertThrows(IllegalArgumentException.class,
                () -> new VanillaItemPack.Icon(-2, "machine", List.of("machine")));
    }

    @Test
    @DisplayName("two icons cannot share a number, and the second is the one that vanishes")
    void duplicateNumbersAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> VanillaItemPack.itemDefinition(spec(icon(5, "bottle"), icon(5, "bucket"))));
    }

    @Test
    @DisplayName("a spec with no carrier is refused, loudly")
    void aSpecWithNoCarrierIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new VanillaItemPack.Spec(" ", List.of(icon(1, "machine"))));
    }

    @Test
    @DisplayName("the pack is complete: mcmeta, the carrier's definition, and one model per icon")
    void everyFileIsThere() {
        List<VanillaItemPack.Icon> icons =
                List.of(icon(1, "machine"), icon(2, "block"), icon(3, "cup"));
        Map<String, String> files = VanillaItemPack.textFiles(
                new VanillaItemPack.Spec("leather_horse_armor", icons));
        // mcmeta + the carrier's item definition + one model per icon
        // + one NATIVE definition per icon (what a new stack's item_model names).
        // The count went 5 → 6 when the union landed, 6 → 9 with item models, and down by one when
        // step 3 dropped the ≤1.21.3 override table; this assertion is what said so, which
        // is the point of counting at all rather than only checking membership.
        // …+ one model per overlay in the library (shipped once, whatever the icons are).
        int overlays = com.moddingfromamod.item.IconOverlays.NAMES.size();
        int backing = VanillaItemPack.BACKING.size();   // The sparkle backing's layers
        assertEquals(1 + 1 + 2 * icons.size() + overlays + backing, files.size(), files.keySet().toString());
        for (String o : com.moddingfromamod.item.IconOverlays.NAMES) {
            assertTrue(files.containsKey("assets/wishpack/models/item/overlay/" + o + ".json"), o);
        }
        assertTrue(files.containsKey("pack.mcmeta"));
        assertFalse(files.containsKey("assets/minecraft/models/item/leather_horse_armor.json"),
                "#1585 step 3: the pack no longer replaces the carrier's MODEL — that file was the "
                        + "≤1.21.3 override table, and no client older than 1.21.4 is admitted");
        assertTrue(files.containsKey("assets/minecraft/items/leather_horse_armor.json"),
                "the carrier's item definition is missing, so every wished thing draws as a horse "
                        + "armour — the failure docs/PACK_DELIVERY.md §16 exists for, and nothing "
                        + "errors when it happens");
        for (VanillaItemPack.Icon i : icons) {
            assertTrue(files.containsKey("assets/wishpack/models/item/wish/" + i.name() + ".json"),
                    files.keySet().toString());
            assertTrue(files.containsKey(NativeItemModels.definitionPath(i.name())),
                    files.keySet().toString());
        }
        assertTrue(files.get("pack.mcmeta").contains("\"pack_format\": 34"),
                "1.21.1 is pack format 34, and it STAYS 34: a 34-declared pack is "
                        + "applied by a 26.2 client and its items/*.json are read (measured). "
                        + "What the other three format keys buy is the absence of an "
                        + "'incompatible pack' warning: " + files.get("pack.mcmeta"));
    }

    @Test
    @DisplayName("every text file is valid JSON")
    void everyFileParses() {
        Map<String, String> files = VanillaItemPack.textFiles(
                spec(icon(1, "machine"), icon(14, "lamp"), icon(27, "snowball")));
        for (Map.Entry<String, String> e : files.entrySet()) {
            assertTrue(balanced(e.getValue()),
                    e.getKey() + " is not well-formed:\n" + e.getValue());
            assertFalse(e.getValue().contains(",\n  ]"),
                    e.getKey() + " has a trailing comma, which makes the whole pack silently "
                            + "fail to load:\n" + e.getValue());
        }
    }

    /** Braces/brackets balanced and quotes even — enough to catch the ways a builder goes wrong. */
    private static boolean balanced(String s) {
        int braces = 0;
        int brackets = 0;
        int quotes = 0;
        for (char c : s.toCharArray()) {
            switch (c) {
                case '{' -> braces++;
                case '}' -> braces--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                case '"' -> quotes++;
                default -> { }
            }
            if (braces < 0 || brackets < 0) {
                return false;
            }
        }
        return braces == 0 && brackets == 0 && quotes % 2 == 0;
    }

    @Test
    @DisplayName("a sprite outside item/wish can be carried — Moddy's own artwork is one")
    void aSpriteOutsideTheWishFolderIsCarried() {
        // The folder used to be hardwired into texturePath, which read as a simplification and was
        // a ceiling: the mod's own watch sprite is textures/item/wish_watch.png, so the carrier
        // could dress a wished thing and could not dress the item a child holds to wish WITH.
        String json = VanillaItemPack.iconModel(
                new VanillaItemPack.Icon(1000, "wish_watch", List.of("wish_watch")));
        assertTrue(json.contains("\"layer0\": \"wishpack:item/wish_watch\""), json);
        assertEquals("assets/wishpack/textures/item/wish_watch.png",
                VanillaItemPack.texturePath("wish_watch"));
        assertEquals("assets/wishpack/textures/item/wish/cone.png",
                VanillaItemPack.texturePath("wish/cone"));
    }
}
