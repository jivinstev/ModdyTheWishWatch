package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WishRun;
import org.junit.jupiter.api.Test;

/**
 * a photograph with no subject frames the picture THIS wish hung, not the last finished
 * wish's thing.: and while a run exists, NEVER the last finished wish's thing — an oasis
 * build was photographed as the previous wish's dragon shark, a teleport as the Pegasus.
 */
class PhotoSubjectTest {

    private static WishRun.Made m(String kind, String id) {
        return new WishRun.Made(kind, id);
    }

    @Test
    void aPictureThisWishHungWinsOverTheLastFinishedWish() {
        assertEquals("blockybob_giant", PhotoSubject.choose(
                List.of(m(MadeKind.PAINTING, "blockybob_giant")), "yesterdays_dragon"));
    }

    @Test
    void theNewestPictureWins() {
        assertEquals("second", PhotoSubject.choose(List.of(
                m(MadeKind.PAINTING, "second"), m(MadeKind.PAINTING, "first")), ""));
    }

    @Test
    void theBlockyBobGrantHadNothingFinishedAndStillFramesTheBoard() {
        assertEquals("blockybob_giant", PhotoSubject.choose(
                List.of(m(MadeKind.PAINTING, "blockybob_giant")), ""));
    }

    /**, a test server: a wand and a sword were photographed by a camera, not drawn from the icon. */
    @Test
    void anItemThisWishMadeIsTheSubjectSoItsIconIsDrawn() {
        assertEquals("red_fire_sword", PhotoSubject.choose(List.of(
                m(MadeKind.DATAPACK, "red_fire_sword_fire"),
                m(MadeKind.ITEM, "red_fire_sword")), "yesterdays_dragon"));
        assertEquals("sunset", PhotoSubject.choose(List.of(
                m(MadeKind.ITEM, "brush"), m(MadeKind.PAINTING, "sunset")), ""), "a picture still comes first");
    }

    /**, live 2026-10-03: the oasis build. The previous wish's dragon shark is NOT the default. */
    @Test
    void aBuildThisWishMadeIsFramedNotThePreviousWishsCreature() {
        assertEquals("oasis", PhotoSubject.choose(List.of(m(MadeKind.BUILD, "oasis")), "dragon_shark"));
    }

    @Test
    void aCreatureThisWishMadeIsFramed() {
        assertEquals("rex", PhotoSubject.choose(List.of(
                m(MadeKind.DATAPACK, "rex_roar"), m(MadeKind.CREATURE, "rex")), "dragon_shark"));
    }

    @Test
    void framableThingsAreFramedNewestFirst() {
        // "train" and "contraption" were framable kinds until Create went. A run that
        // still carries one aims at nothing of its own rather than throwing.
        assertEquals("", PhotoSubject.choose(List.of(m("train", "red_express")), "old"));
        assertEquals("", PhotoSubject.choose(List.of(m("contraption", "rock_crusher")), "old"));
        assertEquals("stable", PhotoSubject.choose(List.of(
                m(MadeKind.BUILD, "stable"), m(MadeKind.CREATURE, "pony")), "old"), "newest first");
        assertEquals("@llama", PhotoSubject.choose(List.of(m(MadeKind.MOB, "@llama")), "old"),
                "a changed mob's handle — PhotographTool.creatureSubject turns it into its species");
    }

    /** the Pegasus teleport. take_me_to made nothing, so no subject — the camera aims at the wish. */
    @Test
    void aTeleportWithNothingMadeHasNoSubjectAndNeverTheLastWish() {
        assertEquals("", PhotoSubject.choose(List.of(), "pegasus"));
        assertEquals("", PhotoSubject.choose(List.of(m(MadeKind.DATAPACK, "rule"), m(MadeKind.EVENT, "wave")),
                "pegasus"), "a datapack or an event is nothing a camera finds by id");
        assertEquals("", PhotoSubject.choose(List.of(m(MadeKind.PAINTING, " ")), "old"));
    }

    @Test
    void withNoRunTheLastFinishedArtifactStillAnswers() {
        assertEquals("old", PhotoSubject.choose(null, "old"));
        assertEquals("", PhotoSubject.choose(null, null));
    }

    // ---- the detector (E) ----

    @Test
    void aSubjectThisWishMadeIsNotAFault() {
        var made = List.of(m(MadeKind.BUILD, "oasis"));
        assertFalse(PhotoSubject.notThisWish(made, List.of(), false, "oasis"));
        assertFalse(PhotoSubject.notThisWish(made, List.of(), false, "OASIS"), "case does not matter");
        assertFalse(PhotoSubject.notThisWish(List.of(m(MadeKind.CREATURE, "rex")), List.of(), false,
                "moddingfromamod:rex"), "a namespaced species is the same species");
    }

    @Test
    void anEarlierWishsCreatureIsAFault() {
        assertTrue(PhotoSubject.notThisWish(List.of(m(MadeKind.BUILD, "oasis")), List.of(), false,
                "dragon_shark"));
    }

    @Test
    void aHandleCountsWhenItsSpeciesIsThisWishs() {
        assertFalse(PhotoSubject.notThisWish(List.of(m(MadeKind.CREATURE, "spikor")), List.of(), false,
                "@llama", "spikor"), "the handle as asked and the species it resolves to");
    }

    @Test
    void notAFaultWhenThereIsNothingOfItsOwnOrItIsAboutAnEarlierThing() {
        assertFalse(PhotoSubject.notThisWish(null, List.of(), false, "dragon_shark"), "no run");
        assertFalse(PhotoSubject.notThisWish(List.of(m(MadeKind.DATAPACK, "rule")), List.of(), false,
                "dragon"), "it made nothing a camera could frame");
        assertFalse(PhotoSubject.notThisWish(List.of(m(MadeKind.BUILD, "oasis")), List.of(), true,
                "dragon_shark"), "plan_wish said it is about an earlier thing");
        assertFalse(PhotoSubject.notThisWish(List.of(), java.util.Arrays.asList("castle", null), false,
                "castle"), "where a teleport took him counts as his");
        assertFalse(PhotoSubject.notThisWish(List.of(m(MadeKind.BUILD, "oasis")), List.of(), false, ""),
                "no subject is not a wrong subject");
    }
}
