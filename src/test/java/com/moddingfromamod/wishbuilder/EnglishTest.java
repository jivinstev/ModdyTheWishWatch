package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** English plurals both ways; the answers over every installed name are written for tools/english.py sync. */
class EnglishTest {

    @Test
    @DisplayName("a plural is read as its thing, and a thing is said for several")
    void bothWays() {
        for (Map.Entry<String, String> e : Map.of("geese", "goose", "potatoes", "potato", "cookies", "cookie",
                "zombies", "zombie", "knives", "knife", "cacti", "cactu", "mice", "mouse", "wolves", "wolf",
                "tomatos", "tomato", "berrys", "berry").entrySet()) {
            assertEquals(Apprentice.stem(e.getValue().equals("cactu") ? "cactus" : e.getValue()), Apprentice.stem(e.getKey()), e.getKey());
        }
        assertEquals("Golden Apples", English.plural("Golden Apple", false));
        assertEquals("Foxes", English.plural("Fox", true));
        assertEquals("Sweet Berries", English.plural("Sweet Berries", false));
        assertEquals("Dirt", English.plural("Dirt", false));
        assertEquals("Rabbits", English.plural("Rabbit", true), "a creature always takes a plural");
        assertEquals("Cooked Rabbit", English.plural("Cooked Rabbit", false), "meat is a material");
        assertEquals("Eyes of Ender", English.plural("Eye of Ender", false));
        assertEquals("Endermen", English.plural("Enderman", true));
        assertEquals("Sheep", English.plural("Sheep", true));
    }

    @Test
    @DisplayName("every installed name, said for several, written for the reference")
    void everyNameIsWrittenForTheReference() throws IOException {
        Path snap = Path.of("corpus/apprentice/content-index.json");
        if (!Files.exists(snap)) {
            return;
        }
        JsonArray rows = JsonParser.parseString(Files.readString(snap, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("entries");
        Map<String, String> out = new TreeMap<>();
        rows.forEach(r -> {
            JsonArray a = r.getAsJsonArray();
            String kind = a.get(1).getAsString();
            if (kind.equals("ITEM") || kind.equals("MOB")) {
                out.put(kind + "|" + a.get(2).getAsString(), English.plural(a.get(2).getAsString(), kind.equals("MOB")));
            }
        });
        English.readings().keySet().forEach(w -> out.put("read|" + w, English.singular(w)));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/english-java.json"), new GsonBuilder().create().toJson(out), StandardCharsets.UTF_8);
    }
}
