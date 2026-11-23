package com.moddingfromamod.reference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The (b) describer's own picture search, by the character's name</b>.
 *
 * <p>{@link WikiArtwork} reads only the pages the researcher cited, by design: the wiki comes from the sources, never
 * from a table. But for Tabbycat and Duffington those sources were pet-care blogs and a shop, so the describer had
 * nothing while a person found the character's picture at once. This is the second source, used only when the cited
 * pages give too little: the character's own Fandom wiki (its name, guessed as the wiki's address) and Wikipedia's
 * lead picture of its article. Two general sources keyed on the resolved name; no per-character table, which
 * {@code CharacterPicturesTest} keeps true. What comes back is fetched by {@link ReferenceImages} (public hosts,
 * size bounds) and described behind the same injection check and safety grade.
 */
public final class CharacterPictures {

    private static final Logger LOGGER = LoggerFactory.getLogger(CharacterPictures.class);

    private CharacterPictures() {
    }

    static final String WIKIPEDIA_API = "https://en.wikipedia.org/w/api.php";

    /**
     * <b>Wikipedia's lead picture of a character, found by its name</b>. The cited pages
     * are what the researcher happened to read, and for Tabbycat and Duffington those were pet-care blogs and a
     * shop, so the describer had nothing while a person found the character's picture at once. This asks
     * Wikipedia for the article's lead image by title (following redirects, and "Name (character)" too), then by
     * search when no title matches. Thumbnails only; JPEGs count (a describer needs no alpha). Never throws.
     */
    public static List<String> leadImages(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            String titles = name.strip() + "|" + name.strip() + " (character)";
            addLead(out, WIKIPEDIA_API + "?action=query&format=json&redirects=1&prop=pageimages&piprop=thumbnail"
                    + "&pithumbsize=" + WikiArtwork.THUMB_WIDTH + "&titles=" + URLEncoder.encode(titles, StandardCharsets.UTF_8));
            if (out.isEmpty()) {
                addLead(out, WIKIPEDIA_API + "?action=query&format=json&generator=search&gsrlimit=2&prop=pageimages"
                        + "&piprop=thumbnail&pithumbsize=" + WikiArtwork.THUMB_WIDTH + "&gsrsearch="
                        + URLEncoder.encode(name.strip() + " character", StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            LOGGER.info("no Wikipedia lead picture for '{}': {}", name, e.toString());
        }
        return List.copyOf(out);
    }

    /**
     * <b>The character's own Fandom wiki, guessed from its name</b>. Fandom's cross-wiki search sits behind a
     * browser challenge, but each wiki's own API answers, and a franchise wiki is very often named for the character
     * (tabbycat.fandom.com, duffington.fandom.com). At most four guesses: the name run together, then its first and
     * last words. A guess that is not a wiki answers with no pages and costs one request. Never throws.
     */
    public static List<String> fandomLead(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String wiki : fandomGuesses(name)) {
            try {
                addLead(out, "https://" + wiki + ".fandom.com/api.php?action=query&format=json&redirects=1"
                        + "&prop=pageimages&piprop=thumbnail&pithumbsize=" + WikiArtwork.THUMB_WIDTH + "&titles="
                        + URLEncoder.encode(name.strip(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                LOGGER.info("no Fandom lead picture on {}.fandom.com: {}", wiki, e.toString());
            }
            if (!out.isEmpty()) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** Fandom wiki names to try for a character: letters and digits only, lower case, at most four. */
    static List<String> fandomGuesses(String name) {
        String[] words = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim().split("\\s+");
        java.util.LinkedHashSet<String> g = new java.util.LinkedHashSet<>();
        String joined = String.join("", words);
        if (!joined.isEmpty()) {
            g.add(joined);
        }
        if (words.length > 1) {
            g.add(words[0]);
            g.add(words[words.length - 1]);
        }
        if (!joined.isEmpty() && !joined.endsWith("s")) {
            g.add(joined + "s");
        }
        List<String> out = new ArrayList<>();
        for (String w : g) {
            if (w.length() >= 3 && out.size() < 4) {
                out.add(w);
            }
        }
        return out;
    }

    private static void addLead(List<String> out, String url) throws Exception {
        HttpResponse<String> res = WikiArtwork.getRaw(url);
        if (res.statusCode() != 200 || !res.body().trim().startsWith("{")) {
            LOGGER.info("no Wikipedia lead picture: HTTP {}", res.statusCode());
            return;
        }
        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        if (!root.has("query") || !root.getAsJsonObject("query").has("pages")) {
            return;
        }
        for (var e : root.getAsJsonObject("query").getAsJsonObject("pages").entrySet()) {
            JsonObject p = e.getValue().getAsJsonObject();
            if (p.has("thumbnail")) {
                String src = WikiArtwork.canonical(WikiArtwork.str(p.getAsJsonObject("thumbnail"), "source"));
                if (!src.isBlank() && !out.contains(src)) {
                    out.add(src);
                }
            }
        }
    }

}
