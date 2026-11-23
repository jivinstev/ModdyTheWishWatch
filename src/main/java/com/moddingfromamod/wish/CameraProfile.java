package com.moddingfromamod.wish;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>How big a camera's window is, how fast it draws, and how many may run at once</b> (HO-14).
 *
 * <p>Pure and Minecraft-free, per this repo's first key rule: the decision is here and
 * {@code CameraSupervisor} only executes the result.
 *
 * <h2>Why the numbers are small, and why that is a decision rather than a default</h2>
 *
 * <p>The owner's box has no serious GPU (decision <b>Q9</b>, 2026-09-20). Before this class there
 * was <b>no resolution or frame-rate control anywhere</b> and <b>exactly one camera</b> — so the
 * conservative starting point had nowhere to live. 1280×720 at 30 fps, two at a time.
 *
 * <p>⚠ <b>These are defaults to be INHERITED and then measured, not tuned in advance</b> — the same
 * posture Q9 takes for {@code mem_limit}. What makes that honest rather than lazy is that the cap
 * is instrumented: {@code Counters.waited("camera", ms)} says what queueing the cap caused and
 * {@code Counters.photoDegraded("camera_timeout")} says when it cost a child the framed picture.
 * A cap you can argue about with evidence is worth more than a cap somebody guessed well.
 *
 * <h2>⚠ A profile that is built and not APPLIED is this plan's other favourite failure</h2>
 *
 * <p>Every field here has to reach the real client or the class is decoration — {@code TrainFraming}
 * shipped an axis that was correct at both call sites and ninety degrees wrong the day a train could
 * turn, and it arrived where it was sent the whole time. So the exit criterion for this row is a
 * PHOTOGRAPH whose PNG is 1280×720, not a passing unit test.
 */
public record CameraProfile(int width, int height, int maxFps, int concurrent) {

    /** {@code -Dmoddingfromamod.camera.width} and friends; unset means the values below. */
    public static final String PREFIX = "moddingfromamod.camera.";

    /** 720p, 30 fps, two at once. See the class note for why each one is what it is. */
    public static final CameraProfile DEFAULT = new CameraProfile(1280, 720, 30, 2);

    /**
     * ⚠ <b>Clamped, and the floors matter more than the ceilings.</b> A zero or negative
     * {@code concurrent} is not "no cameras" — it is a typo, and reading it as zero would stall
     * every wish that wants a photograph behind a queue nothing can drain. One is the floor, and
     * the ceiling is four because each camera is a whole Minecraft. (This used to say "for the same
     * reason {@code WishSlots.MAX} is four"; that ceiling is ten since D10, and a wish worker is a
     * process, not a game client, so the two ceilings are no longer the same reason.)
     */
    public CameraProfile {
        width = clamp(width, 320, 3840);
        height = clamp(height, 240, 2160);
        maxFps = clamp(maxFps, 10, 260);
        concurrent = clamp(concurrent, 1, CameraPool.MAX);   // The world's own cap
    }

    /** Read the shipped defaults, overridden per property. */
    public static CameraProfile fromProperties() {
        return new CameraProfile(
                intProp("width", DEFAULT.width()),
                intProp("height", DEFAULT.height()),
                intProp("maxFps", DEFAULT.maxFps()),
                // The camera.concurrent knob wins over the boot property.
                com.moddingfromamod.config.ModdySettings.intOr("camera.concurrent",
                        intProp("concurrent", DEFAULT.concurrent())));
    }

    /**
     * The {@code options.txt} lines this profile owns, in file order.
     *
     * <p>⚠ Only the keys it OWNS. Merging rather than rewriting is deliberate: {@code
     * vanilla-client.sh} already writes the accessibility and tutorial keys that stop a fresh
     * profile sitting on {@code AccessibilityOnboardingScreen} waiting for a human to click — a
     * camera that rewrote the whole file would hang exactly where {@code CameraClientTest}'s
     * comment says it hung.
     */
    public Map<String, String> optionsLines() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("overrideWidth", String.valueOf(width));
        out.put("overrideHeight", String.valueOf(height));
        // `maxFps` is Minecraft's own key and it is a CEILING, not a target: below the cap the
        // client still draws as fast as it can, so this costs nothing when the box is idle.
        out.put("maxFps", String.valueOf(maxFps));
        return out;
    }

    /** Merge {@link #optionsLines()} into an existing {@code options.txt}, keeping everything else. */
    public String mergeInto(String existingOptionsTxt) {
        Map<String, String> mine = optionsLines();
        StringBuilder out = new StringBuilder();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String line : (existingOptionsTxt == null ? "" : existingOptionsTxt).split("\n", -1)) {
            int colon = line.indexOf(':');
            String key = colon < 0 ? null : line.substring(0, colon);
            if (key != null && mine.containsKey(key)) {
                out.append(key).append(':').append(mine.get(key)).append('\n');
                seen.add(key);
            } else if (!line.isEmpty()) {
                out.append(line).append('\n');
            }
        }
        for (Map.Entry<String, String> e : mine.entrySet()) {
            if (!seen.contains(e.getKey())) {
                out.append(e.getKey()).append(':').append(e.getValue()).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * What the launcher exports, so {@code camera-client.sh} needs no knowledge of this class.
     *
     * <p>Environment rather than argv because the launcher is a shell script the supervisor starts
     * with no arguments today, and adding a positional contract would be a second place the
     * profile lives.
     */
    public Map<String, String> launchEnv() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("CAMERA_WIDTH", String.valueOf(width));
        out.put("CAMERA_HEIGHT", String.valueOf(height));
        out.put("CAMERA_MAX_FPS", String.valueOf(maxFps));
        return out;
    }

    /** One line for a boot log, so an operator can see what a camera was actually told. */
    public String describe() {
        return width + "x" + height + " @" + maxFps + "fps, up to " + concurrent
                + " at once" + (equals(DEFAULT) ? " (shipped default)" : " (overridden)");
    }

    /** The keys a caller may set, for the launcher's own documentation. */
    public static List<String> propertyNames() {
        return List.of(PREFIX + "width", PREFIX + "height", PREFIX + "maxFps",
                PREFIX + "concurrent");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int intProp(String name, int fallback) {
        String raw = System.getProperty(PREFIX + name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            // A typo'd flag must not silently become something else — the same rule WishSlots
            // states. Fall back to the shipped value; the clamp above would have hidden a 0.
            return fallback;
        }
    }
}
