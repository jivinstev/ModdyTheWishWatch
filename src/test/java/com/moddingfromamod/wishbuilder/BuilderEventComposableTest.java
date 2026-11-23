package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every ready-made event is a composition</b> (no "Pick one to start from"). An event is what
 * happens and what it is made of ({@link BuilderEvents}), then the tune dialog. Each ready-made (it rains chickens, the
 * tidal wave, the meteor) must lower to the SAME {@code stage_event} call as its composition, at every size and count,
 * named or not; a make saved from one reads as that composition. Pure.
 */
class BuilderEventComposableTest {

    private static final LibraryPresets P = LibraryPresets.SHIPPED;

    private static BuilderFlow flow() {
        return new BuilderFlow(BuilderAllowlist.REVIEWED, P);
    }

    private static JsonObject stage(BuilderSpec spec) {
        List<BuilderLowering.Call> calls = BuilderLowering.lower(spec, "x_1", P);
        return calls.stream().filter(c -> c.tool().equals("stage_event")).findFirst().orElseThrow().args();
    }

    /** A ready-made spec as it was stored (the preset, not yet migrated): the lowering's preset path. */
    private static BuilderSpec ready(String preset, int size, int count, String name) {
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.EVENT, "preset:" + preset, "", size, List.of(), "",
                count, "", name, Map.of());
    }

    private static BuilderSpec composed(String event, String what, int size, int count, String name) {
        Map<String, String> opts = new java.util.LinkedHashMap<>();
        opts.put("motion", "motion:" + BuilderEvents.byId(event).motion());
        opts.put("event", event);
        opts.put("what", what);
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.EVENT, "", "", size, List.of(), "", count, "", name, opts);
    }

    @Test
    @DisplayName("every ready-made event lowers to the same stage_event call as its composition, at every size and count")
    void everyReadyMadeIsAComposition() {
        assertEquals(3, BuilderEvents.READY_MADES.size(), "chicken rain, the tidal wave and the meteor");
        Map<String, String> bad = new TreeMap<>();
        BuilderEvents.READY_MADES.forEach((preset, c) -> {
            assertNotNull(P.read(preset), preset + " is not in the library");
            for (int size = 1; size <= 4; size++) {
                for (int count : List.of(1, 3)) {
                    for (String name : List.of("", "Splash")) {
                        JsonObject was = stage(ready(preset, size, count, name));
                        JsonObject now = stage(composed(c[0], c[1], size, count, name));
                        if (!was.equals(now)) {
                            bad.put(preset + " size " + size + " count " + count + (name.isEmpty() ? "" : " named"),
                                    was + "  vs  " + now);
                        }
                    }
                }
            }
        });
        assertEquals(Map.of(), bad, "a ready-made event is not its composition");
    }

    @Test
    @DisplayName("each ready-made's whole design is said by its composition: nothing in its file is dropped")
    void nothingInTheFileIsDropped() {
        BuilderEvents.READY_MADES.forEach((preset, c) -> {
            JsonObject file = P.read(preset);
            JsonObject args = BuilderEvents.args(c[0], c[1]);
            for (Map.Entry<String, JsonElement> e : file.entrySet()) {
                if (List.of("name", "title", "size").contains(e.getKey())) {
                    continue;   // the lowering's own: the make's id, its words (BuilderEvents.title), the tune's size
                }
                assertEquals(e.getValue(), args.get(e.getKey()), preset + "'s " + e.getKey());
            }
            assertEquals(file.get("title").getAsString(), BuilderEvents.title(c[0], c[1]), preset + "'s words");
        });
        // The meteor's follow-ons are still in the library (a splash in water, a crater on land).
        for (String d : List.of("events/meteor_splash", "events/meteor_crater")) {
            assertNotNull(P.read(d), d);
        }
    }

    @Test
    @DisplayName("a make saved from a ready-made event reads as its composition, name, size and count kept")
    void aSavedReadyMadeMigrates() {
        BuilderEvents.READY_MADES.forEach((preset, c) -> {
            BuilderSpec old = BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"event\",\"from\":\"preset:"
                    + preset + "\",\"size\":3,\"count\":2,\"name\":\"Mine\"}").getAsJsonObject());
            assertEquals("", old.from(), preset);
            assertEquals(c[0], old.opt("event"));
            assertEquals(c[1], old.opt("what"));
            assertEquals("motion:" + BuilderEvents.byId(c[0]).motion(), old.opt("motion"));
            assertEquals(3, old.size());
            assertEquals(2, old.count());
            assertEquals("Mine", old.name());
            assertEquals(stage(ready(preset, 3, 2, "Mine")), stage(old));
        });
        // A follow-on design was never a make of its own, and is left alone.
        BuilderSpec odd = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"event\",\"from\":\"preset:events/meteor_crater\"}").getAsJsonObject());
        assertEquals("preset:events/meteor_crater", odd.from());
    }

    @Test
    @DisplayName("the event kind opens on 'What happens?' (no ready-made page); each ready-made is reached by its presses")
    void thePagesAreAPureBuilder() {
        BuilderFlow f = flow();
        f.press("event");
        assertEquals(BuilderFlow.Step.EVENT_MOTION, f.step());
        assertEquals("What happens?", f.page().title());
        assertTrue(flow().fromIds(BuilderSpec.Kind.EVENT).isEmpty(), "a ready-made event page is back");
        assertFalse(BuilderFlowTest.ids(f.page()).contains(BuilderFlow.MAKE_UP_ID), "no 'make one up' tile");
        BuilderEvents.READY_MADES.forEach((preset, c) -> {
            BuilderFlow g = flow();
            g.press("event");
            g.press("event:" + c[0]);
            if (g.step() == BuilderFlow.Step.EVENT_WHAT) {
                g.press("what:" + c[1]);
            }
            assertEquals(BuilderFlow.Step.TUNE, g.step(), preset);
            BuilderSpec spec = g.spec(Map.of("size", "2"));
            assertEquals(stage(ready(preset, 2, 1, spec.name())), stage(spec), preset + " by the Builder's own presses");
        });
        // A meteor is a fiery rock: nothing to ask, straight to the tune.
        BuilderFlow m = flow();
        m.press("event");
        m.press("event:meteor");
        assertEquals(BuilderFlow.Step.TUNE, m.step());
        // Back from "What happens?" is the kinds.
        BuilderFlow b = flow();
        b.press("event");
        b.press(com.moddingfromamod.ui.PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.KIND, b.step());
    }

    @Test
    @DisplayName("an edit of a migrated ready-made reopens at its first page and, unchanged, makes the same event")
    void anEditRoundTrips() {
        BuilderSpec was = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"event\",\"from\":\"preset:events/tidal_wave\",\"size\":4}").getAsJsonObject());
        BuilderFlow f = BuilderFlow.editing(BuilderAllowlist.REVIEWED, P, "mine_1", was);
        assertEquals(BuilderFlow.Step.EVENT_MOTION, f.step(), "an edit reopens at the event's first page (#2281)");
        EditWalk.toTune(f);
        Map<String, String> sent = new HashMap<>();
        for (com.moddingfromamod.ui.TuneDialog.Input in : f.tune().inputs()) {
            if (in instanceof com.moddingfromamod.ui.TuneDialog.Choice ch) {
                sent.put(ch.key(), ch.initial());
            } else if (in instanceof com.moddingfromamod.ui.TuneDialog.Slider sl) {
                sent.put(sl.key(), String.valueOf(sl.initial()));
            }
        }
        BuilderSpec again = f.spec(sent);
        assertEquals(was.opts(), again.opts());
        assertEquals(4, again.size());
        JsonObject a = stage(was);
        JsonObject b = stage(again);
        a.remove("title");
        b.remove("title");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("every motion and what lowers and is allowed, and what a motion carries never names a block outside its own")
    void everyPairLowers() {
        List<String> bad = new ArrayList<>();
        for (BuilderEvents.Motion mo : BuilderEvents.ALL) {
            for (BuilderEvents.What w : mo.whats()) {
                BuilderSpec spec = composed(mo.id(), w.id(), 2, 1, "");
                if (!BuilderAllowlist.REVIEWED.problems(spec).isEmpty()) {
                    bad.add(mo.id() + "/" + w.id() + ": " + BuilderAllowlist.REVIEWED.problems(spec));
                }
                JsonObject a = stage(spec);
                if (!mo.motion().equals(a.get("motion").getAsString())) {
                    bad.add(mo.id() + "/" + w.id() + " stages " + a.get("motion"));
                }
            }
        }
        assertEquals(List.of(), bad);
    }
}
