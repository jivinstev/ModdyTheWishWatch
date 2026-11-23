package com.moddingfromamod.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>A machine you FILL, then it POURS</b> ("machines you fill") — the pure half.
 *
 * <p>The slushie machine, the popcorn machine, the coffee maker: he loads it with ingredients by
 * holding them and pulling the handle, and when it has what it needs, pulling again pours the
 * thing — bigger for more of the main ingredient, and named after the extras that went in. It can
 * pour into his hand or into the chest standing next to it.
 *
 * <p>Everything here is a function of the SPEC, never of a wish: which items go in, how many of the
 * main one it holds, what comes out, and where. No product name appears in this file (an earlier issue's
 * held-out-noun sweep), so two differently named machines are the same code with
 * different arguments.
 *
 * <p><b>It explains itself (contract C18), and the words come from here, not from the agent.</b>
 * Every message a child can see — progress, what is missing, "it takes …", a full chest, no chest,
 * the sneak-pull how-to, the sign's recipe lines — is generated from the same spec the machine runs
 * on, so it cannot be forgotten and cannot drift from what the machine really does. Each refusal
 * names a next action, and nothing names an item id.
 *
 * <p>This class renders CBScript fragments for {@code items/load_and_pour.cbs}. It imports nothing from
 * Minecraft, so every rule — sizes, names, the chest search, the refusals — is unit-tested
 * ({@code MachineFillTest}); the GameTest proves the rendered pack does what these strings say.
 */
public final class MachineFill {

    private MachineFill() {
    }

    /** The most ingredients a machine takes that it NEEDS — the main one plus two more. */
    public static final int MAX_REQUIRED = 3;
    /** The most optional extras (whipped cream, sprinkles) — each one changes the name. */
    public static final int MAX_EXTRAS = 2;
    /** The most of the main ingredient a machine holds. Past this nobody counts the loads. */
    public static final int MAX_MAIN = 8;
    /** A sign line holds about this many characters before it is cut off mid-word. */
    public static final int SIGN_WIDTH = 15;
    /** The slots of one chest half; {@code Items[26]} present means every one is taken. */
    static final int CHEST_SLOTS = 27;

    /**
     * One thing that goes in.
     *
     * @param match the item as a {@code clear} predicate (a vanilla id, or a made item's id plus its
     *              custom-data identity) — the string that finds it in his bag
     * @param name  what he calls it, already safe inside a JSON string
     */
    public record Ingredient(String match, String name) {
    }

    /**
     * The machine's recipe.
     *
     * @param required   what it NEEDS, main ingredient first; the main one fills up to {@code max}
     *                   and sets the size, every other is needed once
     * @param extras     optional ones, each needed at most once, each named in what comes out
     * @param outputGive the give argument for what comes out (no count)
     * @param outputName what the thing that comes out is called, safe inside a JSON string
     * @param renamable  the text that already names the output inside {@code outputGive}, or null
     *                   when the give carries no name of its own (a plain vanilla item)
     * @param max        how many of the main ingredient it holds, 1..{@link #MAX_MAIN}
     * @param toChest    pour into the chest next to it rather than into his hand
     */
    public record Spec(List<Ingredient> required, List<Ingredient> extras, String outputGive,
                       String outputName, String renamable, int max, boolean toChest) {

        public Spec {
            required = List.copyOf(required);
            extras = List.copyOf(extras);
        }

        /** Why this spec cannot be built, or null. Worded for the agent, which fixes it. */
        public String problem() {
            if (required.isEmpty()) {
                return "fill needs at least one thing that goes IN (INGREDIENTS)";
            }
            if (required.size() > MAX_REQUIRED) {
                return "fill takes at most " + MAX_REQUIRED + " INGREDIENTS (the main one first), not "
                        + required.size() + " — a child cannot keep track of more";
            }
            if (extras.size() > MAX_EXTRAS) {
                return "fill takes at most " + MAX_EXTRAS + " EXTRAS, not " + extras.size();
            }
            if (max < 1 || max > MAX_MAIN) {
                return "fill's MAX is how many of the main ingredient it holds, 1 to " + MAX_MAIN
                        + ", not " + max;
            }
            List<String> seen = new ArrayList<>();
            for (Ingredient i : all()) {
                if (seen.contains(i.match())) {
                    // Measured: the agent put ice in EXTRAS as well, to make "more ice = bigger"
                    // count. It already does, from INGREDIENTS: say so where it is refused.
                    return "fill names '" + i.name() + "' twice — every ingredient must be a "
                            + "different thing, or loading one would count as both. If it is there "
                            + "to make it BIGGER, it only has to be FIRST in INGREDIENTS: the first "
                            + "one sets the size by itself, and EXTRAS never do";
                }
                seen.add(i.match());
            }
            return null;
        }

        /** Every slot, in scoreboard order: the required ones, then the extras. */
        public List<Ingredient> all() {
            List<Ingredient> out = new ArrayList<>(required);
            out.addAll(extras);
            return out;
        }

        int cap(int slot) {
            return slot == 0 ? max : 1;
        }
    }

