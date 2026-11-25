package com.moddingfromamod.species;

import java.util.List;
import java.util.Locale;

/**
 * <b>What a creature's numbers mean, said back to the agent</b> (#101). Pure. The creature tools end their result with
 * one power line: its health and attack damage (and armour and knockback when it has any), the vanilla mob that is
 * most like, and what its size's band ({@link PowerBand}) would give it. And when a creature that fights carries a
 * hit of 0, which lands nothing, the result says so with the band's number, so the agent corrects it before the reveal.
 *
 * <p>Why in the tool and not a lesson: in archived sculpts damage was sent 79% of the time and was 0 in 58% of them,
 * because the tool's own words said "Use 0 for a friendly character". A friendly creature never attacks anyway, so the
 * 0 added no safety; it only took the power from guards, from moves, and from any world that enlists the creature.
 */
public final class PowerLine {

    /** A vanilla mob to compare with: its health and its melee hit on Normal (0 for one that never attacks). */
    record Mob(String name, double health, double damage) {
    }

    /** Vanilla mobs, gentle and fighting, small to huge. A creature is compared with the nearest of its own kind. */
    static final List<Mob> VANILLA = List.of(
            new Mob("rabbit", 3, 0), new Mob("chicken", 4, 0), new Mob("sheep", 8, 0), new Mob("pig", 10, 0),
            new Mob("cow", 10, 0), new Mob("horse", 22, 0), new Mob("camel", 32, 0), new Mob("sniffer", 14, 0),
            new Mob("bee", 10, 2), new Mob("fox", 10, 2), new Mob("wolf", 8, 4), new Mob("spider", 16, 2),
            new Mob("zombie", 20, 3), new Mob("vindicator", 24, 13), new Mob("polar bear", 30, 6),
            new Mob("hoglin", 40, 6), new Mob("enderman", 40, 7), new Mob("elder guardian", 80, 8),
            new Mob("ravager", 100, 12), new Mob("iron golem", 100, 15), new Mob("ender dragon", 200, 10),
            new Mob("warden", 500, 30));

    private PowerLine() {
    }

