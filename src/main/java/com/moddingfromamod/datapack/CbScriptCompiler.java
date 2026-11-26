package com.moddingfromamod.datapack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.moddingfromamod.ModdingFromAModMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compiles a CBScript source string into a datapack with the bundled
 * <a href="https://github.com/jivinstev/moddy-cbscript">CBScript</a> compiler, in-process.
 *
 * <p>The compiler is moddy-cbscript's Java port ({@code com.moddingfromamod.cbscript}, vendored in
 * {@code third_party/moddy-cbscript} by {@code tools/refresh-cbscript-bundle.sh}), which that repository's parity corpus
 * holds byte for byte to the Python compiler this class used to run as a {@code python3} process. So no player needs
 * Python: nothing here starts a process. Minecraft-free on purpose, so the logic is unit-testable without a running
 * game.
 *
 * <p><b>Two normalisations matter</b> and are done here so an AI author never has to think about them:
 * <ul>
 *   <li>The <b>namespace</b> of the datapack (and thus every {@code namespace:function} id) is the
 *       source <i>filename</i> in CBScript, so the compile is named {@code <featureId>.cbscript}.</li>
 *   <li>CBScript's grammar requires a {@code dir "..."} header, which would otherwise force a
 *       machine-specific path into agent-written source. We inject {@code dir "."} + a {@code desc}
 *       automatically (and defensively strip any the agent wrote).</li>
 * </ul>
 */
