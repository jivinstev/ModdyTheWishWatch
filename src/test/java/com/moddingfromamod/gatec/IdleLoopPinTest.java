package com.moddingfromamod.gatec;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rig film's idle row samples ONE WHOLE idle loop, so its length must be the sculpted
 * idle clip's. Read as source text because the film is a client-only class: a longer idle written
 * into CreatureFiles without moving the film's constant would bring the half-the-time flake back.
 */
class IdleLoopPinTest {

    private static String read(String path) throws Exception {
        return Files.readString(SourceRoots.file(path));
    }

    @Test
    @DisplayName("the idle row's loop is the sculpted idle clip's animation_length")
    void theIdleRowSamplesTheWholeIdleClip() throws Exception {
        Matcher clip = Pattern.compile("\"animation\\.sculpted\\.idle\"\\s*:\\s*\\{\\s*\"loop\"\\s*:\\s*true,"
                        + "\\s*\"animation_length\"\\s*:\\s*([0-9.]+)")
                .matcher(read("src/main/java/com/moddingfromamod/runtime/CreatureFiles.java"));
        assertTrue(clip.find(), "CreatureFiles no longer writes the sculpted idle clip as it did");
        Matcher film = Pattern.compile("IDLE_LOOP_TICKS\\s*=\\s*(\\d+);")
                .matcher(read("src/main/java/com/moddingfromamod/gatec/VanillaClipsClientTest.java"));
        assertTrue(film.find(), "VanillaClipsClientTest lost IDLE_LOOP_TICKS");
        assertEquals(Math.round(Double.parseDouble(clip.group(1)) * 20), Long.parseLong(film.group(1)),
                "the idle row must sample a whole idle loop");
    }
}
