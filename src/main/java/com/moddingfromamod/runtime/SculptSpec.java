package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * <b>The editable SOURCE of a sculpted creature</b> — the exact {@code parts}-and-stats spec the
 * agent passed to {@code sculpt_creature}, in one canonical JSON form.
 *
 * <p>Why this exists: the model on disk (geometry/texture/animations) is a <i>baked</i> output — you
 * can re-import it, but you can't cleanly re-edit "make his legs shorter". The re-editable thing is
 * the parts list itself. Saving it beside the model (and publishing it repo-side) means a wished
 * character can always be tweaked or upgraded later, instead of being recovered from agent logs after
 * the fact (which is what we had to do once, and the logs truncate long specs).
 *
 * <p>Pure + Minecraft-free so it's unit-testable: the only transformation is "drop the transient
 * {@code spawn} flag and pin the sanitized id", so what's saved is a faithful, re-runnable spec.
 */
public final class SculptSpec {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SculptSpec() {
    }

    /**
     * The canonical editable spec JSON for a sculpt call: the tool args as given, with the transient
     * {@code spawn} flag removed and {@code id} pinned to the sanitized key. Re-feeding this to
     * {@code sculpt_creature} rebuilds the same creature.
     */
    public static String editable(JsonObject args, String id) {
        JsonObject spec = args == null ? new JsonObject() : args.deepCopy();
        spec.remove("spawn");
        spec.addProperty("id", id);
        return GSON.toJson(spec);
    }

    /**
     * The editable spec for a sculpt that <b>inherited a body</b> — the resolved creature, not the
     * diff that produced it.
     *
     * <h2>The bug this fixes</h2>
     * The two-argument form above saves the tool arguments as given, which is exactly right for a
     * creation and <b>destroys the creature on an edit</b>. When the agent adds wings with
     * {@code start_from}, the baked model is correct (it is built from the merged parts) but this
     * file is overwritten with just the wings. The next edit reads it as its base and gets two
     * wings; the creature collapses to whatever the last diff happened to be.
     *
     * <p>Confirmed by gate, not by reading: after one edit the source read {@code [wingL, wingR]}.
     * It also explains thirteen ladder runs in which <b>no edit ever completed</b> — the agent
     * tried {@code start_from} in eleven of them, watched the creature disintegrate, and rebuilt
     * the whole body from scratch. Both arms did this; the same worker only looked like a ceiling
     * because it could re-type the body from memory.
     *
     * <p>So an inheriting sculpt persists what the creature now IS, and drops the directives that
     * have already been applied — {@code start_from}, {@code remove}, {@code recolour} and
     * {@code kits}. Leaving any of them in would apply them a second time on the next read.
     *
     * <p>A sculpt with no base is untouched: it still saves the agent's own arguments, so a
     * kit-built creature's file does not double in size and stays the re-runnable source it
     * promises to be.
     */
    public static String editable(JsonObject args, String id,
                                  List<CreatureSculpt.Part> resolved) {
        boolean inherited = args != null && args.has("start_from")
                && args.get("start_from").isJsonPrimitive()
                && !args.get("start_from").getAsString().isBlank();
        if (!inherited || resolved == null || resolved.isEmpty()) {
            return editable(args, id);
        }
        return resolvedSpec(args, id, resolved);
    }

    /**
     * The spec of a sculpt whose parts the SERVER changed after the agent sent them (an earlier issue's main-colour
     * lever): always the resolved parts, inherited or not. {@code ModelRepository.sculptedParts} reads this
     * file to DRAW the creature, so saving the agent's own arguments here showed the child the colours the
     * tool had just told the agent it changed (measured: four of six turned sculpts drawn unturned).
     */
    public static String editableResolved(JsonObject args, String id, List<CreatureSculpt.Part> resolved) {
        if (resolved == null || resolved.isEmpty()) {
            return editable(args, id);
        }
        return resolvedSpec(args, id, resolved);
    }

    private static String resolvedSpec(JsonObject args, String id, List<CreatureSculpt.Part> resolved) {
        JsonObject spec = args.deepCopy();
        spec.remove("spawn");
        // Already baked into `resolved`. Re-applying them on the next read would double them.
        spec.remove("start_from");
        spec.remove("remove");
        spec.remove("recolour");
        spec.remove("recolor");
        spec.remove("kits");
        spec.addProperty("id", id);
        JsonArray parts = new JsonArray();
        resolved.forEach(part -> parts.add(toJson(part)));
        spec.add("parts", parts);

        // THE PALETTE MUST DESCRIBE THE CREATURE, NOT THE DIFF.
        //
        // The agent declares the palette of what it SENDS — "black 100%" for two wings — and this
        // file is what inspect_creature reads to answer "what colour is it". Persisting the
        // declaration verbatim alongside the resolved parts made an orange creature report "Its
        // colours: black 8%", which is the very misinformation that issue removed, reintroduced by
        // the fix for a different one. Caught in a run's recorded palette, not by a gate.
        //
        // Recomputed from the resolved parts, so it is measured rather than claimed.
        JsonArray palette = new JsonArray();
        SculptCritique.palette(resolved).entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    JsonObject share = new JsonObject();
                    share.addProperty("colour", e.getKey());
                    share.addProperty("percent", (int) Math.round(e.getValue() * 100));
                    palette.add(share);
                });
        spec.add("palette", palette);
        return GSON.toJson(spec);
    }

    /** One part, in the same shape {@code CreatureSculpt.parseParts} reads back. */
    static JsonObject toJson(CreatureSculpt.Part p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.name());
        if (p.parent() != null && !p.parent().isBlank()) {
            o.addProperty("parent", p.parent());
        }
        o.add("from", vec(p.from()));
        o.add("to", vec(p.to()));
        if (p.pivot() != null && (p.pivot()[0] != 0 || p.pivot()[1] != 0 || p.pivot()[2] != 0)) {
            o.add("pivot", vec(p.pivot()));
        }
        if (p.rotation() != null
                && (p.rotation()[0] != 0 || p.rotation()[1] != 0 || p.rotation()[2] != 0)) {
            o.add("rotation", vec(p.rotation()));
        }
        if (p.inflate() != 0) {
            o.addProperty("inflate", p.inflate());
        }
        // Hex, because that round-trips exactly; a colour NAME would be re-parsed to a slightly
        // different int and the creature would drift a shade on every edit.
        o.addProperty("colour", String.format("#%06X", p.colour() & 0xFFFFFF));
        if (p.pattern() != null && !p.pattern().isBlank()) {
            o.addProperty("pattern", p.pattern());
            o.addProperty("patternColour", String.format("#%06X", p.patternColour() & 0xFFFFFF));
        }
        return o;
    }

    private static JsonArray vec(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v == null ? new float[] {0, 0, 0} : v) {
            a.add(f);
        }
        return a;
    }
}
