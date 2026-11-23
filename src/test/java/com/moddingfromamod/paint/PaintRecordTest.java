package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

/**
 * <b>What an undo can read back.</b>
 *
 * <p>Written at the path-2 flip, because that is when this record stopped being a convenience and
 * became the <i>only</i> place the information lives. Before the flip a picture on a wall was a
 * panel, so an undo could read the facing and the span off its block state; after it, a picture on
 * a wall is map art and there is no block at that position at all. Every assertion here is one an
 * undo depends on, and each of them was reachable only by reading the world until this change.
 */
class PaintRecordTest {

    private static PaintRecipe face() {
        return new PaintRecipe(64, List.of(
                new CreatureSculpt.PaintRect(0, 0, 32, 32, 0xFF0000)));
    }

    private static PaintRecord vanillaCopy() {
        return new PaintRecord("zippy", "minecraft:overworld", 10, 70, -3,
                "zippy", true, "", PaintRecipe.blank(), 1, 1, "north", 6, 5);
    }

    /** The board a hung picture changed survives a save, cell for cell. */
    @Test
    void theBoardSurvivesTheRoundTrip() {
        var cells = List.of(new PaintRecord.BoardCell(1, 2, 3, "minecraft:glass", "minecraft:air"),
                new PaintRecord.BoardCell(1, 2, 4, "minecraft:air", "minecraft:dark_oak_planks"));
        PaintRecord back = PaintRecord.fromJson(vanillaCopy().withBoard(cells).toJson());
        assertEquals(cells, back.board());
        assertEquals(cells, back.withOwner("abc").board(), "stamping an owner keeps the board");
    }

    /** A record with no board writes no "board" key: nothing already saved changes by a byte. */
    @Test
    void aRecordWithNoBoardWritesExactlyWhatItAlwaysDid() {
        assertFalse(vanillaCopy().toJson().has("board"));
        assertTrue(PaintRecord.fromJson(vanillaCopy().toJson()).board().isEmpty());
    }

    @Test
    void theFacingAndTheSpanSurviveTheRoundTrip() {
        PaintRecord back = PaintRecord.fromJson(vanillaCopy().toJson());
        assertEquals("north", back.facing());
        assertEquals(6, back.wide());
        assertEquals(5, back.tall());
        assertTrue(back.hasVanillaCopy());
    }

    /**
     * <b>A record written before the flip must read back as "there is no map art here"</b> — not as
     * a claim that one tile is hanging somewhere. An undo believing the second would go looking for
     * a frame that never existed.
     */
    @Test
    void aRecordFromBeforeTheFlipHasNoVanillaCopy() {
        JsonObject old = JsonParser.parseString("""
                {"id":"legacy","dimension":"minecraft:overworld","x":1,"y":2,"z":3,
                 "key":"legacy","placed_panel":true,"previous_key":"",
                 "previous_width":1,"previous_height":1}""").getAsJsonObject();
        PaintRecord back = PaintRecord.fromJson(old);
        assertEquals("", back.facing());
        assertEquals(0, back.wide());
        assertEquals(0, back.tall());
        assertFalse(back.hasVanillaCopy());
    }

    /**
     * <b>Zero must survive as zero.</b> The two previous-span fields go through
     * {@code PanelGeometry.clampSpan}, whose floor is 1 — applying the same rule to these would
     * turn every panel-only record into a claim that a 1x1 picture is hung at that position, which
     * is precisely the difference between "take the maps down" and "there are no maps".
     */
    @Test
    void aSpanOfZeroIsNotClampedUpToOne() {
        PaintRecord panelOnly = new PaintRecord("car", "minecraft:overworld", 0, 0, 0,
                "car", true, "", PaintRecipe.blank(), 1, 1, "", 0, 0);
        assertEquals(0, panelOnly.wide());
        assertEquals(0, panelOnly.tall());
        assertFalse(panelOnly.hasVanillaCopy());
    }

    /** The pre-flip constructor still means what it meant: a panel, and no map art. */
    @Test
    void theCompatibilityConstructorSaysThereIsNoMapArt() {
        PaintRecord r = new PaintRecord("x", "minecraft:overworld", 1, 2, 3,
                "x", false, "old", face(), 2, 1);
        assertFalse(r.hasVanillaCopy());
        assertTrue(r.hadAPictureBefore());
        assertEquals(2, r.previousWidth());
    }

    /**
     * <b>The picture the record painted survives too</b>, which is what makes undoing a REPAINT
     * possible on a wall with no block entity to hold it.
     */
    @Test
    void theRecipeItPaintedSurvivesTheRoundTrip() {
        PaintRecord r = new PaintRecord("zippy", "minecraft:overworld", 1, 2, 3,
                "zippy", false, "", PaintRecipe.blank(), 1, 1, "north", 2, 2, face());
        assertTrue(r.hasRecipe());
        PaintRecord back = PaintRecord.fromJson(r.toJson());
        assertTrue(back.hasRecipe());
        assertEquals(face().toInts().length, back.recipe().toInts().length);
        assertEquals(face().rects().get(0).colour(), back.recipe().rects().get(0).colour());
    }

    /** A panel record keeps no recipe: the block entity is still the answer there. */
    @Test
    void aPanelRecordCarriesNoRecipe() {
        assertFalse(new PaintRecord("car", "minecraft:overworld", 0, 0, 0,
                "car", true, "", PaintRecipe.blank(), 1, 1).hasRecipe());
    }

    /** A span past the cap is still capped — the record must not describe a picture nothing drew. */
    @Test
    void anImpossibleSpanIsClampedRatherThanRecorded() {
        PaintRecord r = new PaintRecord("x", "minecraft:overworld", 1, 2, 3,
                "x", true, "", PaintRecipe.blank(), 1, 1, "east", 9999, 9999);
        assertEquals(PanelGeometry.MAX_SPAN, r.wide());
        assertEquals(PanelGeometry.MAX_SPAN, r.tall());
    }

    /**
     * One bad record must never cost the file — but note WHICH way it degrades, because the two
     * are different promises and only one of them was true.
     *
     * <p>A field that cannot be read falls back to its default rather than dropping the record: a
     * garbled span reads as 0, which is this record's word for "no map art", so an undo does
     * nothing rather than hunting for frames. Only a record with no id at all is refused. The
     * first cut of this test asserted the record was dropped and was simply wrong about the code.
     */
    @Test
    void anUnreadableFieldDegradesRatherThanCostingTheRecord() {
        JsonObject broken = JsonParser.parseString(
                "{\"id\":\"x\",\"wide\":\"not a number\",\"facing\":\"north\"}").getAsJsonObject();
        PaintRecord back = PaintRecord.fromJson(broken);
        assertEquals("x", back.id());
        assertEquals(0, back.wide());
        assertFalse(back.hasVanillaCopy());
        assertEquals(null, PaintRecord.fromJson(
                JsonParser.parseString("{\"facing\":\"north\"}").getAsJsonObject()));
    }
}
