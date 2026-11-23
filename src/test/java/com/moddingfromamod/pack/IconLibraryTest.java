package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.compat.ModelData;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.item.IconSprites;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The Muse-drawn icon library reaches every client it is meant to.</b>
 *
 * <p>Each rule here is a way the library could ship and look wrong while every other test passes:
 * an icon whose second colour never tints, a strawberry dyed the wish's colour, a number that
 * moved under a child's saved stack.
 */
@DisplayName("The drawn icon library (#1052)")
class IconLibraryTest {

    private static final Path JAR = Path.of("src/main/resources");
    private static final Path MODELS = JAR.resolve("assets/moddingfromamod/models/item");
    private static final Path TEXTURES = JAR.resolve("assets/moddingfromamod/textures/item");

    private static JsonObject json(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    private static Map<String, String> pack() {
        return VanillaItemPack.textFiles(new VanillaItemPack.Spec(
                WishPackWriter.BASE_ITEM,
                WishPackWriter.icons("on"), WishPackWriter.atoms("on")));
    }

    @Test
    @DisplayName("there is a library, numbered after the originals and below Moddy, every number once")
    void numbersAreAContract() {
        List<IconSprites.LibraryIcon> drawn = IconSprites.drawn();
        assertTrue(drawn.size() >= 100, "the drawn library is missing: " + drawn.size());
        Set<Integer> numbers = new HashSet<>();
        for (IconSprites.LibraryIcon icon : drawn) {
            assertTrue(icon.number() >= 28 && icon.number() < 1000, icon.name() + " at " + icon.number());
            assertTrue(numbers.add(icon.number()), "number " + icon.number() + " twice");
            assertEquals(icon.number(), IconSprites.numberFor(icon.name()));
            assertEquals(icon.number(), ItemCatalogue.icon(icon.name()));
        }
        // The originals did not move.
        assertEquals(1, ItemCatalogue.icon("machine"));
        assertEquals(24, ItemCatalogue.icon("key"));
    }

    @Test
    @DisplayName("every icon's layers are on disk, and a fixed icon's picture is never in the dyed layer")
    void layersAreWhereTheModelsSay() throws Exception {
        for (IconSprites.LibraryIcon icon : IconSprites.drawn()) {
            JsonObject tex = json(MODELS.resolve("wish/" + icon.name() + ".json")).getAsJsonObject("textures");
            for (String key : tex.keySet()) {
                String sprite = tex.get(key).getAsString().split(":item/")[1];
                assertTrue(Files.exists(TEXTURES.resolve(sprite + ".png")), icon.name() + " " + key + " -> " + sprite);
            }
            String layer0 = tex.get("layer0").getAsString();
            if (!icon.tints()) {
                assertTrue(layer0.endsWith("wish/_blank"), icon.name() + " is fixed but its layer0 is "
                        + layer0 + " — it would come out the wish's colour");
            }
            assertEquals(icon.accent(), tex.has("layer1") && tex.get("layer1").getAsString().endsWith("_accent"),
                    icon.name() + ": the table and the model disagree about a second colour");
            // the one-tint `_flat` twin was 1.21.1's picture and only an overrides table bound it.
            assertFalse(Files.exists(MODELS.resolve("wish/" + icon.name() + "_flat.json"))
                            || Files.exists(TEXTURES.resolve("wish/" + icon.name() + "_flat.png")),
                    icon.name() + " ships a 1.21.1 `_flat` twin again, which no admitted client reads");
        }
    }

    @Test
    @DisplayName("the jar and the pack both tint an accent icon's second colour from colors[0], and only its")
    void eachDialectGetsThePictureItCanDraw() throws Exception {
        IconSprites.LibraryIcon accented = null;
        IconSprites.LibraryIcon plain = null;
        for (IconSprites.LibraryIcon icon : IconSprites.drawn()) {
            if (icon.accent() && accented == null) {
                accented = icon;
            }
            if (!icon.accent() && icon.tints() && plain == null) {
                plain = icon;
            }
        }
        assertNotNull(accented);
        assertNotNull(plain);

        // The jar (a client that HAS the mod). Since it has no 1.21.1 table either.
        String native1 = Files.readString(JAR.resolve("assets/wishpack/items/icon/" + accented.name() + ".json"));
        assertTrue(native1.contains("\"type\": \"minecraft:custom_model_data\"")
                && native1.contains("wish/" + accented.name() + "\""), "jar 1.21.4 definition: " + native1);
        String native2 = Files.readString(JAR.resolve("assets/wishpack/items/icon/" + plain.name() + ".json"));
        assertFalse(native2.contains("\"type\": \"minecraft:custom_model_data\""),
                "a one-colour icon must not grow a second tint");

        // The pushed pack (a client that has nothing). Since step 3 it has no 1.21.1 table
        // and so no flat twin: no client older than 1.21.4 is admitted to read one.
        Map<String, String> pack = pack();
        assertFalse(pack.containsKey(VanillaItemPack.iconModelPath(accented.name() + "_flat")),
                "the pack carries a flat twin nothing binds any more");
        String def = pack.get(NativeItemModels.definitionPath(accented.name()));
        assertTrue(def.contains("\"type\": \"minecraft:custom_model_data\", \"index\": 0"), def);
        String plainDef = pack.get(NativeItemModels.definitionPath(plain.name()));
        assertFalse(plainDef.contains("\"type\": \"minecraft:custom_model_data\""), plainDef);
    }

    @Test
    @DisplayName("a give line carries the second colour only for an icon that has one")
    void theStampCarriesTheAccent() {
        IconSprites.LibraryIcon accented = IconSprites.drawn().stream().filter(IconSprites.LibraryIcon::accent)
                .findFirst().orElseThrow();
        String line = ModelData.components(accented.number(), null, 0x123456);
        if (ModelData.hasAccent(accented.number())) {
            // 1.21.4: the stack names its own definition and carries the second colour.
            assertTrue(line.contains("colors:[" + 0x123456 + "]"), line);
            assertTrue(line.contains("minecraft:item_model='wishpack:icon/" + accented.name() + "'"), line);
        } else {
            // 1.21.1 has one int and one tint: the flat twin draws the accent in the wish colour.
            assertEquals(ModelData.components(accented.number(), null), line);
        }
        int wand = ItemCatalogue.icon("wand");
        assertEquals(ModelData.components(wand, null), ModelData.components(wand, null, 0x123456),
                "an icon with no second part keeps its give line byte for byte");
    }

    @Test
    @DisplayName("an unknown name gets the nearest real ones, and the derived accent is lighter")
    void nearestAndDerived() {
        assertTrue(ItemCatalogue.nearestIcons("teddy", 6).contains("teddy_bear"));
        assertTrue(ItemCatalogue.nearestIcons("octopuss", 6).contains("octopus"));
        assertEquals(ItemCatalogue.icon("teddy_bear"), ItemCatalogue.icon("Teddy Bear"),
                "a name typed with a space is the same icon");
        int a = MakeItemTool.derivedAccent(0xC81E1E);
        assertTrue(((a >> 16) & 0xFF) > 0xC8 && ((a >> 8) & 0xFF) > 0x1E, Integer.toHexString(a));
    }

    @Test
    @DisplayName("the agent sees every icon, grouped, with the fixed ones marked")
    void theMenuListsEverything() {
        String menu = ItemCatalogue.iconMenu();
        for (String icon : ItemCatalogue.ICONS.keySet()) {
            assertTrue(menu.contains(icon), icon);
        }
        assertTrue(menu.contains("strawberry*"), "a fixed icon is marked so the agent knows its colour is ignored");
        assertTrue(menu.contains("animals:"), menu);
    }

    @Test
    @DisplayName("no two drawn icons are the same picture twice")
    void noPictureTwice() throws Exception {
        Map<String, String> seen = new java.util.HashMap<>();
        for (IconSprites.LibraryIcon icon : IconSprites.drawn()) {
            StringBuilder key = new StringBuilder();
            for (String part : List.of("", "_accent", "_detail")) {
                Path p = TEXTURES.resolve("wish/" + icon.name() + part + ".png");
                if (Files.exists(p)) {
                    key.append(java.util.Arrays.hashCode(Files.readAllBytes(p))).append('/');
                }
            }
            String other = seen.put(key.toString(), icon.name());
            assertTrue(other == null, icon.name() + " and " + other + " ship the same picture");
        }
    }
}
