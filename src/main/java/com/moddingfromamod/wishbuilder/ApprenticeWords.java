package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ui.PickPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>What the Apprentice says and shows</b>. Apprentice mode ALWAYS says it is Apprentice mode: the page's title, Moddy's first line and every tile say these are quick makes from Moddy's
 * Parts, so nobody mistakes one for what a Wish Magician's wish does. Pure; {@code ApprenticeWordsTest}.
 */
public final class ApprenticeWords {

    private ApprenticeWords() {
    }

    /** The pick page's title. */
    public static final String TITLE = "✨ Moddy's Apprentice";
    /** Moddy's first line, every time the page opens. */
    public static final String HELLO = "✨ Moddy's Apprentice found these in Moddy's Parts. Pick one and Moddy makes it.";
    /** Added when some of his words found nothing: the tiles are the nearest the Builder has. */
    public static final String CLOSEST = "These are the closest it has.";
    /**
     * Moddy's first line when the trained layer supplied a pick of the first tile: it says Moddy's Parts are
     * a set to pick from and names what it took as nearest. WORKING TEXT: these words are not final.
     */
    public static final String GUESSED = "✨ Moddy's Apprentice picks from Moddy's Parts. The closest it has: %s. Pick one and Moddy makes it.";
    /** A tile's second line, from these three and no others. */
    public static final String TAG_JUST = "Just what you said";
    public static final String TAG_CLOSEST = "Closest to your words";
    public static final String TAG_LEAVES_OUT = "Leaves out part of your wish";
    /**
     * what a tile leaves out, NAMED, in his own words after typo repair ("sorry, can't do
     * horns"), and a stand-in said as one. WORKING TEXT: these words are not final.
     */
    public static final String CANT_DO = "Can't do: %s";
    public static final String CLOSEST_FOR = "Closest it has: %s (for %s)";
    /** a count fitted down to the world's most says so: "25 flyers is the most at once here (for 50)". */
    public static final String MOST_FOR = "%s (for %s)";
    /** At most this many words are named, then "and more": a Bedrock tile line is short. */
    public static final int NOTE_WORDS = 3;
    /**
     * The line under {@link #TAG_LEAVES_OUT}: a tile makes its thing at once, so what it leaves out
     * is added after, in the Builder. Its own line so a Bedrock tile does not cut it.
     */
    public static final String TAG_CHANGE_AFTER = "You can change it after.";
    /**
     * Said when an Apprentice make stands and its thing can be changed (not a real mob): where, and that the first
     * change is free until his next wish ({@link FreeEdit}). No mouse button is named: a press opens the
     * thing's page on every edition.
     */
    public static final String CHANGE_IT_FREE =
            "Want to change it? Press it on your watch, then ✦ Change it. Free until you wish again.";

    /** The first line for this answer. */
    public static String hello(BuilderTree tree, Apprentice.Answer answer) {
        if (answer.guessed(0)) {
            Apprentice.Pick top = answer.offers().get(0);
            BuilderTree.Form form = tree.kind(top.kind()).form(top.form());
            List<String> said = new ArrayList<>();
            for (String key : answer.layer().get(0)) {
                int eq = key.indexOf('=');
                said.add(low(labelOf(form, key.substring(0, eq), key.substring(eq + 1))));
            }
            return String.format(GUESSED, String.join(", ", said));
        }
        return HELLO + (answer.reach() == Apprentice.Reach.PART ? " " + CLOSEST : "");
    }

    /**
     * What tile {@code i} is to his words: the layer's nearest pick, all of what he said, or a make that leaves
     * some of it out. Null for a further reading of words that were all read.
     */
    public static String tag(Apprentice.Answer answer, int i) {
        if (answer.guessed(i)) {
            return TAG_CLOSEST;
        }
        if (answer.reach() == Apprentice.Reach.IN) {
            return i == 0 ? TAG_JUST : null;
        }
        return TAG_LEAVES_OUT;
    }

