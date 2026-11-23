package com.moddingfromamod.reference;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.zip.CRC32;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Gate 3 of {@code docs/REFERENCE_PALETTE_PLAN.md}: the downloader is bounded and refuses
 * hostile input.</b>
 *
 * <p>Every test here feeds a HOSTILE case, not a happy path. This is the first code in the
 * reference subsystem that opens a socket from inside the game process, and it opens a URL that was
 * named by a model which had just read an untrusted web page. "It works on a wiki image" is not the
 * property that needs proving.
 *
 * <p>Offline by construction — none of this touches the network, because a safety check that only
 * runs when a remote host is reachable is a safety check that silently stops running.
 */
class ReferenceImagesTest {

    @Test
    @DisplayName("SSRF: loopback, private, link-local and carrier-NAT addresses are all refused")
    void itWillNotFetchFromInsideTheHouse() {
        // A URL is an instruction to make THIS machine fetch something. The interesting targets
        // are never public: 169.254.169.254 is the cloud metadata service.
        for (String u : List.of(
                "https://127.0.0.1/a.png",
                "https://localhost/a.png",
                "https://[::1]/a.png",
                "https://10.0.0.5/a.png",
                "https://192.168.1.1/a.png",
                "https://172.16.0.1/a.png",
                "https://169.254.169.254/latest/meta-data/",   // the one that matters
                "https://100.64.0.1/a.png",                    // carrier NAT / cloud runner
                "https://0.0.0.0/a.png")) {
            assertFalse(ReferenceImages.allowed(URI.create(u)), "must refuse " + u);
        }
    }

    @Test
    @DisplayName("SSRF: only https, and a URL must actually have a host")
    void onlyHttpsToARealHost() {
        assertFalse(ReferenceImages.allowed(URI.create("http://example.com/a.png")),
                "plain http would let a proxy rewrite the bytes on the way in");
        assertFalse(ReferenceImages.allowed(URI.create("file:///etc/passwd")));
        assertFalse(ReferenceImages.allowed(URI.create("ftp://example.com/a.png")));
        assertFalse(ReferenceImages.allowed(URI.create("https:///a.png")));
        assertFalse(ReferenceImages.allowed(null));
    }

    @Test
    @DisplayName("SSRF: a hostname that RESOLVES inward is refused, not just a literal address")
    void aNameThatPointsHomeIsStillHome() {
        // The check has to resolve. A denylist of strings loses to any domain whose A record the
        // attacker controls -- and several public ones already answer 127.0.0.1.
        assertFalse(ReferenceImages.isPublic(java.net.InetAddress.getLoopbackAddress()));
        assertFalse(ReferenceImages.allowed(URI.create("https://localhost./a.png")));
    }

    @Test
    @DisplayName("DECOMPRESSION BOMB: a huge declared size is refused on the HEADER, before decode")
    void aBombIsRefusedBeforeItIsAllocated() throws Exception {
        // A tiny PNG that CLAIMS 60000 x 60000. ImageIO.read would allocate 14 GB before noticing;
        // the header check costs nothing. Built by hand because no library will produce one.
        byte[] bomb = pngHeaderClaiming(60000, 60000);
        assertNull(ReferenceImages.decodeBounded(bomb),
                "a 60000x60000 declaration was accepted — the allocation is what kills you, and it "
                        + "happens inside ImageIO.read");
        // ...and the limits are the ones the plan states.
        assertTrue(ReferenceImages.MAX_DIMENSION <= 8000);
        assertTrue(ReferenceImages.MAX_PIXELS <= 40_000_000L);
    }

