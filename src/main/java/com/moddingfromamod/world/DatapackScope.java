package com.moddingfromamod.world;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A hand-written pack on a world with bases: allowed when it stays on his base or on his creatures</b>
 * — Minecraft-free, so the whole judgement is a Gate A test.
 *
 * <p>Every command in every compiled function is read the way {@code run_command} is ({@link CommandCoords}),
 * from his island's anchor, following {@code execute as/at/positioned}: each place it reaches must be inside his
 * island's cell, or be measured from one of HIS creatures (a selector scoped by a tag he owns). Anything it cannot
 * judge is refused — fail closed:
 * <ul>
 *   <li>a world-wide head this world denies ({@code time}, {@code weather}, {@code gamerule}, {@code difficulty},
 *       {@code worldborder}, {@code advancement}, {@code recipe}…), by the same {@link WorldEffect} rules;</li>
 *   <li>{@code function} or {@code schedule} into another namespace, and {@code datapack} / {@code reload};</li>
 *   <li>{@code data modify|merge entity … Pos|Motion} and a {@code Pos}/{@code Motion} in NBT: a place it cannot
 *       read;</li>
 *   <li>a command head that is not in {@code CommandGuard}'s table (a modded or unknown one), and a macro line
 *       built from variables;</li>
 *   <li>(the harm sweep) a pack that keeps HURTING a player — {@code damage}/harmful
 *       {@code effect} aimed at something that can be a player, an explosion, firework, lightning or fireball
 *       summoned at a player, lava or fire set where a player stands. Hostile mobs ({@code @e[type=zombie,…]})
 *       and a show that hurts nobody are fine.</li>
 * </ul>
 * A refusal names the function and line, and the fix, the way {@code run_command}'s does. Another player NAMED is
 * {@code DatapackReach}'s refusal, on every world, before this runs.
 */
public final class DatapackScope {

    private DatapackScope() {
    }

    /** One line of one compiled function. */
    public record Line(String function, int number, String text) {
    }

    /** The CBScript compiler writes this into every pack's reset function; it is the compiler's, not the wish's. */
    static final Pattern COMPILER_BOILERPLATE = Pattern.compile("gamerule maxCommandChainLength \\d+");

    private static final Set<String> HARMFUL = Set.of("instant_damage", "poison", "wither", "minecraft:instant_damage",
            "minecraft:poison", "minecraft:wither");
    private static final Set<String> BLASTS = Set.of("tnt", "creeper", "end_crystal", "lightning_bolt",
            "firework_rocket", "fireball", "small_fireball", "dragon_fireball", "wither_skull", "tnt_minecart");
    private static final Pattern PLAYER_SEL = Pattern.compile("@([aprs])(?![A-Za-z0-9_])(\\[[^\\]]*\\])?");
    private static final Pattern AS_OR_AT = Pattern.compile("(?:^|\\s)(?:as|at)\\s+(@[aeprsn](?:\\[[^\\]]*\\])?)");

    /**
     * The refusal for this pack on this base, or null when every line stays on his base or his creatures.
     *
     * @param ownNamespace the pack's own namespace: {@code function}/{@code schedule} may call into it
     * @param island       the caster's base (its anchor is where an unpositioned command runs)
     * @param ownedTag     the tags of HIS creatures
     * @param rules        this world's rules (what world-wide heads it allows)
     * @param known        whether a command head is one the table knows ({@code CommandGuard.rule != null})
     */
    public static String refusal(List<Line> lines, String ownNamespace, Base island, Predicate<String> ownedTag,
                                 WorldRules rules, Predicate<String> known) {
        WorldRules in = rules == null ? WorldRules.CLOSED : rules;
        CommandCoords.Origin anchor = new CommandCoords.Origin(island.x() + 0.5, island.z() + 0.5, 0, true);
        for (Line l : lines) {
            String c = l.text() == null ? "" : l.text().strip();
            if (c.isEmpty() || c.startsWith("#") || COMPILER_BOILERPLATE.matcher(c).matches()) {
                continue;
            }
            String why = judge(c, ownNamespace, island, anchor, ownedTag, in, known);
            if (why != null) {
                return "REFUSED — NOT HIS ISLAND: this datapack's function " + l.function() + ", line " + l.number()
                        + " (`" + clip(c) + "`): " + why + ". Nothing was saved. On this world a datapack may only act "
                        + "on his own island (absolute places inside " + island.area().describe() + ", or `execute "
                        + "positioned` there with distance=..N) or on his own creatures (@e[tag=<his creature's "
                        + "tag>]). Change that line and author it again.";
            }
        }
        return null;
    }

