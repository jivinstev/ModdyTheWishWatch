package com.moddingfromamod.reference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Which Minecraft account IS this person? Only an outside source that LINKS the two may say
 *.</b>
 *
 * <p> fetched the skin of whatever Mojang account had the public name, once Wikipedia said
 * the name was public. For BlockyBob that is right; for Stampy it paints a stranger: the account
 * {@code Stampy} (uuid {@code 1127a6d9…}) is somebody else, and his is {@code stampylongnose}
 * (measured 2026-10-02). A public name and a username are two facts, and the second needs its own
 * source. The model never supplies it: "the agent knows Stampy's username" is a model's own
 * knowledge gating access to knowledge it lacks (docs/PATTERNS.md), and wrong without warning.
 *
 * <p><b>The source is minecraft.wiki's {@code {{Infobox person}}}</b>: curated,
 * cited, and reachable from a server. The page (or the page a redirect lands on — {@code Joseph
 * Garrett} → {@code Stampy}) is read through the MediaWiki API with {@link WikiArtwork}'s client,
 * and the username is {@code minecraft_name}, else the first JAVA entry of {@code minecraft_names}.
 * Two rules decide "Java", and they are the reason for the fixtures table in
 * {@code MinecraftWikiPersonTest}:
 * <ul>
 *   <li>An entry marked for another edition is DROPPED — {@code ({{BE}})}, "Xbox Gamertag",
 *       Bedrock, PlayStation, Switch, Pocket/PE, Education. A gamertag is not a Java profile, and
 *       the Java account of the same spelling may belong to someone else, which is the whole bug.
 *       So Stampy, whose page lists only {@code stampylongnose (Xbox Gamertag)}, is UNMAPPED even
 *       though that spelling happens to be his Java name too: the page does not say so, and a
 *       guess that happens to be right this time is still a guess.</li>
 *   <li>An UNMARKED entry counts as Java. {@code minecraft_name} renders as a namemc link (a
 *       Java-only service) and is plain in most pages; Technoblade's {@code minecraft_names} is
 *       six unmarked {@code mine.ly} links, all Java.</li>
 * </ul>
 *
 * <p>Being linked here is also an outside fact that the person is PUBLIC (item 3): the page
 * is about them, and it names their account. {@link PublicFigure} counts it alongside Wikipedia.
 *
 * <p>Answers are cached for the process with the page's REVISION id, which provenance records; a
 * failed ask is not an answer and is asked again. A page that cannot be read fails CLOSED:
 * {@link Unreachable} fetches no skin.
 */
