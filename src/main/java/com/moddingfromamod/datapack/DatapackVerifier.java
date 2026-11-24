package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/**
 * <b>Did the datapack actually load, and do its functions actually run?</b>
 *
 * <p>. Every other wish class proves itself — {@code verify_contraption} reads Create's
 * kinetic NBT, {@code verify_train} reads {@code Train.carriages} and the glue oracle, a building's
 * blocks are simply there to be counted. Datapacks, the most powerful class and the one every other
 * new class compiles down to, had <b>nothing</b>.
 *
 * <p>{@code docs/DATAPACKS.md} admits the hole: a pack can <b>compile, load, report success, and
 * still be broken at runtime</b> — a bad particle id, a mangled selector, a function that silently
 * never runs. The mitigation was a sentence in the tool's success message asking the agent to go
 * and check by hand. So the agent's confidence in a granted wish was, for this class, unearned.
 *
 * <p><b>What "verified" means here, precisely.</b> Minecraft parses every function at datapack load
 * and simply <i>drops</i> the ones that fail to parse — a dropped function is absent from the
 * function registry, not present-and-erroring. That makes the registry the honest oracle: if the
 * pack declared a function and the server cannot find it, the pack is broken however cheerfully it
 * compiled. Running one is the second, stronger check.
 */
public final class DatapackVerifier {

    private DatapackVerifier() {
    }

    /** What the verifier found. */
    /**
     * @param recipesWritten how many recipe files the pack actually contains
     * @param recipesLoaded  how many recipes the server accepted from it
     */
    public record Report(String featureId, boolean loaded, List<String> present,
                         List<String> missing, List<String> ranOk, List<String> failed,
                         int recipesWritten, int recipesLoaded) {

        public Report(String featureId, boolean loaded, List<String> present,
                      List<String> missing, List<String> ranOk, List<String> failed) {
            this(featureId, loaded, present, missing, ranOk, failed, 0, 0);
        }

        /**
         * <b>Every recipe the pack wrote was accepted.</b>
         *
         * <p>The check that did not exist, and whose absence cost a whole wish. A recipe with one
         * wrong key is REJECTED SILENTLY: no error in the game, nothing wrong in the world, and a
         * machine that sits there doing nothing exactly like a mechanical fault.
         *
         * <p>Measured consequence: told about that hazard and given no way to check it, a real
         * agent wrote in its own notes <i>"instead of a create:crushing recipe (which would
         * silently never load if a key were wrong) the transformation is a tick rule"</i> — and
         * built a machine that ground nothing while a datapack swapped items near it. Documenting a
         * silent failure without offering a test does not produce caution, it produces avoidance.
         */
        public boolean recipesOk() {
            return recipesWritten == recipesLoaded;
        }

        public boolean healthy() {
            return loaded && missing.isEmpty() && failed.isEmpty() && recipesOk()
                    && !(present.isEmpty() && recipesWritten == 0);
        }

        /** The agent-facing summary — phrased so it can be acted on, not just read. */
        public String describe() {
            if (!loaded) {
                return "NOT LOADED. '" + featureId + "' is not in the server's enabled datapacks, "
                        + "so nothing in it is running. It compiled, but it never took effect — "
                        + "author it again or check the id.";
            }
            StringBuilder sb = new StringBuilder("Datapack '").append(featureId).append("':\n");
            sb.append("  functions the pack declares that the server FOUND: ")
              .append(present.size()).append('\n');
            if (!missing.isEmpty()) {
                sb.append("  MISSING (Minecraft dropped these at load — they failed to parse):\n    - ")
                  .append(String.join("\n    - ", missing)).append('\n');
            }
            if (!ranOk.isEmpty()) {
                sb.append("  ran cleanly: ").append(String.join(", ", ranOk)).append('\n');
            }
            if (!failed.isEmpty()) {
                sb.append("  FAILED WHEN RUN:\n    - ")
                  .append(String.join("\n    - ", failed)).append('\n');
            }
            if (recipesWritten > 0 || recipesLoaded > 0) {
                sb.append("  recipes: wrote ").append(recipesWritten)
                  .append(", the server ACCEPTED ").append(recipesLoaded).append('\n');
                if (!recipesOk()) {
                    sb.append("    ").append(recipesWritten - recipesLoaded)
                      .append(" recipe(s) were REJECTED and are doing nothing. This is invisible in "
                              + "the world — the machine just sits there like a mechanical fault. "
                              + "Check: a fluid ingredient needs \"type\": \"neoforge:single\", a "
                              + "result is keyed \"id\" and NOT \"item\", and the folder is "
                              + "data/<pack>/recipe/ — singular, 1.21 ignores 'recipes'. The "
                              + "server log line begins 'Parsing error loading recipe'.\n");
                }
            }
            if (healthy()) {
                sb.append("\nWORKING — the pack is loaded and its functions are real and runnable. "
                        + "That is not the same as \"it does what the child asked\": watch it "
                        + "happen before you say it worked.");
            } else {
                sb.append("\nBROKEN. Do NOT tell the child it worked. A pack can compile and load "
                        + "and still be dead — that is exactly what happened here. Fix the named "
                        + "function and author it again.");
            }
            return sb.toString();
        }
    }

