package com.moddingfromamod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.moddingfromamod.testing.SourceRoots;
import org.junit.jupiter.api.Test;

/**
 * <b>{@link UsesCorpus} is on exactly the test classes that read a corpus</b> (§5). A build that runs the core's
 * corpus tests with its own real corpus selects them by that tag, so a reader without it would never get the real corpus
 * there, and a tag on a class that reads nothing would claim coverage it does not give. Reads the test sources as text,
 * wherever the build compiles them from ({@link SourceRoots}).
 */
class UsesCorpusTagTest {

    /** A read through the corpus switch: its path, its text, its lines, the real builds, or which dataset it is. */
    private static final Pattern READS = Pattern.compile("\\bCorpus" + "Source\\.(path|read|lines|realBuilds|real)\\b");
    private static final Pattern TAGGED = Pattern.compile("(?m)^@UsesCorpus\\s*$");

    @Test
    void everyCorpusReaderIsTaggedAndNoOtherClassIs() throws IOException {
        List<String> untagged = new ArrayList<>();
        List<String> tagged = new ArrayList<>();
        int readers = 0;
        for (Path root : SourceRoots.TEST_JAVA) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String name = f.getFileName().toString();
                    if (name.equals("CorpusSource.java") || name.equals("UsesCorpus.java") || name.equals("UsesCorpusTagTest.java")) {
                        continue;
                    }
                    String src = Files.readString(f, StandardCharsets.UTF_8);
                    boolean reads = READS.matcher(src).find();
                    boolean isTagged = TAGGED.matcher(src).find();
                    readers += reads ? 1 : 0;
                    if (reads && !isTagged) {
                        untagged.add(root.relativize(f).toString());
                    } else if (isTagged && !reads) {
                        tagged.add(root.relativize(f).toString());
                    }
                }
            }
        }
        assertTrue(readers > 10, "only " + readers + " corpus readers found: the sweep is looking in the wrong place");
        assertEquals(List.of(), untagged, "these read a corpus through CorpusSource and carry no @UsesCorpus");
        assertEquals(List.of(), tagged, "these carry @UsesCorpus and read no corpus through CorpusSource");
    }
}
