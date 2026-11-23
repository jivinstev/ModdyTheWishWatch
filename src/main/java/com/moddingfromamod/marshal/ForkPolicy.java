package com.moddingfromamod.marshal;

/**
 * <b>Copy-on-refine: does this change edit the thing, or make the child his own copy?</b>
 *
 * <p>One pure decision with four inputs and three answers, so the rule is a unit test rather than
 * something only a two-child server mid-wish can show. The Minecraft-facing half — reading the
 * species, copying the geometry, re-pointing the creature standing in the world — asks this and
 * then does what it says.
 *
 * <h2>What it is protecting</h2>
 *
 * <p>The library is <i>"a source of bases, never a write target"</i> ({@code WishLibrary.artifactDir}
 * says so and returns null rather than a hopeful path). But refusing to WRITE into the library
 * directory is only half of it: an edit that saves a species under the same id writes it into
 * <b>this world</b>, and {@code ModelRepository.readable} prefers the world's copy over the
 * library's. So an unforked edit does not corrupt the library — it <i>shadows</i> it, for every
 * child on the server, invisibly and with every gate green. That is the failure this exists for,
 * and it is why LIBRARY is the first question asked.
 *
 * <h2>Why SHARED is a separate answer from OWN</h2>
 *
 * <p>Both write under the same key. The difference is the stamp: editing my own thing keeps my
 * name on it, while editing a thing nobody claimed must <b>not</b> quietly put my name on it —
 * every artifact in every world that exists today is unowned, and a first refinement that claimed
 * one would take a brother's castle away from him for the price of touching it. "Absent = legacy =
 * shared" is the plan's own rule and it has to survive being written to.
 *
 * <h2>And why an unidentifiable asker never forks</h2>
 *
 * <p>A fork key is {@code <base>~<short owner id>}; with no owner there is no key to make, so
 * there is no fork available even in principle. {@code AgentContext.currentRun()} returns null for
 * a gate, a direct call, or two wishes in flight with nothing bound, and {@link ArtifactOwner}
 * turns that into SHARED. The consequence is the one a home server wants: <b>a single child, with
 * or without a run bound, behaves exactly as it did before per-child ownership</b> for everything except a
 * library artifact, where forking is refused-in-place — the edit is declined rather than allowed
 * to shadow the curated copy.
 */
public final class ForkPolicy {

    /** What the caller should do with the change it is holding. */
    public enum Decision {
        /** Write it back under the same key, keeping the asker's stamp. */
        EDIT_IN_PLACE,
        /** Write it back under the same key and leave it unowned — it was, and stays, everyone's. */
        EDIT_SHARED,
        /** Write it under {@link ArtifactOwner#forkKey}, owned by the asker. The base is untouched. */
        FORK,
        /**
         * Do not write.
         *
         * <p>Only ever the answer for a LIBRARY artifact with nobody to attribute a fork to. There
         * is no third option there: editing shadows the curated copy for everyone, and forking
         * needs an owner. Saying so is better than either.
         */
        REFUSE
    }

    private ForkPolicy() {
    }

    /**
     * @param askerId     the child whose wish this is — {@code ""} when nothing is bound
     * @param targetKey   the artifact being refined
     * @param targetOwner the owner stamped on it, {@code ""} for legacy/shared
     * @param inLibrary   whether the library serves this key (a base, not a write target)
     */
    public static Decision decide(String askerId, String targetKey, String targetOwner,
                                  boolean inLibrary) {
        String asker = ArtifactOwner.normalise(askerId);
        if (inLibrary) {
            return asker.isEmpty() ? Decision.REFUSE : Decision.FORK;
        }
        // HIS OWN FORK, BY THE KEY, not only by the stamp. A fork's key carries its owner, so a
        // record whose stamp went missing — hand-edited, or restored from a bundle written by an
        // older build — still edits in place for the child it is named after rather than growing
        // him a second fork of his own thing.
        if (!asker.isEmpty() && ArtifactOwner.isForkOf(targetKey, asker)) {
            return Decision.EDIT_IN_PLACE;
        }
        return switch (ArtifactOwner.scopeOf(asker, targetOwner)) {
            case OWN -> Decision.EDIT_IN_PLACE;
            case SHARED -> Decision.EDIT_SHARED;
            case OTHER -> asker.isEmpty() ? Decision.EDIT_SHARED : Decision.FORK;
        };
    }

    /**
     * The key the change should be written under, given {@link #decide}'s answer.
     *
     * @return {@code ""} for {@link Decision#REFUSE}, so a caller that ignores the decision and
     *         uses the key still writes nowhere rather than writing over the base
     */
    public static String keyFor(Decision decision, String targetKey, String askerId) {
        if (decision == Decision.REFUSE) {
            return "";
        }
        if (decision == Decision.FORK) {
            return ArtifactOwner.forkKey(targetKey, askerId);
        }
        return targetKey == null ? "" : targetKey;
    }

    /**
     * The owner to stamp on the result.
     *
     * <p>{@code ""} for {@link Decision#EDIT_SHARED} — see the class note: a shared thing that is
     * refined stays shared.
     */
    public static String ownerFor(Decision decision, String askerId, String targetOwner) {
        return switch (decision) {
            case FORK, EDIT_IN_PLACE -> ArtifactOwner.normalise(
                    decision == ForkPolicy.Decision.FORK ? askerId : firstNonEmpty(targetOwner, askerId));
            case EDIT_SHARED, REFUSE -> "";
        };
    }

    private static String firstNonEmpty(String a, String b) {
        String first = ArtifactOwner.normalise(a);
        return first.isEmpty() ? ArtifactOwner.normalise(b) : first;
    }
}
