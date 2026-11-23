package com.moddingfromamod.vanilla;

import com.moddingfromamod.runtime.BedrockAnimation;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>A named gesture played over a rig's clip</b> (Moddy Media), set by
 * {@code /wishmob gesture <targets> <clip|off> [hold]}.
 *
 * <p>A film directs its presenter's arms: a hand to his chest on "like myself", a tip of the hat on
 * "soon." ("control arm position during videos"). A gesture is any clip in the species'
 * animation file, by name ({@code hat_tip} finds {@code animation.<species>.hat_tip}). It is laid OVER the
 * clip the rig is already playing ({@link BedrockAnimation#withOverlay}): the bones it moves take its
 * pose, the rest keep idling or talking, so his jaw keeps time with the voice while his arm moves.
 * It plays once from its first frame and lets go at its end, or holds its last frame with {@code hold}
 * until {@code off}.
 *
 * <p>Held in memory, like {@link RigFacing}: a take sets it up again from its timeline.
 */
public final class RigGesture {

    record Active(String clip, long startTick, boolean hold) {
    }

    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();

    private RigGesture() {
    }

    public static void start(UUID carrier, String clip, long gameTick, boolean hold) {
        ACTIVE.put(carrier, new Active(clip.toLowerCase(Locale.ROOT), gameTick, hold));
    }

    /** @return true when a gesture was playing */
    public static boolean stop(UUID carrier) {
        return ACTIVE.remove(carrier) != null;
    }

    /** The gesture a carrier is playing: its clip name and the tick it started. */
    public record Playing(String clip, long startTick) {
    }

    /** What {@code carrier} is gesturing, or null (a native creature plays the same clip from its pack). */
    public static Playing playing(UUID carrier) {
        Active a = ACTIVE.get(carrier);
        return a == null ? null : new Playing(a.clip(), a.startTick());
    }

    public static void forget(UUID carrier) {
        ACTIVE.remove(carrier);
    }

    /** The species' clip with this name, or null: an exact key, or one ending in ".name". */
    public static BedrockAnimation named(Map<String, BedrockAnimation> clips, String name) {
        if (clips == null || name == null) {
            return null;
        }
        String want = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, BedrockAnimation> e : clips.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (key.equals(want) || key.endsWith("." + want)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * The clips to draw this tick: unchanged with no gesture, otherwise the idle and talk clips with the
     * gesture laid over them, each lined up to the gesture's own start.
     *
     * @param idleSeconds the time the idle clip is drawn at this tick
     * @param talkSeconds the time the talk clip is drawn at this tick
     */
    static Map<String, BedrockAnimation> layer(UUID carrier, Map<String, BedrockAnimation> clips, long gameTick,
                                               float idleSeconds, float talkSeconds) {
        Active a = ACTIVE.get(carrier);
        if (a == null || clips == null || clips.isEmpty()) {
            return clips;
        }
        BedrockAnimation gesture = named(clips, a.clip());
        float g = (gameTick - a.startTick()) / 20f;
        if (gesture == null || (!a.hold() && !gesture.loop() && g > gesture.length())) {
            ACTIVE.remove(carrier, a);
            return clips;
        }
        BedrockAnimation idle = BedrockAnimation.find(clips, BedrockAnimation.Clip.IDLE);
        BedrockAnimation talk = BedrockAnimation.find(clips, BedrockAnimation.Clip.TALK);
        Map<String, BedrockAnimation> out = new HashMap<>(clips);
        for (Map.Entry<String, BedrockAnimation> e : clips.entrySet()) {
            if (e.getValue() == idle) {
                out.put(e.getKey(), BedrockAnimation.withOverlay(idle, gesture, idleSeconds - g));
            } else if (e.getValue() == talk) {
                out.put(e.getKey(), BedrockAnimation.withOverlay(talk, gesture, talkSeconds - g));
            }
        }
        return out;
    }
}
