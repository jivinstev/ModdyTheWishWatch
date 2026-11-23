package com.moddingfromamod.marshal;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Who should be told what Moddy is doing — the rule, separated from the telling.</b>
 *
 * <p>Moddy was written for one child, and with one child every audience question has the same
 * answer, so the code never had to ask. With two, "tell the child" and "tell the room" are different
 * instructions that look identical in a single-player world — and the progress layer was written
 * with one of each, side by side: the boss bar addresses the owner
 * ({@code WishProgress.addPlayer}) while the status line under it was written to <b>every</b> player
 * and then read back <b>per</b> player. So A's <i>"Building your castle"</i> arrived in B's status
 * slot and B's action bar rendered it as B's own.
 *
 * <p>Nobody decided that. It is the shape {@link BarBook} was extracted for one axis earlier — a
 * question about KEYS wearing the costume of a question about players — and it is kept here for the
 * same three reasons that one is: the rule is answered in microseconds by unit tests rather than by
 * a three-minute GameTest; none of it needs Minecraft; and a rule that lives in a pure class is one
 * the next surface can reuse instead of re-deriving.
 *
 * <p><b>The decision it encodes is D1/D2 of {@code docs/MULTIPLAYER_DECISIONS.md}: the owner alone.</b>
 * The two must match. If the bar is the owner's and the line is the room's, the cross-talk simply
 * returns by the other door — B reads A's words underneath B's own bar, and the two disagree on one
 * screen.
 */
public final class WishAudience {

    private WishAudience() {
    }

    /**
     * Everyone who should see narration about one wish.
     *
     * <p>⚠ <b>An unidentifiable owner is told to NOBODY, and that is the whole point.</b> The
     * tempting fallback — "I cannot tell whose wish this is, so I will tell everybody" — is
     * precisely the defect this class exists to remove, and it is tempting because with one child it
     * is indistinguishable from correct. It is also not the lossy choice it looks like:
     * {@code AgentContext.currentRun()} already resolves the single live run when there is exactly
     * one, so a null owner here means either nothing is in flight (there is nothing to say) or two
     * are and nothing said which (saying it to both is worse than saying it to neither).
     *
     * @param owner  whose wish this is, or {@code null} when the calling run cannot be identified
     * @param online everyone currently in the world
     * @return exactly the players to address — never null, frequently empty
     */
    public static Set<UUID> forNarration(UUID owner, Collection<UUID> online) {
        if (owner == null || online == null || !online.contains(owner)) {
            return Set.of();
        }
        return Set.of(owner);
    }

    /**
     * The one child a tool is acting on behalf of, or {@code null} when it cannot be told.
     *
     * <p><b>The same question the 42 "first player in the list" sites ask and answer wrongly</b> —
     * see D9. {@code getPlayerList().getPlayers().stream().findFirst()} resolves to whoever joined
     * first, which with two children is unrelated to who wished: {@code take_me_to} teleports the
     * wrong child and {@code give_items} hands over the wished thing to the wrong one.
     *
     * <p>Separate from {@link #forNarration} on purpose even though today they agree. Narration is a
     * SET because "who should hear this" is a question that could reasonably widen (D1 lists
     * "everyone within N blocks" as a rejected option); acting is a single child because there is no
     * sensible reading in which a tool teleports two people. Collapsing them would make the next
     * change to one silently change the other.
     */
    public static UUID actingPlayer(UUID owner, Collection<UUID> online) {
        return owner != null && online != null && online.contains(owner) ? owner : null;
    }
}