    static String judge(String line, String ns, Base island, CommandCoords.Origin anchor, Predicate<String> ownedTag,
                        WorldRules in, Predicate<String> known) {
        String c = line.startsWith("/") ? line.substring(1) : line;
        if (c.startsWith("$")) {
            if (c.contains("$(")) {
                return "a macro line builds its command from variables, which cannot be judged before it runs";
            }
            c = c.substring(1).strip();
        }
        // walk the execute chain for the heads and the executor
        String tail = c;
        String executor = null;
        String at = null;
        for (int depth = 0; depth < 16; depth++) {
            String root = root(tail);
            if (!root.equals("execute")) {
                break;
            }
            int run = tail.toLowerCase(Locale.ROOT).indexOf(" run ");
            String head = run < 0 ? tail : tail.substring(0, run);
            Matcher m = Pattern.compile("(?:^|\\s)(as|at)\\s+(@[aeprsn](?:\\[[^\\]]*\\])?)").matcher(head);
            while (m.find()) {
                if (m.group(1).equals("as") && !m.group(2).startsWith("@s")) {
                    executor = m.group(2);
                } else if (m.group(1).equals("at") && !m.group(2).startsWith("@s")) {
                    at = m.group(2);
                } else if (m.group(1).equals("at")) {
                    at = executor;
                }
            }
            if (head.matches("(?s).*\\bin\\s+(?!minecraft:overworld\\b|overworld\\b)\\S+.*")) {
                return "it runs in another dimension, and the islands are in the overworld";
            }
            if (run < 0) {
                tail = "";
                break;
            }
            tail = tail.substring(run + " run ".length()).strip();
        }
        String root = tail.isEmpty() ? "execute" : root(tail);
        if (!known.test(root)) {
            return "`" + root + "` is not a command a wish can run, so this pack cannot be judged";
        }
        if (root.equals("datapack") || root.equals("reload")) {
            return "`" + root + "` switches datapacks on and off, for every island";
        }
        if (root.equals("function") || root.equals("schedule")) {
            Matcher f = Pattern.compile("([a-z0-9_.-]+):[a-z0-9_./-]+").matcher(tail.toLowerCase(Locale.ROOT));
            if (!f.find() || !f.group(1).equals(ns)) {
                return "`" + root + "` reaches a function outside this pack, which this check never read";
            }
        }
        if (Pattern.compile("(?<![A-Za-z0-9_])(Pos|Motion)(?![A-Za-z0-9_])").matcher(tail).find()
                && (root.equals("data") || root.equals("summon"))) {
            return "a position or a motion inside NBT is a place this check cannot read";
        }
        // In a pack nothing narrows a player selector to him (run_command's PersonSelectors does not run here): a
        // bare @a/@p/@r is every player on every island. Bounded by a distance or a box, it is the players near a
        // place, which CommandCoords then holds to his island.
        Matcher ps = PLAYER_SEL.matcher(c);
        while (ps.find()) {
            String args = ps.group(2) == null ? "" : ps.group(2).toLowerCase(Locale.ROOT);
            if (!ps.group(1).equals("s") && !args.contains("distance=") && !(args.contains("dx=") || args.contains("dz="))) {
                return "'" + ps.group() + "' picks players on EVERY island — add distance=..N from a place on his island";
            }
        }
        String hurt = hurtsAPlayer(root, tail, executor, at, ownedTag);
        if (hurt != null) {
            return hurt;
        }
        // places, by the run_command analysis — function/schedule into its own pack is judged above, so
        // allowed here; every other world-wide head by this world's rules.
        CommandCoords.Result r = CommandCoords.analyse(c, anchor,
                h -> h.equals("function") || h.equals("schedule") || in.allowsCommand(h), ownedTag);
        if (r.refusal() != null) {
            return r.refusal();
        }
        for (CommandCoords.Area a : r.areas()) {
            if (!a.onHisCreature() && !island.area().containsBox(a.x(), a.z(), a.radius())) {
                return "it reaches (" + (int) a.x() + ", " + (int) a.z() + ")" + (a.radius() > 0 ? " ± " + (int) a.radius()
                        : "") + " — " + a.what() + " — which is off his island";
            }
        }
        return null;
    }

