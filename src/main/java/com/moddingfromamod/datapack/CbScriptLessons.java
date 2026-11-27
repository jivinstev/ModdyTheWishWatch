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
 * <p>It lives in the CBScript fork ({@code CBSCRIPT_LESSONS.md}) because it is general CBScript knowledge, versioned
 * alongside the compiler it describes, and it ships inside the vendored compiler
 * ({@code com/moddingfromamod/cbscript/cbscript-lessons.md} on the classpath). A developer trying newer lessons without
 * rebuilding points {@code -Dmoddingfromamod.cbscript.lessons} (or {@code CBSCRIPT_LESSONS}) at a file.
 */
public final class CbScriptLessons {

    static final String RESOURCE = "/com/moddingfromamod/cbscript/cbscript-lessons.md";

    private CbScriptLessons() {
    }

    /** A lessons file named by the developer override, or null. */
    public static Path file() {
        String p = System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".cbscript.lessons");
        if (p == null || p.isBlank()) {
            p = System.getenv("CBSCRIPT_LESSONS");
        }
        return p == null || p.isBlank() ? null : Path.of(p);
    }

    /** The distilled lessons text, or "" if none exists. Never throws. */
    public static String text() {
        Path f = file();
        try {
            if (f != null && Files.isRegularFile(f)) {
                return Files.readString(f, StandardCharsets.UTF_8).strip();
            }
            try (java.io.InputStream in = CbScriptLessons.class.getResourceAsStream(RESOURCE)) {
                return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
