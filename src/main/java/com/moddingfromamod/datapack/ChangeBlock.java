package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>"Use it on a block": the {@code change_block} effect, and the aim it needs.</b>
 *
 * <p>The family of item wishes that act on the block he POINTS AT — a wand that turns blocks into
 * glass, a shovel that turns dirt into grass, a wand that makes blocks disappear. One effect answers
 * them all: turn the block he points at (and, if he asked, the ones around it) from {@code FROM}
 * into {@code BLOCK}, where {@code BLOCK = minecraft:air} is "make it disappear".
 *
 * <p><b>Why a new AIM, {@code block}.</b> Every other aimed effect happens {@code HELD_REACH}
 * blocks ahead of his eyes, which is right for lightning and wrong here: pointing at a wall two
 * blocks away puts that spot INSIDE the wall or past it, so "turn THIS block into glass" would turn
 * some other block, or the air. So a {@code block}-aimed effect walks along his line of sight in
 * quarter-block steps and fires once, at the first block that is not air — a real raycast written
 * as plain commands ({@link #aimLines}), because 1.21.1 and 1.21.4 have no "the block I am looking
 * at" in a command. Pure, like {@link ItemProperties}: which lines, which radius, which filter is a
 * string decision.
 *
 * <p><b>What it may never change.</b> {@code FROM = any} is the block tag
 * {@code moddingfromamod:changeable}: natural ground and building MATERIAL (dirt, sand, stone, logs,
 * planks, leaves, wool, terracotta, bricks, ice, snow…), listed rather than derived. The obvious
 * derivation, "every mineable block", includes chests, furnaces and beds — a wand that turns
 * "anything" into glass would have deleted a chest and everything in it. So a block with something
 * inside it, bedrock, portals and command blocks are never in reach of "anything", and air is never
 * "changed" into glass around what he pointed at.
 */
public final class ChangeBlock {

    private ChangeBlock() {
    }

    /**
     * The effect id in {@code items/catalogue.json}. Since the block side of
     * {@link TurnInto}: {@code change_block} is an alias of {@code turn_into}.
     */
    public static final String EFFECT = TurnInto.EFFECT;

    /**
     * The E1 labels this effect grants ({@code tools/parts-coverage.py} reads this set): the class
     * that implements a behaviour declares which wishes' words it answers.
     */
    public static final Set<String> PARTS = Set.of("transmute_block", "remove_block");

    /** What {@code FROM = any} means: a block tag of every mineable block, shipped in our data. */
    public static final String ANY = "#moddingfromamod:changeable";

    /** What the aim looks THROUGH: air, and the invisible blocks that are air to the eye. */
    public static final String SEE_THROUGH = "#moddingfromamod:see_through";

    /** The largest radius: 3 is a 7×7×7 patch, a big hole or a big patch of glass, not a crater. */
    public static final int MAX_RADIUS = 3;

    /** How far along his line of sight the aim looks, in quarter-block steps (6 blocks). */
    static final int STEPS = 24;

    private static final Pattern TAG = Pattern.compile("#[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** The tag his words name, or null when FROM is a single block (checked by {@link BlockRef}). */
    public static String fromTag(String from) {
        if (from == null) {
            return null;
        }
        String f = from.trim().toLowerCase(Locale.ROOT);
        if (f.equals("any") || f.equals("anything") || f.equals("*")) {
            return ANY;
        }
        return TAG.matcher(f).matches() ? f : null;
    }

    /** The radius as a number, or null when it is not a whole number 0 to {@link #MAX_RADIUS}. */
    public static Integer radius(String r) {
        try {
            int n = Integer.parseInt(r == null ? "" : r.trim());
            return n >= 0 && n <= MAX_RADIUS ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Why this radius is wrong for this shape, or null. Only a thing that RAYCASTS aims at an exact
     * block — held, and on_use_done, which walks the same line of sight ({@link TurnInto#raycasts};
     * fixed this refusing 0 for on_use_done although it aims exactly): a throw lands on a
     * face and a hit lands on a mob, both in the AIR beside the block, so a radius of 0 there
     * would change nothing and say nothing.
     */
    public static String radiusRefusal(String shape, int radius) {
        if (radius == 0 && !TurnInto.raycasts(shape)) {
            return "turn_into's RADIUS is 0, which only works on something HELD — it changes the "
                    + "exact block he points at. A " + shape + " thing lands in the air beside a "
                    + "block, so give it a RADIUS of at least 1 or it will change nothing.";
        }
        return null;
    }

    /**
     * The aim for a held {@code block} effect: step along his line of sight and run
     * {@code function} once, centred in the first block that is not air. Two tags make it fire at
     * most once however many steps follow — needed because the effect may have turned that block
     * into air, and the next step would otherwise fire again one block further on.
     */
    public static List<String> aimLines(String function) {
        List<String> out = new ArrayList<>();
        out.add("/tag @s remove mfam_aimed");
        for (int i = 1; i <= STEPS; i++) {
            String at = "at @s anchored eyes positioned ^ ^ ^" + step(i);
            out.add("/execute if entity @s[tag=!mfam_aimed] " + at
                    + " unless block ~ ~ ~ " + SEE_THROUGH + " run tag @s add mfam_aim_now");
            out.add("/execute if entity @s[tag=mfam_aim_now] " + at
                    + " align xyz positioned ~0.5 ~0.5 ~0.5 run function " + function);
            out.add("/tag @s[tag=mfam_aim_now] add mfam_aimed");
            out.add("/tag @s remove mfam_aim_now");
        }
        out.add("/tag @s remove mfam_aimed");
        return out;
    }

    /** Quarter-block step {@code i}, written without trailing zeros: 0.25, 0.5, … 6. */
    static String step(int i) {
        java.math.BigDecimal d = java.math.BigDecimal.valueOf(i).multiply(java.math.BigDecimal.valueOf(0.25));
        return d.stripTrailingZeros().toPlainString();
    }
}
