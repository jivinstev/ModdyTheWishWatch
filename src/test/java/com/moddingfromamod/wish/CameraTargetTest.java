package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A camera VM's client dials only a whole, well-formed address — never a partial one. */
class CameraTargetTest {

    @Test
    void aLeasedClientReArmsOnlyWhenItsLeaseIsOverAndItIsOutOfTheWorld() {
        // The agent removes the target at the lease's end; the client goes back to waiting.
        org.junit.jupiter.api.Assertions.assertTrue(CameraTarget.rearm(true, true, false, false));
        org.junit.jupiter.api.Assertions.assertFalse(CameraTarget.rearm(true, true, false, true),
                "the target is still there: the lease is not over (or the next one is) — keep the dial");
        org.junit.jupiter.api.Assertions.assertFalse(CameraTarget.rearm(true, true, true, false),
                "still in the world: the gate has not closed yet");
        org.junit.jupiter.api.Assertions.assertFalse(CameraTarget.rearm(true, false, false, false),
                "never dialled: nothing to re-arm");
        org.junit.jupiter.api.Assertions.assertFalse(CameraTarget.rearm(false, true, false, false),
                "a local camera (an address at launch) is never re-armed");
    }

    @Test
    void aWholeAddressIsRead() {
        assertEquals("10.40.0.2:25590", CameraTarget.parse("10.40.0.2:25590\n"));
        assertEquals("camera-host.internal:25590", CameraTarget.parse("  camera-host.internal:25590  "));
        assertEquals("10.40.0.2:25590", CameraTarget.parse("10.40.0.2:25590\nlease=abc\n"));
    }

    @Test
    void anythingElseIsNotYet() {
        assertNull(CameraTarget.parse(null));
        assertNull(CameraTarget.parse(""));
        assertNull(CameraTarget.parse("10.40.0.2"));          // the port not written yet
        assertNull(CameraTarget.parse("10.40.0.2:"));
        assertNull(CameraTarget.parse("10.40.0.2:2559x"));
        assertNull(CameraTarget.parse("10.40.0.2:0"));
        assertNull(CameraTarget.parse("10.40.0.2:70000"));
        assertNull(CameraTarget.parse(":25590"));
        assertNull(CameraTarget.parse("evil host;rm:25590"));
    }

    /** The key is read from its file at the challenge, so a client booted before the lease signs. */
    @Test
    void theKeyIsReadFromItsFileWhenAsked(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key.b64");
        String prop = "moddingfromamod.serviceClientPrivateKeyFile";
        String old = System.getProperty(prop);
        try {
            System.setProperty(prop, f.toString());
            assertEquals("", RuntimeConfig.serviceClientPrivateKey());   // no file yet: no key
            Files.writeString(f, "AAAA\n");
            assertEquals("AAAA", RuntimeConfig.serviceClientPrivateKey());
            Files.writeString(f, "BBBB");   // a later lease (another server): the NEW key, no restart
            assertEquals("BBBB", RuntimeConfig.serviceClientPrivateKey());
        } finally {
            if (old == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, old);
            }
        }
    }
}
