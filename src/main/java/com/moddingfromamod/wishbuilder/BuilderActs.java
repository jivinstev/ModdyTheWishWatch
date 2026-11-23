package com.moddingfromamod.wishbuilder;

import java.util.List;

/**
 * <b>How a REAL creature acts</b> (from the R1 ratings: friendly wolves he asked for were wild wolves,
 * a nice warden was a warden). A real mob is summoned as the game makes it; this one choice on its tune
 * dialog then changes it the way the Magician's own {@code modify_mob} does: never attacks him, follows him, or stays
 * where it is put. Kept in the spec's {@code acts} opt, so a make from before this reads as the game's own.
 *
 * <p>Pure: {@code BuilderActsTest}.
 */
public final class BuilderActs {

    private BuilderActs() {
    }

    /** The spec's opt, the tune control's key and the tree's slot. */
    public static final String SLOT = "acts";
    /** As the game makes it: the default, never stored. */
    public static final String GAME = "game";

    public record Act(String id, String label, List<String> words) {
    }

    public static final List<Act> ALL = List.of(
            new Act(GAME, "As the game makes it", List.of("as it is", "real one", "normal one")),
            new Act("friendly", "Friendly (never attacks you)", List.of("friendly", "nice", "kind", "gentle", "my friend",
                    "my friends", "frends", "my frends", "be nice", "not scary", "wont hurt me",
                    "doesnt attack me", "never attacks")),
            new Act("pet", "Follows you", List.of("pet", "my pet", "pets", "follows me", "follow me", "tame", "tamed",
                    "loyal", "comes with me", "on my team", "goes with me")),
            new Act("stays", "Stays put", List.of("stays", "stays put", "stays there", "guards", "stays home", "doesnt move")));

    public static Act of(String id) {
        for (Act a : ALL) {
            if (a.id().equals(id)) {
                return a;
            }
        }
        return ALL.get(0);
    }
}
