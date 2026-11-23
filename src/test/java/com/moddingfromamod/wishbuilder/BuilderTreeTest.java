package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.moddingfromamod.ui.PickPage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The Builder's tree and its words stay in step with the menus</b>. The Apprentice wish box matches a
 * child's typed words to this tree, so an entry with no words is an entry no typed wish can reach, and nobody would
 * see it: every make still works by hand. These checks turn that into a failure that names the entry.
 *
 * <p>It also WRITES the export the Python back-test reads, {@code build/builder-tree.json}
 * ({@code ./gradlew test --tests '*BuilderTreeTest'}).
 */
class BuilderTreeTest {

    static final Path EXPORT = Path.of("build", "builder-tree.json");

    private static final BuilderTree TREE = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);

    @Test
    @DisplayName("the export is written for the back-test, the same bytes every time")
    void theExportIsWritten() throws IOException {
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(TREE.toJson()) + "\n";
        String again = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED).toJson()) + "\n";
        assertEquals(json, again, "two walks of the same menus must export the same tree");
        Files.createDirectories(EXPORT.getParent());
        Files.writeString(EXPORT, json, StandardCharsets.UTF_8);
        assertEquals(10, TREE.kinds().size(), "the reviewed Builder offers all ten kinds (#2182: supplies)");
    }

    @Test
    @DisplayName("every offered entry has at least " + BuilderTree.MIN_WORDS + " words a child might call it by")
    void everyEntryHasWords() {
        List<String> thin = new ArrayList<>();
        for (BuilderTree.Kind k : TREE.kinds()) {
            if (k.words().size() < BuilderTree.MIN_WORDS) {
                thin.add("kind " + k.id() + " (BuilderVocab.KINDS)");
            }
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    if (s.steps()) {
                        continue;   // a slider is one control; its numbers need no words of their own
                    }
                    for (BuilderTree.Entry e : s.entries()) {
                        if (e.words().size() < BuilderTree.MIN_WORDS) {
                            thin.add(k.id() + "/" + f.id() + "/" + s.id() + ": '" + e.label() + "' (" + e.id() + ") has "
                                    + e.words().size());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), thin, "these Builder entries have too few words, so a typed wish cannot reach them. "
                + "Add words where the entry is defined: on its record, or in BuilderVocab for a ready-made, a kind, "
                + "a creature power, a temperament, a rule trigger or mob, or an ingredient");
    }

    @Test
    @DisplayName("every word parses, and a word that also picks elsewhere names a real entry")
    void everyWordParses() {
        List<String> bad = new ArrayList<>();
        for (BuilderTree.Kind k : TREE.kinds()) {
            check(bad, k.id(), k.words(), null);
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    for (BuilderTree.Entry e : s.entries()) {
                        check(bad, k.id() + "/" + f.id() + "/" + s.id() + "/" + e.id(), e.words(), f);
                    }
                }
            }
        }
        assertEquals(List.of(), bad);
    }

    private static void check(List<String> bad, String where, List<String> words, BuilderTree.Form form) {
        Set<String> seen = new LinkedHashSet<>();
        for (String w : words) {
            try {
                BuilderTree.Term t = BuilderTree.Term.parse(w);
                if (!seen.add(t.phrase())) {
                    bad.add(where + ": '" + t.phrase() + "' twice");
                }
                t.also().forEach((slot, entry) -> {
                    BuilderTree.Slot s = form == null ? null : form.slot(slot);
                    if (s == null || s.entry(entry) == null) {
                        bad.add(where + ": '" + w + "' picks " + slot + "=" + entry + ", which this form does not offer");
                    }
                });
            } catch (IllegalArgumentException e) {
                bad.add(where + ": " + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("no word names two entries of one slot, unless every one of them marks it a family word (~)")
    void noWordClaimsTwoEntriesOfOneSlot() {
        List<String> clashes = new ArrayList<>();
        for (BuilderTree.Kind k : TREE.kinds()) {
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    Map<String, List<String>> owners = new LinkedHashMap<>();
                    Map<String, Boolean> allFamily = new LinkedHashMap<>();
                    for (BuilderTree.Entry e : s.entries()) {
                        for (String w : e.words()) {
                            BuilderTree.Term t = BuilderTree.Term.parse(w);
                            owners.computeIfAbsent(t.phrase(), x -> new ArrayList<>()).add(e.id());
                            allFamily.merge(t.phrase(), t.family(), Boolean::logicalAnd);
                        }
                    }
                    owners.forEach((phrase, ids) -> {
                        if (ids.size() > 1 && !allFamily.get(phrase)) {
                            clashes.add(k.id() + "/" + f.id() + "/" + s.id() + ": '" + phrase + "' names " + ids);
                        }
                        if (ids.size() == 1 && allFamily.get(phrase)) {
                            clashes.add(k.id() + "/" + f.id() + "/" + s.id() + ": '" + phrase + "~' is marked a family "
                                    + "word but only " + ids + " has it");
                        }
                    });
                }
            }
        }
        assertEquals(List.of(), clashes, "one word, two entries of the same slot, and no stated tie-break. Give the "
                + "word to one entry, or mark it a family word on EVERY entry that shares it (\"dino~\"): the "
                + "apprentice then offers each of them");
    }

    @Test
    @DisplayName("#1986: a vehicle is never offered a creature's parts, moods or moves — by the menus or by the tree")
    void aVehicleGetsNoCreatureParts() {
        List<String> wrong = new ArrayList<>();
        Set<String> vehicles = new TreeSet<>();
        for (BuilderTree.Kind k : TREE.kinds()) {
            BuilderTree.Form ready = k.form("ready");
            if (ready == null || ready.slot("from") == null) {
                continue;
            }
            for (BuilderTree.Entry body : ready.slot("from").entries()) {
                if (!BuilderParts.vehicle(body.id(), LibraryPresets.SHIPPED.species(body.id()))) {
                    continue;
                }
                vehicles.add(body.id());
                String where = k.id() + " → " + body.id();
                // The menus a child walks: no parts page, and no hop, pounce, nap or mood on the powers page.
                BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
                f.press(k.id());
                f.press(body.id());
                if (f.step() == BuilderFlow.Step.PARTS) {
                    wrong.add(where + ": opens the creature parts page (" + f.page().tiles().size() + " parts)");
                }
                if (f.step() == BuilderFlow.Step.POWERS) {
                    for (PickPage.Tile t : f.page().tiles()) {
                        if (t.id().startsWith("temper:") || BuilderParts.CREATURE_ONLY_POWERS.contains(Catalogue.bare(t.id()))) {
                            wrong.add(where + ": offers " + t.id());
                        }
                    }
                }
                // The tree the Apprentice matches typed words against: a creature-only pick must not go with it.
                for (String sid : List.of("parts", "powers", "temper")) {
                    BuilderTree.Slot s = ready.slot(sid);
                    if (s == null) {
                        continue;
                    }
                    for (BuilderTree.Entry e : s.entries()) {
                        boolean creatureOnly = sid.equals("parts") || sid.equals("temper") && !e.id().equals("temper:friendly")
                                || BuilderParts.CREATURE_ONLY_POWERS.contains(Catalogue.bare(e.id()));
                        boolean goes = !e.only().containsKey("from") || e.only().get("from").contains(body.id());
                        if (creatureOnly && goes) {
                            wrong.add(where + ": the tree lets '" + e.id() + "' go with it");
                        }
                    }
                }
            }
        }
        assertTrue(vehicles.contains("bodies/truck") && vehicles.contains("bodies/car") && vehicles.size() >= 11,
                "the vehicles this check walks: " + vehicles);
        assertEquals(List.of(), wrong);
        // ...and a creature still gets its parts: the rule took nothing from a cat.
        BuilderFlow cat = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        cat.press("ride");
        cat.press("species/cat");
        assertEquals(BuilderFlow.Step.PARTS, cat.step());
    }

    @Test
    @DisplayName("every slot of every kind has a default, so an unnamed part of a wish gets a sensible pick")
    void everySlotHasADefault() {
        List<String> missing = new ArrayList<>();
        for (BuilderTree.Kind k : TREE.kinds()) {
            assertFalse(k.forms().isEmpty(), k.id() + " has no way to be made");
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    String where = k.id() + "/" + f.id() + "/" + s.id();
                    assertFalse(s.entries().isEmpty(), where + " offers nothing");
                    if (!s.dflt().isEmpty()) {
                        if (s.entry(s.dflt()) == null && !s.dflt().equals("as_is")) {
                            missing.add(where + ": its default '" + s.dflt() + "' is not one of its entries");
                        }
                        continue;
                    }
                    // An empty default is "none" for an optional slot, or "the first that fits" for a pick that
                    // depends on another (an item's power on its shape, a rule's effect on its trigger).
                    boolean depends = s.entries().stream().anyMatch(e -> !e.only().isEmpty())
                            || s.id().equals("ingredient");
                    if (s.required() && !depends) {
                        missing.add(where + ": required, with no default");
                    }
                    if (depends) {
                        for (BuilderTree.Slot other : f.slots()) {
                            // a slot every entry of which goes only with the SAME picks of another (a turn-into
                            // item's From and To pages, only for that power) is asked only for those: required there.
                            boolean askedOnlyFor = s.entries().stream().allMatch(e -> e.only().containsKey(other.id()))
                                    && s.entries().stream().map(e -> e.only().get(other.id())).distinct().count() == 1;
                            for (BuilderTree.Entry oe : other.entries()) {
                                if (askedOnlyFor && !s.entries().get(0).only().get(other.id()).contains(oe.id())) {
                                    continue;
                                }
                                boolean named = s.entries().stream().anyMatch(e -> e.only().containsKey(other.id()));
                                // An optional slot may have nothing that fits (a truck takes no parts).
                                if (named && s.required() && s.entries().stream().noneMatch(e -> !e.only().containsKey(other.id())
                                        || e.only().get(other.id()).contains(oe.id()))
                                        && (!other.id().equals("who"))) {
                                    missing.add(where + ": nothing fits " + other.id() + "=" + oe.id());
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    @DisplayName("the tree holds every tile the menus show, and no words are kept for a pick the menus dropped")
    void theTreeIsTheMenus() {
        // An independent walk through the pages a child sees (page(), every "More"), against the tree's `from` slots.
        for (BuilderTree.Kind k : TREE.kinds()) {
            if (k.id().equals("rule")) {
                continue;
            }
            BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
            f.press(k.id());
            if (k.id().equals("machine")) {
                // the machine kind opens on its looks, which are the composed form's "look" slot.
                assertEquals(BuilderFlow.Step.SNACK_LOOK, f.step());
                Set<String> looks = new TreeSet<>();
                f.page().tiles().forEach(t -> looks.add(t.id()));
                Set<String> inTree = new TreeSet<>();
                k.form("composed").slot("look").entries().forEach(e -> inTree.add(e.id()));
                assertEquals(looks, inTree, "machine: the looks its page shows");
                assertNull(k.form("ready"), "machine: no ready-mades");
                continue;
            }
            if (k.id().equals("event")) {
                // the event kind opens on what happens, the made-up form's "motion" slot.
                assertEquals(BuilderFlow.Step.EVENT_MOTION, f.step());
                Set<String> motions = new TreeSet<>();
                f.page().tiles().forEach(t -> motions.add(t.id()));
                Set<String> inTree = new TreeSet<>();
                k.form("made_up").slot("motion").entries().forEach(e -> inTree.add(e.id()));
                assertEquals(motions, inTree, "event: the motions its page shows");
                assertNull(k.form("ready"), "event: no ready-mades");
                continue;
            }
            if (k.id().equals("supplies")) {
                // supplies open on a category; every category's supplies are the ready form's one "from" slot.
                assertEquals(BuilderFlow.Step.SUPPLY_CATEGORY, f.step());
                Set<String> shown = new TreeSet<>();
                for (PickPage.Tile c : f.page().tiles()) {
                    if (c.id().equals(BuilderFind.TILE_ID)) {
                        continue;   //: the 🔍 Find opens a box, it is no pick of its own
                    }
                    BuilderFlow g = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
                    g.press(k.id());
                    g.press(c.id());
                    if (g.step() == BuilderFlow.Step.SUPPLY_WHAT) {
                        g.page().tiles().forEach(t -> shown.add(t.id()));
                    } else {
                        shown.add(g.spec(java.util.Map.of()).from());   // a category of one: its supply
                    }
                }
                Set<String> inTree = new TreeSet<>();
                k.form("ready").slot("from").entries().forEach(e -> inTree.add(e.id()));
                assertEquals(shown, inTree, "supplies: the supplies its pages show");
                continue;
            }
            if (k.id().equals("magic_block")) {
                // the magic-block kind opens on what it is made of, the composed form's "material" slot.
                assertEquals(BuilderFlow.Step.BLOCK_LOOK, f.step());
                Set<String> looks = new TreeSet<>();
                f.page().tiles().forEach(t -> looks.add(t.id()));
                Set<String> inTree = new TreeSet<>();
                k.form("made_up").slot("material").entries().forEach(e -> inTree.add(e.id()));
                assertEquals(looks, inTree, "magic_block: the looks its page shows");
                assertNull(k.form("ready"), "magic_block: no ready-mades");
                continue;
            }
            if (k.id().equals("item")) {
                // the item kind opens on its stock items, which are the made-up form's "shape" slot.
                assertEquals(BuilderFlow.Step.SHAPE, f.step());
                Set<String> stock = new TreeSet<>();
                f.page().tiles().stream().filter(t -> !t.id().equals(BuilderLooks.MORE_ID)).forEach(t -> stock.add(t.id()));   //: More looks… is no stock item
                Set<String> inTree = new TreeSet<>();
                k.form("made_up").slot("shape").entries().forEach(e -> inTree.add(e.id()));
                assertEquals(stock, inTree, "item: the stock items its page shows");
                assertNull(k.form("ready"), "item: no ready-mades");
                continue;
            }
            Set<String> shown = new TreeSet<>();
            for (int p = 0; p < f.fromPages(); p++) {
                for (PickPage.Tile t : f.page().tiles()) {
                    if (!t.id().equals(BuilderFlow.MAKE_UP_ID)) {
                        shown.add(t.id());
                    }
                }
                f.press(PickPage.NEXT_ID);
            }
            BuilderTree.Form ready = k.form("ready");
            assertNotNull(ready, k.id() + " shows ready-mades the tree lacks");
            Set<String> inTree = new TreeSet<>();
            ready.slot("from").entries().forEach(e -> inTree.add(e.id()));
            if (k.form("made_up") != null && k.form("made_up").slot("from") != null) {
                // a building's types are both forms': the whole bodies and the simple ones built from parts.
                k.form("made_up").slot("from").entries().forEach(e -> inTree.add(e.id()));
            }
            if (k.form("animal") != null) {
                // the creature page ends with the real farm animals, their own form.
                k.form("animal").slot("from").entries().forEach(e -> inTree.add(e.id()));
            }
            assertEquals(shown, inTree, k.id() + ": the ready-mades its pages show");
        }
        Set<String> offered = new TreeSet<>();
        for (BuilderTree.Kind k : TREE.kinds()) {
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    s.entries().forEach(e -> offered.add(e.id()));
                }
            }
        }
        List<String> stale = new ArrayList<>();
        BuilderVocab.PRESETS.keySet().forEach(id -> {
            if (!offered.contains(id)) {
                stale.add("BuilderVocab.PRESETS: " + id);
            }
        });
        BuilderVocab.INGREDIENTS.keySet().forEach(id -> {
            if (!offered.contains("ingredient:" + id)) {
                stale.add("BuilderVocab.INGREDIENTS: " + id);
            }
        });
        BuilderVocab.TRIGGERS.keySet().forEach(id -> {
            if (!offered.contains("when:" + id)) {
                stale.add("BuilderVocab.TRIGGERS: " + id);
            }
        });
        BuilderVocab.RULE_MOBS.keySet().forEach(id -> {
            if (!offered.contains("mob:minecraft:" + id)) {
                stale.add("BuilderVocab.RULE_MOBS: " + id);
            }
        });
        BuilderVocab.FARM_ANIMALS.keySet().forEach(id -> {
            if (!offered.contains("mob:minecraft:" + id)) {
                stale.add("BuilderVocab.FARM_ANIMALS: " + id);
            }
        });
        BuilderVocab.POWERS.keySet().forEach(id -> {
            if (!offered.contains("power:" + id)) {
                stale.add("BuilderVocab.POWERS: " + id);
            }
        });
        BuilderVocab.KINDS.keySet().forEach(id -> {
            if (TREE.kind(id) == null) {
                stale.add("BuilderVocab.KINDS: " + id);
            }
        });
        assertEquals(List.of(), stale, "words kept for something the Builder no longer offers: remove the row");
        assertTrue(offered.size() > 300, "only " + offered.size() + " entries: the walk has stopped finding the menus");
    }

    @Test
    @DisplayName("a world that holds a kind back gets a tree without it: the apprentice can never offer it")
    void theTreeFollowsTheAllowlist() {
        BuilderAllowlist noRules = new BuilderAllowlist(Set.of(BuilderSpec.Kind.CREATURE), BuilderAllowlist.REVIEWED.ids(),
                Set.of("friendly"), 1);
        BuilderTree small = BuilderTree.of(noRules, LibraryPresets.SHIPPED);
        assertEquals(1, small.kinds().size());
        BuilderTree.Form ready = small.kind("creature").form("ready");
        assertEquals(1, ready.slot("temper").entries().size(), "only the temperaments this world allows");
        assertEquals(null, ready.slot("count"), "a world with one at a time asks no 'how many'");
    }
}
