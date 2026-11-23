package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>The carrier "skeletons" a creature may be hung on, classified by how they MOVE.</b>
 *
 * <p>The carrier is a real vanilla mob running its own vanilla AI — nothing in this mod adds a
 * goal to it — so choosing one chooses how the creature moves, what it breathes and what a child's
 * right click does to it, as well as its hitbox. So the pool is chosen by movement class FIRST and
 * by shape second, and every row says what the mob does besides carry.
 *
 * <p>Pure: ids and notes only. The DIMENSIONS here are what the Gate A instrument measures with;
 * spawn reads the live registry, and {@code CarrierPoolGameTest} fails if a row here disagrees
 * with it — the rule {@link RigBase}'s javadoc draws from the time a hand table was wrong.
 *
 * <h2>Left out on purpose — each would take the creature away from the child</h2>
 *
 * <ul>
 *   <li><b>bucketable fish and the axolotl</b> — a water bucket scoops the CARRIER up; the rig is
 *       left as an orphan statue.</li>
 *   <li><b>mooshroom</b> — shears convert it to a cow by DISCARDING the entity (it was in the
 *       old pool; it tied with cow and never won, and now cannot).</li>
 *   <li><b>bee</b> — enters hives (removed from the world) and dies after stinging (was in the old
 *       pool).</li>
 *   <li><b>parrot</b> — sits on a shoulder, which removes it from the world.</li>
 *   <li><b>allay</b> — takes the item out of the child's hand on a right click.</li>
 *   <li><b>sniffer, chicken, sheep, fox, cat, rabbit, snow golem</b> — drop items, eat grass or
 *       crops, pick items up, or leave snow: each edits a child's world. (Sniffer was in the old
 *       pool; the polar bear has nearly its ratio, 1.00 against 1.09.)</li>
 *   <li><b>glow squid</b> — glows: a second, unrequested glow.</li>
 *   <li><b>strider, villager, trader</b> — hurt by water / a trade screen / despawns.</li>
 * </ul>
 */
public final class CarrierPool {

    private CarrierPool() {
    }

    /** How a creature gets about. */
    public enum Move {
        WALK, SWIM, FLY
    }

    /**
     * One carrier.
     *
     * @param width      natural width at scale 1 (checked against the live registry by a GameTest)
     * @param height     natural height at scale 1
     * @param moves      the class this carrier serves
     * @param landSafe   survives indefinitely out of water
     * @param waterSafe  survives indefinitely under water (no drowning)
     * @param hostile    a {@code Monster}: listed so a ranking can show it, never chosen
     *                   ({@link RigBase#PEACEFUL_RULE}); decided live at spawn, this is the table's
     *                   claim that the GameTest checks
     * @param note       what the mob does besides carry — AI, breathing, what a click does
     */
    public record Skeleton(String id, float width, float height, Move moves, boolean landSafe,
                           boolean waterSafe, boolean hostile, String note) {

        public RigBase.Candidate candidate() {
            return new RigBase.Candidate(id, width, height, !hostile);
        }
    }

