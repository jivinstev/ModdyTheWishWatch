package com.moddingfromamod.mob;

import java.util.Locale;
import java.util.Set;

/**
 * <b>How many creatures one wish may make, and one world may hold, by class</b> — the
 * one place a creature count is decided. Pure: no Minecraft import, so every number and every
 * sentence is unit-tested without booting the game.
 *
 * <p>Before this, the "20" a child met on a huge creeper wish (wish 1beb4949) was the MODEL's guess:
 * it typed twenty {@code summon} lines and told him the game would crash. No code capped it, the
 * caps that did exist disagreed (1–20, 1–10, 32 commands a call) and none had a measurement behind
 * it. Every spawn path now reads its number here, and the world guard
 * ({@code ops.WishMobLimit}) enforces the same numbers wherever a wished mob actually joins a level
 * — a {@code summon}, an {@code execute} that fans out, a button replay, a datapack, an event.
 *
 * <p><b>Two numbers per class, answering two different questions.</b> {@link #perWish} is a WAVE:
 * how many one wish brings at once; a bigger ask gets the first wave and is told to wish "more".
 * {@link #worldLive} is what one level may hold of wished creatures of that class at once, whoever
 * wished them — two children share a server, and a herd left behind by earlier waves costs every
 * tick whether anybody is looking or not.
 *
 * <p>The numbers are MEASURED on a real dedicated server ({@code ops.SpawnPerfProbe},
 * {@code vanilla.RigPerfProbe}); {@code docs/evidence-1265/README.md} has the table and the machine.
 * They are proposals until a maintainer approves them (D4).
 */
public final class SpawnBudget {

    /** The five classes the issue names; a different reason to stop at a different number each. */
    public enum Kind {
        /** An ordinary mob: AI and pathfinding every tick. */
        VANILLA,
        /** It blows up: the cost is one tick, all at once, and the blocks it breaks. */
        EXPLOSIVE,
        /** It flies: it leaves the pen, spreads over chunks and loads them. */
        FLYING,
        /** Wither, warden, dragon: tears up the world and is close to unkillable. */
        BOSS,
        /** A sculpted creature: priced by its DISPLAYS, which is what the server, the network and
         *  every watching client pay for ({@code docs/ATOM_RIG_COST.md}). */
        SCULPTED
    }

    private static final Set<String> BOSSES = Set.of(
            "wither", "ender_dragon", "warden", "elder_guardian");
    private static final Set<String> EXPLOSIVES = Set.of(
            "creeper", "tnt_minecart", "end_crystal");
    private static final Set<String> FLYERS = Set.of(
            "phantom", "ghast", "vex", "blaze", "bee", "bat", "parrot", "allay");

    // ── the DEFAULTS (measured — docs/evidence-1265/README.md); each world may set its own, below ──

    /** One wish's wave of ordinary mobs. */
    public static final int VANILLA_PER_WISH = 100;
    /** One wish's wave of things that explode. */
    public static final int EXPLOSIVE_PER_WISH = 50;
    /** One wish's wave of flyers. */
    public static final int FLYING_PER_WISH = 25;
    /** One wish's bosses. */
    public static final int BOSS_PER_WISH = 1;
    /** One wish's sculpted creatures, in DISPLAYS: ~16 Spikors (62 each), or {@link #SCULPTED_MAX_COUNT} small ones. */
    public static final int SCULPTED_DISPLAYS_PER_WISH = 1000;
    /** Never more than this many sculpted creatures in one spawn, however small each one is. */
    public static final int SCULPTED_MAX_COUNT = 50;

    /** What one level may hold of wished ordinary mobs at once. */
    public static final int VANILLA_WORLD = 400;
    /** ...of wished things that explode. */
    public static final int EXPLOSIVE_WORLD = 100;
    /** ...of wished flyers. */
    public static final int FLYING_WORLD = 100;
    /** ...of wished bosses. */
    public static final int BOSS_WORLD = 3;
    /** ...of sculpted creatures' displays: ~67 average creatures, ~13 ms a tick and ~27k packets/s per watching player measured. */
    public static final int SCULPTED_DISPLAYS_WORLD = 2000;

    private SpawnBudget() {
    }