public final class CbScriptCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** A valid datapack namespace / feature id: lowercase letter then lowercase/digits/underscore. */
    public static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9_]{0,47}");

    /**
     * Is this a datapack id the mod can use? {@link #VALID_ID}, and not a name Windows reserves for a device: the id names
     * a folder and its files ({@code wish-datapacks/<id>/<id>.zip}), and {@code con} or {@code nul} would be a device on
     * a Windows player's disk ({@code store/SafeFileName}).
     */
    public static boolean validId(String featureId) {
        return featureId != null && VALID_ID.matcher(featureId).matches()
                && !com.moddingfromamod.store.SafeFileName.isWindowsReserved(featureId);
    }

    /** What a refused id is told, the same from every entry point. */
    public static String invalidIdMessage(String featureId) {
        return "Invalid feature id '" + featureId + "'. Use lowercase letters, digits and underscores, starting with a "
                + "letter (e.g. 'ice_bomb'), and not a name Windows keeps for a device (con, nul, aux, prn, com1, lpt1...).";
    }

    /** A compile that runs longer is stopped and refused (the compiler checks; a runaway program cannot hang a game). */
    private static final Duration COMPILE_TIMEOUT = Duration.ofSeconds(30);

    private CbScriptCompiler() {
    }

    // ── Path resolution ──────────────────────────────────────────────────────

    /**
     * Test-only: {@code -Dmoddingfromamod.cbscript.compiler=off} makes the compiler unavailable, so a Gate B arm can prove
     * every Builder and Apprentice power is made from the precompiled store alone (ReadyMadePrecompileGameTest). Nothing
     * in production sets it.
     */
    public static final String OFF_PROPERTY = ModdingFromAModMod.MODID + ".cbscript.compiler";

    /** The compiler is part of this jar, so it is always available (unless a test turned it off). */
    public static boolean available() {
        return !"off".equals(System.getProperty(OFF_PROPERTY));
    }

    /** Can a Builder or Apprentice power be built here? By the compiler, or from the precompiled store. */
    public static boolean canBuildReadyMades() {
        return available() || PrecompiledPacks.size() > 0;
    }

    // ── Compilation ──────────────────────────────────────────────────────────

    /**
     * Compile {@code source} into a datapack zip at {@code outZip}.
     *
     * @param workDir a scratch/persist directory the temp source + copied .cblib libraries live in
     * @param featureId the datapack namespace (also the temp filename); must pass {@link #validId}
     * @param description the datapack description (for pack.mcmeta)
     * @param source the CBScript body (no {@code dir}/{@code desc} header needed)
     * @param outZip where to write the compiled datapack zip
     */
    public static CompileResult compile(Path workDir, String featureId, String description,
                                        String source, Path outZip) {
        return compile(workDir, featureId, description, source, outZip, null);
    }

    /**
     * The same, for a ready-made that knows its {@link ReadyMadeSkeleton.Skeleton}: when the skeleton is
     * precompiled ({@link PrecompiledPacks}) the pack is built from it directly, without compiling, and
     * passes every check below exactly as a compiled one does (the safety sweep, the noise lint and the link strip
     * read the filled source first; the reach and command checks read the zip after). A skeleton that is not
     * precompiled, or whose filled form is not the source being compiled, falls to the compiler.
     */
    public static CompileResult compile(Path workDir, String featureId, String description,
                                        String source, Path outZip, ReadyMadeSkeleton.Skeleton skeleton) {
        if (!validId(featureId)) {
            return CompileResult.failure(featureId, invalidIdMessage(featureId), List.of());
        }
        // CHILD SAFETY, L0 over the words this datapack will say at RUNTIME (plan §12 decision 10).
        //
        // Here rather than in author_datapack because EVERY compile goes through this method -- the
        // wish path via DatapackForge, the item catalogue, the effect templates, the spikes and the
        // GameTests -- so there is one hook and no second path. And BEFORE the compile: a
        // refusal then costs no compile, writes no zip, and leaves nothing for DatapackForge to
        // un-persist. SafetyDatapackSweep's own javadoc has the four reasons this reads the SOURCE
        // rather than the compiled output.
        //
        // ⚠ And before available(), which is a decision rather than an accident. Safety is a
        // question about the CONTENT and needs no compiler; availability is a question about the
        // setup. Asking the cheap deterministic one first means the wiring is provable in Gate A
        // with no compile at all -- "a rule tested only where it is
        // DEFINED is not tested where it RUNS" (docs/MACHINE_DEBUG_LOOP.md), and this hook's whole
        // value is that it is on the one path every compile takes. The id check stays above it
        // because a malformed featureId is not a safety question and its own message is clearer.
        com.moddingfromamod.safety.SafetyDatapackSweep.Decision safety =
                com.moddingfromamod.safety.SafetyDatapackSweep.sweep(featureId, source);
        if (safety.refuses()) {
            // A compile error is the shape the agent already knows how to act on -- the whole
            // premise of CompileResult -- so a refusal arrives as one rather than as a new
            // failure mode it has never seen.
            return CompileResult.failure(featureId, safety.message(), List.of());
        }
        // A SOUND ON EVERY TICK, FOREVER: refused here for the same reasons as the sweep
        // above — one hook every compile passes, before the compile, pure. Narrow on purpose
        // (NoiseLint's javadoc): particles and anything behind a timer or a condition pass, and a
        // counter that is true on every tick anyway is NoiseMeter's, at runtime.
        String noise = NoiseLint.check(source);
        if (noise != null) {
            return CompileResult.failure(featureId, noise, List.of());
        }
        // NO CLICKABLE LINKS: a chat/sign/book link runs as the child who clicks it, and he is
        // permission 0, so every link the agent wrote was dead on Java and a failing pop-up on Bedrock.
        // The click goes and the words stay; on the one path every compile takes, like the sweeps above.
        ChatLinkStrip.Result links = ChatLinkStrip.strip(source);
        if (links.removed() > 0) {
            LOGGER.info("cbscript {}: {} clickable link(s) taken out, their words kept (#1950)",
                    featureId, links.removed());
            source = links.source();
        }
        CompileResult pre = precompiled(workDir, featureId, description, source, outZip, skeleton);
        if (pre != null) {
            return checked(featureId, outZip, pre);
        }
        if (!available()) {
            return CompileResult.failure(featureId, skeleton == null ? "the CBScript compiler is turned off here (a test)"
                    : "this ready-made is not one of the precompiled ones, and the CBScript compiler is turned off here (a test)",
                    List.of());
        }
        try {
            Files.createDirectories(workDir);
            String full = withHeader(description, source);
            // The source is still written where it always was: a person looking into the work directory (or a test)
            // finds what was compiled.
            Files.writeString(workDir.resolve(featureId + ".cbscript"), full, StandardCharsets.UTF_8);
            // WHICH MINECRAFT: the compiler's version-specific spellings (attribute ids, components, FallDistance) and
            // pack format follow this, as CBSCRIPT_MC told the Python compiler.
            com.moddingfromamod.cbscript.CompileResult r = com.moddingfromamod.cbscript.CbScript.compile(
                    featureId + ".cbscript", full, com.moddingfromamod.compat.Target.MINECRAFT,
                    com.moddingfromamod.cbscript.CbScript.bundledLibraries(), COMPILE_TIMEOUT);
            List<String> log = new ArrayList<>();
            for (String line : r.log()) {
                log.add(annotateLine(line, full));
            }
            CompileResult result;
            if (r.ok()) {
                if (outZip.getParent() != null) {
                    Files.createDirectories(outZip.getParent());
                }
                Files.write(outZip, r.toZip());
                result = CompileResult.ok(r.namespace(), outZip, log);
            } else {
                result = CompileResult.failure(featureId, annotateLine(r.error(), full), log);
            }
            verify(featureId, description, outZip, result, skeleton);
            return checked(featureId, outZip, result);
        } catch (IOException e) {
            LOGGER.warn("CBScript compile of '{}' failed to run: {}", featureId, e.toString());
            return CompileResult.failure(featureId, "Could not write the compiled pack: " + e, List.of());
        }
    }

    /** The checks every compiled pack passes, whichever way it was built: read off the compiled functions. */
    private static CompileResult checked(String featureId, Path outZip, CompileResult result) {
        if (!result.ok()) {
            return result;
        }
        try {
            // ANOTHER PLAYER IS A PLACE TO GO, NEVER A SUBJECT. Read off the COMPILED
            // functions, the code that will really run, as the presser: a datapack is where the
            // agent went when run_command refused it, so the rule is here too or it is nowhere.
            // Every compile passes this method, so one hook covers author_datapack, make_item's
            // abilities, apply_effect's templates and the GameTests alike.
            String reach = DatapackReach.check(outZip,
                    com.moddingfromamod.agent.PlayerReach.Who.presser(
                            com.moddingfromamod.world.PlayerVisits.knownNames()),
                    com.moddingfromamod.world.PlayerVisits.mode(),
                    com.moddingfromamod.world.WorldRulesService.current().workshop());
            if (reach != null) {
                com.moddingfromamod.agent.ActionLog.reach("author_datapack", "refused",
                        "datapack_reach", featureId, "", 0);
                Files.deleteIfExists(outZip);
                return CompileResult.failure(featureId, reach, result.log());
            }
            // NOR A COMMAND THE TABLE DENIES (step 1): CommandGuard's DENY rows hold in a
            // datapack too, read off the same compiled functions. Only DENY — the measurement of
            // every wished pack is in DatapackCommands.
            String denied = DatapackCommands.refusal(outZip);
            if (denied != null) {
                LOGGER.info("CBScript compile of '{}' refused: {}", featureId, denied);
                Files.deleteIfExists(outZip);
                return CompileResult.failure(featureId, denied, result.log());
            }
        } catch (IOException e) {
            return CompileResult.failure(featureId, "Could not check the compiled pack: " + e, result.log());
        }
        return result;
    }

    /**
     * Build the pack from its precompiled skeleton, or null when it is not one. In {@code verify} mode
     * nothing is built here: the compiler runs and {@link #verify} compares.
     */
    private static CompileResult precompiled(Path workDir, String featureId, String description, String source,
                                             Path outZip, ReadyMadeSkeleton.Skeleton skeleton) {
        if (skeleton == null || !PrecompiledPacks.enabled()) {
            return null;
        }
        PrecompiledPacks.record(skeleton);
        if ("verify".equals(PrecompiledPacks.mode()) && available()) {
            return null;
        }
        if (!skeleton.fill().equals(source)) {
            // Something between the render and here changed the source (a respelling, a link taken out):
            // the skeleton no longer stands for it, so the compiler must.
            LOGGER.info("cbscript {}: the source is not its skeleton's, so it is compiled", featureId);
            return null;
        }
        String desc = headerDescription(description);
        Map<String, String> files = PrecompiledPacks.build(featureId, desc, skeleton);
        if (files == null) {
            return null;
        }
        try {
            Files.createDirectories(workDir);
            Files.writeString(workDir.resolve(featureId + ".cbscript"), withHeader(description, source),
                    StandardCharsets.UTF_8);
            PrecompiledPacks.writeZip(files, outZip);
        } catch (IOException e) {
            return CompileResult.failure(featureId, "Could not write the precompiled pack: " + e, List.of());
        }
        return CompileResult.ok(featureId, outZip, List.of());
    }

    /** {@code -Ptestprops=precompiled=verify}: the compiled pack and the precompiled one must be the same files. */
    private static void verify(String featureId, String description, Path outZip, CompileResult result,
                               ReadyMadeSkeleton.Skeleton skeleton) {
        if (skeleton == null || !result.ok() || !"verify".equals(PrecompiledPacks.mode())) {
            return;
        }
        Map<String, String> files = PrecompiledPacks.build(featureId, headerDescription(description), skeleton);
        if (files == null) {
            LOGGER.warn("precompiled verify: {} is not precompiled", featureId);
            PrecompiledPacks.mismatch(featureId + ": not precompiled");
            return;
        }
        try {
            Map<String, String> compiled = PrecompiledPacks.readZip(outZip);
            if (compiled.equals(files)) {
                LOGGER.info("precompiled verify: {} is the same pack both ways", featureId);
            } else {
                String diff = PrecompiledPacks.firstDifference(compiled, files);
                LOGGER.warn("precompiled verify: {} DIFFERS from the compiler's pack: {}", featureId, diff);
                PrecompiledPacks.mismatch(featureId + ": " + diff);
            }
        } catch (IOException e) {
            LOGGER.warn("precompiled verify: could not read {}: {}", outZip, e.toString());
        }
    }

    /** The description exactly as {@link #withHeader} writes it into the {@code desc} line. */
    static String headerDescription(String description) {
        return (description == null || description.isBlank()) ? "A wish datapack"
                : description.replace("\\", " ").replace("\"", "'").replace("\n", " ").strip();
    }

    private static final java.util.regex.Pattern LINE_REF =
            java.util.regex.Pattern.compile("line (\\d+)");

    /**
     * The compiler reports errors against the compiled source, which includes the injected
     * {@code dir}/{@code desc} header — so a raw "line N" would point a few lines off from what the
     * author actually wrote. Rather than fight the offset, quote the OFFENDING LINE's text right in
     * the message: unambiguous no matter the offset, and it points the author straight at the fix.
     */
    static String annotateLine(String message, String fullSource) {
        if (message == null || fullSource == null) {
            return message;
        }
        String[] lines = fullSource.split("\n", -1);
        java.util.regex.Matcher m = LINE_REF.matcher(message);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String annot = m.group();
            if (n >= 1 && n <= lines.length) {
                String text = lines[n - 1].strip();
                if (!text.isEmpty()) {
                    annot = m.group() + " (here: `" + text + "`)";
                }
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(annot));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Prepend the mandatory {@code dir}/{@code desc} header, stripping any the agent wrote (a second
     * {@code dir} line is a parse error, and machine paths must never come from the model).
     */
    static String withHeader(String description, String source) {
        String desc = headerDescription(description);
        StringBuilder body = new StringBuilder();
        for (String line : (source == null ? "" : source).split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("dir ") || t.startsWith("dir\"") || t.equals("dir")
                    || t.startsWith("desc ") || t.startsWith("desc\"") || t.equals("desc")) {
                continue;  // drop a header the agent shouldn't have written
            }
            body.append(line).append('\n');
        }
        return "dir \".\"\ndesc \"" + desc + "\"\n\n" + body;
    }
}