    public static final List<Skeleton> ALL = List.of(
            // ---- WALK: the old pool, minus what the list above removes ----
            new Skeleton("iron_golem", 1.4f, 2.7f, Move.WALK, true, true, false,
                    "wanders; fights hostile mobs and a player who hits it; sinks in water"),
            new Skeleton("polar_bear", 1.4f, 1.4f, Move.WALK, true, false, false,
                    "neutral: fights back when hit; swims at the surface"),
            new Skeleton("cow", 0.9f, 1.4f, Move.WALK, true, false, false,
                    "wanders; a bucket milks it (harmless); floats at the surface"),
            new Skeleton("panda", 1.3f, 1.25f, Move.WALK, true, false, false,
                    "wanders, rolls, sits; floats at the surface"),
            new Skeleton("goat", 0.9f, 1.3f, Move.WALK, true, false, false,
                    "jumps high; now and then rams a mob or player (knockback)"),
            new Skeleton("camel", 1.7f, 2.375f, Move.WALK, true, false, false,
                    "slow wanderer; can only be ridden with a saddle"),
            new Skeleton("armadillo", 0.7f, 0.65f, Move.WALK, true, false, false,
                    "rolls up when a player is near; drops a scute rarely (brush only)"),
            new Skeleton("horse", 1.3964844f, 1.6f, Move.WALK, true, false, false,
                    "an empty-hand click MOUNTS it (it bucks the rider off); floats"),
            new Skeleton("pig", 0.9f, 0.9f, Move.WALK, true, false, false,
                    "wanders; rideable only with a saddle"),
            new Skeleton("llama", 0.9f, 1.87f, Move.WALK, true, false, false,
                    "spits at whoever hits it; the tall-thin end of the ratio range"),
            // ---- amphibious: walk AND swim, safe on land and under water ----
            new Skeleton("turtle", 1.2f, 0.4f, Move.SWIM, true, true, false,
                    "amphibious; swims under water, walks on land, heads for its home beach"),
            new Skeleton("frog", 0.5f, 0.5f, Move.SWIM, true, true, false,
                    "amphibious; hops and swims, eats small slimes"),
            // ---- SWIM, water only: a swimmer out of water dies, so spawn gates on the medium ----
            new Skeleton("dolphin", 0.9f, 0.6f, Move.SWIM, false, true, false,
                    "fast, jumps, must surface for air; DRIES OUT on land (~2 min); fights back"),
            new Skeleton("squid", 0.8f, 0.8f, Move.SWIM, false, true, false,
                    "drifts under water, squirts ink when hit; SUFFOCATES on land"),
            // ---- FLY ----
            new Skeleton("bat", 0.5f, 0.9f, Move.FLY, true, false, false,
                    "flutters erratically and roosts under ceilings; ambient, harmless"),
            // ---- hostile: ranked, never chosen ----
            new Skeleton("zombie", 0.6f, 1.95f, Move.WALK, true, true, true, "hostile"),
            new Skeleton("ravager", 1.95f, 2.2f, Move.WALK, true, false, true, "hostile"),
            new Skeleton("spider", 1.4f, 0.9f, Move.WALK, true, false, true, "hostile"),
            new Skeleton("ghast", 4.0f, 4.0f, Move.FLY, true, false, true, "hostile"),
            new Skeleton("warden", 0.9f, 2.9f, Move.WALK, true, false, true, "hostile"),
            new Skeleton("creeper", 0.6f, 1.7f, Move.WALK, true, false, true, "hostile"),
            new Skeleton("hoglin", 1.3964844f, 1.4f, Move.WALK, true, false, true, "hostile"));

    /**
     * <b>Carriers that cannot FOLLOW a child</b>, measured rather than guessed by
     * {@code CarrierFollowProbeGameTest}: each walking carrier given a companion's follow at the
     * starter pet dragon's speed, 8.5 blocks from the child, for 200 ticks. Ten closed to under 2
     * blocks; these two did not:
     * <ul>
     *   <li><b>turtle</b> — 8.5 → 3.8: it follows, at a crawl, and heads for its home beach;</li>
     *   <li><b>frog</b> — 8.5 → 8.2: its brain does not follow at all.</li>
     * </ul>
     * The first real grant after carriers were chosen by movement put the starter pet dragon on a turtle (12 → 7.4 blocks in
     * ten seconds). A species that follows is never offered these; the probe's test fails if one
     * starts following or another stops. Re-measured 2026-11-11: a stuck
     * follower HOPS to him, so a frog pet does arrive — by a hop every time, never by walking; the
     * turtle still crawls (nearest 3.9 blocks in ten seconds). The probe now asks who WALKS to him.
     */
    public static final java.util.Set<String> CANNOT_FOLLOW = java.util.Set.of("turtle", "frog");

    /**
     * <b>Carriers a child cannot RIDE</b> (2026-10-11), measured rather than guessed by
     * {@code CarrierRideProbeGameTest}: each walking carrier made rideable, climbed on with an empty
     * hand and steered forward. Every carrier carried him ~7 blocks in 3 s; these two THREW HIM OFF
     * (horse 1 trial in 3, llama 2 in 3), because vanilla's untamed horse and llama buck a rider
     * off at random. A rideable creature is never offered these.
     */
    public static final java.util.Set<String> CANNOT_RIDE = java.util.Set.of("horse", "llama");

    /**
     * <b>Carriers that hostile mobs HUNT</b>. Zombies and slimes target an iron golem on
     * their own, and the golem starts fights of its own, so a creature riding one fights every
     * monster near it whatever its instinct says. Live 2026-10-10: a friendly 0.5-scale yeti rode
     * one and was gone a minute later, with the studio's slimes going from 2 to 7. Offered only to
     * a creature whose instinct is to fight ({@code guardian}, {@code hunter}).
     */
    public static final java.util.Set<String> HUNTED = java.util.Set.of("iron_golem");

