package com.moddingfromamod.datapack;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <b>A block is not an item, and giving them one type is how {@code BLOCK=not_an_id} got in.</b>
 *
 * <p>{@code place}'s {@code BLOCK} fell through {@code derive}'s
 * {@code default -> the rest take their parameters straight through} branch and was <b>not
 * validated at all</b> — measured, {@code not_an_id} was accepted. The rendered
 * {@code /setblock ~ ~ ~ not_an_id} fails, the wand plays its sound, and nothing is placed. `rich`
 * at least caught that case with a colon check; this had nothing.
 *
 * <p>Its own class rather than a flag on {@link ItemRef} because the two really do differ, and the
 * difference is the useful part of the refusal: <b>a made item can never be a block.</b> An engine
 * that told the agent <i>"name a full id"</i> when it had written
 * {@code moddingfromamod:hot_coffee_plain} would be answering the wrong question — the id is
 * well-formed; the thing simply is not a block and never will be.
 *
 * <p>Existence is not asked here for the same reason it is not asked in {@link ItemRef}: it needs
 * the live registry, which is a Gate B question.
 */
public final class BlockRef {

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private BlockRef() {
    }

    /**
     * Why this cannot be a block, in the agent's terms, or null.
     *
     * @param field what to call the parameter, e.g. {@code "place's BLOCK"}
     */
    public static String refusal(String field, String raw) {
        return refusal(field, raw, RegistryExists.ALWAYS);
    }

    /** The same, with the live registry consulted — see {@link RegistryExists}. */
    public static String refusal(String field, String raw, RegistryExists registry) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return field + " must name a block, like minecraft:water or minecraft:oak_planks.";
        }
        ItemRef.Kind kind = ItemRef.parse(s).kind();
        if (kind == ItemRef.Kind.WISHED || kind == ItemRef.Kind.BLANK) {
            return field + " names '" + raw + "', which is an ITEM rather than a block — the "
                    + "things make_item makes go in his hand, and nothing can set one into the "
                    + "world as a block.\n"
                    + "Name a real block: minecraft:water, minecraft:lava, minecraft:oak_planks, or "
                    + "any block from a mod that is loaded. If what he wants is a machine he puts "
                    + "DOWN, that is shape `machine`, not this.";
        }
        if (!ID.matcher(s).matches()) {
            return field + " must be a full block id like minecraft:water, all lowercase. '"
                    + raw + "' is not one.";
        }
        if (!registry.has(s)) {
            return field + " names '" + raw + "', and there is no such block in the game. Check "
                    + "the spelling, and check the namespace — a block from a mod is only there if "
                    + "that mod is loaded. Nothing reports a miss: /setblock quietly places "
                    + "nothing while the item still plays its sound.";
        }
        return null;
    }
}
