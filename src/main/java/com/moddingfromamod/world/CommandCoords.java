package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Where a {@code run_command} line reaches</b> (the reach rule, its hard case) —
 * Minecraft-free, and honest about what it cannot read.
 *
 * <p>{@code run_command} takes any command, so no field list can say where it lands. This reads
 * the positions the vanilla command GRAMMAR exposes and returns every column the command can touch,
 * each with how uncertain it is; {@link BaseReach} then requires all of them inside the caster's
 * island cell. What it reads:
 * <ul>
 *   <li><b>coordinates</b> — absolute ({@code 12}), relative ({@code ~3}) and local ({@code ^2}),
 *       in the slots of every vanilla command that takes a position ({@link #SCAN}: setblock, fill,
 *       clone, fillbiome, summon, tp/teleport, place, spawnpoint, data/item/loot on a block, damage
 *       at, rotate), only the FIRST triple of particle and playsound (the rest are a spread and a
 *       volume, not places), the column pairs of forceload and spreadplayers (with its range);</li>
 *   <li><b>the execution origin</b> — a command acts where it runs, which is the child (or the
 *       wish's aimed spot when he has left), moved by {@code execute positioned|at|as};</li>
 *   <li><b>entity selectors</b> — {@code @e}/{@code @n} reach every loaded island unless bounded by
 *       {@code distance=..N} (or {@code x/z} with {@code dx/dz}); an unbounded one is refused with
 *       the fix. Player selectors ({@code @a @p @r}, names) are {@code PersonSelectors}' job, which
 *       already narrows them to the child who wished;</li>
 *   <li><b>world-wide commands</b> ({@link #WORLD_WIDE}) — time, weather, game rules, the world
 *       border and spawn, datapacks and functions change every island at once, and are refused.</li>
 * </ul>
 *
 * <p><b>What it does NOT read, said out loud</b>: a position
 * inside NBT or JSON ({@code data modify entity @s Pos set value [...]}, a {@code Motion} that
 * throws something off the island); where a thing goes AFTER the command (a summoned mob walks, a
 * minecart rolls, TNT flies); a command head it does not know — a modded one, or one Minecraft adds
 * later — is checked for its origin and its selectors only, never for coordinates it may take. So
 * this is "the positions the grammar shows, plus the origin", not full coverage.
 */
public final class CommandCoords {

    private CommandCoords() {
    }

    /** Commands whose every coordinate triple is a place they act on (or read to copy from). */
    static final Set<String> SCAN = Set.of("setblock", "fill", "clone", "fillbiome", "summon", "tp",
            "teleport", "place", "spawnpoint", "data", "item", "loot", "damage", "rotate");
    /** Commands whose first triple is a place and whose later numbers are not. */
    static final Set<String> FIRST_ONLY = Set.of("particle", "playsound");
    /**
     * Commands that change every island at once. Refused on an islands world: there is no "his
     * part" of the time of day. A world may decide some are fine (names it).
     */
    public static final Set<String> WORLD_WIDE = worldWide();

    /** Read off {@link WorldEffect}, so a command an effect covers is world-wide here too. */
    private static Set<String> worldWide() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (WorldEffect e : WorldEffect.values()) {
            out.addAll(e.commands());
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    private static final String OVERWORLD = "minecraft:overworld";
    /** {@code BuildShape.MAX_DIM} — the base margin every anchor keeps (an island grid's margin). */
    static final int MARGIN = 96;
    private static final Pattern NUM = Pattern.compile("-?(\\d+(\\.\\d*)?|\\.\\d+)");
    private static final Pattern REL = Pattern.compile("[~^](-?(\\d+(\\.\\d*)?|\\.\\d+))?");
    private static final Pattern DISTANCE = Pattern.compile("(?:^|[,\\[\\s])distance\\s*=\\s*([^,\\]\\s]*)");
    private static final Pattern ARG = Pattern.compile("(?:^|[,\\[\\s])(x|z|dx|dz)\\s*=\\s*(-?[0-9.]+)");

    /** A place the command touches: a column and a radius of uncertainty around it. */
    public record Area(double x, double z, double radius, String what) {
        /** Measured from his own creature ({@link #HIS_CREATURE}), not from a place. */
        public boolean onHisCreature() {
            return Double.isNaN(x) || Double.isNaN(z);
        }
    }

    /**
     * Where commands run from. {@code known == false} means nothing is known (no child and no aimed
     * spot), and then any relative coordinate is unbounded.
     */
    public record Origin(double x, double z, double slack, boolean known) {
        static Origin unknown() {
            return new Origin(0, 0, 0, false);
        }
    }

    /** Every place a command reaches, or why it cannot be judged (then {@code refusal} is set). */
    public record Result(List<Area> areas, String refusal) {
        static Result refused(String why) {
            return new Result(List.of(), why);
        }
    }

    /** Read one command as it would run from {@code origin}. */
    public static Result analyse(String command, Origin origin) {
        return analyse(command, origin, head -> false);
    }

    /**
     * …on a world whose rules ALLOW some world-wide commands. {@code allowed} is asked of
     * a {@link #WORLD_WIDE} head ("time", "weather", …): true means this world's rules file does
     * not deny what it changes, so it is judged like any command with no coordinates (it reaches
     * nothing on the map). The one-argument form allows none — the original behaviour.
     */
    /**
     * …where some tags are HIS creatures' ({@code ownedTag}). A selector scoped by a positive tag he owns
     * ({@code @e[tag=wishmob_his_wolf]}) is his creatures, wherever they have walked: it is accepted with no bound,
     * and what it reaches is recorded as an {@link Area} at {@link #HIS_CREATURE} (NaN), which a caller treats as
     * "on his creature" rather than as a place. Absolute coordinates under it are still places, and still checked.
     */
    public static Result analyse(String command, Origin origin, Predicate<String> allowed, Predicate<String> ownedTag) {
        Predicate<String> before = OWNED.get();
        OWNED.set(ownedTag == null ? t -> false : ownedTag);
        try {
            return analyse(command, origin, allowed);
        } finally {
            OWNED.set(before);
        }
    }

    /** The origin of a selector that is his own creature: no place, by design ({@link Area#onHisCreature}). */
    public static final Origin HIS_CREATURE = new Origin(Double.NaN, Double.NaN, 0, true);

    private static final ThreadLocal<Predicate<String>> OWNED = ThreadLocal.withInitial(() -> t -> false);

    /** Does this selector name, by a positive tag, one of his own creatures? */
    static boolean isHisCreature(String selector) {
        String low = selector.toLowerCase(Locale.ROOT);
        if (!(low.startsWith("@e[") || low.startsWith("@n["))) {
            return false;
        }
        Predicate<String> owned = OWNED.get();
        for (String part : selector.substring(3, selector.endsWith("]") ? selector.length() - 1 : selector.length()).split(",")) {
            String p = part.strip();
            if (p.startsWith("tag=") && !p.startsWith("tag=!") && owned.test(p.substring(4).strip())) {
                return true;
            }
        }
        return false;
    }

    public static Result analyse(String command, Origin origin, Predicate<String> allowed) {
        List<String> t = tokens(command == null ? "" : command.strip());
        if (!t.isEmpty() && t.get(0).startsWith("/")) {
            t.set(0, t.get(0).substring(1));
        }
        List<Area> areas = new ArrayList<>();
        String why = walk(t, origin, origin, areas, allowed == null ? head -> false : allowed);
        return why != null ? Result.refused(why) : new Result(areas, null);
    }

    /** @return a refusal, or null with {@code areas} filled. */
    private static String walk(List<String> t, Origin origin, Origin executor, List<Area> areas,
                               Predicate<String> allowed) {
        if (t.isEmpty()) {
            return null;
        }
        String head = t.get(0).toLowerCase(Locale.ROOT);
        if (head.contains(":")) {
            head = head.substring(head.indexOf(':') + 1);
        }
        if (WORLD_WIDE.contains(head)) {
            if (allowed.test(head)) {
                return null;
            }
            return "'" + head + "' changes the whole world — every child's island at once";
        }
        if ("execute".equals(head)) {
            return execute(t, origin, executor, areas, allowed);
        }
        String shared = sharedScreen(head, t);
        if (shared != null) {
            return shared;
        }
        // A command acts where it runs, whatever its arguments say.
        String o = originArea(origin, areas, "where the command runs");
        if (o != null) {
            return o;
        }
        String sel = selectors(t, origin, executor, areas);
        if (sel != null) {
            return sel;
        }
        if (SCAN.contains(head)) {
            return triples(t, 1, origin, areas, false, head);
        }
        if (FIRST_ONLY.contains(head)) {
            return triples(t, 1, origin, areas, true, head);
        }
        if ("forceload".equals(head)) {
            return pairs(t, 2, origin, areas, 0, head);
        }
        if ("spreadplayers".equals(head) && t.size() >= 5) {
            double range = number(t.get(4));
            return pairs(t, 1, origin, areas, Double.isNaN(range) ? 0 : range, head);
        }
        return null;
    }

    /**
     * <b>What every player on the world sees, which no coordinate can fence</b>. A scoreboard display
     * slot (the sidebar, the tab list, the name tag) is one per world, and a team's name and colour show on
     * every child's tab list — so on a world that keeps bases private, one child's sidebar or team would be on
     * every other child's screen. Refused: {@code scoreboard objectives setdisplay} into any slot, and a
     * {@code team join} that could put a PLAYER in a team. Kept: objectives and scores for his own game, and
     * teams for his own creatures ({@code team join blue @e[tag=his_wolf]} — a glow colour is a team colour).
     * {@code @s} is refused too: after {@code run_command} it is him, and an {@code execute as} chain is not
     * followed here, so the tag is the shape that says "his creatures". Only reached where base reach is on.
     */
    static String sharedScreen(String head, List<String> t) {
        if ("scoreboard".equals(head) && t.size() >= 3 && "objectives".equalsIgnoreCase(t.get(1))
                && "setdisplay".equalsIgnoreCase(t.get(2))) {
            return "a scoreboard display slot (the sidebar, the tab list, below names) shows on EVERY player's "
                    + "screen on this world, not just his. Keep the score for his game with `scoreboard players` "
                    + "and show it to him alone with `title @s actionbar` or a `bossbar` whose players are @s";
        }
        if ("team".equals(head) && t.size() >= 2 && "join".equalsIgnoreCase(t.get(1))) {
            List<String> members = t.size() > 3 ? t.subList(3, t.size()) : List.of("@s");
            for (String m : members) {
                if (!creaturesOnly(m)) {
                    return "`team join` would put a player (" + m + ") in a team, and a team's name shows on every "
                            + "player's tab list on this world. A team is fine for his own creatures: "
                            + "`team join <team> @e[tag=<his creature's tag>]` (that is how a glow colour is set)";
                }
            }
        }
        return null;
    }

    /** An entity selector that cannot pick a player: a positive tag, or a type that is not a player. */
    private static boolean creaturesOnly(String sel) {
        String s = sel.toLowerCase(Locale.ROOT);
        if (!(s.startsWith("@e[") || s.startsWith("@n["))) {
            return false;
        }
        for (String part : s.substring(3, s.endsWith("]") ? s.length() - 1 : s.length()).split(",")) {
            String p = part.strip();
            if (p.startsWith("tag=") && !p.startsWith("tag=!") && p.length() > 4) {
                return true;
            }
            if (p.startsWith("type=") && !p.startsWith("type=!") && !p.equals("type=player")
                    && !p.equals("type=minecraft:player") && !p.startsWith("type=#")) {
                return true;
            }
        }
        return false;
    }

    private static String execute(List<String> t, Origin origin, Origin executor, List<Area> areas,
                                  Predicate<String> allowed) {
        Origin here = origin;
        Origin who = executor;
        for (int i = 1; i < t.size(); i++) {
            String k = t.get(i).toLowerCase(Locale.ROOT);
            switch (k) {
                case "run" -> {
                    return walk(new ArrayList<>(t.subList(i + 1, t.size())), here, who, areas, allowed);
                }
                case "in" -> {
                    String dim = i + 1 < t.size() ? t.get(i + 1) : "";
                    if (!dim.equals(OVERWORLD) && !dim.equals("overworld")) {
                        return "it runs in " + dim + ", and the islands are in the overworld";
                    }
                    i++;
                }
                case "at", "as" -> {
                    String sel = i + 1 < t.size() ? t.get(i + 1) : "";
                    Origin moved = selectorOrigin(sel, here, who, origin);
                    if (!moved.known()) {
                        return unbounded(sel);
                    }
                    if ("at".equals(k)) {
                        here = moved;
                    } else {
                        who = moved;
                    }
                    i++;
                }
                case "positioned" -> {
                    String next = i + 1 < t.size() ? t.get(i + 1).toLowerCase(Locale.ROOT) : "";
                    if ("as".equals(next)) {
                        String sel = i + 2 < t.size() ? t.get(i + 2) : "";
                        here = selectorOrigin(sel, here, who, origin);
                        if (!here.known()) {
                            return unbounded(sel);
                        }
                        i += 2;
                    } else if ("over".equals(next)) {
                        i += 2;
                    } else if (isTriple(t, i + 1)) {
                        here = resolve(t, i + 1, here);
                        i += 3;
                    }
                }
                case "store" -> {
                    // store result|success block <pos> …: a WRITE to that block.
                    int b = i + 2;
                    if (b < t.size() && "block".equalsIgnoreCase(t.get(b)) && isTriple(t, b + 1)) {
                        Origin at = resolve(t, b + 1, here);
                        if (!at.known()) {
                            return "it stores into a block whose place cannot be told";
                        }
                        areas.add(new Area(at.x(), at.z(), at.slack(), "execute store block"));
                        i = b + 3;
                    }
                }
                default -> {
                    if (k.startsWith("@e") || k.startsWith("@n")) {
                        // a condition's selector (if entity @e…) reads, never writes: not a reach
                    }
                }
            }
        }
        return null;
    }

    /** Where a selector's entities are, as an origin. Players are the child (PersonSelectors). */
    private static Origin selectorOrigin(String sel, Origin here, Origin who, Origin child) {
        String s = sel.toLowerCase(Locale.ROOT);
        if (s.startsWith("@s")) {
            return who;
        }
        if ((s.startsWith("@e") || s.startsWith("@n")) && isHisCreature(sel)) {
            return HIS_CREATURE;
        }
        if (s.startsWith("@e") || s.startsWith("@n")) {
            double[] b = bound(sel);
            if (b == null || !here.known()) {
                return Origin.unknown();
            }
            return b.length == 1 ? new Origin(here.x(), here.z(), here.slack() + b[0], true)
                    : new Origin(b[0], b[1], b[2], true);
        }
        return child;           // @a @p @r and names: a player, narrowed to the wisher
    }

    /** Refuse an unbounded entity selector; record a bounded one's reach. */
    private static String selectors(List<String> t, Origin origin, Origin executor, List<Area> areas) {
        for (int i = 1; i < t.size(); i++) {
            String s = t.get(i);
            String low = s.toLowerCase(Locale.ROOT);
            if (low.startsWith("@s")) {
                // @s is whoever runs it — after `execute as`, not necessarily the child.
                if (!executor.known()) {
                    return "'@s' is an entity whose place cannot be told";
                }
                areas.add(new Area(executor.x(), executor.z(), executor.slack(), "@s"));
                continue;
            }
            if (!(low.startsWith("@e") || low.startsWith("@n"))) {
                continue;
            }
            if (isHisCreature(s)) {
                areas.add(new Area(Double.NaN, Double.NaN, 0, s + " (his creature)"));
                continue;
            }
            double[] b = bound(s);
            if (b == null) {
                return unbounded(s);
            }
            if (b.length == 1) {
                if (!origin.known()) {
                    return "'" + s + "' is measured from a place that cannot be told";
                }
                areas.add(new Area(origin.x(), origin.z(), origin.slack() + b[0], s));
            } else {
                areas.add(new Area(b[0], b[1], b[2], s));
            }
        }
        return null;
    }

    private static String unbounded(String selector) {
        return "'" + selector + "' picks entities on EVERY island — add distance=..N (N at most "
                + MARGIN + ") so it only reaches his own";
    }

    /** {@code {radius}} for distance=..N, {@code {cx, cz, r}} for an x/z box, or null (unbounded). */
    static double[] bound(String selector) {
        int open = selector.indexOf('[');
        if (open < 0) {
            return null;
        }
        String args = selector.substring(open);
        Matcher d = DISTANCE.matcher(args);
        if (d.find()) {
            // distance=5, distance=..5, distance=2..5 are bounded; distance=3.. is not.
            String v = d.group(1);
            String upper = v.contains("..") ? v.substring(v.indexOf("..") + 2) : v;
            try {
                return upper.isEmpty() ? null : new double[]{Double.parseDouble(upper)};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Double x = null;
        Double z = null;
        Double dx = null;
        Double dz = null;
        Matcher a = ARG.matcher(args);
        while (a.find()) {
            double v;
            try {
                v = Double.parseDouble(a.group(2));
            } catch (NumberFormatException e) {
                return null;
            }
            switch (a.group(1)) {
                case "x" -> x = v;
                case "z" -> z = v;
                case "dx" -> dx = v;
                default -> dz = v;
            }
        }
        if (x != null && z != null && dx != null && dz != null) {
            double hx = Math.abs(dx) / 2;
            double hz = Math.abs(dz) / 2;
            return new double[]{x + dx / 2, z + dz / 2, Math.max(hx, hz) + 1};
        }
        return null;
    }

    private static String originArea(Origin origin, List<Area> areas, String what) {
        if (!origin.known()) {
            return "it runs at a place that cannot be told (nobody is in the world and the wish has "
                    + "no aimed spot)";
        }
        areas.add(new Area(origin.x(), origin.z(), origin.slack(), what));
        return null;
    }

    private static String triples(List<String> t, int from, Origin origin, List<Area> areas,
                                  boolean firstOnly, String head) {
        int i = from;
        while (i + 2 < t.size()) {
            if (isTriple(t, i)) {
                Origin at = resolve(t, i, origin);
                if (!at.known()) {
                    return "'" + head + "' names a relative place, measured from a place that cannot be told";
                }
                areas.add(new Area(at.x(), at.z(), at.slack(),
                        head + " " + t.get(i) + " " + t.get(i + 1) + " " + t.get(i + 2)));
                if (firstOnly) {
                    return null;
                }
                i += 3;
            } else {
                i++;
            }
        }
        return null;
    }

    private static String pairs(List<String> t, int from, Origin origin, List<Area> areas,
                                double radius, String head) {
        int i = from;
        while (i + 1 < t.size()) {
            if (isCoord(t.get(i)) && isCoord(t.get(i + 1))) {
                double[] x = axis(t.get(i), origin.x(), origin.slack(), origin.known());
                double[] z = axis(t.get(i + 1), origin.z(), origin.slack(), origin.known());
                if (x == null || z == null) {
                    return "'" + head + "' names a relative place, measured from a place that cannot be told";
                }
                areas.add(new Area(x[0], z[0], Math.max(x[1], z[1]) + radius,
                        head + " " + t.get(i) + " " + t.get(i + 1)));
                if ("spreadplayers".equals(head)) {
                    return null;
                }
                i += 2;
            } else {
                i++;
            }
        }
        return null;
    }

    /** A triple as a place: centre and how uncertain it is, or unknown. */
    private static Origin resolve(List<String> t, int i, Origin origin) {
        String a = t.get(i);
        String b = t.get(i + 1);
        String c = t.get(i + 2);
        if (a.startsWith("^") || b.startsWith("^") || c.startsWith("^")) {
            if (!origin.known()) {
                return Origin.unknown();
            }
            // Local coordinates turn with the facing, which a command line does not say: every
            // direction, as far as the three together can reach.
            double r = Math.abs(offset(a)) + Math.abs(offset(b)) + Math.abs(offset(c));
            return new Origin(origin.x(), origin.z(), origin.slack() + r, true);
        }
        double[] x = axis(a, origin.x(), origin.slack(), origin.known());
        double[] z = axis(c, origin.z(), origin.slack(), origin.known());
        if (x == null || z == null) {
            return Origin.unknown();
        }
        return new Origin(x[0], z[0], Math.max(x[1], z[1]), true);
    }

    /** {@code {value, slack}} of one axis, or null when relative to an unknown origin. */
    private static double[] axis(String tok, double base, double slack, boolean known) {
        if (tok.startsWith("~")) {
            return known ? new double[]{base + offset(tok), slack} : null;
        }
        return new double[]{Double.parseDouble(tok), 0};
    }

    private static double offset(String tok) {
        String rest = tok.substring(1);
        if (rest.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(rest);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double number(String tok) {
        try {
            return Double.parseDouble(tok);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static boolean isCoord(String tok) {
        return NUM.matcher(tok).matches() || REL.matcher(tok).matches();
    }

    private static boolean isTriple(List<String> t, int i) {
        return i + 2 < t.size() && isCoord(t.get(i)) && isCoord(t.get(i + 1)) && isCoord(t.get(i + 2));
    }

    /**
     * Split on whitespace, keeping {@code {…}}, {@code […]} and quoted strings whole — so an NBT
     * compound or a selector with spaces in it is one token.
     */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == '{' || c == '[') {
                depth++;
                cur.append(c);
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
                cur.append(c);
            } else if (Character.isWhitespace(c) && depth == 0) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
