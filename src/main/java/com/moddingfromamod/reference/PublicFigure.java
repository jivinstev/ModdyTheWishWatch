package com.moddingfromamod.reference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Is this name a PUBLIC figure? An outside fact, never the model's opinion.</b>
 *
 * <p>The rule: a real person may be looked up, and his Minecraft skin painted,
 * only if <b>a wiki article exists for the name</b>. The resolver judges phrasing, not who somebody
 * is — and every child with an account has a Minecraft username — so without an outside fact a
 * child naming a classmate or a sibling would start a web search on, or fetch the skin of, a
 * private child. "Never let a model's own knowledge gate access to knowledge it lacks"
 * (docs/PATTERNS.md) cuts both ways here: a model that has "heard of" a name is no evidence that
 * the name is public.
 *
 * <p><b>Which wiki: English Wikipedia — or a minecraft.wiki person page that LINKS a Java
 * account.</b> Wikipedia's notability policy is an editorial judgement made by other people
 * that a subject is public, which is exactly the fact wanted, and an article about a private child
 * is deleted. A Fandom page can be created by anyone in a minute — including by a child about his
 * brother — so its existence proves nothing. minecraft.wiki is curated and cites its sources, and
 * a {@code {{Infobox person}}} naming the person's Minecraft account is as outside a fact as an
 * article ({@link MinecraftWikiPerson}); a person page with no linked account
 * is not asked to stand in for one. A disambiguation page is not an article about the name and
 * does not count: "Emma" has one.
 *
 * <p>Goes through {@link WikiArtwork}'s client — its agent string, timeout and no-redirect rule —
 * rather than a second HTTP path. Wikipedia rate-limits (a 429 was measured from a shared cloud
 * IP, and retried with a short backoff), and a gate that cannot ask <b>fails closed</b>: {@link Verdict#UNREACHABLE} gets no lookup
 * and no skin, and says why. Answers are cached for the life of the process, so a name is asked
 * about once.
 */
public final class PublicFigure {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicFigure.class);

    static final String API = "https://en.wikipedia.org/w/api.php";

    /** The three answers. Only {@link #PUBLIC} opens anything. */
    public enum Verdict {
        PUBLIC,
        NO_ARTICLE,
        UNREACHABLE
    }

    private static final Map<String, Verdict> CACHE = new ConcurrentHashMap<>();

    private static volatile Function<String, Verdict> oracle = PublicFigure::askOutside;

    private PublicFigure() {
    }

    /**
     * Whether {@code name} has an English Wikipedia article, or a minecraft.wiki page that links
     * them to a Java account. Never throws; blocks ~a second cold.
     */
    public static Verdict check(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 60) {
            return Verdict.NO_ARTICLE;
        }
        String key = n.toLowerCase(Locale.ROOT);
        Verdict cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Verdict v;
        try {
            v = oracle.apply(n);
        } catch (RuntimeException e) {
            v = Verdict.UNREACHABLE;
        }
        if (v == null) {
            v = Verdict.UNREACHABLE;
        }
        if (v != Verdict.UNREACHABLE) {
            CACHE.put(key, v);      // a failed ask is not an answer, so it is asked again next time
        }
        LOGGER.info("public figure check: '{}' -> {}", n, v);
        return v;
    }

    /** True only for {@link Verdict#PUBLIC}. */
    public static boolean isPublic(String name) {
        return check(name) == Verdict.PUBLIC;
    }

    /** A test's answers instead of Wikipedia's. {@link #reset()} afterwards. */
    public static void useOracle(Function<String, Verdict> o) {
        oracle = o == null ? PublicFigure::askOutside : o;
        CACHE.clear();
    }

    public static void reset() {
        useOracle(null);
    }

    /**
     * The titles asked for: the name as given, and in Title Case. Wikipedia capitalises only the
     * first letter itself, so "blockybob" is not "BlockyBob" — but a redirect usually exists for the
     * Title Case form, and asking both costs one request.
     */
    static Set<String> titles(String name) {
        Set<String> out = new LinkedHashSet<>();
        out.add(name);
        StringBuilder t = new StringBuilder();
        for (String w : name.split("\\s+")) {
            if (!w.isEmpty()) {
                t.append(t.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0)))
                        .append(w.substring(1));
            }
        }
        out.add(t.toString());
        return out;
    }

    static String queryUrl(String name) {
        return API + "?action=query&titles="
                + URLEncoder.encode(String.join("|", titles(name)), StandardCharsets.UTF_8)
                + "&redirects=1&prop=pageprops&ppprop=disambiguation&format=json&formatversion=2";
    }

    /**
     * Read the API's answer: PUBLIC when any page asked about exists and is not a disambiguation.
     * Null when the body is not an answer at all (an HTML error page, a rate-limit text).
     */
    static Verdict parse(String body) {
        if (body == null || !body.strip().startsWith("{")) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("query") || !root.getAsJsonObject("query").has("pages")) {
                return null;
            }
            for (JsonElement el : root.getAsJsonObject("query").getAsJsonArray("pages")) {
                JsonObject p = el.getAsJsonObject();
                if (p.has("missing") || p.has("invalid")) {
                    continue;
                }
                if (p.has("pageprops") && p.getAsJsonObject("pageprops").has("disambiguation")) {
                    continue;
                }
                return Verdict.PUBLIC;
            }
            return Verdict.NO_ARTICLE;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Backoffs after a 429, in ms. Measured 2026-10-02 from a shared cloud IP: four of five
     * requests in a burst were 429 and the fifth answered, so one retry was not enough. Bounded at
     * ~7 s, which a tool call can afford and the background resolver does not notice.
     */
    private static final long[] BACKOFF_MS = {1000, 2000, 4000};

    /**
     * Wikipedia first; when it has no article, minecraft.wiki's link (item 3). A wiki that
     * cannot be asked leaves the answer UNREACHABLE only if nothing else said PUBLIC — fail closed.
     */
    private static Verdict askOutside(String name) {
        Verdict w = askWikipedia(name);
        return w == Verdict.PUBLIC ? w : combine(w, MinecraftWikiPerson.resolve(name));
    }

    /** Wikipedia's verdict, opened by a minecraft.wiki link; pure, for the test. */
    static Verdict combine(Verdict w, MinecraftWikiPerson.Result link) {
        if (w == Verdict.PUBLIC || link instanceof MinecraftWikiPerson.Linked) {
            return Verdict.PUBLIC;
        }
        if (link instanceof MinecraftWikiPerson.Unreachable) {
            return Verdict.UNREACHABLE;
        }
        return w;
    }

    private static Verdict askWikipedia(String name) {
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            try {
                var res = WikiArtwork.getRaw(queryUrl(name));
                if (res.statusCode() == 200) {
                    Verdict v = parse(res.body());
                    if (v != null) {
                        return v;
                    }
                }
                LOGGER.info("public figure check for '{}': Wikipedia answered {}", name,
                        res.statusCode());
                if (res.statusCode() != 429 || attempt == BACKOFF_MS.length) {
                    return Verdict.UNREACHABLE;
                }
                Thread.sleep(BACKOFF_MS[attempt]);  // polite retries on a rate limit, then closed
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Verdict.UNREACHABLE;
            } catch (Exception e) {
                LOGGER.info("public figure check for '{}' failed: {}", name, e.toString());
                return Verdict.UNREACHABLE;
            }
        }
        return Verdict.UNREACHABLE;
    }
}
