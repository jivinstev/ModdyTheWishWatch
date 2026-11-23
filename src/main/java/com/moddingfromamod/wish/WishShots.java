package com.moddingfromamod.wish;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <b>Every photograph a wish takes, filed under that wish</b> — so a server can ship it to
 * an operator's view and nobody has to re-photograph a wish that was photographed already.
 *
 * <p>A photograph is named after what it shows ({@code <label>.png}, {@code <subject>__0_face.png}),
 * never after the wish that took it, and the next shot with the same label overwrites it. So the
 * photos folder alone cannot answer "what did this wish look like", and by the time anything
 * asks, a later wish may have written over the answer. This keeps a COPY, at the moment a shot is
 * confirmed complete, in {@code photos/wish/<wish8>/} — the same eight hex characters the action
 * log and the tower key a wish by — and keeps every retake rather than the last one (a retake is
 * the agent fixing something, which is worth seeing beside the original).
 *
 * <p>Which wish is taking the shot is known only to the MCP call that is running it, so
 * {@code McpProtocol} brackets each tool call with {@link #during}; a shot taken outside any wish
 * (a probe, a test) is not filed.
 *
 * <p>Bounded: at most {@link #PER_WISH} files per wish, and a byte-identical retake is not kept
 * twice. Pruned with the rest of {@code photos/} by a server's log rotation.
 */
public final class WishShots {

    /** The eight hex characters of a wish id — {@code Wish.shortId()}, and the tower's MEDIA_ID. */
    static final Pattern WISH8 = Pattern.compile("[0-9a-f]{8}");

    /** A file name an operator's viewer will serve, before the extension. */
    private static final Pattern SAFE_STEM = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,80}");

    /** Enough for four orbits and their retakes; a wish that shoots more is a runaway loop. */
    static final int PER_WISH = 48;

    private static final ThreadLocal<String> WISH = new ThreadLocal<>();

    private WishShots() {
    }

    /** Run {@code body} as wish {@code wish8}'s: shots confirmed inside it are filed under it. */
    public static <T> T during(String wish8, Supplier<T> body) {
        String previous = WISH.get();
        if (wish8 == null || !WISH8.matcher(wish8).matches()) {
            WISH.remove();
        } else {
            WISH.set(wish8);
        }
        try {
            return body.get();
        } finally {
            if (previous == null) {
                WISH.remove();
            } else {
                WISH.set(previous);
            }
        }
    }

    /** The wish the current thread is working for, or empty. */
    public static String current() {
        String w = WISH.get();
        return w == null ? "" : w;
    }

    /**
     * A photograph is complete on disk: keep a copy under the current wish, if there is one.
     * Never throws — a photograph that could not be filed is still a photograph the agent can use.
     *
     * @return where the copy went, or null if nothing was filed
     */
    public static Path keep(Path photo) {
        String wish8 = current();
        if (wish8.isEmpty() || photo == null || photo.getParent() == null) {
            return null;
        }
        try {
            return file(photo, photo.getParent().resolve("wish").resolve(wish8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The filing itself, given the wish's folder. Package-private for the unit test. */
    static Path file(Path photo, Path dir) throws IOException {
        if (!Files.isRegularFile(photo)) {
            return null;
        }
        Files.createDirectories(dir);
        Set<String> have;
        try (Stream<Path> s = Files.list(dir)) {
            have = s.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
        if (have.size() >= PER_WISH) {
            return null;
        }
        String stem = stemOf(photo.getFileName().toString());
        byte[] bytes = Files.readAllBytes(photo);
        for (String n : have) {
            if (n.equals(stem + ".png") || (n.startsWith(stem + "-") && n.endsWith(".png"))) {
                if (Arrays.equals(bytes, Files.readAllBytes(dir.resolve(n)))) {
                    return dir.resolve(n);   // this exact picture is already filed
                }
            }
        }
        Path dest = dir.resolve(nextName(have, stem));
        Path tmp = dir.resolve("." + dest.getFileName() + ".part");
        Files.write(tmp, bytes);
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return dest;
    }

    /** {@code Leaper__0_Face.png} → {@code leaper__0_face}; anything the tower would refuse → {@code photo}. */
    static String stemOf(String fileName) {
        String s = fileName.toLowerCase(java.util.Locale.ROOT);
        if (s.endsWith(".png")) {
            s = s.substring(0, s.length() - 4);
        }
        s = s.replaceAll("[^a-z0-9_.-]", "-");
        return SAFE_STEM.matcher(s).matches() ? s : "photo";
    }

    /** {@code stem.png}, or the first free {@code stem-2.png}, {@code stem-3.png}… */
    static String nextName(Set<String> have, String stem) {
        if (!have.contains(stem + ".png")) {
            return stem + ".png";
        }
        for (int i = 2; ; i++) {
            String n = stem + "-" + i + ".png";
            if (!have.contains(n)) {
                return n;
            }
        }
    }
}
