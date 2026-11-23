package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moddingfromamod.entity.WishMobTags;

/**
 * The addressing rule behind, pinned here rather than in the tool, because the tool needs a
 * server, a world and a client to say anything at all -- which is why the regression it encodes
 * shipped as a green PASS.
 */
class CaptureSubjectTest {

    private static final String SPIKOR_TAG = WishMobTags.forSpecies("spikor");

    // ---------------------------------------------------------------- the WishMobEntity path

    @Test
    @DisplayName("a WishMobEntity matches on its own species id, exactly as it always did")
    void wishMobMatchesOnSpeciesId() {
        assertTrue(CaptureSubject.isSubject("spikor", "spikor", Set.of()));
        assertTrue(CaptureSubject.isSubject("SPIKOR", "spikor", Set.of()),
                "the id comparison has always been case-insensitive; a kid types what a kid types");
        assertFalse(CaptureSubject.isSubject("spikor", "goomba", Set.of()));
    }

    @Test
    @DisplayName("a species id that sanitising would mangle still matches EXACTLY on the id")
    void theExactPathIsNotRoutedThroughTheLossyOne() {
        // "SPIKOR, King of the Kroaks" sanitises to spikor_king_of_the_kroaks -- lossy, and a
        // second creature could collide with it. The entity that knows its own id is compared
        // against that id, so the lossy path never runs for it.
        String fancy = "SPIKOR, King of the Kroaks";
        assertTrue(CaptureSubject.isSubject(fancy, fancy, Set.of()));
    }

    // ---------------------------------------------------------------- the vanilla-base path

    @Test
    @DisplayName("REGRESSION #503: a rig on a vanilla base matches on its species TAG")
    void aRigOnAVanillaBaseIsFound() {
        // This is the shipping creature: no WishMobEntity anywhere, so entitySpeciesId
        // is null and the tags are all the world has to say. Before the fix this returned false,
        // the orbit fell back to one plain shot and the clip was never written.
        assertTrue(CaptureSubject.isSubject("spikor", null,
                Set.of(WishMobTags.ALL, SPIKOR_TAG, "rig", "rig_owner_abc")));
    }

    @Test
    @DisplayName("a rig for a DIFFERENT species is not the subject")
    void aRigForAnotherSpeciesIsNotTheSubject() {
        assertFalse(CaptureSubject.isSubject("spikor", null,
                Set.of(WishMobTags.ALL, WishMobTags.forSpecies("goomba"))));
    }

    @Test
    @DisplayName("an ordinary vanilla mob standing next to the rig is never the subject")
    void aPlainVanillaMobIsNotTheSubject() {
        assertFalse(CaptureSubject.isSubject("spikor", null, Set.of()));
        assertFalse(CaptureSubject.isSubject("spikor", null, List.of("some_other_mods_tag")));
    }

    // ---------------------------------------------------------------- the guard that matters

    @Test
    @DisplayName("an id that sanitises to nothing matches NOTHING, not everything")
    void anIdThatCollapsesMatchesNothing() {
        // WishMobTags.forSpecies falls back to the bare "wishmob" for an id with no usable
        // characters -- and every wish creature carries that tag. Matching on it would hand back
        // whichever creature is nearest, correctly framed and confidently labelled, which is worse
        // than returning nothing: a wrong photograph that looks exactly like a right one.
        assertTrue(WishMobTags.ALL.equals(WishMobTags.forSpecies("!!!")),
                "if this ever stops being true the guard below is testing nothing");
        assertFalse(CaptureSubject.isSubject("!!!", null, Set.of(WishMobTags.ALL, SPIKOR_TAG)));
        assertFalse(CaptureSubject.isSubject("...", null, Set.of(WishMobTags.ALL)));
    }

    @Test
    @DisplayName("a blank or null request matches nothing")
    void blankMatchesNothing() {
        assertFalse(CaptureSubject.isSubject(null, "spikor", Set.of(SPIKOR_TAG)));
        assertFalse(CaptureSubject.isSubject("", "spikor", Set.of(SPIKOR_TAG)));
        assertFalse(CaptureSubject.isSubject("   ", null, Set.of(SPIKOR_TAG)));
    }

    @Test
    @DisplayName("null tags are tolerated, not thrown at")
    void nullTagsAreFine() {
        assertFalse(CaptureSubject.isSubject("spikor", null, null));
        assertTrue(CaptureSubject.isSubject("spikor", "spikor", null));
    }

    // ---------------------------------------------------------------- the framing input (#503b)

    @Test
    @DisplayName("REGRESSION: a rig's carrier scale is a fact about the CARRIER, not the creature")
    void aRigCarrierScaleIsNotTheCreatureScale() {
        // The measured case, from the first run where the subject was found at all: the rig had
        // chosen a BEE, so wantHeight/beeHeight came out at 12.25 and the camera framed a creature
        // 49/16 * 12.25 = 37.5 blocks tall. It stood 29 blocks back and photographed a speck.
        assertEquals(2.4, CaptureSubject.scaleOf(false, 12.249999, 2.4), 1e-9);
    }

    @Test
    @DisplayName("a WishMobEntity keeps its OWN scale, so a one-off resize still frames right")
    void aWishMobKeepsItsOwnScale() {
        // "make THIS one bigger" changes the entity. Reading the species back here would frame the
        // creature at the size it used to be, which is a wrong photograph of a right creature --
        // the exact class of bug this file exists for, just pointing the other way.
        assertEquals(5.0, CaptureSubject.scaleOf(true, 5.0, 2.4), 1e-9);
    }

    @Test
    @DisplayName("something that is not ours falls back to its own scale")
    void aModdedBossUsesItsOwnScale() {
        // No species record: a modded boss the child asked to be photographed. Its own scale is
        // the only fact anyone has, and it is the correct one.
        assertEquals(1.75, CaptureSubject.scaleOf(false, 1.75, null), 1e-9);
    }

    @Test
    @DisplayName("a nonsense species scale is not preferred over a real one")
    void aBrokenSpeciesScaleIsIgnored() {
        assertEquals(3.0, CaptureSubject.scaleOf(false, 3.0, 0.0), 1e-9);
        assertEquals(3.0, CaptureSubject.scaleOf(false, 3.0, -1.0), 1e-9);
    }

    // ---------------------------------------------------------------- the crowd count

    @Test
    @DisplayName("the crowd count sees BOTH kinds of wish creature")
    void anyWishCreatureCountsBothArms() {
        assertTrue(CaptureSubject.isAnyWishCreature("spikor", Set.of()),
                "a WishMobEntity, the pre-#500 shape");
        assertTrue(CaptureSubject.isAnyWishCreature(null, Set.of(WishMobTags.ALL, SPIKOR_TAG)),
                "a rig on a vanilla base, the shipping shape -- this is the half that was missing, "
                        + "so the 'more than one creature is in this shot' warning had gone quiet");
        assertFalse(CaptureSubject.isAnyWishCreature(null, Set.of("passing_cow")));
        assertFalse(CaptureSubject.isAnyWishCreature(null, null));
    }
}
