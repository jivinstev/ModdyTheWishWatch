package com.moddingfromamod.lite;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>Which mode minted the things in this world — the record, not the migration.</b>
 *
 * <p>Five wish classes now ship <i>vanilla-by-default</i>: a wished item is a tinted vanilla
 * carrier rather than {@code wish_item}, a creature is a display rig on a vanilla base rather than
 * a {@code WishMobEntity}, a picture is maps in item frames rather than our own panel, and the
 * watch itself is a carrier too. Every one of those is a different thing ON DISK from what the mod
 * path writes. So a world minted on the substituted path and later opened without it — a flag
 * flipped, a build rolled back, a jar swapped — degrades <b>silently</b>: the items are still
 * there, they are just not the things they were.
 *
 * <p>This is the mark that stops that being silent. It records what a world IS; deciding what to do
 * about it is a migration and is deliberately not here — recording precedes deciding, and
 * doing both at once makes the mark's own correctness hard to review.
 *
 * <p>Minecraft-free, because everything interesting about it is a small state machine over strings:
 * what an absent record means, what happens when a world has been minted BOTH ways, and what a
 * damaged file may do to a world load. {@link MintStore} is the file half.
 *
 * <h2>🔴 Which classes are marked, and why the other three are exempt</h2>
 *
 * <p>The mark covers the <b>substituted</b> classes only — the ones whose artefact on disk differs
 * between the two paths. Three of the seven {@code ProvenanceContract.PUBLISHING_KINDS} are exempt,
 * and the reasons differ, so they are written down here rather than rediscovered:
 *
 * <ul>
 *   <li><b>{@code build}</b> — a wished castle is stone bricks either way. Nothing about a building
 *       is path-specific on disk, so there is nothing a mark could warn about.</li>
 *   <li><b>{@code contraption} and {@code train}</b> — the same argument one mod along: they are
 *       Create's own blocks, placed identically on both paths.</li>
 *   <li><b>{@code datapack}</b> — a compiled datapack is byte-identical on both paths. The mark it
 *       would need is not on the pack at all: it is on whatever the pack's <b>selectors</b> point
 *       at, which is a creature, and creatures are marked. Marking the pack would put the flag on
 *       the wrong object and read as covered.</li>
 * </ul>
 */
public final class MintMarks {

    /**
     * Stamped on write and <b>tolerated on read whatever it says</b>.
     *
     * <p>The same reasoning {@code MobHandleStore.VERSION} carries: refusing to read a future
     * version would turn "you opened your world in an older build" into "this world has no mark",
     * which is precisely the silence the mark exists to remove.
     */
    public static final int VERSION = 1;

    /** The substituted classes — see the class note for why these and not the other three. */
    public static final List<String> MARKED = List.of("item", "species", "painting", "watch");

    /** What a world's things of one class were made by. */
    public enum Mode {
        /** Our own item/entity/panel — the path a modded client has always had. */
        MOD,
        /** A vanilla carrier, a display rig, maps in frames — what a stock client can see. */
        VANILLA,
        /**
         * <b>Both, and that is a real state rather than a corrupt one.</b> A child's world outlives
         * a flag: he plays a session on one path and a session on the other, and the world then
         * holds some of each. A migration has to know that, and a mark that overwrote would say the
         * world was purely whatever it last saw.
         */
        MIXED,
        /**
         * <b>Nothing was ever recorded — which is NOT the same as "the mod path".</b> Every world
         * that predates this mark is UNKNOWN, and that is most of them. Reporting MOD for those
         * would be a guess dressed as a fact, and it would be wrong for exactly the worlds the mark
         * was built for: the ones already minted on the substituted path.
         */
        UNKNOWN
    }

    private final Map<String, Mode> marks = new LinkedHashMap<>();

    /** What this class's things were made by. Never null — an unrecorded class is UNKNOWN. */
    public synchronized Mode modeOf(String kind) {
        Mode m = marks.get(key(kind));
        return m == null ? Mode.UNKNOWN : m;
    }

    /** Everything recorded so far, for a report or a gate. */
    public synchronized Map<String, Mode> all() {
        return new LinkedHashMap<>(marks);
    }

    /**
     * Record that something of this class was just minted on one path or the other.
     *
     * @return true when the record CHANGED, so the caller knows whether to write the file. Minting
     *         is frequent and the mark almost never moves, so a store that saved unconditionally
     *         would rewrite the file on every wish for no reason.
     */
    public synchronized boolean record(String kind, boolean vanilla) {
        String k = key(kind);
        if (k.isEmpty()) {
            return false;
        }
        Mode now = vanilla ? Mode.VANILLA : Mode.MOD;
        Mode had = marks.get(k);
        Mode next = had == null || had == now ? now : Mode.MIXED;
        if (had == Mode.MIXED) {
            next = Mode.MIXED;   // once mixed, always mixed: the old things are still in the world
        }
        if (next == had) {
            return false;
        }
        marks.put(k, next);
        return true;
    }

    /** Is any marked class known to have been minted the vanilla way? */
    public synchronized boolean anyVanilla() {
        for (Mode m : marks.values()) {
            if (m == Mode.VANILLA || m == Mode.MIXED) {
                return true;
            }
        }
        return false;
    }

    public synchronized JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject by = new JsonObject();
        for (Map.Entry<String, Mode> e : marks.entrySet()) {
            by.addProperty(e.getKey(), e.getValue().name());
        }
        root.add("mintedBy", by);
        return root;
    }

    /**
     * Read a saved mark, skipping anything it cannot understand.
     *
     * <p><b>Defensive by hand, and deliberately not a generated codec.</b> The migration
     * catalogue's own rule: a generated codec fails the WHOLE decode on one bad field, which is the
     * opposite of what a defensive store is for. One unreadable class here must cost that class's
     * mark and nothing else — and above all it must never abort a world load, because a mark that
     * can break a child's world is worse than no mark.
     *
     * @return how many class marks were restored
     */
    public synchronized int load(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return 0;
        }
        JsonElement by = root.getAsJsonObject().get("mintedBy");
        if (by == null || !by.isJsonObject()) {
            return 0;
        }
        int restored = 0;
        for (Map.Entry<String, JsonElement> e : by.getAsJsonObject().entrySet()) {
            String k = key(e.getKey());
            if (k.isEmpty() || !e.getValue().isJsonPrimitive()) {
                continue;
            }
            Mode m = modeNamed(e.getValue().getAsString());
            if (m == null || m == Mode.UNKNOWN) {
                // UNKNOWN is the ABSENCE of a record, so storing it would be a record saying there
                // is none — which reads back as a fact and is not one.
                continue;
            }
            marks.put(k, m);
            restored++;
        }
        return restored;
    }

    /** A line for a log or a report — the whole point is that a degraded world says so out loud. */
    public synchronized String describe() {
        if (marks.isEmpty()) {
            return "this world carries no mint mark — it predates the mark, so which path made its "
                    + "things is UNKNOWN";
        }
        StringBuilder sb = new StringBuilder("minted by:");
        for (Map.Entry<String, Mode> e : marks.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        }
        for (String kind : MARKED) {
            if (!marks.containsKey(kind)) {
                sb.append(' ').append(kind).append('=').append(Mode.UNKNOWN);
            }
        }
        return sb.toString();
    }

    private static Mode modeNamed(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;   // a mode a later build invented: skip that class, keep the rest
        }
    }

    private static String key(String kind) {
        return kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
    }
}