    @Test
    @DisplayName("junk that is not an image at all decodes to nothing rather than throwing")
    void junkIsSkippedNotFatal() throws Exception {
        // A wish must never break because a wiki moved a file and served an error page.
        assertNull(ReferenceImages.decodeBounded("<!DOCTYPE html><html>404</html>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(ReferenceImages.decodeBounded(new byte[0]));
        assertNull(ReferenceImages.decodeBounded(new byte[]{1, 2, 3, 4, 5}));
    }

    @Test
    @DisplayName("a real image still decodes — the guards are not just refusing everything")
    void theHappyPathSurvivesTheGuards() throws Exception {
        // A guard suite that only proves refusals would pass with fetchOne hard-coded to null.
        try (var in = ReferenceImagesTest.class.getResourceAsStream("/palette/fixture.png")) {
            assertNotNull(in);
            var img = ReferenceImages.decodeBounded(in.readAllBytes());
            assertNotNull(img, "the parity fixture no longer decodes");
            assertTrue(img.getWidth() > 0 && img.getHeight() > 0);
        }
    }

    @Test
    @DisplayName("REAL BUG: we only accept formats this JVM can actually DECODE")
    void theAllowlistIsWhatWeCanRead() {
        // The hand-written list named image/webp. ImageIO cannot read WebP without an extra
        // library -- and a wiki content-negotiated on the Accept header, served WebP, and EVERY
        // download decoded to null. Total, silent, and indistinguishable from a character having
        // no artwork: six of six images "downloaded" and zero were usable.
        //
        // The list described what we would ACCEPT rather than what we can READ.
        var canRead = new java.util.HashSet<String>();
        for (String m : javax.imageio.ImageIO.getReaderMIMETypes()) {
            canRead.add(m.toLowerCase(java.util.Locale.ROOT));
        }
        for (String t : ReferenceImages.TYPES) {
            assertTrue(canRead.contains(t),
                    "we accept " + t + " but ImageIO cannot decode it, so every such download "
                            + "silently becomes nothing");
        }
        assertTrue(ReferenceImages.TYPES.contains("image/png"),
                "PNG is the format the whole design rests on — transparency is the subject mask");
    }

    @Test
    @DisplayName("and we ADVERTISE only what we can decode, so a server cannot negotiate past us")
    void theAcceptHeaderCannotInviteAFormatWeCannotRead() {
        // The other half of the same bug. Refusing WebP on arrival is no use if the request said
        // "webp is fine" -- the server takes us at our word and the PNG we wanted is never sent.
        String accept = ReferenceImages.acceptHeader();
        assertFalse(accept.contains("*"),
                "a wildcard invites a server to negotiate its way to anything: " + accept);
        for (String part : accept.split(",")) {
            assertTrue(ReferenceImages.TYPES.contains(part.trim()),
                    "the Accept header offers " + part + ", which is not in the allowlist");
        }
        assertTrue(accept.contains("image/png"), accept);
    }

    @Test
    @DisplayName("the caps are the ones the plan states, and the type list is an ALLOWlist")
    void theBoundsAreWhatWasAgreed() {
        assertTrue(ReferenceImages.MAX_BYTES <= 6 * 1024 * 1024);
        assertTrue(ReferenceImages.MAX_REDIRECTS <= 3);
        assertTrue(ReferenceImages.TIMEOUT.getSeconds() <= 10);
        assertTrue(ReferenceImages.MAX_IMAGES <= 6);
        // An allowlist, because a denylist of image formats loses. SVG is deliberately absent:
        // it is a document, it can reference remote content, and it is not artwork we can count.
        assertFalse(ReferenceImages.TYPES.contains("image/svg+xml"));
        assertFalse(ReferenceImages.TYPES.contains("text/html"));
        assertTrue(ReferenceImages.TYPES.contains("image/png"));
    }

    @Test
    @DisplayName("fetch() never throws and never exceeds its cap, whatever it is handed")
    void fetchIsTotal() {
        // Every one of these is refused before any socket opens, so this stays offline.
        var out = ReferenceImages.fetch(java.util.Arrays.asList(
                null, "", "not a url", "http://127.0.0.1/x.png", "file:///etc/passwd",
                "https://127.0.0.1/a.png"));
        assertTrue(out.isEmpty(), "something got through: " + out);
        assertTrue(ReferenceImages.fetch(null).isEmpty());
    }

    // ──: the parallel fetch gives the sequential loop's answer, faster ─────────────────

    /** A stand-in downloader: sleeps like a network, fails the URLs marked "bad". */
    private static java.util.function.Function<String, ReferenceImages.Fetched> fake(
            long sleepMs, java.util.concurrent.atomic.AtomicInteger calls,
            java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> live,
            java.util.Map<String, Integer> peak) {
        return u -> {
            calls.incrementAndGet();
            String host = ReferenceImages.hostOf(u);
            int now = live.computeIfAbsent(host, h -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet();
            peak.merge(host, now, Math::max);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                live.get(host).decrementAndGet();
            }
            return u.contains("bad") ? null : new ReferenceImages.Fetched(u,
                    new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                    1, sleepMs, 0);
        };
    }

    @Test
    @DisplayName("parallel fetch keeps URL order, the cap, and never downloads more than the loop did")
    void parallelFetchKeepsTheSequentialAnswer() {
        List<String> urls = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            urls.add("https://h" + (i % 2) + ".example/" + (i == 1 || i == 4 ? "bad" : "ok") + i);
        }
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var out = ReferenceImages.fetch(urls, fake(30, calls,
                new java.util.concurrent.ConcurrentHashMap<>(),
                new java.util.concurrent.ConcurrentHashMap<>()), 3);
        // The sequential loop: first six successes in order, i.e. 0,2,3,5,6,7 — having tried 8 URLs.
        assertTrue(out.stream().map(ReferenceImages.Fetched::url).toList().equals(List.of(
                urls.get(0), urls.get(2), urls.get(3), urls.get(5), urls.get(6), urls.get(7))),
                "order or selection changed: " + out.stream().map(ReferenceImages.Fetched::url).toList());
        assertTrue(calls.get() == 8, "waves must start only as many as still needed; called "
                + calls.get());
    }

    @Test
    @DisplayName("no host ever sees more than PER_HOST downloads at once, and six take ~two slots of time")
    void parallelFetchIsPoliteAndFast() {
        List<String> urls = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            urls.add("https://one-wiki.example/img" + i + ".png");
        }
        var peak = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        long t0 = System.nanoTime();
        var out = ReferenceImages.fetch(urls, fake(200, new java.util.concurrent.atomic.AtomicInteger(),
                new java.util.concurrent.ConcurrentHashMap<>(), peak), 3);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(out.size() == 6, "lost images: " + out.size());
        assertTrue(peak.get("one-wiki.example") <= 3, "per-host cap broken: " + peak);
        assertTrue(peak.get("one-wiki.example") >= 2, "never actually ran in parallel: " + peak);
        // Sequential would be 6 × 200 = 1200 ms; two slots of three is ~400 ms.
        assertTrue(ms < 1000, "not faster than the sequential loop: " + ms + " ms");
    }

    /** A syntactically valid PNG whose IHDR declares an enormous image. */
    private static byte[] pngHeaderClaiming(int w, int h) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        ihdr.write(new byte[]{'I', 'H', 'D', 'R'});
        for (int v : new int[]{w, h}) {
            ihdr.write(v >>> 24);
            ihdr.write(v >>> 16);
            ihdr.write(v >>> 8);
            ihdr.write(v);
        }
        ihdr.write(new byte[]{8, 6, 0, 0, 0});     // 8-bit RGBA
        byte[] body = ihdr.toByteArray();
        int len = body.length - 4;
        o.write(new byte[]{(byte) (len >>> 24), (byte) (len >>> 16),
                (byte) (len >>> 8), (byte) len});
        o.write(body);
        CRC32 crc = new CRC32();
        crc.update(body);
        long c = crc.getValue();
        o.write(new byte[]{(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
        return o.toByteArray();
    }
}
