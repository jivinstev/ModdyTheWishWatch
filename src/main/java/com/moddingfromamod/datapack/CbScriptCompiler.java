package com.moddingfromamod.datapack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compiles a CBScript source string into a 1.21.1 datapack by shelling out to the forked
 * <a href="https://github.com/jivinstev/moddy-cbscript">CBScript</a> compiler ({@code compile_once.py}).
 *
 * <p>Minecraft-free on purpose, so the logic is unit-testable without a running game. It resolves
 * the Python interpreter and the compiler the same searching way {@link com.moddingfromamod.agent.ClaudeCli}
 * finds the Claude CLI (property → env → sensible defaults), because both are dev-machine tools whose
 * paths we should discover, not hardcode.
 *
 * <p><b>Two normalisations matter</b> and are done here so an AI author never has to think about them:
 * <ul>
 *   <li>The <b>namespace</b> of the datapack (and thus every {@code namespace:function} id) is the
 *       source <i>filename</i>, so we always write the temp source as {@code <featureId>.cbscript}.</li>
 *   <li>CBScript's grammar requires a {@code dir "..."} header, which would otherwise force a
 *       machine-specific path into agent-written source. We inject {@code dir "."} + a {@code desc}
 *       automatically (and defensively strip any the agent wrote), and {@code compile_once.py --out}
 *       decides where the zip actually lands.</li>
 * </ul>
 */
