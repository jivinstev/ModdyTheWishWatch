package com.moddingfromamod.wish;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B: the camera sees in the dark for a take, and a child never does. */
class CameraNightVisionTest {

    @Test
    @DisplayName("a camera gets night vision; a child's borrowed eyes never do, whatever the seam says")
    void onlyACamera() {
        assertTrue(CameraNightVision.appliesTo(false, null));
        assertTrue(CameraNightVision.appliesTo(false, ""));
        assertTrue(CameraNightVision.appliesTo(false, "on"));
        assertFalse(CameraNightVision.appliesTo(true, null), "a child is never given it");
        assertFalse(CameraNightVision.appliesTo(true, "on"), "not even with the seam on");
        assertFalse(CameraNightVision.appliesTo(false, "off"), "the A/B seam turns it off");
        assertFalse(CameraNightVision.appliesTo(false, " OFF "));
    }

    @Test
    @DisplayName("a take never runs into vanilla's flickering last ten seconds")
    void neverFlickers() {
        assertEquals(200, CameraNightVision.FLICKER_TICKS);
        assertTrue(CameraNightVision.TAKE_TICKS >= CameraNightVision.FLICKER_TICKS + 20 * 30,
                "a take of half a minute must end before the flicker begins");
    }

    @Test
    @DisplayName("WIRING: every shot PhotographTool sends goes through shutter, which gives the camera night vision")
    void everyShotGoesThroughTheShutter() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/PhotographTool.java"));
        Matcher m = Pattern.compile("Send\\.to\\(").matcher(src);
        int n = 0;
        while (m.find()) {
            n++;
        }
        assertEquals(1, n, "the only Send.to in PhotographTool is inside shutter() — a shot sent "
                + "any other way is photographed without night vision");
        int at = src.indexOf("private static void shutter(");
        assertTrue(at > 0, "shutter() exists");
        String body = src.substring(at, src.indexOf("}", at));
        assertTrue(body.indexOf("Viewpoint.seeInTheDark(p)") < body.indexOf("Send.to(p, payload)"),
                "night vision goes on BEFORE the shot is sent, so the client has it when it draws");
        assertTrue(src.split("shutter\\(", -1).length - 1 >= 7, "the seven shot sites use it");

        String vp = Files.readString(Path.of("src/main/java/com/moddingfromamod/wish/Viewpoint.java"));
        int see = vp.indexOf("public static void seeInTheDark(");
        assertTrue(vp.substring(see, vp.indexOf("}", see)).contains("PlayerKind.isChild("),
                "seeInTheDark asks whether the body is a child before anything else");
        int rel = vp.indexOf("public void release()");
        assertTrue(vp.substring(rel, vp.indexOf("toString", rel)).contains("endTake(player)"),
                "releasing a camera takes its night vision away");
        String wc = Files.readString(Path.of("src/main/java/com/moddingfromamod/wish/WishCapture.java"));
        int tick = wc.indexOf("public static void onServerTick(");
        int early = wc.indexOf("return;", tick);
        int sweep = wc.indexOf("Viewpoint.sweepNightVision(", tick);
        assertTrue(sweep > tick && sweep < early,
                "the server tick ends a finished take's night vision BEFORE its early return — a "
                        + "take that ended leaves nothing ACTIVE, which is when the early return fires");
    }
}