    // ── the starter kit ────────────────────────────────────────────────────────────────────────

    /**
     * The most KINDS of ingredient a kit hands over. Every required one always comes (without them
     * the machine cannot pour at all), so this only ever trims extras: 3 required + 2 extras would be
     * five kinds, and a wish that fills a bag with half a dozen new stacks buries the machine it was for.
     */
    public static final int KIT_KINDS = 4;

    /**
     * Never handed over, whatever the machine takes: things that are rare in play, so a kit of them
     * would turn a wish for a machine into a wish for the rare thing. The machine still TAKES them;
     * he just finds his own, and the message says so. Keyed by bare item id.
     */
    public static final Set<String> NEVER_IN_KIT = Set.of(
            "minecraft:diamond", "minecraft:diamond_block", "minecraft:emerald", "minecraft:emerald_block",
            "minecraft:gold_block", "minecraft:netherite_scrap", "minecraft:netherite_ingot",
            "minecraft:netherite_block", "minecraft:ancient_debris", "minecraft:nether_star",
            "minecraft:dragon_egg", "minecraft:dragon_breath", "minecraft:elytra",
            "minecraft:totem_of_undying", "minecraft:enchanted_golden_apple", "minecraft:beacon",
            "minecraft:heavy_core", "minecraft:trial_key", "minecraft:ominous_trial_key",
            "minecraft:echo_shard", "minecraft:heart_of_the_sea", "minecraft:shulker_shell",
            "minecraft:command_block", "minecraft:spawner", "minecraft:bedrock");

    /** One line of a kit: what to hand over (a give argument) and how many. */
    public record KitPart(String give, int count, String name) {
    }

    /** Whether {@code give} (a give argument, id first) may ever be part of a kit. */
    public static boolean kitAllows(String give) {
        String id = give.strip();
        int cut = id.indexOf('[');
        if (cut >= 0) {
            id = id.substring(0, cut);
        }
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        return !NEVER_IN_KIT.contains(id);
    }

    /**
     * ENOUGH FOR ITS BIGGEST POUR, decided here and never by the agent (an earlier issue's follow-up). A wish
     * for a machine should pour on the first pull, not send him hunting — so the main ingredient
     * comes as a full load ({@code max}, which is exactly the Big size on any machine and the only
     * size on a machine that holds one), and every other ingredient as the one it needs.
     *
     * @param gives the give argument for each of {@link Spec#all()}, in the same order
     * @return what to hand over, in recipe order; every required ingredient that is allowed, then
     *         extras while there is room under {@link #KIT_KINDS}
     */
    public static List<KitPart> kit(Spec s, List<String> gives) {
        List<Ingredient> all = s.all();
        if (gives.size() != all.size()) {
            throw new IllegalArgumentException("one give per ingredient: " + gives.size() + " for " + all.size());
        }
        List<KitPart> out = new ArrayList<>();
        for (int slot = 0; slot < all.size(); slot++) {
            boolean extra = slot >= s.required().size();
            if (extra && out.size() >= KIT_KINDS) {
                break;
            }
            if (kitAllows(gives.get(slot))) {
                out.add(new KitPart(gives.get(slot), s.cap(slot), all.get(slot).name()));
            }
        }
        return out;
    }

    /** The ingredients it needs that the kit leaves out because they are rare — he finds those. */
    public static List<String> kitLeavesOut(Spec s, List<String> gives) {
        List<String> out = new ArrayList<>();
        for (int slot = 0; slot < s.required().size(); slot++) {
            if (!kitAllows(gives.get(slot))) {
                out.add(s.required().get(slot).name());
            }
        }
        return out;
    }