    /** The vanilla mob most like these numbers: among gentle mobs for a creature with no hit, else among fighters. */
    public static String likeVanilla(double health, double damage) {
        boolean gentle = damage <= 0;
        Mob best = null;
        double bestD = Double.MAX_VALUE;
        for (Mob m : VANILLA) {
            if ((m.damage() <= 0) != gentle) {
                continue;
            }
            double dh = Math.log(Math.max(1, health)) - Math.log(Math.max(1, m.health()));
            double dd = gentle ? 0 : Math.log(1 + damage) - Math.log(1 + m.damage());
            double d = dh * dh + dd * dd;
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best == null ? "nothing in the game"
                : ("aeiou".indexOf(best.name().charAt(0)) >= 0 ? "an " : "a ") + best.name() + " (" + num(best.health()) + " health, " + num(best.damage()) + " damage)";
    }

    /**
     * The power line: {@code "Power: 60 health, 6 damage: like an elder guardian (80 health, 8 damage). Its size is
     * medium: about 35 health, 5 damage."} {@code bodyVolume} is its sculpt's solid volume at scale
     * 1 (NaN when it has none).
     */
    public static String of(Species s, double bodyVolume) {
        boolean vehicle = s.archetype() == Archetype.VEHICLE;
        return of(s.health(), s.damage(), s.armor(), s.knockbackResistance(),
                PowerBand.forCreature(bodyVolume, s.body(), s.scale()), s.powers().contains(Species.Power.BOSS), vehicle);
    }

    /** The power line from plain numbers: a real mob's live attributes, or a species'. */
    public static String of(double health, double damage, double armor, double knockback, PowerBand.Band band,
                            boolean boss, boolean vehicle) {
        StringBuilder sb = new StringBuilder("Power: ").append(num(health)).append(" health, ").append(num(damage))
                .append(" damage");
        if (armor > 0) {
            sb.append(", ").append(num(armor)).append(" armour");
        }
        if (knockback > 0) {
            sb.append(", knockback resistance ").append(num(knockback));
        }
        sb.append(": like ").append(likeVanilla(health, damage)).append(". Its size is ").append(band.name())
                .append(": about ").append(num(PowerBand.defaultHealth(band, boss))).append(" health, ")
                .append(num(PowerBand.defaultDamage(band, vehicle))).append(" damage")
                .append(vehicle ? " (a vehicle never hits)." : ".");
        return sb.toString();
    }

    /**
     * Non-empty when the creature has a fighting instinct and a hit of 0: it can never hurt what it fights. Says the
     * band's number to use, and that a friendly creature never attacks whatever its damage.
     */
    public static String zeroHitFlag(Species s, double bodyVolume) {
        if (s.damage() > 0 || !com.moddingfromamod.vanilla.RigInstinct.of(s).fights()) {
            return "";
        }
        String why = s.base() == Species.Base.GUARDIAN ? "base 'guardian'"
                : s.base() == Species.Base.HUNTER ? "base 'hunter'" : "no 'friendly' power, so it attacks";
        return zeroHitFlag(why, PowerBand.forCreature(bodyVolume, s.body(), s.scale()));
    }

    /** The flag for a fighter with no hit, given why it fights and its band. */
    public static String zeroHitFlag(String why, PowerBand.Band band) {
        return "⚠ It fights (" + why + ") but its damage is 0, and a hit of 0 lands nothing: it can never hurt what it "
                + "fights. Give it damage before you show him (modify_mob with damage, or sculpt it again with damage): about "
                + num(band.damage()) + " for its size (" + band.name() + "). Damage never makes a friendly creature "
                + "attack; only its instinct does.";
    }

    /**
     * How much armour doubles its toughness: with this much, every hit takes about half (vanilla armour shaves up to
     * 4% a point). Toughness for the advisory is {@code health * (1 + armour / ARMOUR_DOUBLES)}.
     */
    public static final double ARMOUR_DOUBLES = 15;

    /**
     * <b>An advisory, never a refusal</b> (#101 smoke): non-empty when a creature that is not a boss is FAR above what
     * its size's band allows a reviewed stock creature: its health above the band's most health, or its health and
     * armour together (its toughness) above twice that (a small body there is in iron-golem territory), or its damage
     * above the band's most damage. Those tops ({@link PowerBand.Band#maxHealth},
     * {@link PowerBand.Band#maxDamage}) are about two and a half times the band's default for the small and medium
     * bands and about twice for the large and huge ones: a man-sized knight at 80 health and 12 armour is in iron-golem
     * territory; a giant dinosaur at 300 health is at the top of its band, not above it. Never for a boss, never below
     * the band, and the line only says how to lower it or to mark it a boss if that is meant.
     */
    public static String farAbove(double health, double damage, double armor, PowerBand.Band band, boolean boss) {
        if (boss) {
            return "";
        }
        double tough = health * (1 + Math.max(0, armor) / ARMOUR_DOUBLES);
        boolean tooTough = health > band.maxHealth() + 1e-9 || tough > 2 * band.maxHealth() + 1e-9;
        boolean hitsTooHard = damage > band.maxDamage() + 1e-9;
        if (!tooTough && !hitsTooHard) {
            return "";
        }
        String what = tooTough && hitsTooHard ? "too tough and hits too hard" : tooTough
                ? (armor > 0 ? "too tough for its size, health and armour together" : "too tough for its size")
                : "hits too hard for its size";
        return "⚠ That is far above its size (" + band.name() + ": the most a creature this size has is about "
                + num(band.maxHealth()) + " health, " + num(band.maxDamage()) + " damage): it is " + what
                + ". If it is meant to be a boss, give it the 'boss' power; otherwise lower it (modify_mob with health, "
                + "damage or armor) before you show him. Nothing was refused.";
    }

    /** The advisory for a species. A vehicle has none: its health is how much it takes, not a size (#99). */
    public static String farAbove(Species s, double bodyVolume) {
        if (s.archetype() == Archetype.VEHICLE) {
            return "";
        }
        return farAbove(s.health(), s.damage(), s.armor(), PowerBand.forCreature(bodyVolume, s.body(), s.scale()),
                s.powers().contains(Species.Power.BOSS));
    }

    /**
     * A real mob's body, measured on its bounding box: width x width x height, times {@link #BOX_FILL}, the share of
     * its box a vanilla mob's model fills (a zombie's boxes are about 0.41 cubic blocks of its 0.70 box; an iron
     * golem's and a ravager's land in the large band, as the stock creatures their size do).
     */
    public static double boxVolume(double width, double height) {
        return Math.max(0, width) * Math.max(0, width) * Math.max(0, height) * BOX_FILL;
    }

    public static final double BOX_FILL = 0.6;

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
