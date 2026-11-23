package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.provenance.ProvenanceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stage 2 — the six single-wish assumptions, asserted from the outside.
 *
 * <p>Each of these fails on the code as it stood before, and fails <b>silently</b> in play: nothing
 * crashes, nothing looks wrong on screen, and the damage is a manifest carrying the other wish's
 * words or an agent addressing the other wish's creature.
 *
 * <p>Driven against {@code Marshal.global()} and the real static entry points on purpose. A test
 * against injected instances would prove the new classes work and say nothing about whether the old
 * call sites reach them, which is the entire content of this stage.
 */
class PerWishContextTest {

    private static final UUID CHILD = UUID.nameUUIDFromBytes("steve".getBytes());

    private UUID dragonId;
    private UUID castleId;
    private WishRun dragon;
    private WishRun castle;

    @BeforeEach
    void setUp() {
        Marshal.global().clear();
        AgentContext.unbind();
        dragonId = UUID.randomUUID();
        castleId = UUID.randomUUID();
        Marshal.global().submit(dragonId, CHILD, "make me an ice dragon");
        Marshal.global().submit(castleId, CHILD, "build a floating castle");
        dragon = Marshal.global().assign("worker-one", dragonId);
        castle = Marshal.global().assign("worker-two", castleId);
    }

    @AfterEach
    void tearDown() {
        AgentContext.unbind();
        Marshal.global().clear();
    }

    // ---- 1. ProvenanceContext ---------------------------------------------------------------

    @Test
    void eachWorkerSeesItsOwnWishText() {
        assertEquals("make me an ice dragon",
                AgentContext.with("worker-one", ProvenanceContext::currentIntent));
        assertEquals("build a floating castle",
                AgentContext.with("worker-two", ProvenanceContext::currentIntent));
    }

    @Test
    void aDesignNoteCannotLandOnTheOtherWishesArtifact() {
        AgentContext.with("worker-one", () -> {
            ProvenanceContext.note("scaly and blue because he asked for ICE");
            return null;
        });
        AgentContext.with("worker-two", () -> {
            ProvenanceContext.note("quartz, because a floating castle should read as clean");
            return null;
        });

        assertEquals("scaly and blue because he asked for ICE",
                AgentContext.with("worker-one", ProvenanceContext::currentNote));
        assertEquals("quartz, because a floating castle should read as clean",
                AgentContext.with("worker-two", ProvenanceContext::currentNote),
                "a shared field means the corpus that drives the roadmap records the wrong reasoning "
                        + "— it publishes fine, so nothing ever says so");
    }

    @Test
    void aSecondWishStartingDoesNotWipeTheFirstsNote() {
        AgentContext.with("worker-one", () -> {
            ProvenanceContext.note("scaly and blue");
            return null;
        });
        // The other worker waking on its own wish used to clear the single shared note.
        AgentContext.with("worker-two", () -> {
            ProvenanceContext.begin("build a floating castle");
            return null;
        });
        assertEquals("scaly and blue",
                AgentContext.with("worker-one", ProvenanceContext::currentNote));
    }

    // ---- 2. the anchor (WishProgress FOCUS) --------------------------------------------------

    @Test
    void oneWishesConjureAtDoesNotMoveTheOthersSparkles() {
        dragon.lockAnchor(new Spot("minecraft:overworld", 100, 70, 100));
        castle.lockAnchor(new Spot("minecraft:overworld", -900, 120, 400));

        assertEquals(new Spot("minecraft:overworld", 100, 70, 100), dragon.anchor());
        assertEquals(new Spot("minecraft:overworld", -900, 120, 400), castle.anchor());
        assertNotEquals(dragon.anchor(), castle.anchor(),
                "a per-player spot means the second wish's conjure_at moves the first one's beam, "
                        + "and then something appears where he was never looking");
    }

    @Test
    void bothWishesAreLandmarkedAtOnce() {
        dragon.lockAnchor(new Spot("minecraft:overworld", 100, 70, 100));
        castle.lockAnchor(new Spot("minecraft:overworld", -900, 120, 400));
        // Beams are uncapped by design — the sky is not a physically singular channel, and ten
        // promises in the air is a skyline rather than clutter.
        assertEquals(2, Marshal.global().liveFor(CHILD).stream()
                .filter(r -> r.anchor() != null).count());
    }

    // ---- 3. EntityHandles @last ---------------------------------------------------------------

    @Test
    void lastMeansTheLastThingThisWishMade() {
        dragon.rememberedHandle("@ice_dragon");
        castle.rememberedHandle("@castle_guard");

        assertEquals("@ice_dragon", dragon.handles().get(0));
        assertEquals("@castle_guard", castle.handles().get(0),
                "shared, '@last' hands one agent the OTHER wish's creature — which is the nearest-mob "
                        + "heuristic that handles were introduced to kill, in a new costume");
    }

    @Test
    void aWishThatHasMadeNothingHasNoLastOfItsOwn() {
        assertTrue(dragon.handles().isEmpty());
    }

    // ---- 4. ToolNarrator ----------------------------------------------------------------------

    @Test
    void oneAgentSpeakingDoesNotSilenceTheOthersNarrator() {
        dragon.agentSpoke(10_000);

        assertEquals(10_000, dragon.agentSpokeAtMs());
        assertEquals(0, castle.agentSpokeAtMs(),
                "the mod's narrator exists to speak during the window where a model structurally "
                        + "cannot; a shared timestamp gags it for the wish nobody narrated");
    }

    // ---- 5. what each wish used ---------------------------------------------------------------

    @Test
    void whatOneWishUsedNeverCountsAgainstTheOthersUndo() {
        dragon.usedTool("run_command");
        castle.usedTool("build_structure");

        assertTrue(UndoContract.undoable(castle.toolsUsed()),
                "the castle only built, so [undo] is honestly available on it");
        assertTrue(!UndoContract.undoable(dragon.toolsUsed()));
    }

    @Test
    void whatOneWishMadeIsNeverUndoneByStoppingTheOther() {
        dragon.made(MadeKind.CREATURE, "ice_dragon");
        castle.made(MadeKind.BUILD, "floating_castle");

        assertEquals(1, dragon.made().size());
        assertEquals("floating_castle", castle.made().get(0).id());
    }

    // ---- the binding itself --------------------------------------------------------------------

    @Test
    void theBindingIsAlwaysReleasedEvenWhenTheToolThrows() {
        try {
            AgentContext.with("worker-one", () -> {
                throw new IllegalStateException("tool blew up");
            });
        } catch (IllegalStateException expected) {
            // the point is what happens next
        }
        assertEquals("", AgentContext.workerId(),
                "a leaked binding would attribute the NEXT wish's work to this one");
    }

    @Test
    void withNothingBoundAndOneWishInFlightTheAnswerIsUnambiguous() {
        Marshal.global().finish(castleId);
        assertSame(dragon, AgentContext.currentRun(Marshal.global()),
                "one wish in flight: the fallback is correct, which is why every single-slot call "
                        + "site keeps working unchanged");
    }

    @Test
    void withNothingBoundAndTwoInFlightItRefusesToGuess() {
        assertNull(AgentContext.currentRun(Marshal.global()),
                "guessing here is exactly the silent mis-attribution this stage removes");
    }
}