    /** What he is told alongside the kit, or null when there is no kit. Safe inside a JSON string. */
    public static String kitMessage(Spec s, List<String> gives) {
        List<KitPart> k = kit(s, gives);
        List<String> left = kitLeavesOut(s, gives);
        if (k.isEmpty() && left.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (KitPart p : k) {
            names.add(p.count() > 1 ? p.count() + " " + p.name() : p.name());
        }
        String said = k.isEmpty() ? "" : "Here's " + joinAnd(names) + " so you can try it!";
        if (!left.isEmpty()) {
            said += (said.isEmpty() ? "" : " ") + "Find some " + joinAnd(left) + " yourself - it's rare!";
        }
        return said;
    }

    // ── sizes and names ────────────────────────────────────────────────────────────────────────

    /** One size of what comes out: a word, and the range of the main ingredient that makes it. */
    public record Size(String word, int lo, int hi) {
    }

    /**
     * The sizes a machine that holds {@code max} of its main ingredient can pour.
     *
     * <p>A machine that holds one has no sizes at all. Otherwise the top of the range is Big, the
     * bottom is Small and anything between is Medium — 1 small, 3 medium, 5 big for a machine that
     * holds five, which is the rule the slushie machine was built to, and a load in between rounds
     * DOWN so no ingredient is wasted on a size he did not get.
     */
    public static List<Size> sizes(int max) {
        if (max <= 1) {
            return List.of(new Size("", 1, 1));
        }
        if (max == 2) {
            return List.of(new Size("Small", 1, 1), new Size("Big", 2, 2));
        }
        int small = Math.max(1, (max - 1) / 2);
        return List.of(new Size("Small", 1, small), new Size("Medium", small + 1, max - 1),
                new Size("Big", max, max));
    }

    /** What one pour is called: the size, the thing, and the extras that went in. */
    public static String nameFor(String size, String output, List<String> extras) {
        StringBuilder b = new StringBuilder();
        if (!size.isEmpty()) {
            b.append(size).append(' ');
        }
        b.append(output);
        if (!extras.isEmpty()) {
            b.append(" with ").append(joinAnd(extras));
        }
        return b.toString();
    }

    /** {@code a}, {@code a and b}, {@code a, b and c}. */
    static String joinAnd(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and "
                + parts.get(parts.size() - 1);
    }

    /**
     * The give argument with its name replaced — so the cup in his hand says "Big Slushie", not the
     * name of the machine's plain output.
     *
     * <p>A made item already names itself ({@code minecraft:custom_name='{"text":"Slushie",…}'}),
     * and two {@code custom_name} components on one give is a parse error the game reports by
     * handing over nothing — so the existing name is REPLACED, never added to. A plain vanilla item
     * gets one of its own.
     */
    public static String withName(String give, String renamable, String name) {
        // REPLACE WHATEVER NAME IS THERE, not only the one we expect. Measured: a made
        // output with variants is handed over under its FIRST VARIANT's name ("Small Slushie"),
        // so looking for "Slushie" missed it and a second custom_name was prepended — and an item
        // with a repeated component does not parse, so the machine poured nothing at all.
        // 1.21.8: the name is an UNQUOTED text component, {…} to its matching brace; the
        // pre-1.21.5 JSON-in-quotes form is still read, so an old stored give line is renamed too.
        String key = "minecraft:custom_name={";
        String oldKey = "minecraft:custom_name='";
        int at = give.indexOf(key);
        boolean braced = at >= 0;
        if (!braced) {
            at = give.indexOf(oldKey);
        }
        if (at >= 0) {
            int from = at + (braced ? key.length() - 1 : oldKey.length());
            int close = from;
            if (braced) {
                int depth = 0;
                boolean inString = false;
                for (; close < give.length(); close++) {
                    char c = give.charAt(close);
                    if (c == '"' && give.charAt(close - 1) != '\\') {
                        inString = !inString;
                    } else if (!inString && c == '{') {
                        depth++;
                    } else if (!inString && c == '}' && --depth == 0) {
                        close++;
                        break;
                    }
                }
            } else {
                while (close < give.length() && !(give.charAt(close) == '\'' && give.charAt(close - 1) != '\\')) {
                    close++;
                }
            }
            String json = give.substring(from, close);
            String text = "\"text\":\"";
            int t = json.indexOf(text);
            if (t >= 0) {
                int ts = t + text.length();
                int te = ts;
                while (te < json.length() && !(json.charAt(te) == '"' && json.charAt(te - 1) != '\\')) {
                    te++;
                }
                json = json.substring(0, ts) + name + json.substring(te);
                return give.substring(0, from) + json + give.substring(close);
            }
        }
        String own = "minecraft:custom_name={\"text\":\"" + name + "\",\"italic\":false}";
        int open = give.indexOf('[');
        if (open < 0) {
            return give + "[" + own + "]";
        }
        return give.substring(0, open + 1) + own + "," + give.substring(open + 1);
    }


    // ── what the child reads ───────────────────────────────────────────────────────────────────

    /** The sign's second and third lines: what goes in, and what comes out. */
    public static List<String> signLines(Spec s) {
        List<String> in = new ArrayList<>();
        for (Ingredient i : s.required()) {
            in.add(i.name());
        }
        return List.of(fit(String.join(" + ", in).toLowerCase(java.util.Locale.ROOT)),
                fit("→ " + s.outputName()));
    }

    /** Cut to a sign line, with an ellipsis rather than half a word. */
    public static String fit(String line) {
        return line.length() <= SIGN_WIDTH ? line : line.substring(0, SIGN_WIDTH - 1).trim() + "…";
    }

    /** The recipe in one sentence, for the give message and the how-to. */
    public static String recipe(Spec s) {
        List<String> in = new ArrayList<>();
        for (Ingredient i : s.required()) {
            in.add(i.name());
        }
        return joinAnd(in) + " → " + s.outputName();
    }

    /**
     * What this machine really does, for the AGENT (not the child): the recipe, every size with the
     * load that makes it, the add-ins and where it pours. Measured: a machine built with
     * sizes was described to the child as "more ice doesn't make it bigger yet", because nothing the
     * agent was handed said the sizes existed.
     */
    public static String describe(Spec s) {
        StringBuilder b = new StringBuilder(recipe(s));
        List<Size> sizes = sizes(s.max());
        if (sizes.size() > 1) {
            String main = s.required().get(0).name();
            List<String> each = new ArrayList<>();
            for (int i = 0; i < sizes.size(); i++) {
                Size z = sizes.get(i);
                boolean top = i == sizes.size() - 1;
                String load = top ? z.lo() + " or more" : z.lo() == z.hi() ? String.valueOf(z.lo())
                        : z.lo() + "-" + z.hi();
                each.add(z.word() + " (" + load + " " + main + ")");
            }
            b.append(". SIZES, which already work: ").append(String.join(", ", each));
        }
        if (!s.extras().isEmpty()) {
            List<String> x = new ArrayList<>();
            for (Ingredient i : s.extras()) {
                x.add(i.name());
            }
            b.append(". Optional add-ins, named on what it pours: ").append(joinAnd(x));
        }
        b.append(s.toChest() ? ". It pours into a chest beside it." : ". It hands it to him.");
        return b.toString();
    }

    /** The two or three lines a sneak-pull prints: what goes in, how it pours, where it goes. */
    public static List<String> help(Spec s, String machineName) {
        List<String> out = new ArrayList<>();
        List<String> need = new ArrayList<>();
        for (Ingredient i : s.required()) {
            need.add(i.name());
        }
        out.add(machineName + ": hold " + joinAnd(need) + " and pull the handle to load it.");
        String bigger = s.max() > 1 ? " — more " + s.required().get(0).name()
                + " makes a bigger one" : "";
        out.add("When it has them, pull again to pour a " + s.outputName() + bigger + ".");
        if (!s.extras().isEmpty()) {
            List<String> x = new ArrayList<>();
            for (Ingredient i : s.extras()) {
                x.add(i.name());
            }
            out.add("Add " + joinAnd(x).replace(" and ", " or ") + " too, if you like!");
        }
        out.add(s.toChest() ? "It pours into the chest next to it." : "It hands it to you.");
        return out;
    }

    // ── the pack ───────────────────────────────────────────────────────────────────────────────

    static String objective(int slot) {
        return "mfam_fl" + slot;
    }

    /** A tellraw/title JSON text list, from plain parts: {@code [text, colour]} pairs. */
    static String text(String... textColour) {
        StringBuilder b = new StringBuilder("[\"\"");
        for (int i = 0; i < textColour.length; i += 2) {
            b.append(",{\"text\":\"").append(textColour[i]).append("\",\"color\":\"")
             .append(textColour[i + 1]).append("\"}");
        }
        return b.append(']').toString();
    }

    private static final String WHO = "@a[tag=mfam_filler,limit=1]";

    /** The chest search: five places a chest can stand against the machine's base. */
    static final String[] AROUND = {"~1 ~ ~", "~-1 ~ ~", "~ ~ ~1", "~ ~ ~-1", "~ ~-1 ~"};
    static final String[] CONTAINERS = {"minecraft:chest", "minecraft:trapped_chest",
            "minecraft:barrel"};

    /**
     * Every generated fragment {@code fill.cbs} names, keyed by its placeholder.
     *
     * @param machineName what the machine is called, for the how-to
     */
    public static Map<String, String> render(Spec s, String machineName) {
        String problem = s.problem();
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        List<Ingredient> all = s.all();
        Map<String, String> p = new LinkedHashMap<>();

        StringBuilder objectives = new StringBuilder();
        StringBuilder init = new StringBuilder();
        StringBuilder calls = new StringBuilder();
        StringBuilder loads = new StringBuilder();
        for (int slot = 0; slot < all.size(); slot++) {
            String obj = objective(slot);
            objectives.append("    /scoreboard objectives add ").append(obj).append(" dummy\n");
            // An UNSET score matches no range at all, so "holds fewer than five" would be false for
            // a machine that has never been loaded — and nothing would ever go in.
            init.append("    /scoreboard players add @s ").append(obj).append(" 0\n");
            calls.append("    /function $namespace:fill_load").append(slot).append('\n');
            int cap = s.cap(slot);
            loads.append("function fill_load").append(slot).append("()\n")
                 .append("    /scoreboard players set #took mfam_fill 0\n")
                 .append("    /execute if score @s ").append(obj).append(" matches ..")
                 .append(cap - 1).append(" store result score #took mfam_fill run clear ")
                 .append(WHO).append(' ').append(all.get(slot).match()).append(" 1\n")
                 .append("    /execute if score #took mfam_fill matches 1.. run scoreboard players add @s ")
                 .append(obj).append(" 1\n")
                 .append("    /execute if score #took mfam_fill matches 1.. run scoreboard players set #any mfam_fill 1\n")
                 // ONE AT A TIME, AND AGAIN: `clear` takes a literal count, and "as many as it has
                 // room for" is not one — so a load takes one and calls itself until it is full or
                 // he has none left. Bounded by the cap, which is at most MAX_MAIN.
                 .append("    /execute if score #took mfam_fill matches 1.. run function $namespace:fill_load")
                 .append(slot).append('\n')
                 .append("end\n\n");
        }
        p.put("OBJECTIVES", objectives.toString().stripTrailing());
        p.put("INIT", init.toString().stripTrailing());
        p.put("LOAD_CALLS", calls.toString().stripTrailing());
        p.put("LOAD_FUNCS", loads.toString().stripTrailing());

        StringBuilder ready = new StringBuilder();
        for (int slot = 0; slot < s.required().size(); slot++) {
            ready.append(ready.length() == 0 ? "" : " ").append("if score @s ")
                 .append(objective(slot)).append(" matches 1..");
        }
        p.put("READY", ready.toString());
        StringBuilder empty = new StringBuilder();
        for (int slot = 0; slot < all.size(); slot++) {
            empty.append(empty.length() == 0 ? "" : " ").append("if score @s ")
                 .append(objective(slot)).append(" matches 0");
        }
        p.put("EMPTY", empty.toString());

        // PROGRESS, on the action bar after every load: "Ice 3/5 · Sugar 1/1".
        StringBuilder progress = new StringBuilder("[\"\"");
        for (int slot = 0; slot < all.size(); slot++) {
            progress.append(slot == 0 ? "" : ",{\"text\":\"  ·  \",\"color\":\"gray\"}")
                    .append(",{\"text\":\"").append(all.get(slot).name()).append(" \",\"color\":\"aqua\"}")
                    .append(",{\"score\":{\"name\":\"@s\",\"objective\":\"").append(objective(slot))
                    .append("\"},\"color\":\"white\",\"bold\":true}")
                    .append(",{\"text\":\"/").append(s.cap(slot)).append("\",\"color\":\"gray\"}");
        }
        p.put("PROGRESS", progress.append(']').toString());

        String main = all.get(0).name();
        p.put("READY_LINE", text("Ready! ", "green",
                s.max() > 1 ? "Pull again to pour it — or add more " + main + " for a bigger one."
                        : "Pull again to pour it.", "white"));
        p.put("FULL_LINE", "/execute if score @s " + objective(0) + " matches " + s.max()
                + ".. run tellraw " + WHO + " " + text("Full of " + main + "!", "aqua"));

        StringBuilder stillNeeds = new StringBuilder();
        StringBuilder needs = new StringBuilder();
        for (int slot = 0; slot < s.required().size(); slot++) {
            String name = s.required().get(slot).name();
            stillNeeds.append("    /execute if score @s ").append(objective(slot))
                      .append(" matches 0 run tellraw ").append(WHO).append(' ')
                      .append(text("Still needs ", "yellow", name, "white",
                              " — hold some and pull again.", "yellow")).append('\n');
            // "Needs syrup! (has 3 ice)" — what is missing AND what is already in, so he can see
            // the machine did take his ice rather than eating it.
            needs.append("    /execute if score @s ").append(objective(slot))
                 .append(" matches 0 run tellraw ").append(WHO).append(" [\"\",")
                 .append("{\"text\":\"Needs \",\"color\":\"yellow\"},{\"text\":\"").append(name)
                 .append("\",\"color\":\"white\",\"bold\":true},{\"text\":\"! (has \",\"color\":\"yellow\"},")
                 .append("{\"score\":{\"name\":\"@s\",\"objective\":\"").append(objective(0))
                 .append("\"},\"color\":\"white\"},{\"text\":\" ").append(main)
                 .append(") — hold some and pull the handle.\",\"color\":\"yellow\"}]\n");
        }
        p.put("STILL_NEEDS", stillNeeds.toString().stripTrailing());
        p.put("NEEDS", needs.toString().stripTrailing());

        List<String> takes = new ArrayList<>();
        for (Ingredient i : s.required()) {
            takes.add(i.name());
        }
        p.put("EMPTY_LINE", text("It takes ", "yellow", joinAnd(takes), "white",
                "! Hold them and pull the handle.", "yellow"));

        StringBuilder help = new StringBuilder();
        List<String> lines = help(s, machineName);
        for (int i = 0; i < lines.size(); i++) {
            help.append("    /tellraw @s ").append(text(lines.get(i), i == 0 ? "gold" : "white"))
                .append('\n');
        }
        p.put("HELP", help.toString().stripTrailing());

        // WHERE IT GOES. A chest-pouring machine with no chest REFUSES rather than handing it over
        // anyway: he asked for it in a chest, and "not in my pockets, silly!" was a real refinement.
        StringBuilder find = new StringBuilder();
        for (int d = 0; d < AROUND.length; d++) {
            for (String c : CONTAINERS) {
                find.append("    /execute if score #dir mfam_fill matches 0 if block ").append(AROUND[d])
                    .append(' ').append(c).append(" run scoreboard players set #dir mfam_fill ")
                    .append(d + 1).append('\n');
            }
        }
        for (int d = 0; d < AROUND.length; d++) {
            find.append("    /execute if score #dir mfam_fill matches ").append(d + 1)
                .append(" if data block ").append(AROUND[d]).append(" Items[").append(CHEST_SLOTS - 1)
                .append("] run scoreboard players set #full mfam_fill 1\n");
        }
        p.put("FIND_CHEST", find.toString().stripTrailing());

        StringBuilder policy = new StringBuilder();
        if (s.toChest()) {
            policy.append("    /execute if entity @s[type=armor_stand] if score #dir mfam_fill matches 1.. run scoreboard players set #tochest mfam_fill 1\n")
                  .append("    /execute if entity @s[type=armor_stand] if score #dir mfam_fill matches 0 run scoreboard players set #go mfam_fill 0\n")
                  .append("    /execute if entity @s[type=armor_stand] if score #dir mfam_fill matches 0 run tellraw ")
                  .append(WHO).append(' ').append(text("Put a chest next to me for the ", "yellow",
                          s.outputName(), "white", "!", "yellow")).append('\n')
                  .append("    /execute if score #tochest mfam_fill matches 1 if score #full mfam_fill matches 1 run scoreboard players set #go mfam_fill 0\n")
                  .append("    /execute if score #tochest mfam_fill matches 1 if score #full mfam_fill matches 1 run tellraw ")
                  .append(WHO).append(' ').append(text("The chest is full! ", "yellow",
                          "Take something out and pull again.", "white"));
        }
        p.put("CHEST_POLICY", policy.toString().stripTrailing());

        StringBuilder deliver = new StringBuilder();
        for (int d = 0; d < AROUND.length; d++) {
            deliver.append("    /execute if score #dir mfam_fill matches ").append(d + 1)
                   .append(" positioned ").append(AROUND[d]).append(" run function $namespace:fill_slots\n");
        }
        p.put("DELIVER", deliver.toString().stripTrailing());
        StringBuilder slots = new StringBuilder();
        StringBuilder slotFns = new StringBuilder();
        for (int n = 0; n < CHEST_SLOTS; n++) {
            // The staged item leaves the machine's hand the moment it lands, so every slot after
            // the first free one sees an empty hand and does nothing.
            slots.append("    /execute if items entity @s weapon.mainhand * unless data block ~ ~ ~ Items[{Slot:")
                 .append(n).append("b}] run function $namespace:fill_slot").append(n).append('\n');
            slotFns.append("function fill_slot").append(n).append("()\n")
                   .append("    /item replace block ~ ~ ~ container.").append(n)
                   .append(" from entity @s weapon.mainhand\n")
                   .append("    /item replace entity @s weapon.mainhand with minecraft:air\n")
                   .append("end\n\n");
        }
        p.put("SLOTS", slots.toString().stripTrailing());
        p.put("SLOT_FUNCS", slotFns.toString().stripTrailing());

        // EVERY SIZE × EVERY SET OF EXTRAS is its own named pour: a give line has to name the thing
        // literally, so "Big Slushie with Whipped Cream" cannot be assembled at runtime.
        StringBuilder pick = new StringBuilder();
        StringBuilder outs = new StringBuilder();
        List<Size> sizes = sizes(s.max());
        int k = 0;
        int combos = 1 << s.extras().size();
        for (Size size : sizes) {
            for (int mask = 0; mask < combos; mask++) {
                List<String> named = new ArrayList<>();
                StringBuilder cond = new StringBuilder("if score @s ").append(objective(0))
                        .append(" matches ").append(size.lo()).append("..")
                        .append(size == sizes.get(sizes.size() - 1) ? "" : String.valueOf(size.hi()));
                for (int x = 0; x < s.extras().size(); x++) {
                    int slot = s.required().size() + x;
                    boolean in = (mask & (1 << x)) != 0;
                    cond.append(in ? " if" : " unless").append(" score @s ").append(objective(slot))
                        .append(" matches 1..");
                    if (in) {
                        named.add(s.extras().get(x).name());
                    }
                }
                String name = nameFor(size.word(), s.outputName(), named);
                String give = withName(s.outputGive(), s.renamable(), name);
                pick.append("    /execute ").append(cond).append(" run function $namespace:fill_out")
                    .append(k).append('\n');
                outs.append("function fill_out").append(k).append("()\n")
                    .append("    /execute if score #tochest mfam_fill matches 0 run give ").append(WHO)
                    .append(' ').append(give).append(" 1\n")
                    .append("    /execute if score #tochest mfam_fill matches 0 run tellraw ").append(WHO)
                    .append(' ').append(text("Here's your ", "white", name, "green", "!", "white")).append('\n')
                    .append("    /execute if score #tochest mfam_fill matches 1 run item replace entity @s weapon.mainhand with ")
                    .append(give).append(" 1\n")
                    .append("    /execute if score #tochest mfam_fill matches 1 run function $namespace:fill_deliver\n")
                    .append("    /execute if score #tochest mfam_fill matches 1 run tellraw ").append(WHO)
                    .append(' ').append(text(name, "green", " — it's in the chest!", "white")).append('\n')
                    .append("    /item replace entity @s weapon.mainhand with minecraft:air\n")
                    .append("end\n\n");
                k++;
            }
        }
        p.put("PICK", pick.toString().stripTrailing());
        p.put("OUT_FUNCS", outs.toString().stripTrailing());

        StringBuilder reset = new StringBuilder();
        for (int slot = 0; slot < all.size(); slot++) {
            reset.append("    /scoreboard players set @s ").append(objective(slot)).append(" 0\n");
        }
        p.put("EMPTY_IT", reset.toString().stripTrailing());
        return p;
    }
}