    /**
     * a Magician's words go straight to the Magician when the Apprentice is not sure (when
     * the Apprentice has no good recommendation). WORKING TEXT: not final.
     */
    public static final String TO_THE_MAGICIAN = "✪ Moddy will wish that one from scratch.";
    /** a Crafter named his thing; only a Wish Magician's names are kept (nothing a Crafter types is). */
    public static final String NAME_MAGICIAN = "✪ A Wish Magician can give it the name you chose. Here's what I can make:";

    /** "Can't do: horns, wings and more": at most {@link #NOTE_WORDS} of his words, each once. */
    public static String cantDo(List<String> words) {
        List<String> once = new ArrayList<>(new java.util.LinkedHashSet<>(words));
        String named = String.join(", ", once.subList(0, Math.min(NOTE_WORDS, once.size())));
        return String.format(CANT_DO, named + (once.size() > NOTE_WORDS ? " and more" : ""));
    }

    /** Is this lore line the one that says a tile leaves something out (named or not)? */
    public static boolean leavesOut(String line) {
        return TAG_LEAVES_OUT.equals(line) || line.startsWith(CANT_DO.substring(0, CANT_DO.indexOf('%')));
    }

    /** Nothing he named is in Moddy's Parts. Honest: no tile is stretched to fit. */
    public static final String OUT = "✨ That one isn't in Moddy's Parts, so Moddy's Apprentice has no tile for it.";
    /** No tile fits and the Magician is off (#78): what he can do instead, in a child's words. */
    public static final String NO_MAGICIAN_TRY = "✨ Try other words, or build it yourself in the 🛠 Wish Builder on your watch!";
    /** …and for the host only, where the Magician is turned on; {@link #HOST_TURN_ON} is the link that opens it. */
    public static final String HOST_MAGICIAN_OFF = "✪ The Magician is off. A grown-up can ";
    public static final String HOST_TURN_ON = "turn it on";
    /** The last tile, for a Crafter. */
    public static final String NONE_LABEL = "None of these";
    public static final List<String> NONE_LORE = List.of("Not what you meant?", "Press to see who can");
    /** "None of these", pressed by a Crafter: honest, and it collects nothing. */
    public static final String NONE_SAID = "No problem! The Wish Builder on your watch has all of Moddy's Parts.";
    /** The capped nudge after it: what a Wish Magician could do with those exact words. The link follows. */
    public static final String UPSELL = "A Wish Magician could wish for exactly what you typed. Grown-ups (18+) can become one at ";
    /** The last tile, for a Magician in Apprentice mode: his words go to the agent instead. */
    public static final String MAGICIAN_INSTEAD_LABEL = "✪ Moddy wishes it from scratch";   //
    public static final List<String> MAGICIAN_INSTEAD_LORE = List.of("Magician magic makes", "exactly what you typed");
    /** A world whose Builder is off has no Parts to pick from. */
    public static final String BUILDER_OFF = "✨ Moddy's Apprentice isn't open in this world yet. Your watch has ready-made wishes for you!";
    /** Each offer tile's lore: what it is, then the price where wishes cost something. */
    public static final String TILE_LORE = "Press to make it";
    public static final String HALF_WISH = "½ wish";
    public static final String WHOLE_WISH = "1 wish";

    /** The wish box's label, by who will answer (shown with the price on a world with a wish bank). */
    public static String boxLabel(boolean apprentice, boolean priced) {
        return apprentice
                ? "What do you wish for? ✨ Moddy's Apprentice finds it in Moddy's Parts" + (priced ? " (" + HALF_WISH + ")." : ".")
                : "What do you wish for? Type anything you can imagine." + (priced ? " ✪ Magician magic (" + WHOLE_WISH + ")." : "");
    }

    /** The watch toggle's label and lore, by the mode it is in now. */
    public static String modeLabel(boolean apprentice) {
        return apprentice ? "Typed wishes: Apprentice ✨" : "Typed wishes: Magician ✪";
    }

    public static List<String> modeLore(boolean apprentice) {
        return apprentice
                ? List.of("Quick Builder makes, ½ wish", "Press for Magician ✪ magic")
                : List.of("Moddy makes anything, 1 wish", "Press for Apprentice ✨ magic");
    }

