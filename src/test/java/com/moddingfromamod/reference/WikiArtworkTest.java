package com.moddingfromamod.reference;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Deriving artwork URLs from a page the researcher already cited.</b>
 *
 * <p>The parsing is pure and is tested here; the network half is Gate 4, which is a live
 * measurement rather than an assertion — a unit test that needs a wiki to be up is a test that
 * fails for reasons that have nothing to do with this repo.
 */
class WikiArtworkTest {

    @Test
    @DisplayName("both MediaWiki URL layouts yield the same title")
    void titlesComeOutOfEitherLayout() {
        // MediaWiki ships two: /wiki/Title (Wikipedia, Fandom, Miraheze) and a bare /Title
        // (plumowiki.com). A resolver that knows only one silently returns nothing for half the
        // wikis a child's character lives on.
        assertEquals("Spikor", WikiArtwork.titleOf(URI.create("https://www.plumowiki.com/Spikor")));
        assertEquals("Spikor", WikiArtwork.titleOf(
                URI.create("https://en.wikipedia.org/wiki/Spikor")));
        assertEquals("Omega Lad", WikiArtwork.titleOf(
                URI.create("https://italianbrainrotfanon.miraheze.org/wiki/Omega_Lad")),
                "an underscore is how MediaWiki spells a space in a URL");
        assertEquals("Spikor (character)", WikiArtwork.titleOf(
                URI.create("https://en.wikipedia.org/wiki/Spikor_%28character%29")),
                "a percent-encoded title was not decoded, so the API would be asked for a page "
                        + "whose name contains a literal %28");
        assertNull(WikiArtwork.titleOf(URI.create("https://www.plumowiki.com/")));
    }

    @Test
    @DisplayName("#1219: the API is wherever the page's EditURI says — Wikipedia's is /w/api.php")
    void theApiEndpointIsReadOffThePage() {
        // The real heads, trimmed. The old rule guessed /api.php for every wiki, which is a 404
        // on Wikipedia -- and this test used to ASSERT that 404 path, so it passed over the bug
        // that made every Wikipedia source yield zero images.
        String wikipedia = "<head><link rel=\"EditURI\" type=\"application/rsd+xml\" "
                + "href=\"//en.wikipedia.org/w/api.php?action=rsd\"/></head>";
        assertEquals("https://en.wikipedia.org/w/api.php", WikiArtwork.editUriApi(wikipedia,
                URI.create("https://en.wikipedia.org/wiki/Spikor_(character)")));
        String fandom = "<link rel=\"EditURI\" type=\"application/rsd+xml\" "
                + "href=\"https://dinko.fandom.com/api.php?action=rsd\">";
        assertEquals("https://dinko.fandom.com/api.php", WikiArtwork.editUriApi(fandom,
                URI.create("https://dinko.fandom.com/wiki/Dinko")));
        // Attribute order, quoting and a path-relative href all occur in the wild.
        assertEquals("https://www.plumowiki.com/api.php", WikiArtwork.editUriApi(
                "<LINK href='/api.php?action=rsd' rel='EditURI'>",
                URI.create("https://www.plumowiki.com/Spikor")));
    }

    @Test
    @DisplayName("#1219: a hostile EditURI cannot point the next request anywhere but the wiki")
    void aHostileEditUriIsRefused() {
        URI page = URI.create("https://evil.example/wiki/X");
        // Another host -- public or not -- is not this wiki, and is never asked.
        assertNull(WikiArtwork.editUriApi(
                "<link rel=\"EditURI\" href=\"https://169.254.169.254/w/api.php\">", page));
        assertNull(WikiArtwork.editUriApi(
                "<link rel=\"EditURI\" href=\"//other.example/w/api.php\">", page));
        // Downgraded to http, or not an API at all.
        assertNull(WikiArtwork.editUriApi(
                "<link rel=\"EditURI\" href=\"http://evil.example/w/api.php\">", page));
        assertNull(WikiArtwork.editUriApi(
                "<link rel=\"EditURI\" href=\"https://evil.example/upload/x.php\">", page));
        assertNull(WikiArtwork.editUriApi(
                "<link rel=\"EditURI\" href=\"javascript:alert(1)\">", page));
        // No link, another rel, an empty page: nothing declared, so the guesses are used.
        assertNull(WikiArtwork.editUriApi("<link rel=\"stylesheet\" href=\"/api.php\">", page));
        assertNull(WikiArtwork.editUriApi("", page));
        assertNull(WikiArtwork.editUriApi(null, page));
    }

