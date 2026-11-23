package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The stand-in is the thing that makes {@link VanillaRigBridge} total, so what is tested here is
 * TOTALITY rather than beauty: every body shape, every degenerate input, and the one property the
 * rig actually depends on — a non-empty parts list whose extents are a real creature's.
 */
class StandInSculptTest {

    private static Species species(Species.Body body, int tint, String modelKey) {
        return new Species("t9_standin", "Stand-in", body, "minecraft:textures/entity/pig/pig.png",
                tint, 1.0, 20, 0.25, 3, 0, 0, Set.of(), modelKey,
                Species.Base.WILD, Set.of(), Set.of());
    }

    // ── totality ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Species.Body.class)
    @DisplayName("every body shape yields a creature — the enum is covered by construction, so "
            + "adding a Body without a shape fails here rather than in a child's world")
    void everyBodyYieldsACreature(Species.Body body) {
        List<CreatureSculpt.Part> parts = StandInSculpt.forBody(body, 0x8844CC);
        assertFalse(parts.isEmpty(), body + " produced no parts");
        for (CreatureSculpt.Part p : parts) {
            assertTrue(p.width() > 0 && p.height() > 0 && p.depth() > 0,
                    body + "/" + p.name() + " is a degenerate box: a zero-size display is "
                            + "invisible, which is the failure this whole class exists to remove");
            assertEquals(0x8844CC, p.colour(), body + "/" + p.name() + " lost the species tint");
        }
    }

    @ParameterizedTest
    @EnumSource(Species.Body.class)
    @DisplayName("and it is a creature-sized creature — between a slime and two blocks tall, with "
            + "its feet on the ground")
    void everyBodyIsCreatureSized(Species.Body body) {
        List<CreatureSculpt.Part> parts = StandInSculpt.forBody(body, 0xFFFFFF);
        float h = StandInSculpt.heightBlocks(parts);
        assertTrue(h >= 0.4f && h <= 2.1f, body + " is " + h + " blocks tall");

        float lowest = Float.MAX_VALUE;
        for (CreatureSculpt.Part p : parts) {
            lowest = Math.min(lowest, p.from()[1]);
        }
        assertEquals(0f, lowest, 0.001f, body + " does not stand on y=0, so it floats or sinks");

        var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
        assertTrue(CreatureAnatomy.widthBlocks(extents) > 0.2f, body + " has no width");
        assertTrue(CreatureAnatomy.heightBlocks(extents) > 0.2f, body + " has no height");
    }

    // ── the substitution rule ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a real sculpt is handed back UNTOUCHED — the stand-in is a fallback, never a "
            + "filter, and this is the assertion that keeps it one")
    void arealSculptIsNeverSubstituted() {
        List<CreatureSculpt.Part> real = StandInSculpt.forBody(Species.Body.SLIME, 0x00FF00);
        assertSame(real, StandInSculpt.manifest(species(Species.Body.HUMANOID, 0xFF0000, "k"), real));
        assertFalse(StandInSculpt.isStandIn(real));
    }

    @Test
    @DisplayName("#497 fallback 1 — a species with NO custom model still manifests")
    void aSpeciesWithNoModelStillManifests() {
        Species s = species(Species.Body.QUADRUPED, 0xFF00FF, "");
        assertFalse(s.hasCustomModel(), "the fixture must be the case under test");
        List<CreatureSculpt.Part> parts = StandInSculpt.manifest(s, List.of());
        assertFalse(parts.isEmpty());
        assertEquals(0xFF00FF, parts.get(0).colour(), "a stand-in wears the species' own colour");
    }

    @Test
    @DisplayName("#497 fallback 2 — a model with NO sculpt spec (an import, or a creature older "
            + "than the spec) still manifests")
    void aModelWithNoSpecStillManifests() {
        Species s = species(Species.Body.SPIDER, 0x223344, "imported/thing");
        assertTrue(s.hasCustomModel(), "the fixture must be the case under test");
        assertTrue(StandInSculpt.isStandIn(List.of()));
        assertFalse(StandInSculpt.manifest(s, List.of()).isEmpty());
    }

    @Test
    @DisplayName("a wish mob whose species has been forgotten still manifests — a null species is "
            + "what an old save with a deleted species record hands us")
    void aForgottenSpeciesStillManifests() {
        assertFalse(StandInSculpt.manifest(null, null).isEmpty());
        assertFalse(StandInSculpt.manifest(null, List.of()).isEmpty());
    }

    @Test
    @DisplayName("tint 0 is not black — an unset tint reads as 0 and a creature the colour of the "
            + "void is indistinguishable from one that failed to render")
    void anUnsetTintIsNotBlack() {
        for (CreatureSculpt.Part p : StandInSculpt.forBody(Species.Body.BIRD, 0)) {
            assertEquals(0xFFFFFF, p.colour());
        }
    }

    @Test
    @DisplayName("a null body is a humanoid rather than a crash")
    void aNullBodyIsAHumanoid() {
        assertEquals(StandInSculpt.forBody(Species.Body.HUMANOID, 0x101010).size(),
                StandInSculpt.forBody(null, 0x101010).size());
    }
}
