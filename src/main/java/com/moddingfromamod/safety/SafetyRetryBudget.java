package com.moddingfromamod.safety;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>One chance to re-plan, per WISH.</b> ({@code docs/CHILD_SAFETY_PLAN.md} §2 "Retry budget",
 * and a maintainer's decision 12.11.)
 *
 * <p>A plan graded {@code LIKELY} is diverted: the agent is refused, told which step and what to
 * do instead, and plans again. A plan diverted <b>twice</b> ends the wish. That is
 * {@link com.moddingfromamod.wish.BuildGate}'s rule landing the other way up — <i>a wish that ends
 * imperfectly beats a wish that cannot end</i>, and here the loop is what must stop, because an
 * agent that can be diverted for ever is a child watching a bar that never moves.
 *
 * <h2>⚠ Why this is not a slot on BuildGate, which is where it started</h2>
 *
 * <p>CS-S3 put it there, and {@code BuildGate} is keyed by PLAYER. Two things follow that are both
 * wrong, and they pull in opposite directions:
 *
 * <ul>
 *   <li><b>A reset that loses a divert.</b> {@code WishProgress.started} calls
 *       {@code BuildGate.startWish(playerId)}, which REPLACES that child's gate — so with
 *       {@code wishslots > 1}, the moment a second wish starts the first one's spent budget is
 *       forgotten and it can be diverted again. The wish that should have stopped does not.</li>
 *   <li><b>A budget spent by somebody else.</b> Between those resets the two wishes SHARE one
 *       flag, so wish A's divert can end wish B on B's first offence. The wish that should have
 *       been offered a second chance is stopped instead.</li>
 * </ul>
 *
 * <p>Either is bad; the pair is worse, because they are the same bug reading as two unrelated
 * ones. And the consequence is not a missing nudge like every other slot on that class — it is
 * whether a child's wish is <b>stopped</b>, decided by evidence from a different wish entirely.
 * So the key is the wish, which is what the rule was always about.
 *
 * <p>The other {@code BuildGate} slots stay where they are on purpose: they are about the build,
 * and a build belongs to a player's session in a way a plan grade does not.
 *
 * <h2>Bounded, so nothing has to remember to clean up</h2>
 *
 * <p>{@link #forget} exists and {@link SafetyPlanGate#endWish} calls it, but the bound is what
 * actually prevents a leak: an exit path somebody has to remember is not a control, and a wish can
 * end half a dozen ways (a stop, a cancel, a crash, a logout). {@link #MAX_WISHES} entries, oldest
 * evicted — three orders of magnitude above any real {@code wishslots}, so an eviction can never
 * reach a wish still in flight, and one that somehow did would hand the agent a second chance
 * rather than ending a wish early.
 */
public final class SafetyRetryBudget {

    /**
     * How many wishes are remembered at once.
     *
     * <p>Not a tuning knob. It only has to exceed the number of wishes that can be in flight
     * (`wishslots`, a small single digit) by enough that eviction is unreachable in practice; a
     * long evening of play is a few dozen wishes and costs one boolean each.
     */
    static final int MAX_WISHES = 256;

    /**
     * Insertion-ordered and access-synchronised rather than a {@code ConcurrentHashMap}, because
     * the eviction rule needs an order and this is asked once per {@code plan_wish} call — a few
     * times per wish, against a grade that costs 10–40 s. Contention is not a thing that can
     * happen here.
     */
    private static final Map<UUID, Boolean> DIVERTED =
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > MAX_WISHES;
                }
            };

    private SafetyRetryBudget() {
    }

    /**
     * <b>Spend the one divert.</b> True the first time this wish asks, false ever after.
     *
     * <p>A null wish id has no budget and answers true every time — which is deliberate and is the
     * same answer {@link SafetyPlanGate#decide} gave before: Gate C½ and the headless probe run
     * wishes with no marshal run behind them, and falling through to STOP because there is nobody
     * to hold a budget would end every objectionable plan on its first offence in exactly the
     * environment the arms are measured in.
     *
     * <p><b>Consumed in shadow mode too</b>, deliberately. Nothing outside the safety gate reads
     * this, so spending it changes nothing the child or the agent can observe — and a shadow run
     * whose log says {@code SHADOW_DIVERT} twice, where the real arm would have said
     * {@code SHADOW_DIVERT} then {@code SHADOW_STOP}, is a base rate that predicts the wrong
     * behaviour. Shadow mode exists to say what ON would have done.
     */
    public static boolean divertOnce(UUID wishId) {
        if (wishId == null) {
            return true;
        }
        synchronized (DIVERTED) {
            return DIVERTED.put(wishId, Boolean.TRUE) == null;
        }
    }

    /**
     * Has this wish already been diverted? <b>Reads without spending.</b>
     *
     * <p>Separate from {@link #divertOnce} because the log needs the retry number for <i>every</i>
     * grade — a FINE plan on a wish that was diverted earlier is retry 1 and is the single most
     * interesting record in the file, since it is the evidence that a divert worked. Asking the
     * consuming method would burn the budget on a plan nobody objected to.
     */
    public static boolean alreadyDiverted(UUID wishId) {
        if (wishId == null) {
            return false;
        }
        synchronized (DIVERTED) {
            return DIVERTED.containsKey(wishId);
        }
    }

    /** This wish is over. Tidiness rather than correctness — see the bound on the class. */
    public static void forget(UUID wishId) {
        if (wishId == null) {
            return;
        }
        synchronized (DIVERTED) {
            DIVERTED.remove(wishId);
        }
    }

    /** Between tests, never in play: this is process-wide and a Gate B run shares it. */
    public static void clearAll() {
        synchronized (DIVERTED) {
            DIVERTED.clear();
        }
    }
}
