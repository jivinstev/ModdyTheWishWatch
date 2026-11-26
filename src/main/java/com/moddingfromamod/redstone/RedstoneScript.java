package com.moddingfromamod.redstone;

import java.util.List;

/**
 * <b>Mechanisms driven by a datapack.</b> A mechanism is real Minecraft redstone: sticky pistons and torch towers,
 * lamps, dispensers, note blocks and repeaters, built by its module exactly as for a wired one. What the datapack
 * replaces is only the WIRE from a switch to it: each tick it reads every switch (a lever's, button's or plate's own
 * state) and, while a mechanism should be on, keeps a redstone block at its FEED, the module's port a wire would have
 * reached. Everything after the feed is the same redstone, the same proof and the same grader. Pure: this class names
 * things and writes the CBScript.
 *
 * <p><b>One engine per world, not a datapack per mechanism.</b> Every mechanism is driven by the same small datapack,
 * {@value #ENGINE}, installed once; it finds the parts by the naming convention below, so building a mechanism adds
 * markers and never reloads the world's datapacks (measured: a pack per mechanism meant a reload per build, and two
 * builds at once lost each other's packs).
 *
 * <p><b>The naming convention.</b> Every part carries a vanilla marker entity at its block, tagged:
 * <ul>
 *   <li>{@value #ALL}: any part of any Moddy mechanism;</li>
 *   <li>{@value #IN} at a switch (and {@value #TOGGLE} when a press flips it), {@value #OUT} at a feed;</li>
 *   <li>{@code rs.<id>}, and {@code rs.<id>.in.<n>} or {@code rs.<id>.out}: which mechanism (its id in the mechanism
 *       store, e.g. {@code piston_door_2}) and which part, for a person or a datapack of the agent's own;</li>
 * </ul>
 * and holds the mechanism's number in the score {@value #ID}, which is how the engine pairs switches with feeds.
 * A switch's marker says in {@value #ON} whether it is on; a datapack of the agent's own can drive a mechanism by
 * setting {@code rs_on} on a marker it tags {@value #IN} with the same {@value #ID}. No coordinates anywhere, and plain
 * vanilla commands, so mechanisms keep working with the mod gone.
 */
public final class RedstoneScript {

    /** The engine's datapack (feature) id. */
    public static final String ENGINE = "moddy_redstone";
    public static final String ALL = "moddy_rs";
    public static final String IN = "rs.in";
    public static final String OUT = "rs.out";
    public static final String TOGGLE = "rs.toggle";
    /** An input the engine does not read from a block: a logic rule (or a datapack of the agent's own) sets its rs_on. */
    public static final String SCRIPT = "rs.script";
    /** Scores: the mechanism's number, and a switch's state (now, last tick, toggled state, effective on). */
    public static final String ID = "rs_id", NOW = "rs_now", WAS = "rs_was", STATE = "rs_state", ON = "rs_on";
    public static final List<String> OBJECTIVES = List.of(ID, NOW, WAS, STATE, ON);
    /** Set to 1 by every tick of the engine: it is running. */
    public static final String ALIVE = "#alive";

    private RedstoneScript() {
    }

    public static String mechTag(String id) {
        return "rs." + id;
    }

    public static String inTag(String id, int n) {
        return "rs." + id + ".in." + n;
    }

    public static String outTag(String id) {
        return "rs." + id + ".out";
    }

    /** The switch tests, each true at a switch's marker while that switch is on. */
    static final List<String> ON_TESTS = List.of(
            "if block ~ ~ ~ minecraft:lever[powered=true]",
            "if block ~ ~ ~ #minecraft:buttons[powered=true]",
            "if block ~ ~ ~ #minecraft:pressure_plates[powered=true]",
            "if block ~ ~ ~ minecraft:light_weighted_pressure_plate unless block ~ ~ ~ "
                    + "minecraft:light_weighted_pressure_plate[power=0]",
            "if block ~ ~ ~ minecraft:heavy_weighted_pressure_plate unless block ~ ~ ~ "
                    + "minecraft:heavy_weighted_pressure_plate[power=0]",
            "if block ~ ~ ~ minecraft:tripwire_hook[powered=true]");

