package com.moddingfromamod.species;

import java.util.List;
import java.util.Locale;

/**
 * <b>How strong a creature of this size should be</b> (#99, #100). One pure table from a body's size to the health and
 * attack damage that fit it, anchored on vanilla mobs: a rabbit 3/0, a wolf 8/4, a zombie 20/3, a ravager 100/12, an
 * iron golem 100/15.
 *
 * <p><b>Size</b> is the body's solid volume in cubic blocks: the sum of its sculpt's boxes (16 pixels to a block),
 * times its scale cubed. Not its longest side: a butterfly's flat wings span four blocks and hold almost nothing, a
 * whale is long and full. Measured on the 55 stock creatures, solid volume sorts them the way a person would.
 *
 * <p>Each band has a <b>default</b> (what a creature gets when it carries no number of its own) and a <b>range</b>
 * (what a reviewed stat line may be, so a gentle animal and a fighter of one size can differ, and a hamster can never
 * have a tank's health). A <b>boss</b> takes half as much health again; a <b>vehicle</b> never hits.
 */
public final class PowerBand {

    /** One size band: its name, the volume it starts at, its default line and the ranges a reviewed line keeps to. */
    public record Band(String name, double fromVolume, double health, double damage,
                       double minHealth, double maxHealth, double minDamage, double maxDamage) {

        public boolean healthFits(double h) {
            return h >= minHealth - 1e-9 && h <= maxHealth + 1e-9;
        }

        public boolean damageFits(double d) {
            return d >= minDamage - 1e-9 && d <= maxDamage + 1e-9;
        }

        /** Its words for a tool result or a test message: "small (16-20 health, 3 damage)". */
        public String describe() {
            return String.format(Locale.ROOT, "%s (about %s health, %s damage)", name, num(health), num(damage));
        }
    }

    /** The bands, smallest first. Volumes in cubic blocks (a zombie's body holds about half of one). */
    public static final List<Band> BANDS = List.of(
            new Band("tiny", 0.0, 6, 1, 2, 12, 0, 2),
            new Band("small", 0.3, 18, 3, 4, 45, 0, 6),
            new Band("medium", 1.0, 35, 5, 8, 90, 1, 9),
            new Band("large", 3.0, 80, 8, 20, 150, 2, 12),
            new Band("huge", 15.0, 160, 12, 60, 300, 4, 20));

    /** A boss carries this much more health than its size's default. */
    public static final double BOSS_HEALTH = 1.5;

    private PowerBand() {
    }

    /** The band a body of this solid volume (cubic blocks) is in. A volume that cannot be read is small. */
    public static Band forVolume(double volume) {
        if (!(volume >= 0) || Double.isInfinite(volume)) {
            return BANDS.get(1);
        }
        Band out = BANDS.get(0);
        for (Band b : BANDS) {
            if (volume >= b.fromVolume()) {
                out = b;
            }
        }
        return out;
    }

    /**
     * Solid volume in cubic blocks: each box {@code {fromX, fromY, fromZ, toX, toY, toZ}} in pixels, summed, times
     * {@code scale} cubed. Overlapping boxes count twice, as a sculpt's do: it is a measure of bulk, not a mesh.
     */
    public static double volume(List<double[]> boxes, double scale) {
        double px = 0;
        if (boxes != null) {
            for (double[] b : boxes) {
                if (b != null && b.length >= 6) {
                    px += Math.abs((b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]));
                }
            }
        }
        double k = scale > 0 ? scale : 1.0;
        return px / 4096.0 * k * k * k;
    }

    /**
     * The solid volume, in cubic blocks at scale 1, of each vanilla model shape a species without a sculpt is drawn
     * with: the sum of that model's boxes (a zombie's head, body and limbs; a cow's body, head and legs; a chicken's;
     * a slime's outer cube; a spider's body, head and legs). Read when a creature has no sculpt to measure.
     */
    public static double bodyVolume(Species.Body body) {
        return switch (body == null ? Species.Body.HUMANOID : body) {
            case HUMANOID -> 1664 / 4096.0;    // zombie: head 8x8x8, body 8x12x4, four limbs 4x12x4
            case QUADRUPED -> 3312 / 4096.0;   // cow: body 12x18x10, head 8x8x6, four legs 4x12x4
            case SPIDER -> 2200 / 4096.0;      // spider: body 10x8x12, head 8x8x8, neck 6x6x6, eight legs 16x2x2
            case SLIME -> 512 / 4096.0;        // slime: its 8x8x8 outer cube
            case BIRD -> 420 / 4096.0;         // chicken: body, head, wings and legs
        };
    }

    /**
     * <b>The band of a creature</b>: its body's solid volume at scale 1 ({@code volumeAtScale1}, from its sculpt; NaN
     * when it has none, and then its vanilla model shape's) grown by its scale.
     */
    public static Band forCreature(double volumeAtScale1, Species.Body body, double scale) {
        double v = Double.isNaN(volumeAtScale1) || volumeAtScale1 <= 0 ? bodyVolume(body) : volumeAtScale1;
        double k = scale > 0 ? scale : 1.0;
        return forVolume(v * k * k * k);
    }

    /** The health a creature of this band gets when it has none of its own. */
    public static double defaultHealth(Band band, boolean boss) {
        return Math.min(Species.MAX_HEALTH, band.health() * (boss ? BOSS_HEALTH : 1.0));
    }

    /** The attack damage a creature of this band gets when it has none of its own: none for a vehicle. */
    public static double defaultDamage(Band band, boolean vehicle) {
        return vehicle ? 0 : band.damage();
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
