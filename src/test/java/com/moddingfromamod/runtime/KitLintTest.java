package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * F1 grading: every ready-made part comes out WHOLE (no box floating off it once turned) and
 * EVEN (a mirror image of itself across the creature's middle), on a head, a long body and a tall body.
 */
class KitLintTest {

    static final List<CreatureSculpt.Part> SHAPES = List.of(
            new CreatureSculpt.Part("head", null, new float[]{0, 10, 0}, new float[]{-4, 10, -4}, new float[]{4, 18, 4}, 0xFF8844),
            new CreatureSculpt.Part("body", null, new float[]{0, 6, 0}, new float[]{-4, 6, -9}, new float[]{4, 13, 9}, 0xFF8844),
            new CreatureSculpt.Part("body", null, new float[]{0, 2, 0}, new float[]{-5, 2, -3}, new float[]{5, 22, 3}, 0xFF8844));

    static List<String> lint() {
        List<String> out = new ArrayList<>();
        for (String kit : SculptKits.NAMES) {
            List<String> styles = SculptKits.STYLES.getOrDefault(kit, List.of(""));
            for (String style : styles) {
                for (CreatureSculpt.Part anchor : SHAPES) {
                    JsonObject o = new JsonObject();
                    o.addProperty("kit", kit);
                    o.addProperty("on", anchor.name());
                    if (!style.isEmpty()) {
                        o.addProperty("style", style);
                    }
                    JsonArray a = new JsonArray();
                    a.add(o);
                    List<CreatureSculpt.Part> made = SculptKits.expand(a, List.of(anchor));
                    String shape = anchor.to()[1] - anchor.from()[1] > 15 ? "tall" : anchor.to()[2] - anchor.from()[2] > 12 ? "long" : "head";
                    for (String p : KitLint.problems(made, anchor)) {
                        out.add(kit + (style.isEmpty() ? "" : "/" + style) + " on a " + shape + ": " + p);
                    }
                }
            }
        }
        return out;
    }

    @Test
    void everyKitAndStyleIsWholeAndEven() {
        List<String> bad = lint();
        assertTrue(bad.isEmpty(), bad.size() + " problem(s):\n" + String.join("\n", bad));
    }

    @Test
    void aTurnedPartThatFloatsIsSaidAndALopsidedOneIsNot() {
        var body = new CreatureSculpt.Part("body", null, new float[]{0, 0, 0}, new float[]{-4, 0, -4}, new float[]{4, 8, 4}, 1);
        // An ear turned about its own base far enough to swing clear of the head (+z leans it out, toward +x, as the game turns it).
        var ear = new CreatureSculpt.Part("ear", null, new float[]{6, 8, 0}, new float[]{5, 8, -1}, new float[]{7, 12, 1}, 1,
                new float[]{0, 0, 60}, 0f, "", 0);
        assertTrue(!KitLint.looksApart(List.of(body, ear), 1.0, 3).isEmpty(), "a floating turned ear is said");
        // One big claw on one side only, touching: lopsided on purpose, and nothing to say.
        var claw = new CreatureSculpt.Part("claw", null, new float[]{4, 2, 0}, new float[]{3, 2, -1}, new float[]{8, 4, 1}, 1,
                new float[]{0, 0, 10}, 0f, "", 0);
        assertTrue(KitLint.looksApart(List.of(body, claw), 1.0, 3).isEmpty(), "a lopsided design is not corrected");
    }
}
