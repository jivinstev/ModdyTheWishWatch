package com.moddingfromamod.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.ItemTriggers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * <b>: a starter button is held to the same rule as a wish.</b>
 *
 * <p>The starter items are {@code make_item} output frozen into the jar, so the rules F.1–F.5 put on
 * {@code make_item} never reached them. The pass found one: the tiny cookie was still a right-click
 * horse armour ({@code held}, no base) &mdash; a cookie he could not eat, on the one button every new
 * child sees. So each starter item must (1) pass {@link ItemTriggers#check} on its own base and
 * shape, and (2) if it looks like something you eat or drink, be eaten or drunk ({@code on_consume}).
 */
class StarterItemsRideTheirUseTest {

    private static final Path ITEMS = Path.of("src/main/resources/starter-library/items");
    /** Sprites a child reads as food or a drink (ItemCatalogue's icon names). */
    private static final Set<String> EDIBLE_SPRITES = Set.of("food_round", "bottle", "cup", "cone");

    private static List<Path> starters() throws IOException {
        try (Stream<Path> s = Files.list(ITEMS)) {
            return s.filter(p -> Files.isRegularFile(p.resolve("item.json"))).sorted().toList();
        }
    }

    private static JsonObject read(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    @Test
    void everyStarterItemsShapeWorksOnItsBase() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path dir : starters()) {
            JsonObject item = read(dir.resolve("item.json"));
            var v = ItemTriggers.check(str(item, "base_item"), str(item, "shape"));
            if (!v.ok()) {
                bad.add(dir.getFileName() + ": " + v.reason());
            }
        }
        assertEquals(List.of(), bad, "a starter item make_item would refuse today");
    }

    @Test
    void aStarterThatLooksEdibleIsEaten() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path dir : starters()) {
            JsonObject item = read(dir.resolve("item.json"));
            Path iconFile = dir.resolve("icon.json");
            String sprite = Files.isRegularFile(iconFile) ? str(read(iconFile), "sprite") : null;
            String name = String.valueOf(str(item, "name")).toLowerCase(Locale.ROOT);
            boolean edible = sprite != null && EDIBLE_SPRITES.contains(sprite)
                    || name.matches(".*\\b(cookie|cake|apple|bread|potion|drink|soup|pie)\\b.*");
            if (edible && !"on_consume".equals(str(item, "shape"))) {
                bad.add(dir.getFileName() + " (" + name + ") is shape " + str(item, "shape")
                        + " — he would click it, never eat it");
            }
        }
        assertEquals(List.of(), bad, "an edible starter must ride the eat (on_consume)");
        assertFalse(starters().isEmpty(), "no starter items found — the path moved");
    }
}
