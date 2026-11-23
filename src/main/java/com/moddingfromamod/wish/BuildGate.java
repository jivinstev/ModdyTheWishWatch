package com.moddingfromamod.wish;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes sure the child was actually SHOWN his wish before it is judged and closed.
 *
 * <p>{@code finish_building} is the beat that tells the child his wish is ready and hands the agent the photo
 * its verdict is supposed to be based on. Nothing enforced it: an agent that went straight to
 * {@code finish_wish} would close the wish having never announced it and never looked at it, which is
 * most of the old failure wearing a new hat.
 *
 * <p><b>Why it refuses ONCE and then gives way.</b> A hard refusal would be a trap. If finish_building itself
 * fails — the game thread busy, no player in the world — the agent would bounce between the two
 * forever. That is exactly the shape of the reload storm, where a tool error saying "try again in a
 * moment" was obeyed 35 times and made the game unplayable. So the first attempt is refused with a
 * specific instruction, and any attempt after that is allowed through.
 *
 * <p>The rule underneath is the one this whole redesign turns on: <b>a wish that ends imperfectly
 * beats a wish that cannot end.</b> Never let a guard become the reason a child watches a ticker
 * climb.
 *
 * <p>The gate state is pure and unit-tested; the per-player registry sits alongside it.
 */
public final class BuildGate {

    private boolean built;
    private boolean alreadyRefused;
    /** The lessons this wish has already been handed: each once, wherever it was asked for. */
    private final java.util.Set<String> lessonsGiven = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean worldTouched;
    private boolean alreadyWarned;

    /**
     * Something the wish makes is now VISIBLE in the child's world — a creature spawned, a datapack
     * switched on. From this instant the build is over.
     *
     * <p>This is the boundary the one-attempt rule was always supposed to use, and the reason it
     * didn't bite: the rule was written twice, once as "when something exists in his world" and once
     * as "after finish_building", and only the second had any enforcement. So the agent quite
     * reasonably kept working right up to finish_building — 128 seconds of debugging eggs on the
     * Dinko wish, with the child watching. See.
     */
    /** True the first time this wish is handed {@code kind}'s lessons; false every time after. */
    public boolean firstLessons(String kind) {
        return lessonsGiven.add(kind);
    }

    public void markWorldTouched() {
        this.worldTouched = true;
    }

    /** Has anything from this wish already reached the child's world? */
    public boolean worldTouched() {
        return worldTouched;
    }

    /**
     * Should a BUILD tool warn the agent off right now?
     *
     * <p>True once something is visible AND we haven't already said so for this wish. Like
     * {@link #refuseFinish} it fires once and then gives way — the warning exists to redirect, not
     * to make a wish unfinishable. Verification is deliberately not affected: checking whether the
     * eggs actually hurt Spikor is exactly what SHOULD happen here.
     */
    public boolean warnStopBuilding() {
        if (!worldTouched || alreadyWarned) {
            return false;
        }
        alreadyWarned = true;
        return true;
    }

    private boolean alreadyNudgedAboutClaims;

    /**
     * <b>Send the agent back once to check what the child actually asked for.</b>
     *
     * <p>Same one-shot rule as everything else here. What it buys is that the last thing before the
     * child is told is somebody asking "did the thing he asked for happen?" — which on the wish
     * that motivated it was never asked at all, across five separate finishes, while the agent told
     * him it worked each time.
     */
    public boolean nudgeAboutClaims() {
        if (alreadyNudgedAboutClaims) {
            return false;
        }
        alreadyNudgedAboutClaims = true;
        return true;
    }

    private boolean alreadyRefusedFalseClaims;

    /**
     * <b>Send the agent back once when the world contradicts a claim it marked as working</b>
     *. Once, like every guard here: the second time the claim is shown to the child as not
     * working, so the wish still ends — honestly.
     */
    public boolean refuseFalseClaimsOnce() {
        if (alreadyRefusedFalseClaims) {
            return false;
        }
        alreadyRefusedFalseClaims = true;
        return true;
    }

