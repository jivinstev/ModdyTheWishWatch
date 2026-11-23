package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A creature's <b>movement</b>, parsed from a Blockbench-exported {@code .animation.json} (the
 * bedrock {@code 1.8.0} format — the same files GeckoLib consumes, so animations authored for a
 * shipped mod mob play here unchanged).
 *
 * <p>This is what turns a runtime creature from a statue into something alive, without adding a
 * GeckoLib dependency and — crucially — without a resource reload: keyframes are just numbers, so a
 * clip re-exported from Blockbench takes effect on the next frame like the geometry and skin do.
 *
 * <p>A clip is a set of per-bone channels ({@code rotation} / {@code position} / {@code scale}), each
 * a list of timestamped vectors. {@link #pose(float)} samples them all at a time in seconds and
 * returns the per-bone transform the renderer adds on top of the model's rest pose.
 *
 * <p>Minecraft-free on purpose: interpolation and the format's many small variations (a static
 * vector instead of a keyframe map, {@code {"pre":…,"post":…}} keyframes, Molang expression strings
 * we can't evaluate) are exactly the fiddly things worth unit-testing in milliseconds.
 */
public final class BedrockAnimation {

    /** One bone's transform at a moment in time. Rotation in degrees; scale defaults to 1. */
    public record BonePose(float[] rotation, float[] position, float[] scale) {

        public boolean isIdentity() {
            return rotation[0] == 0 && rotation[1] == 0 && rotation[2] == 0
                    && position[0] == 0 && position[1] == 0 && position[2] == 0
                    && scale[0] == 1 && scale[1] == 1 && scale[2] == 1;
        }
    }

    /** A timestamped vector. */
    private record Key(float time, float[] value) {
    }

    /** One channel (rotation/position/scale) of one bone: keyframes sorted by time. */
    private static final class Channel {
        private final List<Key> keys;

        Channel(List<Key> keys) {
            keys.sort((a, b) -> Float.compare(a.time(), b.time()));
            this.keys = keys;
        }

        boolean isEmpty() {
            return keys.isEmpty();
        }

        float maxTime() {
            return keys.isEmpty() ? 0f : keys.get(keys.size() - 1).time();
        }

        /** Linear interpolation, clamped outside the keyframe range. */
        float[] sample(float t) {
            if (keys.isEmpty()) {
                return null;
            }
            if (keys.size() == 1 || t <= keys.get(0).time()) {
                return keys.get(0).value();
            }
            Key last = keys.get(keys.size() - 1);
            if (t >= last.time()) {
                return last.value();
            }
            for (int i = 0; i < keys.size() - 1; i++) {
                Key a = keys.get(i);
                Key b = keys.get(i + 1);
                if (t >= a.time() && t <= b.time()) {
                    float span = b.time() - a.time();
                    // Two keys at the same timestamp = a hard cut; take the later one.
                    float f = span <= 1e-6f ? 1f : (t - a.time()) / span;
                    return new float[]{
                            lerp(a.value()[0], b.value()[0], f),
                            lerp(a.value()[1], b.value()[1], f),
                            lerp(a.value()[2], b.value()[2], f)};
                }
            }
            return last.value();
        }

        private static float lerp(float a, float b, float f) {
            return a + (b - a) * f;
        }
    }

    private record BoneAnim(Channel rotation, Channel position, Channel scale) {
    }

    private final String name;
    private final boolean loop;
    private final float length;
    private final Map<String, BoneAnim> bones;

    /** a gesture laid over this clip ({@link #withOverlay}), or null. */
    private final BedrockAnimation overlay;
    /** Seconds to subtract from this clip's time to get the overlay's. */
    private final float overlayOffset;

    private BedrockAnimation(String name, boolean loop, float length, Map<String, BoneAnim> bones) {
        this(name, loop, length, bones, null, 0f);
    }

    private BedrockAnimation(String name, boolean loop, float length, Map<String, BoneAnim> bones,
                             BedrockAnimation overlay, float overlayOffset) {
        this.name = name;
        this.loop = loop;
        this.length = length;
        this.bones = bones;
        this.overlay = overlay;
        this.overlayOffset = overlayOffset;
    }

    /**
     * <b> (Moddy Media): {@code base} with a gesture laid over it.</b> Every bone the gesture moves
     * takes the gesture's pose; every other bone keeps the base clip's, so he can tip his hat while his
     * jaw keeps talking. {@code offset} maps the base clip's time onto the gesture's
     * ({@code gestureSeconds = baseSeconds - offset}), because the two started at different moments.
     */
    public static BedrockAnimation withOverlay(BedrockAnimation base, BedrockAnimation gesture, float offset) {
        if (base == null || gesture == null) {
            return base == null ? gesture : base;
        }
        return new BedrockAnimation(base.name, base.loop, base.length, base.bones, gesture, offset);
    }

    public String name() {
        return name;
    }

    public boolean loop() {
        return loop;
    }

    /** Clip length in seconds. Never zero, so callers can divide by it safely. */
    /**
     * Which bones this clip ever moves.
     *
     * <p>The vanilla rig needs it at PLAN time, not at pose time: a bone the animation moves has to
     * be split into its own display entity or it cannot move at all, and {@code RigPlan.exactGroups}
     * splits on REST ROTATION, which is a different question. Measured on Spikor — {@code legL} and
     * {@code legR} are both unrotated, so both were folded into one model with each other and with
     * the feet, and the walk clip had nothing to drive.
     */
    public java.util.Set<String> bones() {
        return java.util.Set.copyOf(bones.keySet());
    }

    public float length() {
        return length;
    }

    public int boneCount() {
        return bones.size();
    }

    /**
     * Sample every animated bone at {@code seconds}. Looping clips wrap; one-shot clips hold their
     * final pose (the renderer decides when to stop playing one).
     */
    public Map<String, BonePose> pose(float seconds) {
        float t = seconds;
        if (loop && length > 0) {
            t = seconds % length;
            if (t < 0) {
                t += length;
            }
        }
        Map<String, BonePose> out = new HashMap<>();
        for (Map.Entry<String, BoneAnim> e : bones.entrySet()) {
            BoneAnim ba = e.getValue();
            float[] rot = ba.rotation() == null ? null : ba.rotation().sample(t);
            float[] pos = ba.position() == null ? null : ba.position().sample(t);
            float[] scl = ba.scale() == null ? null : ba.scale().sample(t);
            if (rot == null && pos == null && scl == null) {
                continue;
            }
            out.put(e.getKey(), new BonePose(
                    rot == null ? new float[]{0, 0, 0} : rot,
                    pos == null ? new float[]{0, 0, 0} : pos,
                    scl == null ? new float[]{1, 1, 1} : scl));
        }
        if (overlay != null) {
            out.putAll(overlay.pose(seconds - overlayOffset));
        }
        return out;
    }

    /** True when this clip actually animates the named bone. */
    public boolean animates(String bone) {
        return bones.containsKey(bone);
    }

    // ===================== parsing =====================

    /** Guardrail: these files come from an agent and a child. */
    public static final int MAX_CLIPS = 64;

    /**
     * Parse every clip in an {@code .animation.json}. Returns a map keyed by the clip's full name
     * (e.g. {@code animation.baby_dragon.idle}). Never throws; unreadable clips are skipped.
     */
    public static Map<String, BedrockAnimation> parseAll(JsonObject root) {
        Map<String, BedrockAnimation> out = new LinkedHashMap<>();
        if (root == null) {
            return out;
        }
        JsonObject anims = obj(root, "animations");
        if (anims == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
            if (out.size() >= MAX_CLIPS) {
                break;
            }
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            BedrockAnimation clip = parseClip(e.getKey(), e.getValue().getAsJsonObject());
            if (clip != null) {
                out.put(e.getKey(), clip);
            }
        }
        return out;
    }

    private static BedrockAnimation parseClip(String name, JsonObject o) {
        boolean loop = false;
        JsonElement loopEl = o.get("loop");
        if (loopEl != null && loopEl.isJsonPrimitive()) {
            // "loop" is either a boolean or the string "hold_on_last_frame".
            loop = loopEl.getAsJsonPrimitive().isBoolean()
                    ? loopEl.getAsBoolean()
                    : "true".equalsIgnoreCase(loopEl.getAsString());
        }
        Map<String, BoneAnim> bones = new LinkedHashMap<>();
        JsonObject bonesObj = obj(o, "bones");
        float maxTime = 0f;
        if (bonesObj != null) {
            for (Map.Entry<String, JsonElement> e : bonesObj.entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject b = e.getValue().getAsJsonObject();
                Channel rot = channel(b.get("rotation"), 0f);
                Channel pos = channel(b.get("position"), 0f);
                Channel scl = channel(b.get("scale"), 1f);
                if (rot == null && pos == null && scl == null) {
                    continue;
                }
                bones.put(e.getKey(), new BoneAnim(rot, pos, scl));
                maxTime = Math.max(maxTime, Math.max(
                        rot == null ? 0 : rot.maxTime(),
                        Math.max(pos == null ? 0 : pos.maxTime(), scl == null ? 0 : scl.maxTime())));
            }
        }
        if (bones.isEmpty()) {
            return null;
        }
        float length = floatOf(o, "animation_length", 0f);
        if (length <= 0) {
            length = maxTime;         // Blockbench omits it sometimes; derive from the last keyframe
        }
        if (length <= 0) {
            length = 1f;              // a single static pose — any non-zero length is fine
        }
        return new BedrockAnimation(name, loop, length, bones);
    }

    /**
     * A channel is either a static vector ({@code [x,y,z]}) or a map of {@code "time": vector}.
     * Returns null when there's nothing usable.
     */
    private static Channel channel(JsonElement el, float defaultValue) {
        if (el == null) {
            return null;
        }
        List<Key> keys = new ArrayList<>();
        if (el.isJsonArray()) {
            float[] v = vec3(el, defaultValue);
            if (v != null) {
                keys.add(new Key(0f, v));
            }
        } else if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                Float time = parseFloat(e.getKey());
                if (time == null) {
                    continue;             // not a timestamp key
                }
                float[] v = keyframeValue(e.getValue(), defaultValue);
                if (v != null) {
                    keys.add(new Key(time, v));
                }
            }
        }
        return keys.isEmpty() ? null : new Channel(keys);
    }

    /**
     * A keyframe's value: a bare vector, or an object carrying {@code pre}/{@code post} (a step) —
     * we take {@code post}, the value the clip holds leaving that keyframe.
     */
    private static float[] keyframeValue(JsonElement el, float defaultValue) {
        if (el == null) {
            return null;
        }
        if (el.isJsonArray()) {
            return vec3(el, defaultValue);
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            for (String k : new String[]{"post", "pre", "vector"}) {
                if (o.has(k)) {
                    float[] v = vec3(o.get(k), defaultValue);
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    /**
     * A 3-vector. Entries may be Molang expression strings (e.g. {@code "math.sin(…)"}) which we
     * can't evaluate — those components fall back to the channel's neutral value rather than
     * breaking the whole clip.
     */
    private static float[] vec3(JsonElement el, float defaultValue) {
        if (el == null || !el.isJsonArray()) {
            return null;
        }
        JsonArray a = el.getAsJsonArray();
        float[] v = new float[]{defaultValue, defaultValue, defaultValue};
        for (int i = 0; i < 3 && i < a.size(); i++) {
            JsonElement c = a.get(i);
            if (c != null && c.isJsonPrimitive()) {
                try {
                    v[i] = c.getAsFloat();
                } catch (RuntimeException ignored) {
                    // a Molang string — keep the neutral value for this component
                }
            }
        }
        return v;
    }

    private static Float parseFloat(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static float floatOf(JsonObject o, String k, float d) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsFloat() : d;
        } catch (RuntimeException e) {
            return d;
        }
    }

    // ===================== clip selection =====================

    /**
     * The clips the renderer knows how to drive.
     *
     * <p>{@code TALK} is never chosen from motion: a server-side flag on the carrier turns it
     * on ({@code RigTalk}), and {@link AnimationState#select} ranks it above walk and idle, below
     * attack. A species without a talk clip idles instead. Appended last so no ordinal moves.
     */
    public enum Clip { IDLE, WALK, ATTACK, TALK }

    /**
     * Find the clip for a state among the parsed animations.
     *
     * <p>Deliberately forgiving about naming, because these files are authored by an agent and a
     * child and the convention drifts: {@code animation.baby_dragon.walk}, {@code walk},
     * {@code Walk}, {@code animation.walk_cycle} all resolve. Exact suffix matches win over
     * substring ones so {@code attack} can never be shadowed by {@code walk_attack}.
     */
    public static BedrockAnimation find(Map<String, BedrockAnimation> clips, Clip clip) {
        if (clips == null || clips.isEmpty()) {
            return null;
        }
        String want = clip.name().toLowerCase(Locale.ROOT);
        BedrockAnimation substringMatch = null;
        for (Map.Entry<String, BedrockAnimation> e : clips.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (key.equals(want) || key.endsWith("." + want)) {
                return e.getValue();
            }
            if (substringMatch == null && key.contains(want)) {
                substringMatch = e.getValue();
            }
        }
        return substringMatch;
    }

    /** Empty, immutable — a model with no animation file. */
    public static Map<String, BedrockAnimation> none() {
        return Collections.emptyMap();
    }
}