    public static String modeSaid(boolean apprentice) {
        return apprentice
                ? "✨ Moddy's Apprentice now answers your typed wishes: quick makes from Moddy's Parts, half a wish each."
                : "✪ Your typed wishes now use Magician magic: Moddy makes anything you can imagine.";
    }

    /** One offer as a tile's words: "Forest T-Rex to ride", "A sword: lightning". At most {@link #LABEL_MAX}. */
    public static String label(BuilderTree tree, Apprentice.Pick pick) {
        Apprentice.Pick full = Apprentice.filled(tree, pick);
        BuilderTree.Form form = tree.kind(pick.kind()).form(pick.form());
        String size = switch (full.first("size")) {
            case "1" -> "small ";
            case "3" -> "big ";
            case "4" -> "giant ";
            default -> "";
        };
        if (!full.first("mobsize").isEmpty() && !pick.form().equals("animal")) {   //: a body's named size
            size = full.first("mobsize").equals(BuilderMobSizes.NORMAL) ? ""
                    : BuilderMobSizes.of(full.first("mobsize")).name().toLowerCase(Locale.ROOT) + " ";
        }
        String scheme = full.first("scheme").isEmpty() ? "" : labelOf(form, "scheme", full.first("scheme")) + " ";
        String many = full.first("count").isEmpty() || full.first("count").equals("1") ? "" : full.first("count") + " × ";
        String out;
        if (pick.form().equals("animal")) {   //: "2 real cows", "A real sheep"
            BuilderTree.Entry e = form.slot("from").entry(full.first("from"));
            int n = full.first("count").isEmpty() ? 1 : Integer.parseInt(full.first("count"));
            String word = e == null ? "animal" : e.facts().getOrDefault(n == 1 ? "one" : "many", "animal");
            String sized = full.first("mobsize").isEmpty() || full.first("mobsize").equals(BuilderMobSizes.NORMAL) ? ""
                    : BuilderMobSizes.of(full.first("mobsize")).name().toLowerCase(Locale.ROOT) + " ";   //
            String acts = full.first(BuilderActs.SLOT).isEmpty() ? ""   // "5 real wolves (friendly)"
                    : " (" + BuilderActs.of(full.first(BuilderActs.SLOT)).label().split(" \\(")[0].toLowerCase(Locale.ROOT) + ")";
            out = (n == 1 ? "a " : n + " ") + sized + "real " + word + acts;
        } else if (pick.kind().equals("creature") || pick.kind().equals("ride")) {
            List<String> bits = new ArrayList<>();
            for (String slot : List.of("parts", "powers", "abilities")) {
                for (String id : full.picks().getOrDefault(slot, List.of())) {
                    bits.add(low(labelOf(form, slot, id)));
                }
            }
            String temper = full.first("temper").isEmpty() ? "" : low(labelOf(form, "temper", full.first("temper")));
            out = many + size + scheme + labelOf(form, "from", full.first("from"))
                    + (pick.kind().equals("ride") ? " to ride" : "")
                    + (bits.isEmpty() ? "" : ": " + String.join(", ", bits))
                    + (temper.isEmpty() || temper.equals("friendly") ? "" : " (" + temper + ")");
        } else if (pick.kind().equals("supplies")) {   //: "16 oak logs", "A crafting table"
            BuilderSupplies.Supply s = BuilderSupplies.supply(full.first("from"));
            BuilderTree.Entry e = form.slot("from").entry(full.first("from"));
            int most = e == null ? 1 : Integer.parseInt(e.facts().getOrDefault("most", "1"));
            int n = full.first("amount").isEmpty() ? (e == null ? 1 : Integer.parseInt(e.facts().getOrDefault("usual", "1")))
                    : Integer.parseInt(full.first("amount"));
            // As the dialog will set it: never past what this world gives of it at once.
            n = most <= 1 ? 1 : BuilderSupplies.fit(n, most);
            out = s == null ? labelOf(form, "from", full.first("from")) : BuilderSupplies.words(s, n);
        } else if (pick.form().equals("ready")) {
            out = many + size + scheme + labelOf(form, "from", full.first("from"));
            // what goes outside a building, several at once ("with a flower garden and lamp posts")
            List<String> said = new java.util.ArrayList<>();
            for (String o : full.picks().getOrDefault("outside", List.of())) {
                if (!o.equals("outside:" + BuilderBuildings.NOTHING)) {
                    said.add(low(labelOf(form, "outside", o)));
                }
            }
            if (!said.isEmpty()) {
                out += " with " + (said.size() == 1 ? said.get(0)
                        : String.join(", ", said.subList(0, said.size() - 1)) + " and " + said.get(said.size() - 1));
            }
            String behind = full.first("background");   //: what is behind a picture
            if (!behind.isEmpty() && !behind.equals("background:" + BuilderPictures.DRAWN)) {
                out += " on " + low(labelOf(form, "background", behind));
            }
        } else if (pick.kind().equals("item") && !full.first(BuilderTurnInto.TO_SLOT).isEmpty()) {
            // "A wand: sand into glass", "A sword: any block into gold"
            List<String> from = new ArrayList<>();
            full.picks().getOrDefault(BuilderTurnInto.FROM_SLOT, List.of()).forEach(id -> from.add(low(labelOf(form, BuilderTurnInto.FROM_SLOT, id))));
            out = many + scheme + labelOf(form, "shape", full.first("shape")).replaceFirst(" \\(.*\\)$", "") + ": "
                    + String.join(" and ", from) + " into " + low(labelOf(form, BuilderTurnInto.TO_SLOT, full.first(BuilderTurnInto.TO_SLOT)));
        } else if (pick.kind().equals("item")) {
            // a LOOK is what the item is ("Crown: super speed"), never the stock item it goes on ("A helmet")
            String look = full.first(BuilderLooks.SLOT);
            String what = look.isEmpty() ? labelOf(form, "shape", full.first("shape")).replaceFirst(" \\(.*\\)$", "")
                    : labelOf(form, BuilderLooks.SLOT, look) + lookUse(look);   //
            out = many + scheme + what + ": " + low(labelOf(form, "power", full.first("power")));
        } else if (pick.kind().equals("building")) {   //: "A house: a dome, lots of windows, 2 floors"
            String floors = full.first("floors");
            out = many + scheme + labelOf(form, "from", full.first("from")) + ": " + low(labelOf(form, "roof", full.first("roof")))
                    + ", " + low(labelOf(form, "windows", full.first("windows")))
                    + (floors.isEmpty() || floors.equals("1") ? "" : ", " + floors + " floors");
        } else if (pick.kind().equals("magic_block")) {   //: "Gold block: a lucky surprise, when I break it"
            out = many + scheme + labelOf(form, "material", full.first("material")) + " block: "
                    + low(labelOf(form, "power", full.first("power"))) + ", " + low(labelOf(form, "trigger", full.first("trigger")));
        } else if (pick.kind().equals("machine")) {
            List<String> powers = new ArrayList<>();
            full.picks().getOrDefault("snack_powers", List.of()).forEach(id -> powers.add(low(labelOf(form, "snack_powers", id))));
            out = labelOf(form, "look", full.first("look")) + " machine: " + String.join(" and ", powers);
        } else if (pick.kind().equals("event")) {
            // A motion with one thing it is made of says only itself ("A meteor", not "A meteor a fiery rock").
            BuilderEvents.Motion m = BuilderEvents.byId(full.first("motion").replaceFirst("^event:", ""));
            boolean alone = m != null && m.whats().size() == 1;
            out = (size + labelOf(form, "motion", full.first("motion")).replaceFirst("\\.\\.\\.$", "")
                    + (alone ? "" : " " + low(labelOf(form, "what", full.first("what"))))).strip();
        } else {
            out = labelOf(form, "when", full.first("when"))
                    + (full.first("who").isEmpty() ? "" : " (" + labelOf(form, "who", full.first("who")) + ")")
                    + ": " + low(labelOf(form, "does", full.first("does")));
        }
        out = Character.toUpperCase(out.charAt(0)) + out.substring(1);
        return out.length() <= LABEL_MAX ? out : out.substring(0, LABEL_MAX - 1).stripTrailing() + "…";
    }

