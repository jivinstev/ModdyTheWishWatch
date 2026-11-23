package com.moddingfromamod.likeness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.reference.ReferenceSpec;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptKits;
import org.junit.jupiter.api.Test;

/**
 * Score the sculpts a live A/B produced, with the same graders that scored the baseline.
 *
 * <pre>
 *   MFAM_AB_DIRS=/tmp/mfam-ab-A:/tmp/mfam-ab-B ./gradlew test --tests '*AbScoreTest' -i
 * </pre>
 *
 * <p><b>Kits are expanded before scoring, exactly as the game expands them.</b> A captured sculpt is
 * the tool's <i>arguments</i> — the hand-written {@code parts} plus a {@code kits} list — not the
 * finished creature. Scoring the raw arguments would credit arm B with only the boxes it typed and
 * none of the geometry the kits built, which is precisely the work the kits exist to do.
 *
 * <p>Skips when the directories are absent, so it stays green on a clean checkout.
 */
class AbScoreTest {

    @Test
    void scoreEachArm() throws IOException {
        String dirs = System.getenv("MFAM_AB_DIRS");
        if (dirs == null || dirs.isBlank()) {
            System.out.println("[ab] no MFAM_AB_DIRS, skipping");
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append(LikenessReport.header()).append('\n');
        for (String d : dirs.split(":")) {
            Path dir = Path.of(d.trim());
            if (!Files.isDirectory(dir)) {
                System.out.println("[ab] missing " + dir);
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().contains("index"))
                        .sorted().toList();
            }
            for (Path f : files) {
                JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                if (!o.has("parts")) {
                    continue;
                }
                // Expand kits, so arm B is scored on the creature that was actually built.
                List<CreatureSculpt.Part> parts = SculptCreatureTool.withKits(
                        CreatureSculpt.parseParts(o.getAsJsonArray("parts")), o);
                String id = f.getFileName().toString().replace(".json", "");
                ReferenceSpec ref = reference(id);
                LikenessReport r = LikenessReport.of(parts, ref);
                out.append(r.row(id)).append('\n');
                System.out.println("[ab] " + id + "  parts=" + parts.size()
                        + " kit=" + SculptKits.usedBy(parts)
                        + " rotated=" + parts.stream().filter(CreatureSculpt.Part::rotated).count()
                        + " patterned="
                        + parts.stream().filter(CreatureSculpt.Part::patterned).count()
                        + "  G2=" + r.palette() + "  G3=" + r.features());
            }
        }
        Path target = Path.of("build", "ab-likeness.tsv");
        Files.createDirectories(target.getParent());
        Files.writeString(target, out.toString());
        System.out.println("[ab] -> " + target.toAbsolutePath());
    }

    /** The cached reference for whatever character this file is of. */
    private static ReferenceSpec reference(String id) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        int us = key.lastIndexOf("__");
        if (us >= 0) {
            key = key.substring(us + 2);
        }
        List<Path> roots = new ArrayList<>(List.of(
                Path.of(System.getProperty("user.home"), ".moddingfromamod", "reference-cache"),
                Path.of(System.getProperty("user.home"), "mfam-eval-corpus", "reference-cache")));
        for (Path root : roots) {
            Path f = root.resolve(key + ".json");
            if (Files.isRegularFile(f)) {
                return ReferenceSpec.parse(Files.readString(f));
            }
        }
        return null;
    }
}
