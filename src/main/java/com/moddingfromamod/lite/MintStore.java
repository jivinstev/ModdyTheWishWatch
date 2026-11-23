package com.moddingfromamod.lite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>{@link MintMarks} on disk — one file per world, next to everything else a wish remembers.</b>
 *
 * <p><b>Why a JSON file rather than {@code SavedData}.</b> The same reason
 * {@code MobHandleStore}'s own note gives: every other thing a wish remembers — buildings, species,
 * items, machines, trains, handles, paintings — is a file under {@code <world>/wishes/}, loaded on
 * server start and written atomically on change. A second persistence mechanism for one small
 * record would mean two shapes to reason about at world load and two places to look when something
 * did not come back.
 *
 * <p>Everything here is best-effort in one direction only: a damaged or absent file costs the mark
 * and nothing else. A mark that could abort a world load would be strictly worse than no mark.
 */
public final class MintStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /**
     * The open world's marks.
     *
     * <p>Static, like every sibling store, because there is one open world per server process and
     * the alternative is threading a handle through four unrelated mint sites.
     */
    private static volatile MintMarks marks = new MintMarks();

    private MintStore() {
    }

    static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("wishes").resolve("minted.json");
    }

    /** What this world's things of one class were made by. UNKNOWN before a world is open. */
    public static MintMarks.Mode modeOf(String kind) {
        return marks.modeOf(kind);
    }

    /** The whole record, for a report or a gate. */
    public static MintMarks current() {
        return marks;
    }

    /**
     * Read this world's mark, and say what it found.
     *
     * <p>The log line is the feature. A world minted on the substituted path and opened without it
     * is the failure this whole issue is about, and the only thing that makes that visible at the
     * moment it matters is a sentence at world load naming what the world is.
     *
     * @return how many class marks came back
     */
    public static int load(MinecraftServer server) {
        MintMarks fresh = new MintMarks();
        int restored = 0;
        if (server != null) {
            Path p = file(server);
            if (Files.isRegularFile(p)) {
                try {
                    JsonElement root =
                            JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
                    restored = fresh.load(root);
                } catch (IOException | RuntimeException e) {
                    // A corrupt mark is not a corrupt world. Say so and carry on UNKNOWN, which is
                    // the honest answer for a world whose record cannot be read.
                    LOGGER.warn("could not read the mint mark at {} — treating this world as "
                            + "UNKNOWN: {}", p, e.toString());
                }
            }
        }
        marks = fresh;
        LOGGER.info("mint mark: {}", fresh.describe());
        return restored;
    }

    /**
     * Record that something of this class was just minted, on whichever path is live.
     *
     * <p>Called from the mint sites rather than from the flag itself, deliberately: a flag is read
     * constantly and says only what the SETTING is, while the question the mark answers is what the
     * world actually CONTAINS. A world opened once on the substituted path and never wished in has
     * no substituted things in it and must not claim to.
     *
     * <p>Writes only when the record moved (see {@link MintMarks#record}); minting is frequent and
     * the mark almost never changes.
     */
    public static void stamp(MinecraftServer server, String kind, boolean vanilla) {
        if (server == null) {
            return;
        }
        MintMarks m = marks;
        if (m.record(kind, vanilla)) {
            LOGGER.info("mint mark: {} is now minted by {}", kind, m.modeOf(kind));
            save(server);
        }
    }

    /** Write the mark out. Called for you when it changes; safe to call by hand in a test. */
    public static void save(MinecraftServer server) {
        if (server == null) {
            return;
        }
        Path p = file(server);
        String blocked = com.moddingfromamod.store.WishStoreFile.writeBlocker(p, "version",
                MintMarks.VERSION);
        if (blocked != null) {
            // A newer or damaged mark is left alone — see WishStoreFile.writeBlocker.
            LOGGER.error("NOT saving the mint mark — {}. The file has been left alone on purpose.",
                    blocked);
            return;
        }
        Path tmp = p.resolveSibling("minted.json.tmp-" + System.nanoTime());
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(tmp, marks.toJson().toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("could not save the mint mark: {}", e.toString());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** Test seam: start from nothing, as a freshly created world would. */
    public static void resetForTest() {
        marks = new MintMarks();
    }
}