    /** A chest tile's name stays readable up to about this many characters on every edition. */
    public static final int LABEL_MAX = 48;

    /** The vanilla item a tile shows: the picked ready-made's own Builder tile, else the form's leading pick's. */
    public static String icon(BuilderAllowlist allow, LibraryPresets presets, BuilderTree tree, Apprentice.Pick pick) {
        if (Catalogue.prefix(pick.first("from")).equals("supply") || BuilderSupplies.found(pick.first("from"))) {   //: the supply itself
            BuilderSupplies.Supply s = BuilderSupplies.supply(pick.first("from"));
            return s == null ? "minecraft:chest" : s.item();
        }
        if (!pick.first("from").isEmpty()) {
            return new BuilderFlow(allow, presets).iconFor(pick.first("from"));
        }
        if (!pick.first("shape").isEmpty()) {
            BuilderItemPowers.Stock s = BuilderItemPowers.stock(pick.first("shape").substring(pick.first("shape").indexOf(':') + 1));
            return s == null ? "minecraft:blaze_rod" : s.tile();
        }
        if (!pick.first("material").isEmpty()) {   //
            BuilderBlocks.Look l = BuilderBlocks.look(pick.first("material"));
            return l == null ? "minecraft:gold_block" : l.block();
        }
        if (!pick.first("look").isEmpty()) {
            BuilderSnacks.Look l = BuilderSnacks.look(pick.first("look"));
            return l == null ? "minecraft:cookie" : l.tile();
        }
        if (!pick.first("motion").isEmpty()) {
            BuilderEvents.Motion m = BuilderEvents.byId(pick.first("motion").substring("event:".length()));
            return m == null ? "minecraft:fire_charge" : m.tile();
        }
        if (!pick.first("when").isEmpty()) {
            return BuilderFlow.RULE_TRIGGER_ICONS.getOrDefault(Catalogue.bare(pick.first("when")), "minecraft:writable_book");
        }
        String[] kind = BuilderFlow.KIND_TILES.get(BuilderSpec.Kind.of(pick.kind()));
        return kind == null ? "minecraft:nether_star" : kind[1];
    }

