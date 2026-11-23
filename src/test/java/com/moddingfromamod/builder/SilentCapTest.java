package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>No silent caps.</b>
 *
 * <p>{@link BuildPlan} has four limits and only one of them used to say anything.
 * {@code MAX_BLOCKS} refuses with a sentence; {@code MAX_SHAPES} dropped shapes 65+ on the floor,
 * and {@code MAX_DIM} / {@code MAX_OFFSET} silently clamped every size and offset.
 *
 * <p>What that meant in play: ask for a 150-block tower and you get 96 and nobody mentions it. Ask
 * for an eighty-part castle and the last sixteen parts never appear — and those are the roof and
 * the detail, because that is the order a design gets authored in. The agent had no idea anything
 * had happened, so it said "done" and showed the child a truncated building.
 *
 * <p>This is a mod repo's own hard-won rule ("no silent caps: if a build bounds coverage, say
 * what was dropped") applied to the wish engine.
 */
class SilentCapTest {

    private static JsonObject shape(int w, int h, int d, int dx, int dy, int dz) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "box");
        o.addProperty("dx", dx);
        o.addProperty("dy", dy);
        o.addProperty("dz", dz);
        o.addProperty("width", w);
        o.addProperty("height", h);
        o.addProperty("depth", d);
        o.addProperty("block", "minecraft:stone");
        o.addProperty("hollow", true);
        return o;
    }

    private static JsonObject planJson(JsonObject... shapes) {
        JsonObject o = new JsonObject();
        o.addProperty("id", "capped");
        o.addProperty("name", "Capped");
        JsonArray arr = new JsonArray();
        for (JsonObject s : shapes) {
            arr.add(s);
        }
        o.add("shapes", arr);
        return o;
    }

    @Test
    @DisplayName("a tower taller than the limit is reported, not quietly shrunk")
    void oversizeShapesAreReported() {
        JsonObject json = planJson(shape(10, 150, 10, 0, 0, 0));
        BuildPlan plan = BuildPlan.fromJson(json);
        List<BuildPlan.Requested> asked = BuildPlan.shapesAsRequested(json);

        // The clamp itself is fine and deliberate — 96 is the cap.
        assertTrue(plan.shapes().get(0).height() == BuildShape.MAX_DIM,
                "the shape should still be clamped to the cap");

        List<String> notes = plan.clampNotes(asked);
        assertFalse(notes.isEmpty(),
                "asking for a 150-block-tall tower and silently building a 96-block one is how "
                        + "\"make it REALLY tall\" comes out looking ignored. The change must be "
                        + "reported.");
        assertTrue(notes.get(0).contains("150") && notes.get(0).contains("96"),
                "the note must say what was asked for AND what was built, or the agent cannot "
                        + "tell the child what happened: " + notes);
    }

    @Test
    @DisplayName("a shape placed further away than the limit is reported, not quietly moved")
    void oversizeOffsetsAreReported() {
        JsonObject json = planJson(shape(4, 4, 4, 500, 0, 0));
        BuildPlan plan = BuildPlan.fromJson(json);
        List<String> notes = plan.clampNotes(BuildPlan.shapesAsRequested(json));
        assertFalse(notes.isEmpty(),
                "a shape asked for 500 blocks away was moved to " + BuildShape.MAX_OFFSET
                        + " without a word — which silently rearranges the building");
        assertTrue(notes.stream().anyMatch(n -> n.contains("moved closer")), notes.toString());
    }

    @Test
    @DisplayName("dropped shapes are detected — the failure that looks like an unfinished building")
    void droppedShapesAreDetected() {
        JsonObject[] many = new JsonObject[BuildPlan.MAX_SHAPES + 16];
        for (int i = 0; i < many.length; i++) {
            many[i] = shape(4, 4, 4, i, 0, 0);
        }
        JsonObject json = planJson(many);
        BuildPlan plan = BuildPlan.fromJson(json);
        List<BuildPlan.Requested> asked = BuildPlan.shapesAsRequested(json);

        assertTrue(plan.droppedShapes(asked),
                "16 shapes past the limit were dropped and nothing noticed. Those are the LAST "
                        + "shapes — the roof and the detail — so the building comes out visibly "
                        + "unfinished while the agent reports success.");
        List<String> notes = plan.clampNotes(asked);
        assertTrue(notes.stream().anyMatch(n -> n.contains("DROPPED")), notes.toString());
    }

    @Test
    @DisplayName("a plan that fits is reported as clean — no crying wolf")
    void aPlanWithinTheLimitsSaysNothing() {
        JsonObject json = planJson(shape(10, 10, 10, 0, 0, 0), shape(6, 3, 6, 2, 10, 2));
        BuildPlan plan = BuildPlan.fromJson(json);
        List<BuildPlan.Requested> asked = BuildPlan.shapesAsRequested(json);
        assertFalse(plan.droppedShapes(asked));
        assertTrue(plan.clampNotes(asked).isEmpty(),
                "a building that came out exactly as asked must produce no notes, or the warning "
                        + "becomes noise and stops being read: " + plan.clampNotes(asked));
    }
}
