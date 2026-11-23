package com.moddingfromamod.species;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.library.WishLibrary;
import com.moddingfromamod.marshal.ArtifactOwner;
import com.moddingfromamod.marshal.ForkPolicy;
import com.moddingfromamod.runtime.ModelRepository;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Copy-on-refine for creatures</b>.
 *
 * <p>The decision is {@link ForkPolicy} and is pure; this is everything that decision needs a world
 * for — the species record, the art on disk, and the creatures already standing in the world.
 *
 * <h2>The art is COPIED, and that is the part that is easy to get wrong</h2>
 *
 * <p>A fork could plausibly keep the base's {@code modelKey}: {@code ModelRepository.readable}
 * already falls back to the library for art this world does not have, so the forked creature would
 * draw correctly and nothing would be duplicated. That is right up until the child re-sculpts his
 * fork — at which point the sculpt writes {@code <world>/wishes/models/<base>/} and, because the
 * world's copy is preferred over the library's, <b>every other child's copy of the curated creature
 * silently becomes his</b>. Nothing errors and nothing looks wrong to him. So the geometry, the
 * skin and the sculpt source are copied at fork time and the fork owns its own directory.
 *
 * <p>The copy is read through {@link ModelRepository#readable}, which is the same resolution the
 * renderer uses — world first, library second. So forking a library creature reads the library's
 * files, and forking a brother's fork reads the world's, with one code path.
 *
 * <h2>What a fork does NOT take</h2>
 *
 * <p>The base is never touched: not its record, not its directory, not the creatures of its kind
 * standing in the world. Only the ONE creature the wish is acting on is re-pointed at the fork, and
 * only because the child is looking at it — his brother's copies of the same creature go on being
 * that creature.
 */
public final class CreatureFork {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** What a creature's directory holds. Anything absent is simply not copied. */
    private static final String[] ART = {
        "geometry.json", "texture.png", "sculpt.json", "species.json"
    };

    private CreatureFork() {
    }

    /**
     * Whether the curated library serves this creature — i.e. whether an edit would shadow it.
     *
     * <p>The ARTIFACT, not the recipe. A library {@code templates.json} entry called {@code spikor}
     * is a button; {@code species/spikor/} is the creature a refinement would write over, and only
     * the second one is at risk.
     */
    public static boolean inLibrary(String speciesId) {
        return WishLibrary.artifactDir("species", speciesId) != null;
    }

    /**
     * What should happen to a change aimed at this creature.
     *
     * @param askerId the child whose wish it is, or {@code ""}
     */
    public static ForkPolicy.Decision decide(Species target, String askerId) {
        if (target == null) {
            return ForkPolicy.Decision.EDIT_SHARED;
        }
        return ForkPolicy.decide(askerId, target.id(), target.ownerId(), inLibrary(target.id()));
    }

    /**
     * Make {@code edited} into this child's own creature, art and all.
     *
     * <p>{@code edited} is the ALREADY-CHANGED species — the caller has applied the tweak to the
     * base and hands the result here, so the fork is born with the change in it rather than being
     * created and then edited, which would leave a window in which a copy of somebody else's
     * creature existed under the child's name.
     *
     * @return the stored fork, or null when there is nothing to fork to (no owner, no server)
     */
    public static Species fork(MinecraftServer server, Species edited, String askerId) {
        if (server == null || edited == null) {
            return null;
        }
        String key = ArtifactOwner.forkKey(edited.id(), askerId);
        if (key.isEmpty()) {
            return null;
        }
        copyArt(edited.modelKey(), key);
        Species forked = edited.forkedAs(key, key, askerId);
        Species stored = SpeciesRegistry.put(server, forked);
        if (stored != null) {
            LOGGER.info("forked creature '{}' as '{}' for the child who asked — the original is"
                    + " untouched and still serves everyone", edited.id(), key);
        }
        return stored;
    }

    /**
     * Copy a creature's art into a new directory.
     *
     * <p>Best effort per file, and loudly so. A fork with no geometry is a creature that renders as
     * nothing, which is the failure a child would report as "it disappeared"; a warning in the log
     * is the only thing that connects it to a copy that could not be made.
     */
    private static void copyArt(String fromKey, String toKey) {
        if (fromKey == null || fromKey.isEmpty() || !ModelRepository.isValidKey(toKey)) {
            return;   // a vanilla-bodied creature has no art to copy, and that is normal
        }
        Path dest = ModelRepository.dir(toKey);
        for (String name : ART) {
            Path src = ModelRepository.readable(fromKey, name);
            if (src == null) {
                continue;
            }
            try {
                Files.createDirectories(dest);
                Files.copy(src, dest.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("could not copy '{}' from creature '{}' to its fork '{}': {}",
                        name, fromKey, toKey, e.toString());
            }
        }
    }
}
