package com.moddingfromamod.reference;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Downloads a character's official artwork so {@link ReferencePalette} can count its pixels.</b>
 *
 * <p>This is the first code in the reference subsystem that opens a socket from inside the game
 * process, and the URL it opens was named by a model that read an untrusted web page. So every
 * limit below is a rule, not a precaution, and each has a test that feeds it the hostile case.
 *
 * <h2>What is and is not a risk here</h2>
 *
 * <p><b>Not a risk:</b> prompt injection. No image reaches a model — the bytes go to a histogram
 * and the only thing that leaves is ten numbers. That is the whole reason this design is safer than
 * the vision-model one it replaces, and cheaper by 122,005 tokens.
 *
 * <p><b>Real risks, and the answer to each:</b>
 * <ul>
 *   <li><b>SSRF.</b> A URL is an instruction to make this machine fetch something, and "something"
 *       could be a metadata endpoint or a service on the loopback. Every hop is resolved and
 *       checked against {@link #isPublic}, and redirects are followed BY HAND
 *       ({@code followRedirects(NEVER)}) — letting the client follow them would check the first
 *       address and connect to the last.</li>
 *   <li><b>Decompression bomb.</b> A 40 KB PNG can declare 60,000 × 60,000. The header is read and
 *       the dimensions checked BEFORE any decode, because the allocation is what kills you and it
 *       happens inside {@code ImageIO.read}.</li>
 *   <li><b>An endless or enormous body.</b> Read through a bounded stream that stops at
 *       {@link #MAX_BYTES}, rather than trusting {@code Content-Length}, which is a claim.</li>
 * </ul>
 *
 * <p>Every failure is a skipped image, never an exception out of this class. A child's wish must
 * not break because a wiki moved a file.
 */
public final class ReferenceImages {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReferenceImages.class);

    /** Enough for a median that survives leave-one-out; more is spent bandwidth for no accuracy. */
    public static final int MAX_IMAGES = 6;

    /** A character render is well under this. */
    public static final int MAX_BYTES = 6 * 1024 * 1024;

    /** Refused on the HEADER, before any pixels are allocated. */
    public static final int MAX_DIMENSION = 8000;
    public static final long MAX_PIXELS = 40_000_000L;

    public static final int MAX_REDIRECTS = 3;
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * Formats that are BOTH safe to accept and decodable by this JVM.
     *
     * <p>An allowlist, because a denylist of image formats loses — but it is intersected with
     * {@link ImageIO#getReaderMIMETypes()} rather than written out by hand, and that is a bug fix.
     * The hand-written list named {@code image/webp}, which ImageIO cannot read without an extra
     * library. A wiki then did content negotiation on the {@code Accept} header, served WebP, and
     * every single download decoded to null — a silent, total failure that looked exactly like a
     * character having no artwork. The list described what we would ACCEPT rather than what we can
     * READ, which is this repo's signature failure in a new costume.
     */
    public static final Set<String> TYPES;

    static {
        // REGISTER THE WEBP READER BEFORE ASKING WHAT WE CAN READ. Order matters: TYPES is
        // computed from ImageIO's registry, so a reader that arrives afterwards is invisible to
        // the very check that decides whether to download the file.
        //
        // Registered EXPLICITLY rather than left to ServiceLoader. TwelveMonkeys ships the usual
        // META-INF/services entry, and that works on a plain classpath -- but this runs inside
        // Minecraft's transforming classloader, out of a JarJar-nested library, and ImageIO's
        // automatic scan is the kind of thing that silently finds nothing there. A silent nothing
        // here is indistinguishable from "that character has no artwork", which is the exact
        // failure mode this whole class already carries a comment about.
        // CATCH THROWABLE, in a static initializer, on purpose. Anything escaping here becomes an
        // ExceptionInInitializerError and then a NoClassDefFoundError on every later use of this
        // class -- so a missing or broken image decoder would stop the reference subsystem dead
        // instead of degrading it. Not measured, unlike everything else in this class; it is the
        // cheap insurance a class-load-time side effect earns by existing at all.
        try {
            registerWebP();
        } catch (Throwable t) {
            LOGGER.warn("WebP registration failed outright: {} — characters on Fandom-hosted "
                    + "wikis will fall back to the model's remembered palette", t.toString());
        }
        TYPES = decodableSubsetOf(Set.of("image/png", "image/jpeg", "image/gif", "image/webp"));
    }

    /**
     * Make {@code image/webp} decodable, or say why not.
     *
     * <p>Reflective so that a build without the decoder still compiles and runs — it simply
     * measures fewer characters, which is what happened for the whole of this subsystem's life
     * before the reader was added.
     */
    private static void registerWebP() {
        String spiName = "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi";
        // TRY SEVERAL CLASSLOADERS, because there is no single right one here. In a dev run the
        // decoder is an ordinary classpath library, which NeoForge's ModLauncher puts on a
        // DIFFERENT layer from mod code -- so Class.forName from here, on the transforming
        // loader, does not see it. In a shipped jar it arrives JarJar-nested and the layering is
        // different again. Measured: the plain-classpath unit test passed while Gate B failed with
        // ClassNotFoundException, which is exactly the split this loop exists to cross.
        ClassLoader[] loaders = {
            ReferenceImages.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader(),
            ImageIO.class.getClassLoader(),
        };
        for (ClassLoader loader : loaders) {
            try {
                Class<?> spi = Class.forName(spiName, true, loader);
                javax.imageio.spi.IIORegistry registry =
                        javax.imageio.spi.IIORegistry.getDefaultInstance();
                Object instance = spi.getDeclaredConstructor().newInstance();
                registry.registerServiceProvider(instance,
                        (Class) javax.imageio.spi.ImageReaderSpi.class);
                LOGGER.debug("WebP decoder registered via {}", loader);
                return;
            } catch (ClassNotFoundException | NullPointerException e) {
                // This loader cannot see it. Try the next.
            } catch (Exception e) {
                LOGGER.warn("WebP decoder found via {} but would not register: {}", loader,
                        e.toString());
                return;
            }
        }
        // Last resort: let ImageIO do its own scan under each loader. Slower and less certain than
        // naming the class, which is why it is not the first thing tried.
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loader);
                ImageIO.scanForPlugins();
                for (String m : ImageIO.getReaderMIMETypes()) {
                    if ("image/webp".equalsIgnoreCase(m)) {
                        LOGGER.debug("WebP decoder found by scanForPlugins under {}", loader);
                        return;
                    }
                }
            } catch (Exception ignored) {
                // A scan that finds nothing is the ordinary outcome; the warning below is the
                // one that matters.
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
        LOGGER.warn("no WebP decoder reachable from any classloader — every Fandom-hosted wiki "
                + "serves WebP, so those characters will fall back to the model's remembered "
                + "palette. ImageIO can read: {}", java.util.Arrays.toString(
                        ImageIO.getReaderMIMETypes()));
    }

    private static Set<String> decodableSubsetOf(Set<String> wanted) {
        Set<String> can = new java.util.HashSet<>();
        for (String m : ImageIO.getReaderMIMETypes()) {
            can.add(m.toLowerCase(Locale.ROOT));
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : wanted) {
            if (can.contains(w)) {
                out.add(w);
            }
        }
        return Set.copyOf(out);
    }

    /**
     * Advertise only what we can decode. Sent as-is, a browser-shaped {@code Accept} invites a
     * server to negotiate its way to a format we then cannot read.
     */
    static String acceptHeader() {
        return String.join(",", new java.util.TreeSet<>(TYPES));
    }

    private static volatile HttpClient client;

    private ReferenceImages() {
    }

    private static HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            synchronized (ReferenceImages.class) {
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NEVER)   // see the class note
                            .connectTimeout(TIMEOUT)
                            .build();
                }
                c = client;
            }
        }
        return c;
    }

    /**
     * One downloaded, decoded, size-checked image — with what it cost, split, because "the palette
     * took 57 s" could not say whether the network or the decoder was slow.
     */
    public record Fetched(String url, BufferedImage image, int bytes, long downloadMs,
                          long decodeMs) {
    }

    /**
     * <b>At most this many downloads from one host at a time</b>, across every lookup in the
     * process — the politeness the old sequential loop bought, kept without its cost.
     *
     * <p>Measured against the exact URLs production fetched for Plumo, the Kroaklings and
     * the Enderman (plumo.wiki.gallery, minecraft.wiki, static.wikia.nocookie.net): six at once
     * came back 200 every time with no slowdown, so three is a margin, not a ceiling we hit.
     */
    public static final int PER_HOST = 3;

    /** Shared by every lookup; daemon threads, so it never holds the server open at shutdown. */
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newFixedThreadPool(MAX_IMAGES * 2, r -> {
                Thread t = new Thread(r, "reference-images");
                t.setDaemon(true);
                return t;
            });

    /** One gate per host, process-wide, so two lookups at once cannot double the burst. */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.Semaphore> HOST_GATES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Fetch what we can, keeping the URLs' order, stopping at {@link #MAX_IMAGES}. Never throws.
     *
     * <p><b>In parallel, and why it used to be sequential no longer holds.</b> The old
     * loop said it ran "on a background thread with no child waiting on it". Then
     * {@code look_up_character} started WAITING for the reference (up to 90 s) so the first sculpt
     * could use it, and these downloads moved onto a wish's critical path: in production Plumo's
     * six images took <b>56.6 s</b> of a 74.5 s wait and the Kroaklings' 23.2 s of 38.5 s, while a
     * child saw nothing happen.
     *
     * <p>Same answer as the loop, by construction: results come back in URL order, and URLs are
     * started in waves of exactly as many as are still needed, so this never downloads more than
     * the sequential loop would have. The rate-limit worry the loop answered is answered by
     * {@link #PER_HOST} instead.
     */
    public static List<Fetched> fetch(List<String> urls) {
        long t0 = System.nanoTime();
        int early = warmed(urls);
        List<Fetched> out = fetch(urls, ReferenceImages::fetchOne, PER_HOST);
        if (urls != null && !urls.isEmpty()) {
            LOGGER.info("reference images: {} of {} url(s) in {} ms ({} started early; parallel, "
                    + "at most {} per host) — {}", out.size(), urls.size(),
                    (System.nanoTime() - t0) / 1_000_000, early, PER_HOST, describe(out));
        }
        return out;
    }

    /** What each image cost, as one log fragment: host, bytes, download and decode time. */
    static String describe(List<Fetched> fetched) {
        StringBuilder b = new StringBuilder();
        for (Fetched f : fetched) {
            if (b.length() > 0) {
                b.append("; ");
            }
            b.append(hostOf(f.url())).append(' ').append(f.bytes() / 1024).append("KB dl ")
                    .append(f.downloadMs()).append("ms dec ").append(f.decodeMs()).append("ms");
        }
        return b.length() == 0 ? "none usable" : b.toString();
    }

    /**
     * The parallel fetch with its downloader handed in, so the ordering, the cap, the waves and
     * the per-host limit are all provable without a network (ReferenceImagesTest).
     */
    static List<Fetched> fetch(List<String> urls, java.util.function.Function<String, Fetched> one,
                               int perHost) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        List<Fetched> out = new ArrayList<>();
        int next = 0;
        while (out.size() < MAX_IMAGES && next < urls.size()) {
            int want = MAX_IMAGES - out.size();
            List<String> wave = urls.subList(next, Math.min(urls.size(), next + want));
            next += wave.size();
            List<java.util.concurrent.Future<Fetched>> pending = new ArrayList<>();
            for (String u : wave) {
                // A download started early, while the researcher was still writing, is
                // picked up here BEFORE a host slot is taken -- waiting on it while holding a slot
                // could starve the very task it waits for.
                java.util.concurrent.Future<Fetched> early = takeWarm(u);
                pending.add(early != null ? early : submitGated(u, one, perHost));
            }
            for (var f : pending) {
                try {
                    Fetched got = f.get();
                    if (got != null && out.size() < MAX_IMAGES) {
                        out.add(got);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pending.forEach(p -> p.cancel(true));
                    return List.copyOf(out);
                } catch (Exception e) {
                    // fetchOne never throws; a downloader that did is one skipped image, as ever
                }
            }
        }
        return List.copyOf(out);
    }

    /** One download through its host's gate, on the shared pool. */
    private static java.util.concurrent.Future<Fetched> submitGated(
            String u, java.util.function.Function<String, Fetched> one, int perHost) {
        var gate = HOST_GATES.computeIfAbsent(hostOf(u) + "#" + perHost,
                h -> new java.util.concurrent.Semaphore(perHost));
        return POOL.submit(() -> {
            gate.acquire();
            try {
                return one.apply(u);
            } finally {
                gate.release();
            }
        });
    }

    // ── Early downloads: started from pages the researcher opened, used once ─────────

    /** How long an early download waits to be claimed, and how many may wait at once. */
    static final long WARM_TTL_MS = 120_000;
    static final int WARM_MAX = 2 * MAX_IMAGES;

    private record Warm(java.util.concurrent.Future<Fetched> future, long at) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Warm> WARM =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * <b>Start downloading these now</b>, before anybody asks — the researcher has opened the page
     * they are on, and its answer is still ~10 s away. A later {@link #fetch} of the same URL
     * takes the download over instead of starting another. Bounded ({@link #WARM_MAX} waiting,
     * {@link #WARM_TTL_MS} each) because each one holds a decoded picture. Never throws.
     */
    public static void warm(List<String> urls) {
        if (urls == null) {
            return;
        }
        long now = System.currentTimeMillis();
        WARM.entrySet().removeIf(e -> now - e.getValue().at() > WARM_TTL_MS);
        int started = 0;
        for (String u : urls) {
            if (started >= MAX_IMAGES || WARM.size() >= WARM_MAX) {
                break;
            }
            if (u == null || u.isBlank() || WARM.containsKey(u)) {
                continue;
            }
            WARM.put(u, new Warm(submitGated(u, ReferenceImages::fetchOne, PER_HOST), now));
            started++;
        }
    }

    /** The early download for this URL, claimed (so it is used once), or null. */
    static java.util.concurrent.Future<Fetched> takeWarm(String url) {
        if (url == null) {
            return null;
        }
        Warm w = WARM.remove(url);
        return w == null || System.currentTimeMillis() - w.at() > WARM_TTL_MS ? null : w.future();
    }

    /** How many of these URLs have an early download waiting — for the log line only. */
    static int warmed(List<String> urls) {
        int n = 0;
        for (String u : urls == null ? List.<String>of() : urls) {
            n += u != null && WARM.containsKey(u) ? 1 : 0;
        }
        return n;
    }

    /** For tests: forget every early download. */
    static void clearWarmForTest() {
        WARM.clear();
    }

    /** The host a URL names, or "" — the key the per-host gate is held on. */
    static String hostOf(String url) {
        try {
            String h = URI.create(url == null ? "" : url.trim()).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** One URL, or null for any reason at all. */
    public static Fetched fetchOne(String url) {
        long started = System.nanoTime();
        try {
            URI uri = URI.create(url.trim());
            byte[] body = null;
            String type = null;
            for (int hop = 0; hop <= MAX_REDIRECTS && body == null; hop++) {
                if (!allowed(uri)) {
                    LOGGER.debug("reference image refused: {}", uri);
                    return null;
                }
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                        // A real one. Several wikis answer 403 to a default Java agent, which
                        // presents as "this character has no artwork" rather than as a refusal.
                        .header("User-Agent", WikiArtwork.AGENT)
                        .header("Accept", acceptHeader())
                        .GET().build();
                HttpResponse<byte[]> res =
                        client().send(req, HttpResponse.BodyHandlers.ofByteArray());
                int code = res.statusCode();
                if (code >= 300 && code < 400) {
                    String loc = res.headers().firstValue("location").orElse(null);
                    if (loc == null) {
                        return null;
                    }
                    uri = uri.resolve(loc);   // re-checked at the top of the next hop
                    continue;
                }
                if (code != 200) {
                    return null;
                }
                type = res.headers().firstValue("content-type").orElse("")
                        .toLowerCase(Locale.ROOT).split(";")[0].trim();
                body = res.body();
            }
            if (body == null || type == null || !TYPES.contains(type)) {
                return null;
            }
            // Content-Length is a claim; the array is the fact.
            if (body.length > MAX_BYTES || body.length < 512) {
                return null;
            }
            long decodeStart = System.nanoTime();
            BufferedImage img = decodeBounded(body);
            long decodeMs = (System.nanoTime() - decodeStart) / 1_000_000;
            return img == null ? null : new Fetched(url, img, body.length,
                    (decodeStart - started) / 1_000_000, decodeMs);
        } catch (Exception | OutOfMemoryError e) {
            // OutOfMemoryError is caught ON PURPOSE. A decode that slips the header check must not
            // take the server down over a picture of a cartoon turtle.
            LOGGER.debug("reference image {} failed: {}", url, e.toString());
            return null;
        }
    }

    /**
     * Decode only after the declared size is known to be sane. Reading the header is cheap;
     * {@code ImageIO.read} allocates first and asks questions never.
     */
    static BufferedImage decodeBounded(byte[] body) throws Exception {
        try (InputStream in = new ByteArrayInputStream(body);
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) {
                return null;
            }
            ImageReader reader = it.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION
                        || (long) w * h > MAX_PIXELS) {
                    return null;
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * <b>The SSRF gate.</b> https only, a real host, and an address that is actually out on the
     * internet — not loopback, not a private range, not link-local (which is where cloud metadata
     * services live), not multicast, not a wildcard.
     */
    static boolean allowed(URI uri) {
        try {
            if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            // Resolving is the point: a name can be made to answer with 127.0.0.1.
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (!isPublic(a)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isPublic(InetAddress a) {
        return !(a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()
                || isUniqueLocalV6(a) || isSharedOrBenchmark(a));
    }

    /** fc00::/7 — the v6 private range, which {@code isSiteLocalAddress} does not cover. */
    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    /** 100.64/10 (carrier NAT, and what a cloud runner often sits behind) and 198.18/15. */
    private static boolean isSharedOrBenchmark(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length != 4) {
            return false;
        }
        int o0 = b[0] & 0xFF;
        int o1 = b[1] & 0xFF;
        return (o0 == 100 && o1 >= 64 && o1 <= 127) || (o0 == 198 && (o1 == 18 || o1 == 19));
    }
}
