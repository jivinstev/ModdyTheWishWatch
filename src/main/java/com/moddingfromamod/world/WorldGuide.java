package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>A world's guide</b> (<i>"I was dumped on a small island with a chest
 * that had lava and ice. I have no idea what to do."</i>) — the manifest's {@code guide}, read by
 * {@link WorldFacts}. Two optional halves, both DATA so the words are edited in {@code worlds/<id>.json}
 * and never in Java:
 *
 * <ul>
 *   <li>{@link Book} — a vanilla written book given once on a first arrival ({@code GuideBookService})
 *       and again by {@code /moddy guide}. {@link GuideBook} checks its pages fit a book page and
 *       fills its {@code {bank_*}} placeholders from the rules in force.</li>
 *   <li>{@link Goals} — the advancement tree the watch's "📖 What do I do now?" walks for the next
 *       goal ({@link NextGoal}): its root, the order early goals are offered in, ids that are never a
 *       goal (a warning like "Oops! Turn your lava into obsidian"), and per-goal help — a hint and the
 *       wish the "Help me with this" button pre-fills.</li>
 * </ul>
 *
 * <p>A world with no {@code guide} is {@link #NONE}: no book, no goal tree — the watch's guide shows
 * the "be creative" page instead. Minecraft-free; parsed field by field, never throwing.
 */
public record WorldGuide(Book book, Goals goals) {

    /** No guide. */
    public static final WorldGuide NONE = new WorldGuide(Book.NONE, Goals.NONE);

    public WorldGuide {
        book = book == null ? Book.NONE : book;
        goals = goals == null ? Goals.NONE : goals;
    }

    /** True when this world gives a guide book (and so has {@code /moddy guide}). */
    public boolean hasBook() {
        return !book.pages().isEmpty();
    }

    /** True when this world has a goal tree for "What do I do now?". */
    public boolean hasGoals() {
        return goals.root().contains(":");
    }

    /**
     * The guide book's words.
     *
     * @param title  the book's title (at most {@link GuideBook#TITLE_MAX} characters)
     * @param author who the book says wrote it
     * @param pages  each page's text; {@code \n} breaks a line, {@code {bank_start}} etc. are filled in
     */
    public record Book(String title, String author, List<String> pages) {

        public static final Book NONE = new Book("", "", List.of());

        public Book {
            title = title == null ? "" : title.strip();
            author = author == null ? "" : author.strip();
            pages = pages == null ? List.of() : List.copyOf(pages);
        }
    }

    /**
     * The help one goal gets: what to tell him, and what to wish for.
     *
     * @param hint the line shown under the goal ("" = the advancement's own description)
     * @param wish the wish "Help me with this" pre-fills ("" = {@link NextGoal#fallbackWish})
     */
    public record Help(String hint, String wish) {

        public Help {
            hint = hint == null ? "" : hint.strip();
            wish = wish == null ? "" : wish.strip();
        }
    }

    /**
     * The goal tree.
     *
     * @param root  the advancement the tree is walked from (its tab's root)
     * @param order ids offered first, in this order, when several goals are open at once
     * @param skip  ids that are never offered as a goal (warnings, not goals)
     * @param help  per-goal help, by advancement id
     */
    public record Goals(String root, List<String> order, List<String> skip, Map<String, Help> help) {

        public static final Goals NONE = new Goals("", List.of(), List.of(), Map.of());

        public Goals {
            root = root == null ? "" : root.strip();
            order = order == null ? List.of() : List.copyOf(order);
            skip = skip == null ? List.of() : List.copyOf(skip);
            help = help == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(help));
        }

        /** The help for {@code id}, or an empty one. */
        public Help helpFor(String id) {
            Help h = id == null ? null : help.get(id);
            return h == null ? new Help("", "") : h;
        }
    }

    /** The manifest's {@code guide} element → a guide; anything malformed is left out, never thrown. */
    public static WorldGuide parse(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return NONE;
        }
        JsonObject o = el.getAsJsonObject();
        Book book = Book.NONE;
        JsonElement b = o.get("book");
        if (b != null && b.isJsonObject()) {
            JsonObject bo = b.getAsJsonObject();
            book = new Book(str(bo, "title"), str(bo, "author"), strings(bo.get("pages")));
        }
        Goals goals = Goals.NONE;
        JsonElement g = o.get("goals");
        if (g != null && g.isJsonObject()) {
            JsonObject go = g.getAsJsonObject();
            Map<String, Help> help = new LinkedHashMap<>();
            JsonElement h = go.get("help");
            if (h != null && h.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : h.getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        JsonObject ho = e.getValue().getAsJsonObject();
                        help.put(e.getKey(), new Help(str(ho, "hint"), str(ho, "wish")));
                    }
                }
            }
            goals = new Goals(str(go, "root"), strings(go.get("order")), strings(go.get("skip")), help);
        }
        return new WorldGuide(book, goals);
    }

    private static List<String> strings(JsonElement el) {
        List<String> out = new ArrayList<>();
        if (el != null && el.isJsonArray()) {
            for (JsonElement x : el.getAsJsonArray()) {
                if (x.isJsonPrimitive() && !x.getAsString().isBlank()) {
                    out.add(x.getAsString());
                }
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
