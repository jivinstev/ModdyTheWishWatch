package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.moddingfromamod.wish.WishLessons;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Is the agent still told to leave the background alone?</b>
 *
 * <p>The picture pipeline has always been able to produce a transparent background:
 * {@code CreatureFiles.writeTexture} paints into a {@code TYPE_INT_ARGB} image and fills only the
 * rectangles it is given, and {@code PaintPanelRenderer} draws through
 * {@code RenderType.entityCutoutNoCull}, which discards low-alpha pixels. Nothing needed building.
 *
 * <p>What actually produced the flat green slabs was the <b>tool schema's own worked example</b>,
 * whose first rectangle was a full-canvas fill and whose prose read <q>the background goes
 * first</q>. Measured on a real Spikor train: all 15 pictures came back <b>100% opaque</b>, corner
 * pixel {@code (80, 224, 80)}. The agent was doing exactly what it had been shown.
 *
 * <p>That is the trap CLAUDE.md already records for Create's copycat panel — a schema that
 * documents a broken form is followed, and no check the agent could run would say why. So the fix
 * lives where it is READ, and this pins it there.
 */
class PaintBackgroundLessonTest {

    /**
     * The source with {@code //} comments removed — i.e. only what the agent is actually told.
     *
     * <p>Earned immediately. The first cut of {@link #theDescriptionDoesNotTeachABackdrop} went red
     * against a correct fix, because the comment explaining the bug <i>quotes</i> the wording it
     * replaced. A source scan reads comments, so a note describing a defect is indistinguishable
     * from the defect — and the natural response to that failure is to water down the comment,
     * which is the wrong half to give up.
     *
     * <p>Same family as the guard that anchored on {@code assembledCarriageNear} after the call had
     * become a comment: a scan must look at the thing that RUNS.
     */
    private static String instructionsOnly(String javaSource) {
        return javaSource.replaceAll("(?m)^\\s*//.*$", "");
    }

    /** The schema is Java source; reading it is the only way to check what the agent is told. */
    private static String schema() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/PaintSurfaceTool.java"));
    }

    @Test
    @DisplayName("the schema's worked example does not open with a full-canvas fill")
    void theExampleNoLongerPaintsABackdrop() throws IOException {
        // The example IS the instruction. A 256x256 rectangle at the origin is a backdrop however
        // the surrounding prose is worded, so this looks for the shape rather than for the words.
        String src = schema();
        int at = src.indexOf("The picture, as JSON");
        assertTrue(at > 0, "the picture schema is gone — nothing tells the agent how to paint");
        String example = src.substring(at, Math.min(src.length(), at + 1200));
        assertFalse(example.contains("\\\"w\\\":256,") && example.contains("\\\"h\\\":256,"),
                "the worked example paints a full-canvas rectangle again, which is what put every "
                        + "Kroakling on a flat green slab. The example was:\n" + example);
    }

    @Test
    @DisplayName("the worked example draws the subject BIG, not a stamp in the middle")
    void theExampleFillsThePicture() throws IOException {
        // ⚠ THE THIRD TIME THIS FILE'S EXAMPLE TAUGHT THE WRONG THING, AND THE SUBTLEST.
        //
        // Removing the full-canvas backdrop left an example whose subject was 80x90 inside a 256
        // picture — 12% of the area. The agent copied the SCALE as faithfully as it had copied the
        // backdrop, and a real Spikor came back as a postage stamp on the nose of his own train.
        // Nothing failed: transparency worked exactly as designed, and the result was worse than
        // before the change.
        //
        // The backdrop had been doing a job nobody had noticed — it set the SIZE. Take it away
        // without saying "fill the picture" and you have not removed a slab, you have shrunk the
        // subject.
        String src = schema();
        int at = src.indexOf("The picture, as JSON");
        assertTrue(at > 0, "the picture schema is gone");
        String example = src.substring(at, Math.min(src.length(), at + 400));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\\\\"w\\\\\":(\\d+)").matcher(example);
        int widest = 0;
        while (m.find()) {
            widest = Math.max(widest, Integer.parseInt(m.group(1)));
        }
        assertTrue(widest >= 128 && widest < 256,
                "the example's biggest rectangle is " + widest + " wide in a 256 picture. Under "
                        + "half is a stamp the agent will copy; a full 256 is the backdrop this "
                        + "file already removed once. The example is the instruction.");
    }

    @Test
    @DisplayName("the schema says outright not to paint a background")
    void theSchemaForbidsABackdrop() throws IOException {
        String src = schema().toLowerCase(Locale.ROOT);
        assertTrue(src.contains("do not paint a background"),
                "removing the bad example is not enough on its own — without a rule, 'paint a face' "
                        + "reads as 'paint a picture of a face', and a picture has edges");
        assertTrue(src.contains("see-through") || src.contains("transparent"),
                "the agent is told not to paint a background but not WHY, so the first wish that "
                        + "wants an opaque one has no way to reason about it");
    }

    @Test
    @DisplayName("the tool DESCRIPTION does not teach a backdrop either")
    void theDescriptionDoesNotTeachABackdrop() throws IOException {
        // ⚠ THE SECOND PLACE, AND IT WAS FOUND BY GREPPING RATHER THAN BY A TEST FAILING. The
        // picture schema was fixed while describe() still read "biggest first (background, then
        // shapes on top of it, in order)" — which the agent reads FIRST, so the fix would have
        // changed nothing and the next run would have looked like the change had not worked.
        //
        // So this asserts on the whole file rather than on one property: any surviving instruction
        // to paint a background anywhere in it is the bug, wherever it lives.
        String src = schema();
        int describe = src.indexOf("return \"Paint a real picture");
        assertTrue(describe > 0, "the tool description is gone");
        String desc = instructionsOnly(src.substring(describe, src.indexOf("}", describe)));
        assertFalse(desc.toLowerCase(Locale.ROOT).contains("background, then"),
                "describe() teaches a backdrop again — and it is read BEFORE the picture schema, "
                        + "so fixing only the example changes nothing. It said:\n" + desc);
        assertTrue(desc.toLowerCase(Locale.ROOT).contains("never a background")
                        || desc.toLowerCase(Locale.ROOT).contains("no background"),
                "the description lists what to paint without saying not to paint a backdrop, so "
                        + "'biggest first' still reads as an invitation to start with the canvas");
    }

    @Test
    @DisplayName("the wish class that hangs pictures carries the lesson")
    void theLessonReachesBuildings() {
        // paint_surface served trains and buildings; trains went with Create, and a train
        // is a building now. The rule that keeps paying, from CLAUDE.md: a defect found in one
        // wish class is a defect CLASS found.
        for (String kind : new String[] {"building"}) {
            String text = WishLessons.text(kind).toLowerCase(Locale.ROOT);
            assertTrue(text.length() > 500,
                    "lessons for '" + kind + "' are missing or nearly empty, so nothing pinned "
                            + "here reaches the agent regardless of what the file says");
            assertTrue(text.contains("see-through") || text.contains("background"),
                    "the '" + kind + "' lessons never mention the background, so a picture there "
                            + "will keep arriving on a coloured slab");
        }
    }
}
