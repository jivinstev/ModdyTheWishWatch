package com.moddingfromamod.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>The vendored CBScript compiler says where it came from</b>. {@code third_party/moddy-cbscript} is the compiler
 * (the Java port) built into the mod's jar. {@code tools/refresh-cbscript-bundle.sh} fetches
 * {@code jivinstev/moddy-cbscript} at a branch or commit, vendors it, and writes {@code tools/cbscript.SOURCE}: the
 * repo, the branch, the commit and the licence, and tree-sha256 (every vendored file's path and bytes, sorted), so
 * this test can tell offline that the SOURCE still describes the tree beside it.
 */
class CbScriptBundleSourceTest {

    static final Path TREE = Path.of("third_party/moddy-cbscript");
    static final Path SOURCE = Path.of("src/main/resources/tools/cbscript.SOURCE");
    static final String REPO = "https://github.com/jivinstev/moddy-cbscript";
    private static final String FIX = "write it with ./tools/refresh-cbscript-bundle.sh --source-only --ref <the commit "
            + "the vendored tree is>, or re-vendor with ./tools/refresh-cbscript-bundle.sh";

    static Map<String, String> source() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(SOURCE)) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                out.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return out;
    }

    /** The same digest as tree_sha256 in refresh-cbscript-bundle.sh. */
    static String treeSha256(Path root) throws IOException, NoSuchAlgorithmException {
        List<String> paths = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile).forEach(p -> paths.add(root.relativize(p).toString().replace('\\', '/')));
        }
        // Python sorts str by code point; for these ASCII paths that is String's natural order.
        paths.sort(null);
        MessageDigest h = MessageDigest.getInstance("SHA-256");
        for (String p : paths) {
            h.update(p.getBytes(StandardCharsets.UTF_8));
            h.update((byte) 0);
            h.update(Files.readAllBytes(root.resolve(p)));
            h.update((byte) 0);
        }
        return HexFormat.of().formatHex(h.digest());
    }

    @Test
    void theCompilerNamesTheRepoTheBranchTheCommitAndTheLicence() throws IOException {
        assertTrue(Files.isDirectory(TREE.resolve("src/main/java/com/moddingfromamod/cbscript")),
                "no vendored compiler at " + TREE);
        assertTrue(Files.isRegularFile(SOURCE),
                "the vendored compiler has no " + SOURCE.getFileName() + ", so nobody can say which commit ships: " + FIX);
        Map<String, String> s = source();
        assertEquals(REPO, s.get("repo"), "the compiler is vendored from jivinstev/moddy-cbscript: " + s);
        assertTrue(s.getOrDefault("branch", "").matches("[A-Za-z0-9._/-]+"), "a branch: " + s);
        assertTrue(s.getOrDefault("commit", "").matches("[0-9a-f]{40}"), "a full 40-hex commit: " + s);
        assertEquals("MPL-2.0", s.get("license"), "CBScript is MPL-2.0: " + s);
        assertEquals(TREE.toString(), s.get("tree"), "the SOURCE names the vendored tree: " + s);
        assertFalse("true".equals(s.get("local")),
                "a compiler vendored from a local checkout (CBSCRIPT_DIR) is for trying a fix; ship one fetched from "
                        + "the repo: " + FIX);
        assertTrue(Files.isRegularFile(TREE.resolve("LICENSE")) && Files.isRegularFile(TREE.resolve("NOTICE.md")),
                "the licence and the attribution travel with the code");
    }

    @Test
    void theSourceDescribesTheTreeBesideIt() throws IOException, NoSuchAlgorithmException {
        // The commit cannot be checked without the network, but the tree can: a file edited by hand no longer has
        // the digest the SOURCE recorded, so the commit it names is no longer known to be what ships.
        String recorded = source().getOrDefault("tree-sha256", "");
        assertTrue(recorded.matches("[0-9a-f]{64}"), "the SOURCE records the tree's sha256 (tree-sha256): " + FIX);
        assertEquals(recorded, treeSha256(TREE), "the vendored tree is not the one its SOURCE describes (edited by "
                + "hand? fix the compiler in moddy-cbscript and re-vendor): " + FIX);
    }
}