    /**
     * <b>Every one-shot below has its own slot</b>, because a complaint that shares a budget is a
     * complaint that never gets said: measured on the (since removed) Create machine guards,
     * 15 structural refusals against 1 unproven refusal over 30 rungs when two shared one slot.
     */
    private boolean alreadyNudgedAboutSupport;

    /**
     * <b>Its own slot </b>: a building with a ladder nobody can climb, or sand over air, is
     * held back from the reveal ONCE. Measured: the warning at {@code build_structure} time alone
     * was read and revealed past. Once, and then it gives way, like every guard here.
     */
    public boolean nudgeAboutSupport() {
        if (alreadyNudgedAboutSupport) {
            return false;
        }
        alreadyNudgedAboutSupport = true;
        return true;
    }

    // ⚠ THE SAFETY DIVERT BUDGET USED TO LIVE HERE, AND MOVED (docs/CHILD_SAFETY_PLAN.md §12.11).
    // It is now safety/SafetyRetryBudget, keyed by WISH rather than by player -- because this
    // class is per-player and startWish REPLACES a child's gate, so with more than one wish in
    // flight the second wish's start forgot the first one's spent divert, and between those
    // resets the two shared one flag. Every other slot here is about the BUILD and belongs to the
    // session; a plan grade belongs to the plan. Said out loud rather than silently deleted,
    // because the natural place to look for "the safety one-shot" is beside the other one-shots,
    // and finding nothing there reads as it never having existed.

    private boolean alreadyNudgedAboutTestedClaim;
    /**
     * <b>Send the agent back once when the note claims a test and the wish built nothing that
     * could have been tested.</b>
     *
     * <p>Its own budget for the same reason as every other gate here — but note that this one
     * cannot collide with them in practice: they all require an artifact to exist and this one
     * requires that none does. The separate slot is for the rule rather than for the arithmetic.
     */
    public boolean nudgeAboutTestedClaim() {
        if (alreadyNudgedAboutTestedClaim) {
            return false;
        }
        alreadyNudgedAboutTestedClaim = true;
        return true;
    }

    private boolean alreadyNudgedAboutUnbackedSay;
    /**
     * <b>Send the agent back once when its closing line promises a part no call built</b>.
     * Its own slot: sharing one with the tested-claim nudge would let the commoner complaint use up
     * the rarer one's only chance.
     */
    public boolean nudgeAboutUnbackedSay() {
        if (alreadyNudgedAboutUnbackedSay) {
            return false;
        }
        alreadyNudgedAboutUnbackedSay = true;
        return true;
    }

    private boolean alreadyNudgedAboutMissingCreature;
    private boolean alreadyNudgedAboutTeaching;

    /**
     * A command-granted wish that was never taught: refuse ONCE with an add_template draft,
     * then give way — a wish that ends untaught beats one that cannot end.
     */
    public boolean nudgeAboutTeaching() {
        if (alreadyNudgedAboutTeaching) {
            return false;
        }
        alreadyNudgedAboutTeaching = true;
        return true;
    }

    /**
     * <b>Send the agent back once when the creature this wish spawned is standing nowhere</b>
     *, so it can spawn it again or say so. If it ends the wish granted anyway, the
     * verdict is coerced to {@code failed} — a creature he cannot see was not granted.
     */
    public boolean nudgeAboutMissingCreature() {
        if (alreadyNudgedAboutMissingCreature) {
            return false;
        }
        alreadyNudgedAboutMissingCreature = true;
        return true;
    }

    /** The child has been shown the wish. */
    public void markBuilt() {
        this.built = true;
    }

    private boolean finishBuildingCalled;

