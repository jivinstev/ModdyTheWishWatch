package com.moddingfromamod.datapack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;

/**
 * The wish agent's Tier-1 factory: turn a CBScript source into a live, persisted datapack.
 *
 * <p>Split into two steps on purpose, because they belong on different threads:
 * <ol>
 *   <li>{@link #compile} shells out to the CBScript compiler (slow, off the game thread), and on
 *       success persists just the source + zip into the durable {@link DatapackStore}. A compile
 *       error comes back as data so the agent can fix it and retry — and every attempt, pass or
 *       fail, is written to {@link CbScriptCompileLog} for the outer improvement loop.</li>
 *   <li>{@link #activate} installs the compiled zip into the open world and reloads it — this
 *       touches server state and must run on the server thread.</li>
 * </ol>
 */
public final class DatapackForge {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("mfam-datapack-forge");


    private DatapackForge() {
    }

    /**
     * Compile {@code source} and, on success, persist the source + zip for {@code featureId}.
     * Off-thread safe. Compiles in a throwaway temp directory (which holds the copied {@code .cblib}
     * libraries and parser artifacts) so the durable store stays clean — just {@code <id>.cbscript}
     * and {@code <id>.zip}.
     */
    public static CompileResult compile(String featureId, String description, String source) {
        return compile(featureId, description, source, null);
    }

    /**
     * The same, for a ready-made that knows its skeleton: a precompiled one is built with no compiler.
     * See {@link CbScriptCompiler#compile(Path, String, String, String, Path, ReadyMadeSkeleton.Skeleton)}.
     */
    public static CompileResult compile(String featureId, String description, String source,
                                        ReadyMadeSkeleton.Skeleton skeleton) {
        long t = System.currentTimeMillis();
        // THIS BUILD'S DIALECT, for every datapack whatever wrote it -- a template, the agent,
        // make_item. A spelling for the other target is refused at PACK LOAD, silently, so it is
        // corrected here, the one place every datapack passes (wish.CommandDialect).
        source = com.moddingfromamod.wish.CommandDialect.correct(source).command();
        // The skeleton takes the same respelling (a machine's sign text is quoted JSON in its template), so it still
        // stands for the source; CbScriptCompiler checks that it fills back to exactly this source.
        skeleton = dialect(skeleton);
        CompileResult result;
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("cbscript_" + safe(featureId) + "_");
            Path zip = tmp.resolve(featureId + ".zip");
            result = CbScriptCompiler.compile(tmp, featureId, description, source, zip, skeleton);
            if (result.ok()) {
                Path dir = DatapackStore.featureDir(featureId);
                Files.createDirectories(dir);
                Files.copy(tmp.resolve(featureId + ".cbscript"), DatapackStore.sourceFor(featureId),
                        StandardCopyOption.REPLACE_EXISTING);
                // ownZipFor, NOT zipFor: since the curated library landed, the read path falls back to the curated library,
                // and compiling over it would write a child's wish into the server owner's checkout — the
                // library is a source of bases, never a write target.
                Files.copy(zip, DatapackStore.ownZipFor(featureId),
                        StandardCopyOption.REPLACE_EXISTING);
                // Provenance Contract: stamp the wish that produced/tuned this datapack into the Mod
                // Repo (the bits already live in wish-datapacks/<id>/). Best-effort; never fails the compile.
                com.moddingfromamod.provenance.ProvenancePublisher.record(
                        "datapack", featureId, "wish-datapacks/" + featureId);
                // whose wish made it, so a pause can tell HIM.
                var run = com.moddingfromamod.agent.AgentContext.currentRun();
                if (run != null) {
                    DatapackQuarantine.rememberOwner(featureId, run.playerId());
                }
                // a creature this datapack names is made immune to what it does, here,
                // where every datapack passes, so no tool and no agent has to remember it.
                try {
                    com.moddingfromamod.species.SelfHarmGuard.onCompiled(featureId, source);
                } catch (RuntimeException e) {
                    LOGGER.warn("self-harm guard failed for '{}' -- the datapack compiled, the "
                            + "creature may not be immune to it", featureId, e);
                }
            }
        } catch (IOException e) {
            result = CompileResult.failure(featureId, "Could not stage the compile: " + e, List.of());
        } finally {
            deleteQuietly(tmp);
        }
        // Raw signal for the outer loop: the exact draft the agent wrote + what happened to it.
        CbScriptCompileLog.record(t, featureId, result.ok(),
                result.ok() ? null : result.failureSummary(), source);
        return result;
    }

    /**
     * A skeleton respelt the way {@link #compile} respells every source ({@code CommandDialect}): the key its
     * precompiled pack is stored under. A tool asking "is this precompiled?" asks it of this.
     */
    public static ReadyMadeSkeleton.Skeleton dialect(ReadyMadeSkeleton.Skeleton skeleton) {
        return skeleton == null ? null : new ReadyMadeSkeleton.Skeleton(
                com.moddingfromamod.wish.CommandDialect.correct(skeleton.source()).command(),
                skeleton.slots(), skeleton.blocks(), skeleton.lines());
    }

    /**
     * Install the already-compiled datapack for {@code featureId} into the open world and reload.
     * Server thread only.
     *
     * @return the enabled pack id (e.g. {@code file/ice_bomb.zip})
     */
    public static String activate(MinecraftServer server, String featureId) throws IOException {
        return DatapackReloader.installAndReload(server, featureId, DatapackStore.zipFor(featureId));
    }

    private static String safe(String id) {
        return id == null ? "x" : id.replaceAll("[^a-z0-9_]", "_");
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
