package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ui.PickPage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Apprentice's page and words: what every tile says, for every make the corpus is offered. */
class ApprenticeWordsTest {

    private static final BuilderTree TREE = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
    private static final Apprentice APPRENTICE = new Apprentice(TREE, Apprentice.english());

    private static PickPage page(String words, boolean magician, boolean priced) {
        return ApprenticeWords.page(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, APPRENTICE.match(words).offers(), magician, priced);
    }

    @Test
    @DisplayName("the page says Apprentice, its tiles say Builder make, and the last tile is honest")
    void thePageSaysWhatItIs() {
        PickPage crafter = page("a green dino i can ride", false, true);
        assertTrue(crafter.title().contains("Apprentice"));
        assertFalse(crafter.back(), "nothing behind it to go back to");
        List<PickPage.Tile> tiles = crafter.tiles();
        assertTrue(tiles.size() >= 4 && tiles.size() <= Apprentice.MOST_OFFERS + 1, "three to five makes and the last tile: " + tiles.size());
        for (PickPage.Tile t : tiles.subList(0, tiles.size() - 1)) {
            assertTrue(t.lore().contains(ApprenticeWords.TILE_LORE) && t.lore().contains(ApprenticeWords.HALF_WISH), t.lore().toString());
            assertTrue(t.item().startsWith("minecraft:"), "a vanilla item every edition draws: " + t.item());
        }
        assertEquals("Green Giant T-Rex to ride", tiles.get(0).label());
        PickPage.Tile last = tiles.get(tiles.size() - 1);
        assertEquals(ApprenticeWords.NONE, last.id());
        assertEquals(ApprenticeWords.NONE_LABEL, last.label());

        PickPage magician = page("a green dino i can ride", true, true);
        PickPage.Tile ask = magician.tiles().get(magician.tiles().size() - 1);
        assertEquals(ApprenticeWords.MAGICIAN_INSTEAD_LABEL, ask.label(), "a Magician's last tile sends his words to the agent");
        assertTrue(ask.lore().contains(ApprenticeWords.WHOLE_WISH), "and says what that costs");

        PickPage free = page("a green dino i can ride", false, false);
        assertFalse(free.tiles().get(0).lore().contains(ApprenticeWords.HALF_WISH), "no price on a world with no wish bank");
    }

