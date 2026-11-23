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