    /**
     * <b>Carriers that run a Brain</b>: their own wandering is a brain's behaviours, which no
     * goal list switches off — so a creature living an ARCHETYPE (CreatureLife) never gets one, or two
     * lives would steer it at once. Every other carrier's goals are removed for its archetype.
     */
    public static final java.util.Set<String> BRAINS = java.util.Set.of("camel", "goat", "frog", "armadillo");

    /**
     * Carriers whose OWN state, outside any goal, overrides where they go: a panda's move control does
     * nothing while it sits, eats, sneezes or lies on its back, and its tick rolls it (Panda.canPerformAction,
     * PandaMoveControl). An archetype cannot drive one: a climber on a panda rose 0-1.6 of 2 blocks
     * in four runs out of six (2026-11-02), every other carrier climbed.
     */
    public static final java.util.Set<String> SELF_WILLED = java.util.Set.of("panda");

    /** {@code pool} without the {@link #BRAINS} carriers when the creature lives an archetype. */
    public static List<String> forArchetype(List<String> pool, boolean archetype) {
        if (!archetype || pool == null) {
            return pool;
        }
        List<String> out = new ArrayList<>(pool);
        out.removeAll(BRAINS);
        out.removeAll(SELF_WILLED);
        // And not a carrier that cannot keep up with its own life on land (the followers' rule):
        // a turtle crawled through every walking archetype's GameTest (2026-11-02). A swimmer's pool
        // is the water carriers, where a turtle swims well, so this only bites on land.
        java.util.List<String> landed = new ArrayList<>(out);
        landed.removeAll(CANNOT_FOLLOW);
        if (!landed.isEmpty() && out.stream().anyMatch(c -> !CANNOT_FOLLOW.contains(c) && !"dolphin".equals(c)
                && !"squid".equals(c))) {
            out = landed;
        }
        return out.isEmpty() ? pool : out;
    }

    /** {@code pool} without the {@link #HUNTED} carriers unless the creature is meant to fight. */
    public static List<String> forInstinct(List<String> pool, boolean fights) {
        if (fights || pool == null) {
            return pool;
        }
        List<String> out = new ArrayList<>(pool);
        out.removeAll(HUNTED);
        return out.isEmpty() ? pool : out;
    }

    /**
     * {@link #allowed(Move, boolean)} for a creature that FOLLOWS the child: the same list without
     * {@link #CANNOT_FOLLOW}. A swimmer on land (whose only carriers are the two amphibians) gets
     * a walker that can follow, which is better than a pet that never comes.
     */
    public static List<String> allowed(Move move, boolean inWater, boolean follows) {
        return allowed(move, inWater, follows, false);
    }

    /**
     * ... and for a creature he RIDES: without {@link #CANNOT_RIDE} too (2026-10-11). A rideable
     * flier or swimmer keeps its own class; riding one is post-v1 (vehicles), and its carriers are
     * not on the list.
     */
    public static List<String> allowed(Move move, boolean inWater, boolean follows, boolean rides) {
        List<String> out = allowed(move, inWater);
        if (!follows && !rides) {
            return out;
        }
        List<String> ok = new ArrayList<>(out);
        if (follows) {
            ok.removeAll(CANNOT_FOLLOW);
        }
        if (rides) {
            ok.removeAll(CANNOT_RIDE);
        }
        if (ok.isEmpty() && move != Move.WALK) {
            return allowed(Move.WALK, inWater, follows, rides);
        }
        return ok.isEmpty() ? out : ok;
    }

