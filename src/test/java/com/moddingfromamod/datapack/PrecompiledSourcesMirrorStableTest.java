package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The files {@link PrecompiledPacks#fingerprint} reads must come out of the public render byte for byte, or the
 * public build's {@link PrecompiledFingerprintTest} fails while this repo's passes. The render drops issue references
 * () from text, so these files carry none: the same pattern tools/public-tree.py's {@code _ANY} matches.
 */
class PrecompiledSourcesMirrorStableTest {

    private static final Pattern ISSUE_REF =
            Pattern.compile("(?<![\\w&#/=$\"'.\\\\-])#\\d{1,5}(?:-#\\d{1,5})?(?![\\w-])");

    @Test
    void theFingerprintedSourcesCarryNoIssueReferences() throws Exception {
        Path resources = Path.of("src/main/resources");
        List<String> found = new ArrayList<>();
        for (String dir : List.of("effects", "items")) {
            try (var files = Files.list(resources.resolve(dir))) {
                for (Path f : files.sorted().toList()) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".cbs") && !name.equals("catalogue.json")) {
                        continue;
                    }
                    String[] lines = Files.readString(f, StandardCharsets.UTF_8).split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        Matcher m = ISSUE_REF.matcher(lines[i]);
                        if (m.find()) {
                            found.add(dir + "/" + name + ":" + (i + 1) + " " + m.group());
                        }
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), "the public render would rewrite these lines, so the public build's precompiled "
                + "fingerprint would not match; drop the issue reference (say it in the commit instead), then run "
                + "./tools/precompile-ready-mades.sh: " + found);
    }
}
