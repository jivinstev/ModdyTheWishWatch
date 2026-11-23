package com.moddingfromamod.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mod's read of tools/muse-cli-shim.py's per-worker files. */
class MuseShimStateTest {

    @TempDir
    Path dir;

    private void write(String sub, String worker, String json) throws Exception {
        Files.createDirectories(dir.resolve(sub));
        Files.writeString(dir.resolve(sub).resolve(worker + ".json"), json);
    }

    @Test
    @DisplayName("off unless MUSE_SHIM_STATE is set: a server without the subscription is unchanged")
    void offByDefault() {
        assertTrue(MuseShimState.dir(k -> null).isEmpty());
        assertTrue(MuseShimState.dir(k -> "  ").isEmpty());
        assertEquals(Optional.of(Path.of("/srv/x")),
                MuseShimState.dir(Map.of("MUSE_SHIM_STATE", "/srv/x")::get));
    }

    @Test
    @DisplayName("the served spec is the shim's record for that slot")
    void servedSpec() throws Exception {
        write("served", "wish-granter-2",
                "{\"model\":\"muse:muse-wonder-1.0\",\"arm\":\"subscription\",\"billing\":\"subscription\"}");
        assertEquals(Optional.of("muse:muse-wonder-1.0"), MuseShimState.servedSpec(dir, "wish-granter-2"));
        assertTrue(MuseShimState.servedSpec(dir, "wish-granter-3").isEmpty(), "no record, no claim");
    }

    @Test
    @DisplayName("a torn file or a name that could escape the directory answers empty, never throws")
    void refusesWhatItCannotTrust() throws Exception {
        write("served", "wish-granter-2", "{\"model\":\"muse:mu");
        assertTrue(MuseShimState.servedSpec(dir, "wish-granter-2").isEmpty());
        assertTrue(MuseShimState.servedSpec(dir, "../etc").isEmpty());
        assertTrue(MuseShimState.servedSpec(dir, "").isEmpty());
        assertTrue(MuseShimState.servedSpec(null, "wish-granter-2").isEmpty());
        assertFalse(MuseShimState.safeName("a/b"));
    }

    @Test
    @DisplayName("a death is read with its reason and time, and consumed once acted on")
    void deaths() throws Exception {
        write("deaths", "wish-granter-1", "{\"reason\":\"quota\",\"atMs\":1792014484744}");
        var d = MuseShimState.death(dir, "wish-granter-1").orElseThrow();
        assertEquals("quota", d.reason());
        assertEquals(1792014484744L, d.atMs());
        MuseShimState.consumeDeath(dir, "wish-granter-1");
        assertTrue(MuseShimState.death(dir, "wish-granter-1").isEmpty());
        write("deaths", "wish-granter-1", "{\"atMs\":5}");
        assertTrue(MuseShimState.death(dir, "wish-granter-1").isEmpty(), "a death with no reason is none");
    }
}