    /**
     * The page: one tile per offer, then the honest last tile. {@code magician}: his last tile sends the words to the
     * agent; a Crafter's says who could. {@code priced}: the world has a wish bank, so each tile says its price.
     */
    public static PickPage page(BuilderAllowlist allow, LibraryPresets presets, BuilderTree tree, List<Apprentice.Pick> offers,
                                boolean magician, boolean priced) {
        return page(allow, presets, tree, offers, null, magician, priced);
    }

    /** The page with each tile tagged for {@code answer} ({@link #tag}); a null answer is the page with no tags. */
    public static PickPage page(BuilderAllowlist allow, LibraryPresets presets, BuilderTree tree, List<Apprentice.Pick> offers,
                                Apprentice.Answer answer, boolean magician, boolean priced) {
        return page(allow, presets, tree, offers, answer, null, magician, priced);
    }

    /**
     * …and with each tile's {@code notes} (one per offer, or null): a tile that drops some of his words names
     * them ("Can't do: horns"), a tile that keeps every word says nothing about leaving any out, and a stand-in is
     * said as one ("Closest it has: Lion (for tiger)") instead of "Just what you said". A tile the layer added to
     * keeps its own line.
     */
    public static PickPage page(BuilderAllowlist allow, LibraryPresets presets, BuilderTree tree, List<Apprentice.Pick> offers,
                                Apprentice.Answer answer, List<Apprentice.Notes> notes, boolean magician, boolean priced) {
        return page(allow, presets, tree, offers, answer, notes, magician, priced, false);
    }

