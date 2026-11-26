package com.moddingfromamod.species;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every creature the child (or an agent) has invented, for the current world.
 *
 * <p>Held in a concurrent map because BOTH sides read it: the server applies stats, and the client
 * renderer needs the same definitions to pick a model and texture. The client copy arrives over the
 * network ({@code SpeciesSyncPayload}) rather than from disk — which is precisely why new creatures
 * need no resource-pack reload and therefore no game restart.
 *
 * <p>Persisted as plain JSON in the world folder, so creatures travel with the save and a grown-up
 * can read or hand-edit them.
 */
public final class SpeciesRegistry {

    /**
     * Bumped on every change to any species.
     *
     * <p>Exists because the obvious check was wrong in a way that no test caught. Creatures used to
     * re-apply their species when their species <i>id</i> changed — but editing a creature keeps the
     * same id and changes its contents, so "make it bigger" never reached the creatures already in
     * the world. The tool description and the docs both claimed it did. A revision counter detects
     * the case that actually happens.
     */
    private static volatile int revision;

    /** The current revision. A creature re-applies when this moves past the one it last saw. */
    public static int revision() {
        return revision;
    }

    /**
     * A creature's ART changed under an unchanged record — {@code paint_creature} recoloured its
     * parts. Moves the revision, so every rig asks whether what it was built from is
     * still what it would build now ({@code VanillaRigAnimator.rebuildIfRewished}), and only the
     * ones whose geometry print changed are rebuilt.
     */
    public static void touch() {
        revision++;
    }


    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final int MAX_SPECIES = 500;

    /**
     * Every write to the registry, wherever it came from — the map counts its own puts, removes and
     * clears, so a reader's cache keyed on it cannot miss a write path (2026-10-31: the per-tick
     * species lookup, {@code DisplayRig.speciesOn}, keys its tag index on this).
     */
    private static final java.util.concurrent.atomic.AtomicLong WRITES = new java.util.concurrent.atomic.AtomicLong();

    private static final Map<String, Species> SPECIES = new ConcurrentHashMap<>() {
        @Override
        public Species put(String key, Species value) {
            Species was = super.put(key, value);
            WRITES.incrementAndGet();
            return was;
        }

        @Override
        public Species remove(Object key) {
            Species was = super.remove(key);
            WRITES.incrementAndGet();
            return was;
        }

        @Override
        public void clear() {
            super.clear();
            WRITES.incrementAndGet();
        }
    };


    /** How many writes the registry has seen; any change means its contents may have changed. */
    public static long writes() {
        return WRITES.get();
    }

    /** When each creature was made and last changed — what the watch sorts on. */
    private static final com.moddingfromamod.store.ArtifactTimes.Book TIMES =
            new com.moddingfromamod.store.ArtifactTimes.Book();

    /** When this creature was made and last changed; {@code NONE} when nobody knows. */
    public static com.moddingfromamod.store.ArtifactTimes.Times timesOf(String id) {
        return TIMES.get(id);
    }

    /** Test seam: say when a fixture creature was made/changed ({@code NONE} forgets it). */
    public static void timesForTest(String id, com.moddingfromamod.store.ArtifactTimes.Times t) {
        TIMES.set(id, t);
    }

    private SpeciesRegistry() {
    }

