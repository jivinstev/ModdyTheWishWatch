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
        StringBuilder sb = new StringBuilder("Power: ").append(num(s.health())).append(" health, ")
                .append(num(s.damage())).append(" damage");
        if (s.armor() > 0) {
            sb.append(", ").append(num(s.armor())).append(" armour");
        }
        if (s.knockbackResistance() > 0) {
            sb.append(", knockback resistance ").append(num(s.knockbackResistance()));
        }
        boolean vehicle = s.archetype() == Archetype.VEHICLE;
        PowerBand.Band band = PowerBand.forCreature(bodyVolume, s.body(), s.scale());
        sb.append(": like ").append(likeVanilla(s.health(), s.damage())).append(". Its size is ").append(band.name())
                .append(": about ").append(num(PowerBand.defaultHealth(band, s.powers().contains(Species.Power.BOSS))))
                .append(" health, ").append(num(PowerBand.defaultDamage(band, vehicle))).append(" damage")
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
        PowerBand.Band band = PowerBand.forCreature(bodyVolume, s.body(), s.scale());
        String why = s.base() == Species.Base.GUARDIAN ? "base 'guardian'"
                : s.base() == Species.Base.HUNTER ? "base 'hunter'" : "no 'friendly' power, so it attacks";
        return "⚠ It fights (" + why + ") but its damage is 0, and a hit of 0 lands nothing: it can never hurt what it "
                + "fights. Give it damage before you show him (modify_mob with damage, or sculpt it again with damage): about "
                + num(band.damage()) + " for its size (" + band.name() + "). Damage never makes a friendly creature attack; only its instinct does.";
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
