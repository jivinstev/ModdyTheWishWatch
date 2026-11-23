package com.moddingfromamod.bag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.moddingfromamod.store.WishStoreFile;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>One child's Wish Bag on one world, on disk</b> —
 * {@code <players>/<uuid>/wish-bag/<world>.json}.
 *
 * <pre>
 * {"store":"wish-bag","v":1,"world":"main","ownerId":"…",
 *  "items":[{"stack":{…ItemStack.CODEC…}}, …]}          ← newest first
 * </pre>
 *
 * <p><b>On his SHELF, one file per WORLD</b> (one bag per world — one world's things do
 * not reach another). The shelf is where his things live ({@code docs/WISH_STORES.md} §9),
 * so a world reset does not empty his bag, and each world's file has exactly one writer — the
 * server running that world — so unlike the shared shelf stores it needs no three-way merge.
 *
 * <p>The three rules of {@code WISH_STORES.md} §2 through {@link WishStoreFile}: an unreadable file
 * or one a newer Moddy wrote is REFUSED, latches this bag closed and is never written over (a bag
 * that came back empty and was then saved would be every wished thing he had put away, gone).
 * A stack that will not decode — a mod that supplied it is gone — is KEPT as its raw JSON and
 * written back unchanged, so it survives until the mod does; it is just not shown.
 */
public final class WishBagStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("mfam-wish-bag");

    public static final String STORE = "wish-bag";
    public static final int SCHEMA = 1;
    /** The folder on his shelf. */
    public static final String DIR = "wish-bag";

    private final Path file;
    private final String world;
    private final String owner;
    private final WishStoreFile.Guard guard = new WishStoreFile.Guard();
    /** Entries that would not decode, kept verbatim and written back. */
    private final List<JsonElement> undecodable = new ArrayList<>();

    public WishBagStore(Path playersDir, String owner, String world) {
        this.owner = owner;
        this.world = world;
        this.file = playersDir.resolve(owner).resolve(DIR).resolve(world + ".json");
    }

    public Path file() {
        return file;
    }

    /** True while the file may be written (it was read, or was absent). */
    public boolean usable() {
        return guard.mayWrite();
    }

    public String refusalReason() {
        return guard.refusalReason();
    }

    /** Read the bag, newest first; empty when there is none or the file was refused. */
    public List<ItemStack> load(HolderLookup.Provider registries) {
        List<ItemStack> out = new ArrayList<>();
        undecodable.clear();
        guard.reset();
        var read = guard.observe(WishStoreFile.readFile(file, STORE, SCHEMA));
        if (read instanceof WishStoreFile.Read.Refused r) {
            LOGGER.error("MODDY-ALERT could not read {}'s Wish Bag at {}: {}. It stays closed and "
                    + "nothing will be saved over it until a grown-up looks.", owner, file, r.reason());
            return out;
        }
        JsonObject root = WishStoreFile.rootOf(read);
        JsonElement arr = root == null ? null : root.get("items");
        if (arr == null || !arr.isJsonArray()) {
            return out;
        }
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        for (JsonElement el : arr.getAsJsonArray()) {
            JsonElement stack = el.isJsonObject() ? el.getAsJsonObject().get("stack") : null;
            ItemStack decoded = stack == null ? ItemStack.EMPTY
                    : ItemStack.CODEC.parse(ops, stack).result().orElse(ItemStack.EMPTY);
            if (decoded.isEmpty()) {
                undecodable.add(el);
                continue;
            }
            out.add(decoded);
        }
        if (!undecodable.isEmpty()) {
            LOGGER.warn("{}'s Wish Bag on {} holds {} thing(s) this server cannot rebuild; kept as they "
                    + "are", owner, world, undecodable.size());
        }
        return out;
    }

    /** Write the bag. False when the latch refused the write or the write failed. */
    public boolean save(List<ItemStack> entries, HolderLookup.Provider registries) {
        if (!guard.mayWrite()) {
            LOGGER.error("NOT saving {}'s Wish Bag — {} could not be read ({}). Left alone on purpose.",
                    owner, file, guard.refusalReason());
            return false;
        }
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        JsonArray items = new JsonArray();
        for (ItemStack s : entries) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            var encoded = ItemStack.CODEC.encodeStart(ops, s).result();
            if (encoded.isEmpty()) {
                LOGGER.warn("could not write a {} into {}'s Wish Bag; it is kept in memory", s, owner);
                continue;
            }
            JsonObject o = new JsonObject();
            o.add("stack", encoded.get());
            items.add(o);
        }
        undecodable.forEach(items::add);
        JsonObject payload = new JsonObject();
        payload.addProperty("world", world);
        payload.addProperty("ownerId", owner);
        payload.add("items", items);
        try {
            WishStoreFile.writeFile(file, STORE, SCHEMA, payload);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not save {}'s Wish Bag to {}: {}", owner, file, e.toString());
            return false;
        }
    }
}
