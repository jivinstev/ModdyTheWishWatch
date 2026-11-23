package com.moddingfromamod.marshal;

/**
 * <b>Which thing a press on a watch tile does</b> (converged for every edition 2026-11-10). Minecraft-free,
 * so the rule is a unit test rather than a click.
 *
 * <p> (2026-10-14) made a LEFT-click on a past wish make it again and a RIGHT-click open its card
 * of verbs in chat. A phone has no right-click, so on Bedrock the card — go see it, give me one, change it,
 * hide — was out of reach. The decision of 2026-11-10 ("converge paths"):
 * <b>pressing one of his things opens its own page of actions</b>, {@code ThingPage}, on every edition — a
 * button chest through {@code ui/MenuKit}, so a phone gets it as a one-tap grid with no Bedrock code. "Make
 * another one" is that page's first tile.
 *
 * <ul>
 *   <li><b>One of his things</b> (a creation): its page, either button.</li>
 *   <li><b>A ✨ button</b> (a preset, or one of his saved buttons): a press wishes it, as always; a Java
 *       right-click still opens its page (the one place a button can offer "hide"). Not advertised:
 *       no tile names a mouse button.</li>
 *   <li>Everything else — controls, a happening, the guide — does what its cell says, either button.</li>
 * </ul>
 */
public final class WatchClick {

    /** What a click resolves to. */
    public enum Press {
        /** Do the cell's own action ({@link WatchPanel.Cell#action()}), as every press did before. */
        AS_CELL,
        /** Open the thing's own page of actions ({@code ThingPage}). */
        CARD
    }

    private WatchClick() {
    }

    /**
     * @param secondary true for a right-click ({@code PICKUP} with {@code button == 1})
     */
    public static Press decide(WatchPanel.Family family, WatchPanel.Action action, boolean secondary,
                               boolean canRemake) {
        if (family == WatchPanel.Family.CREATION && action == WatchPanel.Action.CARD) {
            return Press.CARD;
        }
        if (family == WatchPanel.Family.PRESET && action == WatchPanel.Action.WISH) {
            return secondary ? Press.CARD : Press.AS_CELL;
        }
        return Press.AS_CELL;
    }

    /** {@link #decide} for a cell. */
    public static Press decide(WatchPanel.Cell cell, boolean secondary) {
        return decide(cell.family(), cell.action(), secondary, cell.canRemake());
    }

    /**
     * What a watch press names in the action log (step 1): an id, never words. A recipe press logs its recipe
     * id; a thing, a happening, a page turn or a go logs its own id or number. A preset's text and a help wish's
     * pre-filled words are not logged: the recipe id names the first, and the second is ours.
     */
    public static String logged(WatchPanel.Cell cell) {
        if (!cell.recipeId().isBlank()) {
            return cell.recipeId();
        }
        return switch (cell.action()) {
            case WISH, HELP_WISH, COMPOSE -> "";
            default -> cell.arg();
        };
    }

    /** Is this click a right-click? A stock client's PICKUP carries the button: 0 left, 1 right. */
    public static boolean secondary(boolean pickup, int button) {
        return pickup && button == 1;
    }

    /** A creation's last tooltip line: what a press does, in words true on every edition. */
    public static String creationHint(boolean canRemake) {
        return canRemake ? "Press to make another, see it or change it" : "Press to see what you can do with it";
    }

    /** A ✨ button's last tooltip line. */
    public static String presetHint(boolean all) {
        return all ? "Press to wish for ALL of it again!" : "Press to wish for this!";
    }
}
