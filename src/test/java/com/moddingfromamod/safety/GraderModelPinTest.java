package com.moddingfromamod.safety;

import com.moddingfromamod.agent.MuseGrade;
import com.moddingfromamod.readymade.ReadyMadeLive;
import com.moddingfromamod.world.WorldRulesGrader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every grader names an exact model, never an alias: an alias moves when its
 * vendor moves it, and a grader measured on one model would silently become another
 * (docs/EMBER_GRADERS.md).
 */
class GraderModelPinTest {

    @Test
    void theHaikuSideIsPinnedTo55() {
        assertEquals("claude-haiku-5-5", SafetyGrader.HAIKU_PIN);
        assertEquals(SafetyGrader.HAIKU_PIN, ReadyMadeLive.MODEL, "the ready-made nudge's Haiku fallback");

    }

    @Test
    void theEmberSideIsPinnedTo10() {
        assertEquals("muse:muse-ember-1.0", MuseGrade.EMBER);
        assertEquals(MuseGrade.EMBER, ChildSafetyConfig.DEFAULT_MODEL, "the wish-text grader");
        assertEquals(MuseGrade.EMBER, ChildSafetyConfig.DEFAULT_PLAN_MODEL, "the plan grader");
        assertEquals(MuseGrade.EMBER, WorldRulesGrader.model(), "world rules");
        assertEquals(MuseGrade.EMBER, ReadyMadeLive.model(), "the ready-made nudge");
    }

    @Test
    void noGraderFallsBackToTheHaikuAlias() throws Exception {
        for (String f : new String[] {"world/WorldRulesGrader.java", "reference/CharacterResolver.java",
                "readymade/ReadyMadeLive.java", "safety/SafetyGrader.java"}) {
            String src = Files.readString(Path.of("src/main/java/com/moddingfromamod", f));
            assertFalse(src.contains("() -> \"haiku\"") || src.contains("MODEL = \"haiku\""),
                    f + " names the `haiku` alias as a model");
        }
    }
}
