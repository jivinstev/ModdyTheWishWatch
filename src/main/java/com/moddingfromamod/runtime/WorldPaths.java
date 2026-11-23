package com.moddingfromamod.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the open world keeps the things wishes make.
 *
 * <p><b>The split brain this exists to end.</b> A wish creature was stored in two places with two
 * different lifetimes: its <i>species</i> (name, scale, stats) went into {@code <world>/wishes/} and
 * was per-world, while its <i>model</i> (geometry, texture) went into {@code ~/.moddingfromamod/models/}
 * and was global. Datapacks were global too. So a creature was half-remembered in a new world, and —
 * far worse — re-sculpting an id in one world silently rewrote the model that another world's
 * creature was still pointing at.
 *
 * <p>That really happened. Spikor was sculpted in {@code modwatch-daddus} at 56 units tall and again
 * in {@code New World (16)} at 52; the second sculpt overwrote the shared model, leaving the first
 * world's Spikor rendering with a body it never had, at a scale meant for a different one. Worlds
 * could not be independent, which also meant they could not be a clean test environment.
 *
 * <p>So everything a wish produces now lives under the world that produced it, and nothing is
 * shared. Bringing a creature ACROSS worlds is a real feature worth having, but it has to be an
 * explicit, thought-through import rather than an accident of where a file happened to sit.
 *
 * <p>Photos ({@code ~/.moddingfromamod/photos}) and the Mod Repo publish target are deliberately
 * NOT world-scoped: a photo is a screenshot for the agent to look at, and publishing is archival.
 * Neither is world content.
 */
public final class WorldPaths {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** The open world's root, or null at the title screen. */
    private static volatile Path worldRoot;

    private WorldPaths() {
    }

    /** Called on server start (integrated or dedicated) and cleared on stop. */
    public static void open(MinecraftServer server) {
        openAt(server == null ? null : server.getWorldPath(LevelResource.ROOT));
    }

