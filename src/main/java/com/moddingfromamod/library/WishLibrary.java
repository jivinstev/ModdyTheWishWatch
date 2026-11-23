package com.moddingfromamod.library;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.wish.WishCatalog;
import com.moddingfromamod.wish.WishPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The server owner's curated wish library, hot-reloadable</b>.
 *
 * <p>Two sources answer "what does the watch already know", and they are ordered:
 * <b>library &gt; learned</b> (the hard-coded built-ins that used to sit behind both were deleted,
 *). The library is what the owner chose and pushed; a learned entry is what the agent
 * happened to teach one afternoon, possibly badly. So when both claim one id, the curated answer
 * serves. (A child's *fork* of a
 * library thing carries a different id and is another problem, not this one's.)
 *
 * <p><b>Minecraft-free.</b> Everything here is paths, JSON and a thread, so the precedence and the
 * snapshot rules are unit-tested without booting a game. The Minecraft-facing half — re-reading
 * {@code learned.json}, pushing the buttons out to the clients — is {@link LibraryService}, which
 * hands itself to {@link #onReload}.
 *
 * <p><b>A reload is a SWAP, never a mutation.</b> {@link LibrarySnapshot} is immutable and the
 * reference is volatile, so a replay that has already matched keeps the recipe it matched. That is
 * the one thing a library must not be able to break: a child pressing a button and getting half of
 * yesterday's recipe and half of today's.
 *
 * <p><b>The poll is a plain daemon thread</b>, deliberately — not a {@code ScheduledExecutorService}
 * (a dead task takes its reason into a {@code Future} nobody reads; CLAUDE.md records a narrator
 * that never ran once inside the game) and not a {@code WatchService} (nothing in this tree uses
 * one, it is a per-platform surprise, and thirty seconds is fast enough for a deploy the owner is
 * standing over).
 */
public final class WishLibrary {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** How often the poll asks the disk whether anything moved. */
    public static final long DEFAULT_POLL_MS = 30_000L;

    private static volatile LibrarySnapshot snapshot = LibrarySnapshot.empty(null);
    private static volatile Thread poller;
    /** The owner directory's fingerprint as the last {@link #reload()} found it — the poll's baseline. */
    private static volatile long readFingerprint;
    private static volatile boolean polling;
    private static volatile Runnable onReload = () -> { };

    private WishLibrary() {
    }

    /** Where the library lives. Machine-local config only — this engine names no repo. */
    public static Path dir() {
        return RuntimeConfig.libraryDir();
    }

    /** The current reading. Never null; empty until {@link #reload()} has run. */
    public static LibrarySnapshot snapshot() {
        return snapshot;
    }

    /**
     * Re-read the library and swap the snapshot in. Returns the new one.
     *
     * <p>Logs one INFO line either way, because "the library is empty" and "the library did not
     * load" look identical from the outside and only one of them is a problem.
     */
    public static synchronized LibrarySnapshot reload() {
        Path dir = dir();
        // Taken BEFORE the read, so a write that lands during it still differs at the next poll.
        readFingerprint = LibrarySnapshot.fingerprintOf(dir);
        // The owner's library OVER the starter library the jar ships — one path for the
        // starter nine and every other cached wish, and the owner's entry wins on a shared id.
        Path starter = StarterLibrary.dir();
        LibrarySnapshot next = starter == null ? LibrarySnapshot.read(dir)
                : LibrarySnapshot.layered(java.util.Arrays.asList(dir, starter));
        snapshot = next;
        if (!Files.isDirectory(dir)) {
            LOGGER.info("no wish library at {} — serving the starter library ({}) + learned "
                    + "wishes", dir, starter == null ? "none" : next.summary());
        } else {
            LOGGER.info("wish library {}: {}", dir, next.summary());
            for (String problem : next.problems()) {
                LOGGER.warn("wish library {}: {}", dir, problem);
            }
        }
        return next;
    }

    /** Forget the library (server stop), and stop the poll. */
    public static synchronized void clear() {
        stopPolling();
        snapshot = LibrarySnapshot.empty(null);
    }

    /** What to run after the POLL finds a change — set by {@link LibraryService}. */
    public static void onReload(Runnable listener) {
        onReload = listener == null ? () -> { } : listener;
    }

    /** The poll interval, overridable with {@code -Dmoddingfromamod.libraryPollMs} (a test uses ~1s). */
    public static long pollMillis() {
        String raw = System.getProperty(ModdingFromAModMod.MODID + ".libraryPollMs");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_POLL_MS;
        }
        try {
            long ms = Long.parseLong(raw.trim());
            return ms < 100L ? 100L : ms;
        } catch (NumberFormatException e) {
            return DEFAULT_POLL_MS;
        }
    }

    /**
     * Start watching the library directory for a change.
     *
     * <p>Its own try/catch around the whole body AND around each pass: the job of this thread is to
     * notice a deploy, and a thread that dies on one unreadable file would then never notice another
     * one — silently, which is the failure mode that costs a day.
     */
    public static synchronized void startPolling() {
        if (poller != null && poller.isAlive()) {
            return;
        }
        polling = true;
        Thread t = new Thread(WishLibrary::pollLoop, "moddy-library-poll");
        t.setDaemon(true);
        poller = t;
        t.start();
    }

    public static synchronized void stopPolling() {
        polling = false;
        Thread t = poller;
        poller = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Whether the poll thread is running — a test asks rather than sleeping and hoping. */
    public static boolean isPolling() {
        Thread t = poller;
        return polling && t != null && t.isAlive();
    }

    private static void pollLoop() {
        // THE OWNER'S DIRECTORY'S fingerprint, both sides — as it was when reload() READ it. The
        // snapshot's own fingerprint is the LAYERED one (owner + the jar's starters), so
        // comparing it against this read never matched and the library reloaded — and re-pushed
        // every button — on every poll (found by the film: every 30 s on a server). And not
        // the directory as it is when this thread starts: a change written between the read and
        // the thread's first line would become the baseline and never be noticed (Gate B's
        // thePollNoticesAChangeWithNobodyAskingIt caught exactly that).
        long known = readFingerprint;
        while (polling) {
            try {
                Thread.sleep(pollMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!polling) {
                return;
            }
            try {
                long now = LibrarySnapshot.fingerprintOf(dir());
                if (now != known) {
                    known = now;
                    LOGGER.info("the wish library changed on disk — reloading");
                    // The reload runs later, on the server thread. `known` stays the fingerprint
                    // that triggered it: a pull still writing moves the mtimes again, which the next
                    // poll sees as one more change — never a change swallowed.
                    onReload.run();
                }
            } catch (RuntimeException | Error e) {
                LOGGER.warn("wish library poll failed (will keep watching): {}", e.toString());
            }
        }
    }

    // ── what the rest of the mod asks the library ───────────────────────────────────────────

    /**
     * <b>The composed template set: library first, then learned.</b>
     *
     * <p>Positional, because that is how the matcher and {@code byId} already work — both walk the
     * list and take the first hit. An id the library claims is dropped from the learned set rather
     * than left to sit behind it, so {@code ids()} is honest and a curated override is visible as
     * one entry rather than two. There is no third source: the hard-coded built-ins were deleted
     *.
     *
     * @param learned the learned array, newest last, exactly as {@code learned.json} holds it
     */
    public static String composeTemplatesJson(JsonArray learned) {
        JsonArray all = new JsonArray();
        List<String> libraryIds = snapshot.templateIds();
        snapshot.templatesJson().forEach(all::add);
        if (learned != null) {
            for (var el : learned) {
                if (!el.isJsonObject() || !claimed(libraryIds, el.getAsJsonObject())) {
                    all.add(el);
                }
            }
        }
        JsonObject root = new JsonObject();
        root.add("templates", all);
        return root.toString();
    }

    private static boolean claimed(List<String> libraryIds, JsonObject o) {
        return o.has("id") && o.get("id").isJsonPrimitive()
                && libraryIds.contains(o.get("id").getAsString());
    }

    /**
     * The library's recipes as buttons, in the order the owner wrote them.
     *
     * <p>File order, not newest-first: a learned list is a history and reads best backwards, while
     * a curated file is a MENU and its order is a decision somebody made. §10's "pinned nine" is
     * exactly that decision, and reversing it would throw it away.
     */
    public static List<WishCatalog.Preset> asPresets() {
        List<WishCatalog.Preset> out = new ArrayList<>();
        for (LibrarySnapshot.Entry e : snapshot.templates()) {
            JsonObject o;
            try {
                o = JsonParser.parseString(e.json()).getAsJsonObject();
            } catch (RuntimeException ignored) {
                continue;
            }
            String text = WishPresets.matchingText(terms(o, "all"), terms(o, "any"),
                    terms(o, "none"));
            if (text == null) {
                continue;
            }
            String label = o.has("label") && o.get("label").isJsonPrimitive()
                    ? o.get("label").getAsString() : WishPresets.labelFor(e.id());
            out.add(new WishCatalog.Preset(label, text, e.id(), e.icon()));
        }
        // THE STARTERS LEAD: the first thing a new child sees is the starter set, in the
        // order it ships (the stock panel flows presets from slot 0). An owner entry
        // that overrides a starter id keeps the starter's place — the id is the slot.
        List<WishCatalog.Preset> starters = new ArrayList<>();
        List<WishCatalog.Preset> rest = new ArrayList<>();
        for (WishCatalog.Preset p : out) {
            (isStarter(p.templateId()) ? starters : rest).add(p);
        }
        starters.addAll(rest);
        return ordered(starters, snapshot.order());
    }

    /**
     * The owner's watch order applied: the ids he placed come first, in his order; every
     * other button keeps its place after them, starters first. An id in the order that no longer
     * names a button (demoted, retired, withheld) is simply skipped, so a stale order can never
     * hide a button or show one that is gone.
     */
    static List<WishCatalog.Preset> ordered(List<WishCatalog.Preset> presets, List<String> order) {
        if (order == null || order.isEmpty()) {
            return List.copyOf(presets);
        }
        List<WishCatalog.Preset> placed = new ArrayList<>();
        for (String id : order) {
            for (WishCatalog.Preset p : presets) {
                if (id.equals(p.templateId()) && !placed.contains(p)) {
                    placed.add(p);
                }
            }
        }
        List<WishCatalog.Preset> out = new ArrayList<>(placed);
        for (WishCatalog.Preset p : presets) {
            if (!placed.contains(p)) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    /** A starter-set id — every one is namespaced {@code starter_} (StarterLibraryTest). */
    static boolean isStarter(String id) {
        return id != null && id.startsWith("starter_");
    }

    private static List<String> terms(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (var t : o.getAsJsonArray(key)) {
                if (t.isJsonPrimitive()) {
                    out.add(t.getAsString());
                }
            }
        }
        return out;
    }

    /** The curated icon for a recipe id, or {@code ""} when the library does not carry one. */
    public static String iconFor(String id) {
        return snapshot.iconFor(id);
    }

    /**
     * <b>A library artifact directory, READ-ONLY.</b> Null when the library has no such thing.
     *
     * <p>"A source of bases, never a write target" (plan §8.3). Every caller here resolves a file to
     * READ; nothing writes back, and a refinement of a library thing forks it into the world instead.
     * Returning null rather than a hopeful path keeps that honest — a caller cannot
     * accidentally create a directory inside somebody's checkout.
     */
    public static Path artifactDir(String kind, String key) {
        if (!snapshot.has(kind, key)) {
            return null;
        }
        return snapshot.artifactDir(kind, key);
    }

    /** The library's file for one artifact, or null when it is not there. */
    public static Path artifactFile(String kind, String key, String filename) {
        Path dir = artifactDir(kind, key);
        if (dir == null || filename == null || filename.isBlank()) {
            return null;
        }
        Path f = dir.resolve(filename);
        return Files.isRegularFile(f) ? f : null;
    }

    /** Every key of a kind the library carries — unioned into listings, never replacing the world's. */
    public static List<String> keys(String kind) {
        return List.copyOf(snapshot.keys(kind).stream().sorted().toList());
    }
}
