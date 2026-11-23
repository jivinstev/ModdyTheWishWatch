package com.moddingfromamod.reference;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The artwork on a page, asked for by machine instead of guessed at by a model.</b>
 *
 * <p>{@link ReferencePalette} needs image FILE urls. A model can name them, and is told to, but it
 * is naming them from a page it read as text — so it produces file-description pages, thumbnails,
 * dead links and occasionally a plausible URL that never existed. Every one of those is a silent
 * subtraction from the median.
 *
 * <p>Nearly every character wiki in the world is MediaWiki — Wikipedia, Fandom, Miraheze,
 * plumowiki.com — and MediaWiki has an API that will simply list a page's images with their real
 * URLs, MIME types and dimensions, in <b>one request</b>. So when the researcher has already cited
 * a page, the artwork can be derived from it rather than recalled.
 *
 * <p><b>This is also the fast path.</b> Measured: the API answers in well under a second and the
 * download plus histogram is 0.57s, against 75–109s for the lookup that produced the page URL. It
 * needs no model and no search.
 *
 * <h2>What it deliberately does NOT do</h2>
 *
 * <p>It does not carry a table of "the wiki for Plumo is plumowiki.com". That table is a
 * hardcoded-character list wearing a hat: it works for whatever was in it on the day and silently
 * fails for the meme the child actually asked about. The wiki comes from the researcher's own
 * {@code sources}, so a character on a wiki nobody has heard of works exactly as well.
 *
 * <p>Filtering is by MIME and size only — no filename heuristics. Gate 0 measured those:
 * requiring the character's name in the filename moved the pass rate 0/4 → 1/4 and could not see
 * the failure that mattered, which was the character sitting in a go-kart. The defence against a
 * bad image is the MEDIAN OF N, not a cleverer filter.
 */
public final class WikiArtwork {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikiArtwork.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** Small artwork carries no palette; a page's icons and badges are all under this. */
    static final int MIN_EDGE = 200;

    /**
     * What width to ask the wiki to scale to. {@link ReferencePalette} samples down to 512px, so
     * anything above this is bytes nobody counts — measured, it turns an 8.6 MB download into a
     * few hundred KB, which is most of why the whole path fits inside a second.
     */
    static final int THUMB_WIDTH = 800;

    /**
     * A real one: several wikis answer 403 to a default Java agent, which reads as "no artwork".
     * And one with a CONTACT, which is what Wikimedia's User-Agent policy asks of a bot:
     * a client it cannot identify is the first one it rate-limits.
     */
    static final String AGENT = "moddythewishwatch/1.0 "
            + "(+https://github.com/jivinstev/ModdyTheWishWatch; Minecraft mod; "
            + "creature reference lookup)";

    /**
     * Formats that carry an alpha channel. The palette needs the subject cut out from its
     * background; a format without alpha measures the background.
     *
     * <p>Not intersected with what we can decode, deliberately: a wiki declaring a format we
     * cannot read should be visible as a download that was refused, not as a page that had no
     * artwork. Those two look identical from here and need different fixes.
     */
    static final java.util.Set<String> ALPHA_FORMATS = java.util.Set.of("image/png", "image/webp");

    /**
     * Formats with no alpha, offered only for a {@link SubjectKind#CREATURE} and AFTER every
     * alpha-capable one (step 3). A photograph or a painting is measurable once
     * {@link BackgroundMask} has found it a backdrop, and a real animal's pages hold nothing else.
     */
    static final java.util.Set<String> OPAQUE_FORMATS = java.util.Set.of("image/jpeg");

    private WikiArtwork() {
    }

    /** One candidate image, biggest first. */
    public record Art(String title, String url, int width, int height, String mime) {
        long pixels() {
            return (long) width * height;
        }

        /** Alpha-capable first, then biggest: renders before photos, artwork before icons. */
        static final java.util.Comparator<Art> BEST_FIRST = java.util.Comparator
                .comparing((Art a) -> !ALPHA_FORMATS.contains(a.mime()))
                .thenComparing(Art::pixels, java.util.Comparator.reverseOrder());
    }

    /**
     * Artwork on the page a source URL points at, or an empty list for anything that is not a
     * reachable MediaWiki page. Never throws.
     *
     * <p>Ordered biggest first, then capped by the caller — a wiki's largest images are its
     * artwork, and its smallest are navigation.
     */
    public static List<Art> forPage(String sourceUrl) {
        return forPage(sourceUrl, false);
    }

