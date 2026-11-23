package com.moddingfromamod.marshal;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>One worker, one wish — the invariant, and the door left open beside it.</b>
 *
 * <p>{@link Marshal#claim} states the rule in its own comment: <i>"A worker that already holds a
 * wish is not free; handing it a second is how one agent ends up interleaving two wishes with no
 * yield point, which is the design this rejects."</i> It enforces it correctly.
 *
 * <p><b>{@link Marshal#assign} does not, and that is the path a real wish takes.</b>
 * {@code AwaitWishTool} blocks on the wish QUEUE and then calls {@code assign} directly, so the
 * guard in {@code claim} is never consulted. Two dispatch paths, one invariant, enforced in one of
 * them.
 *
 * <p>Measured on a live session (2026-08-14, world {@code daddus moddy 4}): one agent was handed the
 * metal-factory wish at 23:20:09 and the skyscraper wish at 23:22:00, while the first was still
 * open — its {@code finish_wish} did not land until 23:25:26. Downstream that took out the child's
 * progress bar (the second {@code plan_wish} files under whichever run {@code currentRun()} answers
 * with) and left the skyscraper hovering, having plausibly inherited the factory's anchor.
 *
 * <p>These tests <b>characterise</b> rather than demand: the disagreement is written down and
 * asserted so it cannot drift further.
 *
 * <h2>The door is now shut — one level up</h2>
 *
 * <p>{@code AwaitWishTool} refuses a busy worker <b>before</b> {@code queue.await()}, which is the
 * only point in the path where nothing has been dequeued and a refusal therefore cannot lose a wish.
 * See {@code AwaitWishRefusesABusyWorkerTest}.
 *
 * <p><b>{@code assign} itself is deliberately still permissive</b>, and the test below still asserts
 * that. Refusing there would drop an already-dequeued wish on the floor — the reason the fix is not
 * in the obvious-looking place. {@code assign} does now LOG when it overwrites a worker's live wish,
 * so if a third caller ever appears, it says so instead of silently forgetting a build.
 */
class OneWishPerWorkerTest {

    private static Marshal freshMarshal() {
        return new Marshal(() -> 0L, () -> 3);   // three slots, so slot pressure is not the refusal
    }

    /** The rule, where it IS enforced. */
    @Test
    void claimRefusesAWorkerThatIsAlreadyBusy() {
        Marshal m = freshMarshal();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        m.submit(first, player, "make me a factory");
        m.submit(second, player, "make me a skyscraper");

        assertNotNull(m.claim("worker-1"), "the first wish should be claimable");
        assertNull(m.claim("worker-1"),
                "claim handed a second wish to a worker that is still working the first — the "
                        + "invariant this method's own comment describes");
    }

    /**
     * <b>The door beside it.</b>
     *
     * <p>If this test ever starts failing because {@code assign} began refusing, that is the fix
     * landing and this expectation should be inverted — deliberately, in the commit that reconciles
     * the two paths.
     */
    @Test
    void assignDoesNotEnforceIt_whichIsHowOneAgentGotTwoWishes() {
        Marshal m = freshMarshal();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        m.submit(first, player, "make me a factory");
        m.submit(second, player, "make me a skyscraper");

        assertNotNull(m.assign("worker-1", first));
        assertNotNull(m.assign("worker-1", second),
                "assign has started refusing a busy worker. If that is deliberate, invert this "
                        + "expectation — it is the #141 fix, and the wish path (AwaitWishTool) "
                        + "must be checked for wishes being taken off the queue and then dropped");

        // AND THE FIRST WISH IS FORGOTTEN, which is the part that breaks the child's screen:
        // byWorker is one entry per worker, so the second assignment overwrites the first and
        // nothing afterwards can say the factory wish was ever this worker's.
        var held = m.runForWorker("worker-1");
        assertNotNull(held);
        assertEquals(second, held.wishId(),
                "the worker's recorded wish should be the most recently assigned one — byWorker "
                        + "holds a single entry per worker, so the factory wish is simply gone");
    }
}