    @Test
    @DisplayName("every tile the whole corpus is offered has a label a chest can show, and a real icon")
    void everyOfferedTileReads() throws IOException {
        int tiles = 0;
        for (String line : Files.readAllLines(ApprenticeCorpusTest.corpus(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject w = JsonParser.parseString(line).getAsJsonObject();
            for (Apprentice.Pick p : APPRENTICE.match(w.get("clean").getAsString()).offers()) {
                String label = ApprenticeWords.label(TREE, p);
                assertFalse(label.isBlank(), Apprentice.key(p));
                assertTrue(label.length() <= ApprenticeWords.LABEL_MAX, label);
                assertFalse(label.contains(":") && label.contains("preset"), "an id leaked into a label: " + label);
                assertTrue(ApprenticeWords.icon(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, p).startsWith("minecraft:"));
                tiles++;
            }
        }
        System.out.println("apprentice words: " + tiles + " tiles");
        assertTrue(tiles > (com.moddingfromamod.CorpusSource.real() ? 1000 : 60), "only " + tiles + " tiles checked");
    }

    @Test
    @DisplayName("the wish box and the toggle say which magic answers, and what it costs where wishes cost")
    void theModeIsAlwaysSaid() {
        assertTrue(ApprenticeWords.boxLabel(true, true).contains("Apprentice") && ApprenticeWords.boxLabel(true, true).contains("½ wish"));
        assertTrue(ApprenticeWords.boxLabel(false, true).contains("Magician") && ApprenticeWords.boxLabel(false, true).contains("1 wish"));
        assertFalse(ApprenticeWords.boxLabel(true, false).contains("wish)"), "no price on a world with no wish bank");
        assertTrue(ApprenticeWords.modeLabel(true).contains("Apprentice ✨") && ApprenticeWords.modeLabel(false).contains("Magician ✪"));
        for (boolean on : new boolean[] {true, false}) {
            for (String l : ApprenticeWords.modeLore(on)) {
                assertTrue(l.length() <= 30, "a tooltip line the client would wrap: " + l);
            }
        }
    }

    @Test
    @DisplayName("with the trained layer: the first line names what it took as closest, and each tile says what it is to his words")
    void theLayersPageSaysWhatEachTileIs() {
        ClauseReader fire = clause -> List.of(new ClauseReader.Guess("abilities=ability:fire_breath", 0.9));
        Apprentice.Answer a = APPRENTICE.match("a wolf that zorbles", fire, 0.5);
        String hello = ApprenticeWords.hello(TREE, a);
        String label = TREE.kind("creature").form("ready").slot("abilities").entry("ability:fire_breath").label();
        assertEquals(String.format(ApprenticeWords.GUESSED, label.substring(0, 1).toLowerCase() + label.substring(1)), hello);
        assertTrue(hello.contains("The closest it has: ") && hello.contains("Moddy's Parts") && !hello.contains("experimental"), hello);
        PickPage page = ApprenticeWords.page(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, a.offers(), a, false, true);
        assertEquals(List.of(ApprenticeWords.TILE_LORE, ApprenticeWords.TAG_CLOSEST, ApprenticeWords.HALF_WISH), page.tiles().get(0).lore());
        assertEquals(List.of(ApprenticeWords.TILE_LORE, ApprenticeWords.TAG_LEAVES_OUT, ApprenticeWords.TAG_CHANGE_AFTER,
                ApprenticeWords.HALF_WISH), page.tiles().get(1).lore());
        assertEquals(ApprenticeWords.NONE_LORE, page.tiles().get(page.tiles().size() - 1).lore(), "the last tile is as it was");

        // every word read by the keywords: the top tile is just what he said, and the other readings carry no tag
        Apprentice.Answer in = APPRENTICE.match("a wolf with wings", fire, 0.5);
        assertEquals(ApprenticeWords.HELLO, ApprenticeWords.hello(TREE, in));
        assertEquals(ApprenticeWords.TAG_JUST, ApprenticeWords.tag(in, 0));
        assertEquals(null, ApprenticeWords.tag(in, 1));

        // "none": today's line, and every tile leaves the unread part out
        Apprentice.Answer none = APPRENTICE.match("a wolf that zorbles", clause -> List.of(new ClauseReader.Guess(ClauseReader.NONE, 0.9)), 0.5);
        assertEquals(ApprenticeWords.HELLO + " " + ApprenticeWords.CLOSEST, ApprenticeWords.hello(TREE, none));
        assertEquals(ApprenticeWords.TAG_LEAVES_OUT, ApprenticeWords.tag(none, 0));

        // no layer in use: the page is exactly the page it was
        assertEquals(page("a wolf that zorbles", false, true), ApprenticeWords.page(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE,
                APPRENTICE.match("a wolf that zorbles").offers(), null, false, true));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#2251: a tile names what it leaves out, and a stand-in is said as one")
    void aTileNamesWhatItLeavesOut() {
        java.util.function.BiFunction<String, Integer, List<String>> lore = (words, i) -> {
            Apprentice.Answer a = APPRENTICE.match(words);
            List<Apprentice.Notes> notes = new java.util.ArrayList<>();
            a.offers().forEach(o -> notes.add(APPRENTICE.notes(words, o)));
            return ApprenticeWords.page(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, a.offers(), a, notes, false, false)
                    .tiles().get(i).lore();
        };
        List<String> hat = lore.apply("a hat with horns", 0);
        assertTrue(hat.contains("Can't do: horns"), hat.toString());
        assertTrue(hat.contains(ApprenticeWords.TAG_CHANGE_AFTER), hat.toString());
        List<String> tiger = lore.apply("a tiger", 0);
        assertTrue(tiger.contains("Closest it has: Lion (for tiger)"), tiger.toString());
        assertTrue(!tiger.contains(ApprenticeWords.TAG_JUST), "a stand-in is not just what he said: " + tiger);
        List<String> lion = lore.apply("a lion", 0);
        assertTrue(lion.contains(ApprenticeWords.TAG_JUST), lion.toString());
        assertEquals("Can't do: a, b, c and more", ApprenticeWords.cantDo(List.of("a", "b", "a", "c", "d")));
        assertTrue(ApprenticeWords.leavesOut("Can't do: horns") && ApprenticeWords.leavesOut(ApprenticeWords.TAG_LEAVES_OUT));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#2226: on a Magician's sure page, ✪ is the second tile")
    void theMagicianTileIsSecond() {
        Apprentice.Answer a = APPRENTICE.match("a lion");
        PickPage page = ApprenticeWords.page(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, TREE, a.offers(), a, null, true, true, true);
        assertEquals(ApprenticeWords.OFFER + 0, page.tiles().get(0).id());
        assertEquals(ApprenticeWords.NONE, page.tiles().get(1).id());
        assertEquals(ApprenticeWords.MAGICIAN_INSTEAD_LABEL, page.tiles().get(1).label());
        assertEquals(a.offers().size() + 1, page.tiles().size(), "every make is still there");
    }

    /** (found by R1's rating page): a look's tile names the LOOK, never the stock item it goes on ("A helmet"). */
    @Test
    void aLooksTileNamesTheLook() {
        Apprentice.Pick crown = new Apprentice.Pick("item", "made_up", java.util.Map.of("itemlook", java.util.List.of("look:crown"),
                "power", java.util.List.of("itempower:speed")));
        assertEquals("Crown (wear it): super speed", ApprenticeWords.label(TREE, crown));   //: how it is used
    }
}