    /** With {@code includeOpaque}, a page's JPEGs too — for a {@link SubjectKind#CREATURE}. */
    public static List<Art> forPage(String sourceUrl, boolean includeOpaque) {
        try {
            URI page = URI.create(sourceUrl.trim());
            if (!ReferenceImages.allowed(page)) {
                return none(sourceUrl, "the address is not a public https host");
            }
            // FOLLOW THE PAGE TO WHERE IT LIVES, then ask it where its API is. A cited URL may
            // redirect (a mobile host, an old title), and the API is wherever the wiki SAYS it
            // is -- not wherever a convention guesses.
            Located at = locate(page);
            String title = at.title();
            if (title == null) {
                return none(sourceUrl, "no article title in the address");
            }
            String body = null;
            List<String> answers = new ArrayList<>();
            for (String api : at.apis()) {
                HttpResponse<String> res = getRaw(queryUrl(api, title));
                answers.add(res.statusCode() + "");
                body = res.statusCode() == 200 ? res.body() : null;
                if (body != null && body.trim().startsWith("{")) {
                    break;      // a MediaWiki API answered; a 404 page or HTML does not
                }
                body = null;
            }
            if (body == null) {
                // an HTML error page, a redirect, a rate limit (429), a wiki without an API
                return none(sourceUrl, "no MediaWiki API answer (HTTP " + String.join(", ", answers) + ")");
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject pages = root.has("query") && root.getAsJsonObject("query").has("pages")
                    ? root.getAsJsonObject("query").getAsJsonObject("pages") : null;
            if (pages == null) {
                return List.of();
            }
            List<Art> out = new ArrayList<>();
            for (var e : pages.entrySet()) {
                JsonObject p = e.getValue().getAsJsonObject();
                if (!p.has("imageinfo")) {
                    continue;
                }
                JsonObject ii = p.getAsJsonArray("imageinfo").get(0).getAsJsonObject();
                String mime = str(ii, "mime");
                int w = ii.has("width") ? ii.get("width").getAsInt() : 0;
                int h = ii.has("height") ? ii.get("height").getAsInt() : 0;
                // ALPHA-CAPABLE FORMATS ONLY, and this is the single most load-bearing filter
                // here. Transparency is the subject mask: a JPEG has none, so its background is
                // counted and the palette becomes the palette of a white rectangle.
                //
                // It used to say PNG, which was the same rule written as one format -- and that
                // cost us every Fandom character. Fandom's API DECLARES image/png and its
                // thumbnailer SERVES image/webp, so the filter passed and the download was then
                // refused downstream. WebP carries alpha exactly as PNG does; the reason to
                // exclude JPEG is the alpha channel, not the file extension.
                if (!(ALPHA_FORMATS.contains(mime)
                        || (includeOpaque && OPAQUE_FORMATS.contains(mime)))
                        || w < MIN_EDGE || h < MIN_EDGE) {
                    continue;
                }
                // thumburl when the wiki scaled it, url when it declined (already small enough).
                String thumb = str(ii, "thumburl");
                out.add(new Art(str(p, "title"), canonical(thumb.isBlank() ? str(ii, "url") : thumb), w, h,
                        mime));
            }
            out.sort(Art.BEST_FIRST);
            return List.copyOf(out);
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            // OUR bug, not the wiki's: a URL this code built is malformed. Loud, because the
            // symptom of the quiet version is indistinguishable from "that page has no artwork".
            LOGGER.warn("wiki artwork request was malformed for {} — this is a bug here, not a "
                    + "missing page: {}", sourceUrl, e.toString());
            return List.of();
        } catch (Exception e) {
            // Theirs: unreachable, not a wiki, no API, rate-limited. Ordinary, but said at INFO with the
            // reason: "0 url(s)" alone could not tell a rate limit from a page with no artwork.
            LOGGER.info("no wiki artwork for {}: {}", sourceUrl, e.toString());
            return List.of();
        }
    }

    /**
     * Wikimedia's newer thumbnail host, {@code thumb.wikimedia.org}, serves the same paths as {@code upload.wikimedia.org}
     * and is not reachable everywhere upload is (Pippo Rabbit's six pictures failed on it). Use upload, and drop
     * the tracking query string the API appends.
     */
    static String canonical(String url) {
        if (url == null) {
            return "";
        }
        String u = url.startsWith("https://thumb.wikimedia.org/") ? "https://upload.wikimedia.org/" + url.substring(28) : url;
        int q = u.indexOf("?utm_");
        return q > 0 ? u.substring(0, q) : u;
    }

    /** Why a cited page gave no artwork, said once at INFO: "0 url(s)" alone hid a rate limit. */
    private static List<Art> none(String sourceUrl, String why) {
        LOGGER.info("no wiki artwork for {}: {}", sourceUrl, why);
        return List.of();
    }

    /** The image query against one API endpoint. */
    static String queryUrl(String api, String title) {
        return api + "?action=query&generator=images&titles="
                + URLEncoder.encode(title, StandardCharsets.UTF_8)
                // %7C, not a raw pipe: java.net.URI is stricter than curl and THROWS on it.
                // That threw straight into the catch below and returned "no artwork" -- a
                // programming error wearing the costume of a wiki that had nothing. Every
                // character in the first sweep came back empty and the timings looked
                // plausible (0.8s), because a failure that fast reads like a fast success.
                // ASK FOR A THUMBNAIL, NOT THE ORIGINAL. A histogram is downsampled to 512px
                // anyway, so full-resolution artwork is pure waste -- and it is not small
                // waste: a wiki's character renders run 5-10 MB, which blew straight past the
                // downloader's size cap and presented as "this character has no artwork".
                // iiurlwidth makes MediaWiki scale it server-side, preserving PNG (and so the
                // alpha channel, which is the subject mask this whole thing depends on).
                + "&prop=imageinfo&iiprop=url%7Csize%7Cmime&iiurlwidth=" + THUMB_WIDTH
                + "&gimlimit=60&format=json";
    }

    /** The best guesses across every page the researcher cited, de-duplicated, biggest first. */
    public static List<String> urlsFrom(List<String> sources, int limit) {
        return urlsFrom(sources, limit, false);
    }

    public static List<String> urlsFrom(List<String> sources, int limit, boolean includeOpaque) {
        // IN PARALLEL, UNDER ONE DEADLINE (step 5). Each page now costs a head read plus
        // an API call, and serially that put Spikor's three wikis at 6-13 s. One slow or
        // tar-pitted site may not hold the rest hostage: what has not answered by the deadline
        // is simply not counted.
        List<String> srcs = sources == null ? List.of() : sources;
        List<java.util.concurrent.Future<List<Art>>> pending = new ArrayList<>();
        for (String s : srcs) {
            pending.add(page(s, includeOpaque));
        }
        long deadline = System.nanoTime() + PAGES_DEADLINE.toNanos();
        List<Art> all = new ArrayList<>();
        for (var f : pending) {
            try {
                all.addAll(f.get(Math.max(0, deadline - System.nanoTime()),
                        java.util.concurrent.TimeUnit.NANOSECONDS));
            } catch (Exception e) {
                // late, or failed: either way not counted. NOT cancelled: a page
                // lookup can be shared with the early start, and it costs nothing to let it land.
            }
        }
        all.sort(Art.BEST_FIRST);
        List<String> urls = new ArrayList<>();
        for (Art a : all) {
            if (urls.size() >= limit) {
                break;
            }
            if (!urls.contains(a.url())) {
                urls.add(a.url());
            }
        }
        return List.copyOf(urls);
    }

    // ──: a page's artwork, looked up once and shared for a couple of minutes ──────────

    static final long PAGE_TTL_MS = 120_000;
    static final int PAGE_MAX = 32;

    private record Page(java.util.concurrent.CompletableFuture<List<Art>> future, long at) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Page> PAGES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * <b>This page's artwork, started now or joined if already started.</b> The researcher opens
     * the page it will cite ~10 s before it answers (measured: Plumo's wiki page at 3.5 s of a
     * 17.9 s run), so the lookup can begin then and the final {@link #urlsFrom} finds it done.
     * Kept {@link #PAGE_TTL_MS}: long enough for one lookup, short enough that a wiki edit is seen.
     */
    static java.util.concurrent.CompletableFuture<List<Art>> page(String source,
                                                                   boolean includeOpaque) {
        String key = includeOpaque + "|" + source;
        long now = System.currentTimeMillis();
        if (PAGES.size() >= PAGE_MAX) {
            PAGES.entrySet().removeIf(e -> now - e.getValue().at() > PAGE_TTL_MS);
        }
        Page p = PAGES.get(key);
        if (p != null && now - p.at() <= PAGE_TTL_MS) {
            return p.future();
        }
        var f = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> forPage(source, includeOpaque), POOL);
        if (PAGES.size() < PAGE_MAX) {
            PAGES.put(key, new Page(f, now));
        }
        return f;
    }

    /** For tests: forget every remembered page. */
    static void clearPagesForTest() {
        PAGES.clear();
    }

    /** The whole cited-page step, however many pages there are. */
    static final Duration PAGES_DEADLINE = Duration.ofSeconds(12);

    /** Daemon threads, so a lookup in flight never holds a server's shutdown open. */
    static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "reference-pages");
                t.setDaemon(true);
                return t;
            });

    /**
     * The page title from a wiki URL. Handles both layouts MediaWiki ships with —
     * {@code /wiki/Title} and a bare {@code /Title} — and refuses anything with a query string,
     * which is a search or a diff rather than an article.
     */
    static String titleOf(URI page) {
        String path = page.getPath();
        if (path == null || path.isBlank() || path.equals("/")) {
            return null;
        }
        int w = path.indexOf("/wiki/");
        String t = w >= 0 ? path.substring(w + 6) : path.substring(1);
        if (t.isBlank() || t.contains("/")) {
            // A nested path is not an article on any MediaWiki layout, and guessing costs a
            // request that will 404.
            return w >= 0 && !t.contains("/") ? t : (w >= 0 ? t : null);
        }
        // MediaWiki spells a space as an underscore in a URL and treats the two as the same
        // title, so normalise rather than asking the API for a page whose name has a literal "_".
        return java.net.URLDecoder.decode(t, StandardCharsets.UTF_8).replace('_', ' ');
    }

    /** Where a cited page really lives, and the API endpoints to try for it, best first. */
    record Located(URI page, List<String> apis, String title) {
    }

    /** How much of a page is read looking for its {@code <head>}. The link sits near the top. */
    static final int HEAD_BYTES = 192 * 1024;

    /**
     * <b>Ask the page where its API is.</b> Every MediaWiki publishes
     * {@code <link rel="EditURI" href="//host/w/api.php?action=rsd">} in its head, so the answer is
     * read rather than guessed — and guessing was a real, total failure: the old rule put the API
     * at {@code /api.php}, which is right for Fandom and plumowiki.com and a <b>404 on
     * Wikipedia</b>, whose API is {@code /w/api.php}. Every Wikipedia page the researcher cited
     * yielded zero images, and the test asserted the wrong path, so it passed over the bug.
     *
     * <p>Falls back to the two conventional paths when the page has no such link or cannot be
     * read. Never throws.
     */
    static Located locate(URI page) {
        URI at = page;
        String html = null;
        try {
            for (int hop = 0; hop <= 3; hop++) {
                if (!ReferenceImages.allowed(at)) {
                    break;
                }
                HttpResponse<java.io.InputStream> res = client().send(
                        HttpRequest.newBuilder(at).timeout(TIMEOUT)
                                .header("User-Agent", AGENT).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                int code = res.statusCode();
                if (code >= 300 && code < 400) {
                    res.body().close();
                    String loc = res.headers().firstValue("location").orElse(null);
                    if (loc == null) {
                        break;
                    }
                    at = at.resolve(loc);
                    continue;
                }
                try (var in = res.body()) {
                    if (code == 200) {
                        html = new String(in.readNBytes(HEAD_BYTES), StandardCharsets.UTF_8);
                    }
                }
                break;
            }
        } catch (Exception e) {
            LOGGER.debug("could not read {} for its EditURI: {}", page, e.toString());
        }
        if (!ReferenceImages.allowed(at)) {
            at = page;
        }
        List<String> apis = new ArrayList<>();
        String declared = editUriApi(html, at);
        if (declared != null) {
            apis.add(declared);
        }
        for (String guess : guessedApis(at)) {
            if (!apis.contains(guess)) {
                apis.add(guess);
            }
        }
        // The title the WIKI uses, when it says: minecraft.wiki's articles live at /w/Creeper,
        // a layout the URL rule cannot read, and a redirect lands on a title of its own.
        String declaredTitle = pageName(html);
        return new Located(at, List.copyOf(apis),
                declaredTitle != null ? declaredTitle : titleOf(at));
    }

    private static final java.util.regex.Pattern PAGE_NAME =
            java.util.regex.Pattern.compile(
                    "\"wgPageName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,200})\"");

    /**
     * The article's own name from MediaWiki's page config
     * ({@code "wgPageName":"Spikor_(character)"}), or null. Read as DATA: it is only ever URL-encoded into the {@code titles=} parameter.
     */
    static String pageName(String html) {
        if (html == null) {
            return null;
        }
        var m = PAGE_NAME.matcher(html);
        if (!m.find()) {
            return null;
        }
        try {
            String t = JsonParser.parseString("\"" + m.group(1) + "\"").getAsString()
                    .replace('_', ' ').trim();
            return t.isEmpty() || t.contains("\n") ? null : t;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern LINK =
            java.util.regex.Pattern.compile("<link\\b[^>]*>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern ATTR = java.util.regex.Pattern.compile(
            "\\b(rel|href)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The API endpoint a page's {@code EditURI} link declares, or null.
     *
     * <p><b>The page is untrusted, so what it declares is checked like a URL a model named:</b>
     * https only, and only on the page's OWN host. A page cannot use this to point our next
     * request at another machine — the SSRF gate would refuse a private address anyway, but a
     * public third-party host is not the wiki and has no business being asked about its images.
     */
    static String editUriApi(String html, URI page) {
        if (html == null || page == null || page.getHost() == null) {
            return null;
        }
        var links = LINK.matcher(html);
        while (links.find()) {
            String rel = null;
            String href = null;
            var a = ATTR.matcher(links.group());
            while (a.find()) {
                String v = a.group(2) != null ? a.group(2) : a.group(3);
                if (a.group(1).equalsIgnoreCase("rel")) {
                    rel = v;
                } else {
                    href = v;
                }
            }
            if (rel == null || href == null || !rel.trim().equalsIgnoreCase("EditURI")) {
                continue;
            }
            try {
                URI api = page.resolve(href.trim().replace("&amp;", "&"));
                if (!"https".equalsIgnoreCase(api.getScheme())
                        || !page.getHost().equalsIgnoreCase(api.getHost())
                        || api.getPath() == null || !api.getPath().endsWith("api.php")) {
                    return null;
                }
                return "https://" + api.getRawAuthority() + api.getRawPath();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * The conventional endpoints, for a page whose head could not be read: {@code /w/api.php}
     * (Wikipedia, and MediaWiki's own default install layout) then {@code /api.php} (Fandom,
     * plumowiki.com). A wiki served under a prefix keeps it.
     */
    static List<String> guessedApis(URI page) {
        String path = page.getPath() == null ? "" : page.getPath();
        int w = path.indexOf("/wiki/");
        String root = page.getScheme() + "://" + page.getRawAuthority()
                + (w > 0 ? path.substring(0, w) : "");
        return List.of(root + "/w/api.php", root + "/api.php");
    }

    private static volatile HttpClient client;

    private static HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            synchronized (WikiArtwork.class) {
                if (client == null) {
                    client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NEVER).build();
                }
                c = client;
            }
        }
        return c;
    }

    /**
     * A GET with this class's agent, client and timeout, whatever the status — for
     * {@link ReferenceBench}'s page probe, which needs the 403s and 404s that {@link #get} hides.
     * The probe sends nothing of its own: every request it makes goes through here.
     */
    static HttpResponse<String> getRaw(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("User-Agent", AGENT).GET().build();
        HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 429) {
            // RATE-LIMITED: Wikimedia and Fandom answer 429 to a busy address, and the brief then
            // had no pictures at all. One short wait and one retry; a second 429 is the answer.
            Thread.sleep(retryAfterMs(res.headers().firstValue("Retry-After").orElse("")));
            res = client().send(req, HttpResponse.BodyHandlers.ofString());
        }
        return res;
    }

    /** How long to wait before the one retry: the server's Retry-After in seconds, at most 2 s. */
    static long retryAfterMs(String header) {
        try {
            return Math.max(250, Math.min(2_000, Long.parseLong(header.trim()) * 1000));
        } catch (NumberFormatException e) {
            return 1_000;
        }
    }

    /** A GET with this class's agent, client and timeout; the body on a 200, else null. */
    static String getText(String url) throws Exception {
        return get(url);
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> res = getRaw(url);
        return res.statusCode() == 200 ? res.body() : null;
    }

    static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    /** Unused today; kept honest by the tests rather than by a comment. */
    static String lower(JsonElement e) {
        return e == null ? "" : e.getAsString().toLowerCase(Locale.ROOT);
    }
}
