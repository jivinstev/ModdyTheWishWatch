package com.moddingfromamod.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.moddingfromamod.compat.Wearables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>An entry that needs what this Minecraft cannot do is left off — and so is what only it
 * hands out.</b> Runs on BOTH targets and asserts each one's answer: 1.21.4 serves the worn boots,
 * 1.21.1 withholds the entry, its datapack and its item, and keeps everything else.
 */
class StarterNeedsTest {

    @TempDir
    Path dir;

    private LibrarySnapshot library() throws Exception {
        Files.writeString(dir.resolve("templates.json"), """
                {"templates":[
                  {"id": "boots", "label": "b", "phrase": "boots", "all": ["boots"],
                   "commands": ["wishdatapack jump_boots give"], "needs": "wearables"},
                  {"id": "cookie", "label": "c", "phrase": "cookie", "all": ["cookie"],
                   "commands": ["wishdatapack tiny_cookie give"]}
                ]}
                """);
        for (String id : new String[] {"jump_boots", "tiny_cookie"}) {
            Files.createDirectories(dir.resolve("datapacks").resolve(id));
            Files.writeString(dir.resolve("datapacks").resolve(id).resolve(id + ".zip"), "x");
            Files.createDirectories(dir.resolve("items").resolve(id));
            Files.writeString(dir.resolve("items").resolve(id).resolve("item.json"), "{}");
        }
        return LibrarySnapshot.read(dir);
    }

    @Test
    @DisplayName("worn boots are served where they can be worn, and withheld WHOLE where they cannot")
    void anUnmetNeedWithholdsTheEntryAndWhatOnlyItUses() throws Exception {
        LibrarySnapshot lib = library();
        assertTrue(lib.hasTemplate("cookie"));
        assertTrue(lib.has("datapacks", "tiny_cookie") && lib.has("items", "tiny_cookie"));
        assertEquals(Wearables.SUPPORTED, lib.hasTemplate("boots"), "the row");
        assertEquals(Wearables.SUPPORTED, lib.has("datapacks", "jump_boots"),
                "a datapack whose give line this Minecraft cannot parse must not be installed");
        assertEquals(Wearables.SUPPORTED, lib.has("items", "jump_boots"), "its item");
        assertTrue(lib.problems().isEmpty(), "a deliberate omission is not a problem: " + lib.problems());
    }

    @Test
    @DisplayName("a need nobody knows is never met")
    void anUnknownNeedIsUnmet() {
        var o = com.google.gson.JsonParser.parseString("{\"needs\":\"flying_cars\"}").getAsJsonObject();
        assertTrue(StarterNeeds.unmet(o) != null);
        assertEquals(null, StarterNeeds.unmet(new com.google.gson.JsonObject()));
    }
}
