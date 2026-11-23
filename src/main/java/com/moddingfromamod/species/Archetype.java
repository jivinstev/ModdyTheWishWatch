package com.moddingfromamod.species;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>How a creature LIVES — named after the vanilla mob it should act like</b> (
 * "A dragon should behave like a dragon. A zombie should be like a zombie, even if we
 * sculpt it and it's a different carrier").
 *
 * <p>Until this, a sculpted creature's life was its CARRIER's: whichever vanilla mob fitted its body
 * shape walked under it with all of that mob's own AI, so a predator on a cow grazed between hunts and
 * every flying creature — a dragon and a butterfly alike — fluttered at random like the bat it rode.
 * An archetype is chosen from what the creature IS ({@code behaves_like}, the vanilla mob the agent
 * names), and {@code CreatureLife} runs it on any carrier, the carrier's own wandering switched off.
 *
 * <p><b>Modelled on vanilla, not copied from it.</b> Each life's numbers are the vanilla goal's own —
 * {@code RandomStrollGoal}'s 120-tick interval and 10-block reach, {@code LookAtPlayerGoal}'s 8 blocks,
 * {@code WaterAvoidingRandomFlyingGoal}'s low flights, the phantom's circling — but it is driven through
 * one steering layer, because the carriers cannot all take goals: every flyer rides a bat, which is not a
 * pathfinding mob, and four carriers run a Brain (CarrierPool keeps those from an archetype).
 *
 * <p><b>What an archetype is NOT</b>: whether it fights, whom, how big it is, whether it flies or can be
 * ridden. Those stay separate settings (the temperament, size, powers), so "a friendly guard dog the
 * size of a horse" is PACK_HUNTER + guardian + friendly + big, and the archetype count stays small.
 *
 * <p>Pure — no Minecraft imports. The first batch is ten, chosen by counting 721 wished creatures
 * (2026-11-02): with the kinds folded into them they cover 81–88% of every sample.
 */
public enum Archetype {

    /** Cow, sheep, pig, chicken, rabbit, frog: ambles, stops to graze, keeps to its herd, runs when hit. */
    GRAZER(new Life(Gait.WALK, 8, 120, 0.6, 0.55, 8, 10, 0, 0, 0, 0, Fight.FLEE, false)),
    /** Ravager, hoglin, polar bear, iron golem's size: heavy and slow, a loner, charges and slams. */
    BRUISER(new Life(Gait.WALK, 10, 160, 0.5, 0.45, 10, 0, 0, 0, 0, 0, Fight.CHARGE, false)),
    /** Wolf, fox, cat, ocelot: trots, prowls, keeps with its pack, pounces. */
    PACK_HUNTER(new Life(Gait.WALK, 12, 100, 0.9, 0.3, 8, 12, 0, 0, 0, 0, Fight.LEAP, true)),
    /** Bee, parrot, allay, a butterfly or bird: short darting flights low over the ground, lands now and then. */
    BUZZER(new Life(Gait.FLIT, 6, 30, 0.7, 0.25, 6, 8, 1, 4, 0, 0, Fight.DART, false)),
    /** A dragon (and the phantom's swoop): circles high over its home, perches up high, swoops and breathes fire. */
    DRAGON(new Life(Gait.GLIDE, 14, 20, 0.8, 0.0, 16, 0, 9, 15, 12, 600, Fight.SWOOP, false)),
    /** Fish, squid, dolphin, axolotl: swims and turns in water, schools, never leaves it on purpose. */
    SWIMMER(new Life(Gait.SWIM, 8, 60, 0.7, 0.2, 6, 8, 0, 0, 0, 0, Fight.DART, false)),
    /** Horse, donkey, camel, llama — and a pegasus when it flies: grazes, herds loosely, comes to its rider. */
    MOUNT(new Life(Gait.WALK, 6, 160, 0.5, 0.6, 8, 12, 2, 6, 7, 0, Fight.FLEE, true)),
    /** Villager, trader, piglin at peace: potters between spots, stops to look at you, gathers with its own. */
    VILLAGER(new Life(Gait.WALK, 6, 120, 0.5, 0.5, 8, 10, 0, 0, 0, 0, Fight.FLEE, false)),
    /** Skeleton, stray, a creature with a bow: keeps its distance and shoots, strafing round its target. */
    ARCHER(new Life(Gait.WALK, 8, 120, 0.6, 0.35, 10, 0, 0, 0, 0, 0, Fight.KEEP_DISTANCE, false)),
    /** Spider, cave spider: scuttles, climbs straight up walls, pounces. */
    CLIMBER(new Life(Gait.CLIMB, 10, 100, 0.8, 0.3, 8, 0, 0, 0, 0, 0, Fight.LEAP, false)),
    /**
     * A car, a truck, a plane, a helicopter, a boat, a submarine, a train, a rocket — anything he DRIVES:
     * it stays where it was left until he climbs in (a flying one comes down and parks), it is rigid (no
     * flapping wings, no swinging legs, no head turning to look at him), and it never fights or runs.
     * The vanilla things that live this way are the minecart and the boat ("I made an
     * airplane once, and it flapped its wings and flew around like a bat").
     */
    VEHICLE(new Life(Gait.PARKED, 0, 200, 0, 1.0, 0, 0, 0, 0, 0, 0, Fight.NONE, false));

    /** How it gets about. */
    public enum Gait { WALK, FLIT, GLIDE, SWIM, CLIMB, PARKED }

    /** How it fights WHEN its temperament says it does (it decides whether; this, how). */
    public enum Fight { FLEE, CHARGE, LEAP, DART, SWOOP, KEEP_DISTANCE, NONE }

    /**
     * One archetype's life, in vanilla's own numbers.
     *
     * @param wanderRadius  how far from home it wanders (RandomStrollGoal: 10)
     * @param wanderEvery   ticks between new wander targets, on average (RandomStrollGoal: 120)
     * @param speed         wander speed, as a multiple of its own (RandomStrollGoal: 1.0; a grazer ambles)
     * @param pause         chance that a new decision is to stand still (graze, look about) instead
     * @param lookRange     looks at a player this close (LookAtPlayerGoal: 8)
     * @param herdRadius    keeps within this of its own kind's middle (0: a loner)
     * @param flyMin        a flyer's lowest height above the ground
     * @param flyMax        a flyer's highest height above the ground
     * @param circleRadius  a glider circles its home at this radius (0: it does not circle)
     * @param perchEvery    a glider lands on a high spot about this often, in ticks (0: never)
     * @param fight         how it fights, when it does
     * @param comesToRider  it comes over to its owner nearby (a mount, a pack animal) rather than ignore him
     */
    public record Life(Gait gait, double wanderRadius, int wanderEvery, double speed, double pause,
                       double lookRange, double herdRadius, double flyMin, double flyMax,
                       double circleRadius, int perchEvery, Fight fight, boolean comesToRider) {
    }

    private final Life life;

    Archetype(Life life) {
        this.life = life;
    }

    public Life life() {
        return life;
    }

    /** Its key in a species file and a tool reply: {@code pack_hunter}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Does it live in the air? (Its species then flies: {@link #impliedPowers}.) */
    public boolean flies() {
        return life.gait() == Gait.FLIT || life.gait() == Gait.GLIDE;
    }

    /** Powers this life cannot be lived without: a dragon or a buzzer flies. */
    public Set<Species.Power> impliedPowers() {
        if (this == VEHICLE) {
            // A vehicle he cannot drive is a statue: it is driven, and so it also comes with its horn.
            return Set.of(Species.Power.RIDEABLE);
        }
        return flies() ? Set.of(Species.Power.FLIES) : Set.of();
    }

    /** Is its body rigid — drawn in its rest pose, no clips, no look (a vehicle)? */
    public boolean rigid() {
        return life.gait() == Gait.PARKED;
    }

    /**
     * Moves its way of fighting brings, used ONLY when its temperament fights (CreatureLife): an archer
     * shoots, a dragon breathes fire (a fire charge), a bruiser charges. A peaceful one never gains a
     * move from its archetype.
     */
    public Set<com.moddingfromamod.runtime.Abilities.Kind> impliedAbilities() {
        return switch (this) {
            case ARCHER, DRAGON -> Set.of(com.moddingfromamod.runtime.Abilities.Kind.RANGED);
            case BRUISER -> Set.of(com.moddingfromamod.runtime.Abilities.Kind.CHARGE);
            default -> Set.of();
        };
    }

    /**
     * <b>Every vanilla mob, by the archetype that acts most like it</b> — the table the agent's
     * {@code behaves_like} is read through, so the prompt names a mob the model already knows and never
     * carries a list. Mobs whose own archetype is not built yet are mapped to the nearest that is
     * (a zombie shambles like a bruiser, a blaze darts like a buzzer); {@link #NOT_YET} names them.
     */
    public static final Map<String, Archetype> VANILLA = Map.ofEntries(
            // grazers
            Map.entry("cow", GRAZER), Map.entry("mooshroom", GRAZER), Map.entry("sheep", GRAZER),
            Map.entry("pig", GRAZER), Map.entry("chicken", GRAZER), Map.entry("rabbit", GRAZER),
            Map.entry("frog", GRAZER), Map.entry("goat", GRAZER), Map.entry("turtle", GRAZER),
            Map.entry("armadillo", GRAZER), Map.entry("sniffer", GRAZER), Map.entry("slime", GRAZER),
            Map.entry("magma_cube", GRAZER), Map.entry("panda", GRAZER),
            // bruisers
            Map.entry("ravager", BRUISER), Map.entry("hoglin", BRUISER), Map.entry("zoglin", BRUISER),
            Map.entry("polar_bear", BRUISER), Map.entry("iron_golem", BRUISER), Map.entry("warden", BRUISER),
            Map.entry("zombie", BRUISER), Map.entry("husk", BRUISER), Map.entry("drowned", BRUISER),
            Map.entry("zombie_villager", BRUISER), Map.entry("zombified_piglin", BRUISER),
            Map.entry("piglin_brute", BRUISER), Map.entry("vindicator", BRUISER), Map.entry("giant", BRUISER),
            Map.entry("creeper", BRUISER), Map.entry("enderman", BRUISER), Map.entry("snow_golem", BRUISER),
            Map.entry("creaking", BRUISER),
            // pack hunters
            Map.entry("wolf", PACK_HUNTER), Map.entry("fox", PACK_HUNTER), Map.entry("cat", PACK_HUNTER),
            Map.entry("ocelot", PACK_HUNTER),
            // buzzers
            Map.entry("bee", BUZZER), Map.entry("parrot", BUZZER), Map.entry("allay", BUZZER),
            Map.entry("bat", BUZZER), Map.entry("vex", BUZZER), Map.entry("blaze", BUZZER),
            Map.entry("breeze", BUZZER),
            // dragons
            Map.entry("ender_dragon", DRAGON), Map.entry("phantom", DRAGON), Map.entry("ghast", DRAGON),
            Map.entry("wither", DRAGON),
            // swimmers
            Map.entry("cod", SWIMMER), Map.entry("salmon", SWIMMER), Map.entry("tropical_fish", SWIMMER),
            Map.entry("pufferfish", SWIMMER), Map.entry("squid", SWIMMER), Map.entry("glow_squid", SWIMMER),
            Map.entry("dolphin", SWIMMER), Map.entry("axolotl", SWIMMER), Map.entry("tadpole", SWIMMER),
            Map.entry("guardian", SWIMMER), Map.entry("elder_guardian", SWIMMER),
            // mounts
            Map.entry("horse", MOUNT), Map.entry("donkey", MOUNT), Map.entry("mule", MOUNT),
            Map.entry("camel", MOUNT), Map.entry("llama", MOUNT), Map.entry("trader_llama", MOUNT),
            Map.entry("skeleton_horse", MOUNT), Map.entry("zombie_horse", MOUNT), Map.entry("strider", MOUNT),
            // villager-like
            Map.entry("villager", VILLAGER), Map.entry("wandering_trader", VILLAGER), Map.entry("piglin", VILLAGER),
            Map.entry("witch", VILLAGER), Map.entry("evoker", VILLAGER), Map.entry("illusioner", VILLAGER),
            // archers
            Map.entry("skeleton", ARCHER), Map.entry("stray", ARCHER), Map.entry("bogged", ARCHER),
            Map.entry("pillager", ARCHER), Map.entry("wither_skeleton", ARCHER), Map.entry("shulker", ARCHER),
            // climbers
            Map.entry("spider", CLIMBER), Map.entry("cave_spider", CLIMBER), Map.entry("silverfish", CLIMBER),
            Map.entry("endermite", CLIMBER));

    /**
     * Invented creatures with no vanilla mob to name — "dragon" is a dragon's own archetype — and the
     * archetype words themselves, so {@code behaves_like: "grazer"} reads too.
     */
    public static final Map<String, Archetype> INVENTED = Map.ofEntries(
            Map.entry("dragon", DRAGON), Map.entry("grazer", GRAZER), Map.entry("bruiser", BRUISER),
            Map.entry("pack_hunter", PACK_HUNTER), Map.entry("buzzer", BUZZER), Map.entry("swimmer", SWIMMER),
            Map.entry("mount", MOUNT), Map.entry("villager_like", VILLAGER), Map.entry("archer", ARCHER),
            Map.entry("climber", CLIMBER),
            // Not mobs, but the two vanilla things that live a vehicle's life, and the word itself.
            Map.entry("vehicle", VEHICLE), Map.entry("minecart", VEHICLE), Map.entry("boat", VEHICLE));

    /**
     * Vanilla mobs mapped to a STAND-IN until their own archetype is built — the reply says so, so
     * "acts like a zombie" is never claimed as a zombie's own shamble.
     */
    public static final Set<String> NOT_YET = Set.of("zombie", "husk", "drowned", "zombie_villager",
            "zombified_piglin", "creeper", "enderman", "blaze", "breeze", "ghast", "wither", "shulker",
            "slime", "magma_cube", "silverfish", "endermite", "guardian", "elder_guardian", "warden",
            "snow_golem", "iron_golem", "creaking", "vex", "witch", "evoker", "illusioner", "strider");

    /**
     * The archetype {@code behaves_like} names: a vanilla mob ({@code wolf} or {@code minecraft:wolf}),
     * an invented one ({@code dragon}) or an archetype word. Null when it names none of these — the
     * caller then asks the registry what the mob IS ({@link #byTraits}) or refuses it.
     */
    public static Archetype resolve(String behavesLike) {
        if (behavesLike == null || behavesLike.isBlank()) {
            return null;
        }
        String k = behavesLike.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (k.startsWith("minecraft:")) {
            k = k.substring("minecraft:".length());
        }
        Archetype a = INVENTED.get(k);
        return a != null ? a : VANILLA.get(k);
    }

    /** By key, as stored on a species ({@code pack_hunter}); null for none or an unknown key. */
    public static Archetype byKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (Archetype a : values()) {
            if (a.key().equalsIgnoreCase(key.trim())) {
                return a;
            }
        }
        return null;
    }

    /**
     * A mob outside the table (another mod's) by what it is: flies → a buzzer, lives in water → a
     * swimmer, a monster → a bruiser, anything else a grazer. The nearest built archetype, said as such.
     */
    public static Archetype byTraits(boolean flies, boolean water, boolean monster) {
        if (flies) {
            return BUZZER;
        }
        if (water) {
            return SWIMMER;
        }
        return monster ? BRUISER : GRAZER;
    }

    /** One line for the tool's reply, so the agent sees what it asked for before the reveal. */
    public String says() {
        return switch (this) {
            case GRAZER -> "ambles about, stops to graze, keeps to its herd, runs if hit";
            case BRUISER -> "stomps about slowly as a loner; when it fights it charges";
            case PACK_HUNTER -> "trots and prowls, keeps with its pack, pounces when it fights";
            case BUZZER -> "flits about low, lands now and then; darts in when it fights";
            case DRAGON -> "circles high over its home, perches up high; swoops and breathes fire when it fights";
            case SWIMMER -> "swims and schools in water";
            case MOUNT -> "grazes, herds loosely, comes over to its rider";
            case VILLAGER -> "potters between spots, stops to look at you, gathers with its own";
            case ARCHER -> "keeps its distance and shoots when it fights";
            case CLIMBER -> "scuttles about and climbs straight up walls; pounces when it fights";
            case VEHICLE -> "stays parked where it was left until he drives it, and never moves a part of its own";
        };
    }
}