    /**
     * The engine. Each tick: read every switch into {@value #NOW}; a toggle flips {@value #STATE} on each fresh press;
     * {@value #ON} is the state for a toggle and NOW for anything else (a datapack of the agent's own may set ON on its
     * own marker instead); then each feed holds a redstone block while any switch with its number is on. It only ever
     * CHANGES a feed, so nothing updates while nothing changes.
     */
    public static String engine() {
        String in = "@e[type=minecraft:marker,tag=" + IN + ",tag=!" + SCRIPT + "]";
        String plain = "@e[type=minecraft:marker,tag=" + IN + ",tag=!" + TOGGLE + ",tag=!" + SCRIPT + "]";
        String tog = "@e[type=minecraft:marker,tag=" + IN + ",tag=" + TOGGLE + "]";
        StringBuilder b = new StringBuilder();
        b.append("# Moddy's redstone engine: every mechanism's switches power its own redstone (RedstoneScript).\n");
        b.append("reset\n");
        OBJECTIVES.forEach(o -> b.append("    /scoreboard objectives add ").append(o).append(" dummy\n"));
        b.append("end\n\n");
        b.append("function feed()\n");
        b.append("    /scoreboard players operation #cur ").append(ID).append(" = @s ").append(ID).append('\n');
        b.append("    /scoreboard players set #on ").append(ON).append(" 0\n");
        b.append("    /execute as @e[type=minecraft:marker,tag=").append(IN).append(",scores={").append(ON)
                .append("=1}] if score @s ").append(ID).append(" = #cur ").append(ID)
                .append(" run scoreboard players set #on ").append(ON).append(" 1\n");
        b.append("    /execute if score #on ").append(ON)
                .append(" matches 1 if block ~ ~ ~ minecraft:air run setblock ~ ~ ~ minecraft:redstone_block\n");
        b.append("    /execute if score #on ").append(ON)
                .append(" matches 0 if block ~ ~ ~ minecraft:redstone_block run setblock ~ ~ ~ minecraft:air\n");
        b.append("end\n\n");
        b.append("clock tick\n");
        b.append("    /scoreboard players set ").append(ALIVE).append(' ').append(ON).append(" 1\n");
        b.append("    /execute as ").append(in).append(" run scoreboard players set @s ").append(NOW).append(" 0\n");
        for (String t : ON_TESTS) {
            b.append("    /execute as ").append(in).append(" at @s ").append(t).append(" run scoreboard players set @s ")
                    .append(NOW).append(" 1\n");
        }
        b.append("    /execute as ").append(tog).append(" if score @s ").append(NOW).append(" matches 1 unless score @s ")
                .append(WAS).append(" matches 1 run scoreboard players add @s ").append(STATE).append(" 1\n");
        b.append("    /execute as ").append(tog).append(" if score @s ").append(STATE)
                .append(" matches 2.. run scoreboard players set @s ").append(STATE).append(" 0\n");
        b.append("    /execute as ").append(in).append(" run scoreboard players operation @s ").append(WAS).append(" = @s ")
                .append(NOW).append('\n');
        b.append("    /execute as ").append(tog).append(" run scoreboard players operation @s ").append(ON).append(" = @s ")
                .append(STATE).append('\n');
        b.append("    /execute as ").append(plain).append(" run scoreboard players operation @s ").append(ON)
                .append(" = @s ").append(NOW).append('\n');
        b.append("    /execute as @e[type=minecraft:marker,tag=").append(OUT).append("] at @s run function ")
                .append(ENGINE).append(":feed\n");
        b.append("end\n");
        return b.toString();
    }
}
