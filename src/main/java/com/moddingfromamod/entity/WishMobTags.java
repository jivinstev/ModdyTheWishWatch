package com.moddingfromamod.entity;

import java.util.Locale;

import com.moddingfromamod.marshal.KidTyping;

/**
 * The stable handle for referring to a wish creature from a command or a datapack.
 *
 * <p><b>Why this exists.</b> Creature identity used to be write-only: the agent could sculpt a
 * creature and then had no way to select it again. On 2026-08-04 that cost four minutes of a
 * live wish — it sculpted Spikor, then tried {@code Tags}, then {@code CustomName} with the colour
 * codes escaped, then guessed at entity types ({@code type=minecraft:zombie}? {@code hoglin}?) —
 * and it broke a datapack compile outright, because the only handle it had was the DISPLAY NAME and
 * deriving a scoreboard objective from "§6SPIKOR, King of the Kroaks" fails:
 * {@code Cannot create objective "King of the Kroaks"", name is 19 characters (max is 16)}.
 *
 * <p>A display name is for humans: it is renameable, it carries formatting codes, it contains
 * spaces and punctuation, and it is not unique. None of that is true of these tags, which is the
 * whole point. Applied in {@link WishMobEntity#applySpecies} — the one place that runs on spawn, on
 * world load AND on a species edit — so creatures made before this existed get tagged the next time
 * their world opens, with no migration.
 *
 * <p>Pure and Minecraft-free so the format is pinned by unit tests: the agent is TOLD these strings
 * in tool results, so changing one silently would break every datapack that ever used it.
 */
public final class WishMobTags {

    /** On every wish creature, whatever its species — "all the things I made". */
    public static final String ALL = "wishmob";

    /** Prefix for the per-species tag. */
    public static final String PREFIX = "wishmob_";

    private WishMobTags() {
    }

    /**
     * The tag identifying one species, e.g. {@code wishmob_spikor}. Sanitised to the character set
     * a command selector can carry, because a tag the agent cannot type is no better than no tag.
     */
    public static String forSpecies(String speciesId) {
        if (speciesId == null || speciesId.isBlank()) {
            return ALL;
        }
        // A Minecraft colour code is TWO characters — the section sign AND the digit/letter after
        // it. Dropping only the section sign leaves the code behind, turning "§6SPIKOR" into
        // "6spikor"; the unit test caught exactly that. ONE copy of that rule, in KidTyping, because
        // this file having its own is how the naming ladder went on matching against "6spikor" for
        // months after this was fixed here.
        String plain = KidTyping.plain(speciesId).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(PREFIX);
        for (char c : plain.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            }
            // Anything else (punctuation, accents, emoji) is dropped rather than escaped: a
            // selector is not a place for cleverness.
        }
        // Collapse and trim the underscores that dropped punctuation leaves behind, so
        // "SPIKOR, King" doesn't become "spikor__king" and "trailing..." doesn't keep a tail.
        String body = sb.substring(PREFIX.length()).replaceAll("_{2,}", "_")
                .replaceAll("^_+", "").replaceAll("_+$", "");
        return body.isEmpty() ? ALL : PREFIX + body;
    }

    /** Is this one of ours? Used to clear a stale tag when a creature changes species. */
    public static boolean isSpeciesTag(String tag) {
        return tag != null && tag.startsWith(PREFIX);
    }

    /**
     * A ready-to-run selector for one species, e.g. {@code @e[tag=wishmob_spikor]}. Handed back in
     * tool results so the agent never has to invent one — the whole failure this class prevents.
     */
    public static String selector(String speciesId) {
        return "@e[tag=" + forSpecies(speciesId) + "]";
    }

    /** The same, limited to the single nearest one — what an aimed effect usually wants. */
    public static String nearestSelector(String speciesId) {
        return "@e[tag=" + forSpecies(speciesId) + ",limit=1,sort=nearest]";
    }
}
