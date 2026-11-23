package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every ready-made picture is a composition</b> (no "Pick one to start from"). A picture is now
 * a subject (a library drawing), what is behind it and a frame size. Each of the 22 ready-mades must come out of those
 * picks PIXEL FOR PIXEL as its library file draws it, at its own frame size, plain and in a colour scheme, and a make
 * saved from a ready-made reads as that composition. What is behind a picture never covers any of the drawing, and
 * fills the whole canvas. Pure: a rasteriser of the picture's rects, in order, over its canvas.
 */
class BuilderPictureComposableTest {

    private static final LibraryPresets P = LibraryPresets.SHIPPED;

    private static BuilderFlow flow() {
        return new BuilderFlow(BuilderAllowlist.REVIEWED, P);
    }

    /** The colour of every pixel ({@code null} = nothing painted), the later rect on top, as the painter draws. */
    static String[] raster(JsonObject picture) {
        int s = picture.get("size").getAsInt();
        String[] px = new String[s * s];
        for (JsonElement e : picture.getAsJsonArray("rects")) {
            JsonObject r = e.getAsJsonObject();
            String c = r.get("colour").getAsString().toUpperCase(Locale.ROOT);
            int x0 = Math.max(0, r.get("x").getAsInt());
            int y0 = Math.max(0, r.get("y").getAsInt());
            for (int y = y0; y < Math.min(s, y0 + r.get("h").getAsInt()); y++) {
                for (int x = x0; x < Math.min(s, x0 + r.get("w").getAsInt()); x++) {
                    px[y * s + x] = c;
                }
            }
        }
        return px;
    }

    private static JsonObject paint(BuilderSpec spec) {
        List<BuilderLowering.Call> calls = BuilderLowering.lower(spec, "x_1", P);
        return calls.stream().filter(c -> c.tool().equals("paint_surface")).findFirst().orElseThrow().args();
    }

    private static JsonObject picture(JsonObject args) {
        return JsonParser.parseString(args.get("picture").getAsString()).getAsJsonObject();
    }

