package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for the thing that travels.</b>
 *
 * <p>The recipe exists because of one piece of arithmetic — shapes are far smaller than pixels —
 * and that arithmetic stops being true if the shapes get small enough. So the cap is asserted
 * against the image it replaces rather than as a number somebody chose, and the round trip is
 * asserted because this rides in a contraption's NBT and is re-read on every assembly.
 */
class PaintRecipeTest {

    @Test
    void aRecipeIsFarSmallerThanTheImageItDrawsEvenAtTheCap() {
        // THE WHOLE ARGUMENT. CapturedStructure's ~2 MB frame lesson is why a picture is stored as
        // shapes, and a cap that let a recipe grow past its own image would quietly undo it.
        List<CreatureSculpt.PaintRect> many = new ArrayList<>();
        for (int i = 0; i < PaintRecipe.MAX_RECTS * 3; i++) {
            many.add(new CreatureSculpt.PaintRect(i, i, 2, 2, 0xFF0000));
        }
        PaintRecipe worst = new PaintRecipe(PaintArt.SIZE, many);
        assertEquals(PaintRecipe.MAX_RECTS, worst.rects().size(), "the cap did not hold");
        assertTrue(worst.approximateBytes() * 4 < worst.bytesAsAnImage(),
                "at the cap a recipe is " + worst.approximateBytes() + " bytes against "
                        + worst.bytesAsAnImage() + " for the image — that is no longer a saving, "
                        + "and the reason to store shapes instead of pixels has gone");
        // And nowhere near the network frame that started all this.
        assertTrue(worst.approximateBytes() < 2_000_000 / 10,
                "a recipe at the cap is within sight of Minecraft's ~2 MB frame limit");
    }

    @Test
    void aTypicalPictureIsTinyRatherThanMerelyUnderTheCap() {
        // A face is a couple of dozen shapes. If THAT is already kilobytes the design is wrong.
        PaintRecipe face = new PaintRecipe(256, List.of(
                new CreatureSculpt.PaintRect(0, 0, 256, 256, 0x00A000),
                new CreatureSculpt.PaintRect(40, 60, 60, 40, 0xFFFFFF),
                new CreatureSculpt.PaintRect(156, 60, 60, 40, 0xFFFFFF),
                new CreatureSculpt.PaintRect(60, 70, 25, 25, 0x000000),
                new CreatureSculpt.PaintRect(176, 70, 25, 25, 0x000000),
                new CreatureSculpt.PaintRect(70, 170, 116, 30, 0xC00000)));
        assertTrue(face.approximateBytes() < 200,
                "six shapes should be under 200 bytes, was " + face.approximateBytes());
        assertEquals(256 * 256 * 4, face.bytesAsAnImage());
    }

    @Test
    void theRoundTripIsExact() {
        // This is the trip a contraption makes on every assembly: saved to NBT, read back, drawn.
        PaintRecipe before = new PaintRecipe(128, List.of(
                new CreatureSculpt.PaintRect(1, 2, 3, 4, 0x123456),
                new CreatureSculpt.PaintRect(-7, 9, 300, 11, 0xFFFFFF)));
        PaintRecipe after = PaintRecipe.fromInts(before.toInts());
        assertEquals(before.size(), after.size());
        assertEquals(before.rects(), after.rects());
    }

    @Test
    void damagedStoredDataCostsAShapeRatherThanThePicture() {
        // A truncated tag is a real thing on a world that was killed mid-save. Losing the last
        // rectangle is a blemish; throwing here would take the block, and on a contraption the
        // whole carriage's render pass with it.
        PaintRecipe full = new PaintRecipe(64, List.of(
                new CreatureSculpt.PaintRect(0, 0, 10, 10, 0xFF0000),
                new CreatureSculpt.PaintRect(10, 10, 10, 10, 0x00FF00)));
        int[] ints = full.toInts();
        int[] truncated = java.util.Arrays.copyOf(ints, ints.length - 2);
        PaintRecipe back = PaintRecipe.fromInts(truncated);
        assertEquals(1, back.rects().size(), "the intact shape should survive");
        assertEquals(64, back.size());

        assertTrue(PaintRecipe.fromInts(null).isEmpty());
        assertTrue(PaintRecipe.fromInts(new int[0]).isEmpty());
        assertTrue(PaintRecipe.fromInts(new int[]{256}).isEmpty());
    }

