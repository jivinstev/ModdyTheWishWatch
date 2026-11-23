package com.moddingfromamod.datapack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The DISTILLED, continuously-growing half of the CBScript author's context — the output of the
 * outer improvement loop.
 *
 * <p>{@link CbScriptPrimer} is the static base baked into the mod; this is a plain-text file that
 * accumulates hard-won rules ("don't do X, it fails to parse"; "to do Y, write it like this") mined
 * from real compile failures ({@link CbScriptCompileLog}). Keeping it as <b>data</b>, not compiled
 * Java, is the whole point: a lesson learned from today's failures can improve tomorrow's first
 * draft without rebuilding or redeploying the mod — the agent just reads the newer file.
 *
 * <p>It lives in the CBScript fork ({@code <cbscriptDir>/CBSCRIPT_LESSONS.md}) because it is general
 * CBScript knowledge, versioned alongside the compiler it describes.
 */
public final class CbScriptLessons {

    private CbScriptLessons() {
    }

    /** The lessons file, if the fork is locatable. */
    public static Path file() {
        Path dir = CbScriptCompiler.cbscriptDir();
        return dir == null ? null : dir.resolve("CBSCRIPT_LESSONS.md");
    }

    /** The distilled lessons text, or "" if none exists yet. Never throws. */
    public static String text() {
        Path f = file();
        if (f == null || !Files.isRegularFile(f)) {
            return "";
        }
        try {
            return Files.readString(f, StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return "";
        }
    }
}
