package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import org.junit.jupiter.api.Test;

/** #132: a sculpt's stored body is its shape, so its stand-in looks like it. */
class SculptBodyTest {

    private static CreatureSculpt.Part box(String name, float x0, float y0, float z0, float x1, float y1, float z1) {
        return new CreatureSculpt.Part(name, null, new float[]{0, 0, 0}, new float[]{x0, y0, z0},
                new float[]{x1, y1, z1}, 0xFFFFFF);
    }

    @Test
    void theStandInShapesReadBackAsThemselves() {
        for (Species.Body b : List.of(Species.Body.HUMANOID, Species.Body.QUADRUPED, Species.Body.SPIDER,
                Species.Body.SLIME)) {
            assertEquals(b, SculptBody.of(StandInSculpt.forBody(b, 0xFFFFFF)), b + "'s own stand-in");
        }
    }

    @Test
    void aWingedSculptIsABird() {
        List<CreatureSculpt.Part> eagle = new ArrayList<>(StandInSculpt.forBody(Species.Body.HUMANOID, 0xFFFFFF));
        eagle.removeIf(p -> p.name().startsWith("arm"));
        eagle.add(box("wing_left", -20, 18, -2, -4, 20, 4));
        eagle.add(box("wing_right", 4, 18, -2, 20, 20, 4));
        assertEquals(Species.Body.BIRD, SculptBody.of(eagle));
    }

    @Test
    void nothingToReadStandsInAsAPerson() {
        assertEquals(Species.Body.HUMANOID, SculptBody.of(List.of()));
        assertEquals(Species.Body.HUMANOID, SculptBody.of(null));
    }
}
