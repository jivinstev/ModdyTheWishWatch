package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Everything the Builder offers on one world, as one tree</b>: each kind, the ways to make it (its
 * FORMS), each form's slots, and each slot's entries with their label and the words a child calls them by.
 *
 * <p><b>Walked, never listed.</b> {@link #of} presses through a real {@link BuilderFlow} and reads the tiles its
 * pages show and the controls its tune dialog asks, so the tree is what the menus offer on THIS allowlist: an entry
 * a world holds back is not in it, and an entry added to the menus is in it with no second list to update. The
 * words come from where each entry is defined (its record, or {@link BuilderVocab}).
 *
 * <p>Two readers: the Apprentice matcher reads it at runtime, and {@link #toJson} is the generated export
 * ({@code build/builder-tree.json}, written by {@code BuilderTreeTest} in Gate A) that the Python back-test reads.
 * It replaced the hand-kept mirror in {@code tools/builder_tree.py}. Pure.
 */
public final class BuilderTree {

    /** An offered entry needs at least this many words of its own; {@code BuilderTreeTest} fails one with fewer. */
    public static final int MIN_WORDS = 3;

    /** The export's shape version: bump it when a reader would misread the new shape. */
    public static final int VERSION = 1;

    /**
     * One pick. {@code id} is the tile id the menu presses (or a slider's step, a dialog option). {@code only} names
     * the other picks it needs: slot → the values it goes with (an item power fits some shapes; a rule's effect fits
     * some triggers). {@code facts} carries what a matcher must know to keep a pick valid (a body's part budget and
     * each part's cost on it; a food look's own ingredient).
     */
    public record Entry(String id, String label, List<String> words, Map<String, List<String>> only,
                        Map<String, String> facts) {
        public Entry {
            words = List.copyOf(words);
            only = Map.copyOf(only);
            facts = Map.copyOf(facts);
        }
    }

    /**
     * One choice a form asks. {@code most} is how many may be picked (1 = pick one); {@code required} means the form
     * cannot be made without one; {@code dflt} is what an unnamed slot gets (the dialog's own default, the first tile
     * of a required page, or empty for "none"); {@code steps} marks a slider, whose entries are its numbers.
     */
    public record Slot(String id, String label, int most, boolean required, String dflt, boolean steps,
                       List<Entry> entries, List<List<String>> clashes) {
        public Slot {
            entries = List.copyOf(entries);
            clashes = List.copyOf(clashes);
        }

        public Entry entry(String id) {
            for (Entry e : entries) {
                if (e.id().equals(id)) {
                    return e;
                }
            }
            return found(id);
        }

        /**
         *: besides the picks it lists, a real animal's From takes any installed creature
         * ({@code mob:<id>}) and a Supplies' From any installed item ({@code item:<id>}), as
         * {@link com.moddingfromamod.content.Found} finds them. The entry is made up: its label is the thing's
         * English name. tools/apprentice_lib.py {@code found_entry} is the twin.
         */
        private Entry found(String id) {
            if (id != null && (this.id.equals(BuilderTurnInto.FROM_SLOT) || this.id.equals(BuilderTurnInto.TO_SLOT))) {
                return foundTurn(id);
            }
            if (id != null && this.id.equals("what") && id.startsWith("what:")) {   //: "it rains slimes"
                BuilderEvents.What w = BuilderEvents.found(id.substring("what:".length()));
                return w == null ? null : new Entry(id, w.label(), w.words(), Map.of("motion", BuilderEvents.dropMotions()), Map.of());
            }
            if (!this.id.equals("from") || id == null) {
                return null;
            }
            com.moddingfromamod.content.ContentIndex.Kind kind;
            String prefix;
            if (id.startsWith("mob:") && entries.stream().anyMatch(e -> e.id().startsWith("mob:"))) {
                kind = com.moddingfromamod.content.ContentIndex.Kind.MOB;
                prefix = "mob:";
            } else if (id.startsWith("item:") && entries.stream().anyMatch(e -> e.id().startsWith("supply:"))) {
                kind = com.moddingfromamod.content.ContentIndex.Kind.ITEM;
                prefix = "item:";
            } else {
                return null;
            }
            String name = com.moddingfromamod.content.Found.name(kind, id.substring(prefix.length()));
            return name == null ? null
                    : new Entry(id, name, List.of(), Map.of(), com.moddingfromamod.content.Found.facts(name,
                    kind == com.moddingfromamod.content.ContentIndex.Kind.MOB));
        }

        /**
         * §4: Turn-into's From or To takes an installed block found by name ({@code turnfrom:found:<id>},
         * {@code turnto:found:<id>}), with the facts its own picks carry. tools/apprentice_lib.py {@code found_entry}.
         */
        private Entry foundTurn(String id) {
            Map<String, List<String>> only = Map.of("power", List.of("itempower:" + BuilderTurnInto.POWER));
            if (this.id.equals(BuilderTurnInto.FROM_SLOT) && id.startsWith(BuilderTurnInto.FROM_PREFIX + BuilderTurnInto.FOUND)) {
                BuilderTurnInto.From f = BuilderTurnInto.from(id);
                return f == null ? null : new Entry(id, f.label(), List.of(), only, BuilderTurnInto.facts(f));
            }
            if (this.id.equals(BuilderTurnInto.TO_SLOT) && id.startsWith(BuilderTurnInto.TO_PREFIX + BuilderTurnInto.FOUND)) {
                BuilderTurnInto.To t = BuilderTurnInto.to(id);
                return t == null ? null : new Entry(id, t.label(), List.of(), only, BuilderTurnInto.facts(t));
            }
            return null;
        }

        /** Is this id a found entry here (an installed thing), not one of the slot's own picks? */
        public boolean isFound(String id) {
            for (Entry e : entries) {
                if (e.id().equals(id)) {
                    return false;
                }
            }
            return found(id) != null;
        }
    }

    /** One way to make a kind: a ready-made, or a composed thing (a made-up item, a snack machine, an event). */
    public record Form(String id, List<Slot> slots) {
        public Form {
            slots = List.copyOf(slots);
        }

        public Slot slot(String id) {
            for (Slot s : slots) {
                if (s.id().equals(id)) {
                    return s;
                }
            }
            return null;
        }
    }

    public record Kind(String id, String label, List<String> words, List<Form> forms) {
        public Kind {
            words = List.copyOf(words);
            forms = List.copyOf(forms);
        }

        public Form form(String id) {
            for (Form f : forms) {
                if (f.id().equals(id)) {
                    return f;
                }
            }
            return null;
        }
    }

    /**
     * One declared word, parsed: {@code "dino~"} is a family word several entries share on purpose;
     * {@code "unicorn>parts=kit:unicorn_horn"} also picks in another slot. The phrase is lower-case words.
     */
    public record Term(String phrase, boolean family, Map<String, String> also) {
        public static Term parse(String word) {
            String[] parts = word.split(">");
            String phrase = parts[0].strip();
            boolean family = phrase.endsWith("~");
            if (family) {
                phrase = phrase.substring(0, phrase.length() - 1).strip();
            }
            Map<String, String> also = new LinkedHashMap<>();
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq < 1) {
                    throw new IllegalArgumentException("'" + word + "': after '>' comes slot=entry");
                }
                also.put(parts[i].substring(0, eq).strip(), parts[i].substring(eq + 1).strip());
            }
            if (!phrase.matches("[a-z0-9]+( [a-z0-9]+)*")) {
                throw new IllegalArgumentException("'" + word + "': a word is lower-case letters, digits and spaces");
            }
            return new Term(phrase, family, also);
        }
    }

    private final List<Kind> kinds;

    private BuilderTree(List<Kind> kinds) {
        this.kinds = List.copyOf(kinds);
    }

    public List<Kind> kinds() {
        return kinds;
    }

    public Kind kind(String id) {
        for (Kind k : kinds) {
            if (k.id().equals(id)) {
                return k;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the walk

    /** The tree of what {@code allow} offers, read off the menus. */
    public static BuilderTree of(BuilderAllowlist allow, LibraryPresets presets) {
        List<Kind> kinds = new ArrayList<>();
        for (PickPage.Tile kt : new BuilderFlow(allow, presets).kindTiles()) {
            BuilderSpec.Kind kind = BuilderSpec.Kind.of(kt.id());
            List<Form> forms = new ArrayList<>();
            if (kind == BuilderSpec.Kind.RULE) {
                forms.add(ruleForm(allow, presets));
            } else {
                Form ready = kind == BuilderSpec.Kind.BUILDING ? composedBuilding(allow, presets)
                        : kind == BuilderSpec.Kind.PICTURE ? composedPicture(allow, presets) : readyForm(allow, presets, kind);
                if (ready != null) {
                    forms.add(ready);
                }
                Form made = switch (kind) {
                    case ITEM -> madeUpItem(allow, presets);
                    case MACHINE -> composedSnack(allow, presets);
                    case MAGIC_BLOCK -> composedBlock(allow, presets);
                    case BUILDING -> simpleBuilding(allow, presets);   //: built from parts
                    case EVENT -> madeUpEvent(allow, presets);
                    case SUPPLIES -> supplies(allow, presets);   //
                    case CREATURE -> animals(allow, presets);   //: real farm animals
                    default -> null;
                };
                if (made != null) {
                    forms.add(made);
                }
            }
            kinds.add(new Kind(kt.id(), kt.label(), words(BuilderVocab.KINDS, kt.id()), forms));
        }
        return new BuilderTree(kinds);
    }

    private static BuilderFlow at(BuilderAllowlist allow, LibraryPresets presets, BuilderSpec.Kind kind) {
        BuilderFlow f = new BuilderFlow(allow, presets);
        f.press(kind.id());
        return f;
    }

    private static Form readyForm(BuilderAllowlist allow, LibraryPresets presets, BuilderSpec.Kind kind) {
        BuilderFlow f = at(allow, presets, kind);
        List<String> ids = f.fromIds(kind);
        if (ids.isEmpty()) {
            return null;
        }
        boolean creature = kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE;
        List<PickPage.Tile> parts = List.of();
        // the bodies that are not vehicles — the only ones a creature's parts, moods and moves go with.
        List<String> creatures = new ArrayList<>();
        List<Entry> from = new ArrayList<>();
        for (String id : ids) {
            Map<String, String> facts = new LinkedHashMap<>();
            if (creature) {
                BuilderFlow g = at(allow, presets, kind);
                g.press(id);
                if (!g.vehicle()) {
                    creatures.add(id);
                }
                parts = g.allPartTiles();
                JsonObject pre = presets.read(id);
                JsonArray body = pre != null && pre.has("parts") ? pre.getAsJsonArray("parts") : null;
                if (body != null) {
                    facts.put("partBudget", String.valueOf(BuilderParts.budget(body.size())));
                    for (PickPage.Tile pt : parts) {
                        facts.put("cost:" + pt.id(), String.valueOf(
                                BuilderParts.boxes(List.of(Catalogue.bare(pt.id())), body, "")));
                    }
                }
                List<String> natural = BuilderNatural.of(id, pre);
                if (!natural.isEmpty()) {
                    // What the body or starter already does (BuilderNatural: a dragon flies and never burns, a
                    // rabbit hops), so words that ask for it are met and it spends no power slot.
                    facts.put("has", String.join(",", natural));
                }
                if (pre != null && pre.has("powers") && pre.get("powers").toString().contains("\"rideable\"")) {
                    facts.put("ridden", "1");   // made to be ridden (a car, a horse): ranks it for "something to ride"
                }
            }
            from.add(new Entry(id, presets.label(id), words(BuilderVocab.PRESETS, id), Map.of(), facts));
        }
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("from", creature ? "Pick a body" : "Pick one to start from", 1, true, ids.get(0), false, from, List.of()));
        // The later slots are read off a walk through a CREATURE's pages (a vehicle's show fewer); every pick a
        // vehicle does not get says so with `only`, so the matcher never puts ears on a truck.
        BuilderFlow f2 = at(allow, presets, kind);
        f2.press(creature && !creatures.isEmpty() ? creatures.get(0) : ids.get(0));
        Map<String, List<String>> creatureOnly = creatures.size() == ids.size() ? Map.of() : Map.of("from", creatures);
        if (creature) {
            if (!parts.isEmpty() && !creatures.isEmpty()) {
                List<Entry> es = new ArrayList<>();
                for (PickPage.Tile t : parts) {
                    BuilderParts.Part part = BuilderParts.byId(Catalogue.bare(t.id()));
                    // Its kit: a styled part too big for a body can give way to the plain one of the same kit.
                    es.add(new Entry(t.id(), t.label(), part.words(), creatureOnly, Map.of("kit", part.kit())));
                }
                // any number — the body's room is the limit (Apprentice.problems asks BuilderLimits).
                slots.add(new Slot("parts", "Add parts", es.size(), false, "", false, es, List.of()));
            }
            if (f2.step() == BuilderFlow.Step.PARTS) {
                f2.press(PickPage.NEXT_ID);
            }
            List<Entry> powers = new ArrayList<>();
            List<Entry> tempers = new ArrayList<>();
            for (PickPage.Tile t : f2.powerTiles()) {
                if (t.id().startsWith("temper:")) {
                    String temper = t.id().substring(7);
                    // A vehicle stays friendly (the default it is made with); the other moods are a creature's.
                    tempers.add(new Entry(t.id(), t.label(), words(BuilderVocab.TEMPERS, temper),
                            temper.equals("friendly") ? Map.of() : creatureOnly, Map.of()));
                } else {
                    String bare = Catalogue.bare(t.id());
                    powers.add(new Entry(t.id(), t.label(), words(BuilderVocab.POWERS, bare),
                            BuilderParts.CREATURE_ONLY_POWERS.contains(bare) ? creatureOnly : Map.of(), Map.of()));
                }
            }
            slots.add(new Slot("powers", "Pick powers", Math.max(1, powers.size()), false, "", false, powers, List.of()));
            if (!tempers.isEmpty()) {
                slots.add(new Slot("temper", "How it behaves", 1, false, "temper:friendly", false, tempers, List.of()));
            }
            f2.press(PickPage.NEXT_ID);
            if (f2.step() == BuilderFlow.Step.ABILITIES) {
                List<Entry> es = new ArrayList<>();
                for (PickPage.Tile t : f2.abilityTiles()) {
                    es.add(new Entry(t.id(), t.label(), BuilderAbilities.byId(Catalogue.bare(t.id())).words(), Map.of(), Map.of()));
                }
                // any number, but one of each effect (BuilderLimits.CONFLICTS), so the matcher never offers two breaths.
                List<List<String>> moveClashes = new ArrayList<>();
                for (int i = 0; i < es.size(); i++) {
                    for (int j = i + 1; j < es.size(); j++) {
                        if (BuilderLimits.whyNot("abilities", List.of(Catalogue.bare(es.get(i).id())), Catalogue.bare(es.get(j).id())) != null) {
                            moveClashes.add(List.of(es.get(i).id(), es.get(j).id()));
                        }
                    }
                }
                slots.add(new Slot("abilities", "Pick special moves", Math.max(1, es.size()), false, "", false, es, moveClashes));
                f2.press(PickPage.NEXT_ID);
            }
        }
        slots.addAll(tuneSlots(f2));
        return new Form("ready", slots);
    }

    private static Form madeUpItem(BuilderAllowlist allow, LibraryPresets presets) {
        // the item kind starts at "What is it?" (its stock items); there is no ready-made page before it.
        BuilderFlow f = at(allow, presets, BuilderSpec.Kind.ITEM);
        if (f.stockTiles().isEmpty()) {
            return null;
        }
        List<Entry> shapes = new ArrayList<>();
        Map<String, Entry> powers = new LinkedHashMap<>();
        Map<String, List<String>> fits = new LinkedHashMap<>();
        List<Slot> tune = null;
        for (PickPage.Tile st : f.stockTiles()) {
            BuilderItemPowers.Stock stock = BuilderItemPowers.stock(st.id().substring(BuilderFlow.STOCK_PREFIX.length()));
            shapes.add(new Entry(st.id(), st.label(), stock.words(), Map.of(), Map.of("trigger", stock.shape())));
            BuilderFlow g = at(allow, presets, BuilderSpec.Kind.ITEM);
            g.press(st.id());
            for (PickPage.Tile pt : g.itemPowerTiles()) {
                fits.computeIfAbsent(pt.id(), k -> new ArrayList<>()).add(st.id());
                powers.putIfAbsent(pt.id(), new Entry(pt.id(), pt.label(),
                        BuilderItemPowers.power(pt.id().substring("itempower:".length())).words(), Map.of(), Map.of()));
            }
            if (tune == null && !g.itemPowerTiles().isEmpty()) {
                g.press(g.itemPowerTiles().get(0).id());
                tune = tuneSlots(g);
            }
        }
        List<Entry> ps = new ArrayList<>();
        String turn = "itempower:" + BuilderTurnInto.POWER;
        String show = "itempower:" + BuilderLooks.SHOW;
        if (powers.containsKey(show)) {
            // "Just for fun" FIRST, and the default only when a look is named ("a crown" is a crown and does
            // nothing): with no look it never fits, so an item with no power named still gets the first real one.
            ps.add(new Entry(show, powers.get(show).label(), powers.get(show).words(), Map.of("shape", fits.get(show)),
                    Map.of("defaultWhen", BuilderLooks.SLOT + ":showDefault")));
        }
        powers.forEach((id, e) -> {
            if (!id.equals(turn) && !id.equals(show)) {
                ps.add(new Entry(id, e.label(), e.words(), Map.of("shape", fits.get(id)), Map.of()));
            }
        });
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("shape", "What is it?", 1, true, shapes.get(0).id(), false, shapes, List.of()));
        // a LOOK from the icon library, behind "🎨 More looks…": each names the stock item it goes on (only), so
        // "a crown" is a helmet and "a cupcake" a snack, and makes "Just for fun" the power when none is named.
        List<Entry> looks = new ArrayList<>();
        BuilderFlow lf = at(allow, presets, BuilderSpec.Kind.ITEM);
        lf.press(BuilderLooks.MORE_ID);
        if (lf.step() == BuilderFlow.Step.ITEM_LOOK_GROUP) {
            for (PickPage.Tile gt : lf.lookGroupTiles()) {
                BuilderFlow lg = at(allow, presets, BuilderSpec.Kind.ITEM);
                lg.press(BuilderLooks.MORE_ID);
                lg.press(gt.id());
                for (PickPage.Tile lt : lg.lookTiles()) {
                    BuilderLooks.Look l = BuilderLooks.look(lt.id());
                    looks.add(new Entry(lt.id(), lt.label(), l.words(),
                            Map.of("shape", List.of(BuilderFlow.STOCK_PREFIX + l.stock())), Map.of("showDefault", "1")));
                }
            }
        }
        if (!looks.isEmpty()) {
            slots.add(new Slot(BuilderLooks.SLOT, "What does it look like?", 1, false, "", false, looks, List.of()));
        }
        List<Slot> turnSlots = List.of();
        if (powers.containsKey(turn)) {
            // "Turn things into…" LAST, so it is never the power an item with no power named gets; and its own two
            // pages, read off the menus: every From group any item offers, and every To any From can become here.
            ps.add(new Entry(turn, powers.get(turn).label(), powers.get(turn).words(), Map.of("shape", fits.get(turn)), Map.of()));
            turnSlots = turnSlots(allow, presets, fits.get(turn));
        }
        slots.add(new Slot("power", "What does it do?", 1, true, "", false, ps, List.of()));
        slots.addAll(turnSlots);
        slots.addAll(tune == null ? List.of() : tune);
        return new Form("made_up", slots);
    }

    /**
     * the From and To pages of "Turn things into…", walked on each item that offers it. Both are asked only for
     * that power (each entry goes only with it), so they are required only there; From has no default (the Apprentice
     * fills "Any block", the first), and To's is the furnace when every From has a furnace result and none is "Any block"
     * ({@code defaultWhen},: the Apprentice then takes the next fitting To, gold).
     */
    private static List<Slot> turnSlots(BuilderAllowlist allow, LibraryPresets presets, List<String> stocks) {
        String turn = "itempower:" + BuilderTurnInto.POWER;
        Map<String, List<String>> only = Map.of("power", List.of(turn));
        Map<String, Entry> from = new LinkedHashMap<>();
        Map<String, Entry> to = new LinkedHashMap<>();
        for (String stock : stocks) {
            BuilderFlow f = at(allow, presets, BuilderSpec.Kind.ITEM);
            f.press(stock);
            f.press(turn);
            for (PickPage.Tile t : f.turnFromTiles()) {
                BuilderTurnInto.From g = BuilderTurnInto.from(t.id());
                from.putIfAbsent(t.id(), new Entry(t.id(), t.label(), g.words(), only, BuilderTurnInto.facts(g)));
                BuilderFlow h = at(allow, presets, BuilderSpec.Kind.ITEM);
                h.press(stock);
                h.press(turn);
                h.press(t.id());
                h.press(PickPage.NEXT_ID);
                if (h.step() == BuilderFlow.Step.TURN_TO) {
                    for (PickPage.Tile tt : h.turnToTiles()) {
                        BuilderTurnInto.To target = BuilderTurnInto.to(tt.id());
                        to.putIfAbsent(tt.id(), new Entry(tt.id(), tt.label(), target.words(), only, BuilderTurnInto.facts(target)));
                    }
                }
            }
        }
        // The To page's order, whichever From found them first.
        List<Entry> tos = new ArrayList<>();
        for (BuilderTurnInto.To t : BuilderTurnInto.TO) {
            Entry e = to.get(BuilderTurnInto.TO_PREFIX + t.id());
            if (e != null) {
                tos.add(e);
            }
        }
        List<Entry> froms = new ArrayList<>();
        for (BuilderTurnInto.From g : BuilderTurnInto.FROM) {
            Entry e = from.get(BuilderTurnInto.FROM_PREFIX + g.id());
            if (e != null) {
                froms.add(e);
            }
        }
        if (froms.isEmpty() || tos.isEmpty()) {
            return List.of();
        }
        return List.of(
                new Slot(BuilderTurnInto.FROM_SLOT, BuilderTurnInto.FROM_TITLE, froms.size(), true, "", false, froms, List.of()),
                new Slot(BuilderTurnInto.TO_SLOT, BuilderTurnInto.TO_TITLE, 1, true, "", false, tos, List.of()));
    }

    private static Form composedSnack(BuilderAllowlist allow, LibraryPresets presets) {
        BuilderFlow f = at(allow, presets, BuilderSpec.Kind.MACHINE);
        if (!f.composerOpen()) {
            return null;
        }
        // the machine kind opens on its looks; there is no ready-made page before it.
        List<Entry> looks = new ArrayList<>();
        for (PickPage.Tile t : f.snackLookTiles()) {
            BuilderSnacks.Look l = BuilderSnacks.look(t.id());
            List<String> lw = new ArrayList<>(l.words());
            BuilderVocab.MACHINE_LOOKS.getOrDefault(l.id(), List.of()).forEach(w -> {
                if (!lw.contains(w)) {
                    lw.add(w);   //: what the stock machine was called ("pizza oven")
                }
            });
            looks.add(new Entry(t.id(), t.label(), lw, Map.of(), Map.of("ingredient", "ingredient:" + l.ingredient())));
        }
        f.press(looks.get(0).id());
        List<Entry> powers = new ArrayList<>();
        List<List<String>> clashes = new ArrayList<>();
        List<PickPage.Tile> pt = f.snackPowerTiles();
        for (PickPage.Tile t : pt) {
            powers.add(new Entry(t.id(), t.label(),
                    BuilderSnacks.power(t.id().substring("snackpower:".length())).words(), Map.of(), Map.of()));
        }
        for (int i = 0; i < pt.size(); i++) {
            for (int j = i + 1; j < pt.size(); j++) {
                if (BuilderSnacks.clash(pt.get(i).id().substring(11), pt.get(j).id().substring(11)) != null) {
                    clashes.add(List.of(pt.get(i).id(), pt.get(j).id()));
                }
            }
        }
        f.press(pt.get(0).id());
        f.press(PickPage.NEXT_ID);
        Set<String> seen = new LinkedHashSet<>();
        List<Entry> ingredients = new ArrayList<>();
        // Every look leads with its own ingredient; the list after it is the same, so one walk over the looks finds all.
        for (Entry look : looks) {
            BuilderFlow g = at(allow, presets, BuilderSpec.Kind.MACHINE);
            g.press(look.id());
            g.press(pt.get(0).id());
            g.press(PickPage.NEXT_ID);
            for (PickPage.Tile t : g.snackIngredientTiles()) {
                if (seen.add(t.id())) {
                    ingredients.add(new Entry(t.id(), t.label(), words(BuilderVocab.INGREDIENTS, Catalogue.bare(t.id())),
                            Map.of(), Map.of()));
                }
            }
        }
        f.press(f.snackIngredientTiles().get(0).id());
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("look", "What does your machine make?", 1, true, looks.get(0).id(), false, looks, List.of()));
        slots.add(new Slot("snack_powers", "What happens when you eat it?", BuilderLimits.SNACK_EFFECTS, true,
                powers.get(0).id(), false, powers, clashes));
        // Unnamed, the ingredient is the look's own (its "ingredient" fact), which is the first tile of its page.
        slots.add(new Slot("ingredient", "What goes in it?", 1, true, "", false, ingredients, List.of()));
        slots.addAll(tuneSlots(f));
        return new Form("composed", slots);
    }

    /**
     * a building, composed: the type (its "from" slot, as the ready-made was, so every typed word and judged
     * pick still names the same body) and what goes outside it, which only a type on the ground can have.
     */
    private static Form composedBuilding(BuilderAllowlist allow, LibraryPresets presets) {
        BuilderFlow f = at(allow, presets, BuilderSpec.Kind.BUILDING);
        List<PickPage.Tile> types = f.buildTypeTiles();
        if (types.isEmpty()) {
            return null;
        }
        List<Entry> from = new ArrayList<>();
        List<String> grounded = new ArrayList<>();
        List<Slot> tune = null;
        for (PickPage.Tile t : types) {
            if (t.id().startsWith("simple:")) {
                continue;   // built from parts: its own form (simpleBuilding)
            }
            from.add(new Entry(t.id(), presets.label(t.id()), words(BuilderVocab.PRESETS, t.id()), Map.of(), Map.of()));
            BuilderFlow g = at(allow, presets, BuilderSpec.Kind.BUILDING);
            g.press(t.id());
            if (g.step() == BuilderFlow.Step.BUILD_OUTSIDE) {
                grounded.add(t.id());
                if (tune == null) {
                    g.press("outside:" + BuilderBuildings.NOTHING);
                    tune = tuneSlots(g);
                }
            }
        }
        List<Entry> outside = new ArrayList<>();
        for (BuilderBuildings.Outside o : BuilderBuildings.OUTSIDE) {
            outside.add(new Entry("outside:" + o.id(), o.label(), o.words(),
                    o.id().equals(BuilderBuildings.NOTHING) ? Map.of() : Map.of("from", grounded), Map.of()));
        }
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("from", "What will you build?", 1, true, from.get(0).id(), false, from, List.of()));
        slots.add(new Slot("outside", "What goes outside?", BuilderBuildings.OUTSIDE.size() - 1, false, "outside:" + BuilderBuildings.NOTHING, false, outside, List.of()));
        // a WORKING build comes at its own size and in its own blocks (the menus offer it neither), so a size
        // word or a colour word is only ever for the other types: "a little farm" is the farm plot, never a small one.
        List<String> tunable = new ArrayList<>();
        for (Entry e : from) {
            BuilderBuildings.Type t = BuilderBuildings.type(e.id());
            if (t == null || !t.working()) {
                tunable.add(e.id());
            }
        }
        for (Slot s : tune == null ? List.<Slot>of() : tune) {
            if (tunable.size() == from.size() || !(s.id().equals("size") || s.id().equals("scheme"))) {
                slots.add(s);
                continue;
            }
            List<Entry> es = new ArrayList<>();
            for (Entry e : s.entries()) {
                boolean own = s.id().equals("size") && e.id().equals("2");
                es.add(own ? e : new Entry(e.id(), e.label(), e.words(), Map.of("from", tunable), e.facts()));
            }
            slots.add(new Slot(s.id(), s.label(), s.most(), s.required(), s.dflt(), s.steps(), es, s.clashes()));
        }
        return new Form("ready", slots);
    }

    /**
     * a picture, composed: the subject (its "from" slot, as the ready-made was, so every typed word and judged
     * pick still names the same drawing) and what is behind it, which a drawing that fills its canvas cannot have.
     */
    private static Form composedPicture(BuilderAllowlist allow, LibraryPresets presets) {
        BuilderFlow f = at(allow, presets, BuilderSpec.Kind.PICTURE);
        List<PickPage.Tile> subjects = f.subjectTiles();
        if (subjects.isEmpty()) {
            return null;
        }
        List<Entry> from = new ArrayList<>();
        List<String> open = new ArrayList<>();
        List<Slot> tune = null;
        for (PickPage.Tile t : subjects) {
            from.add(new Entry(t.id(), presets.label(t.id()), words(BuilderVocab.PRESETS, t.id()), Map.of(), Map.of()));
            BuilderFlow g = at(allow, presets, BuilderSpec.Kind.PICTURE);
            g.press(t.id());
            if (g.step() == BuilderFlow.Step.PICT_BACKGROUND) {
                open.add(t.id());
                if (tune == null) {
                    g.press("background:" + BuilderPictures.DRAWN);
                    tune = tuneSlots(g);
                }
            }
        }
        List<Entry> behind = new ArrayList<>();
        for (BuilderPictures.Background b : BuilderPictures.BACKGROUNDS) {
            behind.add(new Entry("background:" + b.id(), b.label(), b.words(),
                    b.id().equals(BuilderPictures.DRAWN) ? Map.of() : Map.of("from", open), Map.of()));
        }
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("from", "What will you paint?", 1, true, from.get(0).id(), false, from, List.of()));
        slots.add(new Slot("background", "What is behind it?", 1, false, "background:" + BuilderPictures.DRAWN, false, behind,
                List.of()));
        slots.addAll(tune == null ? List.of() : tune);
        return new Form("ready", slots);
    }

    /**
     * a simple building built from parts: its type (the "from" slot), roof, windows and what goes outside, then the
     * tune dialog's floors and colours. The door's side is left at its default (no typed word picks a side yet).
     */
    private static Form simpleBuilding(BuilderAllowlist allow, LibraryPresets presets) {
        BuilderFlow f = at(allow, presets, BuilderSpec.Kind.BUILDING);
        List<Entry> types = new ArrayList<>();
        for (PickPage.Tile t : f.buildTypeTiles()) {
            if (t.id().startsWith("simple:")) {
                types.add(new Entry(t.id(), t.label(), BuilderHouses.simple(t.id()).words(), Map.of(), Map.of()));
            }
        }
        if (types.isEmpty()) {
            return null;
        }
        f.press(types.get(0).id());
        List<Entry> roofs = new ArrayList<>();
        f.page().tiles().forEach(t -> roofs.add(new Entry(t.id(), t.label(), BuilderHouses.PART_WORDS.get(t.id().substring(5)),
                Map.of(), Map.of())));
        f.press(roofs.get(0).id());
        List<Entry> windows = new ArrayList<>();
        f.page().tiles().forEach(t -> windows.add(new Entry(t.id(), t.label(), BuilderHouses.PART_WORDS.get(t.id().substring(8)),
                Map.of(), Map.of())));
        f.press(windows.get(0).id());
        List<Entry> outside = new ArrayList<>();
        for (BuilderBuildings.Outside o : BuilderBuildings.OUTSIDE) {
            outside.add(new Entry("outside:" + o.id(), o.label(), o.words(), Map.of(), Map.of()));
        }
        f.press("outside:" + BuilderBuildings.NOTHING);
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("from", "What will you build?", 1, true, types.get(0).id(), false, types, List.of()));
        slots.add(new Slot("roof", "What kind of roof?", 1, true, roofs.get(0).id(), false, roofs, List.of()));
        slots.add(new Slot("windows", "How many windows?", 1, true, windows.get(0).id(), false, windows, List.of()));
        slots.add(new Slot("outside", "What goes outside?", BuilderBuildings.OUTSIDE.size() - 1, false, "outside:" + BuilderBuildings.NOTHING, false, outside, List.of()));
        slots.addAll(tuneSlots(f));
        return new Form("made_up", slots);
    }

    /**
     * a magic block, composed: what it is made of, what it does and what sets it off. The trigger's entries say
     * which effects they go with ({@code only}), so an unnamed trigger is the first that fits what it does.
     */
    private static Form composedBlock(BuilderAllowlist allow, LibraryPresets presets) {
        BuilderFlow f = at(allow, presets, BuilderSpec.Kind.MAGIC_BLOCK);
        if (f.blockLookTiles().isEmpty()) {
            return null;
        }
        List<Entry> looks = new ArrayList<>();
        for (PickPage.Tile t : f.blockLookTiles()) {
            looks.add(new Entry(t.id(), t.label(), BuilderBlocks.look(t.id()).words(), Map.of(), Map.of()));
        }
        f.press(looks.get(0).id());
        Map<String, Entry> does = new LinkedHashMap<>();
        Map<String, List<String>> fits = new LinkedHashMap<>();
        List<Entry> whens = new ArrayList<>();
        List<Slot> tune = null;
        for (PickPage.Tile wt : f.blockWhenTiles()) {
            BuilderFlow g = at(allow, presets, BuilderSpec.Kind.MAGIC_BLOCK);
            g.press(looks.get(0).id());
            g.press(wt.id());
            List<String> doesHere = new ArrayList<>();
            for (PickPage.Tile dt : g.blockDoesTiles()) {
                doesHere.add(dt.id());
                fits.computeIfAbsent(dt.id(), k -> new ArrayList<>()).add(wt.id());
                does.putIfAbsent(dt.id(), new Entry(dt.id(), dt.label(), BuilderBlocks.does(dt.id()).words(), Map.of(), Map.of()));
            }
            whens.add(new Entry(wt.id(), wt.label(), BuilderBlocks.when(wt.id()).words(), Map.of("power", doesHere), Map.of()));
            if (tune == null && !doesHere.isEmpty()) {
                g.press(doesHere.get(0));
                tune = tuneSlots(g);
            }
        }
        List<Entry> ds = new ArrayList<>();
        does.forEach((id, e) -> ds.add(new Entry(id, e.label(), e.words(), Map.of("trigger", fits.get(id)), Map.of())));
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("material", "What is your block made of?", 1, true, looks.get(0).id(), false, looks, List.of()));
        // Each says what it goes with: unnamed, a trigger is the first that fits what it does (a lucky surprise: when he
        // breaks it), and what it does is the first that fits a named trigger.
        slots.add(new Slot("power", "What does it do?", 1, true, "", false, ds, List.of()));
        slots.add(new Slot("trigger", "When does it work?", 1, true, "", false, whens, List.of()));
        slots.addAll(tune == null ? List.of() : tune);
        return new Form("made_up", slots);
    }

    /**
     * supplies, as one "from" slot of every supply this world offers (the category page is only how the menus
     * group them: each entry says its category in {@code facts}), then the tune dialog's how many. Its form is "ready",
     * so its anchor is the supply itself, as a ready-made's was.
     */
    private static Form supplies(BuilderAllowlist allow, LibraryPresets presets) {
        BuilderFlow f = at(allow, presets, BuilderSpec.Kind.SUPPLIES);
        List<Entry> from = new ArrayList<>();
        List<Slot> tune = null;
        for (PickPage.Tile ct : f.supplyCategoryTiles()) {
            for (PickPage.Tile st : f.supplyTiles(Catalogue.bare(ct.id()))) {
                BuilderSupplies.Supply s = BuilderSupplies.supply(st.id());
                int top = BuilderSupplies.top(s, allow.supplies());
                // How many it may be here and how many it is when unnamed, so a matcher never says "16 crafting tables".
                from.add(new Entry(st.id(), st.label(), s.words(), Map.of(), Map.of("category", ct.id(),
                        "most", String.valueOf(top == 1 ? 1 : BuilderSupplies.sliderTop(top)),
                        "usual", String.valueOf(top == 1 ? 1 : BuilderSupplies.fit(s.dflt(), top)))));
                if (tune == null && BuilderSupplies.top(s, allow.supplies()) > 1) {
                    BuilderFlow g = at(allow, presets, BuilderSpec.Kind.SUPPLIES);
                    g.press(ct.id());
                    if (g.step() == BuilderFlow.Step.SUPPLY_WHAT) {
                        g.press(st.id());
                    }
                    tune = tuneSlots(g);
                }
            }
        }
        if (from.isEmpty()) {
            return null;
        }
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("from", "What do you need?", 1, true, from.get(0).id(), false, from, List.of()));
        for (Slot s : tune == null ? List.<Slot>of() : tune) {
            // Unnamed, how many is each supply's own (16 logs, 1 crafting table), never the first supply's.
            slots.add(s.id().equals("amount")
                    ? new Slot(s.id(), s.label(), s.most(), false, "", true, s.entries(), s.clashes()) : s);
        }
        return new Form("ready", slots);
    }

    /**
     * a real vanilla farm animal, its own form ("animal"): one "from" slot of the animals this world allows (the
     * creature page's last tiles), then the tune dialog's how many. It takes nothing else: no parts, powers, mood, size or
     * colours, so the matcher can never offer "a glowing cow". Each entry says its own word for one and for more.
     */
    private static Form animals(BuilderAllowlist allow, LibraryPresets presets) {
        BuilderFlow f = at(allow, presets, BuilderSpec.Kind.CREATURE);
        List<String> ids = f.animalIds();
        if (ids.isEmpty()) {
            return null;
        }
        List<Entry> from = new ArrayList<>();
        for (String id : ids) {
            String m = id.substring(id.lastIndexOf(':') + 1);
            from.add(new Entry(id, BuilderFlow.animalLabel(id), words(BuilderVocab.FARM_ANIMALS, m), Map.of(),
                    Map.of("one", BuilderFlow.animalWord(m, 1), "many", BuilderFlow.animalWord(m, 2))));
        }
        BuilderFlow g = at(allow, presets, BuilderSpec.Kind.CREATURE);
        g.press(ids.get(0));
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("from", "Real farm animals", 1, true, ids.get(0), false, from, List.of()));
        slots.addAll(tuneSlots(g));
        return new Form("animal", slots);
    }

    private static Form madeUpEvent(BuilderAllowlist allow, LibraryPresets presets) {
        BuilderFlow f = at(allow, presets, BuilderSpec.Kind.EVENT);
        if (f.step() != BuilderFlow.Step.EVENT_MOTION) {   //: the event kind opens on "What happens?"
            return null;
        }
        List<Entry> motions = new ArrayList<>();
        Map<String, Entry> whats = new LinkedHashMap<>();
        Map<String, List<String>> fits = new LinkedHashMap<>();
        Map<String, List<String>> whatWords = new LinkedHashMap<>();
        List<Slot> tune = null;
        for (PickPage.Tile mt : f.eventMotionTiles()) {
            BuilderEvents.Motion m = BuilderEvents.byId(mt.id().substring("event:".length()));
            motions.add(new Entry(mt.id(), mt.label(), m.words(), Map.of(), Map.of()));
            for (BuilderEvents.What w : m.whats()) {
                String id = "what:" + w.id();
                fits.computeIfAbsent(id, k -> new ArrayList<>()).add(mt.id());
                // One block can be two things ("Slime" in a wave, "A bounce pit" under his feet): the words add up.
                List<String> ws = whatWords.computeIfAbsent(id, k -> new ArrayList<>());
                for (String word : w.words()) {
                    if (!ws.contains(word)) {
                        ws.add(word);
                    }
                }
                whats.putIfAbsent(id, new Entry(id, w.label(), w.words(), Map.of(), Map.of()));
            }
            if (tune == null) {
                BuilderFlow g = at(allow, presets, BuilderSpec.Kind.EVENT);
                g.press(mt.id());
                if (g.step() == BuilderFlow.Step.EVENT_WHAT) {
                    g.press(g.eventWhatTiles().get(0).id());
                }
                tune = tuneSlots(g);
            }
        }
        // Which motions this is the FIRST choice of, so an unnamed one is the motion's own first (a twister is
        // wind, a hurricane storm clouds) and never whichever motion happened to list a shared block earlier.
        Map<String, List<String>> firstOf = new LinkedHashMap<>();
        for (PickPage.Tile mt : f.eventMotionTiles()) {
            BuilderEvents.Motion m = BuilderEvents.byId(mt.id().substring("event:".length()));
            if (!m.whats().isEmpty()) {
                firstOf.computeIfAbsent("what:" + m.whats().get(0).id(), k -> new ArrayList<>()).add(mt.id());
            }
        }
        List<Entry> ws = new ArrayList<>();
        whats.forEach((id, e) -> ws.add(new Entry(id, e.label(), whatWords.get(id), Map.of("motion", fits.get(id)),
                firstOf.containsKey(id) ? Map.of(Apprentice.FIRST_FOR, String.join(",", firstOf.get(id))) : Map.of())));
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("motion", "What happens?", 1, true, motions.get(0).id(), false, motions, List.of()));
        // Unnamed, it is the motion's first (a wave is water, it rains chickens): the first entry that fits.
        slots.add(new Slot("what", "What is it made of?", 1, true, "", false, ws, List.of()));
        slots.addAll(tune == null ? List.of() : tune);
        // How a spinning thing moves — the tune dialog asks it of a vortex only, so its entries fit those motions.
        List<String> spinning = motions.stream().map(Entry::id)
                .filter(id -> BuilderEvents.spins(id.substring("event:".length()))).toList();
        if (!spinning.isEmpty()) {
            List<Entry> paths = new ArrayList<>();
            for (BuilderEvents.PathChoice p : BuilderEvents.PATHS) {
                if (!p.id().equals(BuilderEvents.OWN_WAY)) {
                    paths.add(new Entry(p.id(), p.label(), p.words(), Map.of("motion", spinning), Map.of()));
                }
            }
            slots.add(new Slot("path", "How does it move?", 1, false, "", false, paths, List.of()));
        }
        // What a wave or a spinning thing spares; unnamed, it wrecks everything.
        List<String> wrecking = motions.stream().map(Entry::id)
                .filter(id -> BuilderEvents.wrecks(id.substring("event:".length()))).toList();
        if (!wrecking.isEmpty()) {
            List<Entry> wrecks = new ArrayList<>();
            for (BuilderEvents.WreckChoice c : BuilderEvents.WRECKS) {
                if (!c.id().equals(BuilderEvents.WRECKS_ALL)) {
                    wrecks.add(new Entry(c.id(), c.label(), c.words(), Map.of("motion", wrecking), Map.of()));
                }
            }
            slots.add(new Slot("wreck", "What does it wreck?", 1, false, "", false, wrecks, List.of()));
        }
        return new Form("made_up", slots);
    }

    private static Form ruleForm(BuilderAllowlist allow, LibraryPresets presets) {
        BuilderFlow f = at(allow, presets, BuilderSpec.Kind.RULE);
        List<Entry> whens = new ArrayList<>();
        Map<String, Entry> whos = new LinkedHashMap<>();
        Map<String, String> doesLabel = new LinkedHashMap<>();
        Map<String, Set<String>> doesWhen = new LinkedHashMap<>();
        Map<String, Set<String>> doesWho = new LinkedHashMap<>();
        List<String> mobWhens = new ArrayList<>();
        for (PickPage.Tile wt : f.whenTiles()) {
            whens.add(new Entry(wt.id(), wt.label(), words(BuilderVocab.TRIGGERS, Catalogue.bare(wt.id())), Map.of(), Map.of()));
            BuilderFlow g = at(allow, presets, BuilderSpec.Kind.RULE);
            g.press(wt.id());
            if (g.step() == BuilderFlow.Step.WHO) {
                mobWhens.add(wt.id());
                for (PickPage.Tile mt : g.whoTiles()) {
                    whos.putIfAbsent(mt.id(), new Entry(mt.id(), mt.label(),
                            words(BuilderVocab.RULE_MOBS, mt.id().substring("mob:minecraft:".length())), Map.of(), Map.of()));
                    BuilderFlow h = at(allow, presets, BuilderSpec.Kind.RULE);
                    h.press(wt.id());
                    h.press(mt.id());
                    for (PickPage.Tile dt : h.doesTiles()) {
                        doesLabel.putIfAbsent(dt.id(), dt.label());
                        doesWhen.computeIfAbsent(dt.id(), k -> new LinkedHashSet<>()).add(wt.id());
                        doesWho.computeIfAbsent(dt.id(), k -> new LinkedHashSet<>()).add(mt.id());
                    }
                }
            } else {
                for (PickPage.Tile dt : g.doesTiles()) {
                    doesLabel.putIfAbsent(dt.id(), dt.label());
                    doesWhen.computeIfAbsent(dt.id(), k -> new LinkedHashSet<>()).add(wt.id());
                }
            }
        }
        List<Entry> who = new ArrayList<>();
        whos.forEach((id, e) -> who.add(new Entry(id, e.label(), e.words(), Map.of("when", mobWhens), Map.of())));
        List<Entry> does = new ArrayList<>();
        doesLabel.forEach((id, label) -> {
            Map<String, List<String>> only = new LinkedHashMap<>();
            only.put("when", new ArrayList<>(doesWhen.get(id)));
            // An effect only some mobs can show (a gold hat) says which; one any mob can show says nothing.
            if (doesWho.containsKey(id) && doesWho.get(id).size() < whos.size()) {
                only.put("who", new ArrayList<>(doesWho.get(id)));
            }
            does.add(new Entry(id, label, RuleEffects.byId(id).words(), only, Map.of()));
        });
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("when", "When does it happen?", 1, true, whens.get(0).id(), false, whens, List.of()));
        if (!who.isEmpty()) {
            // Asked only for a trigger about a mob; unnamed, it is the first mob on the page.
            slots.add(new Slot("who", "Which animal or monster?", 1, false, who.get(0).id(), false, who, List.of()));
        }
        slots.add(new Slot("does", "What happens?", 1, true, "", false, does, List.of()));
        return new Form("rule", slots);
    }

    /** The slots a flow's tune dialog asks, for a flow that has reached it: sliders as steps, the colours as a pick. */
    private static List<Slot> tuneSlots(BuilderFlow atTune) {
        List<Slot> out = new ArrayList<>();
        if (atTune.step() != BuilderFlow.Step.TUNE) {
            throw new IllegalStateException("the walk did not reach the tune dialog (at " + atTune.step() + ")");
        }
        List<TuneDialog.Input> inputs = new ArrayList<>(atTune.tune().inputs());
        if (atTune.typedOnlyCount() != null) {
            inputs.add(atTune.typedOnlyCount());   // #103: not on the dialog, still said in typed words
        }
        for (TuneDialog.Input in : inputs) {
            if (in instanceof TuneDialog.Choice c && c.key().equals("strength")) {
                // #103: a creature's strength, its three steps in his words and the strength words items use
                List<Entry> es = new ArrayList<>();
                for (TuneDialog.Option o : c.options()) {
                    List<String> said = new ArrayList<>(BuilderVocab.STEPS.getOrDefault("strength:" + o.id(), List.of()));
                    String own = o.display().toLowerCase(java.util.Locale.ROOT);
                    if (!said.contains(own)) {
                        said.add(0, own);
                    }
                    es.add(new Entry(o.id(), o.display(), said, Map.of(), Map.of()));
                }
                out.add(new Slot("strength", c.label(), 1, false, c.initial(), true, es, List.of()));
                continue;
            }
            if (in instanceof TuneDialog.Slider s) {
                // a ready-made building's size slider is cut to the sizes that work FOR THAT TYPE, and the tree
                // is one per kind — so the words keep every size, and the flow moves the size he named into the ones
                // the type is offered (BuilderSizes.clamp in BuilderFlow.spec).
                int start = s.key().equals("size") ? 1 : s.start();
                int end = s.key().equals("size") ? 4 : s.end();
                List<Entry> es = new ArrayList<>();
                if (s.key().equals("count")) {   //: the counts said by name, up to this world's most (BuilderCounts)
                    List<Integer> steps = BuilderCounts.steps(end);
                    for (int v : steps) {
                        List<String> said = new ArrayList<>(BuilderVocab.STEPS.getOrDefault("count:" + v, List.of()));
                        // past five, the digits only: "forty" typed is read by the number reader (Apprentice.numbers),
                        // and a spelled-out step here is one more word a typo could be respelt into ("forrets")
                        if (!said.contains(String.valueOf(v))) {
                            said.add(0, String.valueOf(v));
                        }
                        if (v == steps.get(steps.size() - 1)) {   //: an army, a swarm: as many as there may be
                            BuilderVocab.COUNT_MOST.stream().filter(w -> !said.contains(w)).forEach(said::add);
                        }
                        es.add(new Entry(String.valueOf(v), String.valueOf(v), said, Map.of(), Map.of()));
                    }
                    out.add(new Slot(s.key(), s.label(), 1, false, String.valueOf(s.initial()), true, es, List.of()));
                    continue;
                }
                for (int v = start; v <= end; v += s.step()) {
                    List<String> said = new ArrayList<>(BuilderVocab.STEPS.getOrDefault(s.key() + ":" + v, List.of()));
                    if (s.key().equals("amount")) {   //: every amount, one at a time, is said as itself
                        for (String w : BuilderVocab.number(v)) {
                            if (!said.contains(w)) {
                                said.add(0, w);
                            }
                        }
                    }
                    es.add(new Entry(String.valueOf(v), String.valueOf(v), said, Map.of(), Map.of()));
                }
                out.add(new Slot(s.key(), s.label(), 1, false, String.valueOf(s.initial()), true, es, List.of()));
            } else if (in instanceof TuneDialog.Choice c && c.key().equals("mobsize")) {
                // a real creature's named sizes, read like a slider's steps (never as things: "giant")
                List<Entry> es = new ArrayList<>();
                for (BuilderMobSizes.Level l : BuilderMobSizes.ALL) {
                    es.add(new Entry(l.id(), l.name(), l.words(), Map.of(), Map.of("scale", String.valueOf(l.scale()))));
                }
                // No default: unnamed is the game's own size, and a make's key stays what it was before sizes.
                out.add(new Slot("mobsize", c.label(), 1, false, "", true, es, List.of()));
            } else if (in instanceof TuneDialog.Choice c && c.key().equals(BuilderActs.SLOT)) {
                // how a real creature acts; unnamed is the game's own, so a make's key stays what it was
                List<Entry> es = new ArrayList<>();
                for (BuilderActs.Act a : BuilderActs.ALL) {
                    if (!a.id().equals(BuilderActs.GAME)) {
                        es.add(new Entry(a.id(), a.label(), a.words(), Map.of(), Map.of()));
                    }
                }
                out.add(new Slot(BuilderActs.SLOT, c.label(), 1, false, "", false, es, List.of()));
            } else if (in instanceof TuneDialog.Choice c && c.key().equals("scheme")) {
                List<Entry> es = new ArrayList<>();
                for (TuneDialog.Option o : c.options()) {
                    Schemes.Scheme sc = Schemes.byId(o.id());
                    if (sc != null) {
                        es.add(new Entry(o.id(), o.display(), sc.words(), Map.of(), Map.of()));
                    }
                }
                out.add(new Slot("scheme", c.label(), 1, false, c.initial(), false, es, List.of()));
            }
            // The name is never matched from typed words (OPEN_DOOR_PLAN §7.3), and the dialog asks none: Moddy names it.
        }
        return out;
    }

    private static List<String> words(Map<String, List<String>> table, String id) {
        return table.getOrDefault(id, List.of());
    }

    // ------------------------------------------------------------------ the export

    /** The generated export: the whole tree as JSON, the same on every run (no clock, no order from a hash). */
    /**
     * This tree's fingerprint: the hash {@code ApprenticeTreeFingerprintTest} records (a SHA-256 over every kind, slot
     * and pick, each hashed as the export writes it), so "the same tree" means the same here as there
     * ({@code BuilderPageFactsTest} checks the two agree). What a world's page facts are kept under (#14).
     */
    public String fingerprint() {
        java.util.Map<String, String> kinds = new java.util.TreeMap<>();
        java.util.Map<String, String> slots = new java.util.TreeMap<>();
        java.util.Map<String, String> picks = new java.util.TreeMap<>();
        for (com.google.gson.JsonElement ke : toJson().getAsJsonArray("kinds")) {
            JsonObject k = ke.getAsJsonObject();
            String kid = k.get("id").getAsString();
            kinds.put(kid, sha256(without(k, "forms").toString()).substring(0, 12));
            for (com.google.gson.JsonElement fe : k.getAsJsonArray("forms")) {
                JsonObject f = fe.getAsJsonObject();
                String fid = kid + "/" + f.get("id").getAsString();
                for (com.google.gson.JsonElement se : f.getAsJsonArray("slots")) {
                    JsonObject s = se.getAsJsonObject();
                    String sid = fid + "/" + s.get("id").getAsString();
                    slots.put(sid, sha256(without(s, "entries").toString()).substring(0, 12));
                    for (com.google.gson.JsonElement ee : s.getAsJsonArray("entries")) {
                        picks.put(sid + "/" + ee.getAsJsonObject().get("id").getAsString(),
                                sha256(ee.toString()).substring(0, 12));
                    }
                }
            }
        }
        StringBuilder all = new StringBuilder();
        kinds.forEach((k, v) -> all.append("kind|").append(k).append('|').append(v).append('\n'));
        slots.forEach((k, v) -> all.append("slot|").append(k).append('|').append(v).append('\n'));
        picks.forEach((k, v) -> all.append("pick|").append(k).append('|').append(v).append('\n'));
        return sha256(all.toString());
    }

    private static JsonObject without(JsonObject o, String key) {
        JsonObject copy = new JsonObject();
        for (String k : o.keySet()) {
            if (!k.equals(key)) {
                copy.add(k, o.get(k));
            }
        }
        return copy;
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "GENERATED by BuilderTreeTest from the Builder's own menus (BuilderTree.of). "
                + "Never edit: change the entry where it is defined (its record, or BuilderVocab).");
        root.addProperty("version", VERSION);
        root.addProperty("minWords", MIN_WORDS);
        JsonArray ks = new JsonArray();
        for (Kind k : kinds) {
            JsonObject kj = new JsonObject();
            kj.addProperty("id", k.id());
            kj.addProperty("label", k.label());
            kj.add("words", strings(k.words()));
            JsonArray fs = new JsonArray();
            for (Form f : k.forms()) {
                JsonObject fj = new JsonObject();
                fj.addProperty("id", f.id());
                JsonArray ss = new JsonArray();
                for (Slot s : f.slots()) {
                    JsonObject sj = new JsonObject();
                    sj.addProperty("id", s.id());
                    sj.addProperty("label", s.label());
                    sj.addProperty("most", s.most());
                    sj.addProperty("required", s.required());
                    sj.addProperty("default", s.dflt());
                    sj.addProperty("steps", s.steps());
                    if (!s.clashes().isEmpty()) {
                        JsonArray cs = new JsonArray();
                        s.clashes().forEach(c -> cs.add(strings(c)));
                        sj.add("clashes", cs);
                    }
                    JsonArray es = new JsonArray();
                    for (Entry e : s.entries()) {
                        JsonObject ej = new JsonObject();
                        ej.addProperty("id", e.id());
                        ej.addProperty("label", e.label());
                        ej.add("words", strings(e.words()));
                        if (!e.only().isEmpty()) {
                            JsonObject o = new JsonObject();
                            new java.util.TreeMap<>(e.only()).forEach((slot, vals) -> o.add(slot, strings(vals)));
                            ej.add("only", o);
                        }
                        if (!e.facts().isEmpty()) {
                            JsonObject o = new JsonObject();
                            new java.util.TreeMap<>(e.facts()).forEach(o::addProperty);
                            ej.add("facts", o);
                        }
                        es.add(ej);
                    }
                    sj.add("entries", es);
                    ss.add(sj);
                }
                fj.add("slots", ss);
                fs.add(fj);
            }
            kj.add("forms", fs);
            ks.add(kj);
        }
        root.add("kinds", ks);
        root.add("counts", BuilderCounts.toJson());   //: each class's most, for the Python twin
        root.add("colours", Colours.toJson());          //: the solids, their leads and the three-tone hues
        return root;
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    /** A phrase as the matcher and the checks compare it: lower-case, single spaces. */
    public static String normal(String phrase) {
        return phrase.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }
}