    public static Species get(String id) {
        return id == null || id.isEmpty() ? null : SPECIES.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean has(String id) {
        return get(id) != null;
    }

    /** Whether this world has its OWN record of a creature — as opposed to reading the library's. */
    public static boolean isRegistered(String id) {
        return id != null && SPECIES.containsKey(id.toLowerCase(java.util.Locale.ROOT));
    }

    public static List<Species> all() {
        return new ArrayList<>(SPECIES.values());
    }

    public static int count() {
        return SPECIES.size();
    }

    /** Replace the whole set (used by the client when a sync packet arrives). */
    public static void replaceAll(List<Species> species) {
        SPECIES.clear();
        for (Species s : species) {
            if (s != null && s.isValid()) {
                SPECIES.put(s.id(), s);
            }
        }
    }

    public static void clear() {
        SPECIES.clear();
        TIMES.clear();
        // The next world's creatures are not this one's: forget whose models are whose.
        com.moddingfromamod.runtime.ModelRepository.forgetShelved();
    }

    /**
     * Add or update a species and persist it. Server side.
     *
     * <h2>The owner stamp goes on AT CREATION, and only then </h2>
     *
     * <p>One choke point rather than a line in each of the eight tools that call this: a creature
     * that is NEW takes the acting child's uuid, and one that already exists keeps whatever it has.
     * That second half is the load-bearing one. Every creature in every world today is unowned, and
     * unowned means SHARED ({@link com.moddingfromamod.marshal.ArtifactOwner}); a rule that stamped
     * on every write would hand the first child to touch his brother's dragon sole ownership of it,
     * silently, with nothing in the world looking any different.
     *
     * <p>{@code AgentContext.currentPlayerId()} is {@code ""} for a gate, a direct call, or two
     * wishes in flight with nothing bound — so those still write shared creatures, which is exactly
     * what they wrote before this existed.
     */
    public static synchronized Species put(MinecraftServer server, Species species) {
        return put(server, species, true);
    }

    /**
     * <b>: a creature the LIBRARY serves, registered as everybody's.</b>
     *
     * <p>A cached wish spawning a library creature leaves a world record of it (the design: "the
     * world's own copy of the curated creature … unowned, because the library is everybody's"), so
     * the creature outlives a library release that retires it. What it must NOT do is take the
     * bound child's stamp, as {@link #put} does for a NEW creature — that saved the library's
     * creature to his shelf as his own: copy on READ. Copy on WRITE is {@code CreatureFork}.
     */
    public static synchronized Species putShared(MinecraftServer server, Species species) {
        return put(server, species == null ? null : species.withOwner(""), false);
    }

    private static Species put(MinecraftServer server, Species species, boolean stampNew) {
        revision++;
        if (species == null || !species.isValid()) {
            return null;
        }
        // whatever was written, a creature stays immune to its own abilities.
        species = SelfHarmGuard.guard(species);
        boolean isNew = !SPECIES.containsKey(species.id());
        if (isNew && SPECIES.size() >= MAX_SPECIES) {
            // Said, not silent: a refused creature reads downstream as one that "has no species" (2026-11-15).
            LOGGER.warn("species registry full ({} creatures): '{}' was not added", MAX_SPECIES, species.id());
            return null;
        }
        Species stored = species;
        if (isNew && stampNew && species.ownerId().isEmpty()) {
            String who = com.moddingfromamod.agent.AgentContext.currentPlayerId();
            if (!who.isEmpty()) {
                stored = species.withOwner(who);
            }
        } else if (!isNew && species.ownerId().isEmpty()) {
            // An edit that arrived without the stamp keeps the one already on file rather than
            // quietly un-owning it — `Species.with` carries it, but `Species.fromJson` of a
            // hand-written patch would not.
            Species had = SPECIES.get(species.id());
            if (had != null && !had.ownerId().isEmpty()) {
                stored = species.withOwner(had.ownerId());
            }
        }
        Species before = SPECIES.put(stored.id(), stored);
        if (before == null || !before.equals(stored)) {
            // An edit moves it to the front of his history; a re-put of the same thing does not.
            TIMES.touch(stored.id(), com.moddingfromamod.store.ShelfTimes.now());
        }
        save(server);
        // A new creature's model was written before this record named its owner, so it is in the
        // world folder; this is what carries it onto his shelf.
        com.moddingfromamod.runtime.ModelRepository.shelveOwnedModels(ownedModelKeys());
        // wished again and saved — back on his watch if he had hidden it.
        com.moddingfromamod.wish.WatchHides.unhideSaved(stored.ownerId(), stored.id());
        return stored;
    }

    /**
     * <b>Test only:</b> these species exist in memory while {@code body} runs, and are gone after —
     * never saved, so a reused GameTest world keeps no stray creature (§X19). A test that needs a
     * creature to exist used a shipped built-in for exactly that reason retired them.
     */
    public static void withSpeciesForTest(List<Species> extra, Runnable body) {
        Runnable undo = putForTest(extra);
        try {
            body.run();
        } finally {
            undo.run();
        }
    }

    /**
     * <b>Test only:</b> these species exist in memory, never saved and outside the 500-creature cap, until the
     * returned undo runs — for a test that ticks, which hands the undo to {@code MockPlayerNetwork.afterTest} so it
     * runs on pass, fail or timeout. A full Gate B registers far more creatures than a world holds, so a test that
     * {@link #put}s its own can find the registry full and every one of them refused (2026-11-15).
     */
    public static Runnable putForTest(List<Species> extra) {
        Map<String, Species> had = new java.util.HashMap<>();
        synchronized (SpeciesRegistry.class) {
            for (Species s : extra) {
                if (s == null || !s.isValid()) {
                    continue;
                }
                // The same creature put() would store, so a test reads what a wish would have made: immune to its
                // own abilities. Only the SAVE and the owner stamp are left out -- that is what "for test" means.
                Species guarded = SelfHarmGuard.guard(s);
                had.putIfAbsent(guarded.id(), SPECIES.get(guarded.id()));
                SPECIES.put(guarded.id(), guarded);
            }
            revision++;
        }
        return () -> {
            synchronized (SpeciesRegistry.class) {
                had.forEach((id, before) -> {
                    if (before == null) {
                        SPECIES.remove(id);
                    } else {
                        SPECIES.put(id, before);
                    }
                });
                revision++;
            }
        };
    }

    /**
     * <b>Test only:</b> take a creature a test {@link #put} back out, and save — the cleanup half of a test that is ABOUT
     * the real write path (the save, the shelf, the owner stamp) and so cannot use {@link #putForTest}. {@code before}
     * is what was there when the test began ({@code null}: nothing, so the id is removed).
     */
    public static synchronized void restoreForTest(MinecraftServer server, String id, Species before) {
        revision++;
        if (before == null) {
            SPECIES.remove(id);
            TIMES.forget(id);
        } else {
            SPECIES.put(id, before);
        }
        if (server != null) {
            save(server);
        }
    }

    /** Whose this creature is, or {@code ""} when nobody's — see {@link #put}. */
    public static String ownerOf(String id) {
        Species s = get(id);
        return s == null ? "" : s.ownerId();
    }

    private static final String FILE = "species.json";

    /** The envelope name, and the newest schema this build reads (stamped this store). */
    static final String STORE = "species";
    static final int SCHEMA = 1;

    /**
     * A child's creatures live on his shelf ({@code <players>/<uuid>/species.json}) — with
     * their models beside them ({@link com.moddingfromamod.runtime.ModelRepository#dir}) — so a
     * Spikor made in Creative can be summoned in Survival. Built-ins and unowned creatures stay
     * with the world.
     */
    private static final com.moddingfromamod.store.PlayerShelf SHELF =
            new com.moddingfromamod.store.PlayerShelf(STORE, FILE, "species",
                    com.moddingfromamod.store.PlayerShelf.byField("id"));

    static {
        com.moddingfromamod.store.PlayerShelves.register(STORE, SHELF, SpeciesRegistry::load);
    }

    private static Path wishesDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("wishes");
    }

