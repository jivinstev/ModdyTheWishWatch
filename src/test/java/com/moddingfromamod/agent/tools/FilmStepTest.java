package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The film step, and the three ways it can go quietly wrong.</b>
 *
 * <p>Filming is an optional step in the photograph orbit: at stop 0 — the creature's FACE, by
 * construction — the rig takes its still and then holds the same pose for a few seconds while
 * {@code tools/record-client.sh} grabs the X display. There is nothing to assert about the film
 * itself here, because the frames never enter this process; the recorder is a separate program and
 * the evidence is checked afterwards by {@code tools/film-evidence.py}.
 *
 * <p>What CAN be asserted is the handful of decisions that make the difference between a film of a
 * fire breath and a fifteen-second clip of a creature standing still — every one of which fails
 * <i>silently</i>, producing a well-formed mp4 of the wrong thing. That is the same failure
 * docs/CAMERA.md is entirely about, and the reason it is worth a test at all.
 *
 * <p>Read as source text, like {@code SourceRuleSweepTest} and {@code AgentKnowsTheTrapsTest}: the
 * subject is a private method on a class whose collaborators are a live server and a real client,
 * so there is no seam to call. The rules are about what the code SAYS, and a grep can hold them.
 */
class FilmStepTest {

    private static final Path RIG =
            Path.of("src/main/java/com/moddingfromamod/agent/tools/PhotographTool.java");

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), p + " has moved; this test reads it as source text");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the EXTERNAL film is off unless asked for; the in-game CLIP is on by default")
    void defaults() throws IOException {
        String src = read(RIG);

        // The external recorder cannot ship — it is ffmpeg grabbing an X display, and the child
        // plays on a Mac. It exists for the gates, so it stays opt-in.
        assertTrue(src.contains("\".filmSeconds\", 0)"),
                "FILM_SECONDS must default to 0. It drives an EXTERNAL recorder that only exists on "
                + "a test VM, and every second of it is a second the orbit cannot advance.");

        // The in-game clip is the opposite: it is the product, it costs about 5ms of render thread
        // per frame, and a creature wish without one is four stills of a thing that moves.
        assertTrue(src.contains("\".clipSeconds\", 2)"),
                "CLIP_SECONDS must default to 2. This is deliberate and is the feature: a creature "
                + "is a thing that MOVES, and a dragon's fire breath burns for 40 ticks out of every "
                + "80, so a still of one is indistinguishable from a creature standing in a cloud.");

        assertTrue(src.contains("if (stop != 0 || (FILM_SECONDS <= 0 && clipSeconds <= 0))"),
                "both must be skipped entirely when neither is wanted, and must only ever happen at "
                + "stop 0 — the face. Filming all four stops would quadruple the cost and three of "
                + "the takes would be of the creature's back.");

        // The length is a PARAMETER now, because an offline contact card holds ten seconds and a
        // wish holds two. That makes the default a thing that can be lost in a refactor rather than
        // a constant that cannot, so pin the wish's own entry point: whatever else calls
        // filmFromHere, the one reached from captureOrbit must pass CLIP_SECONDS.
        assertTrue(src.contains("filmFromHere(label, stop, subject, height, standoff, CLIP_SECONDS)"),
                "the in-game path must film for CLIP_SECONDS. A contact card's ten seconds leaking "
                + "into a wish would put five times the delay between a child and their creature at "
                + "the exact moment they are waiting to see it.");
        assertTrue(src.contains("OFFLINE_CLIP_SECONDS = 10"),
                "the offline length must stay 10s and stay SEPARATE. Two seconds is right in a wish "
                + "and reads as a jitter on a contact card, where nobody is waiting and a rebuilt "
                + "creature has no ability to burst — only a walk cycle to show.");
    }

    @Test
    @DisplayName("the clip rolls only once the camera has settled")
    void clipWaitsForThePose() throws IOException {
        String src = read(RIG);
        assertTrue(src.contains("CLIP_SETTLE_MS"),
                "the clip must not start on the same tick as the still. The tracking re-aims every "
                + "100ms and the client interpolates toward each correction, so the opening second "
                + "is the camera arriving. Measured on a real creature: of twenty frames the creature "
                + "was framed in the LAST TWO and the middle eight were a picture of a jungle.");
        int guard = src.indexOf("System.currentTimeMillis() - startMs >= CLIP_SETTLE_MS");
        assertTrue(guard > 0,
                "the settle must gate when the clip is REQUESTED, not merely be a constant nobody "
                + "reads — that is the shape this bug had the first time.");
    }

    @Test
    @DisplayName("a film never quiets the particles it exists to record")
    void neverQuiet() throws IOException {
        String src = read(RIG);
        int call = src.indexOf("label + \"__film\"");
        assertTrue(call > 0, "the film payload has been renamed; this test cannot see it");
        String payload = src.substring(call, Math.min(src.length(), call + 200));
        assertFalse(payload.contains("quiet"),
                "the caller's `quiet` flag must NOT reach the film. `quiet` exists so a creature's "
                + "form and colour can be judged THROUGH its own fire, and captureOrbit is called "
                + "with quiet=true by the eval harness — which would silently turn every film it "
                + "took into footage of a creature standing still. A film of a fire breath with "
                + "the fire switched off is a perfectly good mp4 of nothing happening.");
    }

    @Test
    @DisplayName("a film TRACKS the creature; it does not hold one pose")
    void tracksTheSubject() throws IOException {
        String src = read(RIG);
        int start = src.indexOf("private static void filmFromHere(");
        assertTrue(start > 0, "filmFromHere has been renamed; this test cannot see it");
        int end = src.indexOf("\n    private static String stopName(", start);
        String body = src.substring(start, end > 0 ? end : src.length());

        assertTrue(body.contains("while (System.currentTimeMillis() < endMs)"),
                "the film must re-aim for the length of the take. The first version held ONE pose "
                + "— the one the still was shot from — because the client pins a pose every tick "
                + "for the length of a settle. That is true and insufficient: the rig's "
                + "assumptions are about a TWO-TICK exposure and a film is three hundred ticks "
                + "long. Measured on the first real take: the camera held perfectly, the creature walked "
                + "away, and the still at the end of the take is a photograph of an empty beach.");
        assertTrue(body.contains("faceBearing") && body.contains("moveCameraTo"),
                "the re-aim must go through the rig's own OrbitCamera.faceBearing and "
                + "moveCameraTo. Those carry the bearing that finds a creature's face whichever "
                + "way it turned, the distance derived from the vertical FOV, and the lift that "
                + "gets the lens out of a hillside — none of which a second camera should "
                + "re-derive, least of all one that is moving.");
        assertFalse(body.contains("PhotoPayload.at("),
                "the film payload must carry NO pose. It is sent for the HUD and the brightness "
                + "and for the still at the end; a pose in it would pin the camera to the spot "
                + "the take began and fight the tracking above.");
    }

}
