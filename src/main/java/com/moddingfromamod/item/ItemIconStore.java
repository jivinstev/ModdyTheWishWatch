package com.moddingfromamod.item;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.WorldPaths;
import com.moddingfromamod.store.PlayerShelf;
import com.moddingfromamod.store.WishStoreFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>{@code <world>/wishes/item-icons.json} — each wished item's {@link ItemLook}, keyed by the
 * design's id</b>. The icon half of the split; {@code item-specs.json} holds the other half.
 *
 * <pre>
 *   {"store":"item-icons","v":1,"icons":[{"id":"zonkbolt_lightning","sprite":"wand","tint":"purple"}]}
 * </pre>
 *
 * <p><b>Its own file so it has its own {@code v}.</b> The point of the split is that an icon revamp can change
 * what an icon is — trims, accents, a second tint — by bumping THIS store's schema, without the
 * design store moving and without a jar that understands designs but not trims refusing a child's
 * whole item list.
 *
 * <p><b>It is written by {@link ItemSpecStore} and by nothing else</b>, from the same in-memory map,
 * so the two files cannot disagree about which items exist: an icon whose design is gone is dropped
 * at the next save. It has its own refusal latch (the store's rule 2), and {@link ItemSpecStore#save} will
 * not write designs while this one is latched — see there for why.
 */
final class ItemIconStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** The name this store owns in the envelope, and the newest schema it can read. */
    static final String STORE = "item-icons";
    static final int SCHEMA = 1;

    /**
     * A child's icons sit on his shelf beside his designs ({@code <players>/<uuid>/
     * item-icons.json}). An icon row carries no owner of its own — the split kept it to the look — so
     * whose it is comes from the design with the same id ({@link ItemSpecStore} hands the map in).
     * The world file keeps its refusal latch (the store's rule 2); a child's latches only him.
     */
    private static final PlayerShelf SHELF =
            new PlayerShelf(STORE, "item-icons.json", "icons", PlayerShelf.byField("id"));

    private ItemIconStore() {
    }

    static Path file() {
        return WorldPaths.wishesDir().resolve("item-icons.json");
    }

    /** Where the file is, for a log line — never throws, even with no world open. */
    static String where() {
        Path dir = WorldPaths.wishesDir();
        return dir == null ? "item-icons.json (no world open)"
                : dir.resolve("item-icons.json").toString();
    }

    /** False once this world's icon file could not be read. */
    static boolean mayWrite() {
        return SHELF.mayWrite();
    }

    /**
     * This world's looks and every child's, by lower-cased design id. Empty for a new world, for a
     * world from before the split, and for a file that was refused — the last one latched, so
     * nothing writes over it.
     */
    static Map<String, ItemLook> load() {
        Map<String, ItemLook> out = new LinkedHashMap<>();
        WishStoreFile.Read read = SHELF.read(WorldPaths.wishesDir(),
                com.moddingfromamod.runtime.RuntimeConfig.playersDir(), SCHEMA);
        if (read instanceof WishStoreFile.Read.Refused r) {
            LOGGER.error("could not read item icons from {}: {}. Nothing will be saved over it.",
                    where(), r.reason());
            return out;
        }
        SHELF.refusedShelves().forEach((who, why) -> LOGGER.error(
                "could not read {}'s item icons: {}. Their shelf will not be written.", who, why));
        if (read instanceof WishStoreFile.Read.Absent) {
            return out;
        }
        JsonObject root = read instanceof WishStoreFile.Read.Ok ok ? ok.root()
                : ((WishStoreFile.Read.Legacy) read).root();
        adopt(root, out);
        return out;
    }

    /** The merge base, after {@link ItemSpecStore} has joined looks to designs. */
    static void remember(Map<String, ItemLook> looks, Map<String, String> ownerById) {
        SHELF.remember(payload(looks), ownerOf(ownerById));
    }

    /** The {@code icons} array into {@code out}. One damaged record is skipped, never fatal. */
    static int adopt(JsonObject root, Map<String, ItemLook> out) {
        JsonElement arr = root.get("icons");
        if (arr == null || !arr.isJsonArray()) {
            return 0;
        }
        int skipped = 0;
        for (JsonElement el : arr.getAsJsonArray()) {
            JsonObject o = el.isJsonObject() ? el.getAsJsonObject() : null;
            JsonElement id = o == null ? null : o.get("id");
            ItemLook look = o == null ? null : ItemLook.fromJson(o);
            if (id == null || !id.isJsonPrimitive() || id.getAsString().isBlank() || look == null) {
                skipped++;
                continue;
            }
            out.put(key(id.getAsString()), look);
        }
        if (skipped > 0) {
            LOGGER.warn("skipped {} damaged item icon(s)", skipped);
        }
        return skipped;
    }

    /** The payload {@link #save} writes, before the stamp. Visible for the shape test. */
    static JsonObject payload(Map<String, ItemLook> looks) {
        JsonArray arr = new JsonArray();
        looks.forEach((id, look) -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            look.toJson().entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
            arr.add(o);
        });
        JsonObject payload = new JsonObject();
        payload.add("icons", arr);
        return payload;
    }

    /**
     * @param ownerById design id to its owner, so each look goes where its design goes
     * @return true when the files now hold exactly {@code looks}; false when nothing was written
     */
    static boolean save(Map<String, ItemLook> looks, Map<String, String> ownerById) {
        if (!SHELF.mayWrite()) {
            LOGGER.error("NOT saving item icons — {} could not be read ({}). "
                    + "The file has been left alone on purpose; a grown-up should look at it.",
                    where(), SHELF.refusalReason());
            return false;
        }
        return SHELF.write(WorldPaths.wishesDir(),
                com.moddingfromamod.runtime.RuntimeConfig.playersDir(), SCHEMA, payload(looks),
                ownerOf(ownerById), LOGGER::warn);
    }

    private static java.util.function.Function<JsonObject, String> ownerOf(
            Map<String, String> ownerById) {
        return o -> {
            JsonElement id = o.get("id");
            return id == null || !id.isJsonPrimitive() ? ""
                    : ownerById.getOrDefault(key(id.getAsString()), "");
        };
    }

    static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

}
