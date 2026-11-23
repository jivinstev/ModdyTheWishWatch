package com.moddingfromamod.species;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>What a creature's own ability can do to the creature, and what makes it immune</b>
 *. Pure: no Minecraft, so every row is a unit test.
 *
 * <p>Found in production: <i>"make spikor! he breathes fire"</i> got a datapack that put fire at
 * Spikor's own feet, and the next wish was <i>"lol spikor catches himself on fire"</i>. The power
 * that stops it ({@code fireproof}) already existed; the agent did not think to set it, and it
 * should never have had to. So the TOOLS decide: a datapack that runs as or at a wished creature
 * ({@code @e[tag=wishmob_…]}) and makes fire, blasts, poison, wither, lightning, frost or falls
 * gives that creature the matching immunity, recorded on its species so it survives a respawn, a
 * rebuild and a reload.
 *
 * <p>Deliberately generous. A creature with flame particles and no real fire is made fireproof
 * anyway: an unneeded immunity costs the child nothing, and a missed one is a pet that burns.
 * It never protects the CHILD, only the creature the datapack names.
 */
public final class SelfHarm {

    private SelfHarm() {
    }

    /** Ways an ability can hurt whoever stands where it lands, including its own creature. */
    public enum Hazard {
        FIRE, EXPLOSION, POISON, WITHER, LIGHTNING, FREEZE, FALL
    }

    /**
     * The power that makes a creature immune to a hazard. A switch with no default, so a new
     * {@link Hazard} does not compile until someone decides its immunity, and
     * {@code SelfHarmTest} fails if one is left out of {@link #SIGNS}.
     */
    public static Species.Power immunity(Hazard h) {
        return switch (h) {
            case FIRE -> Species.Power.FIREPROOF;
            case EXPLOSION -> Species.Power.BLASTPROOF;
            case POISON -> Species.Power.POISONPROOF;
            case WITHER -> Species.Power.WITHERPROOF;
            case LIGHTNING -> Species.Power.LIGHTNINGPROOF;
            case FREEZE -> Species.Power.FROSTPROOF;
            case FALL -> Species.Power.FALLPROOF;
        };
    }

    /**
     * How each hazard shows in a datapack's source: block ids, entity ids, effect ids, damage types,
     * particles and NBT keys, matched as whole words on the lower-cased source.
     */
    static final Map<Hazard, List<String>> SIGNS = new EnumMap<>(Map.of(
            // "in_fire"/"on_fire" are damage types; "fire"/"soul_fire" blocks; "flame" the breath's
            // particle; "fire:" the Fire ticks tag. fire_resistance never matches: `_` is a word
            // character, so \bfire\b does not see it.
            Hazard.FIRE, List.of("fire", "soul_fire", "in_fire", "on_fire", "lava", "fireball",
                    "small_fireball", "fire_charge", "flame", "soul_fire_flame", "campfire",
                    "magma_block", "hot_floor", "burn", "burning"),
            Hazard.EXPLOSION, List.of("tnt", "tnt_minecart", "creeper", "end_crystal", "fireball",
                    "wither_skull", "explosion", "player_explosion", "explosionpower", "explode"),
            Hazard.POISON, List.of("poison"),
            Hazard.WITHER, List.of("wither", "wither_skull", "wither_rose"),
            Hazard.LIGHTNING, List.of("lightning_bolt", "lightning"),
            Hazard.FREEZE, List.of("powder_snow", "freeze", "freezing", "ticksfrozen", "frozen"),
            Hazard.FALL, List.of("levitation", "fall", "motion")));

    private static final Map<Hazard, Pattern> PATTERNS = new EnumMap<>(Hazard.class);

    static {
        for (var e : SIGNS.entrySet()) {
            PATTERNS.put(e.getKey(), Pattern.compile("\\b(" + String.join("|", e.getValue()) + ")\\b"));
        }
    }

    /** Every hazard a datapack's source can make. */
    public static Set<Hazard> hazardsIn(String source) {
        Set<Hazard> out = EnumSet.noneOf(Hazard.class);
        if (source == null || source.isBlank()) {
            return out;
        }
        String s = source.toLowerCase(Locale.ROOT);
        for (var e : PATTERNS.entrySet()) {
            if (e.getValue().matcher(s).find()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * The hazards a BUILT-IN ability ({@code sculpt_creature}'s {@code abilities}) makes for its own
     * creature. None, each for a stated reason, and a switch with no default so a new ability does
     * not compile until someone says:
     * <ul>
     *   <li>ranged throws a snowball ({@code WishMobEntity.shoot});</li>
     *   <li>slam hurts everything nearby EXCEPT itself ({@code e != this});</li>
     *   <li>charge, summon, teleport and heal hurt nobody.</li>
     * </ul>
     */
    public static Set<Hazard> hazardsOf(com.moddingfromamod.runtime.Abilities.Kind k) {
        return switch (k) {
            case RANGED, SLAM, CHARGE, SUMMON, TELEPORT, HEAL -> EnumSet.noneOf(Hazard.class);
        };
    }

    /** The powers that make a creature immune to these hazards. */
    public static Set<Species.Power> immunitiesFor(Set<Hazard> hazards) {
        Set<Species.Power> out = EnumSet.noneOf(Species.Power.class);
        for (Hazard h : hazards) {
            out.add(immunity(h));
        }
        return out;
    }

    private static final Pattern WISHMOB_TAG = Pattern.compile("tag\\s*=\\s*!?\\s*(wishmob_[a-z0-9_]+)");

    /**
     * The wished creatures a datapack names, by their species tag ({@code WishMobTags.forSpecies}):
     * {@code @e[tag=wishmob_spikor]} is Spikor. A negated tag ({@code tag=!wishmob_x}) is left
     * out, because that is the datapack pointing AWAY from the creature.
     */
    public static Set<String> creatureTags(String source) {
        Set<String> out = new TreeSet<>();
        if (source == null) {
            return out;
        }
        Matcher m = WISHMOB_TAG.matcher(source.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (!m.group(0).replace(" ", "").contains("=!")) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /**
     * The one line a tool says when it made a creature immune, so the agent knows and never has to
     * do it itself. Empty when nothing was added.
     */
    public static String note(String creature, Set<Species.Power> added) {
        if (added == null || added.isEmpty()) {
            return "";
        }
        List<String> names = added.stream().map(p -> p.name().toLowerCase(Locale.ROOT)).sorted().toList();
        return "🛡 '" + creature + "' was made " + String.join(", ", names)
                + " automatically, so its own ability cannot hurt it. You do not need to add this.";
    }
}
