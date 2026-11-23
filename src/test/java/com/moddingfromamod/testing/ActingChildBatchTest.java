package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ACTING child ({@code MockPlayerNetwork.actingChildFor}) is one shared stand-in, and GameTests in one batch run
 * at the same time: a test that sets it in the default batch makes every concurrent "for the child" tool act round
 * ITS child. an earlier issue's Follows-you test did, and 12 ModifyMob/MobIdentity tests found "no mobs within 24 blocks of the
 * child" (full Gate B, 2026-11-15). So a GameTest that sets the acting child names its own batch.
 */
class ActingChildBatchTest {

    static final Pattern TEST = Pattern.compile("@GameTest\\(([^)]*)\\)");

    @Test
    @DisplayName("a GameTest that sets the acting child runs in a named batch, never the default one")
    void actingChildTestsNameABatch() throws Exception {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = SourceRoots.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith("GameTest.java"))::iterator) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = TEST.matcher(src);
                List<int[]> at = new ArrayList<>();
                List<String> args = new ArrayList<>();
                while (m.find()) {
                    at.add(new int[] {m.start(), m.end()});
                    args.add(m.group(1));
                }
                for (int i = 0; i < at.size(); i++) {
                    String body = src.substring(at.get(i)[1], i + 1 < at.size() ? at.get(i + 1)[0] : src.length());
                    if (body.contains("actingChildFor(") && !args.get(i).contains("batch")) {
                        bad.add(f.getFileName() + " @" + src.substring(0, at.get(i)[0]).split("\n", -1).length);
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), "these GameTests set the shared acting child in the default batch, where every "
                + "concurrent test then acts round their child — give each a batch: " + bad);
    }
}
