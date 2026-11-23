package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Which taught wish is the recipe for which of his things — ONE TILE PER THING HE MADE</b>
 * (owner-approved 2026-09-28).
 *
 * <p>A taught wish (a recipe the agent saved with {@code add_template}) and a creation (one of the
 * things his wishes made) used to be two tiles for one dragon: the creation, whose card opens in
 * chat, and the taught button, which replays the recipe. To a child those read as the same thing
 * twice. This joins them, so the panel can show the creation once and hand its card the recipe.
 *
 * <h2>The link is the recipe's OWN COMMANDS, never a name</h2>
 *
 * <p>A taught recipe that replays a saved thing names that thing outright, because that is the only
 * way it can replay it: {@code wishbuild <id>}, {@code wishmob spawn <id>}, {@code wishpaint <id>},
 * and {@code wishdatapack <id> …} for a made item (the one command
 * {@code make_item} tells the agent to teach, and the id is the item's feature id, which his item's
 * catalogue key carries as {@code id:<feature id>}). Those are {@code ReplayContract.ALL}'s replay
 * commands — the explicit link already exists, so nothing new is stored.
 *
 * <p>No fuzzy matching, deliberately. {@code WishSeed.bestMatch} matches a recipe's WORDS against a
 * thing's names, and a wrong answer there is a dragon's tile carrying a chicken's recipe — the
 * child presses "make another one" and gets something else. A recipe whose commands name nothing
 * (plain vanilla commands, a {@code wishsummon} of a modified real mob) joins nothing, and stays
 * its own button: an ORPHAN.
 *
 * <p>Pure — no Minecraft imports — so the whole rule is unit-tested.
 */
public final class TaughtJoin {

    private TaughtJoin() {
    }

    /**
     * One taught recipe, as the panel needs it.
     *
     * @param id       the template id — what {@code /moddy again} and a preset press name
     * @param commands its commands, as saved
     * @param ownerId  the child who taught it, or {@code ""} for an unowned (legacy/shared) recipe
     */
    public record Taught(String id, List<String> commands, String ownerId, List<String> parts) {

        public Taught {
            id = id == null ? "" : id.trim();
            commands = commands == null ? List.of() : List.copyOf(commands);
            ownerId = ownerId == null ? "" : ownerId.trim();
            List<String> ps = new ArrayList<>();
            for (String part : parts == null ? List.<String>of() : parts) {
                String k = key(part);
                if (!k.isEmpty() && !ps.contains(k)) {
                    ps.add(k);
                }
            }
            parts = List.copyOf(ps);
        }

        /** A recipe with no parts recorded at its grant (taught, or by hand). */
        public Taught(String id, List<String> commands, String ownerId) {
            this(id, commands, ownerId, List.of());
        }

        /** An unowned recipe — shared by every child, as before shelves. */
        public Taught(String id, List<String> commands) {
            this(id, commands, "");
        }
    }

