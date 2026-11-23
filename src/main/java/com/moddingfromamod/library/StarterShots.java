package com.moddingfromamod.library;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>What a starter-library entry puts in the world, and so what the camera points at</b>.
 *
 * <p>The starter photographer replays each entry through the commands a child's press runs, then
 * photographs the thing those commands made. Which thing that is, and whether it moves, is read
 * off the commands here — Minecraft-free, so the rule is unit-tested and the fixture only executes
 * it. A part has no commands of its own; it is given the one command that shows it
 * ({@link #commandsForPart}), the same one a starter holding that part runs.
 */
public final class StarterShots {

    /** What kind of thing an entry makes, which decides how it is photographed. */
    public enum Kind {
        /** A creature: four sides, then a clip of it moving. */
        CREATURE,
        /** A building: four sides and inside (the build studio's job, not this fixture's). */
        BUILDING,
        /** An item: in his hand, first person, the view he gets when he uses it. */
        ITEM,
        /** A picture: hung on its board, from where he stands. */
        PICTURE,
        /** A world event: whatever he sees from where he stands, while it happens. */
        EVENT
    }

    /** The subject of an entry: its kind and the id the world knows it by. */
    public record Subject(Kind kind, String id) {
        /** Does it move — does it get a clip as well as stills? */
        public boolean moves() {
            return kind == Kind.CREATURE;
        }
    }

    private static final Pattern WISHMOB = Pattern.compile("^/?wishmob\\s+spawn\\s+([a-z0-9_-]+)");
    private static final Pattern WISHBUILD = Pattern.compile("^/?wishbuild\\s+([a-z0-9_-]+)");
    private static final Pattern WISHPAINT = Pattern.compile("^/?wishpaint\\s+([a-z0-9_-]+)");
    private static final Pattern MODDYEVENT = Pattern.compile("^/?moddyevent\\s+stage\\s+([a-z0-9_]+)");
    private static final Pattern DATAPACK_GIVE =
            Pattern.compile("^/?wishdatapack\\s+([a-z0-9_-]+)\\s+give\\b");
    private static final Pattern SUMMON_TAGGED =
            Pattern.compile("^/?summon\\s+\\S+.*\"wishmob_([a-z0-9_-]+)\"");

    private StarterShots() {
    }

    /**
     * The subject of an entry, from the commands it runs: the FIRST command that makes something.
     * A starter's later commands decorate what the first made (the axolotl's glow), and an item's
     * pack is the item.
     */
    public static Optional<Subject> subjectOf(List<String> commands) {
        for (String raw : commands) {
            String c = raw.trim();
            Matcher m;
            if ((m = WISHMOB.matcher(c)).find()) {
                return Optional.of(new Subject(Kind.CREATURE, m.group(1)));
            }
            if ((m = SUMMON_TAGGED.matcher(c)).find()) {
                return Optional.of(new Subject(Kind.CREATURE, m.group(1)));
            }
            if ((m = WISHBUILD.matcher(c)).find()) {
                return Optional.of(new Subject(Kind.BUILDING, m.group(1)));
            }
            if ((m = WISHPAINT.matcher(c)).find()) {
                return Optional.of(new Subject(Kind.PICTURE, m.group(1)));
            }
            if ((m = MODDYEVENT.matcher(c)).find()) {
                return Optional.of(new Subject(Kind.EVENT, m.group(1)));
            }
            if ((m = DATAPACK_GIVE.matcher(c)).find()) {
                return Optional.of(new Subject(Kind.ITEM, m.group(1)));
            }
        }
        return Optional.empty();
    }

    /**
     * The command that shows a library PART, keyed as an operator's tools key it ({@code body-<id>},
     * {@code building-<id>}, {@code picture-<id>}), or empty for anything else.
     */
    public static Optional<List<String>> commandsForPart(String mediaId) {
        int dash = mediaId.indexOf('-');
        if (dash <= 0) {
            return Optional.empty();
        }
        String id = mediaId.substring(dash + 1);
        return switch (mediaId.substring(0, dash)) {
            case "body" -> Optional.of(List.of("wishmob spawn " + id));
            case "building" -> Optional.of(List.of("wishbuild " + id));
            case "picture" -> Optional.of(List.of("wishpaint " + id));
            default -> Optional.empty();
        };
    }
}
