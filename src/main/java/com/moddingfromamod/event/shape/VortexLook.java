package com.moddingfromamod.event.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.event.EventScale;

/**
 * <b>What a vortex is drawn with</b> (EVENTS_PLAN §16.3, §16.4). Pure.
 *
 * <p>Its walls are display blocks laid on rings up (or down) an axis; each piece is a tilted slab tangent to its
 * ring, turning about the axis faster near the foot. The layout is here so its counts are capped and asserted
 * ({@code VortexTest}) rather than discovered on a server. The {@link Family} table is the presets' materials, and
 * the one place a material decides a particle, a sound and a default effect.
 */
public final class VortexLook {

    private VortexLook() {
    }

    /**
     * One piece of wall: its ring, its height above the foot ({@code y}, negative down a drain), its distance from
     * the axis, its starting angle, its size (along the ring, tall, thick), whether it is drawn in {@code core}
     * rather than {@code material}, and how fast it turns (radians a tick).
     */
    public record Piece(int ring, double y, double radius, double angle0, double along, double tall, double thick,
                        boolean core, double spin, double tilt) {
        /** Its angle at {@code tick}. */
        public double angle(int tick) {
            return angle0 + spin * tick;
        }
    }

    /** Base turn rate at the foot, radians a tick; a 2-tick glide steps at most ~0.5 rad there. */
    public static final double SPIN = 0.24;

    public static List<Piece> pieces(Vortex.Shape shape, EventScale s) {
        return switch (shape) {
            case FUNNEL -> funnel(s);
            case DRAIN -> drain(s);
            case SPIRAL -> spiral(s);
        };
    }

    static List<Piece> funnel(EventScale s) {
        Vortex.Size z = Vortex.size(s);
        int rings = switch (s) {
            case LITTLE -> 6;
            case MEDIUM -> 9;
            case BIG -> 11;
            case GIGANTIC -> 13;
        };
        List<Piece> out = new ArrayList<>();
        double step = z.height() / (double) rings;
        for (int i = 0; i < rings; i++) {
            double y = step * (i + 0.5);
            double r = Vortex.funnelRadius(z, y);
            double along = 1.0 + r * 0.32;
            int n = Math.max(5, Math.min(18, (int) Math.round(2 * Math.PI * r / along)));
            double spin = SPIN * (1.3 - 0.6 * y / z.height());
            for (int k = 0; k < n; k++) {
                double a = 2 * Math.PI * k / n + i * 0.7;
                // Streaks, not a wall: each piece a little in or out, shorter or longer, leaning its own way.
                double j = ((k * 37 + i * 11) % 7) / 6.0 - 0.5;
                out.add(new Piece(i, y + j * step * 0.4, r * (1 + 0.12 * j), a, along * (0.8 + 0.3 * Math.abs(j)),
                        step * (0.55 + 0.3 * Math.abs(j)), 0.3, (k + i) % 3 == 0, spin, 0.25 + 0.3 * j));
            }
        }
        return cap(out, z.maxDisplays());
    }

    static List<Piece> drain(EventScale s) {
        Vortex.Size z = Vortex.size(s);
        double depth = Vortex.drainDepth(z);
        int rings = Math.max(4, (int) Math.round(depth));
        List<Piece> out = new ArrayList<>();
        double step = depth / rings;
        for (int i = 0; i < rings; i++) {
            double d = step * (i + 0.3);
            double r = Vortex.drainRadius(z, d);
            double along = 0.8 + r * 0.3;
            int n = Math.max(4, Math.min(18, (int) Math.round(2 * Math.PI * r / along)));
            double spin = SPIN * (0.7 + 0.6 * d / depth);   // a drain turns FASTER the deeper it goes
            for (int k = 0; k < n; k++) {
                double a = 2 * Math.PI * k / n + i * 0.9;
                out.add(new Piece(i, -d, r, a, along, 0.5, 0.25, (k + i) % 2 == 0, spin, -0.5));
            }
        }
        // The dark hole at its bottom.
        out.add(new Piece(rings, -depth, 0, 0, 1.2, 0.3, 1.2, true, SPIN, 0));
        return cap(out, z.maxDisplays());
    }

