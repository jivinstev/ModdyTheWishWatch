package com.moddingfromamod.item;

/**
 * <b>Refining an item this engine did not mint</b>.
 *
 * <h2>The failure</h2>
 * {@code start_from} looks the id up in {@link ItemSpecStore}, which holds designs this engine
 * made. A wish like <i>"make my diamond sword unbreakable"</i> or <i>"give the piglin a gold axe
 * that never brakes"</i> names something that was never minted here, so the lookup misses, and the
 * agent falls back to {@code base_item} — which builds a COPY. The child ends the wish holding
 * <b>two</b> swords, one of them a stranger wearing the name of the other.
 *
 * <p>The quantity to gate on is exactly that: extra items in his inventory after the wish, which
 * {@code ItemSpecStore} was built to hold at zero and which is still live for anything the engine
 * did not make.
 *
 * <h2>Why editing in place beats reforging, and why it is safe</h2>
 * <b>A mod's behaviour lives in Java, not in components.</b> Reforging recovers what components
 * express and silently drops the rest — so <i>"make the bloop slayer stronger"</i> returns a
 * trident that hits harder and no longer does the one thing it is named for, and it <i>looks
 * correct</i>. Editing the actual stack cannot have that failure mode, because it is still the same
 * item.
 *
 * <p>Both halves of that were measured before this was written rather than assumed, on a REAL
 * foreign item with Create loaded ({@code ForeignItemEditSpikeGameTest}):
 *
 * <pre>
 * SPIKE306 item=create:wrench survivedSaveLoad=true sameItemAfter=true name=Bloop Slayer maxDamage=4321
 * SPIKE306 componentDrivenReachable=true stockMaxDamage=0 afterOverride=500
 * </pre>
 *
 * The spike was written to be able to REFUTE this direction — had a component write not survived a
 * save, or had the item changed identity across one, in-place would grant nothing and the honest
 * answer would invert to "refuse, and name what it can do". It survived both.
 *
 * <h2>The bound this does not cross</h2>
 * A component is the source of truth for <b>component-driven</b> properties only, and is ignored by
 * anything a mod computes in Java. That is not an argument against editing — a reforged copy cannot
 * reach it either, and additionally loses it — but it bounds what a refinement may promise, which
 * is why {@link #REACH} exists as a sentence the agent can say rather than a caveat in a docstring.
 */
public final class ForeignItemEdit {

    private ForeignItemEdit() {
    }

    /** What to do with a {@code start_from} that {@link ItemSpecStore} does not know. */
    public enum Route {
        /** It is one of our designs: the existing path, untouched. */
        MINT,
        /** A real item, and somebody is carrying it — write onto that stack and make nothing. */
        EDIT_IN_PLACE,
        /** A real item nobody is carrying — build a copy, and SAY it is a copy. */
        REFORGE,
        /** Not a design and not a real id — say so, and name both routes. */
        REFUSE
    }

    /**
     * @param isOurDesign        {@code ItemSpecStore} knows this id
     * @param resolvesInRegistry it is a real registered item id, ours or a mod's or vanilla's
     * @param somebodyHasIt      the actual stack is in a player's inventory right now
     */
    public static Route decide(boolean isOurDesign, boolean resolvesInRegistry,
                               boolean somebodyHasIt) {
        if (isOurDesign) {
            return Route.MINT;
        }
        if (!resolvesInRegistry) {
            return Route.REFUSE;
        }
        return somebodyHasIt ? Route.EDIT_IN_PLACE : Route.REFORGE;
    }

    /** What the agent may honestly promise about an in-place edit. */
    public static final String REACH =
            "This changed the item he is already holding rather than making a second one, so it "
            + "keeps everything it could do before. What an edit can change is what the game reads "
            + "off the item — how hard it hits, whether it wears out, how many stack. Anything the "
            + "mod that made it works out in its own code is untouched, so do not promise that part "
            + "is different.";

    /** Said to the agent when a copy is the only route left, so the copy is never silent. */
    public static String reforgeNotice(String id) {
        return "Nobody is carrying '" + id + "', so I cannot change the one he owns — this is a "
                + "NEW item built to look like it. Tell him that plainly: a copy of a mod's item "
                + "keeps what the game reads off it and loses whatever that mod does in its own "
                + "code, so if it was special for a reason, the copy will not be. If he goes and "
                + "gets the real one out, wish again and I can change that one instead.";
    }

    /** Neither route applies. Both are named, because silently doing the wrong one is the bug. */
    public static String refusal(String id) {
        return "There is no item called '" + id + "' — not one I made, and not a real item id in "
                + "this game either. Two things it could be: something I made, in which case call "
                + "inspect_item and use its exact id; or something a mod made, in which case pass "
                + "its full registry id (like 'minecraft:diamond_sword') and have him HOLD it, and "
                + "I will change that one rather than making a second. If he is asking for a NEW "
                + "thing, leave start_from out — but do not invent an id, because making a second "
                + "item when he asked to change the first is the failure this parameter exists to "
                + "prevent.";
    }
}
