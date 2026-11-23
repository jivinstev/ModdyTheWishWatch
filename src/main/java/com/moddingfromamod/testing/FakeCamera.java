package com.moddingfromamod.testing;

import java.nio.file.Files;
import java.util.Base64;

import com.moddingfromamod.agent.tools.PhotographTool;

/**
 * <b>Be the client for one photograph</b> — the one way a GameTest answers the camera.
 *
 * <p>A mock player has no client, so nobody writes the PNG that {@code photograph} and
 * {@code finish_building} wait for; a wait for a file that never appears runs to the camera's 30 s
 * hard cap, longer than a mock lives. This writes a 1x1 PNG the moment the tool fires the shutter.
 *
 * <p>⚠ <b>Not on a timer.</b> Seven tests each had a copy that slept 400 ms and wrote the file. The
 * tool only accepts a photo NEWER than the one it saw before the shot, so on a loaded server — where
 * the tool takes longer than 400 ms to reach its shutter — the photo arrived first, counted as old,
 * and the wait ran to the cap. See {@code PhotographTool.NO_CLIENT_DARKROOM}.
 */
public final class FakeCamera {

    /** A 1x1 opaque PNG. */
    private static final String ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC";

    private FakeCamera() {
    }

    /** Write {@code label}'s photograph when its shutter fires. */
    public static void answer(String label) {
        PhotographTool.developForTest(label, out -> {
            try {
                Files.createDirectories(out.getParent());
                Files.write(out, Base64.getDecoder().decode(ONE_PIXEL_PNG));
            } catch (Exception ignored) {
                // the tool then waits out its cap, and the test's deadline says so
            }
        });
    }
}
