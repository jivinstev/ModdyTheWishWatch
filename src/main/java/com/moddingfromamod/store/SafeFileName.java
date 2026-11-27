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
 * a device ({@code con}, {@code nul}, {@code com1}, ...) gets its first letter escaped, and a name longer than
 * {@link #MAX} keeps its start and ends in {@code ~} and a hash of the whole key. An id that was already a safe name
 * (almost all of them) keeps the name it always had.
 *
 * <p>Pure, no Minecraft: {@code SafeFileNameTest} (Gate A). {@link #resolve} is the one place a store turns a key into
 * its file, and moves a file saved under the old raw name (on Linux or macOS) to the new one the first time it is read.
 */
public final class SafeFileName {

    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** The longest name {@link #of} returns, and how much of a longer one stays readable. */
    public static final int MAX = 96;
    private static final int KEEP = MAX - 17;

    private SafeFileName() {
    }

    private static String sha256(String k) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(k.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every JDK has SHA-256
        }
    }

    /**
     * Does Windows reserve this name for a device? {@code con}, {@code nul}, {@code com1}... in any case, with or without
     * an extension ({@code con.png}, {@code con.undo-123} too). A sanitizer that keeps only safe characters still lets
     * these through; this is the check it adds.
     */
    public static boolean isWindowsReserved(String name) {
        if (name == null) {
            return false;
        }
        int dot = name.indexOf('.');
        return RESERVED.contains((dot < 0 ? name : name.substring(0, dot)).trim().toLowerCase(Locale.ROOT));
    }

    /**
     * A readable stem from a free label (a photo's label, a film's name): lower-cased, anything but {@code [a-z0-9_-]}
     * becomes {@code _}, cut at {@code max}, a device name Windows keeps gets a {@code _} on its end, and an empty result
     * is {@code fallback}. Not one-to-one (two labels can share a stem), which is what a label that names a picture to
     * overwrite wants; a store key that must never collide uses {@link #of}.
     */
    public static String stem(String raw, int max, String fallback) {
        StringBuilder sb = new StringBuilder();
        for (char c : (raw == null ? "" : raw).toLowerCase(Locale.ROOT).toCharArray()) {
            if (sb.length() >= max) {
                break;
            }
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        if (sb.length() == 0) {
            return fallback;
        }
        if (isWindowsReserved(sb.toString())) {
            if (sb.length() >= max) {
                sb.setLength(max - 1);
            }
            sb.append('_');
        }
        return sb.toString();
    }

    /**
     * {@code id} itself when it is already a name every OS accepts (no character Windows refuses, not a device name,
     * not ending in a dot or space, not empty, at most {@link #MAX}), else {@link #of}. For a folder other code also
     * finds by the raw id (a species' {@code <base>~<who>} fork key): a valid name never moves.
     */
    public static String segment(String id) {
        String k = id == null ? "" : id;
        boolean ok = !k.isEmpty() && k.length() <= MAX && !isWindowsReserved(k)
                && !k.endsWith(".") && !k.endsWith(" ") && !k.equals("..");
        for (int i = 0; ok && i < k.length(); i++) {
            char c = k.charAt(i);
            ok = c >= 0x20 && "<>:\"/\\|?*".indexOf(c) < 0;
        }
        return ok ? k : of(k);
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
        if (s.length() > MAX) {
            // A long key (a typed name, a long description) would pass Windows' 255-character segment and its 260-character
            // path well before that. Its first KEEP characters stay readable; the rest is a hash of the whole key, after
            // a '~' that no escaped name contains, so a cut name never meets an uncut one.
            s = s.substring(0, KEEP) + "~" + sha256(k).substring(0, 16);
        }
        int dot = s.indexOf('.');
        String base = dot < 0 ? s : s.substring(0, dot);
        if (isWindowsReserved(base)) {
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