    /**
     * The same, given the path directly. A seam so the isolation rules can be unit-tested without
     * booting two servers — which is the only practical way to assert that two worlds do NOT share
     * a directory.
     */
    public static void openAt(Path root) {
        worldRoot = root;
        // A new id per open, INCLUDING re-opening the same world. See sessionId().
        sessionId = root == null ? null
                : java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    public static void close() {
        worldRoot = null;
        sessionId = null;
    }

    /** Set on every {@link #openAt}, so it identifies one sitting rather than one world. */
    private static volatile String sessionId;

    /**
     * Which world this is, by folder name — {@code "daddus moddy3"}. Null at the title screen.
     *
     * <p>Recorded on every logged action so that <b>lineage is a fact rather than an inference</b>.
     * Without it, the only way to ask "were these two sculpts of Spikor a revision, or somebody
     * starting over?" is to guess from timestamps — and that guess was wrong. Seven Spikors were
     * filed as seven successive revisions that "got worse"; they were four independent
     * creations in four worlds, flat in quality within every world and changing only at the
     * boundaries. Nothing in the data could have contradicted that reading, because the one field
     * that settles it was never written down.
     */
    public static String worldName() {
        Path root = worldRoot;
        if (root == null) {
            return null;
        }
        // NORMALISE FIRST, and this is not tidiness — without it every record in the action log
        // said world=".".
        //
        // `open(server)` asks for `server.getWorldPath(LevelResource.ROOT)`, and vanilla's ROOT
        // resource is literally the string ".". So the path handed in ends in a "." component and
        // `getFileName()` faithfully returns that component: the field that exists to make lineage
        // a fact was a constant, identical for every world, in every record ever written. Measured
        // on one afternoon's logs: 14 distinct sittings, 14 × world=".".
        //
        // toAbsolutePath() before normalize() covers the dedicated server, where the world path is
        // just "." because the server runs INSIDE the world directory — relative-normalising that
        // yields an empty path and no name at all.
        //
        // The stored root is deliberately left alone: wishesDir() and friends resolve against it
        // and a trailing "." resolves correctly, so only the NAME was ever wrong.
        Path name = root.toAbsolutePath().normalize().getFileName();
        return name == null ? root.toString() : name.toString();
    }

    /**
     * One id per world-open — a <b>sitting</b>, not a world.
     *
     * <p>Deliberately regenerated when the same world is re-opened, because the question an
     * analysis actually asks is "did these happen in one go?". Two sculpts three hours apart in one
     * world are no more a revision series than two sculpts in different worlds, and only this tells
     * them apart. Null at the title screen.
     */
    public static String sessionId() {
        return sessionId;
    }

    /** The open world's {@code wishes/} directory, or null when no world is open. */
    public static Path wishesDir() {
        Path root = worldRoot;
        return root == null ? null : root.resolve("wishes");
    }

    /** Sculpted creature models for the open world, or null if no world is open. */
    public static Path modelDir() {
        Path wishes = wishesDir();
        return wishes == null ? null : wishes.resolve("models");
    }

    /** Compiled CBScript features for the open world, or null if no world is open. */
    public static Path datapackDir() {
        Path wishes = wishesDir();
        return wishes == null ? null : wishes.resolve("wish-datapacks");
    }

    // ---- one-time migration off the old global stores ----

    /**
     * Move a world that predates per-world storage onto it, once.
     *
     * <p>Without this, everything a child already made would silently vanish the next time he
     * opened his world — his creatures would still be listed (species are already per-world) but
     * would have no model to render. So the first time a world is opened after this change, whatever
     * is in the old global directories is copied in.
     *
     * <p>Deliberately a COPY, and deliberately only when the world has nothing of its own yet: the
     * legacy folders are read once and never written again, so the split cannot creep back. Two
     * worlds migrating from the same global store each get their own independent copy, which is the
     * whole point — from here they can diverge without touching each other.
     */
    public static void migrateLegacyGlobalData() {
        // ONLY for a world that already had wishes in it. A brand-new world must start genuinely
        // empty — inheriting every creature ever made on this machine is precisely the shared-state
        // behaviour being removed, and it would make a fresh world useless as a clean test.
        if (!hasExistingWishData()) {
            return;
        }
        migrateLegacyFrom(RuntimeConfig.home().resolve("models"), modelDir(), "creature model");
        // Datapacks lived under the configured Mod Repo rather than the runtime home.
        Path repo = RuntimeConfig.persistDir();
        if (repo != null) {
            migrateLegacyFrom(repo.resolve("wish-datapacks"), datapackDir(), "wish datapack");
        }
    }

    /**
     * Has this world ever granted a wish? Judged by the two files written the moment one is —
     * the species list and the wish journal — both of which were already per-world, so they are
     * the honest record of "this world has a history worth preserving".
     */
    static boolean hasExistingWishData() {
        Path wishes = wishesDir();
        return wishes != null
                && (Files.isRegularFile(wishes.resolve("species.json"))
                    || Files.isRegularFile(wishes.resolve("journal.jsonl")));
    }

    /** One legacy folder into one world folder. Package-visible so the rules are unit-tested. */
    public static void migrateLegacyFrom(Path legacy, Path target, String what) {
        if (target == null || legacy == null || !Files.isDirectory(legacy)) {
            return;
        }
        // Anything already here means this world has migrated (or was born after the change).
        if (Files.isDirectory(target)) {
            return;
        }
        int copied = 0;
        try (Stream<Path> entries = Files.list(legacy)) {
            Files.createDirectories(target);
            for (Path entry : entries.toList()) {
                if (!Files.isDirectory(entry)) {
                    continue;   // stray READMEs and loose files aren't creatures or features
                }
                copyTree(entry, target.resolve(entry.getFileName().toString()));
                copied++;
            }
        } catch (IOException e) {
            LOGGER.warn("could not migrate {}s into the world: {}", what, e.toString());
            return;
        }
        if (copied > 0) {
            LOGGER.info("migrated {} {}(s) from the old shared folder into this world — they are "
                    + "this world's own copies now", copied, what);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path src : walk.toList()) {
                Path dest = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