    public static Skeleton byId(String id) {
        for (Skeleton s : ALL) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Which carriers a creature of class {@code move} may use where it stands.
     *
     * <ul>
     *   <li><b>WALK</b> — walkers and the amphibians (a turtle is a fine carrier for a flat
     *       land creature).</li>
     *   <li><b>SWIM</b> — the swimmers; the water-only ones only when it is spawned IN water, so a
     *       whale wished on a beach gets a turtle rather than a dolphin that dies in two
     *       minutes.</li>
     *   <li><b>FLY</b> — the flyers.</li>
     * </ul>
     *
     * <p>Every class falls back to WALK if its own list is empty, so the answer is never "none".
     */
    public static List<String> allowed(Move move, boolean inWater) {
        List<String> out = new ArrayList<>();
        for (Skeleton s : ALL) {
            boolean ok = switch (move) {
                case WALK -> (s.moves() == Move.WALK || s.moves() == Move.SWIM) && s.landSafe();
                case SWIM -> s.moves() == Move.SWIM && (inWater ? s.waterSafe() : s.landSafe());
                case FLY -> s.moves() == Move.FLY;
            };
            if (ok && !s.hostile()) {
                out.add(s.id());
            }
        }
        if (out.isEmpty() && move != Move.WALK) {
            return allowed(Move.WALK, inWater);
        }
        return out;
    }

    /** The table's rows as ranking candidates, for the pure instrument. */
    public static List<RigBase.Candidate> candidates() {
        List<RigBase.Candidate> out = new ArrayList<>();
        for (Skeleton s : ALL) {
            out.add(s.candidate());
        }
        return out;
    }

    private static final String[] SWIM_PARTS = {"fin", "flipper", "fluke", "gill"};
    private static final String[] WALK_PARTS = {"leg", "foot", "feet", "paw", "hoof", "shoe",
            "knee", "toe", "thigh", "shin", "boot"};
    private static final String[] SWIM_NAMES = {"whale", "shark", "fish", "dolphin", "eel",
            "squid", "octopus", "jellyfish", "orca", "seal", "manta", "ray", "narwhal", "mermaid"};

    /**
     * <b>Movement class from what the creature IS</b>: its powers and its sculpt.
     *
     * <ul>
     *   <li><b>FLY</b> when the species has the {@code flies} power — the agent's own word for
     *       "stays airborne instead of walking".</li>
     *   <li><b>SWIM</b> when it has swimming anatomy (a fin, flipper, fluke or gill) and nothing to
     *       walk on (no leg, foot, paw, hoof, shoe…), or no legs and a sea creature's name. A shark
     *       in trainers walks: legs win.</li>
     *   <li><b>WALK</b> otherwise.</li>
     * </ul>
     */
    public static Move movementOf(Collection<String> powers, List<CreatureSculpt.Part> parts,
                                  String name) {
        if (powers != null) {
            for (String p : powers) {
                if (p != null && p.trim().equalsIgnoreCase("flies")) {
                    return Move.FLY;
                }
            }
        }
        boolean fins = false;
        boolean legs = false;
        if (parts != null) {
            for (CreatureSculpt.Part p : parts) {
                String n = p.name() == null ? "" : p.name().toLowerCase(Locale.ROOT);
                fins |= any(n.replace("finger", ""), SWIM_PARTS);
                legs |= any(n, WALK_PARTS);
            }
        }
        if (legs) {
            return Move.WALK;
        }
        String nm = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (fins || anyWord(nm, SWIM_NAMES)) {
            return Move.SWIM;
        }
        return Move.WALK;
    }

    /** {@link #movementOf(Collection, List, String)} from part NAMES alone: a stored sculpt's JSON (the Builder). */
    public static Move movementOfNames(Collection<String> powers, List<String> partNames, String name) {
        List<CreatureSculpt.Part> parts = new ArrayList<>();
        if (partNames != null) {
            for (String n : partNames) {
                parts.add(new CreatureSculpt.Part(n, null, null, null, null, 0));
            }
        }
        return movementOf(powers, parts, name);
    }

    /** {@link #movementOf(Collection, List, String)} for a species record (null: sculpt only). */
    public static Move movementOf(com.moddingfromamod.species.Species species,
                                  List<CreatureSculpt.Part> parts) {
        if (species == null) {
            return movementOf(null, parts, null);
        }
        // a swimmer's archetype is a swimmer's body, whatever it has for fins.
        if (species.archetype() == com.moddingfromamod.species.Archetype.SWIMMER) {
            return Move.SWIM;
        }
        List<String> powers = new ArrayList<>();
        for (var p : species.powers()) {
            powers.add(p.name());
        }
        return movementOf(powers, parts, species.id() + " " + species.name());
    }

    private static boolean any(String s, String[] keys) {
        for (String k : keys) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** Whole words only, so "ray" is not found in "gray" nor "seal" in "sealed". */
    private static boolean anyWord(String s, String[] keys) {
        for (String w : s.split("[^a-z]+")) {
            for (String k : keys) {
                if (w.equals(k) || w.equals(k + "s")) {
                    return true;
                }
            }
        }
        return false;
    }
}
