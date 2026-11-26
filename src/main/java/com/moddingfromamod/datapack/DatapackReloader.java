package com.moddingfromamod.datapack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs a compiled datapack zip into the <b>currently open world</b> and reloads it live — the
 * same effect as dropping a pack in {@code <world>/datapacks} and running {@code /datapack enable}
 * then {@code /reload}, but done through the server API so no restart and no console are involved.
 *
 * <p><b>Threading:</b> every method here mutates server/level state and MUST be called on the server
 * thread (route through {@link com.moddingfromamod.agent.AgentBridge#onServer}). {@code reloadResources}
 * is asynchronous and driven by the server thread itself, so this kicks it off and returns rather than
 * blocking on its future — blocking here would deadlock the very thread that has to run the reload.
 */
public final class DatapackReloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /**
     * The reload currently being driven by the server thread, or null if none was ever started.
     *
     * <p><b>Why this exists.</b> {@code reloadResources} is asynchronous but is driven BY the server
     * thread, and on a heavily-modded instance it occupies that thread for 10-30s (every mod's data
     * loaders re-run, tags revalidate, JEI tears down and restarts). Anything submitted through
     * {@link com.moddingfromamod.agent.AgentBridge#onServer} during that window sits in the task
     * queue and times out — which reads to the agent as "the reload failed", so it retries, which
     * queues ANOTHER full reload behind the first. Observed live: 35 retries produced 34 full data
     * reloads and 48 JEI restarts in 15 minutes and made the game unplayable. Callers that can
     * safely skip (see {@link #reloadInProgress()}) check this instead of piling on.
     */
    private static volatile java.util.concurrent.CompletableFuture<Void> inFlight;

    private DatapackReloader() {
    }

    /**
     * Is a resource reload running right now? Safe to call from any thread (a volatile read), so an
     * MCP tool can answer "already reloading" WITHOUT first hopping to the server thread — which is
     * the whole point, since that hop is exactly what times out during a reload.
     */
    public static boolean reloadInProgress() {
        java.util.concurrent.CompletableFuture<Void> f = inFlight;
        return f != null && !f.isDone();
    }

    /**
     * Kick off a resource reload and remember it, so {@link #reloadInProgress()} can report on it.
     * Every reload in this class goes through here — a reload started behind this seam would be
     * invisible to the coalescing check and could still start a pile-up. Server thread only.
     */
    static void kickReload(MinecraftServer server, List<String> selected) {
        if (shouldCoalesce()) {
            // ONE FOLLOW-UP, NOT ONE PER ARRIVAL. Two wishes authoring datapacks at the same time
            // is a thing concurrency makes reachable, and a resource reload occupies the server
            // thread for 10-30s on a modded instance. Starting a second on top of the first is how
            // 35 retries once became 34 full reloads and 48 JEI restarts in fifteen minutes.
            //
            // Skipping the second outright would be wrong -- it is the reload that makes a
            // just-authored pack real -- so instead it is DEFERRED: whatever arrives during the
            // window collapses into a single follow-up carrying the LATEST selection, which is a
            // superset (enableAndReload re-selects every saved pack), so nothing is lost.
            pendingSelection = new ArrayList<>(selected);
            java.util.concurrent.CompletableFuture<Void> current = inFlight;
            if (current != null && !followUpArmed) {
                followUpArmed = true;
                current.whenComplete((ignored, error) -> server.execute(() -> {
                    followUpArmed = false;
                    List<String> latest = pendingSelection;
                    pendingSelection = null;
                    if (latest != null) {
                        LOGGER.info("coalesced datapack reload: {} pack(s)", latest.size());
                        trackReload(server.reloadResources(latest));
                    }
                }));
            }
            return;
        }
        trackReload(server.reloadResources(selected));
    }

    // ---------------------------------------------------------------------------------------
    // BATCHED INSTALL — one reload for a whole loop, instead of one per item.
    //
    // A resource reload BLOCKS the server thread (managedBlock) for ~3s. That is the right price
    // for a wish: one child, one item, one reload. It is the wrong price for a MATRIX, and Gate B
    // was paying it 161 times. Measured 2026-09-01 by sampling the server thread 60 times during
    // defaultBatch:1: 60/60 samples were inside ONE test, 51/60 of them inside reloadResources.
    // That batch ran 1,220 ticks in 324.7s -- 3.8 ticks/second -- while stress:0 managed 7,500
    // ticks in 30.7s. The suite was not tick-bound; it was blocked, and every one of the 49 other
    // tests co-batched with it was frozen behind the same thread.
    //
    // Nothing is lost by collapsing them, and that is not an optimism: enableAndReload re-selects
    // EVERY pack in DatapackStore, so each reload in such a loop already carried a superset of the
    // one before it. Fifty-five reloads applied fifty-five nested supersets; the last one alone
    // applies the same final set.
    //
    // ⚠ This does NOT weaken what the matrix proves. activate() does not throw when a pack fails
    // to LOAD -- that is the lesson recorded in ItemMatrixGameTest's own javadoc ("the tool
    // reported success, the matrix built all 51 cells, and Gate B was green"). Per-cell load
    // attribution was never on offer here; everyHardcodedRegistryIdResolves is what catches it, in
    // 0.002s. So the per-cell reload bought nothing and cost ~3s.
    //
    // Deliberately NOT automatic on a test run. A silent behaviour change keyed off "are we under
    // test" is how a gate stops exercising the thing it names. A caller opts in, by name, around a
    // loop it knows is a loop.

    /** True while a caller is inside {@link #withDeferredReloads}. Server thread only. */
    private static boolean deferringReloads;

    /** True when at least one install happened while deferring, so a flush is owed. */
    private static boolean deferredReloadOwed;

    /**
     * The features installed while deferring, in order. The owed flush rescans the datapacks folder
     * ONCE and enables all of them -- see {@link #enableAndReload}.
     */
    private static final List<String> deferredFeatures = new ArrayList<>();

    /** What the last flush enabled, for the matrix that checks it. Server thread only. */
    private static List<String> lastFlushed = List.of();

    /** A body that may throw, so a test loop can be wrapped without swallowing its failure. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Run {@code body} with datapack reloads deferred, then perform <b>exactly one</b> reload
     * covering everything installed inside it. Server thread only.
     *
     * <p>The flush is in a {@code finally}, so a body that throws still loads what it installed --
     * an install that is never made real is the one failure mode this shortcut could introduce, and
     * it would present as a later test mysteriously not seeing a pack that was definitely written.
     *
     * <p>Re-entrant calls are a no-op wrapper: the outermost one owns the flush.
     */
    public static void withDeferredReloads(MinecraftServer server, ThrowingRunnable body)
            throws Exception {
        if (deferringReloads) {
            body.run();  // an inner wrapper must not flush early and must not clear the outer flag
            return;
        }
        deferringReloads = true;
        try {
            body.run();
        } finally {
            deferringReloads = false;
            flushDeferredReloads(server);
        }
    }

    /**
     * Apply the single reload owed by a {@link #withDeferredReloads} body, if any.
     *
     * @return true when a reload was actually kicked
     */
    static boolean flushDeferredReloads(MinecraftServer server) {
        if (!deferredReloadOwed) {
            return false;
        }
        List<String> features = new ArrayList<>(deferredFeatures);
        deferredReloadOwed = false;
        deferredFeatures.clear();
        lastFlushed = List.copyOf(features);
        List<String> selected = selectionWith(server, features);
        LOGGER.info("batched datapack reload: {} feature(s) installed, {} pack(s) in one pass",
                features.size(), selected.size());
        kickReload(server, selected);
        return true;
    }

    /** Is a batched install in progress? For tests and diagnostics. */
    public static boolean deferringReloads() {
        return deferringReloads;
    }

    /** The features the last flush enabled, in install order. For tests and diagnostics. */
    public static List<String> lastFlushedFeatures() {
        return lastFlushed;
    }

    /** Is a flush owed? For tests and diagnostics. */
    public static boolean reloadOwed() {
        return deferredReloadOwed;
    }

    /**
     * Pure decision: should this install skip its own reload and leave one owed? Split out so the
     * rule is unit-testable without a server.
     */
    static boolean shouldDeferInstall(boolean deferring) {
        return deferring;
    }

    /** Test seam: forget any deferred state, so one test cannot leak into the next. */
    static void resetDeferredForTest() {
        deferringReloads = false;
        deferredReloadOwed = false;
        deferredFeatures.clear();
    }

    /** The selection a deferred reload will apply, or null when none is waiting. */
    private static volatile List<String> pendingSelection;

    /** True once a follow-up has been chained, so arrivals two through N add nothing. */
    private static volatile boolean followUpArmed;

    /**
     * Pure decision, so the coalescing can be unit-tested without a server: defer only when a reload
     * is genuinely still running.
     */
    static boolean shouldCoalesce() {
        return reloadInProgress();
    }

    /** Is a reload waiting to run behind the current one? For tests and diagnostics. */
    public static boolean reloadDeferred() {
        return pendingSelection != null;
    }

    /** Reset between worlds (and between tests), so state never straddles a reload. */
    static void resetCoalescing() {
        pendingSelection = null;
        followUpArmed = false;
        inFlight = null;
    }

    /**
     * Seam: remember the reload being driven. Exists so the coalescing decision can be unit-tested
     * without booting a server — the guard is the whole point of this class's recent history, and a
     * guard nothing exercises is a guard that quietly stops working.
     */
    static void trackReload(java.util.concurrent.CompletableFuture<Void> reload) {
        inFlight = reload;
    }

    /** The current world's {@code datapacks} directory. */
    public static Path worldDatapackDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR);
    }

    /** The pack id Minecraft gives a file datapack named {@code <id>.zip}. */
    public static String packId(String featureId) {
        return "file/" + featureId + ".zip";
    }

    /** Is this selection id one of ours? Keeps a disable from reaching into mod content. */
    static boolean isWishPack(String packSelectionId) {
        return packSelectionId != null && packSelectionId.startsWith("file/");
    }

    /**
     * <b>Is this a pack a WISH made — one the store holds?</b>
     *
     * <p>{@link #isWishPack} answers "a world-folder pack" (every one has a {@code file/} id), which is
     * not the same question. A SkyBlock world's three packs are {@code file/} packs that no wish made:
     * they were put in the folder when the world was created. Treating them as wish packs is
     * how they were switched off: the first disable of ANY wish pack rebuilt the selection from the
     * store and dropped every {@code file/} pack the store did not hold, level.dat saved them as
     * Disabled, and the boot seed then adopted them as "switched off when this world was saved". A
     * disable, the seed and the culprit pick all ask this instead.
     */
    static boolean isStorePack(String packSelectionId, java.util.Collection<String> storeIds) {
        if (!isWishPack(packSelectionId) || storeIds == null) {
            return false;
        }
        return storeIds.contains(featureIdOf(packSelectionId));
    }

    static boolean isStorePack(String packSelectionId) {
        return isStorePack(packSelectionId, DatapackStore.listIds());
    }

    /**
     * <b>Every pack that is ours in this world</b>: the store's ids, plus every world-folder zip
     * namespaced by its own id ({@code data/<id>/…}). A wish's pack is always written that way — the
     * ready-made items and abilities included, which the world's store does not list (measured on a
     * SkyBlock world: 10 of its 19 wish packs) — while a third-party pack's id carries a version and its namespaces
     * are its own ({@code skyvoid_*}). Without the second half, an undone ready-made pack read as "a pack
     * this world was made with" and would have paged at the next boot.
     */
    static java.util.List<String> oursIds(MinecraftServer server) {
        java.util.List<String> ids = new ArrayList<>(DatapackStore.listIds());
        for (String f : selfNamespaced(worldDatapackDir(server))) {
            if (!ids.contains(f)) {
                ids.add(f);
            }
        }
        return ids;
    }

    /** The feature ids of the zips in {@code dir} that carry a {@code data/<their own id>/} folder. */
    static java.util.List<String> selfNamespaced(Path dir) {
        java.util.List<String> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (java.util.stream.Stream<Path> zips = Files.list(dir)) {
            for (Path z : zips.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted().toList()) {
                String f = stripZip(z.getFileName().toString());
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(z.toFile())) {
                    String prefix = "data/" + f + "/";
                    if (zf.stream().anyMatch(e -> e.getName().startsWith(prefix))) {
                        out.add(f);
                    }
                } catch (IOException | RuntimeException e) {
                    // an unreadable zip is not provably ours — the alert names it, which is the safe side
                }
            }
        } catch (IOException e) {
            LOGGER.warn("could not list world datapacks: {}", e.toString());
        }
        return out;
    }

    /** {@code file/<id>.zip} → {@code <id>}; anything else unchanged. */
    static String featureIdOf(String packSelectionId) {
        String f = packSelectionId.startsWith("file/") ? packSelectionId.substring(5) : packSelectionId;
        return f.endsWith(".zip") ? f.substring(0, f.length() - 4) : f;
    }

    /**
     * <b>The world's own packs that are OFF</b>: every world-folder pack the store does not hold
     * (so no wish made it — the world's creation packs, or one a grown-up added) that the folder offers
     * and the selection does not have. Nothing in Moddy turns such a pack off on purpose, so each one is
     * a pack that was switched off silently, and is said out loud at every boot. Pure.
     */
    static List<String> worldPacksOff(java.util.Collection<String> available,
                                      java.util.Collection<String> selected,
                                      java.util.Collection<String> storeIds) {
        List<String> off = new ArrayList<>();
        for (String id : available) {
            if (isWishPack(id) && !isStorePack(id, storeIds) && !selected.contains(id)) {
                off.add(id);
            }
        }
        java.util.Collections.sort(off);
        return off;
    }

    /**
     * Packs turned off in this session, so a second disable does not resurrect the first.
     *
     * <p>Needed because the applied selection lags the intended one by a reload, so "what is on"
     * cannot be read back fast enough to make two disables in a row correct. Cleared whenever a
     * pack is enabled again, which is the only thing that legitimately undoes a disable.
     */
    private static final java.util.Set<String> disabledThisSession =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Every wish pack switched off in this world, as selection ids ({@code file/<id>.zip}). */
    public static List<String> disabledIds() {
        return new ArrayList<>(disabledThisSession);
    }

    static void rememberDisabled(String packSelectionId) {
        disabledThisSession.add(packSelectionId);
    }

    static void forgetDisabled(String packSelectionId) {
        disabledThisSession.remove(packSelectionId);
    }

    /**
     * <b>What was off when the world was saved stays off after a restart</b>.
     *
     * <p>{@link #disabledThisSession} is memory only, so after a restart it was empty, and the first
     * activation of anything rebuilt the selection from every SAVED pack — switching an undone wish,
     * or a pack whose tick function the server had dropped, straight back on. The world already
     * knows better: every reload writes {@code level.dat}'s disabled list as "available but not
     * selected" ({@code MinecraftServer.getSelectedPacks}, read from the bytecode), and boot keeps
     * those off. So the set is seeded from that list — wish packs only, and only those boot really
     * left off — and cleared first, because the static outlives one world in singleplayer.
     */
    static void seedDisabled(java.util.Collection<String> worldDisabled,
                             java.util.Collection<String> selectedNow) {
        seedDisabled(worldDisabled, selectedNow, DatapackStore.listIds());
    }

    /**
     * Seam: the same seed against a store list handed in. Only STORE packs are adopted: a
     * world-creation pack in level.dat's Disabled list was switched off by mistake, and adopting it
     * would make every later activation switch it back off even after a grown-up re-enabled it.
     */
    static void seedDisabled(java.util.Collection<String> worldDisabled,
                             java.util.Collection<String> selectedNow,
                             java.util.Collection<String> storeIds) {
        disabledThisSession.clear();
        for (String id : worldDisabled) {
            if (isStorePack(id, storeIds) && !selectedNow.contains(id)) {
                disabledThisSession.add(id);
            }
        }
        // a paused (quarantined) pack stays off whatever level.dat saved — a world that froze and
        // was stopped by the watchdog never saved the disable that paused it.
        disabledThisSession.addAll(DatapackQuarantine.pausedSelectionIds());
    }

    /** The server the off-list was seeded for; once per world, whoever asks first. */
    private static volatile MinecraftServer seededFor;

    /**
     * Seed the off-list for this server if nobody has yet. Called by every path that rebuilds a
     * selection, because the one that matters most runs at world open beside this class's own
     * listener, in no fixed order: {@code DatapackStore.installAllIntoWorld} re-enabled every
     * saved pack at every boot, so on a live server a switched-off pack was back on the moment the
     * server restarted, before any wish.
     */
    static void ensureSeeded(MinecraftServer server) {
        if (server == null || seededFor == server) {
            return;
        }
        seededFor = server;
        seedDisabled(server.getWorldData().getDataConfiguration().dataPacks().getDisabled(),
                server.getPackRepository().getSelectedIds(), oursIds(server));
        if (!disabledThisSession.isEmpty()) {
            LOGGER.info("{} wish datapack(s) were switched off when this world was saved and stay off: {}",
                    disabledThisSession.size(), disabledThisSession);
        }
        // a pack the world was MADE with that is off is never a wish being undone — it is a pack
        // switched off silently (a SkyBlock world's three packs were, for weeks, with nothing said).
        checkWorldPacks(server);
    }

    /** Where the world's own packs that were ON at the last boot are remembered. */
    static final String WORLD_PACKS_FILE = "moddy-world-packs.json";

    /**
     * Say every world pack that is off — and PAGE for one that was on at the last boot.
     *
     * <p>A pack that went from on to off is the silent switch-off this exists for: one
     * {@code MODDY-ALERT} line, which a server's watchdog can page on. A pack that was already off when it
     * was last seen is a WARN line at every boot rather than a page at every wake — a server that sleeps
     * between players would otherwise page each time a child joins.
     */
    static void checkWorldPacks(MinecraftServer server) {
        try {
            var repo = server.getPackRepository();
            java.util.List<String> storeIds = oursIds(server);
            List<String> off = worldPacksOff(repo.getAvailableIds(), repo.getSelectedIds(), storeIds);
            Path file = server.getWorldPath(LevelResource.ROOT).resolve(WORLD_PACKS_FILE);
            java.util.Set<String> wasOn = readWorldPacksOn(file);
            for (WorldPackState st : judgeWorldPacks(off, wasOn)) {
                if (st.newlyOff()) {
                    LOGGER.error("MODDY-ALERT datapack switched off: {} — a pack this world was made with (no wish "
                            + "made it) was on at the last boot and is off now; its mechanics are not running. "
                            + "/datapack enable \"{}\" turns it back on (#1828)", st.id(), st.id());
                } else {
                    LOGGER.warn("datapack {} is off: a pack this world was made with (no wish made it), already off "
                            + "when last seen; its mechanics are not running (#1828)", st.id());
                }
            }
            List<String> on = new ArrayList<>();
            for (String id : repo.getSelectedIds()) {
                if (isWishPack(id) && !isStorePack(id, storeIds)) {
                    on.add(id);
                }
            }
            java.util.Collections.sort(on);
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            on.forEach(arr::add);
            o.addProperty("v", 1);
            o.add("on", arr);
            Files.writeString(file, o.toString() + "\n");
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not check the world's own datapacks: {}", e.toString());
        }
    }

    /** One world pack that is off, and whether it was on at the last boot. */
    record WorldPackState(String id, boolean newlyOff) {
    }

    /** Pure: each pack that is off, marked newly off when the last boot saw it on. */
    static List<WorldPackState> judgeWorldPacks(List<String> off, java.util.Set<String> wasOn) {
        List<WorldPackState> out = new ArrayList<>();
        for (String id : off) {
            out.add(new WorldPackState(id, wasOn.contains(id)));
        }
        return out;
    }

    private static java.util.Set<String> readWorldPacksOn(Path file) {
        java.util.Set<String> on = new java.util.HashSet<>();
        if (!Files.isRegularFile(file)) {
            return on;
        }
        try {
            var el = com.google.gson.JsonParser.parseString(Files.readString(file));
            if (el.isJsonObject() && el.getAsJsonObject().has("on")) {
                el.getAsJsonObject().getAsJsonArray("on").forEach(e -> on.add(e.getAsString()));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not read {}: {}", file, e.toString());
        }
        return on;
    }

    /** Test seam: as a fresh boot sees it — nothing seeded, nothing in memory. */
    static void forgetSeedForTest() {
        seededFor = null;
        disabledThisSession.clear();
        refusedBeforeLoad.clear();
    }

    @net.neoforged.bus.api.SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        ensureSeeded(event.getServer());
        // a world's own defaults for a pack's settings, before the pack's first #load (first tick).
        try {
            com.moddingfromamod.world.DatapackDefaults.apply(event.getServer());
        } catch (RuntimeException e) {
            LOGGER.warn("could not write the world's datapack defaults: {}", e.toString());
        }
    }

    /**
     * <b>Did the server take every function of a pack just switched on? If not, take it back off.</b>
     *
     * <p>Minecraft drops a function it cannot parse, and a dropped TICK function is a missing
     * reference in {@code #minecraft:tick}, which Minecraft refuses WHOLE: every datapack clock in
     * the world stops with it, silently (one such pack turned ten unrelated GameTests red).
     * {@code apply_effect} learned this first (chunk 3); {@code author_datapack} and
     * {@code make_item} switch packs on with the agent's own commands in them too.
     *
     * <p>Says nothing while reloads are batched or a reload is still on its way — the functions are
     * not loaded yet, and "missing" would mean "not yet", not "refused". Server thread only.
     *
     * @return the verifier's report when the pack was refused and is now off, else null
     */
    public static DatapackVerifier.Report takeBackIfRefused(MinecraftServer server, String featureId) {
        // Refused before it was ever switched on (PackParseCheck): already off, nothing to reload.
        List<String> before = refusedBeforeLoad.remove(featureId);
        if (before != null) {
            String ns = featureId.trim().toLowerCase(java.util.Locale.ROOT);
            return new DatapackVerifier.Report(ns, true, List.of(), before, List.of(), List.of());
        }
        if (deferringReloads || reloadInProgress() || reloadDeferred()) {
            return null;
        }
        DatapackVerifier.Report r = DatapackVerifier.verify(server, featureId, List.of(), false);
        if (!r.loaded() || r.missing().isEmpty()) {
            return null;
        }
        disableAndReload(server, featureId);
        LOGGER.warn("datapack '{}' switched back off: the server refused {}", featureId, r.missing());
        return r;
    }

    /**
     * Copy {@code zip} into the open world's datapacks folder (atomically), then discover and enable
     * it and reload resources. Safe to call again for the same id to hot-swap an edited pack.
     *
     * @return the pack id now enabled (e.g. {@code file/ice_bomb.zip})
     */
    public static String installAndReload(MinecraftServer server, String featureId, Path zip)
            throws IOException {
        if (DatapackQuarantine.isQuarantined(featureId)) {
            // paused for freezing the world; only /moddyop quarantine restore turns it back on — a new
            // wish re-making the same pack must not put a crash back with it.
            throw new IOException("the datapack '" + featureId + "' is paused because it was making the world "
                    + "freeze (#1683); a grown-up turns it back on with /moddyop quarantine restore " + featureId
                    + ". Grant the wish another way, or with a datapack of a new name that does less each tick.");
        }
        Path dir = worldDatapackDir(server);
        Files.createDirectories(dir);
        Path dest = dir.resolve(featureId + ".zip");
        // Stage + atomic move so a half-copied zip is never visible to the pack scanner.
        Path tmp = dir.resolve("." + featureId + ".zip.tmp");
        // In THIS build's dialect: a zip compiled by another build would otherwise lose
        // its functions at pack load while the pack still reported enabled.
        DatapackDialect.Retargeted fixed = DatapackDialect.forThisBuild(zip);
        if (fixed.changed()) {
            LOGGER.info("datapack '{}' put into this build's dialect on install: {}", featureId,
                    fixed.notes());
        }
        Files.write(tmp, fixed.zip());
        try {
            moveInto(tmp, dest);
        } catch (java.nio.file.FileSystemException held) {
            // WINDOWS: the pack being replaced is loaded, its zip is open in the server's resources, and Windows
            // refuses to replace an open file ("being used by another process"), so every edit of an item or effect
            // failed there (the platform smoke, 2026-11-26) while Linux and macOS replace it under the open handle.
            // Reload once WITHOUT this pack, which closes its zip, then put the new one in; the reload below turns it
            // back on. A replace that is refused for any other reason is still refused, by the second move.
            LOGGER.info("datapack '{}' is held open by the loaded resources ({}); releasing it to replace it",
                    featureId, held.getReason());
            releaseLoaded(server, featureId);
            moveInto(tmp, dest);
        }
        return enableAndReload(server, featureId);
    }

    /** Stage-to-final move: atomic where the file system has it. A test may make it refuse once, as Windows does. */
    static void moveInto(Path tmp, Path dest) throws IOException {
        if (holdForTest != null && holdForTest.test(dest)) {
            holdForTest = null;
            throw new java.nio.file.FileSystemException(dest.toString(), null,
                    "The process cannot access the file because it is being used by another process (test)");
        }
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException atomicUnsupported) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Test seam: the next move onto a matching path is refused as Windows refuses an open file. */
    static volatile java.util.function.Predicate<Path> holdForTest;

    /**
     * Unload {@code featureId}'s pack so the server lets go of its zip: wait out a reload in flight, then reload
     * everything applied but this pack, and wait for that too (the server thread keeps running tasks meanwhile). From
     * another thread, the same on the server thread, waited for.
     */
    static void releaseLoaded(MinecraftServer server, String featureId) {
        Runnable release = () -> {
            java.util.concurrent.CompletableFuture<Void> current = inFlight;
            if (current != null) {
                server.managedBlock(current::isDone);
            }
            List<String> keep = new ArrayList<>(server.getPackRepository().getSelectedIds());
            keep.remove(packId(featureId));
            java.util.concurrent.CompletableFuture<Void> without = server.reloadResources(keep);
            trackReload(without);
            server.managedBlock(without::isDone);
        };
        if (server.isSameThread()) {
            release.run();
        } else {
            server.submit(release).join();
        }
    }

    /**
     * Enable the (already-present) pack for {@code featureId} and reload. Used both after installing a
     * fresh zip and when re-activating packs that were copied into the world before it opened.
     */
    public static String enableAndReload(MinecraftServer server, String featureId) {
        String id = packId(featureId);
        if (DatapackQuarantine.isQuarantined(featureId)) {
            LOGGER.warn("datapack '{}' is paused (#1683) and stays off; /moddyop quarantine restore turns it on", id);
            return id;
        }
        // Re-enabling is the one thing that legitimately undoes a disable, so it clears the flag —
        // otherwise the next disable would rebuild a set that still excludes this pack and switch
        // it straight back off.
        forgetDisabled(id);
        if (shouldDeferInstall(deferringReloads)) {
            // Owed, not dropped -- withDeferredReloads flushes in a finally.
            //
            // AND THE RESCAN IS OWED TOO. repo.reload() opens every zip in the datapacks
            // folder, and inside a deferred loop the folder grows by one pack per call, so a loop of
            // N installs rescanned 1..N packs: quadratic. Sampled on 1.21.1, it was the item
            // matrix's largest server-thread frame (11 of 26 samples, RandomAccessFile.open/seek)
            // while its batch's other 49 tests waited. The flush rescans ONCE and selects this
            // feature with every other one owed, plus every saved pack -- the same superset the
            // last per-call selection would have held, which is why the reload could be owed at all.
            deferredReloadOwed = true;
            deferredFeatures.add(featureId);
            return id;
        }
        List<String> selected = selectionWith(server, List.of(featureId));
        // Kick off the reload; the server thread drives it to completion over the next ticks. When
        // the pack content changed on disk but the enabled SET didn't, reloading the same set still
        // re-reads it from disk, so an edited pack is picked up.
        //
        // This reload is NEVER skipped when one is already in flight: it is the one that makes a
        // just-authored pack real. Only the redundant agent-initiated refresh coalesces.
        kickReload(server, selected);
        LOGGER.info("hot-reloaded datapack '{}' ({} packs enabled)", id, selected.size());
        return id;
    }

    /**
     * The selection that enables {@code features}: what is applied now, plus each of them that the
     * rescanned folder offers, plus every wish pack saved for this world. Server thread only.
     */
    static List<String> selectionWith(MinecraftServer server, List<String> features) {
        ensureSeeded(server);
        PackRepository repo = server.getPackRepository();
        repo.reload();  // rescan the datapacks folder so a newly-written zip is discoverable
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        java.util.Collection<String> available = repo.getAvailableIds();
        for (String featureId : features) {
            String id = packId(featureId);
            if (available.contains(id) && !selected.contains(id)) {
                selected.add(id);
            }
        }
        // ...and EVERY wish pack already saved for this world, not just this one.
        //
        // getSelectedIds() reports the selection that is currently APPLIED, and a reload is applied
        // over the following ticks — so two activations in quick succession read the same stale
        // selection and the second one's set omits the first pack. The second reload then wins and
        // the first wish is silently switched off. Two wishes in a row is not an edge case; it is
        // Tuesday. Found by driving two real wishes end to end, where both reported "activated" and
        // both were dead, and the only clue was that each reload logged the same pack COUNT.
        for (String saved : DatapackStore.listIds()) {
            String other = packId(saved);
            if (available.contains(other) && !selected.contains(other)) {
                selected.add(other);
            }
        }
        // ...EXCEPT what was turned off. Both sources above re-include a disabled pack: the applied
        // selection still holds it until the disable's reload lands, and the saved list always
        // does (a disable keeps the zip). So the next activation of ANYTHING switched it straight
        // back on — an undone wish ran again, and a pack apply_effect had taken off for a dropped
        // tick function came back and stopped every datapack's clock. A pack being
        // enabled here was forgotten from the set by enableAndReload first.
        selected.removeAll(disabledThisSession);
        // ...and NEVER a pack the server would refuse a function of (PackParseCheck). Letting the
        // reload find out put a "Failed to load function" stack trace in the log for every refused
        // COMMAND and then took the pack back off; a port CI that reads the log as a crash failed on
        // the one ApplyEffectGameTest makes on purpose. Checked for the packs this selection ADDS
        // (and the features asked for, an edited pack included); one already applied was checked
        // when it went on.
        java.util.Set<String> applied = new java.util.HashSet<>(repo.getSelectedIds());
        for (java.util.Iterator<String> it = selected.iterator(); it.hasNext(); ) {
            String id = it.next();
            if (!isWishPack(id)) {
                continue;
            }
            String featureId = featureIdOf(id);
            if (applied.contains(id) && !features.contains(featureId)) {
                continue;
            }
            List<String> bad = PackParseCheck.refused(server, worldDatapackDir(server).resolve(featureId + ".zip"));
            if (!bad.isEmpty()) {
                it.remove();
                rememberDisabled(id);
                refusedBeforeLoad.put(featureId, bad);
                LOGGER.warn("datapack '{}' not switched on: the server would refuse {}", featureId, bad);
            } else {
                refusedBeforeLoad.remove(featureId);
            }
        }
        return selected;
    }

    /** Packs {@link #selectionWith} kept off because a function would not parse, by feature id. */
    private static final java.util.Map<String, List<String>> refusedBeforeLoad =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Is the pack for {@code featureId} currently enabled? */
    public static boolean isEnabled(MinecraftServer server, String featureId) {
        return server.getPackRepository().getSelectedIds().contains(packId(featureId));
    }

    /**
     * Turn OFF the pack for {@code featureId} and reload — the mirror of {@link #enableAndReload}.
     * The zip is left on disk (this only removes it from the enabled set), so a disabled pack is
     * fully recoverable: re-enable it later with {@link #enableAndReload}. Used when a wish's datapack
     * turned out wrong (the child rejected it) or too heavy.
     *
     * @return true if the pack was enabled and has now been disabled
     */
    public static boolean disableAndReload(MinecraftServer server, String featureId) {
        ensureSeeded(server);
        PackRepository repo = server.getPackRepository();
        repo.reload();
        String id = packId(featureId);
        if (!repo.getSelectedIds().contains(id)) {
            return false;   // it wasn't on — nothing to turn off
        }
        // ⚠ THE STALE-SELECTION TRAP, IN THE DIRECTION THAT PUTS A PACK BACK ON.
        //
        // This used to be `new ArrayList<>(repo.getSelectedIds())` with the one id removed, and
        // that is the same read enableAndReload carries a scar about twenty lines below:
        // getSelectedIds() reports the selection currently APPLIED, and a reload lands over the
        // following ticks. Two disables in quick succession therefore both read the SAME stale
        // set, and — because kickReload keeps only the LATEST — the second one's set still
        // contains the first pack. The follow-up applies it and the first pack is switched back ON.
        //
        // kickReload's own safety argument names the enable path in as many words ("the LATEST
        // selection is a superset, since enableAndReload re-selects every saved pack, so nothing
        // is lost"). Disabling is a SUBSET, so that argument does not carry, and the failure is
        // silent: undo reports success and the wish is still running.
        //
        // Reachable rather than theoretical: WishUndo loops over every made() entry, so undoing
        // one wish that authored two datapacks disables both back to back.
        //
        // Fixed by rebuilding the set from what is SAVED rather than from what is applied — the
        // same move enableAndReload makes, mirrored.
        List<String> disabled = disabledIds();
        disabled.add(id);
        List<String> selected = new ArrayList<>();
        for (String saved : DatapackStore.listIds()) {
            String other = packId(saved);
            if (!disabled.contains(other) && repo.getAvailableIds().contains(other)) {
                selected.add(other);
            }
        }
        // Everything on that is NOT one of ours stays on — vanilla, mods, a hand-added pack, and a
        // pack the world was created with. "Ours" is a pack the STORE holds: asking only for a
        // file/ id dropped a SkyBlock world's three packs on the first disable of any wish pack.
        java.util.List<String> storeIds = oursIds(server);
        for (String on : repo.getSelectedIds()) {
            if (!disabled.contains(on) && !selected.contains(on) && !isStorePack(on, storeIds)) {
                selected.add(on);
            }
        }
        rememberDisabled(id);
        kickReload(server, selected);
        LOGGER.info("disabled datapack '{}' ({} packs still enabled; zip kept for recovery)",
                id, selected.size());
        return true;
    }

    /** A wish datapack found in the open world's folder, with when its zip was last written. */
    public record InstalledPack(String featureId, long modifiedMs) {
    }

    /**
     * The most recently written {@code .zip} datapack in the open world's datapacks folder (by file
     * modification time), or empty if there are none. Recency is how a just-rejected wish is tied to
     * the pack it produced without threading a featureId through the whole grant.
     */
    public static java.util.Optional<InstalledPack> newestInstalledPack(MinecraftServer server) {
        // only a pack a wish made can be a rejected wish's culprit — never a world-creation pack.
        java.util.List<String> storeIds = oursIds(server);
        return newestInstalledPack(worldDatapackDir(server), storeIds::contains);
    }

    /** Directory seam (no server needed), so the newest-pack pick is testable against a temp folder. */
    static java.util.Optional<InstalledPack> newestInstalledPack(Path dir) {
        return newestInstalledPack(dir, id -> true);
    }

    /** The same pick, restricted to the feature ids {@code ours} accepts. */
    static java.util.Optional<InstalledPack> newestInstalledPack(Path dir,
                                                                 java.util.function.Predicate<String> ours) {
        if (dir == null || !Files.isDirectory(dir)) {
            return java.util.Optional.empty();
        }
        try (java.util.stream.Stream<Path> zips = Files.list(dir)) {
            return zips
                    .filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .map(p -> new InstalledPack(stripZip(p.getFileName().toString()), modifiedMs(p)))
                    .filter(pk -> ours.test(pk.featureId()))
                    .max(java.util.Comparator.comparingLong(InstalledPack::modifiedMs));
        } catch (IOException e) {
            LOGGER.warn("could not list world datapacks: {}", e.toString());
            return java.util.Optional.empty();
        }
    }

    private static String stripZip(String name) {
        return name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    private static long modifiedMs(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Pure decision (unit-tested): is the newest pack plausibly the one this just-rejected wish
     * produced? True only when the pack was written around or after the wish was made AND recently —
     * so a template-only wish, whose newest pack is OLDER than it, can never disable an innocent pack.
     *
     * @param graceMs how much earlier than the wish the pack may have been written (grant lag / skew)
     */
    public static boolean packMatchesRejectedWish(long packModifiedMs, long wishCreatedMs, long nowMs,
                                                  long recentWindowMs, long graceMs) {
        return packModifiedMs >= wishCreatedMs - graceMs && nowMs - packModifiedMs <= recentWindowMs;
    }
}
