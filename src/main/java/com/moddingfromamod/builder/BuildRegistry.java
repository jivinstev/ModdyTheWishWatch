package com.moddingfromamod.builder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Buildings the agent has invented, stored with the world.
 *
 * <p>This is what lets a building become <b>instant</b>. A learned wish template is a list of
 * commands, so a plan that only existed inside one agent turn could never be re-run offline — the
 * child would pay a round-trip for the same castle every time, and the self-improving loop would
 * simply not apply to buildings. Saved under an id, a template can say {@code wishbuild
 * floating_castle} and the offline engine rebuilds it in a couple of seconds.
 *
 * <p>Plain JSON next to the learned wishes, so a grown-up can read, edit or delete a building by
 * hand. Same shape as {@link BuildPlan}'s own serialisation.
 */
public final class BuildRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Bounded so an agent in a loop can't fill the save with buildings. */
    private static final int MAX_PLANS = 128;

    private static final Map<String, BuildPlan> PLANS = new LinkedHashMap<>();

    /** When each building was made and last changed — what the watch sorts on. */
    private static final com.moddingfromamod.store.ArtifactTimes.Book TIMES =
            new com.moddingfromamod.store.ArtifactTimes.Book();

    /** When this building was made and last changed; {@code NONE} when nobody knows. */
    public static com.moddingfromamod.store.ArtifactTimes.Times timesOf(String id) {
        return TIMES.get(id);
    }

    /**
     * Where each building was last put up IN THIS WORLD.
     *
     * <p>Without this, "give it crenellations" builds a SECOND castle wherever the child happens to
     * be standing, instead of changing the one they are looking at. Remembering the anchor is what
     * lets an edit re-render the building in place.
     *
     * <p>Saved, on the building's own record ({@link BuildPlacements}), so it survives a
     * restart: before, every release forgot where every building stood.
     */
    private static final Map<String, long[]> ANCHORS = new LinkedHashMap<>();
    /**
     * Which world each building stands in — per-run, alongside its anchor.
     *
     * <p>remove_build used to resolve the level from the first online player, so removing a
     * building placed in the Nether cleared those coordinates in the Overworld instead. That does
     * not fail quietly; it deletes the wrong world's terrain. See
     * {@link com.moddingfromamod.agent.ArtifactLevel}.
     */
    private static final Map<String, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>>
            DIMENSIONS = new LinkedHashMap<>();

    private BuildRegistry() {
    }

    private static final String FILE = "buildings.json";

    /** The envelope name, and the newest schema this build reads (stamped this store). */
    static final String STORE = "buildings";
    static final int SCHEMA = 1;

    /**
     * A child's buildings live on his shelf ({@code <players>/<uuid>/buildings.json}), so
     * "build my castle again" works in Survival for a castle he designed in Creative. Where one
     * stands rides on the same record — one row per WORLD ({@link BuildPlacements}), so
     * the Creative world's position never moves a Survival copy.
     */
    private static final com.moddingfromamod.store.PlayerShelf SHELF =
            new com.moddingfromamod.store.PlayerShelf(STORE, FILE, "buildings",
                    com.moddingfromamod.store.PlayerShelf.byField("id"));

    static {
        com.moddingfromamod.store.PlayerShelves.register(STORE, SHELF, BuildRegistry::load);
    }

    private static Path wishesDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("wishes");
    }

    /** Load this world's buildings, and every child's. A damaged file must never break wishes. */
    public static synchronized void load(MinecraftServer server) {
        PLANS.clear();
        TIMES.clear();
        PLACED_ROWS.clear();
        world = com.moddingfromamod.world.WorldModuleHost.worldKey(server);
        seed = server.overworld() == null ? 0L : server.overworld().getSeed();
        Path wishes = wishesDir(server);
        com.moddingfromamod.store.WishStoreFile.Read read = SHELF.read(wishes,
                com.moddingfromamod.runtime.RuntimeConfig.playersDir(), SCHEMA);
        if (read instanceof com.moddingfromamod.store.WishStoreFile.Read.Refused r) {
            LOGGER.error("could not read saved buildings at {}: {}. Nothing will be saved over it.",
                    wishes.resolve(FILE), r.reason());
            return;
        }
        SHELF.refusedShelves().forEach((who, why) -> LOGGER.error(
                "could not read {}'s buildings: {}. Their shelf will not be written.", who, why));
        JsonObject root = read instanceof com.moddingfromamod.store.WishStoreFile.Read.Ok ok
                ? ok.root()
                : read instanceof com.moddingfromamod.store.WishStoreFile.Read.Legacy l
                ? l.root() : null;
        JsonElement arr = root == null ? null : root.get("buildings");
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement el : arr.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                BuildPlan plan = BuildPlan.fromJson(el.getAsJsonObject());
                if (plan != null) {
                    PLANS.put(plan.id(), plan);
                    TIMES.read(plan.id(), el.getAsJsonObject());
                    readPlacement(plan.id(), el.getAsJsonObject());
                }
            }
        }
        if (!PLANS.isEmpty()) {
            LOGGER.info("loaded {} saved building(s)", PLANS.size());
        }
        SHELF.remember(payload(), com.moddingfromamod.marshal.ArtifactOwner::of);
        // an earlier issue's backfill, after the base (see SpeciesRegistry.load for why the order matters).
        int filled = 0;
        var known = com.moddingfromamod.store.ShelfTimes.backfill(wishes);
        for (String id : PLANS.keySet()) {
            if (TIMES.fill(id, known.timesOf(com.moddingfromamod.store.TimesBackfill.BUILD, id))) {
                filled++;
            }
        }
        if (filled > 0 || SHELF.needsConverge()) {
            save(server);   // a pre-T9.1b world: move each child's buildings onto his shelf now
        }
    }

    /**
     * How many versions back an undo can walk —.
     *
     * <p>In memory on purpose, and the issue says why: the STACK need not survive a restart, only
     * the RESULT must. Once he undoes, the reverted version is the persisted one from then on, so
     * the next refinement inherits it whether or not anything remembers how we got there.
     */
    public static final int MAX_HISTORY = 5;

    /** The versions this building had before its current one, newest first. */
    private static final Map<String, java.util.Deque<BuildPlan>> HISTORY = new java.util.HashMap<>();

    /**
     * Save (or replace) a building and persist it, keeping the version it replaces.
     *
     * <h2>It may not store the plan it was handed </h2>
     *
     * <p>Replace-by-id is how a building is REFINED — <i>"make the lighthouse taller"</i> comes back
     * here under the same id — so this is also the building half of copy-on-refine. When the id
     * belongs to the library, or to another child, {@link ForkPolicy} says FORK and what is stored
     * is {@code <id>~<his short uuid>}, owned by him, with the original untouched.
     *
     * <p><b>So it returns what it stored.</b> The caller has to know: the anchor it remembers, the
     * command it prints and the button it offers all name an id, and naming the one it asked for
     * after the registry stored another is the shape of bug that reports success and serves
     * nothing. Every test caller ignoring the return is unaffected — with nothing bound there is no
     * child to fork for and the answer is the plan that came in.
     *
     * @return the plan actually stored, or null if there was nothing to store
     */
    public static synchronized BuildPlan put(MinecraftServer server, BuildPlan plan) {
        if (plan == null || !plan.isValid()) {
            return null;
        }
        BuildPlan incoming = scoped(plan);
        // KEEP THE ONE IT REPLACES --. Undo used to clear the CURRENT merged plan, so undoing
        // "put a dome on it" took the castle and the dome together. There was no previous version
        // anywhere to go back to: this line is the whole of that.
        BuildPlan outgoing = PLANS.get(incoming.id());
        if (outgoing != null && !outgoing.equals(incoming)) {
            var stack = HISTORY.computeIfAbsent(incoming.id(), k -> new java.util.ArrayDeque<>());
            stack.addFirst(outgoing);
            while (stack.size() > MAX_HISTORY) {
                stack.removeLast();
            }
        }
        PLANS.remove(incoming.id());
        PLANS.put(incoming.id(), incoming);
        if (outgoing == null || !outgoing.equals(incoming)) {
            TIMES.touch(incoming.id(), com.moddingfromamod.store.ShelfTimes.now());
        }
        while (PLANS.size() > MAX_PLANS) {
            String oldest = PLANS.keySet().iterator().next();
            PLANS.remove(oldest);
            TIMES.forget(oldest);
            PLACED_ROWS.remove(oldest);
        }
        save(server);
        // wished again and saved — back on his watch if he had hidden it.
        com.moddingfromamod.wish.WatchHides.unhideSaved(incoming.ownerId(), incoming.id());
        return incoming;
    }

    /**
     * The plan as it should be STORED: the child's own fork of a curated or borrowed id, his own
     * name on a building he is making for the first time, and nothing at all otherwise.
     *
     * <p>A REFUSE — a library building with nobody to attribute a fork to — keeps the incoming plan
     * under its own id rather than dropping the wish on the floor. That case is a gate or a direct
     * call, never a child: {@code ForkPolicy} only reaches it with no run bound at all, and a
     * single-child server has one right answer, which is the one it had before per-child ownership.
     */
    private static BuildPlan scoped(BuildPlan plan) {
        String asker = com.moddingfromamod.agent.AgentContext.currentPlayerId();
        BuildPlan had = PLANS.get(plan.id());
        boolean known = had != null;
        boolean inLibrary = com.moddingfromamod.library.WishLibrary.artifactDir(
                "buildings", plan.id()) != null;
        if (!known && !inLibrary) {
            // Brand new: his, if anyone's. Never re-stamps, because there is nothing to re-stamp.
            return plan.ownerId().isEmpty() && !asker.isEmpty() ? plan.withOwner(asker) : plan;
        }
        String owner = known ? had.ownerId() : "";
        var decision = com.moddingfromamod.marshal.ForkPolicy.decide(
                asker, plan.id(), owner, inLibrary);
        if (decision == com.moddingfromamod.marshal.ForkPolicy.Decision.FORK) {
            String key = com.moddingfromamod.marshal.ArtifactOwner.forkKey(plan.id(), asker);
            return key.isEmpty() ? plan : plan.forkedAs(key, asker);
        }
        String stamp = com.moddingfromamod.marshal.ForkPolicy.ownerFor(decision, asker, owner);
        return stamp.isEmpty() ? plan.withOwner("") : plan.withOwner(stamp);
    }

    /** Whose this building is, or {@code ""} when nobody's. */
    public static synchronized String ownerOf(String id) {
        BuildPlan p = id == null ? null : PLANS.get(id.trim().toLowerCase(java.util.Locale.ROOT));
        return p == null ? "" : p.ownerId();
    }

    /** Remember where a building went, so a later edit can rebuild it in the same spot. */
    public static synchronized void rememberAnchor(String id, long packedPos) {
        rememberAnchor(id, packedPos, null);
    }

    /**
     * Remember where — and in WHICH WORLD — a building went. Memory only until the next save; a
     * caller that has the server uses {@link #placed}, which writes it down at once.
     */
    public static synchronized void rememberAnchor(String id, long packedPos,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        if (id != null && !id.isBlank()) {
            String key = id.trim().toLowerCase(java.util.Locale.ROOT);
            ANCHORS.put(key, new long[]{packedPos});
            REMOVED.remove(key);
            if (dimension != null) {
                DIMENSIONS.put(key, dimension);
            }
        }
    }

    /**
     * <b>A building went up here</b>: its anchor, its world, which way it faces him, and
     * SAVED — so after a restart remove, take-me-to, inspect, an edit in place and its spots all
     * still find it. {@code toward} may be null when nobody was near to face.
     */
    public static synchronized void placed(MinecraftServer server, String id, long packedPos,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int[] toward) {
        rememberAnchor(id, packedPos, dimension);
        if (toward != null) {
            rememberToward(id, toward[0], toward[1]);
        }
        saveIfKnown(server, id);
    }

    /**
     * <b>It was taken down</b> (remove_build, an undo of its creation). The position is kept, so an
     * edit can still rebuild it where it stood, but it is no longer {@link #standing}: take-me-to,
     * its spots and a replay stop treating air as a building.
     */
    public static synchronized void markRemoved(MinecraftServer server, String id) {
        if (id != null && !id.isBlank()) {
            String key = id.trim().toLowerCase(java.util.Locale.ROOT);
            if (ANCHORS.containsKey(key)) {
                REMOVED.add(key);
                saveIfKnown(server, key);
            }
        }
    }

    private static void saveIfKnown(MinecraftServer server, String id) {
        if (server != null && id != null && PLANS.containsKey(id.trim().toLowerCase(java.util.Locale.ROOT))) {
            save(server);
        }
    }

    /**
     * Where he stood when each building went up, relative to its anchor — which wall is
     * its FRONT when the building has no door. Saved with the anchor.
     */
    private static final Map<String, int[]> TOWARD = new LinkedHashMap<>();

    /** Buildings in this world that were taken down (their anchor is kept for an in-place rebuild). */
    private static final java.util.Set<String> REMOVED = new java.util.HashSet<>();

    /**
     * Every world's {@code placed} row for each record, as read — so a save writes back the OTHER
     * worlds' positions untouched and replaces only this world's.
     */
    private static final Map<String, Map<String, JsonObject>> PLACED_ROWS = new java.util.HashMap<>();

    /** This world's key and seed ({@link BuildPlacements}); null until a world is loaded. */
    private static String world;
    private static long seed;

    /** Remember which way the building faces him: his position minus its anchor. */
    public static synchronized void rememberToward(String id, int dx, int dz) {
        if (id != null && !id.isBlank()) {
            TOWARD.put(id.trim().toLowerCase(java.util.Locale.ROOT), new int[]{dx, dz});
        }
    }

    /** {dx, dz} from the anchor to where he stood when it went up, or {0, 0} when nobody knows. */
    public static synchronized int[] towardOf(String id) {
        int[] t = id == null ? null : TOWARD.get(id.trim().toLowerCase(java.util.Locale.ROOT));
        return t == null ? new int[]{0, 0} : t.clone();
    }

    /** Which world this building stands in, or null if it was placed before we recorded that. */
    public static synchronized net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>
            dimensionOf(String id) {
        return id == null ? null : DIMENSIONS.get(id.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The ids of every building standing this run, in the order they first went up — what an {@code at}
     * that names a building by its base id ({@code castle}) or its display name searches when no
     * building of exactly that id stands: his fork {@code castle~1a2b3c4d} is the castle he means.
     * A building taken down is not standing (keeps its anchor so an edit rebuilds there).
     */
    public static synchronized List<String> standingIds() {
        List<String> out = new ArrayList<>();
        for (String id : ANCHORS.keySet()) {
            if (!REMOVED.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Where this building was last put up in this world, or null if it never was (or was put up
     * before positions were saved). Still answers for one that was taken down — an edit
     * rebuilds there; ask {@link #standing} for "is it there now".
     */
    public static synchronized Long anchorOf(String id) {
        long[] a = id == null ? null : ANCHORS.get(id.trim().toLowerCase(java.util.Locale.ROOT));
        return a == null ? null : a[0];
    }

    /** Is this building standing in this world right now — put up here, and not taken down since? */
    public static synchronized boolean standing(String id) {
        String key = id == null ? null : id.trim().toLowerCase(java.util.Locale.ROOT);
        return key != null && ANCHORS.containsKey(key) && !REMOVED.contains(key);
    }

    /**
     * {@code TRUE} when it stands here, {@code FALSE} when it is KNOWN not to (taken down, in
     * another world, an older world of this name), {@code null} when no position was ever saved — a
     * building from before positions were kept may well stand, so the picker must not drop it.
     */
    public static synchronized Boolean standsHere(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (standing(key)) {
            return Boolean.TRUE;
        }
        if (REMOVED.contains(key)) {
            return Boolean.FALSE;
        }
        var found = BuildPlacements.find(PLACED_ROWS.getOrDefault(key, Map.of()), world, seed);
        return found.status() == BuildPlacements.Status.UNKNOWN ? null : Boolean.FALSE;
    }

    /**
     * Why a building is not {@link #standing} here, in words for the agent: taken down, in another
     * world, or built before positions were saved — never a guess. Null when it stands.
     */
    public static synchronized String whyNotHere(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (standing(key)) {
            return null;
        }
        if (REMOVED.contains(key)) {
            return BuildPlacements.whyNotHere(key, BuildPlacements.Status.REMOVED, null);
        }
        var found = BuildPlacements.find(PLACED_ROWS.getOrDefault(key, Map.of()), world, seed);
        return BuildPlacements.whyNotHere(key, found.status(), found.otherWorld());
    }

    /**
     * <b>Where {@code id} stands in this world, read from DISK past the in-memory mirror</b> — the
     * seam a GameTest uses to prove a position was saved without wiping the registry its neighbours
     * read (LEARNINGS: "a GameTest never wipes a registry that has a per-test seam"). A throwaway
     * shelf reader, so this store's own merge base is untouched; the row is judged by the same
     * {@link BuildPlacements#find} {@link #load} uses. Null when there is no accepted row here.
     */
    public static BuildPlacements.Placement savedPlacementOnDisk(MinecraftServer server, String id) {
        var fresh = new com.moddingfromamod.store.PlayerShelf(STORE, FILE, "buildings",
                com.moddingfromamod.store.PlayerShelf.byField("id"));
        var read = fresh.read(wishesDir(server), com.moddingfromamod.runtime.RuntimeConfig.playersDir(), SCHEMA);
        JsonObject root = read instanceof com.moddingfromamod.store.WishStoreFile.Read.Ok ok ? ok.root()
                : read instanceof com.moddingfromamod.store.WishStoreFile.Read.Legacy l ? l.root() : null;
        JsonElement arr = root == null ? null : root.get("buildings");
        if (arr == null || !arr.isJsonArray() || id == null) {
            return null;
        }
        String key = id.trim().toLowerCase(java.util.Locale.ROOT);
        for (JsonElement el : arr.getAsJsonArray()) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")
                    && key.equals(el.getAsJsonObject().get("id").getAsString())) {
                return BuildPlacements.find(BuildPlacements.rows(el.getAsJsonObject()),
                        com.moddingfromamod.world.WorldModuleHost.worldKey(server),
                        server.overworld().getSeed()).here();
            }
        }
        return null;
    }

    /** Fill this world's position from a record as it was read — memory wins over disk (a refresh). */
    private static void readPlacement(String id, JsonObject record) {
        Map<String, JsonObject> rows = BuildPlacements.rows(record);
        if (rows.isEmpty()) {
            PLACED_ROWS.remove(id);
            return;
        }
        PLACED_ROWS.put(id, rows);
        var found = BuildPlacements.find(rows, world, seed);
        if (found.here() == null || ANCHORS.containsKey(id)) {
            return;
        }
        BuildPlacements.Placement p = found.here();
        ANCHORS.put(id, new long[]{net.minecraft.core.BlockPos.asLong(p.x(), p.y(), p.z())});
        if (p.dim() != null) {
            try {
                DIMENSIONS.put(id, net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(p.dim())));
            } catch (RuntimeException e) {
                // an unreadable dimension: ArtifactLevel falls back to the overworld
            }
        }
        if (p.towardX() != 0 || p.towardZ() != 0) {
            TOWARD.put(id, new int[]{p.towardX(), p.towardZ()});
        }
        if (p.removed()) {
            REMOVED.add(id);
        } else {
            REMOVED.remove(id);
        }
    }

    /** This world's row for {@code id}, from memory, or null when it was never put up here. */
    private static BuildPlacements.Placement placementHere(String id) {
        long[] a = ANCHORS.get(id);
        if (a == null || world == null) {
            return null;
        }
        net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.of(a[0]);
        var dim = DIMENSIONS.get(id);
        int[] t = TOWARD.getOrDefault(id, new int[]{0, 0});
        return new BuildPlacements.Placement(dim == null ? null : dim.location().toString(),
                p.getX(), p.getY(), p.getZ(), t[0], t[1], seed, REMOVED.contains(id));
    }

    /**
     * Take the version before this one off the stack, or null if this building has only ever had
     * one — which is a CREATION, and undoing a creation still means removing it.
     */
    public static synchronized BuildPlan popPrevious(String id) {
        var stack = HISTORY.get(id);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        BuildPlan prev = stack.removeFirst();
        if (stack.isEmpty()) {
            HISTORY.remove(id);
        }
        return prev;
    }

    /** How many versions back this building can be walked. Test seam. */
    public static synchronized int historyDepth(String id) {
        var stack = HISTORY.get(id);
        return stack == null ? 0 : stack.size();
    }

    /**
     * Make {@code plan} the current version WITHOUT pushing the one it replaces.
     *
     * <p>Undo is not an edit. Going through {@link #put} would stack the version being undone back
     * on top of the history, so a second undo would walk forwards into what he just reverted.
     */
    public static synchronized void restore(MinecraftServer server, BuildPlan plan) {
        if (plan == null || !plan.isValid()) {
            return;
        }
        PLANS.remove(plan.id());
        PLANS.put(plan.id(), plan);
        save(server);
    }

    /**
     * <b>Memory only — never written anywhere.</b> For a probe that stages a panel on a real
     * client (the photograph): a probe must never write a child's real shelf
     * (docs/WISH_STORES.md §9), and {@link #put} does. The species and item registries already have
     * their memory-only {@code replaceAll}; this is the building twin.
     */
    public static synchronized void putInMemoryForProbe(BuildPlan plan) {
        if (plan != null && plan.isValid()) {
            PLANS.put(plan.id(), plan);
        }
    }

    /**
     * <b>Memory only — never written anywhere.</b> The twin of {@link #putInMemoryForProbe}: forget
     * one building in this process without touching any shelf, for a test that must not see what an
     * earlier test left near the shared spawn. Never a child's undo: that is
     * {@link #markRemoved}, which keeps the record.
     */
    public static synchronized void forgetInMemoryForProbe(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        String key = id.trim().toLowerCase(java.util.Locale.ROOT);
        PLANS.remove(key);
        HISTORY.remove(key);
        ANCHORS.remove(key);
        DIMENSIONS.remove(key);
        TOWARD.remove(key);
        REMOVED.remove(key);
    }

    public static synchronized BuildPlan get(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toLowerCase(java.util.Locale.ROOT);
        BuildPlan own = PLANS.get(key);
        return own != null ? own : fromLibrary(key);
    }

    /**
     * <b>A curated building this world has never built</b> — read out of
     * {@code <libraryDir>/buildings/<id>/build.json} on demand.
     *
     * <p>Read-only and NOT put into {@code PLANS}: the map is what {@link #save} writes back, so a
     * library plan cached there would be copied into the world's {@code buildings.json} the next
     * time anything else was saved, and the library's checkout would quietly become this world's
     * property. Re-reading a small JSON file per {@code /wishbuild} is the cheaper mistake.
     *
     * <p>Same file name as the Mod Repo's published shape ({@code wishes/build/<id>/build.json}), so
     * a promoted building is a copy rather than a conversion.
     */
    private static BuildPlan fromLibrary(String id) {
        java.nio.file.Path f =
                com.moddingfromamod.library.WishLibrary.artifactFile("buildings", id, "build.json");
        if (f == null) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(
                    Files.readString(f, StandardCharsets.UTF_8));
            BuildPlan plan = root != null && root.isJsonObject()
                    ? BuildPlan.fromJson(root.getAsJsonObject()) : null;
            if (plan == null) {
                LOGGER.warn("library building '{}' at {} is not a readable plan", id, f);
            }
            return plan;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not read library building '{}' at {}: {}", id, f, e.toString());
            return null;
        }
    }

    /** This world's buildings, then the library's — what {@code list_builds} and tab-complete show. */
    public static synchronized List<String> ids() {
        List<String> out = new ArrayList<>(PLANS.keySet());
        for (String key : com.moddingfromamod.library.WishLibrary.keys("buildings")) {
            if (!out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    public static synchronized int count() {
        return PLANS.size();
    }

    /** Forget this world's buildings (server stop). */
    public static synchronized void clear() {
        HISTORY.clear();
        PLANS.clear();
        TIMES.clear();
        // A remembered position from a previous world would be meaningless; the next load reads
        // this world's own from the records.
        ANCHORS.clear();
        DIMENSIONS.clear();
        TOWARD.clear();
        REMOVED.clear();
        PLACED_ROWS.clear();
        world = null;
    }

    private static void save(MinecraftServer server) {
        if (!SHELF.mayWrite()) {
            LOGGER.error("NOT saving buildings — {} could not be read ({}). The file has been left "
                    + "alone on purpose; a grown-up should look at it.",
                    wishesDir(server).resolve(FILE), SHELF.refusalReason());
            return;
        }
        SHELF.write(wishesDir(server), com.moddingfromamod.runtime.RuntimeConfig.playersDir(),
                SCHEMA, payload(), com.moddingfromamod.marshal.ArtifactOwner::of, LOGGER::warn);
    }

    private static JsonObject payload() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (BuildPlan plan : PLANS.values()) {
            arr.add(BuildPlacements.stamp(TIMES.stamp(plan.id(), plan.toJson()),
                    PLACED_ROWS.getOrDefault(plan.id(), Map.of()), world, placementHere(plan.id())));
        }
        root.add("buildings", arr);
        return root;
    }
}
