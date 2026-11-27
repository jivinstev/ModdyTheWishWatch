package com.moddingfromamod.datapack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The Java code that turns a catalogue entry and a player's values into the CBScript a ready-made is compiled from: the
 * tools' give lines, derived parameters and wrappers. A change to it changes what a Builder power is compiled from, as
 * a change to a template does, so {@link PrecompiledPacks#fingerprint} covers it.
 *
 * <p>The rendering asks the game's registries for part of what it writes. A wished food takes its base's own food
 * component, for example. So it cannot be replayed in a unit test, and the source is fingerprinted instead. Only its
 * code counts: {@link #normalise} drops comments and whitespace, so rewording a comment needs no new store. It also
 * drops issue references, as the public render does, so the public build computes the same fingerprint as this one.
 * Pure: no Minecraft.
 */
public final class RenderingSource {

    /**
     * The classes a ready-made's CBScript is rendered by, under {@code src/main/java}. A class a ready-made's rendering
     * newly goes through is added here, in the same change.
     */
    public static final List<String> FILES = List.of(
            "com/moddingfromamod/agent/tools/ApplyEffectTool.java",
            "com/moddingfromamod/agent/tools/MakeItemTool.java",
            "com/moddingfromamod/datapack/DatapackForge.java",
            "com/moddingfromamod/datapack/EffectCatalogue.java",
            "com/moddingfromamod/datapack/ItemCatalogue.java",
            "com/moddingfromamod/datapack/ReadyMadeSkeleton.java");

    /** An issue reference as the public render removes it (tools/public-tree.py's {@code _ANY}). */
    static final Pattern ISSUE_REF = Pattern.compile("(?<![\\w&#/=$\"'.\\\\-])#\\d{1,5}(?:-#\\d{1,5})?(?![\\w-])");

    private RenderingSource() {
    }

    /** {@link #FILES}, each {@link #normalise}d, read from {@code java} (a tree's {@code src/main/java}). */
    public static String read(Path java, String file) throws IOException {
        return normalise(Files.readString(java.resolve(file), StandardCharsets.UTF_8));
    }

    /**
     * The code of a Java source, without its comments, its issue references or the amount of whitespace between tokens.
     * String, text-block and character literals are kept as written, apart from the issue references.
     */
    public static String normalise(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                while (end > 0 && escaped(src, end)) {
                    end = src.indexOf("\"\"\"", end + 1);
                }
                int stop = end < 0 ? n : end + 3;
                out.append(src, i, stop);
                i = stop;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                int stop = Math.min(n, j + 1);
                out.append(src, i, stop);
                i = stop;
            } else if (Character.isWhitespace(c)) {
                while (i < n && Character.isWhitespace(src.charAt(i))) {
                    i++;
                }
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return ISSUE_REF.matcher(out).replaceAll("").replaceAll("\\s+", " ").strip();
    }

    /** Whether the character at {@code at} is preceded by an odd number of backslashes. */
    private static boolean escaped(String s, int at) {
        int k = at - 1;
        int count = 0;
        while (k >= 0 && s.charAt(k) == '\\') {
            count++;
            k--;
        }
        return count % 2 == 1;
    }
}