    @Test
    void theSizeIsClampedIntoSomethingWorthBaking() {
        // Below MIN_SIZE a picture is block art again, which is the thing this exists to escape;
        // above MAX_SIZE it is memory nobody standing on a platform can resolve.
        assertEquals(PaintRecipe.MIN_SIZE, new PaintRecipe(1, List.of()).size());
        assertEquals(PaintRecipe.MIN_SIZE, new PaintRecipe(-40, List.of()).size());
        assertEquals(PaintRecipe.MAX_SIZE, new PaintRecipe(99999, List.of()).size());
        assertEquals(256, new PaintRecipe(256, List.of()).size());
        assertTrue(PaintRecipe.MIN_SIZE * PaintRecipe.MIN_SIZE > 169,
                "the smallest picture must still beat a 13x13 block face — that is the whole point");
    }

    @Test
    void anAgentsDescriptionParsesWithTheColourVocabularyItAlreadyKnows() {
        // Not a second colour language: the same names sculpt_creature already takes.
        PaintRecipe r = PaintRecipe.parse(JsonParser.parseString("""
            {"size": 128, "rects": [
              {"x":0,"y":0,"w":128,"h":128,"colour":"red"},
              {"x":10,"y":10,"w":20,"h":20,"color":"#FFD800"}
            ]}""").getAsJsonObject());
        assertEquals(128, r.size());
        assertEquals(2, r.rects().size());
        assertEquals(0xFFD800, r.rects().get(1).colour() & 0xFFFFFF, "a hex colour was not read");
        assertTrue((r.rects().get(0).colour() & 0xFF0000) > 0x800000, "\"red\" did not come out red");
    }

    @Test
    void oneBadShapeCostsThatShapeAndNothingElse() {
        // The list comes from an agent acting on a child's description. Refusing the whole picture
        // over one malformed entry is how a child loses a wish to a typo.
        PaintRecipe r = PaintRecipe.parse(JsonParser.parseString("""
            {"rects": [
              {"x":0,"y":0,"w":10,"h":10,"colour":"red"},
              {"x":0,"y":0,"w":0,"h":10,"colour":"blue"},
              "not an object",
              {"x":0,"y":0,"h":10,"colour":"blue"},
              {"x":5,"y":5,"w":4,"h":4,"colour":"green"}
            ]}""").getAsJsonObject());
        assertEquals(2, r.rects().size(),
                "expected the two usable shapes to survive, got " + r.rects());
    }

    @Test
    void nonsenseParsesToABlankCanvasRatherThanThrowing() {
        assertTrue(PaintRecipe.parse(null).isEmpty());
        assertTrue(PaintRecipe.parse(new JsonObject()).isEmpty());
        assertTrue(PaintRecipe.parse(JsonParser.parseString(
                "{\"size\":\"big\",\"rects\":\"lots\"}").getAsJsonObject()).isEmpty());
    }

    @Test
    void paintingOrderIsPreservedBecauseItIsTheOnlyThingThatDecidesWhatIsOnTop() {
        // The painter draws in list order, so this is not a detail: reorder it and a face's eyes
        // end up behind its head. Same rule as SculptPattern's overlays.
        List<CreatureSculpt.PaintRect> in = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            in.add(new CreatureSculpt.PaintRect(i, 0, 5, 5, i));
        }
        PaintRecipe back = PaintRecipe.fromInts(new PaintRecipe(64, in).toInts());
        for (int i = 0; i < 20; i++) {
            assertEquals(i, back.rects().get(i).colour(), "order changed at " + i);
        }
    }
}
