package com.moddingfromamod.reference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Pictures of things that are not characters — an axolotl, a pegasus — from Wikimedia Commons
 *.</b>
 *
 * <p>{@link WikiArtwork} reads the pages the researcher cited, and that is the right source for a
 * character: a franchise wiki is full of official renders on transparent backgrounds. A real
 * animal or a myth has no franchise wiki. Its pages are Wikipedia articles illustrated with
 * photographs and paintings — opaque, so {@link ReferencePalette} rejects every one — and the live
 * log said so: Pegasus resolved <i>0 urls</i>, Axolotl downloaded six and found <i>0 usable</i>.
 *
 * <p>Commons is where the free pictures of those subjects are, and it has a search API that
 * filters by file type. So this is ONE request, by NAME, for bitmaps; the downloader and the
 * subject-mask rule then decide what is usable exactly as they do for a wiki's renders. No
 * filename heuristic and no model.
 *
 * <p>It is a FALLBACK, used only for a {@link SubjectKind#CREATURE} and only when the cited pages
 * offered too few USABLE pictures. Never for a character: a stranger's photo of a cosplay is not
 * a reference for Spikor.
 */
public final class CommonsCutouts {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommonsCutouts.class);

    /** The one host this asks. A constant, not a table: it is the same for every subject. */
    static final String API = "https://commons.wikimedia.org/w/api.php";

    /** How many search hits to consider. Each is a candidate, not a download. */
    static final int HITS = 20;

    /**
     * What is searched, {@code %s} being the subject. Every arm was measured on the frozen
     * corpus (docs/REFERENCE_LOOKUP_RESULTS.md):
     * <ul>
     *   <li>{@code "%s transparent filemime:png"} finds SILHOUETTES — transparent, so usable, and
     *       solid black: Tiger and Red panda came back TRUSTED "black 73" and "black 100";</li>
     *   <li>{@code "%s filemime:png"} finds the species files ("Gold Axolotl.png"), mostly opaque;
     *       with backdrop masking it left one confident wrong palette (Griffin "white 42");</li>
     *   <li>{@code "%s filetype:bitmap"} — PNG and JPEG, the photographs {@link BackgroundMask}
     *       can cut out — measured the same correct palettes with that wrong one gone.</li>
     * </ul>
     * Not final only so {@link ReferenceBench} can measure an alternative; nothing else writes it.
     */
    static volatile String searchTemplate = "%s filetype:bitmap";

    private CommonsCutouts() {
    }

    /** The search request: files only (namespace 6), bitmaps only, scaled server-side. */
    static String queryUrl(String subject) {
        String search = String.format(java.util.Locale.ROOT, searchTemplate, subject.trim());
        return API + "?action=query&generator=search&gsrnamespace=6&gsrlimit=" + HITS
                + "&gsrsearch=" + URLEncoder.encode(search, StandardCharsets.UTF_8)
                + "&prop=imageinfo&iiprop=url%7Csize%7Cmime&iiurlwidth=" + WikiArtwork.THUMB_WIDTH
                + "&format=json";
    }

    /** Candidate image URLs for a subject, in Commons' relevance order. Never throws. */
    public static List<String> urlsFor(String subject, int limit) {
        if (subject == null || subject.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            String body = WikiArtwork.getText(queryUrl(subject));
            return parse(body, limit);
        } catch (Exception e) {
            LOGGER.debug("no Commons cut-outs for {}: {}", subject, e.toString());
            return List.of();
        }
    }

    /** Pure, so the response shape is unit-tested. Ordered by the search's own {@code index}. */
    static List<String> parse(String body, int limit) {
        if (body == null || !body.trim().startsWith("{")) {
            return List.of();
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("query") || !root.getAsJsonObject("query").has("pages")) {
            return List.of();
        }
        List<JsonObject> hits = new ArrayList<>();
        for (var e : root.getAsJsonObject("query").getAsJsonObject("pages").entrySet()) {
            hits.add(e.getValue().getAsJsonObject());
        }
        hits.sort((a, b) -> Integer.compare(idx(a), idx(b)));
        List<String> out = new ArrayList<>();
        for (JsonObject p : hits) {
            if (out.size() >= limit || !p.has("imageinfo")) {
                continue;
            }
            JsonObject ii = p.getAsJsonArray("imageinfo").get(0).getAsJsonObject();
            String mime = ii.has("mime") ? ii.get("mime").getAsString() : "";
            int w = ii.has("width") ? ii.get("width").getAsInt() : 0;
            int h = ii.has("height") ? ii.get("height").getAsInt() : 0;
            if (!(WikiArtwork.ALPHA_FORMATS.contains(mime)
                    || WikiArtwork.OPAQUE_FORMATS.contains(mime))
                    || w < WikiArtwork.MIN_EDGE || h < WikiArtwork.MIN_EDGE) {
                continue;
            }
            String url = ii.has("thumburl") ? ii.get("thumburl").getAsString()
                    : ii.has("url") ? ii.get("url").getAsString() : "";
            if (url.startsWith("https://") && !out.contains(url)) {
                out.add(url);
            }
        }
        return List.copyOf(out);
    }

    private static int idx(JsonObject p) {
        return p.has("index") ? p.get("index").getAsInt() : Integer.MAX_VALUE;
    }
}
