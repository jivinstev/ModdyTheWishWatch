package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * <b>Every particle and sound an ability template makes has a hidden-creature twin</b> —.
 *
 * <p>{@code VeilEffectsGameTest} proves the routing on the breath; this proves no template was
 * left out and none can be added without it. A line that plays a particle or a sound with no
 * {@code mfam_veiled} condition is fire out of empty air in front of a child who cannot see the
 * creature yet, and nothing else would notice.
 */
class VeilEffectsTemplateTest {

    private static final Path EFFECTS = Path.of("src/main/resources/effects");

    private static boolean emits(String line) {
        String t = line.strip();
        return !t.startsWith("#") && (t.contains(" run particle ") || t.contains(" run playsound "));
    }

    @Test
    void everyParticleAndSoundLineIsSplitOnTheVeil() throws IOException {
        List<String> bare = new ArrayList<>();
        int shown = 0;
        int hidden = 0;
        try (Stream<Path> files = Files.list(EFFECTS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".cbs")).sorted().toList()) {
                for (String line : Files.readAllLines(f)) {
                    if (!emits(line)) {
                        continue;
                    }
                    if (line.contains("/execute unless entity @e[tag=${TAG},tag=mfam_veiled,limit=1] ")) {
                        shown++;
                    } else if (line.contains("/execute if entity @e[tag=${TAG},tag=mfam_veiled,limit=1] ")
                            && line.contains("@a[tag=!mfam_veil_blind]")) {
                        hidden++;
                    } else {
                        bare.add(f.getFileName() + ": " + line.strip());
                    }
                }
            }
        }
        assertTrue(bare.isEmpty(), "particle/sound lines with no veil twin — a hidden creature "
                + "would show the child these:\n" + String.join("\n", bare));
        assertEquals(shown, hidden, "every veiled-off line needs its veiled-on twin, or the "
                + "camera (or the child after the reveal) loses the effect");
        assertTrue(shown >= 11, "the effects templates held 11 such lines when this was written; "
                + "found " + shown + " — is this reading the right directory?");
    }
}
