package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.moddingfromamod.marshal.StackVitals;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A thing said for one, one way everywhere</b>: "Getting a the last sword…", "a shears" and "an oak planks"
 * came from four private a/an rules. A fixture of real item names (vanilla 1.21.8 and the mods was seen with) with
 * the phrase each must come out as, then the five places that say one, then every vanilla item a Builder Find shows.
 */
class EnglishArticleTest {

    /** A found item from another mod (Found's snapshot is vanilla's): its id is not minecraft's, so it may be a title. */
    private static BuilderSupplies.Supply modded(String id, String label) {
        return new BuilderSupplies.Supply(BuilderSupplies.FOUND_PREFIX + id, id, BuilderSupplies.FOUND_CATEGORY, label, 64, 1, List.of());
    }

    private static BuilderSupplies.Supply vanilla(String path) {
        return BuilderSupplies.supply(BuilderSupplies.FOUND_PREFIX + "minecraft:" + path);
    }

    @Test
    @DisplayName("the names in #2634, as a found item is said for one")
    void theIssuesNames() {
        Map<String, String> modded = new LinkedHashMap<>();
        modded.put("The Last Sword", "The Last Sword");
        modded.put("The Last Sword You Never Forgot", "The Last Sword You Never Forgot");
        modded.put("The Giver's Pain", "The Giver's Pain");
        modded.put("The-Last-End Sword Wraith Spawn Egg", "The-Last-End Sword Wraith Spawn Egg");
        modded.put("the last sword", "the last sword");
        modded.put("Dragon Boots", "a pair of dragon boots");
        modded.put("Boots of the Deep Abyss", "a pair of Boots of the Deep Abyss");
        modded.put("Dragon Cult Paladin Greaves", "a pair of dragon cult paladin greaves");
        modded.put("Shadow Gauntlets", "a pair of shadow gauntlets");
        modded.put("Leggings of Earth's Blessing", "a pair of Leggings of Earth's Blessing");
        modded.put("Crown of Countless Stars", "a Crown of Countless Stars");
        modded.put("Zorbo's Shell", "a Zorbo's Shell");
        modded.put("Moddy", "Moddy");
        modded.put("Ruby Sword", "a ruby sword");
        modded.put("Amber Shard", "an amber shard");
        modded.forEach((label, said) -> assertEquals(said, BuilderSupplies.words(modded("othermod:x", label), 1), label));

        Map<String, String> plain = new LinkedHashMap<>();
        plain.put("shears", "a pair of shears");
        plain.put("iron_bars", "some iron bars");
        plain.put("leather_boots", "a pair of leather boots");
        plain.put("diamond_leggings", "a pair of diamond leggings");
        plain.put("oak_planks", "some oak planks");
        plain.put("oak_stairs", "some oak stairs");
        plain.put("bricks", "some bricks");
        plain.put("wheat_seeds", "some wheat seeds");
        plain.put("sweet_berries", "some sweet berries");
        plain.put("cocoa_beans", "some cocoa beans");
        plain.put("pink_petals", "some pink petals");
        plain.put("sugar", "some sugar");
        plain.put("gunpowder", "some gunpowder");
        plain.put("dragon_breath", "some dragon's breath");
        plain.put("dirt", "some dirt");
        plain.put("sand", "some sand");
        plain.put("glass", "some glass");
        plain.put("white_wool", "some white wool");
        plain.put("golden_apple", "a golden apple");
        plain.put("egg", "an egg");
        plain.put("cake", "a cake");
        plain.put("ender_eye", "an eye of ender");
        plain.put("coal_block", "a block of coal");
        plain.put("carrot_on_a_stick", "a carrot on a stick");
        plain.put("jack_o_lantern", "a jack o'lantern");
        plain.forEach((path, said) -> assertEquals(said, BuilderSupplies.words(vanilla(path), 1), path));
    }

    @Test
    @DisplayName("several keep the count, as before; a leading The is dropped for several")
    void several() {
        assertEquals("16 golden apples", BuilderSupplies.words(vanilla("golden_apple"), 16));
        assertEquals("16 dirt", BuilderSupplies.words(vanilla("dirt"), 16));
        assertEquals("4 shears", BuilderSupplies.words(vanilla("shears"), 4));
        assertEquals("32 oak planks", BuilderSupplies.words(vanilla("oak_planks"), 32));
        assertEquals("2 Last Swords", BuilderSupplies.words(modded("othermod:x", "The Last Sword"), 2));
        assertEquals("16 oak logs", BuilderSupplies.words(BuilderSupplies.supply("oak_log"), 16));
    }