    static List<Piece> spiral(EventScale s) {
        Vortex.Size z = Vortex.size(s);
        double reach = Vortex.spiralRadius(s);
        double eye = Vortex.spiralEye(s);
        double alt = Vortex.spiralAltitude(s);
        List<Piece> out = new ArrayList<>();
        // The eye wall: a few rings of grey from the ground to the arms.
        int wallRings = 4;
        for (int i = 0; i < wallRings; i++) {
            double y = alt * (i + 0.5) / wallRings;
            int n = Math.max(6, Math.min(14, (int) Math.round(2 * Math.PI * eye / 2.5)));
            for (int k = 0; k < n; k++) {
                out.add(new Piece(i, y, eye, 2 * Math.PI * k / n + i * 0.5, 2.5, alt / wallRings * 0.9, 0.4,
                        true, SPIN * 0.6, 0.2));
            }
        }
        // Three arms winding out from the eye, high up.
        int arms = 3;
        int per = Math.max(6, (z.maxDisplays() - out.size()) / arms);
        for (int arm = 0; arm < arms; arm++) {
            for (int i = 0; i < per; i++) {
                double t = (i + 0.5) / per;
                double r = eye + (reach - eye) * t;
                double a = arm * 2 * Math.PI / arms + t * Math.PI * 1.6;
                double along = 1.5 + r * 0.18;
                out.add(new Piece(wallRings + arm, alt + Math.sin(t * Math.PI) * 2, r, a, along, 1.2, 1.4 + 2 * t,
                        i % 4 == 0, SPIN * 0.18 * (1.2 - 0.6 * t), 0.0));
            }
        }
        return cap(out, z.maxDisplays());
    }

    private static List<Piece> cap(List<Piece> all, int max) {
        if (all.size() <= max) {
            return List.copyOf(all);
        }
        // Thin evenly, never by dropping the top rings.
        List<Piece> out = new ArrayList<>();
        double stride = all.size() / (double) max;
        for (int i = 0; i < max; i++) {
            out.add(all.get((int) Math.floor(i * stride)));
        }
        return List.copyOf(out);
    }

    // ── sway: a tornado is a rope, not a stack ──────────────────────────────────────────────────────

    /**
     * How far the funnel's axis is bent sideways {@code y} blocks up at {@code tick}: nothing at the foot, most at the
     * top, wandering slowly (the top lags the foot, as a real tornado's does). The first films showed a straight stack
     * of rings that read as a glass lampshade. A drain and a spiral do not sway. Used for the walls, the debris AND
     * what it catches, so everything follows the same bent axis.
     */
    public static double[] sway(Vortex.Shape shape, EventScale s, double y, int tick, double phase) {
        if (shape != Vortex.Shape.FUNNEL || y <= 0) {
            return new double[] {0, 0};
        }
        Vortex.Size z = Vortex.size(s);
        double t = Math.min(1, y / z.height());
        double reach = z.top() * 0.45 * Math.pow(t, 1.5);
        return new double[] {reach * Math.sin(tick * 0.045 + phase + t * 1.3),
                reach * Math.cos(tick * 0.037 + phase * 1.7 + t * 0.9)};
    }

    // ── debris: what it tore up, flying up the wall ─────────────────────────────────────────────────

