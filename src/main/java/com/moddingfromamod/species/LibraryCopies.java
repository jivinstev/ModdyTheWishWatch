package com.moddingfromamod.species;

import com.google.gson.JsonObject;

/**
 * <b>The library-copy rule</b> (#99), pure: an UNOWNED copy of a library creature always takes its shipped health,
 * attack damage, armour and knockback resistance when it is read. Nothing else about it moves, and an OWNED creature
 * (a player's own, a fork of a library creature) is never touched.
 *
 * <p>Why it is safe: an edit of a library creature always FORKS it ({@code marshal.ForkPolicy}: a library artifact is
 * {@code FORK} for a child and {@code REFUSE} with nobody to own the fork), so an unowned copy under a library id is
 * a pure copy of the shipped record, made by its first spawn, with nothing in it that a player chose. Registered in
 * {@code docs/STORE_MIGRATIONS.md}.
 */
public final class LibraryCopies {

    private LibraryCopies() {
    }

    /**
     * {@code stored} with the shipped stat line when it is an unowned copy of a library creature ({@code shipped}
     * non-null: the library serves its id); otherwise {@code stored} itself.
     */
    public static Species takeShipped(Species stored, JsonObject shipped) {
        if (stored == null || shipped == null || !stored.ownerId().isEmpty()
                || com.moddingfromamod.marshal.ArtifactOwner.isFork(stored.id())) {
            return stored;
        }
        return stored.withShippedStats(shipped);
    }
}
