package com.moddingfromamod.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

/**
 * <b>A store key as a file name every OS accepts.</b>
 *
 * <p>Some stores name a file after the thing it is about ({@code <artifact id>.json}), and an id can hold what Windows
 * refuses in a file name: an item's catalogue key is {@code id:<name>}, and {@code Path.resolve("id:zap.json")} throws
 * {@code InvalidPathException} there. Thrown inside a server task, it left a Builder edit waiting forever on Windows
 * while Linux and macOS (which accept {@code :}) passed (the platform smoke, docs/RELEASING.md, 2026-11-26).
 *
 * <p>The rule: lower-case letters, digits, {@code _} and {@code -} stay as they are, and so does {@code .} except at
 * the start or the end; every other character is written as {@code %} and its UTF-8 bytes in two hex digits
 * ({@code id:zap} is {@code id%3azap}), {@code %} included, so two keys never share a name. A name Windows reserves for
 * a device ({@code con}, {@code nul}, {@code com1}, ...) gets its first letter escaped. An id that was already a safe
 * name (almost all of them) keeps the name it always had.
 *
 * <p>Pure, no Minecraft: {@code SafeFileNameTest} (Gate A). {@link #resolve} is the one place a store turns a key into
 * its file, and moves a file saved under the old raw name (on Linux or macOS) to the new one the first time it is read.
 */
public final class SafeFileName {

    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private SafeFileName() {
    }

    /** {@code key} (already lower-cased by its store, or not) as a file name stem every OS accepts. Never empty. */
    public static String of(String key) {
        String k = key == null ? "" : key;
        if (k.isEmpty()) {
            return "%";
        }
        StringBuilder out = new StringBuilder(k.length() + 8);
        byte[] bytes = k.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            boolean dot = b == '.';
            boolean keep = (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '_' || b == '-'
                    || (dot && i != 0 && i != bytes.length - 1);
            if (keep) {
                out.append((char) b);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02x", b));
            }
        }
        String s = out.toString();
        int dot = s.indexOf('.');
        String base = dot < 0 ? s : s.substring(0, dot);
        if (RESERVED.contains(base)) {
            s = String.format(Locale.ROOT, "%%%02x", (int) s.charAt(0)) + s.substring(1);
        }
        return s;
    }

    /**
     * The file for {@code key} in {@code dir}: {@code dir/<of(key)><ext>}. When it is not there but a file saved under
     * the raw key is (a name this OS can resolve: written on Linux or macOS before this rule), {@code migrate} moves it
     * to the safe name first; without {@code migrate} (a read-only probe) the old file itself is returned.
     */
    public static Path resolve(Path dir, String key, String ext, boolean migrate) {
        Path safe = dir.resolve(of(key) + ext);
        if (Files.exists(safe)) {
            return safe;
        }
        Path old;
        try {
            old = dir.resolve(key + ext);
        } catch (InvalidPathException notOnThisOs) {
            return safe;   // a name this OS cannot hold can never have been saved here
        }
        if (old.equals(safe) || !Files.isRegularFile(old)) {
            return safe;
        }
        if (!migrate) {
            return old;
        }
        try {
            Files.move(old, safe, StandardCopyOption.ATOMIC_MOVE);
            return safe;
        } catch (IOException e) {
            return old;   // still readable where it is; the next read tries again
        }
    }
}
