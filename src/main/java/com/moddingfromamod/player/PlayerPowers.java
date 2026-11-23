package com.moddingfromamod.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>What the child can ask to be, and what that means.</b>
 *
 * <p>Pure and Minecraft-free so the vocabulary can be unit-tested without a game. The tool that
 * applies it is {@link com.moddingfromamod.agent.tools.ModifyPlayerTool}.
 *
 * <p>WHY THIS EXISTS. Measured over 11 player-directed wishes: the agent used a tool
 * whose subject is the child <b>zero</b> times, because there was not one. The same agent, in the
 * same session, answered a creature wish with {@code modify_mob} in four calls and zero failures.
 * It reaches the verbs it has; the cost was entirely the missing one.
 *
 *
 * <p><b>There is deliberately no GameTest for the tool that applies these.</b> One was written and
 * withdrawn: a GameTest's mock player goes into the server's single shared player list, and the
 * tool resolves "the child" as the first player in it — so six concurrent tests each changed
 * somebody else's player, and every FAILING test leaked its player into later batches (cleanup sat
 * inside {@code succeedWhen}, which only runs on success). Four unrelated, correct tests went red
 * because {@code modify_mob} then searched around the wrong plot. That is the shape exactly,
 * committed while fixing. "Which player" is global to a shared server, so the gates here are
 * this class's unit tests plus the Gate-D client probe — which is also the only place the claim
 * that matters can be tested at all: not "the tool works" but "the agent REACHES it".
 *
 * <p>Three things are deliberate:
 *
 * <ul>
 *   <li><b>Named powers, not raw knobs.</b> A child says "make me giant", not "set my scale
 *       attribute to 4". The agent then picks a power rather than inventing numbers, which is what
 *       makes the result the same twice.</li>
 *   <li><b>No attribute is named as a STRING anywhere in this class.</b> The applying tool uses
 *       typed vanilla handles. Measured twice in one matrix, and again two weeks earlier:
 *       the agent writes {@code attribute … minecraft:scale}, which does not exist on this build,
 *       and burns a round trip discovering it is {@code generic.scale}. A tool that never spells an
 *       attribute cannot get it wrong.</li>
 *   <li><b>An unknown power is REFUSED, not approximated.</b> Same rule as {@code apply_effect}: a
 *       silently-substituted power is a wrong answer the child has to notice.</li>
 * </ul>
 */
public final class PlayerPowers {

    /** One potion effect and how strong it is (0 = level I). */
    public record Buff(String effectId, int level) {
    }

    /**
     * A power the child can ask for.
     *
     * @param scale     body size multiplier, or null to leave it alone
     * @param neverDie  survive what would have killed you — see {@code never_die} below
     */
    public record Spec(String id, String blurb, List<String> aliases, List<Buff> buffs,
                       Double scale, boolean neverDie, List<Stat> stats) {

        /** The shape every power had: effects, a size, never-die; no other attribute. */
        public Spec(String id, String blurb, List<String> aliases, List<Buff> buffs, Double scale,
                    boolean neverDie) {
            this(id, blurb, aliases, buffs, scale, neverDie, List.of());
        }
    }

    /**
     * <b>Which vanilla attribute a power sets, as a KIND, never a string.</b>
     *
     * <p>The class rule above holds: the applying tool maps each kind to its typed vanilla handle,
     * so no attribute id is ever spelled here. Every kind exists on both targets (1.21.1 and 1.21.4,
     * checked against both decompiled {@code Attributes} classes, 2026-10-07). Each carries vanilla's
     * own default, which is what taking the power away restores.
     *
     * <p>{@code FALL_DAMAGE}, not {@code SAFE_FALL_DISTANCE}: {@code giant} owns safe-fall (it rides
     * the size), so a fall power on the same attribute would be undone by "make me normal size".
     * Attribute base values survive a death on their own ({@link SurviveDeath}'s note), so a forever
     * attribute power needs no stash.
     */
    public enum StatKind {
        FALL_DAMAGE(1.0),
        KNOCKBACK_RESISTANCE(0.0),
        BLOCK_BREAK_SPEED(1.0),
        WATER_MOVEMENT(0.0);

        private final double vanillaDefault;

        StatKind(double vanillaDefault) {
            this.vanillaDefault = vanillaDefault;
        }

        public double vanillaDefault() {
            return vanillaDefault;
        }
    }

    /** One attribute a power sets, and to what. */
    public record Stat(StatKind kind, double value) {
    }

    /** Vanilla's own clamp on the scale attribute; asking past it is refused rather than silently cut. */
    public static final double MIN_SCALE = 0.2;
    public static final double MAX_SCALE = 8.0;