    /**
     * <b>The agent CALLED finish_building</b> — whether or not anybody was there to be told.
     *
     * <p>Distinct from {@link #markBuilt()} on purpose, and the distinction was measured rather than
     * imagined. {@code markBuilt} is reached from inside
     * {@code for (ServerPlayer p : server.getPlayerList().getPlayers())}, so on a server with nobody
     * online the loop body never runs and the flag is never set — the agent does exactly what it is
     * told, and {@link #refuseFinish()} turns it away anyway with <i>"you haven't called
     * finish_building for this wish"</i>, which is untrue.
     *
     * <p>Found in the first real traffic mined into {@code corpus/real-tool-calls.jsonl}: in <b>7 of
     * 7</b> Gate C½ wishes the sequence ends {@code finish_building(ok) -> finish_wish(REFUSED) ->
     * finish_wish(ok)}, one wasted model round trip each at the most expensive moment in the wish.
     *
     * <p>⚠ It is mostly an INSTRUMENT fault, and saying so is the point: Gate C½ drives a
     * {@code FakePlayer} and has no player list, so every measurement taken there carries this extra
     * turn. Anyone reading a C½ transcript as evidence 's "~2x the turns it needs" would be
     * counting the harness. In real play a child is online and {@code markBuilt} fires.
     *
     * <p>But the underlying rule was still wrong, which is why this exists: the gate's job is to make
     * the agent call {@code finish_building}, not to make players be online. <b>Refusing an agent
     * that complied is the defect</b> — it burns a turn it cannot avoid and is told a false reason,
     * so it cannot act on the message either (docs/WISH_LINTERS.md P2).
     */
    public void markFinishBuildingCalled() {
        this.finishBuildingCalled = true;
    }

    public boolean wasFinishBuildingCalled() {
        return finishBuildingCalled;
    }

    public boolean isBuilt() {
        return built;
    }

    /**
     * Should {@code finish_wish} be turned away right now?
     *
     * <p>True only on the FIRST attempt to finish a wish that never finished building. Calling this records the
     * refusal, so a second attempt gets through however finish_building went — the agent is nudged, never
     * trapped.
     */
    public boolean refuseFinish() {
        // `finishBuildingCalled` as well as `built`: see markFinishBuildingCalled. The agent is
        // being asked to call finish_building, and once it has, turning it away costs a round trip
        // and tells it something untrue. Whether anyone was online to SEE the result is not
        // something the agent can do anything about.
        if (built || finishBuildingCalled || alreadyRefused) {
            return false;
        }
        alreadyRefused = true;
        return true;
    }

    // ---- per-WISH registry ----
    //
    // It was per PLAYER, and startWish REPLACED the player's gate: a child's second wish, submitted
    // while the first was still being granted, wiped the first wish's "built" mark, so the first
    // wish's finish_wish was refused as never built and the agent announced "SPIKOR IS HERE!" three
    // times (production, 2026-09-25). It also cleared three GLOBAL stores, so any child's wish
    // starting wiped every wish in flight. Now: one gate per wish, and the stores keep one state per
    // wish (WishScope) — a wish starting or settling touches only itself.

    private static final Map<UUID, BuildGate> GATES = new ConcurrentHashMap<>();

    /** The gate of this wish. */
    public static BuildGate forWish(UUID wishId) {
        return wishId == null ? new BuildGate() : GATES.computeIfAbsent(wishId, k -> new BuildGate());
    }

    /**
     * The gate of the wish this worker is granting ({@code AgentContext.currentRun()}), or a
     * throwaway gate when no wish can be named — a guard with nothing to guard is inert, never a
     * guess at another wish's gate.
     */
    public static BuildGate current() {
        try {
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            return forWish(run == null ? null : run.wishId());
        } catch (RuntimeException | LinkageError e) {
            return new BuildGate();
        }
    }

    /**
     * A new wish is starting: it gets a fresh gate, and its own trigger sentences, claims and
     * machine complaints start empty. Load-bearing: without a fresh gate the SECOND wish would
     * inherit a build and skip the check. It touches NO other wish.
     */
    public static void startWish(UUID wishId) {
        if (wishId == null) {
            return;
        }
        GATES.put(wishId, new BuildGate());
        forgetStores(wishId.toString());
    }

    /** A wish settled (any way): its gate and everything scoped to it go. */
    public static void settle(UUID wishId) {
        if (wishId == null) {
            return;
        }
        GATES.remove(wishId);
        forgetStores(wishId.toString());
    }

    private static void forgetStores(String key) {
        BuiltAbility.forget(key);
        WishClaims.forget(key);
    }

    public static void clearAll() {
        GATES.clear();
    }
}
