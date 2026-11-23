package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.content.ContentIndex;
import com.moddingfromamod.content.Found;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <b>The Builder's 🔍 Find</b> (§2): he types what it is, in his own words and spelling, and
 * gets a page of at most {@link #MOST} installed things to tap. One round trip: the menus are server-side, so there is
 * no live search as he types, and a child forgets and misspells names, so the search is never exact-name.
 *
 * <p>The search is the one {@link ContentIndex} the Magician's {@code find_content} and the Apprentice use (no drift),
 * with two second tries when the words as typed find nothing:
 * <ol>
 *   <li>a word run together ("packedice") is split into two words the game knows ("packed ice");</li>
 *   <li>a description ("shiny blue block") keeps the words the game knows and ranks each thing by how many of them it
 *       has.</li>
 * </ol>
 * Then the page's own rule ({@code fits}: a To this item can make, a supply this world offers) and {@link #refused}.
 * Pure (no Minecraft type): {@code BuilderFindTest} runs it over the committed snapshot of the game's names.
 */
public final class BuilderFind {

    private BuilderFind() {
    }

    /** At most this many results: one row of a chest, and a Bedrock grid without scrolling. */
    public static final int MOST = 9;
    /** The 🔍 Find tile, on every page that has one. */
    public static final String TILE_ID = "find:open";
    /** "None of these — try again": the box again, with the words he typed. */
    public static final String AGAIN_ID = "find:again";
    /** A result tile: {@code find:<namespace:path>}. */
    public static final String RESULT_PREFIX = "find:";
    public static final String TILE_LABEL = "🔍 Find…";
    public static final String TILE_ICON = "minecraft:spyglass";
    public static final String AGAIN_LABEL = "None of these — try again";
    public static final String AGAIN_ICON = "minecraft:writable_book";
    public static final String NOTHING_TITLE = "Nothing like that — try other words";
    public static final String RESULTS_TITLE = "Is it one of these?";
    public static final String BOX_TITLE = "🔍 Find";
    public static final String BOX_LABEL = "Type what it is";
    public static final String BOX_ACTION = "Find it!";
    /** How long his words may be: a name, never a wish. */
    public static final int MAX_TYPED = 48;

    /** One thing found: its id, its English name, and whether it is a block, an item or both. */
    public record Result(String id, String name, boolean block, boolean item) {
    }

    /**
     * Never offered by a search, whatever it matches: what turning would break or what no child is handed (§1:
     * "anything Any block refuses — containers, portals, command blocks"). Read off the id's words, so a mod's chest or
     * portal is refused too. Moddy's own things (his watch, a magic block) never: they are made, not found.
     */
    static final Set<String> REFUSED_WORDS = Set.of("portal", "spawner", "bedrock", "chest", "barrel", "hopper",
            "dispenser", "dropper", "shulker", "command", "structure", "jigsaw", "barrier", "debug", "spawn",
            "void", "vault", "beacon");   //: not "egg": a spawn egg is "spawn", and "lots of eggs" are eggs
    static final Set<String> REFUSED_IDS = Set.of("water", "lava", "fire", "soul_fire", "bubble_column",
            "end_portal_frame", "reinforced_deepslate", "furnace", "blast_furnace", "smoker", "brewing_stand",
            "crafter", "lectern", "chiseled_bookshelf", "decorated_pot", "light", "knowledge_book", "test_block",
            "test_instance_block", "moving_piston", "piston_head", "frosted_ice", "budding_amethyst", "trial_key",
            "ominous_trial_key", "player_head", "petrified_oak_slab",
            // with no contents these are "Uncraftable Potion" / "Uncraftable Tipped Arrow"; a potion is the drink
            "potion", "splash_potion", "lingering_potion", "tipped_arrow",
            "dragon_egg");   //: the one egg no child is handed

    /** Is this id one a search never offers? */
    public static boolean refused(String id) {
        if (id == null || id.startsWith(Found.OWN) || !id.contains(":")) {
            return true;
        }
        String path = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (REFUSED_IDS.contains(path)) {
            return true;
        }
        for (String w : path.split("[^a-z0-9]+")) {
            if (REFUSED_WORDS.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** The installed thing with this id, as a result, or null (not installed, or refused). */
    public static Result of(String id) {
        if (refused(id)) {
            return null;
        }
        String block = Found.name(ContentIndex.Kind.BLOCK, id);
        String item = Found.name(ContentIndex.Kind.ITEM, id);
        if (block == null && item == null) {
            return null;
        }
        return new Result(id, item != null ? item : block, block != null, item != null);
    }

    /**
     * The best {@link #MOST} things {@code typed} names that {@code fits} takes, best first. Empty when nothing fits:
     * the page says {@link #NOTHING_TITLE}, never an empty page.
     */
    public static List<Result> search(String typed, Predicate<Result> fits) {
        return search(Found.index(), typed, fits);
    }

    /** the installed CREATURES {@code typed} names (for an event that drops them), best first. */
    public static List<Result> searchCreatures(String typed, Predicate<Result> fits) {
        ContentIndex index = Found.index();
        String words = typed == null ? "" : typed.strip();
        if (words.length() > MAX_TYPED) {
            words = words.substring(0, MAX_TYPED);
        }
        if (ContentIndex.stems(words).isEmpty()) {
            return List.of();
        }
        List<Result> out = new ArrayList<>();
        for (String id : ids(index.search(words, Set.of(ContentIndex.Kind.MOB), 400))) {
            String name = Found.name(ContentIndex.Kind.MOB, id);
            if (name == null || id.startsWith(Found.OWN)) {
                continue;
            }
            Result r = new Result(id, name, false, false);
            if (fits.test(r)) {
                out.add(r);
                if (out.size() == MOST) {
                    break;
                }
            }
        }
        return out;
    }

    public static List<Result> search(ContentIndex index, String typed, Predicate<Result> fits) {
        String words = typed == null ? "" : typed.strip();
        if (words.length() > MAX_TYPED) {
            words = words.substring(0, MAX_TYPED);
        }
        if (ContentIndex.stems(words).isEmpty()) {
            return List.of();
        }
        List<Result> out = keep(index, ids(index.search(words, KINDS, 400)), fits);
        if (out.isEmpty()) {
            String split = unjoined(index, words);
            if (split != null) {
                out = keep(index, ids(index.search(split, KINDS, 400)), fits);
            }
        }
        if (out.isEmpty()) {
            out = keep(index, described(index, words), fits);
        }
        return out;
    }

    private static final Set<ContentIndex.Kind> KINDS = Set.of(ContentIndex.Kind.ITEM, ContentIndex.Kind.BLOCK);

    private static List<String> ids(List<ContentIndex.Hit> hits) {
        List<String> out = new ArrayList<>();
        for (ContentIndex.Hit h : hits) {
            if (!out.contains(h.entry().id())) {
                out.add(h.entry().id());
            }
        }
        return out;
    }

    private static List<Result> keep(ContentIndex index, List<String> ids, Predicate<Result> fits) {
        Map<String, String[]> names = new java.util.HashMap<>();   // id → {block name, item name}
        for (ContentIndex.Entry e : index.entries()) {
            if (e.kind() == ContentIndex.Kind.BLOCK || e.kind() == ContentIndex.Kind.ITEM) {
                String[] n = names.computeIfAbsent(e.id(), x -> new String[2]);
                int at = e.kind() == ContentIndex.Kind.BLOCK ? 0 : 1;
                if (n[at] == null) {
                    n[at] = e.name();
                }
            }
        }
        List<Result> out = new ArrayList<>();
        for (String id : ids) {
            String[] n = names.get(id);
            if (n == null || refused(id)) {
                continue;
            }
            Result r = new Result(id, n[1] != null ? n[1] : n[0], n[0] != null, n[1] != null);
            if (fits.test(r)) {
                out.add(r);
                if (out.size() == MOST) {
                    break;
                }
            }
        }
        return out;
    }

    /** "packedice" → "packed ice": one typed word that is two words the game knows, or null. */
    static String unjoined(ContentIndex index, String words) {
        List<String> parts = new ArrayList<>(List.of(words.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")));
        parts.removeIf(String::isEmpty);
        boolean changed = false;
        for (int i = 0; i < parts.size(); i++) {
            String w = parts.get(i);
            if (w.length() < 6 || index.knows(ContentIndex.stems(w).isEmpty() ? w : ContentIndex.stems(w).get(0))) {
                continue;
            }
            for (int cut = 3; cut <= w.length() - 3; cut++) {
                String a = w.substring(0, cut);
                String b = w.substring(cut);
                if (index.knows(a) && known(index, b)) {
                    parts.set(i, a + " " + b);
                    changed = true;
                    break;
                }
            }
        }
        return changed ? String.join(" ", parts) : null;
    }

    private static boolean known(ContentIndex index, String w) {
        List<String> s = ContentIndex.stems(w);
        return !s.isEmpty() && index.knows(s.get(0));
    }

    /**
     * A description: each word the game knows is searched alone, and a thing ranks by how many of them it has, then by
     * where the first of them found it. "shiny blue block" → the blue blocks first.
     */
    static List<String> described(ContentIndex index, String words) {
        List<String> known = new ArrayList<>();
        for (String w : words.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!w.isEmpty() && known(index, w) && !known.contains(w)) {
                known.add(w);
            }
        }
        if (known.size() < 2) {
            return List.of();
        }
        Map<String, Integer> count = new LinkedHashMap<>();
        for (String w : known) {
            for (String id : ids(index.search(w, KINDS, 2000))) {
                count.merge(id, 1, Integer::sum);
            }
        }
        List<String> out = new ArrayList<>(count.keySet());
        out.sort((a, b) -> Integer.compare(count.get(b), count.get(a)));   // stable: ties keep the search's order
        return out.size() > 200 ? out.subList(0, 200) : out;
    }

    /** The tile id of a result. */
    public static String tileId(Result r) {
        return RESULT_PREFIX + r.id();
    }

    /** The id a result tile names, or null for the Find and again tiles. */
    public static String resultId(String tileId) {
        if (tileId == null || !tileId.startsWith(RESULT_PREFIX) || tileId.equals(TILE_ID) || tileId.equals(AGAIN_ID)) {
            return null;
        }
        return tileId.substring(RESULT_PREFIX.length());
    }
}
