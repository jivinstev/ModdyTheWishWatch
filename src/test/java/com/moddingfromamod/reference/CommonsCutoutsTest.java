package com.moddingfromamod.reference;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** step 2: the Commons cut-out search. The network half is the bench's job. */
class CommonsCutoutsTest {

    @Test
    @DisplayName("one search request, files only, bitmaps only, scaled server-side, name encoded")
    void theQuery() {
        String q = CommonsCutouts.queryUrl("Red panda");
        assertTrue(q.startsWith("https://commons.wikimedia.org/w/api.php?"));
        assertTrue(q.contains("gsrnamespace=6"), "namespace 6 is File:, not articles");
        assertTrue(q.contains("filetype%3Abitmap"),
                "bitmaps only -- the measured arm (no SVGs, no PDFs, no audio)");
        assertTrue(q.contains("Red+panda"), q);
        assertTrue(q.contains("iiurlwidth="), "a thumbnail, not a 10 MB original");
        assertTrue(!q.contains("|"), "a raw pipe makes java.net.URI throw");
    }

    @Test
    @DisplayName("hits come back in RELEVANCE order, PNG/JPEG and big enough only, https only")
    void parsing() {
        String body = """
            {"query":{"pages":{
              "11":{"index":2,"title":"File:B.png","imageinfo":[{"mime":"image/png",
                    "width":900,"height":700,"thumburl":"https://upload.wikimedia.org/b.png"}]},
              "12":{"index":1,"title":"File:A.png","imageinfo":[{"mime":"image/png",
                    "width":900,"height":700,"thumburl":"https://upload.wikimedia.org/a.png"}]},
              "13":{"index":3,"title":"File:C.jpg","imageinfo":[{"mime":"image/jpeg",
                    "width":900,"height":700,"thumburl":"https://upload.wikimedia.org/c.jpg"}]},
              "14":{"index":4,"title":"File:icon.png","imageinfo":[{"mime":"image/png",
                    "width":40,"height":40,"thumburl":"https://upload.wikimedia.org/i.png"}]},
              "15":{"index":5,"title":"File:D.png","imageinfo":[{"mime":"image/png",
                    "width":900,"height":700,"url":"http://insecure.example/d.png"}]}
            }}}""";
        // A JPEG is kept since step 3: BackgroundMask can give a photo on a plain backdrop a mask.
        assertEquals(List.of("https://upload.wikimedia.org/a.png",
                "https://upload.wikimedia.org/b.png", "https://upload.wikimedia.org/c.jpg"),
                CommonsCutouts.parse(body, 6));
        assertEquals(List.of("https://upload.wikimedia.org/a.png"),
                CommonsCutouts.parse(body, 1));
    }

    @Test
    @DisplayName("an error page, a 429 text body or no hits is an empty list, never a throw")
    void anythingElseIsEmpty() {
        assertTrue(CommonsCutouts.parse(null, 6).isEmpty());
        assertTrue(CommonsCutouts.parse("Too many requests", 6).isEmpty());
        assertTrue(CommonsCutouts.parse("{\"batchcomplete\":\"\"}", 6).isEmpty());
        assertTrue(CommonsCutouts.urlsFor("", 6).isEmpty());
    }
}
