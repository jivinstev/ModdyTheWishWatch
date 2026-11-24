package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ReachFromBodyFix#reaches}: how far a step's or a landing's victims are picked, read the same from a pack
 * compiled before the flank rule (no marker) and from one compiled after it (marker), so the starter regen can
 * compare a fresh render with what an install makes of the shipped pack.
 */
class ReachFromBodyFixTest {

    private static final String OLD = "execute as @e[tag=wishmob_rex,nbt={OnGround:1b},scores={mfam_landair=10..}] "
            + "at @s as @e[distance=..4,tag=!wishmob_rex,type=!minecraft:player] run data modify entity @s Motion "
            + "set value [0.0d,0.45d,0.0d]";

    @Test
    void aPreMarkerLandingIsWidenedAndReadsTheSameAsAFreshRender() {
        List<String> notes = new ArrayList<>();
        String fixed = ReachFromBodyFix.fix("# tick\n" + OLD + "\n", notes, s -> s.equals("rex") ? 1.5 : 0.0);
        assertEquals(List.of("wishmob_rex ..5.5"), ReachFromBodyFix.reaches(fixed));
        assertEquals(1, notes.size());
        String fresh = "execute as @e[tag=wishmob_rex,nbt={OnGround:1b},scores={mfam_landair=10..}] at @s as "
                + "@e[distance=..5.5,tag=!wishmob_rex,tag=!mfam_flank,type=!minecraft:player] run say hi";
        assertEquals(ReachFromBodyFix.reaches(fixed), ReachFromBodyFix.reaches(fresh));
    }

    @Test
    void anUnknownSpeciesAndAMarkedLineAreLeftAsTheyReach() {
        assertEquals(List.of("wishmob_rex ..4"),
                ReachFromBodyFix.reaches(ReachFromBodyFix.fix(OLD, new ArrayList<>(), s -> 0.0)));
        String marked = OLD.replace(",type=!minecraft:player", ",tag=!mfam_flank,type=!minecraft:player");
        assertEquals(List.of("wishmob_rex ..4"),
                ReachFromBodyFix.reaches(ReachFromBodyFix.fix(marked, new ArrayList<>(), s -> 9.0)));
    }

    @Test
    void aStepIsReadAndAnythingElseIsNot() {
        String step = "execute as @e[tag=wishmob_cat,scores={mfam_stepw=1..}] at @s as @e[distance=..2.5,tag=!wishmob_cat]"
                + " run damage @s 1";
        assertEquals(List.of("wishmob_cat ..2.5"), ReachFromBodyFix.reaches("say a\n" + step + "\nsay b"));
        assertEquals(List.of(), ReachFromBodyFix.reaches("execute as @e[distance=..4] run say x"));
    }
}
