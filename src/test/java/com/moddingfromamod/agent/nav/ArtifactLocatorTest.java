package com.moddingfromamod.agent.nav;

import java.util.List;

import com.moddingfromamod.agent.nav.ArtifactLocator.Artifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The matching half of {@code take_me_to}. */
class ArtifactLocatorTest {

    private static final Artifact MCTRAIN =
            new Artifact("train", "mctrain", "The McTrain", 622, 0, 654, "minecraft:overworld");
    private static final Artifact LONGTRAIN =
            new Artifact("train", "longtrain", "Really Long Train", 2786, 0, 3577, "minecraft:overworld");
    private static final Artifact CASTLE =
            new Artifact("building", "glimmerhold", "The Glimmerhold", 100, 64, 100, "minecraft:overworld");
    private static final List<Artifact> ALL = List.of(MCTRAIN, LONGTRAIN, CASTLE);

    // ───────────────────── the wish that opened the issue ─────────────────────

    @Test
    @DisplayName("\"the nearest McTrain\" resolves to the McTrain he already built")
    void theRealWishResolves() {
        assertEquals(MCTRAIN, ArtifactLocator.resolve("the nearest McTrain", ALL).best());
    }

    @Test
    @DisplayName("a child's spellings all reach the same train")
    void childSpellingsResolve() {
        for (String said : new String[] {"mctrain", "McTrain", "the mctrain", "MC Train", "mc_train"}) {
            assertEquals(MCTRAIN, ArtifactLocator.resolve(said, ALL).best(), said);
        }
    }

    @Test
    @DisplayName("it is not trains-only — \"my castle\" finds the building")
    void itWorksAcrossKinds() {
        assertEquals(CASTLE, ArtifactLocator.resolve("glimmerhold", ALL).best());
    }

    // ───────────────────── it refuses rather than guesses ─────────────────────

    @Test
    @DisplayName("an EXACT id wins over a loose match — that is precedence, not ambiguity")
    void anExactIdWins() {
        Artifact other = new Artifact("train", "mctrain2", "The McTrain Two", 9, 0, 9, "minecraft:overworld");
        assertEquals(MCTRAIN, ArtifactLocator.resolve("mctrain", List.of(MCTRAIN, other)).best(),
                "'mctrain' IS the id of one of them; asking which he meant would be pedantic");
    }

    @Test
    @DisplayName("a name that only loosely matches two things is REPORTED, never guessed")
    void ambiguityIsReported() {
        var m = ArtifactLocator.resolve("train", ALL);
        assertTrue(m.isAmbiguous(), "arriving at the wrong one looks exactly like arriving at the right one");
        assertEquals(2, m.ambiguous().size(), "both trains, not the castle: " + m.ambiguous());
        assertTrue(m.miss().contains("more than one"), m.miss());
    }

    @Test
    @DisplayName("a name nothing matches says so instead of picking the first thing")
    void aMissSaysSo() {
        var m = ArtifactLocator.resolve("spaceship", ALL);
        assertFalse(m.found());
        assertFalse(m.isAmbiguous());
        assertTrue(m.miss().contains("called that"), m.miss());
    }

    @Test
    @DisplayName("with nothing built at all it says that, rather than blaming the name")
    void anEmptyWorldSaysSo() {
        assertTrue(ArtifactLocator.resolve("mctrain", List.of()).miss().contains("nothing has been built"));
    }

    @Test
    @DisplayName("\"take me to it\" with exactly one thing built needs no name")
    void oneThingNeedsNoName() {
        assertEquals(MCTRAIN, ArtifactLocator.resolve("", List.of(MCTRAIN)).best());
        assertTrue(ArtifactLocator.resolve("", ALL).isAmbiguous());
    }

    // ───────────────────── the guard asks for ─────────────────────

    @Test
    @DisplayName("the navigation phrasings a child really uses are recognised")
    void navigationIsRecognised() {
        for (String said : new String[] {
                "teleport me to the closest McTrain!",
                "take me to my castle",
                "where's my dragon",
                "can you find it and teleport me there",
                "I want to go back to it"}) {
            assertTrue(ArtifactLocator.asksToBeTakenThere(said), said);
        }
    }

    @Test
    @DisplayName("\"build one here too\" is NOT a request to travel, though it names the thing")
    void makingIsNotTravelling() {
        for (String said : new String[] {
                "build one here too",
                "make another McTrain where I am",
                "can you build me a new one here",
                "take me a copy of the McTrain"}) {
            assertFalse(ArtifactLocator.asksToBeTakenThere(said),
                    "answering this with a teleport is the mirror of the bug: " + said);
        }
    }

    @Test
    @DisplayName("an ordinary build wish is not mistaken for navigation")
    void anOrdinaryBuildWishIsNotNavigation() {
        assertFalse(ArtifactLocator.asksToBeTakenThere("make me a mcburgers train called the McTrain"));
        assertFalse(ArtifactLocator.asksToBeTakenThere(""));
        assertFalse(ArtifactLocator.asksToBeTakenThere(null));
    }
}
