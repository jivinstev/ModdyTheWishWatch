package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.moddingfromamod.wish.AttributeIds;
import org.junit.jupiter.api.Test;

/**
 * <b>A datapack is the WORST place to get an attribute id wrong, and it was the one place nothing
 * corrected it.</b>
 *
 * <p>{@code AttributeIds} exists because a model writes {@code minecraft:scale} — 1.21.2's name for
 * what 1.21.1 calls {@code minecraft:generic.scale} — again and again, and has written the right
 * answer in its own handover notes and then got it wrong in a later wish. Its header says it is
 * "one choke point covering both paths, and any future caller". Datapacks were the future caller,
 * and the failure is far quieter there:
 *
 * <ul>
 *   <li>in a command the id comes straight back as <i>"Can't find element 'minecraft:scale'"</i>,
 *       so the agent fixes it and moves on;</li>
 *   <li>in a datapack CBScript compiles any command text at all, Minecraft drops the function at
 *       PACK LOAD, and the pack reports enabled. The rule simply does nothing, with one line in the
 *       server log and nothing the agent or the child can see.</li>
 * </ul>
 *
 * <p>Measured: a "make zombies bigger" standing rule compiled, activated, reported enabled, and left
 * every zombie exactly the size it was — through a green compile gate.
 */
class DatapackAttributeIdsTest {

    /** This build's registry, as far as these tests are concerned. */
    private static final Set<String> REAL = Set.of(
            "minecraft:generic.scale", "minecraft:generic.max_health",
            "minecraft:generic.movement_speed", "minecraft:generic.attack_damage");

    @Test
    void aWholeCbscriptProgramIsCorrected_notJustASingleCommand() {
        String program = """
                clock tick
                    as @e[type=zombie, tag=!my_rule] at @s
                        /tag @s add my_rule
                        /attribute @s minecraft:scale base set 2.0
                        /attribute @s minecraft:max_health base set 40
                    end
                end
                """;
        AttributeIds.Fixed fixed = AttributeIds.correct(program, REAL::contains);
        assertTrue(fixed.command().contains("minecraft:generic.scale"), fixed.command());
        assertTrue(fixed.command().contains("minecraft:generic.max_health"), fixed.command());
        assertFalse(fixed.command().contains(" minecraft:scale "), fixed.command());
        assertTrue(fixed.notes().size() == 2, "both corrections must be reported: " + fixed.notes());
    }

    @Test
    void aProgramThatIsAlreadyRightIsLeftAlone() {
        // The correction can only ever turn a command that was certain to fail into the one the
        // author meant. On a build where the short name is real it must do nothing at all.
        String program = "clock tick\n    /attribute @s minecraft:generic.scale base set 2.0\nend\n";
        AttributeIds.Fixed fixed = AttributeIds.correct(program, REAL::contains);
        assertTrue(fixed.notes().isEmpty());
        assertTrue(fixed.command().equals(program));
    }

    @Test
    void authorDatapackRunsTheCorrectorAndSaysSo() throws IOException {
        // A source check, because the alternative is booting a game to prove one call still exists —
        // and the failure mode is precisely that somebody tidies the call away and every gate stays
        // green while agent-written rules quietly stop working again.
        Path tool = Path.of("src/main/java/com/moddingfromamod/agent/tools/AuthorDatapackTool.java");
        String src = Files.readString(tool);
        // It reaches the corrector through CommandDialect, the one choke point that also fixes the
        // custom_model_data spelling per target — so both links are asserted.
        assertTrue(src.contains("CommandDialect.correct"),
                "author_datapack must repair renamed attribute ids before compiling, or a rule "
                        + "written with the id a model actually reaches for is dropped silently at "
                        + "pack load");
        String dialect = Files.readString(
                Path.of("src/main/java/com/moddingfromamod/wish/CommandDialect.java"));
        assertTrue(dialect.contains("AttributeIds.correct"),
                "CommandDialect must still run the attribute-id corrector");
        assertTrue(src.contains("Corrected "),
                "…and must TELL the agent, or it never learns the id and writes it again next time "
                        + "— a correction nobody can see is indistinguishable from having been right");
    }
}
