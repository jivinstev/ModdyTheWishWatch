package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A creation's panel icon is what it IS</b> — and a screenshot, which is where
 * this started: a row of identical amethyst shards over things he had made.
 */
class CreationIconsTest {

    /**
     * <b>Every kind the snapshot ACTUALLY WRITES maps to a real icon.</b> The old test fed
     * {@code forKind} the {@link MadeKind} constants — and the snapshot writes {@code "species"}
     * for a creature, which no constant spells, so every creature on the panel was the fallback
     * while that test stayed green. This reads the kinds out of {@code WatchSnapshot.java} itself.
     */
    @Test
    @DisplayName("every kind WatchSnapshot writes has an icon — read from the snapshot, not from MadeKind")
    void everyKindTheSnapshotWritesHasAnIcon() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/moddingfromamod/wish/WatchSnapshot.java"));
        Matcher m = Pattern.compile("(?:build|simple)\\((?:me,\\s*)?[^,]+,\\s*\"([a-z_]+)\"").matcher(src);
        Set<String> kinds = new java.util.LinkedHashSet<>();
        while (m.find()) {
            kinds.add(m.group(1));
        }
        assertTrue(kinds.contains("species") && kinds.contains("build") && kinds.contains("item"),
                "the reader found " + kinds + " — it no longer sees the snapshot's kinds, so it "
                        + "would pass over nothing");
        for (String kind : kinds) {
            assertNotEquals(WatchIcons.UNKNOWN, WatchIcons.forKind(kind),
                    "the panel shows kind '" + kind + "' as the amethyst fallback");
        }
    }

    @Test
    @DisplayName("two creatures of different colours are two different eggs, and the spelling round-trips")
    void creaturesAreTheirOwnColour() {
        String red = WatchIcons.forCreature(0xE23B3B);
        String blue = WatchIcons.forCreature(0x3B6BE2);
        assertNotEquals(red, blue);
        assertEquals("wish:egg#E23B3B", red);
        WatchIcons.Sprite s = WatchIcons.spriteOf(red);
        assertEquals(new WatchIcons.Sprite("egg", 0xE23B3B), s);
        assertNull(WatchIcons.spriteOf("minecraft:bricks"));
        assertNull(WatchIcons.spriteOf("wish:egg"), "no colour, no sprite — never a silent default");
    }

    @Test
    @DisplayName("a building is the block it is MOSTLY made of; air never, water as its bucket")
    void aBuildingIsItsMainBlock() {
        Map<String, Integer> castle = new LinkedHashMap<>();
        castle.put("minecraft:air", 5000);
        castle.put("stone_bricks", 900);
        castle.put("minecraft:oak_planks[facing=north]", 300);
        assertEquals("minecraft:stone_bricks", WatchIcons.forBuild(castle));
        Map<String, Integer> moat = new LinkedHashMap<>();
        moat.put("minecraft:lava", 800);
        moat.put("minecraft:cobblestone", 100);
        assertEquals("minecraft:lava_bucket", WatchIcons.forBuild(moat));
        assertEquals(WatchIcons.forKind(MadeKind.BUILD), WatchIcons.forBuild(Map.of("minecraft:air", 9)));
        assertEquals(WatchIcons.forKind(MadeKind.BUILD), WatchIcons.forBuild(null));
    }

    @Test
    @DisplayName("an item is itself; a blank key falls back to the kind, never to the amethyst")
    void anItemIsItself() {
        assertEquals("stack:Hot Coffee|3", WatchIcons.forItem("Hot Coffee|3"));
        assertEquals("Hot Coffee|3", WatchIcons.stackKeyOf("stack:Hot Coffee|3"));
        assertNull(WatchIcons.stackKeyOf("stack:"));
        assertEquals(WatchIcons.forKind(MadeKind.ITEM), WatchIcons.forItem(" "));
    }

    @Test
    @DisplayName("the icon crosses the wire, and a row an older build wrote still decodes")
    void theIconRoundTrips() {
        WatchRow.Creation c = new WatchRow.Creation("spikor", "species", "Spikor", List.of(),
                List.of(), "", List.of(), "", 1, false, List.of(), "", "",
                WatchIcons.forCreature(0x2E8B57));
        assertEquals(c.icon(), WatchRow.Creation.decode(c.encode()).icon());
        String old = new WatchRow.Creation("tower", "build", "Tower", List.of(), List.of(), "",
                List.of(), "", 1, false, List.of(), "", "").encode();
        assertEquals("", WatchRow.Creation.decode(old).icon());
    }

    /**
     * <b>The screenshot, as a number.</b> Six creatures, two buildings and an item used to lay out
     * as six eggs-or-amethysts, two identical brick blocks and a diamond. With icons of their own,
     * each is different.
     */
    @Test
    @DisplayName("a panel of nine different creations shows nine different icons")
    void theScreenshotRow() {
        List<WatchRow.Creation> made = new java.util.ArrayList<>();
        int[] colours = {0x7A4BC2, 0x2E8B57, 0xE23B3B, 0xF2C94C, 0x3B6BE2, 0x111111};
        for (int i = 0; i < colours.length; i++) {
            made.add(new WatchRow.Creation("c" + i, "species", "creature " + i, List.of(), List.of(),
                    "", List.of(), "", 1, false, List.of(), "", "", WatchIcons.forCreature(colours[i])));
        }
        made.add(new WatchRow.Creation("castle", "build", "Castle", List.of(), List.of(), "",
                List.of(), "", 1, false, List.of(), "", "",
                WatchIcons.forBuild(Map.of("minecraft:stone_bricks", 900))));
        made.add(new WatchRow.Creation("hut", "build", "Hut", List.of(), List.of(), "", List.of(),
                "", 1, false, List.of(), "", "", WatchIcons.forBuild(Map.of("minecraft:oak_planks", 90))));
        made.add(new WatchRow.Creation("wand", "item", "Wand", List.of(), List.of(), "", List.of(),
                "", 1, false, List.of(), "", "", WatchIcons.forItem("Wand|10")));
        WatchPanel.Layout layout = WatchPanel.lay(List.of(), List.of(), made);
        Set<String> icons = new java.util.HashSet<>();
        for (WatchPanel.Cell cell : layout.cells()) {
            if (cell.family() == WatchPanel.Family.CREATION) {
                assertNotEquals(WatchIcons.UNKNOWN, cell.icon(), cell.label());
                icons.add(cell.icon());
            }
        }
        assertEquals(made.size(), icons.size(), "icons: " + icons);
        assertFalse(icons.contains(WatchIcons.UNKNOWN));
    }
}
