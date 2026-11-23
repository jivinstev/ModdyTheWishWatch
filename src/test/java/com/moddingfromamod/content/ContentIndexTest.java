package com.moddingfromamod.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.moddingfromamod.wishbuilder.Apprentice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared content search, on fixtures and on the committed snapshot of the real game. */
class ContentIndexTest {

    private static final List<ContentIndex.Entry> SMALL = List.of(
            new ContentIndex.Entry("minecraft:honey_block", ContentIndex.Kind.BLOCK, "Honey Block"),
            new ContentIndex.Entry("minecraft:wither", ContentIndex.Kind.MOB, "Wither"),
            new ContentIndex.Entry("witherstormmod:wither_storm", ContentIndex.Kind.MOB, "Wither Storm"),
            new ContentIndex.Entry("minecraft:cake", ContentIndex.Kind.ITEM, "Cake"),
            new ContentIndex.Entry("minecraft:acacia_button", ContentIndex.Kind.ITEM, "Acacia Button"));

    private static List<String> ids(ContentIndex ix, String q) {
        List<String> out = new ArrayList<>();
        ix.search(q, Set.of(), 9).forEach(h -> out.add(h.entry().id()));
        return out;
    }

    @Test
    @DisplayName("a mod's thing outranks vanilla's, a name matches, a misspelling is put right, a fragment is not a hit")
    void theRules() {
        ContentIndex ix = new ContentIndex(SMALL, Apprentice.english());
        assertEquals("witherstormmod:wither_storm", ids(ix, "wither storm").get(0), "the real wither storm first");
        assertEquals(List.of("minecraft:honey_block"), ids(ix, "honey block"));
        assertEquals(List.of("minecraft:honey_block"), ids(ix, "honny blok"), "a child's spelling");
        assertEquals(List.of("minecraft:cake"), ids(ix, "a cake"));
        assertTrue(ids(ix, "butt cheek water").isEmpty(), "#252: 'butt' is not a button");
        assertEquals(ContentIndex.Tier.NAME, ix.search("cake", Set.of(), 1).get(0).tier());
    }

    @Test
    @DisplayName("over the real game's snapshot: the answers the reference checks, written for --sync")
    void theSnapshotAnswersAndIsWrittenForTheReference() throws IOException {
        Path snap = Path.of("corpus/apprentice/content-index.json");
        if (!Files.exists(snap)) {
            return;   // first run: ContentIndexGameTest writes it, `content_search.py accept` commits it
        }
        JsonArray rows = JsonParser.parseString(Files.readString(snap, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("entries");
        List<ContentIndex.Entry> entries = new ArrayList<>();
        rows.forEach(r -> {
            JsonArray a = r.getAsJsonArray();
            entries.add(new ContentIndex.Entry(a.get(0).getAsString(), ContentIndex.Kind.valueOf(a.get(1).getAsString()),
                    a.get(2).getAsString()));
        });
        ContentIndex ix = new ContentIndex(entries, Apprentice.english());
        assertTrue(ids(ix, "honny blok").contains("minecraft:honey_block"));
        assertEquals("minecraft:zombie", ix.search("zombie", Set.of(ContentIndex.Kind.MOB), 1).get(0).entry().id());
        assertEquals("minecraft:skeleton", ix.search("skeleton", Set.of(ContentIndex.Kind.MOB), 1).get(0).entry().id());
        Map<String, List<String>> answers = new LinkedHashMap<>();
        for (String q : SYNC) {
            answers.put(q, ids(ix, q));
        }
        for (String q : RESPELT) {
            List<String> r = new ArrayList<>();
            ix.respelt(q, Set.of(), 9).forEach(h -> r.add(h.entry().id()));
            answers.put("respelt:" + q, r);
        }
        assertEquals("minecraft:parrot", ix.respelt("prarot", Set.of(ContentIndex.Kind.MOB), 1).get(0).entry().id(), "#2271");
        assertTrue(ix.respelt("carrot", Set.of(), 9).isEmpty(), "a name as typed is never respelt");
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/content-search-java.json"), new Gson().toJson(answers), StandardCharsets.UTF_8);
    }

    /** tools/content_search.py SYNC_QUERIES, word for word. */
    static final List<String> SYNC = List.of("honey block", "honny blok", "zombie", "skeleton", "a cake", "cakes", "diamond",
            "diamnod", "iron blocks", "wither storm", "creeper", "crepeer", "red wool", "glowstone", "glostone", "packed ice",
            "packedice", "sand", "sandd", "golden apple", "a giant", "ender dragon", "cat", "butt cheek water",
            "shiny blue block", "oak log", "torch", "bucket of water", "slime", "boat");

    /** tools/content_search.py RESPELT_QUERIES, word for word. */
    static final List<String> RESPELT = List.of("prarot", "parrt", "sqid", "skwid", "diamodns", "golden aple", "crepeer",
            "zombe", "parrot", "carrot", "honny blok", "glostone", "enderman", "skeletn", "witherr storm");
}
