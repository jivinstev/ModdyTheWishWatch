package com.moddingfromamod.datapack;

import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>RECIPIENT — who ends up holding it.</b>
 *
 * <p><i>"give the piglin boss a gold axe"</i>. Every wish before this one handed the thing to the
 * child, because {@code gives()} wrote {@code /give @s} and there was nowhere else to put it.
 *
 * <p><b>A selector is a command, so this is a lookup and a regex and nothing else.</b> Letting an
 * agent write a selector fragment is letting it write arbitrary command text — {@code @e[...]} can
 * carry an {@code nbt=} or a {@code scores=} clause, and a {@code ]} ends the selector and starts
 * whatever follows. So there are exactly two forms: the word {@code me}, and an entity id matched
 * against {@link #ENTITY_ID}, which cannot contain a bracket, a space or a comma. The selector is
 * built here from the matched id; the agent's string never reaches the command.
 */
public final class ItemRecipient {

    private ItemRecipient() {
    }

    /** The child, and the default — every wish before this axis existed. */
    public static final String ME = "me";

    /** How far to look for the creature he means. Far enough to be the one he just conjured. */
    private static final int RANGE = 24;

    /**
     * A registry id and nothing else. Deliberately stricter than {@code ResourceLocation} —
     * no uppercase, no brackets, no commas, no spaces — because this string is spliced into a
     * selector rather than parsed by one.
     */
    private static final Pattern ENTITY_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Everything wrong with this recipient, in the agent's terms, or empty. */
    public static List<String> refusals(String recipient) {
        if (recipient == null || recipient.isBlank() || ME.equals(recipient)) {
            return List.of();
        }
        if (ENTITY_ID.matcher(recipient).matches()) {
            return List.of();
        }
        return List.of("recipient must be either \"" + ME + "\" — he gets it himself, which is "
                + "almost always right — or a full entity id like minecraft:piglin_brute, naming "
                + "the kind of creature that ends up holding it. '" + recipient + "' is neither. "
                + "A creature only gets it if one of that kind is within " + RANGE + " blocks, so "
                + "conjure or find it FIRST.");
    }

    /**
     * <b>The whole command, because a creature cannot be given anything with {@code /give}.</b>
     *
     * <p>⚠ This is the part that was wrong when the axis first shipped, and no gate could see it:
     * {@code /give} takes {@code EntityArgument.players()}, so
     * {@code /give @e[type=minecraft:piglin_brute,…] …} is refused with <i>"No player was found"</i>
     * — every time, for every mob, forever. The give-line matrix parsed the ITEM ARGUMENT and never
     * looked at the verb in front of it, so it reported a perfectly well-formed command that the
     * game will not run.
     *
     * <p>The verb that does take a mob is {@code /item replace entity <targets> <slot> with <item>}
     * ({@code EntityArgument.entities()}), which is also more honest about what happens: the piglin
     * ends up HOLDING the axe rather than having it dropped into an inventory it does not have.
     *
     * @param itemArgument the {@code <id>[<components>]} the give line was going to carry
     */
    public static String command(String recipient, String itemArgument, int count) {
        if (isMe(recipient)) {
            return "/give @s " + itemArgument + " " + count;
        }
        // 1 to 99 in the vanilla grammar, and a mob has one pair of hands anyway.
        return "/item replace entity " + selector(recipient) + " weapon.mainhand with "
                + itemArgument + " " + Math.max(1, Math.min(99, count));
    }

    /** Whether the child himself is the one holding it. */
    public static boolean isMe(String recipient) {
        return recipient == null || recipient.isBlank() || ME.equals(recipient);
    }

    /**
     * The selector naming who ends up holding it.
     *
     * <p>{@code limit=1,sort=nearest} on purpose: <i>"the piglin boss"</i> is one creature, and a
     * bare type selector would arm every piglin in the area. He asked for one axe.
     */
    public static String selector(String recipient) {
        if (recipient == null || recipient.isBlank() || ME.equals(recipient)) {
            return "@s";
        }
        if (!ENTITY_ID.matcher(recipient).matches()) {
            throw new IllegalStateException("unchecked recipient: " + recipient);
        }
        return "@e[type=" + recipient + ",limit=1,sort=nearest,distance=.." + RANGE + "]";
    }

    /** What to tell the child, since the thing he asked for does not arrive in his own hands. */
    public static String message(String recipient, String name) {
        if (recipient == null || recipient.isBlank() || ME.equals(recipient)) {
            return null;
        }
        String kind = recipient.substring(recipient.indexOf(':') + 1).replace('_', ' ');
        return "The " + kind + " has your " + name + "!";
    }
}
