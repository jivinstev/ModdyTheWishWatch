package com.moddingfromamod.event.shape;

import java.util.Locale;

import com.moddingfromamod.event.EventScale;

/**
 * <b>Something that spins: a tornado, a whirlpool, a hurricane</b> (docs/EVENTS_PLAN.md §16). Pure.
 *
 * <p>One motion, three shapes, five paths, and a size table where SIZE IS BEHAVIOUR: a little one tugs at
 * flowers and chickens, a gigantic one lifts iron golems and tears up a swath — what a vortex can pick up and
 * which blocks it may tear up are rows here, not taste found in play. Every dial has a default chosen from the
 * others ({@link #defaultPath}), so a preset is at most a shape and a material ("it doesn't take a ton
 * of config to get it right").
 */
public final class Vortex {

    private Vortex() {
    }

    /** What it looks like. No shape is named after a disaster (EVENTS_PLAN §1). */
    public enum Shape {
        /** Narrow at the ground, wide at the top: a tornado, a dust devil, a waterspout, a fire whirl. */
        FUNNEL,
        /** A cone spinning DOWN into the surface: a whirlpool, a quicksand pit. */
        DRAIN,
        /** A wide flat ring of spinning arms high up, a calm eye, a storm under it: a hurricane. */
        SPIRAL;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How it moves across the world. */
    public enum Path {
        /** Stays where it was put (a whirlpool). */
        STAY,
        /** A smooth random walk, tethered to where he aimed (a dust devil). */
        WANDER,
        /** A straight swath across his view, through the aimed spot (a big tornado). */
        LINE,
        /** Round and round the aimed spot, or the building it is at. */
        CIRCLE,
        /** Chases him, never closer than its own width. */
        FOLLOW;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What a body weighs, as the vortex sees it: its box's width² × height, so a boat, a minecart and a person
     * are all "person-sized" and a horse is not. Bosses are never lifted, whatever their box says.
     */
    public enum Body {
        /** ≤ 0.3: dropped items, chickens, rabbits, cats, bats. */
        LIGHT(0.3),
        /** ≤ 1.2: sheep, pigs, cows, wolves, villagers, him, boats, minecarts. */
        PERSON(1.2),
        /** ≤ 4: horses, llamas, his rides, a boat with someone in it. */
        BIG(4.0),
        /** ≤ 12: iron golems, ravagers, camels, sniffers. */
        HUGE(12.0),
        /** Too big, or a boss. */
        NEVER(Double.MAX_VALUE);

        public final double upTo;

        Body(double upTo) {
            this.upTo = upTo;
        }

        public static Body of(double width, double height) {
            return ofMass(mass(width, height));
        }

        /** A box's weight to a vortex: width² × height. */
        public static double mass(double width, double height) {
            return Math.max(0, width) * Math.max(0, width) * Math.max(0, height);
        }

        /** A vehicle and what rides it weigh what they weigh TOGETHER: a boat and a chicken, a boat and him. */
        public static Body ofMass(double mass) {
            for (Body b : values()) {
                if (mass <= b.upTo) {
                    return b;
                }
            }
            return NEVER;
        }
    }

    /** What sort of block it may tear up; each size reaches one row further. */
    public enum Loose {
        /** Flowers, grass, crops, saplings, snow layers. */
        PLANT,
        /** Leaves, sand, gravel, snow blocks, glass. */
        LOOSE,
        /** Logs, dirt, grass blocks, fences, torches and small clutter. */
        GROUND,
        /** Anything else breakable that is not a chest or a fluid: his builds (given back when it passes). */
        BUILT,
        /** Never: bedrock, block entities, fluids, air. */
        NEVER
    }

    /**
     * One size's numbers. {@code foot}/{@code top} are the funnel's radius at the ground and at its top;
     * {@code height} its height (a drain's depth is a third of it); {@code speed} blocks a tick along its path;
     * {@code lifts}/{@code tears} the heaviest body and loosest block it can take; {@code tearsPerTick} how many
     * blocks it may tear up in one tick; {@code maxDisplays}/{@code maxDebris} the caps on what it draws.
     */
    public record Size(double foot, double top, int height, int lifeTicks, double speed, Body lifts, Loose tears,
                       int tearsPerTick, int maxDisplays, int maxDebris, int aimAhead, float spinPitch) {

        public boolean canLift(Body b) {
            return b != Body.NEVER && b.ordinal() <= lifts.ordinal();
        }

        public boolean canTear(Loose l) {
            return l != Loose.NEVER && l.ordinal() <= tears.ordinal();
        }

        /**
         * What it may tear up by how it was asked. WRECKING (the default) reaches one row further
         * than the size's own — a big one takes his house, a gigantic one faster — and leaves it torn until undo.
         * SPARING his builds it takes only what the world grew ({@code natural}: plants, leaves, sand, soil, logs),
         * never past the size's own row, and gives it back when it passes.
         */
        public boolean tears(Loose l, boolean wrecks, boolean natural) {
            if (l == Loose.NEVER) {
                return false;
            }
            if (wrecks) {
                return l.ordinal() <= Math.min(tears.ordinal() + 1, Loose.BUILT.ordinal());
            }
            return natural && l != Loose.BUILT && l.ordinal() <= tears.ordinal();
        }
    }

