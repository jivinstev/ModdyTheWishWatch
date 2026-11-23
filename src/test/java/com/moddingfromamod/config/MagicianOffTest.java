package com.moddingfromamod.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * item 4: <b>Magician off means no subprocesses</b>. With no override and nothing turned on (the public mod's
 * single-player default), every door to an AI program is shut — even with a usable {@code claude} and {@code muse}
 * pointed at directly, which is the case PORT_26_2.md found starting idle {@code claude --bg} workers.
 */
class MagicianOffTest {

    @TempDir
    Path dir;

    private String cliWas;
    private String museWas;

    @BeforeEach
    void publicModWithAUsableCliOnTheMachine() throws Exception {
        ModdySettings.overridesForTest(List.of());
        PlayerSettings.shared().clearForTest();
        Path fake = dir.resolve("claude");
        Files.writeString(fake, "#!/bin/sh\necho started >> " + dir.resolve("ran") + "\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"));
        cliWas = System.getProperty("moddingfromamod.cli");
        museWas = System.getProperty("moddingfromamod.muse.bin");
        System.setProperty("moddingfromamod.cli", fake.toString());
        System.setProperty("moddingfromamod.muse.bin", fake.toString());
    }

    @AfterEach
    void putBack() {
        restore("moddingfromamod.cli", cliWas);
        restore("moddingfromamod.muse.bin", museWas);
        ModdySettings.overridesForTest(null);
        PlayerSettings.shared().clearForTest();
    }

    private static void restore(String k, String v) {
        if (v == null) {
            System.clearProperty(k);
        } else {
            System.setProperty(k, v);
        }
    }

    @Test
    void offByDefaultEveryDoorIsShut() throws Exception {
        assertFalse(Magician.enabled(), "Magician is off by default");
        assertNull(com.moddingfromamod.agent.ClaudeCli.resolve(), "a CLI was looked for with Magician off");
        assertNull(com.moddingfromamod.agent.MuseExec.binary(), "Muse Code was looked for with Magician off");
        assertFalse(com.moddingfromamod.agent.AgentSession.shouldRun(), "the wish pool would start");
        var grade = com.moddingfromamod.safety.SafetyGrader.gradeInput("a dragon made of cake");
        assertTrue(grade.isDone(), "a grade was started");
        assertFalse(com.moddingfromamod.safety.SafetyGrader.startedFor("a dragon made of cake"),
                "a model grade was started with Magician off");
        assertNull(com.moddingfromamod.agent.MuseExec.text(null, "hi", "muse-ember-1.0", "low", 5_000),
                "a Muse call was made with Magician off");
        assertFalse(Files.exists(dir.resolve("ran")), "a program ran");
    }

    @Test
    void turningItOnOpensThem() {
        PlayerSettings.shared().set(SettingsCatalog.MAGICIAN, "on");
        assertTrue(Magician.enabled());
        assertTrue(com.moddingfromamod.agent.ClaudeCli.resolve() != null, "Magician on, yet no CLI");
        assertTrue(com.moddingfromamod.agent.MuseExec.binary() != null, "Magician on, yet no Muse");
    }
}