    /** Where a piece of debris {@code age} ticks old is, relative to the foot, or null once it has been thrown out. */
    public static double[] debris(Vortex.Shape shape, EventScale s, int age, double phase) {
        Vortex.Size z = Vortex.size(s);
        double climb = switch (shape) {
            case FUNNEL -> z.height() / 70.0;
            case DRAIN -> -Vortex.drainDepth(z) / 60.0;
            case SPIRAL -> Vortex.spiralAltitude(s) / 60.0;
        };
        double y = climb * age;
        double r;
        double top;
        switch (shape) {
            case FUNNEL -> {
                r = Vortex.funnelRadius(z, Math.max(0, y)) * 0.85;
                top = z.height() + 2;
            }
            case DRAIN -> {
                r = Vortex.drainRadius(z, -y) * 0.8;
                top = Vortex.drainDepth(z);
            }
            default -> {
                r = Vortex.spiralEye(s) * (1.1 + 0.3 * Math.min(1, age / 60.0));
                top = Vortex.spiralAltitude(s);
            }
        }
        if (Math.abs(y) > top) {
            return null;
        }
        double a = phase + SPIN * 1.6 * age;
        return new double[] {Math.cos(a) * r, y, Math.sin(a) * r};
    }

    // ── the presets' materials ──────────────────────────────────────────────────────────────────────

    /**
     * A material family: the two blocks its walls are drawn in, and what that material means for its particles and
     * what it does by default. Every preset in EVENTS_PLAN §16.4 is one of these on one shape.
     */
    public enum Family {
        WIND("minecraft:white_stained_glass", "minecraft:light_gray_stained_glass"),
        WATER("minecraft:light_blue_stained_glass", "minecraft:blue_stained_glass"),
        FIRE("minecraft:orange_stained_glass", "minecraft:red_stained_glass"),
        SAND("minecraft:sand", "minecraft:smooth_sandstone"),
        SNOW("minecraft:snow_block", "minecraft:powder_snow"),
        LEAVES("minecraft:oak_leaves", "minecraft:azalea_leaves"),
        CANDY("minecraft:pink_wool", "minecraft:magenta_wool"),
        ENDER("minecraft:purple_stained_glass", "minecraft:obsidian"),
        CLOUD("minecraft:white_wool", "minecraft:light_gray_stained_glass"),
        MUD("minecraft:sand", "minecraft:soul_sand"),
        OTHER(null, null);

        public final String material;
        public final String core;

        Family(String material, String core) {
            this.material = material;
            this.core = core;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The family a block id belongs to, by the words in its name (any block, not only the presets'). */
        public static Family of(String blockId) {
            String b = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT);
            if (b.isEmpty()) {
                return WIND;
            }
            for (Family f : values()) {
                if (f != OTHER && (b.equals(f.material) || b.equals(f.core))) {
                    return f == MUD ? SAND : f;
                }
            }
            if (b.contains("water") || b.contains("ice") || b.contains("prismarine") || b.contains("blue")) {
                return WATER;
            }
            if (b.contains("sand") || b.contains("dirt") || b.contains("mud") || b.contains("gravel")) {
                return SAND;
            }
            if (b.contains("fire") || b.contains("magma") || b.contains("lava") || b.contains("orange")
                    || b.contains("red") || b.contains("blaze")) {
                return FIRE;
            }
            if (b.contains("snow")) {
                return SNOW;
            }
            if (b.contains("leaves") || b.contains("moss") || b.contains("vine")) {
                return LEAVES;
            }
            if (b.contains("pink") || b.contains("magenta") || b.contains("cake")) {
                return CANDY;
            }
            if (b.contains("purple") || b.contains("obsidian") || b.contains("end") || b.contains("chorus")) {
                return ENDER;
            }
            if (b.contains("glass") || b.contains("white") || b.contains("gray") || b.contains("grey")) {
                return WIND;
            }
            return OTHER;
        }
    }

    // ── how a vortex is drawn ──────────────────────────────────────────────────────

    /**
     * How a vortex's walls are drawn. A player said the first funnels "look too much like glass", so each look was
     * picked, per material, from six prototypes filmed side by side (EVENTS_PLAN §16.7):
     * <ul>
     * <li>{@code SOLID} — the material's own blocks (stained glass for wind, water, fire and ender), as vortexes first shipped.</li>
     * <li>{@code MIST} — see-through bands of the material's colour, two-sided, plus a light stream of its particles.</li>
     * <li>{@code STREAKS} — thin slivers whipping round, like the speed lines in a cartoon.</li>
     * </ul>
     * Which one is {@link #forVortex}: a table, chosen by what the vortex IS, so neither the child nor the Builder
     * picks it. The Magician may name one when his words do ("a sparkly tornado"): the design's {@code look}.
     */
    public enum Style {
        SOLID, MIST, STREAKS;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean ribbons() {
            return this == MIST;
        }

