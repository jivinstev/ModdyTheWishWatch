package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * <b>A wish's PARTS are the distinct NEW things it made</b> — not every entry of
 * {@link WishRun#made()}.
 *
 * <p> stored every {@code made()} entry as a part, and was checked on fixtures only. Replayed
 * over the 47 real {@code made} records from a live server it mislabelled about half the multi-thing
 * wishes, in four shapes, each of which this drops:
 * <ul>
 *   <li><b>a mob handle</b> ({@code mob @pegasus} beside {@code creature pegasus}). A
 *       {@code mob} entry is a CHANGE to an animal standing in the world ({@link MadeKind#MOB}'s
 *       own doc): either the spawned instance of a species this wish made — that creature again —
 *       or an animal that was there before the wish — an edit. Neither is a new thing, and no
 *       command remakes it, so a "part" made of one would have no button;</li>
 *   <li><b>an ability or effect pack that belongs to a thing</b> ({@code datapack spikor_fire}
 *       beside {@code creature spikor}; {@code datapack mighty_laser_bow_hurt}, an item's own
 *       pack). Folded into that thing: a datapack whose id IS a kept creature's or item's id, or
 *       starts with it and {@code _};</li>
 *   <li><b>superseded versions</b> ({@code cake_fork_hurt}, {@code cake_fork_plain},
 *       {@code cake_fork_heal}): what the wish took away again ({@code remove_*} after its last
 *       make), and — when the caller can name them — items of the same NAME, keeping the newest;</li>
 *   <li><b>edits</b>: a thing that existed before this wish (the garage the next wish parked cars
 *       in) is not a part of the editing wish.</li>
 * </ul>
 *
 * <p>Pure — no Minecraft imports — so the rule runs against the real records in Gate A
 * ({@code WishPartsRealDataTest}).
 */
public final class WishParts {

    private WishParts() {
    }

    /**
     * The parts of a wish, OLDEST FIRST.
     *
     * @param madeNewestFirst what the run recorded ({@link WishRun#made()} is newest first)
     * @param removed         what the wish took away again after it last made it
     * @param existedBefore   what was already there before this wish (an edit)
     * @param itemName        an item's design name, or {@code ""}/null when unknown; may be null
     */
    public static List<WishRun.Made> of(List<WishRun.Made> madeNewestFirst, Set<WishRun.Made> removed,
            Set<WishRun.Made> existedBefore, Function<String, String> itemName) {
        List<WishRun.Made> oldestFirst = new ArrayList<>(madeNewestFirst == null ? List.of() : madeNewestFirst);
        java.util.Collections.reverse(oldestFirst);
        List<WishRun.Made> kept = new ArrayList<>();
        for (WishRun.Made m : oldestFirst) {
            if (m == null || m.id() == null || m.id().isBlank() || MadeKind.MOB.equals(m.kind())) {
                continue;
            }
            if (contains(removed, m) || contains(existedBefore, m)) {
                continue;
            }
            kept.add(m);
        }
        // Superseded by NAME: the same item remade under another id inside one wish keeps the last.
        if (itemName != null) {
            Map<String, WishRun.Made> newestByName = new LinkedHashMap<>();
            for (WishRun.Made m : kept) {
                if (MadeKind.ITEM.equals(m.kind())) {
                    String name = norm(itemName.apply(m.id()));
                    if (!name.isEmpty()) {
                        newestByName.put(name, m);
                    }
                }
            }
            kept.removeIf(m -> {
                if (!MadeKind.ITEM.equals(m.kind())) {
                    return false;
                }
                String name = norm(itemName.apply(m.id()));
                return !name.isEmpty() && newestByName.get(name) != m;
            });
        }
        // An ability or effect pack belongs to the creature or item it is named for.
        List<String> owners = new ArrayList<>();
        for (WishRun.Made m : kept) {
            if (MadeKind.CREATURE.equals(m.kind()) || MadeKind.ITEM.equals(m.kind())) {
                owners.add(TaughtJoin.key(m.id()));
            }
        }
        List<WishRun.Made> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (WishRun.Made m : kept) {
            if (MadeKind.DATAPACK.equals(m.kind()) && belongsTo(TaughtJoin.key(m.id()), owners)) {
                continue;
            }
            String k = TaughtJoin.key(m.id());
            if (!seen.contains(k)) {
                seen.add(k);
                out.add(m);
            }
        }
        return out;
    }

    /** The command a learned recipe uses to remake this part ALONE, or {@code ""} when none can. */
    public static String replayCommand(WishRun.Made part) {
        if (part == null || part.id() == null || part.id().isBlank()) {
            return "";
        }
        String id = part.id().trim();
        return switch (part.kind() == null ? "" : part.kind()) {
            case MadeKind.BUILD -> "wishbuild " + id;
            case MadeKind.CREATURE -> "wishmob spawn " + id;
            case MadeKind.ITEM -> "wishdatapack " + id + " give";
            case MadeKind.PAINTING -> "wishpaint " + id;
            // A datapack may be a RULE, which has no entry to run (ReplayContract), and an event or
            // a mob change has no replay command at all — so none is asked for.
            default -> "";
        };
    }

    /**
     * The parts these recipe commands cannot remake although a replay command exists for them —
     * each one a part with no button (a finding, never a silent absence).
     */
    public static List<WishRun.Made> unremakable(List<WishRun.Made> parts, List<String> commands) {
        List<String> named = TaughtJoin.artifactsNamed(commands);
        List<WishRun.Made> out = new ArrayList<>();
        for (WishRun.Made p : parts == null ? List.<WishRun.Made>of() : parts) {
            if (!replayCommand(p).isEmpty() && !named.contains(TaughtJoin.key(p.id()))) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * <b>Teach the whole wish, and every part of it</b>: the one-time refusal for a granted
     * wish of two or more parts that taught nothing, or taught a recipe that leaves a part out.
     * Null when there is nothing to ask.
     *
     * @param taughtCommands the commands of every recipe this wish taught, together
     */
    public static String teachRefusal(List<WishRun.Made> parts, boolean taughtAny, List<String> taughtCommands) {
        if (parts == null) {
            return null;
        }
        List<WishRun.Made> remakable = new ArrayList<>();
        for (WishRun.Made p : parts) {
            if (!replayCommand(p).isEmpty()) {
                remakable.add(p);
            }
        }
        if (!taughtAny) {
            if (parts.size() < 2 || remakable.isEmpty()) {
                return null;
            }
            StringBuilder cmds = new StringBuilder();
            for (WishRun.Made p : remakable) {
                cmds.append(cmds.length() == 0 ? "" : ", ").append('"').append(replayCommand(p)).append('"');
            }
            return "Not yet — this wish made " + parts.size() + " things (" + describe(parts) + ") and "
                    + "you have not taught it, so he cannot wish for it again: no button for the whole "
                    + "wish and none for any of its parts. Call add_template ONCE for the whole wish "
                    + "with commands [" + cmds + "] (one line per thing — each part then gets its own "
                    + "button too), the words he'd say, an icon and a `say` line; then finish_wish "
                    + "again. If it really cannot be made again, just call finish_wish again — this "
                    + "asks only once.";
        }
        List<WishRun.Made> missing = unremakable(parts, taughtCommands);
        if (missing.isEmpty() || parts.size() < 2) {
            return null;
        }
        StringBuilder add = new StringBuilder();
        for (WishRun.Made p : missing) {
            add.append(add.length() == 0 ? "" : ", ").append('"').append(replayCommand(p)).append('"');
        }
        return "Not yet — the recipe you taught does not remake every part of this wish: "
                + describe(missing) + " " + (missing.size() == 1 ? "has" : "have") + " no command in "
                + "it, so re-wishing it comes back without " + (missing.size() == 1 ? "that" : "those")
                + ", and " + (missing.size() == 1 ? "that part gets" : "those parts get") + " no button. "
                + "Call add_template again with the SAME id and these lines added: [" + add + "]; then "
                + "finish_wish again. (This asks only once.)";
    }

    private static String describe(List<WishRun.Made> parts) {
        StringBuilder sb = new StringBuilder();
        for (WishRun.Made p : parts) {
            sb.append(sb.length() == 0 ? "" : ", ").append(p.kind()).append(' ').append(p.id());
        }
        return sb.toString();
    }

    private static boolean belongsTo(String pack, List<String> owners) {
        for (String o : owners) {
            if (!o.isEmpty() && (pack.equals(o) || pack.startsWith(o + "_"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(Set<WishRun.Made> set, WishRun.Made m) {
        if (set == null || set.isEmpty()) {
            return false;
        }
        for (WishRun.Made s : set) {
            if (s != null && java.util.Objects.equals(s.kind(), m.kind())
                    && TaughtJoin.key(s.id()).equals(TaughtJoin.key(m.id()))) {
                return true;
            }
        }
        return false;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
