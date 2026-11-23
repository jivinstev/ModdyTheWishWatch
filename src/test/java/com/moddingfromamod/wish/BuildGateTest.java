package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate A for "the child was actually shown it before it was judged".
 *
 * <p>The interesting half of this guard is not that it blocks — it is that it <b>stops</b> blocking.
 * A tool that refuses forever is how the reload storm worked: an error saying "try again in a
 * moment" was obeyed 35 times and made the game unplayable. So these tests care as much about the
 * agent being let through as about it being turned away.
 */
class BuildGateTest {

    @AfterEach
    void reset() {
        BuildGate.clearAll();
    }

    @Test
    void anUnbuiltWishIsTurnedAwayOnce() {
        BuildGate gate = new BuildGate();
        assertTrue(gate.refuseFinish(), "closing a wish the child never saw must be refused");
    }

    @Test
    void andIsNeverTurnedAwayTwice() {
        // The load-bearing half. If finish_building itself fails — game thread busy, nobody in the world —
        // a permanent block would bounce the agent between the two calls forever, and the child
        // would watch a ticker climb. A wish that ends imperfectly beats a wish that cannot end.
        BuildGate gate = new BuildGate();
        assertTrue(gate.refuseFinish());
        assertFalse(gate.refuseFinish(), "the second attempt must always go through");
        assertFalse(gate.refuseFinish(), "and every attempt after that");
    }

    @Test
    void aBuiltWishIsNeverBlockedAtAll() {
        BuildGate gate = new BuildGate();
        gate.markBuilt();
        assertFalse(gate.refuseFinish(), "the normal path must not cost an extra round trip");
        assertTrue(gate.isBuilt());
    }

    @Test
    void buildingAfterTheNudgeClearsTheBlock() {
        // The behaviour we actually expect in play: finish is refused, the agent goes and finishes the build,
        // then finishes for real.
        BuildGate gate = new BuildGate();
        assertTrue(gate.refuseFinish());
        gate.markBuilt();
        assertFalse(gate.refuseFinish());
    }

    @Test
    void anAgentThatCOMPLIEDIsNeverTurnedAway() {
        // THE CASE ONLY REAL TRAFFIC FOUND, and it is the difference between "the child saw it" and
        // "the agent did what it was told". markBuilt() is reached from inside
        //     for (ServerPlayer p : server.getPlayerList().getPlayers())
        // so with nobody online the loop body never runs, the flag is never set, and finish_wish is
        // refused with "you haven't called finish_building for this wish" — which is false.
        //
        // Measured in the first traffic mined into corpus/real-tool-calls.jsonl: 7 of 7 Gate C½
        // wishes end finish_building(ok) -> finish_wish(REFUSED) -> finish_wish(ok), one wasted
        // model round trip each at the most expensive moment in the wish.
        //
        // The gate's job is to make the agent CALL finish_building. Whether anyone was online to be
        // told is not something the agent can do anything about, so refusing it there is the defect.
        BuildGate gate = new BuildGate();
        gate.markFinishBuildingCalled();
        assertFalse(gate.refuseFinish(),
                "the agent called finish_building; turning it away costs a round trip and tells it "
                        + "something untrue");
        assertTrue(gate.wasFinishBuildingCalled());
        assertFalse(gate.isBuilt(), "nobody was online, so nobody was shown it — still true");
    }

    @Test
    void complianceIsPerWishLikeEverythingElseHere() {
        // Without this the flag would outlive its wish and the SECOND wish of a session would skip
        // the check — the same defect the reset below exists to prevent for markBuilt.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        BuildGate.startWish(first);
        BuildGate.forWish(first).markFinishBuildingCalled();
        assertFalse(BuildGate.forWish(first).refuseFinish());

        BuildGate.startWish(second);
        assertTrue(BuildGate.forWish(second).refuseFinish(),
                "a second wish must be finished on its own merits");
    }

    @Test
    void aWishThatNEVERCalledFinishBuildingIsStillRefusedOnce() {
        // The guard still does its original job: this is the whole reason it exists, and widening
        // it to accept compliance must not turn it into a no-op.
        BuildGate gate = new BuildGate();
        assertFalse(gate.wasFinishBuildingCalled());
        assertTrue(gate.refuseFinish(), "nothing was built and nothing was called");
        assertFalse(gate.refuseFinish(), "...and it still gives way on the second attempt");
    }

    @Test
    void everyNewWishGetsItsOwnRequirement() {
        // Without the per-wish reset the guard would protect the FIRST wish of a session and then
        // silently stop, because the gate would still be holding the previous wish's finish_building.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        BuildGate.startWish(first);
        BuildGate.forWish(first).markBuilt();
        assertFalse(BuildGate.forWish(first).refuseFinish(), "first wish finished building");

        BuildGate.startWish(second);
        assertTrue(BuildGate.forWish(second).refuseFinish(),
                "a second wish must be shown to the child on its own merits");
    }

