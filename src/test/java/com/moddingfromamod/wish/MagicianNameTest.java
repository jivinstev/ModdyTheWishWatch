package com.moddingfromamod.wish;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The typed-wish tier is the Wish MAGICIAN, never the Wish Wizard</b>. Moddy is the wish watch,
 * and on the site he is owned by a magician; the two magics are the Apprentice's and the Magician's. This sweeps what
 * a player, a grown-up or the agent can read (the mod's code and resources, and
 * any web pages, world files and server tools beside it) for the old name, so it cannot creep back in.
 *
 * <p>A wizard as a THING a child wishes for (a wizard tower, a wizard hat, a wizard creature) is content and stays:
 * only the capitalised name of the tier, "Wish Wizard" in any case and the old sign-up address are refused. The one
 * place the old address may stand is the website, where {@code /wizard} still redirects for older games.
 */
class MagicianNameTest {

    private static final List<Path> ROOTS = Stream.concat(SourceRoots.MAIN_JAVA.stream(), Stream.of(
            Path.of("src/main/resources/lessons"), Path.of("site"), Path.of("account"), Path.of("tower"),
            Path.of("worlds"), Path.of("tools/home"))).toList();
    private static final Pattern TEXT = Pattern.compile(".*\\.(java|py|sh|js|html|css|json|md|txt)$");
    /** The tier's name: "Wizard", "Wizards", "WIZARD…" as a word, and not the name of a building. */
    private static final Pattern OLD = Pattern.compile(
            "(?i:wish\\s+wizards?)|\\bWizards?\\b(?! (?:Tower|Castle))|\\bWIZARD|(?i:playmoddy\\.com/wizard)");

    @Test
    @DisplayName("nothing a player, a grown-up or the agent reads calls the typed-wish tier a Wizard")
    void theTierIsTheMagician() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(Files::isRegularFile).filter(p -> TEXT.matcher(p.toString()).matches()).toList()) {
                    if (f.toString().contains("node_modules") || f.toString().contains("__pycache__")) {
                        continue;
                    }
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    } catch (java.nio.charset.MalformedInputException e) {
                        continue;
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        if (OLD.matcher(lines.get(i)).find()) {
                            found.add(f + ":" + (i + 1) + ": " + lines.get(i).strip());
                        }
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), "the typed-wish tier is the Wish Magician; say Magician here:\n"
                + String.join("\n", found));
    }
}
