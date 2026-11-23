package com.moddingfromamod.wish;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <b>The rules of {@code /moddy try <wish id>}, with no Minecraft in them</b>.
 *
 * <p>Two questions, both pure: is what he typed a wish id at all, and which replay command gives
 * him a copy of a thing a wish made. The Minecraft side ({@link WishTry}) only finds the wish, asks
 * the stores which kind a bare id is, and runs the answer as his own wish.
 *
 * <p>The replay commands are the ones a watch press already runs — the library's own verbs
 * ({@code wishmob spawn}, {@code wishbuild}, {@code wishdatapack … give}, {@code wishpaint}), each
 * allowed by {@link CommandGuard} — so a try grants exactly what pressing a button for that thing
 * would, and nothing a button could not.
 */
public final class TryPlan {

    /** The full id: a v4 UUID ({@code Wish.java:41}), which is what makes it a capability. */
    private static final Pattern SHORT = Pattern.compile("[0-9a-f]{8}");

    private TryPlan() {
    }

    /** What he typed, read as an id. */
    public record Id(UUID full, String shortId) {
        /** Is this the full id, or the eight characters a chat line shows? */
        public boolean isFull() {
            return full != null;
        }
    }

    /**
     * Read the argument as a wish id: the full UUID, or its first eight characters (what every
     * chat line and the tower's short id show). Anything else is not an id, and says so.
     */
    public static Optional<Id> parse(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.length() == 36) {
            try {
                UUID u = UUID.fromString(s);
                if (u.toString().equals(s)) {
                    return Optional.of(new Id(u, s.substring(0, 8)));
                }
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            return Optional.empty();
        }
        return SHORT.matcher(s).matches() ? Optional.of(new Id(null, s)) : Optional.empty();
    }

    /**
     * The command that hands a copy of {@code artifactId} to whoever runs it, or empty for a kind
     * that has no replay yet. {@code kind} is a {@code MadeKind} name.
     */
    public static Optional<String> commandFor(String kind, String artifactId) {
        if (artifactId == null || artifactId.isBlank() || !artifactId.matches("[A-Za-z0-9_\\-~]+")) {
            return Optional.empty();
        }
        String id = artifactId.trim();
        return switch (kind == null ? "" : kind) {
            case "creature" -> Optional.of("wishmob spawn " + id);
            case "build" -> Optional.of("wishbuild " + id);
            case "item" -> Optional.of("wishdatapack " + id + " give");
            case "painting" -> Optional.of("wishpaint " + id);
            // A datapack that is not an item (a rule, a time of day) is the WORLD's, not a thing
            // one child can be handed a copy of; and a modified vanilla mob is that one mob. Both
            // say so rather than guess.
            default -> Optional.empty();
        };
    }

    /**
     * Which kind a bare artifact id is, from which stores know it — for a wish whose record named
     * the thing but not its kind. First match wins, in the order a child is most likely to mean.
     */
    public static String inferKind(boolean isCreature, boolean isBuilding, boolean isItem,
                                   boolean isPicture) {
        if (isCreature) {
            return "creature";
        }
        if (isBuilding) {
            return "build";
        }
        if (isItem) {
            return "item";
        }
        if (isPicture) {
            return "painting";
        }
        return "";
    }
}