    /**
     * Which class an entity type is, or {@code null} when it is not a creature this budget counts
     * (an item, an arrow, an armour stand, a display, lit TNT — which has its own budget,
     * {@code ops.TntBudget}). {@code isMob} is whether the game says it is a {@code Mob}; a
     * namespace other than {@code minecraft} is a modded mob and counts as an ordinary one.
     */
    public static Kind classify(String entityId, boolean isMob) {
        if (entityId == null || entityId.isBlank()) {
            return null;
        }
        String id = entityId.toLowerCase(Locale.ROOT).trim();
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length())
                : id.contains(":") ? null : id;
        if (path != null) {
            if (BOSSES.contains(path)) {
                return Kind.BOSS;
            }
            if (EXPLOSIVES.contains(path)) {
                return Kind.EXPLOSIVE;
            }
            if (FLYERS.contains(path)) {
                return Kind.FLYING;
            }
        }
        return isMob ? Kind.VANILLA : null;
    }

    /** The vanilla ids named in a class (bosses, things that explode, flyers); every other mob is ordinary. */
    public static Set<String> members(Kind k) {
        return switch (k) {
            case BOSS -> BOSSES;
            case EXPLOSIVE -> EXPLOSIVES;
            case FLYING -> FLYERS;
            default -> Set.of();
        };
    }

    // ── per-WORLD knobs: the numbers above are the DEFAULTS ─────────────────
    //
    // Each class's two numbers are live knobs, so each world sets its own:
    // `spawn.<class>.perWish` and `spawn.<class>.world`. Precedence is every knob's —
    // the knob, else the boot property -Dmoddingfromamod.spawn.<class>.<which> (set
    // at server launch), else the default above. Each world is its own JVM
    // with its own knobs, so a number reaches only its own world.

    /** The knob key for one of a class's two numbers: {@code spawn.explosive.perWish}. */
    public static String knobKey(Kind k, boolean world) {
        return "spawn." + k.name().toLowerCase(Locale.ROOT) + (world ? ".world" : ".perWish");
    }

    /** The default (the measured, proposed number) for one of a class's two numbers. */
    public static int defaultFor(Kind k, boolean world) {
        return switch (k) {
            case VANILLA -> world ? VANILLA_WORLD : VANILLA_PER_WISH;
            case EXPLOSIVE -> world ? EXPLOSIVE_WORLD : EXPLOSIVE_PER_WISH;
            case FLYING -> world ? FLYING_WORLD : FLYING_PER_WISH;
            case BOSS -> world ? BOSS_WORLD : BOSS_PER_WISH;
            case SCULPTED -> world ? SCULPTED_DISPLAYS_WORLD : SCULPTED_DISPLAYS_PER_WISH;
        };
    }

    /** The smallest value a world may set — one, or a hundred displays (less than one small creature is no wave). */
    public static int minFor(Kind k, boolean world) {
        return k == Kind.SCULPTED ? 100 : 1;
    }

    /**
     * The largest value a world may set. Generous, ~10x the default: the knob is for a world that wants
     * more, and these bounds only stop a typo from becoming a number nobody measured by orders of magnitude.
     */
    public static int maxFor(Kind k, boolean world) {
        return switch (k) {
            case VANILLA -> world ? 4000 : 1000;
            case EXPLOSIVE, FLYING -> world ? 2000 : 500;
            case BOSS -> world ? 50 : 20;
            case SCULPTED -> world ? 20000 : 10000;
        };
    }

    /** How many of this class one wish brings at once — in DISPLAYS for {@link Kind#SCULPTED}. This world's number. */
    public static int perWish(Kind k) {
        return configured(k, false);
    }

    /** How many wished ones of this class a level may hold at once — DISPLAYS for sculpted. This world's number. */
    public static int worldLive(Kind k) {
        return configured(k, true);
    }

    private static int configured(Kind k, boolean world) {
        String key = knobKey(k, world);
        int boot = boot(System.getProperty("moddingfromamod." + key), k, world);
        int n = com.moddingfromamod.config.ModdySettings.intOr(key, boot);
        return n >= minFor(k, world) && n <= maxFor(k, world) ? n : defaultFor(k, world);
    }

    /**
     * The boot default from the property's raw text, or the default. A value that is not a whole number
     * inside the bounds is ignored rather than trusted — a typo in a manifest must not become "no
     * creatures ever" or "a million creepers". Pure.
     */
    static int boot(String raw, Kind k, boolean world) {
        if (raw == null || raw.isBlank()) {
            return defaultFor(k, world);
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return n >= minFor(k, world) && n <= maxFor(k, world) ? n : defaultFor(k, world);
        } catch (NumberFormatException e) {
            return defaultFor(k, world);
        }
    }

    /** Why a join was turned away: this wish's wave is full, or the world is. */
    public enum Stop {
        NONE, WAVE, WORLD
    }

    /**
     * May one more of {@code k} join, when this wish has already made {@code byThisWish} and the
     * level holds {@code liveInWorld} wished ones of the class? A spawn with no wish behind it (a
     * datapack running on its own) passes {@code byThisWish = -1} and meets the world ceiling only.
     */
    public static Stop admit(Kind k, int byThisWish, int liveInWorld) {
        if (liveInWorld >= worldLive(k)) {
            return Stop.WORLD;
        }
        if (byThisWish >= 0 && byThisWish >= perWish(k)) {
            return Stop.WAVE;
        }
        return Stop.NONE;
    }

    /**
     * How many sculpted creatures of {@code displaysEach} displays a spawn of {@code asked} may
     * really make, given the displays the level already holds. At least one whenever one fits in
     * the world — a single creature is never refused for being big — and at most
     * {@link #SCULPTED_MAX_COUNT}.
     */
    public static int sculptedCount(int displaysEach, int asked, int displaysInWorld) {
        int each = Math.max(1, displaysEach);
        int want = Math.max(1, Math.min(asked, SCULPTED_MAX_COUNT));
        int worldCap = worldLive(Kind.SCULPTED);
        int worldRoom = Math.max(0, worldCap - displaysInWorld) / each;
        if (worldRoom == 0) {
            return displaysInWorld < worldCap ? 1 : 0;
        }
        int waveRoom = Math.max(1, perWish(Kind.SCULPTED) / each);
        return Math.min(want, Math.min(waveRoom, worldRoom));
    }

    // ── what the child is told ─────────────────────────────────────────────────────────────────

    /**
     * The words for a wave that stopped short — true, and magical, never "the game would crash":
     * what really happened is that the wand gives this many at a time, and wishing "more" brings
     * the next lot. {@code typeId} names what was asked for; {@code made} is how many came.
     */
    public static String waveLine(Kind k, String typeId, int made) {
        String what = plural(typeId);
        int cap = k == Kind.SCULPTED ? made : perWish(k);
        if (k == Kind.BOSS) {
            return "My wand can only hold " + cap + " " + (cap == 1 ? noun(typeId) : what)
                    + " at a time — " + (cap == 1 ? "it's" : "they're") + " far too mighty for more! "
                    + "Wish 'more' once " + (cap == 1 ? "it's" : "they're") + " gone and another can come.";
        }
        return "My wand holds " + cap + " " + what + " at a time — here come the first " + made
                + "! Wish 'more' and the next wave rolls in!";
    }

    /** The words for a world that is already full of wished creatures of this class. */
    public static String worldLine(Kind k, String typeId) {
        return "Your world is already bursting with wished " + (k == Kind.SCULPTED ? "creatures"
                : plural(typeId)) + " — that's all its magic can keep lively for everyone at once. "
                + "When some are gone, wish again and more will come!";
    }

    /**
     * What a tool result says to the agent beside the child's words, so it repeats the true reason
     * instead of inventing one.
     */
    public static String forTheAgent(String childLine) {
        return "SPAWN BUDGET (#1265): the rest were not made, on purpose — this is the per-class "
                + "limit, not an error, and nothing crashed. Tell the child exactly: \"" + childLine
                + "\" — never say the game would crash or explode.";
    }

    /** "creeper" → "creepers", "minecraft:sheep" → "sheep", "wolf" → "wolves". */
    static String plural(String typeId) {
        String n = noun(typeId);
        if (n.endsWith("sheep") || n.endsWith("fish") || n.endsWith("s")) {
            return n;
        }
        if (n.endsWith("wolf")) {
            return n.substring(0, n.length() - 1) + "ves";
        }
        if (n.endsWith("y") && !n.endsWith("ay") && !n.endsWith("ey") && !n.endsWith("oy")) {
            return n.substring(0, n.length() - 1) + "ies";
        }
        if (n.endsWith("ch") || n.endsWith("sh") || n.endsWith("x")) {
            return n + "es";
        }
        return n + "s";
    }

    /** "minecraft:iron_golem" → "iron golem". */
    static String noun(String typeId) {
        String id = typeId == null ? "creature" : typeId.toLowerCase(Locale.ROOT).trim();
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.isBlank() ? "creature" : path.replace('_', ' ');
    }
}
