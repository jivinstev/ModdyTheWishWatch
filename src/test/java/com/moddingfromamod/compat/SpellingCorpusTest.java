package com.moddingfromamod.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The 1.21.5 spelling corpus: 117 distinct lines out of the two live worlds' stored packs that
 * {@link ComponentSpelling} rewrote, and a 1.21.8 server then loaded. The bundled compiler's
 * {@code component_syntax.py} is held to the SAME file by {@code tools/test-spelling-parity.py} — the two
 * had drifted by 43 lines (sign messages, CustomName) before this pinned them.
 */
class SpellingCorpusTest {

    @Test
    void everyRealLineIsCorrectedAndTheNewSpellingIsLeftAlone() throws Exception {
        // The real lines come from the live worlds' packs and stay private (corpus/compat/); the public fixture is
        // hand-written pairs, one or more per rule. The floor is each dataset's own.
        List<String> rows = com.moddingfromamod.CorpusSource.lines("compat/spelling-1.21.5.jsonl");
        int floor = com.moddingfromamod.CorpusSource.real() ? 100 : 10;
        assertTrue(rows.size() >= floor, "the corpus lost rows: " + rows.size() + " (floor " + floor + ")");
        List<String> bad = new ArrayList<>();
        for (String line : rows) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject r = JsonParser.parseString(line).getAsJsonObject();
            String old = r.get("old").getAsString();
            String neu = r.get("new").getAsString();
            if (!ComponentSpelling.correct(old, new ArrayList<>()).equals(neu)) {
                bad.add("not corrected: " + old);
            }
            if (!ComponentSpelling.correct(neu, new ArrayList<>()).equals(neu)) {
                bad.add("new spelling changed again: " + neu);
            }
        }
        assertEquals(List.of(), bad.size() > 5 ? bad.subList(0, 5) : bad);
    }
}
