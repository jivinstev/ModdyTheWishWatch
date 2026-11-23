package com.moddingfromamod.ui;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The Java → Bedrock menu mapping, locked</b> (docs/BEDROCK_COMPAT.md "Menu
 * mapping"). Two halves:
 * <ol>
 *   <li>the two forms enforce the rules measured on a real phone — a menu built with them
 *       cannot break one;</li>
 *   <li>a SOURCE sweep: nothing outside {@code ui/} opens a dialog or writes dialog JSON, and every
 *       clickable chest is a BUTTON chest — so the next menu gets the mapping because there is no
 *       other way to build one.</li>
 * </ol>
 */
class MenuMappingTest {

    static final Path MAIN = Path.of("src/main/java/com/moddingfromamod");

    // ------------------------------------------------------------------ the forms

    @Test
    void aTuneDialogIsAConfirmationWhoseYesCarriesEveryValueAndWhoseNoDoesNothing() {
        TuneDialog d = new TuneDialog("Tune it", List.of(
                new TuneDialog.Slider("size", "Size", 1, 4, 1, 2),
                new TuneDialog.Choice("scheme", "Colours", List.of(new TuneDialog.Option("ocean", "Ocean")), "ocean"),
                new TuneDialog.Text("name", "Name", "Sparky", 24)), "Make it!", "builder make abc");
        JsonObject j = d.json();
        // A confirmation whose "no" has no action: Escape on Java runs "no", so Cancel cancels (a notice's
        // Escape runs its button). The Bedrock extension shows it as the one-button notice form (ModdyDialogs).
        assertEquals("minecraft:confirmation", j.get("type").getAsString(), "Escape must cancel, not submit");
        assertFalse(j.getAsJsonObject("no").has("action"), "Cancel runs nothing");
        assertFalse(j.has("body"), "Geyser drops an item body: the preview is the picked tile");
        assertEquals("builder make abc $(size) $(scheme) $(name)",
                j.getAsJsonObject("yes").getAsJsonObject("action").get("template").getAsString());
        for (JsonElement e : j.getAsJsonArray("inputs")) {
            assertFalse(e.getAsJsonObject().get("type").getAsString().equals("minecraft:boolean"));
        }
    }

    @Test
    void aDialogWithNoInputsRunsItsCommandAsItIs() {
        JsonObject act = new TuneDialog("Your new rule", List.of(), "Make it!", "builder make abc").json()
                .getAsJsonObject("yes").getAsJsonObject("action");
        assertEquals("minecraft:run_command", act.get("type").getAsString(),
                "vanilla refuses a dynamic template with no variables (BuilderMenusGameTest found it)");
        assertEquals("builder make abc", act.get("command").getAsString());
    }

    @Test
    void theMeasuredRulesAreRefusedByName() {
        var slider = new TuneDialog.Slider("a", "A", 1, 4, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> new TuneDialog("t", List.of(slider,
                new TuneDialog.Slider("b", "B", 1, 4, 1, 2), new TuneDialog.Slider("c", "C", 1, 4, 1, 2),
                new TuneDialog.Slider("d", "D", 1, 4, 1, 2), new TuneDialog.Slider("e", "E", 1, 4, 1, 2)),
                "Go", "x"), "five inputs scroll on a phone");
        assertThrows(IllegalArgumentException.class, () -> new TuneDialog("t", List.of(
                new TuneDialog.Text("name", "Name", "Sparky", 24), slider), "Go", "x"), "a text box is last");
        assertThrows(IllegalArgumentException.class, () -> new TuneDialog.Text("name", "Name", "", 24),
                "a text box starts filled in: the phone keyboard hides Submit");
        assertThrows(IllegalArgumentException.class, () -> new TuneDialog("t", List.of(slider), "Go", "/x"));
        assertThrows(IllegalArgumentException.class, () -> new TuneDialog.Choice("c", "C",
                List.of(new TuneDialog.Option("two words", "x")), "x"), "an option id is one command word");
        assertTrue(TuneDialog.Input.class.getPermittedSubclasses().length == 4,
                "Slider, Choice, Text, Words — and no toggle: a Bedrock toggle flips only at its right end");
        assertThrows(IllegalArgumentException.class, () -> new TuneDialog("t", List.of(
                new TuneDialog.Words("wish", "Wish", "", 200, 4), slider), "Go", "x"), "the wish box is last too");
        assertThrows(IllegalArgumentException.class, () -> new TuneDialog.Words("wish", "Wish", "", 300, 4),
                "a box rides a command line: at most 240");
    }

    @Test
    void aPickPageHasTilesFirstAndControlsInTheLastRow() {
        List<PickPage.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tiles.add(PickPage.Tile.of("t" + i, "minecraft:stone", "T" + i));
        }
        PickPage p = new PickPage("Pick", tiles, true, true, "");
        assertEquals(3, p.rows(), "two rows of tiles + the control row");
        Map<Integer, String> slots = p.slots();
        assertEquals("t0", slots.get(0));
        assertEquals(PickPage.BACK_ID, slots.get(18));
        assertEquals(PickPage.NEXT_ID, slots.get(22));
        assertEquals(PickPage.CLOSE_ID, slots.get(26));
        assertThrows(IllegalArgumentException.class, () -> PickPage.of("x",
                java.util.Collections.nCopies(PickPage.MAX_TILES + 1, PickPage.Tile.of("a", "minecraft:stone", "a")), false));
        assertEquals("Flies ✔", new PickPage.Tile("power:flies", "minecraft:feather", "Flies", List.of(), true).shown(),
                "the tick goes LAST: the Pocket grid drops a leading symbol");
    }

    // ------------------------------------------------------------------ the sweep

    static List<Path> mainSources() throws IOException {
        try (Stream<Path> walk = SourceRoots.walk(MAIN)) {
            return walk.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().contains("GameTest"))
                    .filter(f -> !f.toString().contains("/client/"))
                    .sorted().toList();
        }
    }

    @Test
    @DisplayName("nothing outside ui/ opens a dialog or writes dialog JSON")
    void dialogsGoThroughTheKit() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path f : mainSources()) {
            if (f.startsWith(MAIN.resolve("ui"))) {
                continue;
            }
            String src = Files.readString(f, StandardCharsets.UTF_8);
            for (String banned : List.of(".openDialog(", "\"minecraft:notice\"", "\"minecraft:multi_action\"",
                    "\"minecraft:boolean\"", "Dialog.DIRECT_CODEC")) {
                if (src.contains(banned)) {
                    hits.add(SourceRoots.relativize(MAIN, f) + " uses " + banned);
                }
            }
        }
        assertTrue(hits.isEmpty(), "open a dialog with ui/MenuKit.tune and build it with ui/TuneDialog, so it "
                + "gets the measured Bedrock mapping (docs/BEDROCK_COMPAT.md \"Menu mapping\"):\n  " + String.join("\n  ", hits));
    }

    /** Chests whose slots hold real items a player moves — not buttons, so not button chests. */
    static final Map<String, String> NOT_BUTTONS = Map.of(
            "WishBagMenu.java", "the wish bag stores items: a player moves them in and out");

    @Test
    @DisplayName("every clickable chest is a button chest")
    void everyClickableChestIsAButtonChest() throws IOException {
        List<String> hits = new ArrayList<>();
        int menus = 0;
        for (Path f : mainSources()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            if (!src.matches("(?s).*extends (ChestMenu|AbstractContainerMenu).*")) {
                continue;
            }
            menus++;
            if (NOT_BUTTONS.containsKey(f.getFileName().toString())) {
                continue;
            }
            if (!src.contains("ButtonChest.title(") && !f.startsWith(MAIN.resolve("ui"))) {
                hits.add(SourceRoots.relativize(MAIN, f).toString());
            }
        }
        assertTrue(menus >= 3, "the sweep found " + menus + " menus — an empty glob would read green");
        assertTrue(hits.isEmpty(), "a chest that acts on a click is a BUTTON chest (net/ButtonChest.title, or "
                + "build it with ui/PickPage + MenuKit.pick), or a Pocket player cannot one-tap it (#1824):\n  "
                + String.join("\n  ", hits));
    }

    @Test
    @DisplayName("#1998, #2043: every Builder page with toggling tiles is a multi-pick page with no typed count, and draws what it cannot take grey with why")
    void aMultiPickPageDrawsItsLimitAndNothingPassesIt() {
        java.util.Random rnd = new java.util.Random(1998);
        java.util.Set<String> multiPages = new java.util.TreeSet<>();
        for (int walk = 0; walk < 400; walk++) {
            var f = new com.moddingfromamod.wishbuilder.BuilderFlow(com.moddingfromamod.wishbuilder.BuilderAllowlist.REVIEWED,
                    com.moddingfromamod.wishbuilder.LibraryPresets.SHIPPED);
            for (int press = 0; press < 40 && f.step() != com.moddingfromamod.wishbuilder.BuilderFlow.Step.TUNE; press++) {
                PickPage p = f.page();
                boolean toggles = p.tiles().stream().anyMatch(t -> t.lore().contains("Tap to add or take off"));
                if (toggles) {
                    multiPages.add(f.step().name());
                    org.junit.jupiter.api.Assertions.assertTrue(p.multi(), f.step() + ": a page of toggling tiles must be "
                            + "PickPage.multi — its picks drawn, its limit shown");
                    // no typed count — a tile it cannot take now is GREY with its reason, never just refused
                    org.junit.jupiter.api.Assertions.assertEquals(0, p.picks().most(), f.step() + ": a hand-set count");
                    for (PickPage.Tile t : p.tiles()) {
                        if (p.blocked(t) != null) {
                            org.junit.jupiter.api.Assertions.assertEquals(PickPage.BLOCKED_ITEM,
                                    p.drawn().get(p.tiles().indexOf(t)).item(), f.step() + ": " + t.id() + " is drawn grey");
                            org.junit.jupiter.api.Assertions.assertEquals(List.of(p.blocked(t)),
                                    p.drawn().get(p.tiles().indexOf(t)).lore(), f.step() + ": " + t.id() + " says why");
                        }
                    }
                }
                // A random slot of the page, as a child's tap: tiles, the picks row, Full panes, controls.
                List<Integer> slots = new java.util.ArrayList<>(p.drawn().keySet());
                slots.add(p.nextSlot());
                slots.add(p.backSlot());
                int slot = slots.get(rnd.nextInt(slots.size()));
                String what = p.at(slot);
                if (what != null && !what.equals(PickPage.CLOSE_ID)) {
                    f.press(what);
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(multiPages.containsAll(List.of("PARTS", "POWERS", "ABILITIES", "SNACK_POWER")),
                "the walk reached every multi-pick page: " + multiPages);
    }
}
