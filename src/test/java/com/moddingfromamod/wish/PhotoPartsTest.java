package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WishRun;
import org.junit.jupiter.api.Test;

/** a wish that made two things is photographed as two things, the place first. */
class PhotoPartsTest {

    private static WishRun.Made m(String kind, String id) {
        return new WishRun.Made(kind, id);
    }

    private static List<String> parts(List<WishRun.Made> made) {
        return PhotoParts.parts(made, Set.of(), Set.of());
    }

    @Test
    void theSandcastleAndItsSeahorsesAreTwoPartsThePlaceFirst() {
        // newest first, as WishRun.made() hands them over: the seahorse was made after the castle
        var made = List.of(m(MadeKind.CREATURE, "seahorse"), m(MadeKind.BUILD, "underwater_sand_castle"));
        assertEquals(List.of("underwater_sand_castle", "seahorse"), parts(made));
    }

    @Test
    void oneThingIsTheSinglePhotographItAlwaysWas() {
        assertEquals(List.of(), parts(List.of(m(MadeKind.CREATURE, "spikor"))));
        assertEquals(List.of(), parts(List.of(m(MadeKind.CREATURE, "spikor"),
                m(MadeKind.CREATURE, "spikor"))), "the same creature twice is one thing");
        assertEquals(List.of(), PhotoParts.parts(null, null, null));
    }

    @Test
    void anItemOrARuleIsNotAPartToPhotograph() {
        var made = List.of(m(MadeKind.ITEM, "wand"), m(MadeKind.DATAPACK, "rule"), m(MadeKind.CREATURE, "cat"));
        assertEquals(List.of(), parts(made));
    }

    @Test
    void atMostThreeParts() {
        var made = List.of(m(MadeKind.CREATURE, "a"), m(MadeKind.CREATURE, "b"), m(MadeKind.CREATURE, "c"),
                m(MadeKind.BUILD, "house"));
        List<String> parts = parts(made);
        assertEquals(PhotoParts.MAX, parts.size());
        assertEquals("house", parts.get(0), "the place is never the one left out");
    }

    // ── the shapes an earlier issue's review found in real play (WishParts, already knew them) ──────

    @Test
    void aSculptThenModifyMobIsOneCreatureNotTwo() {
        // "a pegasus I can ride": sculpt_creature pegasus, then modify_mob rideable records the
        // handle @pegasus -- the same creature. Read raw, it was photographed twice.
        var made = List.of(m(MadeKind.MOB, "@pegasus"), m(MadeKind.CREATURE, "pegasus"));
        assertEquals(List.of(), parts(made));
        var withCastle = List.of(m(MadeKind.MOB, "@pegasus"), m(MadeKind.CREATURE, "pegasus"),
                m(MadeKind.BUILD, "castle"));
        assertEquals(List.of("castle", "pegasus"), parts(withCastle));
    }

    @Test
    void aBuildingTheWishTookAwayAgainIsNotPhotographed() {
        // built, removed, rebuilt lower (a real wish, 2026-10-21): the first draft is gone
        var made = List.of(m(MadeKind.CREATURE, "fox"), m(MadeKind.BUILD, "den_v2"),
                m(MadeKind.BUILD, "den_v1"));
        assertEquals(List.of("den_v2", "fox"),
                PhotoParts.parts(made, Set.of(m(MadeKind.BUILD, "den_v1")), Set.of()));
    }

    @Test
    void anEditedPlaceIsPhotographedWithWhatWasPutInIt() {
        // "put a dragon on my castle": the castle was an earlier wish's, this one changed it. The
        // picture is the castle with its dragon — leaving the castle out photographed the seahorses
        // alone (an earlier issue's R4 film), and the next wish about the castle still gets the notes.
        var made = List.of(m(MadeKind.CREATURE, "dragon"), m(MadeKind.BUILD, "castle"));
        Set<WishRun.Made> edited = Set.of(m(MadeKind.BUILD, "castle"));
        assertEquals(List.of("castle", "dragon"), PhotoParts.parts(made, Set.of(), edited));
        assertEquals(List.of("castle", "dragon"), PhotoParts.noted(made, Set.of(), edited));
    }

    @Test
    void notesAreNotCappedLikePhotographs() {
        var made = List.of(m(MadeKind.CREATURE, "a"), m(MadeKind.CREATURE, "b"), m(MadeKind.CREATURE, "c"),
                m(MadeKind.BUILD, "house"));
        assertEquals(List.of("house", "c", "b", "a").size(), PhotoParts.noted(made, Set.of(), Set.of()).size());
    }

    private static String png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", bos);
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    @Test
    void theShotsAreStackedToOneWidth() throws Exception {
        String stacked = PhotoParts.stack(List.of(png(200, 100), png(100, 100), "not a picture"));
        assertNotNull(stacked);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(stacked)));
        assertEquals(200, out.getWidth());
        assertEquals(100 + 200, out.getHeight(), "the narrower shot is scaled up to the width");
        assertNull(PhotoParts.stack(List.of("x")), "no readable shot is no picture");
    }

    @Test
    void theAppearingLineNamesTheCreature() {
        assertEquals("Look, your Seahorse is starting to appear! The rainbow shimmer means I'm still "
                + "wishing it into shape.", WishFeedback.appearingLine("§bSeahorse"));
        assertTrue(WishFeedback.appearingLine("").startsWith("Look, it's starting to appear!"));
    }
}