    private static final Map<String, Spec> POWERS = new LinkedHashMap<>();

    private static void add(Spec s) {
        POWERS.put(s.id(), s);
    }

    static {
        add(new Spec("giant", "towering — four times your size", List.of("huge", "big", "enormous"),
                // Step height and safe-fall come with the size in the applying tool: a four-times
                // child who cannot step onto his own doorstep, and who breaks a leg stepping off
                // it, does not feel giant. He feels broken.
                List.of(), 4.0, false));
        add(new Spec("tiny", "mouse-sized", List.of("small", "little", "shrink"),
                List.of(), 0.35, false));
        add(new Spec("normal_size", "back to your own size", List.of("normal", "unshrink", "ungiant"),
                List.of(), 1.0, false));
        add(new Spec("fast", "running like the wind", List.of("speed", "super_fast", "quick"),
                List.of(new Buff("minecraft:speed", 3)), null, false));
        // SLOW FALLING IS PART OF THE JUMP, not a separate power. The agent paired them unprompted
        // in the measured run, and it is right: a jump boost tall enough to be fun is tall enough
        // to hurt on the way down, and being hurt by your own wish is the thing to avoid.
        add(new Spec("jump", "bouncing high, and landing softly",
                List.of("jump_boost", "high_jump", "bouncy"),
                List.of(new Buff("minecraft:jump_boost", 3),
                        new Buff("minecraft:slow_falling", 0)), null, false));
        add(new Spec("strong", "hitting much harder", List.of("strength", "powerful", "mighty"),
                List.of(new Buff("minecraft:strength", 2)), null, false));
        add(new Spec("tough", "hard to hurt", List.of("armoured", "armored", "resistant"),
                List.of(new Buff("minecraft:resistance", 2)), null, false));
        add(new Spec("fireproof", "fire cannot hurt you", List.of("fire_resistance", "fire_proof"),
                List.of(new Buff("minecraft:fire_resistance", 0)), null, false));
        add(new Spec("water_breathing", "breathing underwater, and seeing down there",
                List.of("underwater", "breathe_underwater", "gills"),
                List.of(new Buff("minecraft:water_breathing", 0),
                        new Buff("minecraft:night_vision", 0)), null, false));
        add(new Spec("night_vision", "seeing in the dark", List.of("see_in_dark", "darkvision"),
                List.of(new Buff("minecraft:night_vision", 0)), null, false));
        add(new Spec("glow", "glowing so everyone can see you", List.of("glowing", "shiny"),
                List.of(new Buff("minecraft:glowing", 0)), null, false));
        // TWO DIFFERENT WISHES, kept apart on purpose.
        //
        // "let me get hurt but NEVER die" is exactly `never_die`: damage still lands and the health
        // bar still drops, and the last hit simply does not finish it. Folding that into
        // `invincible` would answer a wish he did not make — he ASKED to be hurt.
        add(new Spec("never_die", "you still get hurt, but you can never die",
                List.of("cant_die", "unkillable", "immortal", "no_death"),
                List.of(), null, true));
        // what the corpus asked for that had no power, so it was granted by a hand-written
        // datapack with no claim check and nothing to bring it back after a death:
        // "I never take fall damage", "mobs can't knock me back", "my food bar never goes down",
        // "I can swim super fast", "I take double fall damage", and digging fast.
        add(new Spec("no_fall_damage", "falling never hurts you",
                List.of("fallproof", "fall_proof", "no_fall", "soft_landing", "cant_get_fall_damage"),
                List.of(), null, false, List.of(new Stat(StatKind.FALL_DAMAGE, 0.0))));
        add(new Spec("heavy_falls", "falls hurt you twice as much",
                List.of("double_fall_damage", "more_fall_damage", "fall_damage_x2"),
                List.of(), null, false, List.of(new Stat(StatKind.FALL_DAMAGE, 2.0))));
        add(new Spec("no_knockback", "nothing can knock you backwards",
                List.of("knockback_proof", "unpushable", "cant_be_knocked_back", "no_knock_back"),
                List.of(), null, false, List.of(new Stat(StatKind.KNOCKBACK_RESISTANCE, 1.0))));
        // Saturation keeps the food bar full; it covers "sprint without getting hungry" as well.
        add(new Spec("never_hungry", "your food bar never goes down",
                List.of("no_hunger", "not_hungry", "never_hungry_again", "always_full"),
                List.of(new Buff("minecraft:saturation", 0)), null, false));
        add(new Spec("swim_fast", "swimming like a dolphin",
                List.of("fast_swimmer", "super_swimmer", "swim_speed", "swim_faster"),
                List.of(new Buff("minecraft:dolphins_grace", 0)), null, false,
                List.of(new Stat(StatKind.WATER_MOVEMENT, 1.0))));
        add(new Spec("fast_mining", "digging and mining much faster",
                List.of("mine_fast", "fast_digging", "dig_fast", "super_miner"),
                List.of(), null, false, List.of(new Stat(StatKind.BLOCK_BREAK_SPEED, 3.0))));
        add(new Spec("invincible", "nothing can hurt you at all",
                List.of("invinsible", "indestructible", "untouchable"),
                List.of(new Buff("minecraft:resistance", 4),
                        new Buff("minecraft:fire_resistance", 0)), null, true));
    }