    private static BuilderSpec composed(String subject, String background, int frame, String scheme) {
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.PICTURE, "", scheme, 2, List.of(), "", 1, "", "",
                BuilderPictures.opts(subject, background, frame));
    }

    @Test
    @DisplayName("every one of the ready-made pictures is its subject with just the drawing, pixel for pixel, at its own frame")
    void everyReadyMadeIsAComposition() {
        List<String> subjects = flow().subjectIds();
        assertTrue(subjects.size() >= 22, "the subjects did not load: " + subjects);
        Map<String, String> bad = new TreeMap<>();
        for (String s : subjects) {
            JsonObject drawn = P.read(s);
            for (String scheme : List.of("", "ocean", "royal")) {
                JsonObject args = paint(composed(s, BuilderPictures.DRAWN, 0, scheme));
                JsonObject want = new JsonObject();
                want.add("size", drawn.get("size"));
                JsonArray rects = drawn.getAsJsonArray("rects").deepCopy();
                Schemes.Scheme sc = Schemes.byId(scheme);
                if (sc != null) {   // a scheme recolours the drawing's own colours, as it always has
                    Map<String, String> to = Schemes.recolourRects(rects, sc);
                    for (JsonElement e : rects) {
                        JsonObject r = e.getAsJsonObject();
                        String c = r.get("colour").getAsString().toUpperCase(Locale.ROOT);
                        if (to.containsKey(c)) {
                            r.addProperty("colour", to.get(c));
                        }
                    }
                }
                want.add("rects", rects);
                if (!java.util.Arrays.equals(raster(want), raster(picture(args)))) {
                    bad.put(s + (scheme.isEmpty() ? "" : " in " + scheme), "different pixels");
                }
                int own = BuilderPictures.ownFrame(drawn);
                if (args.get("wide").getAsInt() != own || args.get("tall").getAsInt() != own) {
                    bad.put(s + " frame", "hangs " + args.get("wide") + "x" + args.get("tall") + ", its drawing says " + own);
                }
            }
        }
        assertEquals(Map.of(), bad, "a ready-made picture is not its composition");
    }

    @Test
    @DisplayName("a make saved from a ready-made picture reads as that composition, name and colours kept")
    void aSavedReadyMadeMigrates() {
        for (String s : flow().subjectIds()) {
            BuilderSpec old = BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"picture\",\"from\":\"preset:" + s
                    + "\",\"scheme\":\"ocean\",\"size\":2,\"name\":\"Mine\"}").getAsJsonObject());
            assertTrue(BuilderPictures.composed(old), s + " did not migrate: " + old);
            assertEquals("", old.from());
            assertEquals("preset:" + s, old.opt("subject"));
            assertEquals(BuilderPictures.DRAWN, old.opt("background"));
            assertEquals("", old.opt("frame"), "its own frame, not a guessed one");
            assertEquals("ocean", old.scheme());
            assertEquals("Mine", old.name());
            assertArrayEquals(raster(picture(paint(composed(s, BuilderPictures.DRAWN, 0, "ocean")))), raster(picture(paint(old))));
        }
    }

    @Test
    @DisplayName("what is behind a picture fills its canvas and never covers a pixel of the drawing, at every frame")
    void aBackgroundGoesBehind() {
        Map<String, String> bad = new TreeMap<>();
        for (String s : flow().subjectIds()) {
            JsonObject drawn = P.read(s);
            String[] alone = raster(drawn);
            for (BuilderPictures.Background b : BuilderPictures.fitting(drawn)) {
                for (int frame = BuilderPictures.SMALLEST; frame <= BuilderPictures.BIGGEST; frame++) {
                    BuilderSpec spec = composed(s, b.id(), frame, "");
                    assertTrue(BuilderAllowlist.REVIEWED.problems(spec).isEmpty(), BuilderAllowlist.REVIEWED.problems(spec).toString());
                    JsonObject args = paint(spec);
                    assertEquals(frame, args.get("wide").getAsInt());
                    assertEquals(frame, args.get("tall").getAsInt());
                    String[] px = raster(picture(args));
                    for (int i = 0; i < px.length; i++) {
                        if (alone[i] != null && !alone[i].equals(px[i])) {
                            bad.put(s + " on " + b.id(), "covers the drawing at pixel " + i);
                            break;
                        }
                        if (!b.id().equals(BuilderPictures.DRAWN) && px[i] == null) {
                            bad.put(s + " on " + b.id(), "leaves pixel " + i + " unpainted");
                            break;
                        }
                    }
                }
            }
        }
        assertEquals(Map.of(), bad);
    }

    @Test
    @DisplayName("a drawing that fills its own canvas (the moon, the sunset) is offered just the drawing; the rest every background")
    void aFullDrawingHasNothingBehindIt() {
        List<String> full = new ArrayList<>();
        for (String s : flow().subjectIds()) {
            BuilderFlow f = flow();
            f.press("picture");
            f.press(s);
            if (BuilderPictures.fillsItsCanvas(P.read(s))) {
                full.add(s);
                assertEquals(BuilderFlow.Step.TUNE, f.step(), s + " asks what is behind a drawing that covers it all");
            } else {
                assertEquals(BuilderFlow.Step.PICT_BACKGROUND, f.step(), s);
                assertEquals(BuilderPictures.BACKGROUNDS.size(), f.page().tiles().size(), s);
            }
        }
        assertTrue(full.contains("pictures/moon") && full.contains("pictures/sunset"), full.toString());
        assertThrowsOn("pictures/moon", "sky");
    }

    private static void assertThrowsOn(String subject, String background) {
        try {
            paint(composed(subject, background, 0, ""));
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError(background + " was painted behind " + subject + ", which covers its whole canvas");
    }

    @Test
    @DisplayName("the picture kind opens on 'What will you paint?' (no ready-made page), then 'What is behind it?', then the tune")
    void thePagesAreAPurebuilder() {
        BuilderFlow f = flow();
        f.press("picture");
        assertEquals(BuilderFlow.Step.PICT_SUBJECT, f.step());
        assertTrue(f.page().title().startsWith("What will you paint?"), f.page().title());
        assertTrue(flow().fromIds(BuilderSpec.Kind.PICTURE).isEmpty(), "a ready-made picture page is back");
        // Every page of subjects, through "More".
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 10 && seen.size() < f.subjectIds().size(); i++) {
            f.page().tiles().forEach(t -> {
                if (t.id().startsWith("pictures/") && !seen.contains(t.id())) {
                    seen.add(t.id());
                }
            });
            f.press(com.moddingfromamod.ui.PickPage.NEXT_ID);
        }
        assertEquals(f.subjectIds(), seen, "a subject cannot be reached through the pages");
        BuilderFlow g = flow();
        g.press("picture");
        g.press("pictures/duck");
        assertEquals(BuilderFlow.Step.PICT_BACKGROUND, g.step());
        g.press("background:sky");
        assertEquals(List.of("frame", "scheme"), g.tune().keys());
        BuilderSpec spec = g.spec(Map.of("frame", "4"));
        assertEquals("preset:pictures/duck", spec.opt("subject"));
        assertEquals("sky", spec.opt("background"));
        assertEquals("4", spec.opt("frame"));
        assertEquals(4, paint(spec).get("wide").getAsInt());
        // Back from what is behind it is the subject page; back from the subject page is the kinds.
        g = flow();
        g.press("picture");
        g.press("pictures/duck");
        g.press(com.moddingfromamod.ui.PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.PICT_SUBJECT, g.step());
        g.press(com.moddingfromamod.ui.PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.KIND, g.step());
    }

    @Test
    @DisplayName("an edit reopens a composed picture at its subject, and changing nothing makes the same picture")
    void anEditRoundTrips() {
        BuilderSpec was = composed("pictures/star", "night", 3, "ocean");
        BuilderFlow f = BuilderFlow.editing(BuilderAllowlist.REVIEWED, P, "mine_1", BuilderSpec.read(was.toJson()));
        assertEquals(BuilderFlow.Step.PICT_SUBJECT, f.step(), "an edit reopens at the picture's subject (#2281)");
        EditWalk.toTune(f);
        // The dialog opens on what it had, and sends it back unchanged.
        com.moddingfromamod.ui.TuneDialog d = f.tune();
        Map<String, String> sent = new java.util.HashMap<>();
        for (com.moddingfromamod.ui.TuneDialog.Input in : d.inputs()) {
            if (in instanceof com.moddingfromamod.ui.TuneDialog.Choice c) {
                sent.put(c.key(), c.initial());
            } else if (in instanceof com.moddingfromamod.ui.TuneDialog.Slider sl) {
                sent.put(sl.key(), String.valueOf(sl.initial()));
            }
        }
        assertEquals("ocean", sent.get("scheme"));
        assertEquals("3", sent.get("frame"));
        BuilderSpec again = f.spec(sent);
        assertEquals(was.opts(), again.opts());
        assertEquals("ocean", again.scheme());
        assertFalse(BuilderLowering.lower(again, "mine_1", P).isEmpty());
    }
}