public final class MinecraftWikiPerson {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftWikiPerson.class);

    static final String API = "https://minecraft.wiki/api.php";

    /** The two infobox fields, in the order they are read. */
    static final String NAME = "minecraft_name";
    static final String NAMES = "minecraft_names";

    /** A Java username. The same shape {@code SkinFetch} accepts. */
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{2,16}");

    /** What the wiki said. */
    public sealed interface Result permits Linked, Unmapped, Unreachable {
        String publicName();
    }

    /** The page names a Java account: everything provenance records. */
    public record Linked(String publicName, String page, long revision, String field,
                         String username) implements Result, SkinLink {
        @Override
        public String sourceName() {
            return "minecraft.wiki";
        }

        /** Where the link came from, for provenance and the log. */
        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("source", "minecraft.wiki");
            o.addProperty("page", page);
            o.addProperty("revision", revision);
            o.addProperty("field", field);
            o.addProperty("url", "https://minecraft.wiki/w/" + page.replace(' ', '_')
                    + "?oldid=" + revision);
            return o;
        }
    }

    /** Why there is no link. The log says which; the child is told the same either way. */
    public enum Why {
        NO_PAGE,
        NOT_A_PERSON,
        NO_JAVA_NAME
    }

    public record Unmapped(String publicName, Why why, String page) implements Result {
    }

    public record Unreachable(String publicName) implements Result {
    }

    private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

    private static volatile Function<String, Result> oracle = MinecraftWikiPerson::askWiki;

    private MinecraftWikiPerson() {
    }

    /** The account minecraft.wiki links to {@code publicName}. Never throws; ~a second cold. */
    public static Result resolve(String publicName) {
        String n = publicName == null ? "" : publicName.strip();
        if (n.isEmpty() || n.length() > 60) {
            return new Unmapped(n, Why.NO_PAGE, null);
        }
        String key = n.toLowerCase(Locale.ROOT);
        Result cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Result r;
        try {
            r = oracle.apply(n);
        } catch (RuntimeException e) {
            r = null;
        }
        if (r == null) {
            r = new Unreachable(n);
        }
        if (!(r instanceof Unreachable)) {
            CACHE.put(key, r);
        }
        LOGGER.info("minecraft.wiki username link: '{}' -> {}", n, r);
        return r;
    }

    /** A test's answers instead of the wiki's. {@link #reset()} afterwards. */
    public static void useOracle(Function<String, Result> o) {
        oracle = o == null ? MinecraftWikiPerson::askWiki : o;
        CACHE.clear();
    }

    public static void reset() {
        useOracle(null);
    }

    static String queryUrl(String name) {
        return API + "?action=query&prop=revisions&rvprop=content%7Cids&rvslots=main&titles="
                + URLEncoder.encode(String.join("|", PublicFigure.titles(name)),
                StandardCharsets.UTF_8)
                + "&redirects=1&format=json&formatversion=2";
    }

    // ───────────────────────────── reading the answer ─────────────────────────────

    /**
     * Read the API's answer for {@code publicName}. Null when the body is not an answer at all.
     * Of the pages asked about (as given, and in Title Case), the first that is a person wins.
     */
    static Result parse(String publicName, String body) {
        if (body == null || !body.strip().startsWith("{")) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("query") || !root.getAsJsonObject("query").has("pages")) {
                return null;
            }
            Unmapped best = null;
            for (JsonElement el : root.getAsJsonObject("query").getAsJsonArray("pages")) {
                JsonObject p = el.getAsJsonObject();
                if (p.has("missing") || p.has("invalid") || !p.has("revisions")) {
                    continue;
                }
                String title = p.get("title").getAsString();
                JsonObject rev = p.getAsJsonArray("revisions").get(0).getAsJsonObject();
                long revid = rev.has("revid") ? rev.get("revid").getAsLong() : 0L;
                String text = rev.has("slots")
                        ? rev.getAsJsonObject("slots").getAsJsonObject("main").get("content")
                                .getAsString()
                        : rev.has("content") ? rev.get("content").getAsString() : "";
                Map<String, String> box = infobox(text);
                if (box == null) {
                    if (best == null) {
                        best = new Unmapped(publicName, Why.NOT_A_PERSON, title);
                    }
                    continue;
                }
                Name found = javaName(box);
                if (found != null) {
                    return new Linked(publicName, title, revid, found.field(), found.username());
                }
                best = new Unmapped(publicName, Why.NO_JAVA_NAME, title);
            }
            return best != null ? best : new Unmapped(publicName, Why.NO_PAGE, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A username and the field it came from. */
    record Name(String field, String username) {
    }

    /** {@code minecraft_name}'s first Java name, else {@code minecraft_names}'s. Null if neither. */
    static Name javaName(Map<String, String> box) {
        for (String field : new String[]{NAME, NAMES}) {
            String v = box.get(field);
            if (v == null) {
                continue;
            }
            for (String entry : entries(v)) {
                String u = javaUsername(entry);
                if (u != null) {
                    return new Name(field, u);
                }
            }
        }
        return null;
    }

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern REF = Pattern.compile(
            "<ref[^>/]*/>|<ref[^>]*>.*?</ref>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern INFOBOX = Pattern.compile(
            "\\{\\{\\s*infobox[ _]+person\\s*(?=[|}])", Pattern.CASE_INSENSITIVE);

    /**
     * The parameters of the page's {@code {{Infobox person}}}, keys lower-cased with spaces as
     * underscores; null when the page has none. Splits on a {@code |} only at the infobox's own
     * depth, so a nested template or a piped wiki link stays inside its value.
     */
    static Map<String, String> infobox(String wikitext) {
        if (wikitext == null) {
            return null;
        }
        String t = REF.matcher(COMMENT.matcher(wikitext).replaceAll("")).replaceAll("");
        Matcher m = INFOBOX.matcher(t);
        if (!m.find()) {
            return null;
        }
        int i = m.end();
        int braces = 1;     // inside the infobox's own {{
        int links = 0;      // inside a [[wiki link]]
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        while (i < t.length()) {
            if (t.startsWith("{{", i)) {
                braces++;
                cur.append("{{");
                i += 2;
            } else if (t.startsWith("}}", i)) {
                braces--;
                if (braces == 0) {
                    break;
                }
                cur.append("}}");
                i += 2;
            } else if (t.startsWith("[[", i)) {
                links++;
                cur.append("[[");
                i += 2;
            } else if (t.startsWith("]]", i) && links > 0) {
                links--;
                cur.append("]]");
                i += 2;
            } else if (t.charAt(i) == '|' && braces == 1 && links == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
                i++;
            } else {
                cur.append(t.charAt(i));
                i++;
            }
        }
        parts.add(cur.toString());
        Map<String, String> out = new LinkedHashMap<>();
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = p.substring(0, eq).strip().toLowerCase(Locale.ROOT)
                    .replaceAll("[\\s_]+", "_");
            out.putIfAbsent(key, p.substring(eq + 1).strip());
        }
        return out;
    }

    /** One field split into its entries: lines, bullets, {@code <br>}, commas, semicolons. */
    static List<String> entries(String value) {
        List<String> out = new ArrayList<>();
        for (String e : value.split("(?i)\\n|<br\\s*/?>|,|;")) {
            String s = e.strip().replaceFirst("^[*#:]+", "").strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Marks an entry as some edition other than Java. */
    private static final Pattern OTHER_EDITION = Pattern.compile(
            "\\{\\{\\s*(be|pe|ee|bewiki|xbox|ps|switch)\\s*}}"
                    + "|\\b(bedrock|xbox|gamertag|playstation|psn|nintendo|switch|pocket|"
                    + "education|console)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXTERNAL =
            Pattern.compile("\\[(https?://[^\\s\\]]+)(?:\\s+([^\\]]*))?]");
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)]]");

    /** The Java username an entry names, or null — another edition's, or not a username at all. */
    static String javaUsername(String entry) {
        if (OTHER_EDITION.matcher(entry).find()) {
            return null;
        }
        Matcher ext = EXTERNAL.matcher(entry);
        if (ext.find()) {
            String label = ext.group(2) == null ? "" : clean(ext.group(2));
            if (USERNAME.matcher(label).matches()) {
                return label;
            }
            String url = ext.group(1);
            String slug = url.replaceFirst("[?#].*$", "").replaceFirst("/+$", "");
            slug = slug.substring(slug.lastIndexOf('/') + 1);
            return USERNAME.matcher(slug).matches() ? slug : null;
        }
        String plain = clean(WIKI_LINK.matcher(entry).replaceAll("$1")
                .replaceAll("\\{\\{[^}]*}}", "")
                .replaceAll("\\([^)]*\\)", ""));
        return USERNAME.matcher(plain).matches() ? plain : null;
    }

    private static String clean(String s) {
        return s.replace("'''", "").replace("''", "").replace("<code>", "")
                .replace("</code>", "").strip();
    }

    // ───────────────────────────── the real wiki ─────────────────────────────

    /** Backoffs after a 429, as {@link PublicFigure}'s. */
    private static final long[] BACKOFF_MS = {1000, 2000, 4000};

    private static Result askWiki(String name) {
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            try {
                var res = WikiArtwork.getRaw(queryUrl(name));
                if (res.statusCode() == 200) {
                    Result r = parse(name, res.body());
                    if (r != null) {
                        return r;
                    }
                }
                LOGGER.info("minecraft.wiki link for '{}': the wiki answered {}", name,
                        res.statusCode());
                if (res.statusCode() != 429 || attempt == BACKOFF_MS.length) {
                    return new Unreachable(name);
                }
                Thread.sleep(BACKOFF_MS[attempt]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Unreachable(name);
            } catch (Exception e) {
                LOGGER.info("minecraft.wiki link for '{}' failed: {}", name, e.toString());
                return new Unreachable(name);
            }
        }
        return new Unreachable(name);
    }
}