    private PlayerPowers() {
    }

    /** Every power id, in the order they are offered. */
    public static List<String> names() {
        return List.copyOf(POWERS.keySet());
    }

    /**
     * The power the child means, by id or by any of its aliases — or null.
     *
     * <p>Spaces and hyphens are normalised to underscores because "water breathing", "water-breathing"
     * and "water_breathing" are the same request typed three ways, and refusing two of them would be
     * a puzzle rather than a guard.
     */
    public static Spec find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = normalise(name);
        Spec direct = POWERS.get(key);
        if (direct != null) {
            return direct;
        }
        for (Spec s : POWERS.values()) {
            for (String alias : s.aliases()) {
                if (normalise(alias).equals(key)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static String normalise(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    /** What to say when the power is not one we know. Lists the real options; never guesses. */
    public static String unknown(String asked) {
        List<String> offered = new ArrayList<>(POWERS.keySet());
        return "I do not know a power called '" + asked + "'. The ones I can give are: "
                + String.join(", ", offered)
                + ". Guessing here would give the child a power he did not ask for, which he then "
                + "has to notice and undo.";
    }


    /**
     * How strong the child asked for, in his own words. Applies to every power, which is why it is
     * an argument rather than a per-power hack: an effect power raises its amplifier and a size
     * power moves further from 1.0.
     *
     * <p>Named rather than numeric, like {@code apply_effect}'s push strengths — a small named scale
     * reads better to a model than a bare 4 and cannot be asked for out of range. Measured need:
     * asked for "super high", the agent called the tool, got jump III, and went back to
     * {@code run_command} to write jump V by hand.
     */
    public enum Level {
        NORMAL(0, 1.0),
        EXTRA(1, 1.35),
        SUPER(2, 1.75);

        private final int bonus;
        private final double sizeFactor;

        Level(int bonus, double sizeFactor) {
            this.bonus = bonus;
            this.sizeFactor = sizeFactor;
        }

        /** Added to an effect power's amplifier. */
        public int bonus() {
            return bonus;
        }

        /** How much further from normal a SIZE power goes. */
        public double stretch(double scale) {
            if (scale >= 1.0) {
                return Math.min(MAX_SCALE, scale * sizeFactor);
            }
            // Smaller means smaller: "super tiny" is nearer zero, not nearer one.
            return Math.max(MIN_SCALE, scale / sizeFactor);
        }
    }

    /** The level the child means, or NORMAL when he did not say. Never refuses — an unknown word here
     * is not worth failing a wish over, unlike an unknown POWER, which would grant the wrong thing. */
    public static Level level(String name) {
        if (name == null || name.isBlank()) {
            return Level.NORMAL;
        }
        String key = normalise(name);
        for (Level l : Level.values()) {
            if (l.name().toLowerCase(Locale.ROOT).equals(key)) {
                return l;
            }
        }
        return switch (key) {
            case "very", "more", "extra_strong", "stronger", "higher", "faster" -> Level.EXTRA;
            case "super_duper", "ultra", "mega", "max", "maximum", "insane", "crazy" -> Level.SUPER;
            default -> Level.NORMAL;
        };
    }

    /** Vanilla's duration for an effect that never runs out ({@code MobEffectInstance.INFINITE_DURATION}). */
    public static final int INFINITE = -1;

    /**
     * Whether an effect of this duration outlives the child's death ({@link SurviveDeath}).
     *
     * <p>Only an infinite one. A power granted "forever" means forever, including after he falls in
     * lava; a thirty-second effect still ends on death, as vanilla has it.
     */
    public static boolean lastsThroughDeath(int durationTicks) {
        return durationTicks == INFINITE;
    }

    /** Whether a scale is inside what vanilla will accept. */
    public static boolean scaleAllowed(double scale) {
        return scale >= MIN_SCALE && scale <= MAX_SCALE;
    }
}
