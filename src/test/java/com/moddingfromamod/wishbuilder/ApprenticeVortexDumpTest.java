package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The vortex wishes, typed into the Apprentice</b>: each wish in {@code corpus/vortex/wishes.jsonl}
 * is matched, its TOP tile is pressed through the Builder and lowered, and the {@code stage_event} it would make is
 * written to {@code build/apprentice/vortex-top.jsonl}. {@code python3 tools/vortex-eval.py apprentice} grades that
 * file against the same expectations the Magician is graded on.
 *
 * <p>This is the word matcher (the deterministic tier, no sentence model). Its floor is asserted here so a later tree
 * change that loses the vortex words goes red at Gate A: most of the wishes must reach a spinning tile at all.
 */
class ApprenticeVortexDumpTest {

    private static final Path CORPUS = Path.of("corpus/vortex/wishes.jsonl");
    private static final Path OUT = Path.of("build/apprentice/vortex-top.jsonl");

    @Test
    @DisplayName("a typed vortex wish's top tile is a spinning make, and what it lowers to is written for grading")
    void vortexWishesReachASpinningTile() throws IOException {
        BuilderTree tree = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        Apprentice apprentice = new Apprentice(tree, Apprentice.english());
        List<String> rows = new ArrayList<>();
        int spinning = 0;
        int total = 0;
        for (String line : Files.readAllLines(CORPUS, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject w = JsonParser.parseString(line).getAsJsonObject();
            total++;
            Apprentice.Answer a = apprentice.match(w.get("wish").getAsString());
            JsonObject row = new JsonObject();
            row.addProperty("id", w.get("id").getAsString());
            if (a.offers().isEmpty()) {
                row.addProperty("tile", "(none)");
                rows.add(row.toString());
                continue;
            }
            Apprentice.Pick top = a.offers().get(0);
            row.addProperty("tile", ApprenticeWords.label(tree, top));
            try {
                BuilderSpec spec = ApprenticeMake.spec(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, tree, top);
                for (BuilderLowering.Call c : BuilderLowering.lower(spec, "vortex_eval", LibraryPresets.SHIPPED)) {
                    if (c.tool().equals("stage_event")) {
                        row.add("args", c.args());
                        if ("vortex".equals(c.args().has("motion") ? c.args().get("motion").getAsString() : "")) {
                            spinning++;
                        }
                    }
                }
            } catch (RuntimeException e) {
                row.addProperty("error", String.valueOf(e.getMessage()));
            }
            rows.add(row.toString());
        }
        Files.createDirectories(OUT.getParent());
        Files.write(OUT, rows, StandardCharsets.UTF_8);
        // A floor, not a target: the matcher reads words, and a few wishes in the set are deliberately hard.
        assertTrue(spinning * 100 >= total * 75, "only " + spinning + " of " + total
                + " typed vortex wishes reached a spinning tile (python3 tools/vortex-eval.py apprentice says which)");
    }
}
