package com.moddingfromamod.tools;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.moddingfromamod.ModdingFromAModMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deploys a bundled command-line tool the agent needs (right now: the CBScript compiler) to a
 * writable, non-TCC-protected place it can actually run from.
 *
 * <p><b>Why this exists.</b> The CBScript compiler used to be run straight out of a dev checkout under
 * {@code ~/Desktop/Code/cbscript}. On macOS {@code ~/Desktop} is a TCC-protected folder, so the
 * sandboxed game process got {@code Operation not permitted} the moment it listed the compiler's
 * files — every wish that needed a datapack silently failed and fell back to a slow hand-build. The
 * fix is to ship the tool <b>inside the mod jar</b> and extract it once to
 * {@code ~/.moddingfromamod/tools/<name>/}, a plain dotfolder no OS permission prompt guards.
 *
 * <p><b>The pattern.</b> Any tool bundled as a resource {@code /tools/<name>.zip} can be deployed with
 * {@link #ensureExtracted(String)}. Extraction is idempotent and content-addressed: the SHA-256 of the
 * bundled zip is written as a {@code .version} marker, so a new jar (new tool) re-extracts and an
 * unchanged one is a fast no-op.
 */
public final class ToolDeploy {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private ToolDeploy() {
    }

    /**
     * {@code ~/.moddingfromamod/tools} — writable and outside every TCC-protected folder.
     *
     * <p><b>Deliberately the real home, and NOT {@code RuntimeConfig.home()}</b> — the relocatable home moved the
     * four LOGS that hardcoded {@code user.home} and left this one where it is, because it is not
     * a record of anything. It is a content-addressed extraction CACHE whose whole job is to be a
     * stable, non-TCC-protected place the game can execute from; it is rebuilt from the jar on any
     * host, so it travels nowhere and moving a server's home does not want it. Following a relocated home would mean
     * every test that points {@code moddingfromamod.home} at a temp directory — and several do,
     * then compile CBScript — re-unzipping the compiler inside the gate, turning a fast no-op into
     * real work for no answer anybody asked for. A caller that genuinely wants another root has
     * {@link #extractTo(String, Path)}, which exists for exactly that.
     */
    public static Path toolsRoot() {
        return Path.of(System.getProperty("user.home", "."), ".moddingfromamod", "tools");
    }

    /**
     * Ensure the bundled tool {@code name} (resource {@code /tools/<name>.zip}) is extracted under
     * {@link #toolsRoot()} and up to date, and return its directory. Returns null if the mod ships no
     * such bundle (a dev running from source uses {@code CBSCRIPT_DIR} instead).
     */
    public static synchronized Path ensureExtracted(String name) {
        return extractTo(name, toolsRoot());
    }

    /**
     * Seam for tests: extract into an arbitrary root instead of the user's home, so a test can prove
     * deployment works without clobbering a real installation.
     */
    public static synchronized Path extractTo(String name, Path root) {
        Path legacy = root.resolve(name);
        String resource = "/tools/" + name + ".zip";
        byte[] bytes;
        try (InputStream in = ToolDeploy.class.getResourceAsStream(resource)) {
            if (in == null) {
                // Not bundled in this build; if a copy was already extracted, use it, else give up.
                return Files.isDirectory(legacy) ? legacy : null;
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            LOGGER.warn("could not read bundled tool '{}': {}", name, e.toString());
            return Files.isDirectory(legacy) ? legacy : null;
        }

        String version = sha256(bytes);
        Path dest = versionedDir(root, name, version);
        try {
            if (isComplete(dest, version)) {
                return dest;   // this exact bundle is already unpacked — fast path
            }
            // A staging directory NOBODY ELSE CAN BE USING: per version and per process.
            Path tmp = root.resolve("." + name + "-" + shortVersion(version) + "."
                    + ProcessHandle.current().pid() + ".extracting");
            deleteRecursively(tmp);
            unzipInto(bytes, tmp);
            Files.writeString(tmp.resolve(".version"), version, StandardCharsets.UTF_8);
            Files.createDirectories(dest.getParent());
            // An INCOMPLETE dest is abandoned wreckage — a crashed or killed extraction — and it
            // has to go, or the move below can never succeed and this tool is unusable for ever.
            // Safe to delete precisely because a directory with no marker is never handed to a
            // reader, and another process mid-extraction is working in its own private staging
            // directory, not here. (Found by its own test, which deleted a marker and got null
            // back from every subsequent call.)
            //
            // ⚠ RE-ASK BEFORE DELETING. The isComplete() at the top is stale by now: another
            // process may have finished and moved ITS copy in while this one was unzipping — and
            // that copy is in use. Deleting it pulled files out from under a running compiler:
            // `NoSuchFileException: …/Scripts/click_detector.cblib` in LeaveBehindCompilesTest,
            // on a cold ~/.moddingfromamod/tools with Gradle's parallel test forks (2026-10-15).
            if (Files.exists(dest)) {
                if (isComplete(dest, version)) {
                    deleteRecursively(tmp);
                    return dest;
                }
                deleteRecursively(dest);
            }
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException raced) {
                // Another process unpacked the same bundle first. Its copy is as good as ours —
                // identical bytes, by construction — so take it and throw ours away.
                deleteRecursively(tmp);
                if (isComplete(dest, version)) {
                    return dest;
                }
                throw raced;
            }
            LOGGER.info("deployed bundled tool '{}' -> {}", name, dest);
            return dest;
        } catch (IOException e) {
            LOGGER.warn("could not deploy bundled tool '{}': {}", name, e.toString());
            if (isComplete(dest, version)) {
                return dest;
            }
            return Files.isDirectory(legacy) ? legacy : null;
        }
    }

    /**
     * <b>One directory per bundle version</b>, and that is what makes this safe on a machine running
     * several checkouts at once.
     *
     * <p>It used to be a single {@code tools/<name>/} rewritten in place whenever the sha changed:
     * delete the old directory, then move the new one in. Correct for one process and broken for
     * two, in two ways at once — and this machine runs a dozen worktrees against one home directory,
     * with three different bundles live at the time this was found:
     *
     * <ul>
     *   <li><b>The swap had a hole in it.</b> Between the delete and the move the tool did not exist,
     *       so another run reaching for it got
     *       {@code can't open file '.../cbscript/compile_once.py'} — six unrelated GameTests red,
     *       none of them naming a tool.</li>
     *   <li><b>The staging directory was shared too</b> ({@code .cbscript.extracting}), so two
     *       processes unpacked into the same place and one deleted the other's half-written files:
     *       {@code NoSuchFileException: .cbscript.extracting/Scripts/math.cblib}.</li>
     * </ul>
     *
     * <p>Content-addressing removes both. Nothing is ever deleted, so nothing can vanish underneath a
     * reader; two checkouts with different bundles simply use different directories instead of
     * flipping one back and forth on every run. It is the same idea as the {@code .version} marker,
     * moved from "check whether this is current" to "name it by what it is".
     *
     * <p>Old copies are deliberately <b>not</b> tidied up: pruning is the delete this fix exists to
     * remove, and another process may be running out of the very directory being cleaned. They are a
     * few MB apiece and a bundle changes rarely.
     */
    static Path versionedDir(Path root, String name, String version) {
        return root.resolve(name + "-" + shortVersion(version));
    }

    private static String shortVersion(String version) {
        return version.length() > 12 ? version.substring(0, 12) : version;
    }

    /**
     * Unpacked AND finished.
     *
     * <p>The marker is written last, so a directory without one is a crashed or in-flight extraction
     * rather than a usable tool — which matters more now that a directory is never replaced.
     */
    private static boolean isComplete(Path dir, String version) {
        try {
            Path marker = dir.resolve(".version");
            return Files.isDirectory(dir) && Files.isRegularFile(marker)
                    && version.equals(Files.readString(marker, StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            return false;
        }
    }

    private static void unzipInto(byte[] zipBytes, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path root = dir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = root.resolve(entry.getName()).normalize();
                // Zip-slip guard: never write outside the target directory.
                if (!out.startsWith(root)) {
                    throw new IOException("refusing zip entry outside target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort — a leftover temp file can't break the deploy
                }
            });
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));   // SHA-256 always exists
        }
    }
}
