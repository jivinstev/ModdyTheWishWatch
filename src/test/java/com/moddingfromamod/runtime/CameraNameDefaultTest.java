package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The camera has a name without anybody typing one</b> — and the server's default is the same
 * string the launcher's is.
 *
 * <p>Why this is a test rather than a constant nobody checks: for the life of the camera path
 * {@code cameraName} defaulted to the empty string and empty MEANT "there is no camera". That was
 * right while nothing launched one. Once {@code CameraSupervisor} did, the empty default became a
 * silent off switch on the whole capture path — a stock client cannot be borrowed
 * ({@code WishCapture.enter} refuses), so the agent is told {@code noeyes} and cannot see its own
 * work, and no log line anywhere says the reason is one unset string.
 *
 * <p><b>The launcher agreement is the half that would rot.</b> The two ends must derive the same
 * offline UUID — the server registers {@code offlineId(name)} as a service client, the client joins
 * under {@code -Pusername} — and they are defaulted in two files that know nothing about each
 * other. This reads {@code tools/camera-client.sh} rather than restating its value, so changing one
 * side alone fails here rather than in a session where the camera joins as a child.
 */
class CameraNameDefaultTest {

    private static final String PROP = "moddingfromamod.cameraName";

    @AfterEach
    void clear() {
        System.clearProperty(PROP);
    }

    @Test
    @DisplayName("unset means the default, not 'no camera'")
    void unsetMeansTheDefault() {
        System.clearProperty(PROP);
        assertEquals(RuntimeConfig.DEFAULT_CAMERA_NAME, RuntimeConfig.cameraName(),
                "an unconfigured server must still name a camera. Empty means the supervisor is "
                        + "inert, every photograph borrows the child's eyes, and on a stock client "
                        + "that is refused outright — so the agent silently has no eyes at all");
        assertFalse(RuntimeConfig.cameraName().isEmpty(), "the default must not be blank");
    }

    @Test
    @DisplayName("an explicit name still wins")
    void anExplicitNameWins() {
        System.setProperty(PROP, "Some-Other-Camera");
        assertEquals("Some-Other-Camera", RuntimeConfig.cameraName());
    }

    /**
     * The off switch has to be a WORD. {@code RuntimeConfig.value} discards a blank system property
     * and a blank environment variable before {@code cameraName} sees them, so an empty value is
     * indistinguishable from unset — which means without this there is no way to turn the camera
     * off at all once a default exists.
     */
    @Test
    @DisplayName("off, none and false turn the camera off — and a blank value cannot")
    void thereIsAnOffSwitchAndItIsAWord() {
        for (String off : new String[] {"off", "none", "false", "OFF", "None"}) {
            System.setProperty(PROP, off);
            assertEquals("", RuntimeConfig.cameraName(),
                    "'" + off + "' must mean no camera, or a deployment that does not want one "
                            + "has no way to say so");
        }
        System.setProperty(PROP, "");
        assertEquals(RuntimeConfig.DEFAULT_CAMERA_NAME, RuntimeConfig.cameraName(),
                "a blank value is swallowed by value() and reads as unset — this is asserted so "
                        + "the word-based off switch above cannot be 'simplified' away");
    }

    @Test
    @DisplayName("the server's default is the same name the launcher joins under")
    void theServerAndTheLauncherAgree() throws Exception {
        Path launcher = null;
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("tools").resolve("camera-client.sh");
            if (Files.isRegularFile(candidate)) {
                launcher = candidate;
                break;
            }
        }
        if (launcher == null) {
            return;   // a packaged checkout without tools/ — nothing to disagree with
        }
        String script = Files.readString(launcher);
        var m = java.util.regex.Pattern
                .compile("CAMERA_NAME=\"\\$\\{CAMERA_NAME:-([^}\"]+)\\}\"").matcher(script);
        assertTrue(m.find(), "tools/camera-client.sh no longer defaults CAMERA_NAME in the shape "
                + "this test reads. Fix the pattern rather than deleting the check: the two ends "
                + "agreeing is the whole point");
        assertEquals(m.group(1), RuntimeConfig.DEFAULT_CAMERA_NAME,
                "the server defaults the camera's name to '" + RuntimeConfig.DEFAULT_CAMERA_NAME
                        + "' and the launcher joins as '" + m.group(1) + "'. They derive the "
                        + "service-client UUID from that string, so a mismatch makes the camera "
                        + "join as a CHILD — which is silent, and is the identity bug #489 exists "
                        + "about");
    }
}