    /**
     * …and {@code magicianSecond} (the collaborate mode): a Magician's ✪ tile is the SECOND tile, right after the
     * Apprentice's make, not the last. On a sure page the right make is the top tile or nowhere (145 of 149 hits).
     */
    public static PickPage page(BuilderAllowlist allow, LibraryPresets presets, BuilderTree tree, List<Apprentice.Pick> offers,
                                Apprentice.Answer answer, List<Apprentice.Notes> notes, boolean magician, boolean priced,
                                boolean magicianSecond) {
        List<PickPage.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) {
            List<String> lore = new ArrayList<>(List.of(TILE_LORE));
            String tag = answer == null ? null : tag(answer, i);
            Apprentice.Notes n = answer == null || notes == null || i >= notes.size() || answer.guessed(i) ? null : notes.get(i);
            if (n != null) {
                if (!n.cant().isEmpty()) {
                    tag = cantDo(n.cant());
                } else if (TAG_LEAVES_OUT.equals(tag)) {
                    tag = null;              // this tile keeps every word he said
                }
                if (!n.near().isEmpty() && TAG_JUST.equals(tag)) {
                    tag = null;              // not just what he said: a stand-in is said as one, below
                }
            }
            if (tag != null) {
                lore.add(tag);
                if (tag.equals(TAG_LEAVES_OUT) || tag.startsWith(CANT_DO.substring(0, CANT_DO.indexOf('%')))) {
                    lore.add(TAG_CHANGE_AFTER);
                }
            }
            if (n != null && !n.near().isEmpty()) {
                lore.add(mostOr(tree, offers.get(i), n.near().get(0)));
            }
            if (priced) {
                lore.add(HALF_WISH);
            }
            tiles.add(new PickPage.Tile(OFFER + i, icon(allow, presets, tree, offers.get(i)), label(tree, offers.get(i)), lore, false));
        }
        List<String> last = new ArrayList<>(magician ? MAGICIAN_INSTEAD_LORE : NONE_LORE);
        if (magician && priced) {
            last.add(WHOLE_WISH);
        }
        PickPage.Tile lastTile = new PickPage.Tile(NONE, magician ? "minecraft:nether_star" : "minecraft:barrier",
                magician ? MAGICIAN_INSTEAD_LABEL : NONE_LABEL, last, false);
        if (magician && magicianSecond && tiles.size() >= 1) {
            tiles.add(1, lastTile);
        } else {
            tiles.add(lastTile);
        }
        return PickPage.of(TITLE, tiles, false);
    }

    /** Tile ids: {@code offer0}…, and the last tile. */
    public static final String OFFER = "offer";
    public static final String NONE = "none";

    private static String labelOf(BuilderTree.Form form, String slot, String id) {
        BuilderTree.Slot s = form.slot(slot);
        BuilderTree.Entry e = s == null ? null : s.entry(id);
        return e == null ? id : e.label();
    }

    private static String low(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
    }

    /** The note under a tile for a moved number or word: the world's most when that is why, else the closest. */
    static String mostOr(BuilderTree tree, Apprentice.Pick pick, List<String> near) {
        String typed = near.get(0);
        String have = near.get(1);
        try {
            int h = Integer.parseInt(have);
            int most = Apprentice.countMost(pick);
            int t = Integer.parseInt(typed.replaceAll("[^0-9]", "").isEmpty() ? "-1" : typed.replaceAll("[^0-9]", ""));
            if (h == most && (t > most || t < 0) && pick.picks().containsKey("count")) {
                return String.format(MOST_FOR, BuilderCounts.mostLine(pick.first("from"), most), typed);
            }
            if (pick.picks().containsKey("amount") && t > h && h == Apprentice.amountMost(tree, pick)) {
                return String.format(MOST_FOR, h + " is the most at once here", typed);   //: "64 TNT"
            }
        } catch (NumberFormatException e) {
            // a word stood in for a word: the closest it has
        }
        return String.format(CLOSEST_FOR, have, typed);
    }

    /** how a stock item is used, from its label ("A helmet (wear it)" → " (wear it)"), or "". */
    static String use(String shapeLabel) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([^)]*)\\)$").matcher(shapeLabel == null ? "" : shapeLabel);
        return m.find() ? " (" + m.group(1) + ")" : "";
    }

    /** how a look's own stock item is used: " (wear it)" for a crown, " (eat it)" for a cupcake, or "". */
    static String lookUse(String look) {
        BuilderLooks.Look l = BuilderLooks.look(look);
        BuilderItemPowers.Stock st = l == null ? null : BuilderItemPowers.stock(l.stock());
        return st == null ? "" : use(st.label());
    }
}
