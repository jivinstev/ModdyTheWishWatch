package com.moddingfromamod.reference;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Which real character is this creature, once we have worked it out?</b>
 *
 * <p>Recorded when a creature is first built, and read again on every follow-up.
 *
 * <p>Why it cannot be re-derived: the first wish names the character ("make Tralalelo Tralalala the
 * shark"), but the second usually does not — "make him bigger", "give him a friendlier face". Asking
 * the resolver about that text returns nothing, so the cache would be missed at exactly the moment
 * it is finally warm, which is the whole point of the second pass.
 *
 * <p>Same shape as the capture-time stamping that fixed species-id matching, and for the same
 * reason: record a fact at the moment it is known instead of inferring it later. Every time this
 * project has inferred instead, it has been wrong silently.
 */
public final class CharacterLineage {

    /** species id -> the character it was resolved to. */
    private static final Map<String, String> BY_SPECIES = new ConcurrentHashMap<>();

    private CharacterLineage() {
    }

    /** Called when a creature is built, if the wish named a real character. */
    public static void remember(String speciesId, String character) {
        if (speciesId != null && !speciesId.isBlank()
                && character != null && !character.isBlank()) {
            BY_SPECIES.put(speciesId, character);
        }
    }

    public static Optional<String> forSpecies(String speciesId) {
        return Optional.ofNullable(speciesId).map(BY_SPECIES::get);
    }

    /**
     * The character for this wish: what the wish says, else what the creature already is.
     *
     * <p>Order matters. A wish that names a character wins, because a child may genuinely change
     * the subject; only when it names nobody do we fall back to the creature being edited.
     */
    public static Optional<String> resolve(String wishText, String speciesId) {
        return resolve(wishText, speciesId, com.moddingfromamod.marshal.LocalOnly.active());
    }

    /**
     * NOTHING during a Wish Builder make (a Builder pick, an Apprentice make, a typed re-wish change, the free
     * change). Its tool calls are lowered from reviewed picks, so there is no character to find: the text this would
     * read is the make's own name ("Sparky"), and asking about it cost a Muse Ember call (2–3 s, blocking the make) and
     * started the web reference researcher on a fan wiki; a species' remembered character started it too. Every reader
     * of the answer is agent-facing (the stand-in colour lever, the palette check), and a make has no agent.
     */
    static Optional<String> resolve(String wishText, String speciesId, boolean builderMake) {
        if (builderMake) {
            return Optional.empty();
        }
        Optional<String> fromWish = CharacterResolver.resolve(wishText);
        return fromWish.isPresent() ? fromWish : forSpecies(speciesId);
    }

    public static void clear() {
        BY_SPECIES.clear();
    }

    public static int size() {
        return BY_SPECIES.size();
    }
}