    /**
     * the six creatures every world used to start with — Blorp the Giant, Lava Beast, MEGA
     * CHICKEN, Guard Bot, Pink Spider Queen, Tiny Friend. From the first days of the mod, never
     * vetted for quality, their preset buttons retired, and still listed on every child's
     * watch as if he had made them. RETIRED: no world is given them, and a
     * saved copy is dropped like any superseded definition. A creature or egg already out in a
     * world with one of these ids finds no species and falls back to its plain look — the same
     * null-safe path as any missing definition, so no shim.
     */
    public static final java.util.Set<String> RETIRED_BUILT_INS = java.util.Set.of(
            "giant_slime", "lava_beast", "mega_chicken", "guard_bot", "pink_spider_queen",
            "tiny_friend");

    /** Creature definitions a shipped fix has superseded — see LearnedTemplates.SUPERSEDED. */
    private static final java.util.Set<String> SUPERSEDED;

    static {
        java.util.Set<String> all = new java.util.HashSet<>(
                java.util.Set.of("wither_storm", "storm_tentacle", "pink_dragon"));
        all.addAll(RETIRED_BUILT_INS);
        SUPERSEDED = java.util.Set.copyOf(all);
    }

    /** Whether a creature id is one a shipped change has taken away (see {@link #SUPERSEDED}). */
    public static boolean isSuperseded(String id) {
        return id != null && SUPERSEDED.contains(id.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * <b>: a creature already saved with no behaviour gets the one its picks choose</b> ("yes pls
     * edit those creatures too"). {@code choose} answers, from the creature's owner and id, the behaviour, or null when
     * it cannot say (a Magician-made creature keeps no picks to read). The stamp is not HIS edit, so it does not move the
     * creature up his history ({@code updatedAt} is left alone); declared in the store-migrations register. Returns
     * how many creatures it gave a behaviour; saves once if any.
     */
    public static synchronized int backfillBehaviour(MinecraftServer server,
                                                     java.util.function.BiFunction<String, String, Archetype> choose) {
        int done = 0;
        for (Species s : List.copyOf(SPECIES.values())) {
            if (s.archetype() != null || s.ownerId().isEmpty()) {
                continue;
            }
            Archetype a;
            try {
                a = choose.apply(s.ownerId(), s.id());
            } catch (RuntimeException e) {
                LOGGER.warn("behaviour backfill: {} could not be chosen: {}", s.id(), e.toString());
                continue;
            }
            if (a != null) {
                SPECIES.put(s.id(), s.withBehaves(a));
                done++;
            }
        }
        if (done > 0) {
            revision++;
            save(server);
            LOGGER.info("behaviour backfill: {} saved creature(s) given the behaviour their picks choose (#2170)", done);
        }
        return done;
    }

    /** Load this world's creatures, and every child's. */
    public static synchronized void load(MinecraftServer server) {
        revision++;
        SPECIES.clear();
        TIMES.clear();
        Path wishes = wishesDir(server);
        com.moddingfromamod.store.WishStoreFile.Read read = SHELF.read(wishes,
                com.moddingfromamod.runtime.RuntimeConfig.playersDir(), SCHEMA);
        if (read instanceof com.moddingfromamod.store.WishStoreFile.Read.Refused r) {
            // The rule, now for this store: before the envelope a damaged file was logged, the
            // map came back holding only the built-ins, and the next put() wrote that over every
            // creature the child had made.
            LOGGER.error("could not read species at {}: {}. Nothing will be saved over it.",
                    wishes.resolve(FILE), r.reason());
            return;
        }
        SHELF.refusedShelves().forEach((who, why) -> LOGGER.error(
                "could not read {}'s creatures: {}. Their shelf will not be written.", who, why));
        JsonObject root = read instanceof com.moddingfromamod.store.WishStoreFile.Read.Ok ok
                ? ok.root()
                : read instanceof com.moddingfromamod.store.WishStoreFile.Read.Legacy l
                ? l.root() : null;
        JsonElement arr = root == null ? null : root.get("species");
        // A record written by a newer Moddy than this one (its "v" above Species.RECORD_VERSION) is read as best this
        // build can, and the store is LATCHED: this build never saves its narrower view over the newer records.
        NEWER.reset();
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement el : arr.getAsJsonArray()) {
                if (el.isJsonObject() && Species.recordVersion(el.getAsJsonObject()) > Species.RECORD_VERSION) {
                    NEWER.observe(new com.moddingfromamod.store.WishStoreFile.Read.Refused("a creature record was written "
                            + "by a newer Moddy (record v" + Species.recordVersion(el.getAsJsonObject()) + ", this build "
                            + "knows v" + Species.RECORD_VERSION + ")"));
                    LOGGER.error("species: {} Nothing will be saved over it.", NEWER.refusalReason());
                    break;
                }
            }
        }
        // #104: a record saved under the old tool wording (below record v3) is repaired as it is read, declared in the
        // log, and written back at the current record version below, so it runs once per record. Every record the
        // repair does not change is kept exactly as read (it gains only its version when the store is next saved).
        List<String> repaired = List.of();
        int stale = 0;
        if (arr != null && arr.isJsonArray()) {
            stale = StatLineRepair.below(arr.getAsJsonArray(), Species.RECORD_VERSION);
            StatLineRepair.Result r = StatLineRepair.repairAll(arr.getAsJsonArray(), SpeciesRegistry::bodyVolume);
            arr = r.records();
            repaired = r.repaired();
        }
        if (arr != null && arr.isJsonArray()) {
            int bad = 0;
            for (JsonElement el : arr.getAsJsonArray()) {
                Species s = el.isJsonObject() ? Species.fromJson(el.getAsJsonObject()) : null;
                if (s == null) {
                    bad++;
                } else {
                    SPECIES.put(s.id(), s);
                    TIMES.read(s.id(), el.getAsJsonObject());
                }
            }
            if (bad > 0) {
                LOGGER.warn("skipped {} unreadable species definition(s)", bad);
            }
        }
        int purged = 0;
        for (String id : SUPERSEDED) {
            if (SPECIES.remove(id) != null) {
                purged++;
            }
        }
        int restatted = restatLibraryCopies();
        // NOTE: there is deliberately no "global creatures" load here any more. It read
        // ~/.moddingfromamod/species/ so a creature could appear in EVERY world — one half of the
        // split brain (see WorldPaths), and the half that could not even be reached: only the
        // hand-typed /wishmob persist ever wrote there, the agent had no way to, and on this
        // machine the folder was never created at all. The shelf is the explicit version of
        // that idea: a creature crosses worlds because a CHILD made it, not because it is global.
        SHELF.remember(payload(), com.moddingfromamod.marshal.ArtifactOwner::of);
        // an earlier issue's backfill AFTER the base is remembered, so a filled row reads as changed by us
        // and is written; filled before, the merge would take the disk's untimed row back.
        int filled = 0;
        var known = com.moddingfromamod.store.ShelfTimes.backfill(wishes);
        for (Species s : SPECIES.values()) {
            if (TIMES.fill(s.id(), known.timesOf(com.moddingfromamod.store.TimesBackfill.CREATURE,
                    s.id()))) {
                filled++;
            }
        }
        if (stale > 0) {
            LOGGER.info("species record migration v{} (#104, docs/STORE_MIGRATIONS.md): {} record(s) below it; the "
                    + "stat-line repair gave {} the damage their size says ({}); all are written back at v{}",
                    Species.RECORD_VERSION, stale, repaired.size(),
                    repaired.size() <= 20 ? String.join(", ", repaired) : String.join(", ", repaired.subList(0, 20)) + ", …",
                    Species.RECORD_VERSION);
        }
        if (purged > 0 || filled > 0 || restatted > 0 || stale > 0 || SHELF.needsConverge()) {
            save(server);
            if (purged > 0) {
                LOGGER.info("dropped {} superseded creature definition(s)", purged);
            }
            if (restatted > 0) {
                LOGGER.info("species record migration v{} (#99, docs/STORE_MIGRATIONS.md): {} unowned library "
                        + "creature copy(ies) took their shipped health, damage, armour and knockback", Species.RECORD_VERSION,
                        restatted);
            }
        }
        com.moddingfromamod.runtime.ModelRepository.shelveOwnedModels(ownedModelKeys());
        LOGGER.info("{} wish creature species available", SPECIES.size());
    }

    /**
     * <b>A world's copy of a library creature reads its numbers from the shipped record again</b> (#99). The first
     * spawn of a library creature (a starter's pet dragon) registers a SHARED copy in the world, and every later spawn
     * reads that copy ({@code WishMobCommands.resolveSpecies}), so a shipped change to its stat line never reached a
     * world that had spawned one. An edit of a library creature always forks it ({@code ForkPolicy}), so an unowned
     * copy under a library id is a pure copy: only its health, attack damage, armour and knockback are taken from the
     * shipped record (nothing about its look, powers or moves), and only where they differ. Returns how many changed.
     */
    private static int restatLibraryCopies() {
        int changed = 0;
        for (Species s : List.copyOf(SPECIES.values())) {
            if (!s.ownerId().isEmpty() || com.moddingfromamod.marshal.ArtifactOwner.isFork(s.id())) {
                continue;   // owned (his own, a fork): never touched (LibraryCopies)
            }
            Path shippedFile;
            try {
                shippedFile = com.moddingfromamod.library.WishLibrary.artifactFile("species", s.id(), "species.json");
            } catch (RuntimeException e) {
                continue;   // no library on this server: nothing to read again
            }
            if (shippedFile == null) {
                continue;
            }
            try {
                JsonObject shipped = com.google.gson.JsonParser.parseString(
                        java.nio.file.Files.readString(shippedFile, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                Species next = LibraryCopies.takeShipped(s, shipped);
                if (!next.equals(s)) {
                    SPECIES.put(s.id(), next);
                    changed++;
                }
            } catch (java.io.IOException | RuntimeException e) {
                LOGGER.warn("could not read the shipped record of library creature {}: {}", s.id(), e.toString());
            }
        }
        return changed;
    }

    /** A stored creature's body, measured on the geometry it draws (cubic blocks at scale 1); NaN when it draws none. */
    private static double bodyVolume(JsonObject record) {
        String key = record.has("modelKey") && record.get("modelKey").isJsonPrimitive()
                ? record.get("modelKey").getAsString() : "";
        try {
            var g = key.isEmpty() ? null : com.moddingfromamod.runtime.ModelRepository.geometry(key);
            return g == null ? Double.NaN : g.volume();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** Model key to owner, for every owned creature — what {@code ModelRepository} shelves by. */
    public static Map<String, String> ownedModelKeys() {
        Map<String, String> out = new java.util.HashMap<>();
        for (Species s : SPECIES.values()) {
            if (!s.ownerId().isEmpty() && s.modelKey() != null && !s.modelKey().isEmpty()) {
                out.put(s.modelKey(), s.ownerId());
            }
        }
        return out;
    }

    /** Latched when a record newer than this build knows was read: see {@link #load}. */
    private static final com.moddingfromamod.store.WishStoreFile.Guard NEWER = new com.moddingfromamod.store.WishStoreFile.Guard();

    private static void save(MinecraftServer server) {
        if (!NEWER.mayWrite()) {
            LOGGER.error("NOT saving species: {}. The file has been left alone on purpose.", NEWER.refusalReason());
            return;
        }
        if (!SHELF.mayWrite()) {
            LOGGER.error("NOT saving species — {} could not be read ({}). The file has been left "
                    + "alone on purpose; a grown-up should look at it.",
                    wishesDir(server).resolve(FILE), SHELF.refusalReason());
            return;
        }
        SHELF.write(wishesDir(server), com.moddingfromamod.runtime.RuntimeConfig.playersDir(),
                SCHEMA, payload(), com.moddingfromamod.marshal.ArtifactOwner::of, LOGGER::warn);
    }

    private static JsonObject payload() {
        JsonArray arr = new JsonArray();
        // Stable order so the file diffs cleanly when hand-edited.
        new LinkedHashMap<>(SPECIES).values().stream()
                .sorted(java.util.Comparator.comparing(Species::id))
                .forEach(s -> arr.add(TIMES.stamp(s.id(), s.toJson())));
        JsonObject root = new JsonObject();
        root.add("species", arr);
        return root;
    }
}
