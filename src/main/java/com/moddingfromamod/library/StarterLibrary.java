package com.moddingfromamod.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The starter nine, shipped in the jar as a wish library of their own</b>
 * ({@code docs/STARTER_NINE.md}).
 *
 * <p>The rule is one path for everything, so the first row a child sees is served exactly
 * the way every cached wish is — a library entry, read by {@link LibrarySnapshot}, replayed by the
 * same commands — and not by the six hard-coded presets it replaces. What makes it a STARTER is only
 * where it comes from: it ships inside the jar, so every install and every gate has it with no
 * configuration, and {@link WishLibrary} layers it UNDER the owner's library, so the owner can
 * override or retire any of the nine without a new jar.
 *
 * <p><b>Why it is unpacked rather than read in place.</b> Everything that serves a library artifact
 * resolves a {@link Path} ({@code ModelRepository}, {@code BuildRegistry}, the datapack loader), and a
 * resource inside a jar is not one. Unpacking once per boot into {@code <mod home>/starter-library/}
 * means none of them learns a second way to read. Each jar gets its OWN directory ({@link #BASE}).
 *
 * <p><b>Why an index.</b> A classpath directory cannot be listed portably inside a jar, so
 * {@code starter-library/index.txt} names every file; {@code StarterLibraryTest} fails if it and the
 * resource tree ever disagree — a file the index forgot would silently not ship.
 *
 * <p>Minecraft-free: the resource reader is a function, so a unit test hands it a fake one.
 */
public final class StarterLibrary {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** The resource root inside the jar. */
    public static final String ROOT = "starter-library/";
    public static final String INDEX = ROOT + "index.txt";

    /**
     * where every jar's starter library goes, ONE DIRECTORY PER CONTENT under it.
     *
     * <p>It used to be one directory, {@code starter-library/}, rewritten by every boot — so two
     * processes running different jars on one machine overwrote each other's starter library under
     * a running server. Measured: a Gate B beside a Gate D from a checkout without the parts
     * failed {@code body 'wolf' does not ship}; on a dedicated server it is two servers mid-upgrade sharing one
     * {@code MODDY_HOME}. Now each jar reads {@code starter-libraries/<content hash>/}, written once
     * and never touched by another jar. A NEW name rather than subdirectories of the old one,
     * because an older jar still deletes and rewrites {@code starter-library/} wholesale at boot.
     */
    public static final String BASE = "starter-libraries";

    /** Written LAST into an unpack, so a directory without it is not a starter library. */
    static final String COMPLETE = ".complete";

    /** How many other jars' starter libraries a boot leaves standing (the newest by use). */
    static final int KEEP = 6;

    private static volatile Path unpacked;
    private static volatile boolean disabledForTest;

    private StarterLibrary() {
    }

    /**
     * The unpacked starter library, unpacking it on first use. Null when there is none to serve —
     * never an exception: a server without its starter row still serves everything else.
     */
    public static synchronized Path dir() {
        if (disabledForTest) {
            return null;
        }
        if (unpacked == null) {
            try {
                unpacked = unpack(RuntimeConfig.home().resolve(BASE),
                        name -> StarterLibrary.class.getClassLoader().getResourceAsStream(name));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("could not unpack the starter library: {}", e.toString());
                return null;
            }
        }
        return unpacked;
    }

    /** The files the index names, relative to {@link #ROOT}. Blank lines and {@code #} are skipped. */
    static List<String> files(String indexText) {
        List<String> out = new ArrayList<>();
        for (String line : indexText.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("/") || t.contains("..") || t.contains("\\")) {
                throw new IllegalArgumentException("unsafe starter-library path: " + t);
            }
            out.add(t);
        }
        return out;
    }

    /**
     * This jar's starter library under {@code base}: {@code base/<content hash>/}, unpacked once.
     *
     * <p>The hash is over the index and every file it names, so two jars that ship the same starter
     * library share one directory and two that differ can never meet. A directory that already
     * carries {@link #COMPLETE} is used as it is. Otherwise the files are written into a sibling
     * temp directory and MOVED into place, so a server that dies half way leaves no half-written
     * starter library, and a second process of the same jar racing this one simply uses the winner's.
     * No other jar's directory is ever written; the oldest are pruned beyond {@link #KEEP}.
     *
     * @return this jar's directory, or null when the jar ships no starter library
     */
    static Path unpack(Path base, Function<String, InputStream> resources) throws IOException {
        byte[] indexBytes;
        try (InputStream in = resources.apply(INDEX)) {
            if (in == null) {
                return null;
            }
            indexBytes = in.readAllBytes();
        }
        java.util.Map<String, byte[]> content = new java.util.LinkedHashMap<>();
        java.security.MessageDigest sha;
        try {
            sha = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        sha.update(indexBytes);
        for (String rel : files(new String(indexBytes, StandardCharsets.UTF_8))) {
            try (InputStream in = resources.apply(ROOT + rel)) {
                if (in == null) {
                    throw new IOException("the index names " + rel + " but the jar does not have it");
                }
                byte[] bytes = in.readAllBytes();
                content.put(rel, bytes);
                sha.update(rel.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
                sha.update(bytes);
            }
        }
        String id = java.util.HexFormat.of().formatHex(sha.digest()).substring(0, 16);
        Path target = base.resolve(id);
        if (Files.isRegularFile(target.resolve(COMPLETE))) {
            touch(target.resolve(COMPLETE));
            return target;
        }
        Files.createDirectories(base);
        Path tmp = Files.createTempDirectory(base, ".unpacking-");
        try {
            for (var e : content.entrySet()) {
                Path out = tmp.resolve(e.getKey());
                Files.createDirectories(out.getParent());
                Files.write(out, e.getValue());
            }
            Files.writeString(tmp.resolve(COMPLETE), id + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException
                     | java.nio.file.DirectoryNotEmptyException raced) {
                deleteTree(tmp);   // another process of this same jar finished first: use its copy
                if (!Files.isRegularFile(target.resolve(COMPLETE))) {
                    throw new IOException("a starter library at " + target + " is incomplete");
                }
            }
        } catch (IOException | RuntimeException e) {
            deleteTree(tmp);
            throw e;
        }
        LOGGER.info("starter library unpacked to {}", target);
        prune(base, target);
        return target;
    }

    private static void touch(Path marker) {
        try {
            Files.setLastModifiedTime(marker, java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis()));
        } catch (IOException ignored) {
            // only the pruning order rests on it
        }
    }

    /**
     * Keep this jar's directory and the {@link #KEEP} most recently used others; remove the rest,
     * and any temp directory an interrupted unpack left. Best effort: pruning never fails a boot.
     */
    static void prune(Path base, Path keep) {
        try (var list = Files.list(base)) {
            List<Path> others = new ArrayList<>();
            for (Path p : list.toList()) {
                if (p.equals(keep)) {
                    continue;
                }
                if (p.getFileName().toString().startsWith(".unpacking-")) {
                    if (Files.getLastModifiedTime(p).toMillis() < System.currentTimeMillis() - 3_600_000L) {
                        deleteTree(p);
                    }
                    continue;
                }
                if (Files.isRegularFile(p.resolve(COMPLETE))) {
                    others.add(p);
                }
            }
            others.sort(Comparator.comparingLong((Path p) -> {
                try {
                    return Files.getLastModifiedTime(p.resolve(COMPLETE)).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }).reversed());
            for (Path old : others.subList(Math.min(KEEP, others.size()), others.size())) {
                deleteTree(old);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not prune old starter libraries under {}: {}", base, e.toString());
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (p == null || !Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            for (Path q : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(q);
            }
        }
    }

    /** The same seam for a JUnit class that sets it in {@code @BeforeEach} and clears it after. */
    static void disabledForTest(boolean off) {
        disabledForTest = off;
    }

    /**
     * <b>Run with NO starter layer</b> — for the library's own tests, which assert on exactly the
     * library they wrote. A scoped seam rather than a flag, so it cannot be left switched off: there
     * is no configuration in which a shipped server runs without its starter row.
     */
    public static void withoutStarterForTest(Runnable body) {
        boolean was = disabledForTest;
        disabledForTest = true;
        try {
            body.run();
        } finally {
            disabledForTest = was;
        }
    }
}