public final class CbScriptCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** A valid datapack namespace / feature id: lowercase letter then lowercase/digits/underscore. */
    public static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9_]{0,47}");

    private static final long COMPILE_TIMEOUT_MS = 30_000L;

    private CbScriptCompiler() {
    }

    // ── Path resolution ──────────────────────────────────────────────────────

    /**
     * The CBScript compiler directory, or null if none is usable. Resolution order:
     * <ol>
     *   <li>an explicit {@code .cbscript.dir} system property / {@code CBSCRIPT_DIR} env — for a dev
     *       iterating on the fork;</li>
     *   <li>the <b>deployed tool</b>: bundled in the jar and extracted to a writable, non-TCC app-data
     *       dir by {@link com.moddingfromamod.tools.ToolDeploy} — the normal, self-contained path;</li>
     *   <li>a {@code cbscript} checkout beside the project (dev-from-source fallback).</li>
     * </ol>
     *
     * <p>Every candidate is checked for <b>readability</b>, not just existence: a present-but-unreadable
     * checkout (e.g. a TCC-blocked {@code ~/Desktop} path) is skipped so it can never trigger a
     * {@code FileSystemException} mid-compile — it degrades to the next candidate, then to "unavailable".
     */
    public static Path cbscriptDir() {
        Path prop = usableCompilerDir(System.getProperty(ModdingFromAModMod.MODID + ".cbscript.dir"));
        if (prop != null) {
            return prop;
        }
        Path env = usableCompilerDir(System.getenv("CBSCRIPT_DIR"));
        if (env != null) {
            return env;
        }
        Path deployed = com.moddingfromamod.tools.ToolDeploy.ensureExtracted("cbscript");
        if (deployed != null && isReadableCompilerDir(deployed)) {
            return deployed;
        }
        for (Path candidate : defaultCbscriptDirs()) {
            if (isReadableCompilerDir(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A directory string that both exists as a directory AND holds a readable {@code compile_once.py}. */
    private static Path usableCompilerDir(String p) {
        if (p == null || p.isBlank()) {
            return null;
        }
        Path dir = Path.of(p);
        return isReadableCompilerDir(dir) ? dir : null;
    }

    /**
     * The compiler's entry point is present and actually <b>readable</b> (not just a name that
     * exists). Package-visible so Gate A can prove a denied/unreadable checkout is rejected here —
     * that rejection is what stops a TCC-blocked path from crashing mid-compile.
     */
    static boolean isReadableCompilerDir(Path dir) {
        Path entry = dir.resolve("compile_once.py");
        return Files.isRegularFile(entry) && Files.isReadable(entry);
    }

    private static List<Path> defaultCbscriptDirs() {
        List<Path> out = new ArrayList<>();
        // Beside the current working directory (…/Code/<project> → …/Code/cbscript) — dev from source
        // only. The old ~/Desktop/Code/cbscript default was removed: on macOS it is TCC-protected, so
        // the game process gets "Operation not permitted" reading it. The bundled+extracted tool is the
        // reliable path now; this is just a convenience for someone hacking on the fork in place.
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && cwd != null; i++) {
            out.add(cwd.resolve("cbscript"));
            out.add(cwd.resolveSibling("cbscript"));
            cwd = cwd.getParent();
        }
        return out;
    }

    /**
     * The Python interpreter to run the compiler with, or null if none is usable. Once the Magician setup has checked
     * ({@link PythonCheck}), the Python it proved, or none; before that, the first candidate
     * {@link #pythonCandidates} names, as it always was. An explicit property or {@code CBSCRIPT_PYTHON} always wins.
     */
    public static String python() {
        String prop = System.getProperty(ModdingFromAModMod.MODID + ".cbscript.python");
        // "off" is a machine with no Python: the Gate B arm that proves every Builder and Apprentice power is
        // built from its precompiled pack, as on a PC that never had Python. Nothing else is looked for.
        if ("off".equals(prop)) {
            return null;
        }
        if (isExec(prop)) {
            return prop;
        }
        String env = System.getenv("CBSCRIPT_PYTHON");
        if (isExec(env)) {
            return env;
        }
        PythonCheck.Verdict checked = PythonCheck.last();
        if (checked != null) {
            return checked.works() ? checked.path() : null;
        }
        List<String> found = pythonCandidates(System::getenv, PythonCheck.windows());
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Every Python on this computer the compiler could use, best first, by file lookup only (nothing is run): the
     * explicit property and {@code CBSCRIPT_PYTHON}, the compiler's own venv, {@code python3} then {@code python} on
     * the PATH, then the usual install homes (a GUI-launched Minecraft can inherit a minimal PATH that omits them).
     * On Windows the names carry {@code .exe}, the python.org installer's homes are searched (an install whose
     * "Add python.exe to PATH" was not ticked), and the {@code WindowsApps} folder comes last: its {@code python.exe}
     * is usually the Microsoft Store stub, and {@link PythonCheck} tells it from a real Store Python by running it.
     */
    public static List<String> pythonCandidates(java.util.function.Function<String, String> env, boolean windows) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String prop = System.getProperty(ModdingFromAModMod.MODID + ".cbscript.python");
        if (isExec(prop)) {
            out.add(prop);
        }
        String explicit = env.apply("CBSCRIPT_PYTHON");
        if (isExec(explicit)) {
            out.add(explicit);
        }
        Path dir = cbscriptDir();
        if (dir != null) {
            Path venv = windows ? dir.resolve(".venv").resolve("Scripts").resolve("python.exe")
                    : dir.resolve(".venv").resolve("bin").resolve("python");
            if (Files.isExecutable(venv)) {
                out.add(venv.toString());
            }
        }
        List<String> storeAliases = new ArrayList<>();
        String path = env.apply(windows && env.apply("PATH") == null ? "Path" : "PATH");
        for (String name : new String[] {"python3", "python"}) {
            for (String d : path == null ? new String[0] : path.split(windows ? ";" : File.pathSeparator)) {
                if (d.isBlank()) {
                    continue;
                }
                File f = new File(d.strip(), windows ? name + ".exe" : name);
                if (f.isFile() && f.canExecute()) {
                    if (windows && d.toLowerCase(java.util.Locale.ROOT).contains("windowsapps")) {
                        storeAliases.add(f.getAbsolutePath());
                    } else {
                        out.add(f.getAbsolutePath());
                    }
                }
            }
        }
        if (windows) {
            for (String root : new String[] {env.apply("LOCALAPPDATA") == null ? null
                    : env.apply("LOCALAPPDATA") + "\\Programs\\Python", env.apply("ProgramFiles"), "C:\\"}) {
                out.addAll(pythonHomes(root));
            }
        } else {
            for (String abs : new String[] {"/usr/bin/python3", "/opt/homebrew/bin/python3",
                    "/usr/local/bin/python3", "/usr/bin/python"}) {
                if (isExec(abs)) {
                    out.add(abs);
                }
            }
        }
        out.addAll(storeAliases);
        return List.copyOf(out);
    }

    /** {@code <root>\Python3NN\python.exe}, newest first (python.org's default install folders). */
    private static List<String> pythonHomes(String root) {
        List<String> out = new ArrayList<>();
        if (root == null || root.isBlank()) {
            return out;
        }
        File[] dirs = new File(root).listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).startsWith("python3"));
        if (dirs == null) {
            return out;
        }
        java.util.Arrays.sort(dirs, java.util.Comparator.comparing((File f) -> versionKey(f.getName())).reversed());
        for (File d : dirs) {
            File exe = new File(d, "python.exe");
            if (exe.isFile()) {
                out.add(exe.getAbsolutePath());
            }
        }
        return out;
    }

    /** "Python312" → 312, so 3.12 sorts above 3.9. */
    private static int versionKey(String folder) {
        String digits = folder.replaceAll("\\D", "");
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits.length() > 6 ? digits.substring(0, 6) : digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** True if the compiler and an interpreter are both available. */
    public static boolean available() {
        return cbscriptDir() != null && python() != null;
    }

    /**
     * Can a Builder or Apprentice power be built here? By the compiler, or from the precompiled store, which
     * needs no Python. The ability GameTests ask this, so a run with Python off still proves every power.
     */
    public static boolean canBuildReadyMades() {
        return available() || PrecompiledPacks.size() > 0;
    }

    /** A human-readable reason the compiler can't run, or null if it can. */
    public static String unavailableReason() {
        if (cbscriptDir() == null) {
            return "The CBScript compiler wasn't found. Set the CBSCRIPT_DIR env var (or the "
                    + ModdingFromAModMod.MODID + ".cbscript.dir system property) to the cbscript checkout.";
        }
        if (python() == null) {
            return PythonCheck.agentMessage(PythonCheck.last());
        }
        return null;
    }

    /**
     * {@link PythonCheck#agentMessage} when no working Python is known, else null: asked by a tool before it compiles a
     * power, so the agent hears the one clear reason instead of the compile error a missing interpreter would cause.
     * File lookups only; nothing runs.
     */
    public static String pythonRefusal() {
        if (cbscriptDir() == null || python() != null) {
            return null;
        }
        return PythonCheck.agentMessage(PythonCheck.last());
    }

    // ── Compilation ──────────────────────────────────────────────────────────

    /**
     * Compile {@code source} into a datapack zip at {@code outZip}.
     *
     * @param workDir a scratch/persist directory the temp source + copied .cblib libraries live in
     * @param featureId the datapack namespace (also the temp filename); must match {@link #VALID_ID}
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
     * precompiled ({@link PrecompiledPacks}) the pack is built from it in Java, with no compiler and no Python, and
     * passes every check below exactly as a compiled one does (the safety sweep, the noise lint and the link strip
     * read the filled source first; the reach and command checks read the zip after). A skeleton that is not
     * precompiled, or whose filled form is not the source being compiled, falls to the compiler.
     */
    public static CompileResult compile(Path workDir, String featureId, String description,
                                        String source, Path outZip, ReadyMadeSkeleton.Skeleton skeleton) {
        if (!VALID_ID.matcher(featureId == null ? "" : featureId).matches()) {
            return CompileResult.failure(featureId,
                    "Invalid feature id '" + featureId + "'. Use lowercase letters, digits and "
                    + "underscores, starting with a letter (e.g. 'ice_bomb').", List.of());
        }
        // CHILD SAFETY, L0 over the words this datapack will say at RUNTIME (plan §12 decision 10).
        //
        // Here rather than in author_datapack because EVERY compile goes through this method -- the
        // wish path via DatapackForge, the item catalogue, the effect templates, the spikes and the
        // GameTests -- so there is one hook and no second path. And BEFORE the subprocess: a
        // refusal then costs no compile, writes no zip, and leaves nothing for DatapackForge to
        // un-persist. SafetyDatapackSweep's own javadoc has the four reasons this reads the SOURCE
        // rather than the compiled output.
        //
        // ⚠ And before unavailableReason(), which is a decision rather than an accident. Safety is
        // a question about the CONTENT and needs no interpreter; availability is a question about
        // the MACHINE. Asking the cheap deterministic one first means the wiring is provable in
        // Gate A with no Python, no bundle and no subprocess -- "a rule tested only where it is
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
        // above — one hook every compile passes, before the subprocess, pure. Narrow on purpose
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
        String reason = unavailableReason();
        if (reason != null) {
            return CompileResult.failure(featureId, skeleton == null ? reason
                    : "this ready-made is not one of the precompiled ones, so it needs the CBScript compiler: "
                    + reason, List.of());
        }
        try {
            Files.createDirectories(workDir);
            copyLibsInto(workDir);
            String full = withHeader(description, source);
            Path scriptFile = workDir.resolve(featureId + ".cbscript");
            Files.writeString(scriptFile, full, StandardCharsets.UTF_8);

            Path compiler = cbscriptDir().resolve("compile_once.py");
            List<String> cmd = List.of(python(), compiler.toString(),
                    scriptFile.toString(), "--out", outZip.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            // WHICH MINECRAFT. One bundled compiler serves both of this mod's targets, and its
            // attribute-id correction runs in opposite directions on 1.21.1 and 1.21.2+ (the
            // fork's attribute_ids.py). Unset, it assumes 1.21.1 -- which on the 1.21.4 build
            // rewrote every correct id into one that makes the function fail to load.
            pb.environment().put("CBSCRIPT_MC", com.moddingfromamod.compat.Target.MINECRAFT);
            pb.redirectInput(new File("/dev/null"));
            // Keep stdout (the JSON verdict) and stderr (PLY table warnings) SEPARATE — merging them
            // would corrupt the JSON with parser noise.
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(COMPILE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return CompileResult.failure(featureId, "CBScript compile timed out.", List.of());
            }
            CompileResult result = parse(featureId, outZip, stdout, stderr, full);
            if (!result.ok() && lastJsonObject(stdout) == null) {
                // NO RESULT AT ALL: the compiler never started. When the Python it ran on is not a working Python 3
                // (the Windows Store stub, Python 2: a machine never checked by the Magician setup), the agent is
                // told that, not to fix a source with nothing wrong in it.
                PythonCheck.Verdict v = PythonCheck.versionOf(cmd.get(0));
                if (!v.works()) {
                    return CompileResult.failure(featureId, PythonCheck.agentMessage(v), List.of());
                }
            }
            verify(featureId, description, outZip, result, skeleton);
            return checked(featureId, outZip, result);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("CBScript compile of '{}' failed to run: {}", featureId, e.toString());
            return CompileResult.failure(featureId, "Could not run the compiler: " + e, List.of());
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
        if ("verify".equals(PrecompiledPacks.mode()) && unavailableReason() == null) {
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

    private static CompileResult parse(String featureId, Path outZip, String stdout, String stderr,
                                       String fullSource) {
        List<String> log = new ArrayList<>();
        JsonObject json = lastJsonObject(stdout);
        if (json == null) {
            if (!stderr.isBlank()) {
                for (String ln : stderr.split("\n")) {
                    if (!ln.isBlank() && !ln.startsWith("WARNING:")) {  // PLY table noise
                        log.add(ln.strip());
                    }
                }
            }
            return CompileResult.failure(featureId,
                    "The compiler produced no result. " + (log.isEmpty() ? stdout.strip()
                            : String.join("\n", log)), log);
        }
        if (json.has("log") && json.get("log").isJsonArray()) {
            json.getAsJsonArray("log").forEach(e -> log.add(annotateLine(e.getAsString(), fullSource)));
        }
        boolean ok = json.has("ok") && json.get("ok").getAsBoolean();
        if (ok) {
            return CompileResult.ok(
                    json.has("namespace") ? json.get("namespace").getAsString() : featureId,
                    outZip, log);
        }
        String error = json.has("error") && !json.get("error").isJsonNull()
                ? json.get("error").getAsString() : "Compile failed.";
        return CompileResult.failure(featureId, annotateLine(error, fullSource), log);
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

    /** compile_once.py prints exactly one JSON object; be defensive and grab the last {...} block. */
    private static JsonObject lastJsonObject(String stdout) {
        if (stdout == null) {
            return null;
        }
        int end = stdout.lastIndexOf('}');
        while (end >= 0) {
            int depth = 0;
            for (int i = end; i >= 0; i--) {
                char c = stdout.charAt(i);
                if (c == '}') {
                    depth++;
                } else if (c == '{') {
                    depth--;
                    if (depth == 0) {
                        try {
                            return JsonParser.parseString(stdout.substring(i, end + 1))
                                    .getAsJsonObject();
                        } catch (RuntimeException ignored) {
                            break;
                        }
                    }
                }
            }
            end = stdout.lastIndexOf('}', end - 1);
        }
        return null;
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

    /** Copy every {@code *.cblib} from the fork's Scripts/ into {@code workDir} so imports resolve. */
    static void copyLibsInto(Path workDir) throws IOException {
        Path dir = cbscriptDir();
        if (dir == null) {
            return;
        }
        Path scripts = dir.resolve("Scripts");
        if (!Files.isDirectory(scripts)) {
            return;
        }
        try (Stream<Path> libs = Files.list(scripts)) {
            for (Path lib : libs.filter(p -> p.getFileName().toString().endsWith(".cblib")).toList()) {
                Files.copy(lib, workDir.resolve(lib.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ── small fs helpers ─────────────────────────────────────────────────────

    private static boolean isExec(String p) {
        return p != null && !p.isBlank() && new File(p).canExecute();
    }
}