    @Test
    @DisplayName("Moddy's lines and the plan line say it the same way, with 'are' for some planks")
    void theBuilderLines() {
        BuilderSpec shears = spec("item:minecraft:shears", 1);
        assertEquals("Getting a pair of shears...", BuilderWords.making(shears, false));
        assertEquals("Here is a pair of shears!", BuilderWords.ready(shears));
        assertEquals("give him a pair of shears", BuilderWords.step(shears, false));
        BuilderSpec planks = spec("item:minecraft:oak_planks", 1);
        assertEquals("Getting some oak planks...", BuilderWords.making(planks, false));
        assertEquals("Here are some oak planks!", BuilderWords.ready(planks));
        assertEquals("give him some oak planks", BuilderWords.step(planks, false));
        BuilderSpec sugar = spec("item:minecraft:sugar", 1);
        assertEquals("Here is some sugar!", BuilderWords.ready(sugar));
        assertEquals("Here are 16 golden apples!", BuilderWords.ready(spec("item:minecraft:golden_apple", 16)));
        // the stock supplies' own singulars go through the same rule
        assertEquals("an oak log", BuilderSupplies.words(BuilderSupplies.supply("oak_log"), 1));
        assertEquals("some bone meal", BuilderSupplies.words(BuilderSupplies.supply("bone_meal"), 1));
        assertEquals("a crafting table", BuilderSupplies.words(BuilderSupplies.supply("crafting_table"), 1));
    }

    private static BuilderSpec spec(String from, int n) {
        return BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"supplies\",\"from\":\"" + from
                + "\",\"opts\":{\"amount\":\"" + n + "\"}}").getAsJsonObject());
    }

    /** OneOffWish.article and Rewish.withA are package-private: OneOffWishTest and RewishWordsTest say theirs. */
    @Test
    @DisplayName("a stack's base item, and the rule for a creature and for a look")
    void theOtherPlaces() {
        assertEquals("a pair of diamond leggings", StackVitals.whatItIs(facts("minecraft:diamond_leggings")));
        assertEquals("a pair of shears", StackVitals.whatItIs(facts("minecraft:shears")));
        assertEquals("an iron sword", StackVitals.whatItIs(facts("minecraft:iron_sword")));
        assertEquals("a netherite pickaxe", StackVitals.whatItIs(facts("minecraft:netherite_pickaxe")));

        assertEquals("an Owl", English.withArticle("Owl", 1, true));
        assertEquals("The Grinch", English.withArticle("The Grinch", 1, true));
        assertEquals("a Drowned", English.withArticle("Drowned", 1, true), "a creature is never 'some'");
        assertEquals("some Fries", English.withArticle("Fries", 1));
        assertEquals("an Octopus", English.withArticle("Octopus", 1));
        assertEquals("a Cactus", English.withArticle("Cactus", 1));
        assertEquals("a Unicorn", English.withArticle("Unicorn", 1), "the old look line said it right");
        assertEquals("a UFO", English.withArticle("UFO", 1));
        assertEquals("an Elephant", English.withArticle("Elephant", 1));
    }

    private static StackVitals.Facts facts(String base) {
        return new StackVitals.Facts(base, false, Map.of(), false, null, null);
    }

    @Test
    @DisplayName("every vanilla item a Builder Find shows: never 'a the', '1 x', or a/an before a plural")
    void everyFoundItem() throws IOException {
        Path snap = Path.of("corpus/apprentice/content-index.json");
        if (!Files.exists(snap)) {
            return;
        }
        JsonArray rows = JsonParser.parseString(Files.readString(snap, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("entries");
        List<String> bad = new ArrayList<>();
        int seen = 0;
        for (var r : rows) {
            JsonArray a = r.getAsJsonArray();
            String id = a.get(0).getAsString();
            if (!a.get(1).getAsString().equals("ITEM") || BuilderFind.refused(id)) {
                continue;
            }
            BuilderSupplies.Supply s = BuilderSupplies.supply(BuilderSupplies.FOUND_PREFIX + id);
            if (s == null) {
                continue;
            }
            seen++;
            String said = BuilderSupplies.words(s, 1);
            String[] w = said.split(" ");
            String head = English.saidAsMany(s.label()) ? "many" : "one";
            if (said.startsWith("1 ") || said.matches("^an? the\\b.*") || !said.equals(said.toLowerCase(Locale.ROOT))
                    || (said.startsWith("a ") || said.startsWith("an ")) && head.equals("many") && !said.startsWith("a pair of ")
                    || said.startsWith("an ") && "aeiou".indexOf(w[1].charAt(0)) < 0
                    || said.startsWith("a ") && !said.startsWith("a pair of ") && "aeiou".indexOf(w[1].charAt(0)) >= 0
                            && !w[1].startsWith("uni")) {
                bad.add(id + " → " + said);
            }
        }
        assertTrue(seen > 1000, "the snapshot's vanilla items were read: " + seen);
        assertEquals(List.of(), bad);
    }

    @Test
    @DisplayName("the table: every pair noun is also an already-plural word, and the issue's additions are in")
    void theTable() {
        for (String w : Set.of("greaves", "gauntlets", "shears", "boots", "leggings")) {
            assertEquals("a pair of " + w, English.withArticle(w, 1), w);
            assertEquals(w, English.plural(w, false), w + " is already plural");
        }
        assertEquals("Fries", English.plural("Fries", false));
        assertFalse(English.saidAsMany("Shears"), "a pair is one");
        assertTrue(English.saidAsMany("Oak Planks"));
        assertFalse(English.proper("Golden Apple"));
        assertTrue(English.proper("The Last Sword"));
    }
}
