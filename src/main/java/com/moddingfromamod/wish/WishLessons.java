package com.moddingfromamod.wish;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.moddingfromamod.ModdingFromAModMod;

/**
 * <b>Hard-won rules, as DATA the agent reads — not as prose nobody machine-reads.</b>
 *
 * <p> Phase 3⑤, generalising the one mechanism in this repo that already closes the loop.
 * {@code CBSCRIPT_LESSONS.md} is written from real compile failures, read at runtime by
 * {@link com.moddingfromamod.datapack.CbScriptLessons}, and appended to the brief by
 * {@code cbscript_help} — so, in that file's own words, <i>"a lesson learned from today's failures
 * can improve tomorrow's first draft without rebuilding or redeploying the mod"</i>. Nothing outside
 * CBScript had that. Trains learned two expensive lessons in August and both of them lived only in
 * a Java string and a doc.
 *
 * <p><b>Why this is the right shape.</b> One-shot success comes from the agent knowing a constraint
 * <i>before</i> it plans, not from testing harder after it fails. A gate catches a bad train after
 * the wish is spent; a lesson in the brief stops it being designed.
 *
 * <h2>Two layers, and the override is the point</h2>
 *
 * <ol>
 *   <li>a <b>bundled default</b> at {@code /lessons/&lt;kind&gt;.md} in the jar, so the rules ship
 *       and work out of the box;</li>
 *   <li>an optional <b>runtime override</b> at {@code <gameDir>/moddingfromamod/lessons/&lt;kind&gt;.md},
 *       which wins when present — that is the half that lets a lesson from tonight's play reach
 *       tomorrow's first draft without a rebuild.</li>
 * </ol>
 *
 * <p>Never throws and never blocks a wish: a missing or unreadable lessons file yields "", and the
 * caller simply carries on without the extra context.
 */
public final class WishLessons {

    /** Where an operator drops updated lessons, relative to the game directory. */
    public static final String OVERRIDE_DIR = "moddingfromamod/lessons";

    private WishLessons() {
    }

    /**
     * The lessons for one wish kind ({@code "train"}, {@code "building"}, …), or "" if none.
     *
     * @param kind the wish kind, used as the file name
     */
    public static String text(String kind) {
        if (kind == null || kind.isBlank()) {
            return "";
        }
        // A/B SEAM. -Ptestprops="lessons=off" silences every lesson, so the same wish can be run
        // with and without them and the outputs compared. Without a way to turn a lever OFF you
        // cannot tell whether it does anything — you can only tell yourself that it does.
        if ("off".equalsIgnoreCase(System.getProperty(
                com.moddingfromamod.ModdingFromAModMod.MODID + ".lessons"))) {
            return "";
        }
        String safe = kind.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (safe.isEmpty()) {
            return "";
        }
        String override = fromDisk(safe);
        return !override.isEmpty() ? override : fromJar(safe);
    }

    /**
     * The lessons formatted for appending to a tool result, or "" when there are none.
     *
     * <p>Placed LAST by convention, as {@code cbscript_help} does: the freshest hard-won rules are
     * the last thing the model reads before it starts designing.
     */
    public static String appendTo(String kind) {
        String lessons = text(kind);
        if (lessons.isEmpty()) {
            return "";
        }
        return "\n\n══════════════════════════════════════════════════════════════════════\n"
                + "LESSONS FROM REAL FAILURES (these cost real wishes — do not rediscover them):\n\n"
                + lessons;
    }

    /**
     * {@link #appendTo}, ONCE per wish: the first tool to ask hands the lessons over, the rest add
     * nothing. Outside a wish (a test, a tool called by hand) every call hands them over.
     */
    public static String appendOnce(String kind) {
        return BuildGate.current().firstLessons(kind) ? appendTo(kind) : "";
    }

    /** The override file's location, whether or not it exists — useful in a diagnostic. */
    public static Path overrideFile(String kind) {
        try {
            return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve(OVERRIDE_DIR).resolve(kind + ".md");
        } catch (RuntimeException | NoClassDefFoundError e) {
            return null;   // outside a loaded game (a unit test); the bundled copy still works
        }
    }

    private static String fromDisk(String kind) {
        Path f = overrideFile(kind);
        try {
            return f != null && Files.isRegularFile(f)
                    ? Files.readString(f, StandardCharsets.UTF_8).strip() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String fromJar(String kind) {
        try (InputStream in = WishLessons.class.getResourceAsStream("/lessons/" + kind + ".md")) {
            return in == null ? ""
                    : new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return "";
        }
    }

    /** For a startup log line: which source a kind's lessons came from. */
    public static String describe(String kind) {
        if (!fromDisk(kind).isEmpty()) {
            return ModdingFromAModMod.MODID + " lessons for '" + kind + "': "
                    + overrideFile(kind) + " (runtime override)";
        }
        if (!fromJar(kind).isEmpty()) {
            return ModdingFromAModMod.MODID + " lessons for '" + kind + "': bundled default";
        }
        return ModdingFromAModMod.MODID + " lessons for '" + kind + "': none";
    }
}
