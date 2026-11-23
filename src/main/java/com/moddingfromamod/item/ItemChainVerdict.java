package com.moddingfromamod.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>Did a grant → re-wish → undo chain on an item do the right thing?</b> Pure, so Gate D's
 * verdict can be unit-tested against archived runs instead of paid for again.
 *
 * <p>The first version knew one path: the agent makes an item, the re-wish edits that id in place,
 * the undo removes it. Two correct paths read as FAIL under it:
 * <ul>
 *   <li><b>a canned first wish</b> is served from the library and never reaches the agent, so the
 *       child's store holds nothing after it; and</li>
 *   <li><b>a re-wish of a SHARED design forks it</b>: the child gets his own copy under a new id
 *       ({@link ItemLineage#forkId}), and undo removes the copy while the shared design stays.</li>
 * </ul>
 * Snapshots are {@code id -> "shape|owner|…"}, as Gate D takes them from {@link ItemSpecStore}.
 */
public final class ItemChainVerdict {

    private ItemChainVerdict() {}

    public record Result(boolean pass, List<String> edited, List<String> forks, List<String> added,
                         boolean secondMachine, boolean undone) {}

    /**
     * @param agentLane the first wish reached the agent
     * @param canned    the first wish was served from a ready-made (library) design
     * @param undoAsked whether the chain included an undo at all
     */
    public static Result judge(boolean agentLane, boolean canned, Map<String, String> afterWish,
                               Map<String, String> afterModify, Map<String, String> afterUndo,
                               boolean undoAsked) {
        Map<String, String> a = afterWish == null ? Map.of() : afterWish;
        Map<String, String> b = afterModify;
        List<String> edited = new ArrayList<>();
        List<String> forks = new ArrayList<>();
        List<String> added = new ArrayList<>();
        if (b != null) {
            for (var e : b.entrySet()) {
                if (!a.containsKey(e.getKey())) {
                    added.add(e.getKey());
                    if (isChildsCopy(e.getKey(), e.getValue())) {
                        forks.add(e.getKey());
                    }
                } else if (!a.get(e.getKey()).equals(e.getValue())) {
                    edited.add(e.getKey());
                }
            }
        }
        // A fork IS the edit when the thing being edited was shared. Anything else new is a second
        // item, and a second MACHINE is the rebuild this chain exists to catch.
        boolean secondMachine = b != null && added.stream()
                .filter(id -> !forks.contains(id))
                .anyMatch(id -> shapeOf(b.get(id)).equals("machine"));
        List<String> changed = new ArrayList<>(edited);
        changed.addAll(forks);
        boolean undone = !undoAsked || (afterUndo != null
                && changed.stream().anyMatch(id -> !afterUndo.containsKey(id)));
        boolean pass = (agentLane || canned) && b != null && !changed.isEmpty() && !secondMachine
                && undone;
        return new Result(pass, edited, forks, added, secondMachine, undone);
    }

    /** The snapshot value Gate D records for one stored design. */
    public static String snapshot(ItemSpecStore.Spec sp) {
        return sp.shape() + "|" + sp.ownerId() + "|" + sp;
    }

    /** An id that is some child's own copy: owned, and ending in that child's short id. */
    static boolean isChildsCopy(String id, String snapshot) {
        String owner = field(snapshot, 1);
        if (owner.isBlank()) {
            return false;
        }
        String who = com.moddingfromamod.marshal.ArtifactOwner.shortIdOf(owner);
        return !who.isEmpty() && id.endsWith("_" + who);
    }

    private static String shapeOf(String snapshot) {
        return field(snapshot, 0);
    }

    private static String field(String snapshot, int n) {
        String[] parts = snapshot == null ? new String[0] : snapshot.split("\\|", 3);
        return parts.length > n ? parts[n] : "";
    }
}
