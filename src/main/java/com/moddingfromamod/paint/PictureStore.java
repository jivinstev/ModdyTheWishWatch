package com.moddingfromamod.paint;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.ArtifactOwner;
import com.moddingfromamod.runtime.WorldPaths;
import com.moddingfromamod.store.WishStoreFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A picture's RECIPE, kept apart from where it hangs</b>.
 *
 * <p>{@link PaintRegistry} records a PLACEMENT: which wall, which map tiles, what it covered, so an
 * undo can take it down. That is a fact about one world and cannot travel: map ids are per world.
 * The recipe is the picture itself (its shapes, its size, how many blocks it spans, whose it is)
 * and that is what a child keeps. So it lives here, on his shelf
 * ({@code <players>/<uuid>/pictures.json}), and a picture he painted in Creative can be hung again,
 * or refined, in Survival, where it has never hung: the board is drawn fresh at his locked focus.
 *
 * <p>Same shape as {@code ItemSpecStore}: one in-memory map, split on write by owner, unioned on
 * read; unowned pictures stay with the world. A {@link #get} falls through to the wish library
 * ({@link LibraryPicture}), so a starter picture is a base to refine like any other, and never a
 * write target ({@code ForkPolicy} forks it).
 */
public final class PictureStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    static final String STORE = "pictures";
    static final int SCHEMA = 1;
    static final String FILE = "pictures.json";

    /** Bounded like the placement ledger. The oldest go first. */
    static final int MAX = 256;

    /** One picture's recipe. {@code ownerId} empty is SHARED ({@link ArtifactOwner}). */
    public record Picture(String id, String ownerId, PaintRecipe recipe, int wide, int tall) {

        public Picture {
            id = key(id);
            ownerId = ArtifactOwner.normalise(ownerId);
            recipe = recipe == null ? PaintRecipe.blank() : recipe;
            wide = PanelGeometry.clampSpan(wide);
            tall = PanelGeometry.clampSpan(tall);
        }

        public JsonObject toJson() {
            JsonObject o = recipe.toJson();
            o.addProperty("id", id);
            o.addProperty("wide", wide);
            o.addProperty("tall", tall);
            ArtifactOwner.stamp(o, ownerId);
            return o;
        }

        /** Null for anything unusable: one bad record must cost that record, never the file. */
        public static Picture fromJson(JsonObject o) {
            if (o == null || !o.has("id")) {
                return null;
            }
            try {
                PaintRecipe r = PaintRecipe.parse(o);
                if (r.isEmpty()) {
                    return null;
                }
                return new Picture(o.get("id").getAsString(), ArtifactOwner.of(o), r,
                        o.has("wide") ? o.get("wide").getAsInt() : 2,
                        o.has("tall") ? o.get("tall").getAsInt() : 2);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static final Map<String, Picture> PICTURES = new LinkedHashMap<>();

    /** When each picture was made and last changed — what newest-first sorts on. */
    private static final com.moddingfromamod.store.ArtifactTimes.Book TIMES =
            new com.moddingfromamod.store.ArtifactTimes.Book();

    /** When this picture was made and last changed; {@code NONE} when nobody knows. */
    public static com.moddingfromamod.store.ArtifactTimes.Times timesOf(String id) {
        return TIMES.get(id);
    }

    private static final com.moddingfromamod.store.PlayerShelf SHELF =
            new com.moddingfromamod.store.PlayerShelf(STORE, FILE, STORE,
                    com.moddingfromamod.store.PlayerShelf.byField("id"));

    static {
        com.moddingfromamod.store.PlayerShelves.register(STORE, SHELF, server -> load());
    }

    private PictureStore() {
    }

    static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** Read this world's pictures and every child's shelf. A refusal latches the writes off. */
    public static synchronized void load() {
        PICTURES.clear();
        TIMES.clear();
        Path world = WorldPaths.wishesDir();
        if (world == null) {
            return;
        }
        WishStoreFile.Read read = SHELF.read(world,
                com.moddingfromamod.runtime.RuntimeConfig.playersDir(), SCHEMA);
        if (read instanceof WishStoreFile.Read.Refused r) {
            LOGGER.error("could not read pictures from {}: {}. Nothing will be saved over it.",
                    world.resolve(FILE), r.reason());
            return;
        }
        SHELF.refusedShelves().forEach((who, why) -> LOGGER.error(
                "could not read {}'s pictures: {}. Their shelf will not be written.", who, why));
        JsonObject root = read instanceof WishStoreFile.Read.Ok ok ? ok.root()
                : read instanceof WishStoreFile.Read.Legacy l ? l.root() : null;
        JsonElement arr = root == null ? null : root.get(STORE);
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement el : arr.getAsJsonArray()) {
                Picture p = el.isJsonObject() ? Picture.fromJson(el.getAsJsonObject()) : null;
                if (p != null && !p.id().isEmpty()) {
                    PICTURES.put(p.id(), p);
                    TIMES.read(p.id(), el.getAsJsonObject());
                }
            }
        }
        SHELF.remember(payload(), ArtifactOwner::of);
        // an earlier issue's backfill, after the base (SpeciesRegistry.load says why the order matters).
        int filled = 0;
        var known = com.moddingfromamod.store.ShelfTimes.backfill(world);
        for (String id : PICTURES.keySet()) {
            if (TIMES.fill(id, known.timesOf(com.moddingfromamod.store.TimesBackfill.PAINTING,
                    id))) {
                filled++;
            }
        }
        if ((filled > 0 || SHELF.needsConverge()) && !PICTURES.isEmpty()) {
            save();
        }
    }

    /** Keep a picture's recipe — the newest last, so {@link #recent} is newest first. */
    public static synchronized void put(Picture p) {
        if (p == null || p.id().isEmpty() || p.recipe().isEmpty()) {
            return;
        }
        Picture had = PICTURES.remove(p.id());
        PICTURES.put(p.id(), p);
        if (had == null || !had.toJson().equals(p.toJson())) {
            TIMES.touch(p.id(), com.moddingfromamod.store.ShelfTimes.now());
        }
        while (PICTURES.size() > MAX) {
            String oldest = PICTURES.keySet().iterator().next();
            PICTURES.remove(oldest);
            TIMES.forget(oldest);
        }
        save();
    }

    /** This world's (or his shelf's) picture, else the library's; null when there is none. */
    public static synchronized Picture get(String id) {
        Picture own = PICTURES.get(key(id));
        return own != null ? own : fromLibrary(key(id));
    }

    /** Whether the wish library serves this id — a base, never a write target. */
    public static boolean inLibrary(String id) {
        return com.moddingfromamod.library.WishLibrary.artifactDir("pictures", key(id)) != null;
    }

    /**
     * The pictures this child may see, newest first: his own, the shared ones, and the library's.
     * The library's come last, because "the picture" in a wish means the one he was just making.
     */
    public static synchronized List<Picture> recent(String viewer) {
        List<Picture> out = new ArrayList<>();
        List<Picture> mine = new ArrayList<>(PICTURES.values());
        java.util.Collections.reverse(mine);
        // newest first by when it last changed, not by where the map happens to hold it.
        mine.sort(java.util.Comparator.comparingLong(
                (Picture p) -> TIMES.get(p.id()).recency()).reversed());
        for (Picture p : mine) {
            if (ArtifactOwner.visibleTo(viewer, p.ownerId())) {
                out.add(p);
            }
        }
        for (String id : com.moddingfromamod.library.WishLibrary.keys("pictures")) {
            if (!PICTURES.containsKey(key(id))) {
                Picture lib = fromLibrary(key(id));
                if (lib != null) {
                    out.add(lib);
                }
            }
        }
        return out;
    }

    /** For tests: forget everything in memory. The disk is untouched. */
    public static synchronized void clearForTest() {
        PICTURES.clear();
        TIMES.clear();
    }

    private static Picture fromLibrary(String id) {
        Path f = com.moddingfromamod.library.WishLibrary.artifactFile("pictures", id,
                LibraryPicture.FILE);
        if (f == null) {
            return null;
        }
        try {
            LibraryPicture lp = LibraryPicture.parse(java.nio.file.Files.readString(f));
            return new Picture(id, "", lp.recipe(), lp.wide(), lp.tall());
        } catch (java.io.IOException | IllegalArgumentException e) {
            LOGGER.warn("could not read library picture '{}': {}", id, e.toString());
            return null;
        }
    }

    private static JsonObject payload() {
        JsonArray arr = new JsonArray();
        for (Picture p : PICTURES.values()) {
            arr.add(TIMES.stamp(p.id(), p.toJson()));
        }
        JsonObject o = new JsonObject();
        o.add(STORE, arr);
        return o;
    }

    private static void save() {
        Path world = WorldPaths.wishesDir();
        if (world == null) {
            return;   // no world open — a direct call; nothing to write to
        }
        if (!SHELF.mayWrite()) {
            LOGGER.error("NOT saving pictures — {} could not be read ({}). The file has been left "
                    + "alone on purpose; a grown-up should look at it.", world.resolve(FILE),
                    SHELF.refusalReason());
            return;
        }
        SHELF.write(world, com.moddingfromamod.runtime.RuntimeConfig.playersDir(), SCHEMA,
                payload(), ArtifactOwner::of, LOGGER::warn);
    }
}
