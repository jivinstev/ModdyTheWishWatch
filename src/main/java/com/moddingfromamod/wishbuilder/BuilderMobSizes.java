package com.moddingfromamod.wishbuilder;

import java.util.List;

/**
 * the named sizes a creature is made at, each a distinct scale, so "tiny" and "small",
 * "huge" and "giant" make different things. A real creature carries it as {@code minecraft:scale}; the Builder's own
 * bodies and ready-mades too, as their sculpt's scale. The tune dialog asks it
 * ({@code mobsize}), the tree exports it as a slot, and the lowering scales by it. A body's spec also keeps the old
 * 1–4 {@code size} ({@link #oldSize}), which its name and the learned behaviour model read, so neither moved.
 */
public final class BuilderMobSizes {

    /** One size: its dialog id, its name, its scale and the words a child says it with. */
    public record Level(String id, String name, double scale, List<String> words) {
    }

    public static final String NORMAL = "4";

    public static final List<Level> ALL = List.of(
            new Level("1", "Teeny", 0.25, List.of("teeny", "teeny tiny", "itty bitty", "pocket", "pocket sized")),
            new Level("2", "Tiny", 0.4, List.of("tiny", "mini", "miniature")),
            new Level("3", "Small", 0.6, List.of("small", "little", "baby", "smaller")),
            new Level("4", "Normal", 1.0, List.of("normal", "normal sized", "regular", "regular sized")),
            new Level("5", "Big", 1.5, List.of("big", "large", "bigger", "tall")),
            new Level("6", "Huge", 2.5, List.of("huge", "enormous", "massive", "humongous")),
            new Level("7", "Giant", 4.0, List.of("giant", "gigantic", "ginormous", "mega")),
            new Level("8", "Colossal", 8.0, List.of("colossal", "titan", "titanic", "as big as a house", "skyscraper sized")));

    private BuilderMobSizes() {
    }

    /** The old four-step size a level is nearest (Small and below 1, Normal 2, Big 3, Huge and above 4). */
    public static int oldSize(Level l) {
        int i = Integer.parseInt(l.id());
        return i <= 3 ? 1 : i == 4 ? 2 : i == 5 ? 3 : 4;
    }

    /** The level an old four-step size was (1 Small 0.6, 2 Normal, 3 Big, 4 Huge 2.5: the old scales, near enough). */
    public static Level fromOldSize(int size) {
        return of(switch (size) {
            case 1 -> "3";
            case 3 -> "5";
            case 4 -> "6";
            default -> NORMAL;
        });
    }

    /** The level with this id, or Normal. */
    public static Level of(String id) {
        for (Level l : ALL) {
            if (l.id().equals(id)) {
                return l;
            }
        }
        return ALL.get(3);
    }
}
