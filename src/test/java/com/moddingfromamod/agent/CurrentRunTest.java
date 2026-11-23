package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * which wish a tool call is for, when the caller's own wish has already ENDED.
 *
 * <p>{@link AgentContext#currentRun()} falls back to "the single live run" so a call with nothing
 * bound still works at one slot. It applied that fallback to a BOUND worker too — so a worker whose
 * wish had finished, calling {@code plan_wish} or {@code finish_building} a second time, was handed
 * whichever OTHER wish happened to be the only one in flight: another child's plan read out to him,
 * his bar moved, his wish finished by a stranger. Found as a Gate B test that passed alone and
 * failed after an earlier test left a wish open.
 */
class CurrentRunTest {

    private static final UUID STEVE = UUID.nameUUIDFromBytes("steve".getBytes());
    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes());

    @BeforeEach
    void setUp() {
        Marshal.global().clear();
        AgentContext.unbind();
    }

    @AfterEach
    void tearDown() {
        AgentContext.unbind();
        Marshal.global().clear();
    }

    @Test
    void aWorkerWhoseWishEndedIsNeverHandedSomebodyElsesWish() {
        UUID mine = UUID.randomUUID();
        UUID his = UUID.randomUUID();
        Marshal.global().submit(mine, STEVE, "make it daytime forever");
        Marshal.global().assign("worker-one", mine);
        Marshal.global().finish(mine);
        Marshal.global().submit(his, ALEX, "a castle on stilts");
        Marshal.global().assign("worker-two", his);

        assertNull(AgentContext.with("worker-one", () -> AgentContext.currentRun()),
                "worker-one's wish is DONE; a second plan_wish/finish_building from it was handed "
                        + "Alex's castle, the only other wish in flight");
    }

    @Test
    void aWorkerWhoseWishWasStoppedIsNeverHandedSomebodyElsesWish() {
        UUID mine = UUID.randomUUID();
        UUID his = UUID.randomUUID();
        Marshal.global().submit(mine, STEVE, "make it daytime forever");
        Marshal.global().assign("worker-one", mine);
        Marshal.global().cancel(mine, com.moddingfromamod.marshal.CancelMode.STOP, "", "");
        Marshal.global().woundDown("worker-one");
        Marshal.global().submit(his, ALEX, "a castle on stilts");
        Marshal.global().assign("worker-two", his);
        assertNull(AgentContext.with("worker-one", () -> AgentContext.currentRun()),
                "a stopped worker, after its wind-down, borrowed Alex's castle");
    }

    /**
     * a STOPPED wish's tool still running must not read as "no wish at all" — ActingChild answered that with
     * the first child online, and a stopped Builder creature was conjured in front of another child.
     */
    @Test
    void aWorkerWhoseWishWasStoppedKnowsItsWishEnded() {
        UUID mine = UUID.randomUUID();
        Marshal.global().submit(mine, STEVE, "a cat called Blaze Jr");
        Marshal.global().assign("worker-one", mine);
        org.junit.jupiter.api.Assertions.assertFalse(AgentContext.with("worker-one", () -> AgentContext.ownWishEnded()),
                "its wish is live");
        Marshal.global().cancel(mine, com.moddingfromamod.marshal.CancelMode.STOP, "", "");
        Marshal.global().finish(mine);
        org.junit.jupiter.api.Assertions.assertTrue(AgentContext.with("worker-one", () -> AgentContext.ownWishEnded()),
                "stopped mid-call: the worker must know its wish is over, not act for whoever joined first");
        org.junit.jupiter.api.Assertions.assertFalse(AgentContext.ownWishEnded(),
                "a caller with no worker (a probe, a console) has no wish to have ended");
        org.junit.jupiter.api.Assertions.assertFalse(AgentContext.with("never-assigned", () -> AgentContext.ownWishEnded()),
                "a session the marshal never gave a wish (a re-initialised CLI) is not an ended one");
    }

    @Test
    void aBoundWorkerStillFindsItsOwnLiveWish() {
        UUID mine = UUID.randomUUID();
        Marshal.global().submit(mine, STEVE, "make it daytime forever");
        WishRun run = Marshal.global().assign("worker-one", mine);
        assertSame(run, AgentContext.with("worker-one", () -> AgentContext.currentRun()));
    }

    /**
     * The fallback the single-slot server relies on is kept where it is honest: nothing bound (a
     * gate, a direct call), or a session the marshal has never seen (the CLI re-initialised its
     * MCP session mid-wish, so the new session id holds no run) — with exactly one run in flight.
     */
    @Test
    void theSingleLiveRunIsStillTheAnswerForACallerWithNoHistory() {
        UUID only = UUID.randomUUID();
        Marshal.global().submit(only, STEVE, "make it daytime forever");
        WishRun run = Marshal.global().assign("worker-one", only);
        assertSame(run, AgentContext.currentRun(), "nothing bound, one run: that one");
        assertSame(run, AgentContext.with("session-after-reinit", () -> AgentContext.currentRun()),
                "a session with no history, one run: that one");
    }
}
