package com.moddingfromamod.datapack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The durable home for wish datapacks — their CBScript <b>source</b> and the compiled <b>zip</b> —
 * kept OUTSIDE the (deliberately clean) engine repo so a built feature survives across worlds and
 * sessions without re-running the Claude design loop.
 *
 * <p>By default this is {@code <configured mod repo>/wish-datapacks/<id>/}, where the repo is the
 * machine-local {@link RuntimeConfig#persistDir()} (property / env / {@code config.properties}). The
 * engine names no specific mod repo in its checked-in code — the "cool stuff" lives in whatever repo
 * is configured while modding-from-a-mod stays a clean, repo-agnostic engine. If no repo is
 * configured it falls back to {@code ~/.moddingfromamod/wish-datapacks} so the feature still works,
 * just not published to a repo.
 *
 * <p>Each feature is compiled directly into its own directory here, so the directory doubles as the
 * persist location and the recompile workspace (source + copied {@code .cblib} libraries + zip).
 */
public final class DatapackStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private DatapackStore() {
    }

    /** The root under which every feature directory lives. Created on demand. */
    public static Path baseDir() {
        String prop = System.getProperty(ModdingFromAModMod.MODID + ".datapacks.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("WISH_DATAPACKS_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        // The OPEN WORLD owns its features. They used to live in the configured Mod Repo, shared by
        // every world, which meant a feature built in one world silently followed you into another
        // — the same split brain that let a re-sculpted creature overwrite a different world's. See
        // WorldPaths. Publishing to the Mod Repo still happens; it is a copy, not the live store.
        Path world = com.moddingfromamod.runtime.WorldPaths.datapackDir();
        if (world != null) {
            return world;
        }
        // No world open (tests, or the title screen) -> a machine-local scratch home.
        return Path.of(System.getProperty("user.home", "."), "." + ModdingFromAModMod.MODID,
                "wish-datapacks");
    }

    /** The directory that holds feature {@code id}'s source, libs and zip. */
    public static Path featureDir(String id) {
        return baseDir().resolve(id);
    }

    public static Path zipFor(String id) {
        Path own = featureDir(id).resolve(id + ".zip");
        if (Files.isRegularFile(own)) {
            return own;
        }
        // THE CURATED LIBRARY, read-only: <libraryDir>/datapacks/<id>/<id>.zip. A datapack
        // is the one kind whose "install" is a copy into the world's own datapacks/ folder anyway
        // (installAllIntoWorld, DatapackReloader), so serving the zip from the library needs no new
        // machinery and writes nothing back into it.
        //
        // The world's copy wins when both exist, which is what makes a refinement of a library
        // feature behave: it recompiles into the world and from then on the world answers.
        Path library = com.moddingfromamod.library.WishLibrary.artifactFile(
                "datapacks", id, id + ".zip");
        return library != null ? library : own;
    }

    /** Where a datapack this world compiled is WRITTEN — never the library. */
    public static Path ownZipFor(String id) {
        return featureDir(id).resolve(id + ".zip");
    }

    public static Path sourceFor(String id) {
        return featureDir(id).resolve(id + ".cbscript");
    }

    /**
     * The bytes an install copies into a world for {@code id}: the stored zip, put into THIS
     * build's dialect ({@link DatapackDialect}). Every install path goes through here, and
     * so does {@code StarterDatapackDialectGameTest}, so the test parses what a world is given.
     */
    public static byte[] installBytes(String id) throws IOException {
        DatapackDialect.Retargeted r = DatapackDialect.forThisBuild(zipFor(id));
        if (r.changed()) {
            LOGGER.info("datapack '{}' put into this build's dialect on install: {}", id, r.notes());
        }
        return r.zip();
    }

    public static boolean exists(String id) {
        return Files.isRegularFile(zipFor(id));
    }

    /**
     * Every feature id currently servable — this world's compiled zips, plus the curated library's,
     * which {@link #installAllIntoWorld} then stages into the world exactly like the rest.
     */
    public static List<String> listIds() {
        List<String> ids = new ArrayList<>();
        Path base = baseDir();
        if (Files.isDirectory(base)) {
            try (Stream<Path> dirs = Files.list(base)) {
                for (Path d : dirs.filter(Files::isDirectory).sorted().toList()) {
                    String id = d.getFileName().toString();
                    if (Files.isRegularFile(d.resolve(id + ".zip"))) {
                        ids.add(id);
                    }
                }
            } catch (IOException ignored) {
                // an unreadable store means whatever the library has, never a crash
            }
        }
        for (String id : com.moddingfromamod.library.WishLibrary.keys("datapacks")) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Copy every persisted datapack into the open world and enable them, so the accumulated wish
     * features are present in whatever world the child opens — instant and offline. Runs one resource
     * reload for the batch. Server thread only.
     */
    public static int installAllIntoWorld(MinecraftServer server) {
        List<String> ids = listIds();
        int installed = 0;
        for (String id : ids) {
            try {
                Path dest = DatapackReloader.worldDatapackDir(server).resolve(id + ".zip");
                Files.createDirectories(dest.getParent());
                Files.write(dest, installBytes(id));
                installed++;
            } catch (IOException e) {
                LOGGER.warn("could not stage wish datapack '{}' into the world: {}", id, e.toString());
            }
        }
        if (installed > 0) {
            server.getPackRepository().reload();
            List<String> selected = new ArrayList<>(server.getPackRepository().getSelectedIds());
            // ...except what this world had switched off: an undone wish, or a pack the
            // server refused part of. This ran at every boot, so on a live server a switched-off
            // pack came back on at the next restart, and a refused tick function with it stopped
            // every datapack clock in the world.
            DatapackReloader.ensureSeeded(server);
            List<String> off = DatapackReloader.disabledIds();
            for (String id : ids) {
                String packId = DatapackReloader.packId(id);
                if (server.getPackRepository().getAvailableIds().contains(packId)
                        && !selected.contains(packId) && !off.contains(packId)) {
                    selected.add(packId);
                }
            }
            // ...and a pack the world SAVED as on but that is paused: the watchdog stopped the server
            // before level.dat could remember the disable.
            selected.removeAll(DatapackReloader.disabledIds());
            DatapackReloader.kickReload(server, selected);
            LOGGER.info("installed {} wish datapack(s) into the world", installed);
        }
        return installed;
    }

}
