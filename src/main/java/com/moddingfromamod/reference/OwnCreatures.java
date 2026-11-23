package com.moddingfromamod.reference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * <b>Is this "character" really a creature a child wished for in this world?</b>
 *
 * <p>The Apprentice named a child's dragon "Scorch the Dragon"; his next wish, "Give Scorch lava
 * colours", was resolved as the CHARACTER Scorch, and the brief spent minutes researching a game's
 * boss of that name, which would also have given his own dragon somebody else's look. A name a child's
 * creature carries is his, not a character's, so nothing is looked up for it: not the prefetch, not
 * the brief, not the ride-along ({@link CharacterResolver#resolveSubject} applies this to every path).
 *
 * <p>Only WISHED creatures count (a species with an owner). The library's starters are unowned and
 * are often a real character on purpose, so a wish naming one is still looked up as before.
 *
 * <p>The match is by whole words, and the resolved name must be the creature's whole name or its
 * first words: "Scorch" is "Scorch the Dragon", but "Spikor" is not "Spikor's Helper" or "Big Spikor".
 */
public final class OwnCreatures {

    private OwnCreatures() {
    }

    /** Pure: does {@code resolved} name one of {@code creatureNames}? */
    public static boolean matches(String resolved, Collection<String> creatureNames) {
        List<String> want = words(resolved);
        if (want.isEmpty() || creatureNames == null) {
            return false;
        }
        for (String name : creatureNames) {
            List<String> have = words(name);
            if (have.size() >= want.size() && have.subList(0, want.size()).equals(want)) {
                return true;
            }
        }
        return false;
    }

    /** Does {@code resolved} name a creature a child wished for, among the creatures loaded now? */
    public static boolean wished(String resolved) {
        if (resolved == null || resolved.isBlank()) {
            return false;
        }
        try {
            return matches(resolved, wishedNames());
        } catch (RuntimeException e) {
            return false;   // a registry being reloaded costs a lookup, never a wish
        }
    }

    /** The names of every owned (wished) species loaded now. */
    static List<String> wishedNames() {
        List<String> out = new ArrayList<>();
        for (var s : com.moddingfromamod.species.SpeciesRegistry.all()) {
            if (s != null && !s.ownerId().isBlank() && s.name() != null && !s.name().isBlank()) {
                out.add(s.name());
            }
        }
        return out;
    }

    /** Lower-case words, apostrophe-s kept as part of its word so "Spikor's" is not "Spikor". */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String w : s.toLowerCase(Locale.ROOT).replace('’', '\'').split("[^a-z0-9']+")) {
            String t = w.replaceAll("^'+|'+$", "");
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
