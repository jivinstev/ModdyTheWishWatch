package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import com.moddingfromamod.builder.Builder;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The wish manifests at a spot LOCKED when the wish was made — it does not follow the player around
 * (the mid-air McTrain bug). These prove the lock semantics + the anchor-priority rule without a
 * game player (a mock ServerPlayer breaks here because of the join-time sync payloads).
 */
class WishAnchorTest {

    private static final BlockPos AIMED = new BlockPos(100, 64, 100);
    private static final BlockPos DECIDED = new BlockPos(100, 120, 100); // e.g. lifted into the sky

    @Test
    void wishTimeDefaultLocksAndIsNotClobberedButConjureAtWins() {
        UUID kid = UUID.randomUUID();
        WishProgress.forget(kid);

        // Wish time: the watch's aim becomes the default manifest spot.
        WishProgress.defaultAnchor(kid, AIMED);
        assertEquals(AIMED, WishProgress.manifestAnchor(kid), "the aim is captured as the spot");

        // A second default (e.g. a wish queued behind this one) must NOT move the active spot.
        WishProgress.defaultAnchor(kid, new BlockPos(-999, 64, -999));
        assertEquals(AIMED, WishProgress.manifestAnchor(kid), "a later default can't clobber it");

        // But the agent deciding a spot early (conjure_at) DOES override the default.
        WishProgress.focusOn(kid, DECIDED);
        assertEquals(DECIDED, WishProgress.manifestAnchor(kid), "conjure_at wins");

        // When the wish finishes, the spot is cleared so the next wish captures a fresh one.
        WishProgress.forget(kid);
        assertNull(WishProgress.manifestAnchor(kid), "cleared when the wish is done");
    }

    @Test
    void aLockedSpotIsUsedInsteadOfFollowingThePlayer() {
        // No player, a locked spot, no explicit offset -> the build anchors at the LOCKED spot,
        // never recomputed from a (wandered-off) player position.
        assertEquals(DECIDED, Builder.chooseAnchor(null, DECIDED, 12, 0),
                "the locked manifest spot is where it lands");
    }

    @Test
    void withNoLockAndNoPlayerThereIsNoAnchorSoTheCallerFallsBack() {
        assertNull(Builder.chooseAnchor(null, null, 12, 0),
                "no lock + no player -> null, so the tool uses its world-spawn fallback");
    }

    @Test
    void anOffsetNeverThrowsTheAimAwayButOffsetYLiftsFromIt() {
        // offsetForward used to win over the lock and build on the child. Now the lock wins,
        // forward is ignored, and offsetY raises the build FROM the locked spot (the float).
        assertEquals(DECIDED, Builder.chooseAnchor(null, DECIDED, 0, 0),
                "offsetForward 0 still lands at the locked spot");
        assertEquals(DECIDED.above(10), Builder.chooseAnchor(null, DECIDED, 0, 10),
                "offsetY 10 floats it ten above the locked spot");
        assertEquals(DECIDED.below(4), Builder.chooseAnchor(null, DECIDED, 12, -4),
                "a negative offsetY sinks it from the locked spot");
        assertTrue(Builder.ignoresForward(DECIDED, true), "the result must say forward was ignored");
        assertFalse(Builder.ignoresForward(null, true), "with no aim, forward is honoured");
        assertFalse(Builder.ignoresForward(DECIDED, false));
    }
}