    public static Size size(EventScale s) {
        return switch (s) {
            case LITTLE -> new Size(1.5, 4, 10, 200, 0.10, Body.LIGHT, Loose.PLANT, 1, 60, 12, 12, 1.4f);
            case MEDIUM -> new Size(2.5, 7, 18, 300, 0.15, Body.PERSON, Loose.LOOSE, 2, 110, 24, 18, 1.0f);
            case BIG -> new Size(4, 11, 28, 360, 0.20, Body.BIG, Loose.GROUND, 4, 170, 40, 26, 0.8f);
            case GIGANTIC -> new Size(6, 16, 40, 400, 0.24, Body.HUGE, Loose.BUILT, 8, 240, 64, 36, 0.6f);
        };
    }

    /** The path when his words named none: a whirlpool and a hurricane stay; a small funnel wanders, a big one cuts a swath. */
    public static Path defaultPath(Shape shape, EventScale s) {
        return switch (shape) {
            case DRAIN, SPIRAL -> Path.STAY;
            case FUNNEL -> s == EventScale.LITTLE || s == EventScale.MEDIUM ? Path.WANDER : Path.LINE;
        };
    }

    /** How many funnels: what he said, 1 to 3. */
    public static int funnels(int asked) {
        return Math.max(1, Math.min(3, asked <= 0 ? 1 : asked));
    }

    /** The funnel's radius {@code h} blocks above its foot (0..height): narrow low down, flaring at the top. */
    public static double funnelRadius(Size z, double h) {
        double t = Math.max(0, Math.min(1, h / z.height()));
        return z.foot() + (z.top() - z.foot()) * Math.pow(t, 1.6);
    }

    /** A drain's depth below the surface. */
    public static double drainDepth(Size z) {
        return Math.max(3, z.height() / 3.0);
    }

    /** A drain's radius {@code d} blocks below the surface (0..depth): wide at the top, a hole at the bottom. */
    public static double drainRadius(Size z, double d) {
        double t = Math.max(0, Math.min(1, d / drainDepth(z)));
        return 0.6 + (z.top() - 0.6) * Math.pow(1 - t, 0.8);
    }

    /** A spiral's radius, eye and the height of its arms above the ground. */
    public static double spiralRadius(EventScale s) {
        return s.radius * 0.6;
    }

    public static double spiralEye(EventScale s) {
        return spiralRadius(s) / 4;
    }

    public static double spiralAltitude(EventScale s) {
        return switch (s) {
            case LITTLE -> 12;
            case MEDIUM -> 16;
            case BIG -> 22;
            case GIGANTIC -> 28;
        };
    }

    /** How far from its axis the vortex reaches at all, for finding what it catches. */
    public static double reach(Shape shape, EventScale s) {
        Size z = size(s);
        return switch (shape) {
            case FUNNEL -> z.top() + 2;
            case DRAIN -> z.top() + 2;
            case SPIRAL -> spiralRadius(s);
        };
    }

    /** How tall the whole thing stands above its foot (a drain's rim is at its foot). */
    public static double tall(Shape shape, EventScale s) {
        return switch (shape) {
            case FUNNEL -> size(s).height();
            case DRAIN -> 3;
            case SPIRAL -> spiralAltitude(s) + 3;
        };
    }

    /**
     * How far ahead of him a vortex he did not aim is put: its size's distance, and a hurricane from OUTSIDE its own
     * reach (the first film stood him inside one; the wind blew him off his feet and the camera saw only rain).
     */
    public static int aim(Shape shape, EventScale s) {
        return shape == Shape.SPIRAL ? (int) Math.ceil(spiralRadius(s) + 8) : size(s).aimAhead();
    }

    /** Ring radius when it circles a spot with no building round it. */
    public static double circleRadius(EventScale s) {
        return switch (s) {
            case LITTLE -> 8;
            case MEDIUM -> 12;
            case BIG -> 18;
            case GIGANTIC -> 26;
        };
    }

    /** Ticks it lives: the size's own, or longer when he said so. */
    public static int life(EventScale s, int seconds) {
        return Math.max(size(s).lifeTicks(), seconds * 20);
    }

    public static Shape shape(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        for (Shape v : Shape.values()) {
            if (v.name().equals(t)) {
                return v;
            }
        }
        return null;
    }

    public static Path path(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        for (Path v : Path.values()) {
            if (v.name().equals(t)) {
                return v;
            }
        }
        return null;
    }
}