    @Test
    @DisplayName("#1219: the title is the one the wiki declares, so /w/Creeper is readable")
    void theTitleComesFromThePageConfig() {
        // minecraft.wiki serves articles at /w/Title, which the URL rule cannot tell from a
        // nested path -- so Creeper resolved NOTHING in the baseline, in 53 ms.
        assertEquals("Creeper", WikiArtwork.pageName(
                "<script>RLCONF={\"wgCanonicalNamespace\":\"\",\"wgPageName\":\"Creeper\","));
        assertEquals("Spikor (character)", WikiArtwork.pageName(
                "\"wgPageName\": \"Spikor_(character)\""));
        assertEquals("Pok\u00e9mon", WikiArtwork.pageName("\"wgPageName\":\"Pok\\u00e9mon\""));
        assertNull(WikiArtwork.pageName("<head></head>"));
        assertNull(WikiArtwork.pageName(null));
    }

    @Test
    @DisplayName("without an EditURI it tries /w/api.php then /api.php, keeping any prefix")
    void theGuessesAreWikipediasLayoutThenTheRoot() {
        assertEquals(List.of("https://en.wikipedia.org/w/api.php",
                        "https://en.wikipedia.org/api.php"),
                WikiArtwork.guessedApis(URI.create("https://en.wikipedia.org/wiki/Spikor")));
        assertEquals(List.of("https://www.plumowiki.com/w/api.php",
                        "https://www.plumowiki.com/api.php"),
                WikiArtwork.guessedApis(URI.create("https://www.plumowiki.com/Spikor")));
        assertEquals(List.of("https://example.com/sub/w/api.php", "https://example.com/sub/api.php"),
                WikiArtwork.guessedApis(URI.create("https://example.com/sub/wiki/Thing")));
    }

    @Test
    @DisplayName("#1219 step 5: cited pages are asked in parallel, under one bounded deadline")
    void pagesAreAskedInParallelUnderADeadline() throws Exception {
        assertTrue(WikiArtwork.PAGES_DEADLINE.toSeconds() <= 15,
                "the deadline is what stops one slow site holding the lookup hostage");
        // Daemon threads: a lookup in flight must never hold a server's shutdown open.
        assertTrue(WikiArtwork.POOL.submit(() -> Thread.currentThread().isDaemon()).get());
        // Refused before a socket opens, so this stays offline -- and ordering survives.
        assertTrue(WikiArtwork.urlsFrom(List.of("https://127.0.0.1/wiki/A",
                "https://10.0.0.1/wiki/B"), 6, true).isEmpty());
    }

    @Test
    @DisplayName("it carries NO table of which wiki a character lives on, and must not gain one")
    void thereIsNoHardcodedWikiList() throws Exception {
        // A host table is a hardcoded-character list wearing a hat: it works for whatever was in
        // it on the day and silently fails for the meme the child actually asked about. The wiki
        // comes from the researcher's own sources, so an unheard-of wiki works identically.
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/reference/WikiArtwork.java"));
        // Strip the prose: the class note and the tests may NAME these as examples.
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        for (String host : List.of("plumowiki", "fandom", "miraheze", "wikipedia")) {
            assertTrue(!code.contains(host),
                    "WikiArtwork's CODE names '" + host + "'. The wiki must come from the "
                            + "researcher's sources, never from a list here.");
        }
    }

    @Test
    @DisplayName("a non-wiki, a search page and a hostile URL all return nothing rather than throw")
    void anythingUnexpectedIsEmptyNotFatal() {
        // Every one of these is refused before a socket opens, so this stays offline.
        assertTrue(WikiArtwork.forPage("https://127.0.0.1/wiki/X").isEmpty());
        assertTrue(WikiArtwork.forPage("http://example.com/wiki/X").isEmpty(), "not https");
        assertTrue(WikiArtwork.forPage("not a url").isEmpty());
        assertTrue(WikiArtwork.forPage("").isEmpty());
        assertTrue(WikiArtwork.urlsFrom(null, 4).isEmpty());
        assertTrue(WikiArtwork.urlsFrom(List.of("https://127.0.0.1/wiki/X"), 4).isEmpty());
    }