    /** a pack that keeps hurting a player. Null when it does not. */
    static String hurtsAPlayer(String root, String tail, String executor, String at, Predicate<String> ownedTag) {
        String[] t = tail.split("\\s+");
        boolean execIsPlayer = executor != null && canBePlayer(executor, ownedTag);
        boolean atIsPlayer = at != null && canBePlayer(at, ownedTag);
        if (root.equals("damage") && t.length >= 2 && target(t[1], execIsPlayer, ownedTag)) {
            return "it damages something that can be a player — him, every time it runs. A pack may hurt monsters "
                    + "(@e[type=zombie,distance=..N]) but never him";
        }
        if (root.equals("effect") && t.length >= 4 && t[1].equalsIgnoreCase("give") && target(t[2], execIsPlayer, ownedTag)
                && HARMFUL.contains(t[3].toLowerCase(Locale.ROOT))) {
            return "it gives " + t[3] + " to something that can be a player — him. Aim it at monsters, never him";
        }
        if (root.equals("summon") && t.length >= 2) {
            String what = t[1].toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
            if (BLASTS.contains(what) && atIsPlayer && relative(t, 2)) {
                return "it sets off " + what + " where a player stands — him, every time it runs. A show at a fixed "
                        + "place on his island is fine";
            }
        }
        if ((root.equals("setblock") || root.equals("fill")) && atIsPlayer
                && Pattern.compile("(?<![a-z_])(lava|fire|soul_fire|magma_block)(?![a-z_])").matcher(tail).find()) {
            return "it puts lava or fire where a player stands — him. Put it at a fixed place on his island instead";
        }
        return null;
    }

    private static boolean target(String sel, boolean execIsPlayer, Predicate<String> ownedTag) {
        return sel.startsWith("@s") ? execIsPlayer : canBePlayer(sel, ownedTag);
    }

    /** Can this selector (or name) pick a player? Not a non-player type, a tag he owns, or a positive other tag. */
    static boolean canBePlayer(String sel, Predicate<String> ownedTag) {
        if (sel == null || sel.isBlank()) {
            return false;
        }
        if (!sel.startsWith("@")) {
            return true;                                   // a name
        }
        if (PLAYER_SEL.matcher(sel).lookingAt() && !sel.startsWith("@s")) {
            return true;                                   // @a @p @r
        }
        if (sel.startsWith("@s")) {
            return false;                                  // decided by the executor, above
        }
        String args = sel.contains("[") ? sel.substring(sel.indexOf('[') + 1, sel.lastIndexOf(']') < 0 ? sel.length()
                : sel.lastIndexOf(']')) : "";
        for (String part : args.split(",")) {
            String p = part.strip().toLowerCase(Locale.ROOT);
            if (p.startsWith("type=") && !p.startsWith("type=!") && !p.endsWith("player")) {
                return false;
            }
            if (p.startsWith("tag=") && !p.startsWith("tag=!") && ownedTag.test(p.substring(4))) {
                return false;
            }
        }
        return true;
    }

    private static boolean relative(String[] t, int from) {
        return t.length <= from || t[from].startsWith("~") || t[from].startsWith("^");
    }

    private static String root(String c) {
        int sp = c.indexOf(' ');
        String first = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
        if (first.startsWith("/")) {
            first = first.substring(1);
        }
        int colon = first.indexOf(':');
        return colon >= 0 ? first.substring(colon + 1) : first;
    }

    private static String clip(String s) {
        return s.length() <= 90 ? s : s.substring(0, 87) + "…";
    }

    @SuppressWarnings("unused")
    private static final Pattern UNUSED = AS_OR_AT;
}