    @Test
    @DisplayName("#937: the child's SECOND wish starting never resets the FIRST wish mid-grant")
    void aSecondWishStartingLeavesTheFirstWishsGateAlone() {
        // Production, 2026-09-25: finish_building("SPIKOR IS HERE!") → the child wishes again →
        // the gate was per PLAYER and startWish REPLACED it → the first wish's finish_wish was
        // refused as never built → the agent announced SPIKOR three times.
        UUID spikor = UUID.randomUUID();
        UUID onFire = UUID.randomUUID();
        BuildGate.startWish(spikor);
        BuildGate.forWish(spikor).markBuilt();
        BuildGate.startWish(onFire);                         // same child, 18 s later
        assertFalse(BuildGate.forWish(spikor).refuseFinish(),
                "the first wish was built and announced; the second wish must not undo that");
        assertTrue(BuildGate.forWish(onFire).refuseFinish(), "the second wish has built nothing yet");
    }

    @Test
    @DisplayName("#937: claims and trigger sentences are per wish too")
    void theWishScopedStoresAreNotWipedByAnotherWish() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        BuildGate.startWish(a);
        WishScope.as(a.toString(), () -> {
            WishClaims.state(java.util.List.of("he breathes fire"));
            BuiltAbility.record("on_hit", "Hit him to see the fire.", "t");
        });
        BuildGate.startWish(b);                              // another wish starts
        WishScope.as(a.toString(), () -> {
            assertEquals(1, WishClaims.all().size(), "wish A still holds its claim");
            assertEquals("Hit him to see the fire.", BuiltAbility.describe());
        });
        WishScope.as(b.toString(), () -> {
            assertTrue(WishClaims.all().isEmpty(), "wish B starts with none of A's claims");
            assertEquals("", BuiltAbility.describe());
        });
        BuildGate.settle(a);
        WishScope.as(a.toString(), () -> assertTrue(WishClaims.all().isEmpty(), "a settled wish's state is gone"));
    }

    // ---- the world-visibility boundary ----

    @Test
    void nothingVisibleMeansKeepBuilding() {
        BuildGate gate = new BuildGate();
        assertFalse(gate.worldTouched());
        assertFalse(gate.warnStopBuilding(),
                "before anything is visible, building freely is the whole point");
    }

    @Test
    void aSecondBuildAfterSomethingIsVisibleIsWarnedOff() {
        // THE bug: the rule said "once something exists in his world, stop", but only the
        // finish_building boundary had teeth — so a wish spent 128 seconds rewriting a datapack
        // whose eggs were already flying in front of the child.
        BuildGate gate = new BuildGate();
        gate.markWorldTouched();
        assertTrue(gate.warnStopBuilding(), "building again after it's visible must be called out");
    }

    @Test
    void butOnlyOnce() {
        // Same lesson as refuseFinish: a guard that never stops nagging becomes the reason a wish
        // can't finish. Warn, redirect, then get out of the way.
        BuildGate gate = new BuildGate();
        gate.markWorldTouched();
        assertTrue(gate.warnStopBuilding());
        assertFalse(gate.warnStopBuilding(), "a second warning would just be noise");
    }

    @Test
    void theFirstThingToBecomeVisibleIsNotItselfAViolation() {
        // Spawning the creature, or switching its datapack on, is the build COMPLETING — not a
        // breach. Only work that comes after it is.
        BuildGate gate = new BuildGate();
        assertFalse(gate.warnStopBuilding(), "nothing visible yet");
        gate.markWorldTouched();
        assertTrue(gate.worldTouched());
    }

    @Test
    void aNewWishStartsWithNothingVisible() {
        UUID first = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        BuildGate.startWish(first);
        BuildGate.forWish(first).markWorldTouched();
        BuildGate.startWish(next);
        assertFalse(BuildGate.forWish(next).worldTouched(),
                "the previous wish's creature must not make the next wish start out of bounds");
    }

    @Test
    void twoWishesDoNotShareAGate() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        BuildGate.startWish(a);
        BuildGate.startWish(b);
        BuildGate.forWish(a).markBuilt();
        assertFalse(BuildGate.forWish(a).refuseFinish());
        assertTrue(BuildGate.forWish(b).refuseFinish(),
                "one wish's finish_building must not satisfy another's");
    }

    @Test
    void finishingAWishForgetsItsGate() {
        UUID wish = UUID.randomUUID();
        BuildGate.startWish(wish);
        BuildGate.forWish(wish).markBuilt();
        BuildGate.settle(wish);
        assertTrue(BuildGate.forWish(wish).refuseFinish(),
                "a cleared gate starts fresh rather than inheriting the last wish's build");
    }

    @Test
    @DisplayName("an unsound building is held back ONCE, then the wish is allowed to end anyway")
    void theSupportNudgeFiresOnceAndGivesWay() {
        // Until this was asked of the Create machine nudge; the rule is the same for every
        // one-shot here.
        BuildGate gate = new BuildGate();
        assertTrue(gate.nudgeAboutSupport(),
                "the first attempt to hand over a building that will not stand must be sent back");
        assertFalse(gate.nudgeAboutSupport(),
                "the second must go through. A hard refusal is a trap: a wish that ends "
                        + "imperfectly beats a wish that cannot end.");
        assertFalse(gate.nudgeAboutSupport(), "and it stays given-way");
    }
}