        public boolean strands() {
            return this == MIST;
        }
    }

    /**
     * The look a vortex is drawn in when nothing names one (from the filmed comparison): air,
     * dust, snow and candy floss are MIST (moving air, a sandy haze, flurries, spun sugar); a fire whirl is STREAKS
     * ("fire is supposed to be really dynamic"); a waterspout, a leaf whirlwind and an ender vortex keep their SOLID
     * blocks (water already looks like glass, leaves read as leaves, glass and obsidian are the most dramatic). A
     * whirlpool is MIST — it looked best from under the water — and a hurricane stays SOLID (clouds should look solid).
     * A block his words named that is no preset's ({@code OTHER}) is drawn in that block, SOLID.
     */
    public static Style forVortex(Vortex.Shape shape, Family f) {
        return switch (shape) {
            case FUNNEL -> switch (f) {
                case WIND, CLOUD, SAND, MUD, SNOW, CANDY -> Style.MIST;
                case FIRE -> Style.STREAKS;
                default -> Style.SOLID;
            };
            case DRAIN -> f == Family.WATER ? Style.MIST : Style.SOLID;
            case SPIRAL -> Style.SOLID;
        };
    }

    /**
     * One piece reshaped for a style: mist bands are long thin bands (a streak of moving air), streaks thinner still.
     * Solid keeps the piece as it is.
     */
    public static Piece styled(Piece p, Style style) {
        return switch (style) {
            case STREAKS -> new Piece(p.ring(), p.y(), p.radius() * 1.05, p.angle0(), p.along() * 1.5,
                    Math.max(0.08, p.tall() * 0.12), 0.06, p.core(), p.spin(), p.tilt() * 0.6);
            case MIST -> new Piece(p.ring(), p.y(), p.radius(), p.angle0(), p.along() * 1.7,
                    Math.max(0.25, p.tall() * 0.4), 0, p.core(), p.spin(), p.tilt() * 0.7);
            default -> p;
        };
    }

    /** A family's mist tint (RGB): its main colour and its core's. */
    public static int[] tint(Family f) {
        return switch (f) {
            case WATER -> new int[] {0x9fd2f5, 0x3f86c8};
            case FIRE -> new int[] {0xffa640, 0xd8402a};
            case SAND, MUD -> new int[] {0xe0cb94, 0xb39a62};
            case SNOW -> new int[] {0xffffff, 0xe2eefa};
            case LEAVES -> new int[] {0x6fae45, 0x3f7a2a};
            case CANDY -> new int[] {0xf9b3d6, 0xe070b8};
            case ENDER -> new int[] {0xb07ce8, 0x4a2275};
            case CLOUD -> new int[] {0xffffff, 0xc4c9ce};
            default -> new int[] {0xffffff, 0xdde3e8};
        };
    }

    /** A mist band's colour (ARGB): the family's tint, its see-through amount varying piece to piece so the wall
     * reads as moving air rather than a panel. */
    public static int mistColour(Piece p, Family f) {
        int k = Math.floorMod((int) Math.round(p.angle0() * 100) + p.ring() * 7, 5);
        int[] t = tint(f);
        int base = f == Family.WIND || f == Family.OTHER ? 0x48 : 0x58;
        return (base + (f == Family.WIND || f == Family.OTHER ? 0x10 : 0x12) * k) << 24 | (p.core() ? t[1] : t[0]);
    }

    /** The material a shape is drawn in when his words named none. */
    public static Family defaultFamily(Vortex.Shape shape) {
        return switch (shape) {
            case FUNNEL -> Family.WIND;
            case DRAIN -> Family.WATER;
            case SPIRAL -> Family.CLOUD;
        };
    }
}
