package com.moddingfromamod.item;

import java.util.List;
import java.util.Locale;

/**
 * <b>A refinement changes the thing in his hands — it does not hand him a second one.</b>
 *
 * <p>The stable feature id made an edit keep ONE design in the store, and every gate since
 * has counted the store. His inventory is a different count. Every made item carries its name,
 * colour, icon and properties as components baked into the stack when it was handed over, so an
 * edit that rewrote the design and the pack left the stack he was holding exactly as it was — and
 * then {@code make_item} told the agent to run {@code wishdatapack <id> give}, which handed over a
 * new one beside it. Measured in Gate B ({@code InventoryCountGameTest}), before this:
 * <i>"he asked to CHANGE his item and is now holding 2 of it: [1x 'Gt Refine 425', 1x 'Gt Refine
 * 425 Mega']"</i>. It had been on file for weeks as a quirk of an instrument —
 * {@code docs/rollout/ITEMS.md}'s {@code heldCount} dropping 672 → 192 across a rename rung,
 * <i>"because the stacks handed over under the old name stopped matching"</i>.
 *
 * <p>So an edit restamps what he already holds, in its own slot, and only asks for a hand-over when
 * there was nothing to restamp or he asked for more. Pure and Minecraft-free: the world half is
 * {@code MakeItemTool.restampHeld}.
 */
public final class HeldRestamp {

    private HeldRestamp() {
    }

    /**
     * Only a refinement restamps. A copy ({@code as_new}) must leave the first one exactly as it
     * was — that is — and a thing handed to a creature is not in anybody's bag.
     */
    public static boolean applies(ItemLineage.Route route, boolean toTheChild) {
        return route == ItemLineage.Route.EDIT_IN_PLACE && toTheChild;
    }

    /**
     * Whose bag an edit may reach into: the design's owner, or — for a design nobody owns, which is
     * every design older than the stamp — whoever holds it, which is what the shared pack's
     * behaviour change already does to them. Never another child's copy of an owned design: the
     * third face is that a refinement never touches another child's things.
     */
    public static boolean mayRestamp(String ownerId, String playerUuid) {
        return ownerId == null || ownerId.isEmpty() || ownerId.equals(playerUuid);
    }

    /**
     * Should the tool still tell the agent to hand one over?
     *
     * <p>Yes when nothing was restamped — he was not holding one, so without a give the edit is a
     * thing he cannot see. Yes when the call CHANGED the count, because "a whole pile of them" is
     * a request for more (the lesson's own {@code start_from + count} row). Otherwise no: the one
     * in his hand IS the edit, and a give now is how he ends up with two.
     *
     * @param askedCount the count this call sent, or null if it sent none
     */
    public static boolean stillHandOver(int restamped, Integer askedCount, int designCount) {
        return restamped == 0 || (askedCount != null && askedCount != designCount);
    }

    /**
     * Which member of the NEW family a stack he holds becomes, or -1 to leave it alone.
     *
     * <p>By name first — a flavour that kept its name is that flavour. Then a family of one on both
     * sides is a rename of the one thing. Then the same position when the family kept its size (a
     * rename of every member). Anything else cannot be told apart, and a guess would turn his
     * strawberry into mint; leaving it is the smaller wrong. Members the edit REMOVED are not this
     * method's business: {@code clearRetired} has already taken them.
     */
    public static int memberFor(String heldName, List<String> oldNames, List<String> newNames) {
        if (heldName == null || newNames.isEmpty()) {
            return -1;
        }
        String held = norm(heldName);
        for (int i = 0; i < newNames.size(); i++) {
            if (norm(newNames.get(i)).equals(held)) {
                return i;
            }
        }
        if (oldNames.size() == 1 && newNames.size() == 1) {
            return norm(oldNames.get(0)).equals(held) ? 0 : -1;
        }
        if (oldNames.size() == newNames.size()) {
            for (int i = 0; i < oldNames.size(); i++) {
                if (norm(oldNames.get(i)).equals(held)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String norm(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