    /**
     * Verify a pack by asking the server's own function registry about it.
     *
     * @param entries function names the pack declares (without the namespace), or empty to accept
     *                whatever the pack registered
     * @param run     whether to actually execute the functions found. Off by default in bulk
     *                checks: running a pack's functions has side effects in the world.
     */
    public static Report verify(MinecraftServer server, String featureId, List<String> entries,
                                boolean run) {
        String ns = featureId.trim().toLowerCase(java.util.Locale.ROOT);
        boolean loaded = server.getPackRepository().getSelectedIds().stream()
                .anyMatch(s -> s.contains(ns));

        var library = server.getFunctions();
        Set<String> ours = new LinkedHashSet<>();
        for (Identifier key : library.getFunctionNames()) {
            if (ns.equals(key.getNamespace())) {
                ours.add(key.getPath());
            }
        }

        List<String> present = new ArrayList<>(ours);
        List<String> missing = new ArrayList<>();
        // Nothing named: what the pack DECLARES is every function file in it. "Whatever the server
        // registered" made a pack whose rebirth function Minecraft refused read as WORKING (
        // the phoenix: 11 of 14 loaded, the 3 that did the rebirth never did, and nothing said so).
        Map<String, List<String>> files = functionFilesIn(ns);
        List<String> wanted = entries.isEmpty() ? new ArrayList<>(files.keySet()) : entries;
        for (String want : wanted) {
            String w = want.trim().toLowerCase(java.util.Locale.ROOT);
            if (!w.isEmpty() && !ours.contains(w)) {
                missing.add(ns + ":" + w + whyRefused(server, ns, w, files.get(w)));
            }
        }

        List<String> ranOk = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        if (run && loaded) {
            CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput()
                    .withPermission(2);
            for (String fn : present) {
                Identifier key = Identifier.fromNamespaceAndPath(ns, fn);
                try {
                    var found = library.get(key);
                    if (found.isEmpty()) {
                        failed.add(ns + ":" + fn + " — vanished between listing and running");
                        continue;
                    }
                    library.execute(found.get(), src);
                    ranOk.add(fn);
                } catch (RuntimeException e) {
                    // The failure mode the doc warns about: it compiled, it loaded, it dies here.
                    failed.add(ns + ":" + fn + " — " + e);
                }
            }
        }
        // HOW MANY RECIPES DID IT WRITE, AND HOW MANY DID THE SERVER TAKE? The one question that
        // makes an authored Create recipe trustworthy enough to use at all.
        int written = recipeFilesIn(ns);
        int accepted = 0;
        for (var holder : server.getRecipeManager().getRecipes()) {
            if (ns.equals(holder.id().location().getNamespace())) {
                accepted++;
            }
        }
        return new Report(ns, loaded, present, missing, ranOk, failed, written, accepted);
    }

    /** Count the recipe files the pack actually shipped, by reading its own zip. */
    /** Every {@code data/<ns>/function(s)/…mcfunction} in the pack's zip: its path, and its lines. */
    private static Map<String, List<String>> functionFilesIn(String ns) {
        Map<String, List<String>> out = new java.util.TreeMap<>();
        java.nio.file.Path zip = com.moddingfromamod.datapack.DatapackStore.zipFor(ns);
        if (zip == null || !java.nio.file.Files.isRegularFile(zip)) {
            return out;
        }
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            var it = zf.entries();
            while (it.hasMoreElements()) {
                var e = it.nextElement();
                String name = e.getName();
                for (String dir : List.of("data/" + ns + "/function/", "data/" + ns + "/functions/")) {
                    if (name.startsWith(dir) && name.endsWith(".mcfunction")) {
                        String text = new String(zf.getInputStream(e).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        out.put(name.substring(dir.length(), name.length() - ".mcfunction".length()),
                                List.of(text.split("\r?\n", -1)));
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            return out;
        }
        return out;
    }

    /**
     * Minecraft's own reason for refusing a function, read by parsing it again the way the server
     * did at load — " — Whilst parsing command on line 5: Invalid ID …" — or "" when it cannot say.
     */
    private static String whyRefused(MinecraftServer server, String ns, String path, List<String> lines) {
        if (lines == null) {
            return "";
        }
        try {
            net.minecraft.commands.functions.CommandFunction.fromLines(
                    Identifier.fromNamespaceAndPath(ns, path), server.getCommands().getDispatcher(),
                    server.createCommandSourceStack().withPermission(2), lines);
            return "";
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return " — " + (msg.length() > 240 ? msg.substring(0, 240) + "…" : msg);
        }
    }

    private static int recipeFilesIn(String ns) {
        java.nio.file.Path zip = com.moddingfromamod.datapack.DatapackStore.zipFor(ns);
        if (zip == null || !java.nio.file.Files.isRegularFile(zip)) {
            return 0;
        }
        int n = 0;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            var it = zf.entries();
            while (it.hasMoreElements()) {
                String name = it.nextElement().getName();
                // 'recipe', singular — and a file under the old plural 'recipes/' is counted too,
                // deliberately: that is the directory rename 1.21 ignores silently, and counting it
                // is what turns "wrote 3, accepted 0" into a visible failure instead of a pack that
                // looks like it never had any recipes.
                if (name.endsWith(".json")
                        && (name.contains("/recipe/") || name.contains("/recipes/"))) {
                    n++;
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            return 0;
        }
        return n;
    }
}
