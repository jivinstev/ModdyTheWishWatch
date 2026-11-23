package com.moddingfromamod.mob;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps {@link EntityHandles} on disk, so <b>"him" still means the same animal tomorrow morning</b>.
 *
 * <p>Handles were in memory only, justified on the grounds that the agent's context resets per run
 * as well. That is true of the agent and false of the child: he goes to lunch, the game is
 * closed, and in the evening he says <i>"make him even bigger"</i> about the same cow. Without this
 * the third wish of the day is back to a distance sort — and it fails silently, by growing a
 * different animal and saying it worked.
 *
 * <p><b>Why a JSON file rather than {@code SavedData}.</b> Every other thing a wish remembers —
 * buildings, species, items, machines, trains — is a file under {@code <world>/wishes/}, loaded on
 * server start and written atomically on change. Introducing a second persistence mechanism for one
 * table would mean two shapes to reason about at world load and two places to look when something
 * did not come back. {@link com.moddingfromamod.builder.BuildRegistry} is the model this copies,
 * down to the temp-file-then-atomic-move.
 *
 * <p>Split out from {@code EntityHandles} rather than added to it, because that class is
 * deliberately Minecraft-free: naming, collisions, eviction and the referent rule are all unit
 * tests, and they stay that way only if the file I/O lives somewhere else.
 */
public final class MobHandleStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /**
     * Stamped on write and <b>tolerated on read whatever it says</b>.
     *
     * <p>The point of the field is not to gate loading — refusing to read a future version would
     * turn "you opened your world in an older build" into "your animals have no names", which is
     * worse than reading what we can. It is so a file found on disk can be identified — and, since
     * the store latches, so an older build never WRITES over a newer file ({@code WishStoreFile.writeBlocker}),
     * which would drop every field it did not understand.
     */
    public static final int VERSION = 1;

    private MobHandleStore() {
    }

    static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("wishes").resolve("mobs.json");
    }

    /**
     * Read this world's handles and start writing them back on every change.
     *
     * <p>A damaged file must never break a wish, let alone a world load, so everything below is
     * best-effort: {@link EntityHandles#loadJson} skips records it cannot read and this catches
     * anything left.
     *
     * @return how many handles came back
     */
    public static int load(MinecraftServer server) {
        EntityHandles handles = EntityHandles.global();
        int restored = 0;
        Path p = file(server);
        if (Files.isRegularFile(p)) {
            try {
                JsonElement root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
                if (root != null && root.isJsonObject()) {
                    restored = handles.loadJson(root.getAsJsonObject());
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("could not read saved mob handles at {}: {}", p, e.toString());
            }
        }
        // AFTER loading, or the load itself would write the empty table back over the file.
        handles.onChange(() -> save(server));
        if (restored > 0) {
            LOGGER.info("loaded {} saved mob handle(s)", restored);
        }
        return restored;
    }

    /** Write the table out. Called for you whenever it changes; safe to call by hand in a test. */
    public static void save(MinecraftServer server) {
        if (server == null) {
            return;
        }
        Path p = file(server);
        String blocked = com.moddingfromamod.store.WishStoreFile.writeBlocker(p, "version", VERSION);
        if (blocked != null) {
            // A newer or damaged file is left alone — see WishStoreFile.writeBlocker.
            LOGGER.error("NOT saving mob handles — {}. The file has been left alone on purpose.",
                    blocked);
            return;
        }
        JsonObject root = EntityHandles.global().toJson();
        root.addProperty("version", VERSION);
        Path tmp = p.resolveSibling("mobs.json.tmp-" + System.nanoTime());
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("could not save mob handles: {}", e.toString());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
