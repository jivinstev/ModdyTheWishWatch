package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.moddingfromamod.agent.PlayerReach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * the other-player rule over a COMPILED datapack, as the presser. The live orb's function,
 * verbatim, is a visit; anything done to the other child is refused, naming the function and line.
 */
class DatapackReachTest {

    @TempDir
    Path dir;

    private Path zip(String function) throws Exception {
        Path z = dir.resolve("p.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(z))) {
            out.putNextEntry(new ZipEntry("pack.mcmeta"));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new ZipEntry("data/player_tracker/function/use.mcfunction"));
            out.write(function.getBytes(StandardCharsets.UTF_8));
        }
        return z;
    }

    private static final PlayerReach.Who WHO = PlayerReach.Who.presser(List.of("SomePlayer"));

    @Test
    void theLiveOrbIsAVisitAllowedOnlyWhereTheWorldIsOpen() throws Exception {
        Path z = zip("# right-click\nexecute at SomePlayer run tp @s ~ ~ ~\n");
        assertNull(DatapackReach.check(z, WHO, PlayerReach.Mode.OPEN, "a shared world"));
        String off = DatapackReach.check(z, WHO, PlayerReach.Mode.OFF, "a shared world");
        assertTrue(off != null && off.contains("player_tracker:use") && off.contains("a shared world"), off);
    }

    @Test
    void doingSomethingToHimIsRefusedEverywhere() throws Exception {
        for (String line : List.of("tp SomePlayer @s", "execute at SomePlayer run summon tnt ~ ~ ~",
                "give Somebody minecraft:diamond", "$tp $(who) @s", "effect give @a[name=!x] poison 5")) {
            String why = DatapackReach.check(zip(line + "\n"), WHO, PlayerReach.Mode.OPEN, "w");
            if (line.startsWith("$")) {
                continue;   // a macro's argument is filled in at run time: PlayerVisits' half
            }
            assertTrue(why != null && why.startsWith("NOT COMPILED"), line + " → " + why);
        }
    }

    @Test
    void theEffectCataloguesShapesPass() throws Exception {
        Path z = zip("playsound minecraft:block.note_block.flute master @a ~ ~ ~ 1 1\n"
                + "execute as @p[distance=..24] run function ns:give\n"
                + "scoreboard players set Global timer 0\n"
                + "execute as @a[tag=mfam_locate] run tag @s add mfam_locate_player.Bob\n");
        assertNull(DatapackReach.check(z, WHO, PlayerReach.Mode.OFF, "w"));
    }
}