    /**
     * <b>Only the recipes this child may use</b> (2026-09-28: per-player matching).
     *
     * <p>A thing with no owner sits on every child's panel, so without this
     * a brother's recipe that replays it would become THIS child's [Make another one] and hide as
     * his button. The rule is {@link ArtifactOwner#visibleTo}, the one the preset row and the
     * creation list already use: his own, or nobody's — never another child's.
     */
    public static List<Taught> visibleTo(String viewer, List<Taught> taught) {
        List<Taught> out = new ArrayList<>();
        if (taught == null) {
            return out;
        }
        for (Taught t : taught) {
            if (t != null && ArtifactOwner.visibleTo(viewer, t.ownerId())) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Every saved thing these commands replay, lowercased, in order.
     *
     * <p>A replay command counts at the START of a command, or straight after {@code run} (an
     * {@code execute … run wishbuild castle} still replays the castle). Anywhere else — the word
     * inside a {@code say} — it is text, not a command, and names nothing.
     */
    public static List<String> artifactsNamed(List<String> commands) {
        Set<String> out = new LinkedHashSet<>();
        if (commands == null) {
            return List.of();
        }
        for (String command : commands) {
            if (command == null) {
                continue;
            }
            String[] t = command.trim().split("\\s+");
            for (int i = 0; i < t.length; i++) {
                boolean atCommand = i == 0 || "run".equalsIgnoreCase(t[i - 1]);
                if (!atCommand) {
                    continue;
                }
                String root = t[i].startsWith("/") ? t[i].substring(1) : t[i];
                String id = switch (root.toLowerCase(Locale.ROOT)) {
                    // wishpaint: a picture is a part a wish can make, and its recipe line
                    // was the one replay command this join could not read.
                    // wishtrain / wishmachine (gone with Create) name nothing any more: an
                    // old recipe that says one joins to no tile rather than to a missing thing.
                    case "wishbuild", "wishdatapack", "wishpaint" ->
                            i + 1 < t.length ? t[i + 1] : "";
                    case "wishmob" -> i + 2 < t.length && "spawn".equalsIgnoreCase(t[i + 1])
                            ? t[i + 2] : "";
                    default -> "";
                };
                if (!id.isBlank()) {
                    out.add(id.toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(out);
    }

    /** Whether this recipe replays the thing with this creation id. */
    public static boolean names(Taught taught, String creationId) {
        if (taught == null || creationId == null || creationId.isBlank()) {
            return false;
        }
        // An item's catalogue key is "id:<feature id>"; its replay names the bare feature id.
        return partsOf(taught).contains(key(creationId));
    }

    // ──: A WISH AND ITS PARTS ───────────────────────────────────────────────────────────

    /** What separates a wish's recipe id from one of its parts in a PART recipe id. */
    public static final char PART = '@';

    /** A part key: lower-cased, trimmed, and an item's {@code id:} prefix dropped. */
    public static String key(String id) {
        String k = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return k.startsWith("id:") ? k.substring(3) : k;
    }

    /**
     * <b>The things this wish made</b> — recorded at the grant when it was, else what its
     * replay commands name (a recipe taught; no backfill).
     */
    public static List<String> partsOf(Taught taught) {
        if (taught == null) {
            return List.of();
        }
        return !taught.parts().isEmpty() ? taught.parts() : artifactsNamed(taught.commands());
    }

    /**
     * <b>Two levels, a wish and its parts</b> (2026-10-04). A recipe that made two or more
     * things is a WISH: its own button re-wishes all of them, and each of its things' tiles
     * re-wishes only that thing. A recipe that made one thing IS that thing's recipe, and lives on
     * its tile (an earlier issue's one tile per thing).
     */
    public static boolean isWish(Taught taught) {
        return partsOf(taught).size() >= 2;
    }

    /** The recipe id that re-wishes ONLY {@code part} of the wish {@code wishId}. */
    public static String partRecipe(String wishId, String part) {
        return (wishId == null ? "" : wishId.trim()) + PART + key(part);
    }

    /** {@code [wish id, part]} of a part recipe id, or null when it is a plain recipe id. */
    public static String[] splitPart(String recipeId) {
        if (recipeId == null) {
            return null;
        }
        int at = recipeId.indexOf(PART);
        if (at <= 0 || at + 1 >= recipeId.length()) {
            return null;
        }
        return new String[] {recipeId.substring(0, at), recipeId.substring(at + 1)};
    }

    /**
     * The commands of a wish that make ONLY {@code part}: each one that names it. A command that
     * names nothing (a raw {@code give}, a {@code summon}) belongs to the whole wish and never to
     * a part — which is why a machine's supplies must ride it as its KIT rather than as a give
     * beside it.
     */
    public static List<String> commandsFor(List<String> commands, String part) {
        String want = key(part);
        List<String> out = new ArrayList<>();
        if (commands == null || want.isEmpty()) {
            return out;
        }
        for (String c : commands) {
            if (c != null && artifactsNamed(List.of(c)).contains(want)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * The wishes this thing is a PART of, newest first — a part may belong to more than one (the
     * garage's T-Rex is the garage's and the dinosaur park's). Only wishes of two or more parts.
     */
    public static List<Taught> wishesContaining(String creationId, List<Taught> taughtNewestFirst) {
        List<Taught> out = new ArrayList<>();
        if (taughtNewestFirst == null) {
            return out;
        }
        for (Taught t : taughtNewestFirst) {
            if (t != null && !t.id().isEmpty() && isWish(t) && names(t, creationId)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The recipe for one of his things, or {@code ""} when no taught recipe names it.
     *
     * <p>The FIRST that names it, and the caller hands them newest first, so a thing re-taught
     * later is remade from its newest recipe.
     *
     * @param taughtNewestFirst the taught recipes, newest first
     */
    public static String recipeFor(String creationId, List<Taught> taughtNewestFirst) {
        if (taughtNewestFirst == null) {
            return "";
        }
        for (Taught t : taughtNewestFirst) {
            if (t != null && !t.id().isEmpty() && names(t, creationId)) {
                if (!isWish(t)) {
                    return t.id();
                }
                // a PART's tile re-wishes only the part — never the whole wish, which is
                // what pressing the bar's "Make another one" used to do (it rebuilt the oasis).
                // A part no command can remake on its own gets no button at all.
                return commandsFor(t.commands(), creationId).isEmpty() ? ""
                        : partRecipe(t.id(), creationId);
            }
        }
        return "";
    }

    /**
     * The taught recipes that have one of these creations to live on — the ones the panel does
     * NOT show as a separate button, because the creation's tile carries them.
     *
     * <p>Every recipe that names an on-panel thing, not only the newest: an older recipe for the
     * same dragon is a second button for the same dragon, which is the duplicate this removes.
     */
    public static Set<String> joined(Collection<String> creationIds, List<Taught> taught) {
        Set<String> out = new LinkedHashSet<>();
        if (creationIds == null || taught == null) {
            return out;
        }
        List<String> ids = new ArrayList<>();
        for (String c : creationIds) {
            if (c != null && !c.isBlank()) {
                ids.add(c);
            }
        }
        for (Taught t : taught) {
            // a WISH of several parts keeps its own button — the entry that re-wishes all
            // of it. Only a one-thing recipe is folded into its thing's tile.
            if (t == null || t.id().isEmpty() || isWish(t)) {
                continue;
            }
            for (String c : ids) {
                if (names(t, c)) {
                    out.add(t.id());
                    break;
                }
            }
        }
        return out;
    }
}
