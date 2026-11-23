package com.moddingfromamod.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The bundled CBScript compiler says where it came from</b>. {@code tools/cbscript.zip} is the compiler a
 * player's game runs, and it used to be zipped from whatever checkout sat on someone's disk, so nobody could say which
 * commit shipped. {@code tools/refresh-cbscript-bundle.sh} fetches {@code jivinstev/moddy-cbscript} at a branch or
 * commit and writes {@code tools/cbscript.SOURCE} BESIDE the zip (so recording it never changes the zip's bytes, which
 * the precompiled ready-mades fingerprint): the repo, the branch, the commit and the licence.
 */
class CbScriptBundleSourceTest {

    static final Path BUNDLE = Path.of("src/main/resources/tools/cbscript.zip");
    static final Path SOURCE = Path.of("src/main/resources/tools/cbscript.SOURCE");
    static final String REPO = "https://github.com/jivinstev/moddy-cbscript";
    private static final String FIX = "write it with ./tools/refresh-cbscript-bundle.sh --source-only (naming the "
            + "commit the shipped zip is), or re-bundle with ./tools/refresh-cbscript-bundle.sh";

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

    @Test
    void theBundleNamesTheRepoTheBranchTheCommitAndTheLicence() throws IOException {
        assertTrue(Files.isRegularFile(BUNDLE), "no bundled compiler at " + BUNDLE);
        assertTrue(Files.isRegularFile(SOURCE),
                "the bundled compiler has no " + SOURCE.getFileName() + ", so nobody can say which commit ships: " + FIX);
        Map<String, String> s = source();
        assertEquals(REPO, s.get("repo"), "the bundle is built from jivinstev/moddy-cbscript: " + s);
        assertTrue(s.getOrDefault("branch", "").matches("[A-Za-z0-9._/-]+"), "a branch: " + s);
        assertTrue(s.getOrDefault("commit", "").matches("[0-9a-f]{40}"), "a full 40-hex commit: " + s);
        assertEquals("MPL-2.0", s.get("license"), "CBScript is MPL-2.0: " + s);
        assertFalse("true".equals(s.get("local")),
                "a bundle built from a local checkout (CBSCRIPT_DIR) is for trying a fix; ship one fetched from the repo: "
                        + FIX);
    }
}
