package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * <b>The public README lists every command the public build registers, and nothing else</b>. It runs in
 * {@code publicTest}: the tree comes from the public classes alone, with no private class and no plug-in
 * ({@link CommandTree}). A command added to the build without its README row fails here by name, and so does a row for
 * a command this build does not have (a private-only one above all).
 *
 * <p>Reads the public README where it is in each tree: {@code docs/public-repo/root/README.md} here,
 * {@code README.md} in the public repo (where {@code docs/public-repo/root/} is the root).
 */
class PublicCommandsReadmeTest {

    static Path readme() {
        Path here = Path.of("docs/public-repo/root/README.md");
        return Files.exists(here) ? here : Path.of("README.md");
    }

    @Test
    void theReadmeListsExactlyThePublicCommandTree() throws Exception {
        TreeSet<String> real = CommandTree.paths(CommandTree.build());
        assertTrue(real.contains("moddy magician") && real.contains("wish"), "the tree came back empty: " + real);
        assertFalse(real.contains("moddyop"), "a private command is in the public build's tree");
        String text = Files.readString(readme());
        TreeSet<String> doc = CommandsReadme.documented(text, "Commands");
        TreeSet<String> missing = new TreeSet<>(real);
        missing.removeAll(doc);
        TreeSet<String> extra = new TreeSet<>(doc);
        extra.removeAll(real);
        assertEquals(new TreeSet<>(), missing, "registered by the public build, missing from the README's Commands");
        assertEquals(new TreeSet<>(), extra, "named in the README's Commands, not registered by the public build");
        assertFalse(text.contains("moddyop"), "the public README names a private command");
    }
}