    @Test
    @DisplayName("REAL BUG: it asks the wiki to SCALE the image rather than sending the original")
    void itAsksForAThumbnail() throws Exception {
        // A wiki's character renders run 5-10 MB. Downloading them blew straight past the
        // downloader's size cap, so five of six characters came back with "no artwork" and
        // timings that looked like a fast success. The histogram samples down to 512px anyway,
        // so the full-resolution pixels were never counted by anything.
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/reference/WikiArtwork.java"));
        assertTrue(src.contains("iiurlwidth"),
                "the API request no longer asks for a scaled image, so it will download whole "
                        + "multi-megabyte renders to sample them down to 512px");
        assertTrue(src.contains("thumburl"),
                "the scaled URL is requested but never read, so the original is fetched anyway");
        assertTrue(WikiArtwork.THUMB_WIDTH >= 512,
                "scaling below the histogram's own sample size throws away real pixels");
        // A raw pipe in a query string makes java.net.URI THROW, which the catch turned into
        // "this page has no artwork" for every character at once.
        assertTrue(!src.contains("iiprop=url|"), "a raw pipe is back; java.net.URI rejects it");
    }

    @Test
    @DisplayName("filtering is by MIME and size only — Gate 0 measured filename heuristics failing")
    void noFilenameHeuristics() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/reference/WikiArtwork.java"));
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        // Requiring the character's name in the filename moved Gate 0 from 0/4 to 1/4 and could
        // not see the failure that mattered -- the character sitting in a go-kart. The defence
        // against a bad image is the MEDIAN OF N, not a cleverer filter.
        assertTrue(!code.contains("toLowerCase(") || !code.contains("contains(character"),
                "a filename heuristic crept back in");
        assertTrue(code.contains("image/png"),
                "the PNG filter is the load-bearing one: transparency is the subject mask");
    }

    @org.junit.jupiter.api.Test
    void aRateLimitWaitsBrieflyOnce() {
        // a 429 is retried once after the server's Retry-After, never more than 2 s and never less than 0.25 s.
        org.junit.jupiter.api.Assertions.assertEquals(1_000, WikiArtwork.retryAfterMs("1"));
        org.junit.jupiter.api.Assertions.assertEquals(2_000, WikiArtwork.retryAfterMs("120"));
        org.junit.jupiter.api.Assertions.assertEquals(250, WikiArtwork.retryAfterMs("0"));
        org.junit.jupiter.api.Assertions.assertEquals(1_000, WikiArtwork.retryAfterMs(""));
        org.junit.jupiter.api.Assertions.assertEquals(1_000, WikiArtwork.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
    }

    @org.junit.jupiter.api.Test
    void theDescribersOwnPictureSearchGuessesAFandomWikiAndUsesTheUploadHost() throws Exception {
        // Tabbycat's and Duffington's lookups cited pet-care and shop pages; their own wikis had the picture.
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("tabbycat", "tabbycats"), CharacterPictures.fandomGuesses("Tabbycat"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("duffingtonbear", "duffington", "bear", "duffingtonbears"),
                CharacterPictures.fandomGuesses("Duffington Bear"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("webslinger", "webslingers"), CharacterPictures.fandomGuesses("Web-Slinger"));
        // CharacterPictures guesses from the NAME; it must never carry a character-to-wiki table (the rule above).
        String cp = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/reference/CharacterPictures.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        for (String table : List.of("Map.of", "Map.entry", ".put(", "tabbycat", "duffington", "plumo")) {
            org.junit.jupiter.api.Assertions.assertFalse(cp.toLowerCase(java.util.Locale.ROOT).contains(table.toLowerCase(java.util.Locale.ROOT)),
                    "CharacterPictures' code names '" + table + "': a per-character table is the thing it must never become");
        }
        org.junit.jupiter.api.Assertions.assertEquals(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/0/07/X.jpg/960px-X.jpg",
                WikiArtwork.canonical("https://thumb.wikimedia.org/wikipedia/commons/thumb/0/07/X.jpg/960px-X.jpg?utm_source=en.wikipedia.org"));
        org.junit.jupiter.api.Assertions.assertEquals("https://static.wikia.nocookie.net/a.png",
                WikiArtwork.canonical("https://static.wikia.nocookie.net/a.png"));
    }
}
