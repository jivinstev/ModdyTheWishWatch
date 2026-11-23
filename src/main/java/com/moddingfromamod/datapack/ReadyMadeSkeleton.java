package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A ready-made's CBScript with the player's values taken out</b>: the shape a precompiled pack is keyed
 * on, so a Builder or Apprentice power needs no compiler (and no Python) at run time.
 *
 * <p>A catalogue template ({@code effects/*.cbs}, {@code items/*.cbs}) is filled with values: a creature's tag, a
 * radius, an effect id, a whole vanilla command. Most of them reach the compiled pack <b>as plain text</b>: the
 * CBScript compiler copies a {@code /command} line through, so compiling the template with a SENTINEL where the value
 * goes and putting the value back in the compiled files afterwards gives the same files as compiling the filled
 * template (measured for every template: {@code PrecompiledEquivalenceTest}). Each placeholder is therefore a
 * <b>slot</b> when its value is of a kind the compiler cannot change ({@link #kindOf}, {@link #accepts}), and
 * <b>literal</b> otherwise: a literal value stays in the skeleton, so it is part of the key and a different value is a
 * different precompiled pack (or none, and the compiler is asked).
 *
 * <p>What the compiler DOES change, and why each kind is drawn where it is: a selector inside a value is rebuilt
 * ({@code type=player} becomes {@code minecraft:player}, an {@code @e} for players becomes {@code @a}), a {@code $} is a
 * macro or a define, {@code nbt=}/{@code attribute}/item components are respelt (nbt_case, attribute_ids,
 * component_syntax), a single-quoted SNBT string is unquoted, and a {@code /command} line is stripped at its ends. A
 * value touching any of those is literal.
 *
 * <p>Inside a LITERAL value (a block of commands the tool built, like an item's give line), the values of identity slots
 * (its feature id, its name, its colour) are still replaced by their sentinels, so the same pick made for two players
 * shares one precompiled pack. Pure: no Minecraft.
 */
public final class ReadyMadeSkeleton {

    /** The namespace a skeleton is compiled under; the feature id goes in its place. */
    public static final String NAMESPACE = "zqnamespaceqz";

    /** The pack description a skeleton is compiled with; the real one (JSON-escaped) goes in its place. */
    public static final String DESC = "zqdescqz";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_]+)}");

    /** How a block slot stands in a skeleton's source: a command line of its own. */
    static final String BLOCK_LINE = "    /";

    /**
     * A skeleton: the source with sentinels, each sentinel's value in this make, and which sentinels are BLOCKS (a run
     * of whole command lines, like an item's give line, that stand in the source as one command line).
     */
    public record Skeleton(String source, Map<String, String> slots, Set<String> blocks, Set<String> lines) {

        public Skeleton {
            slots = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(slots));
            blocks = Set.copyOf(blocks);
            lines = Set.copyOf(lines);
        }

        public Skeleton(String source, Map<String, String> slots) {
            this(source, slots, Set.of(), Set.of());
        }

        /** The source this skeleton stands for: every sentinel put back. */
        public String fill() {
            Map<String, String> tokens = new LinkedHashMap<>();
            for (String b : blocks) {
                tokens.put(blockToken(b, slots.get(b)), slots.get(b));
            }
            for (String l : lines) {
                tokens.put("/" + l, slots.get(l));
            }
            tokens.putAll(slots);
            return substitute(source, tokens);
        }

        /**
         * What each sentinel becomes in the COMPILED files: its value, or for a block the lines as the compiler writes
         * them (each stripped, its slash gone, blank lines dropped).
         */
        public Map<String, String> outputValues() {
            Map<String, String> out = new LinkedHashMap<>(slots);
            for (String b : blocks) {
                StringBuilder lines = new StringBuilder();
                for (String l : slots.get(b).split("\n", -1)) {
                    String t = l.strip();
                    if (!t.isEmpty()) {
                        lines.append(lines.length() == 0 ? "" : "\n").append(t.substring(1));
                    }
                }
                out.put(b, lines.toString());
            }
            for (String l : this.lines) {
                out.put(l, slots.get(l).substring(1));
            }
            return out;
        }
    }

    /** What a placeholder's value may be for it to ride through the compiler as text. */
    public enum Kind {
        /** a tag, a key, a feature id: {@code [a-z][a-z0-9_]*} */
        IDENT,
        /** a number */
        NUMBER,
        /** a resource id ({@code minecraft:flame}), no block states */
        RESOURCE,
        /** a whole vanilla command with nothing the compiler rewrites in it */
        COMMAND,
        /** words a player reads, already JSON-safe, with nothing the compiler unquotes or rewrites */
        TEXT,
        /** {@code #RRGGBB} */
        HEX,
        /** whole command lines the compiler copies through unchanged (an item's give line), alone on their line */
        COMMANDS,
        /** never a slot */
        LITERAL
    }

    private ReadyMadeSkeleton() {
    }

    private static final Set<String> IDENTS = Set.of("TAG", "ALLY_TAG", "KEY", "FEATURE", "WORN_OBJECTIVE");
    private static final Set<String> NUMBERS = Set.of("SECONDS", "AMPLIFIER", "RADIUS", "PERIOD_TICKS",
            "BREATH_TICKS", "LASTS_TICKS", "STEP_TICKS", "RAMP_TICKS", "REACH", "DENSITY", "HEIGHT", "PERCENT",
            "COUNT", "MOST", "STRIDE_TENTHS", "ICON", "COLOUR", "LEVEL", "FILL_RADIUS", "RENEW_TICKS", "DURATION",
            "DAMAGE", "GROW_TICKS", "FREEZE_TICKS", "FIRE_TICKS", "FALL_SECONDS", "SCALE", "RATE");
    private static final Set<String> RESOURCES = Set.of("EFFECT", "PARTICLE", "SOUND", "ITEM", "BLOCK", "LOOK",
            "BODY_BASE", "BODY_MID", "BODY_TOP");
    private static final Set<String> BLOCKS = Set.of("GIVES", "KIT", "YIELD");
    private static final Set<String> TEXTS = Set.of("NAME", "HINT", "GIVE_MESSAGE", "SIGN_NAME", "SIGN_IN",
            "SIGN_OUT");
    private static final Set<String> HEXES = Set.of("HEX", "SIGN_COLOUR");

    /** The kind a placeholder's value must be to be a slot. Unknown names are literal: a slot is opted in. */
    public static Kind kindOf(String name) {
        if (IDENTS.contains(name)) {
            return Kind.IDENT;
        }
        if (NUMBERS.contains(name)) {
            return Kind.NUMBER;
        }
        if (RESOURCES.contains(name)) {
            return Kind.RESOURCE;
        }
        if (TEXTS.contains(name)) {
            return Kind.TEXT;
        }
        if (HEXES.contains(name)) {
            return Kind.HEX;
        }
        if (BLOCKS.contains(name)) {
            return Kind.COMMANDS;
        }
        return "COMMAND".equals(name) ? Kind.COMMAND : Kind.LITERAL;
    }

    private static final Pattern IDENT_RE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern NUMBER_RE = Pattern.compile("-?[0-9]{1,9}(\\.[0-9]{1,6})?");
    private static final Pattern RESOURCE_RE = Pattern.compile("([a-z0-9_.\\-]{1,64}:)?[a-z0-9_./\\-]{1,128}");
    private static final Pattern HEX_RE = Pattern.compile("#[0-9A-Fa-f]{6}");
    /** A selector the compiler leaves alone: a bare {@code @s}, {@code @a}, … with no argument list after it. */
    private static final Pattern BARE_SELECTOR = Pattern.compile("@[aeprsn](?![A-Za-z0-9_\\[])");
    /** Words that set off one of the compiler's own rewrites (nbt_case, attribute_ids, component_syntax). */
    private static final List<String> REWRITTEN = List.of("nbt=", "attribute", "dyed_color", "custom_name",
            "item_name", "customname", "lore", "messages", "pages", "enchantments", "_modifiers");

    /** Does {@code value} ride through the compiler unchanged as a {@code kind}? */
    public static boolean accepts(Kind kind, String value) {
        if (value == null || value.isEmpty() || kind == Kind.LITERAL) {
            return false;
        }
        if (kind == Kind.COMMANDS) {
            return commandLines(value);
        }
        // A sentinel inside a value would be put back as something else; ends are stripped off a /command line;
        // a line break ends the command; a $ is a macro or a define.
        if (value.contains("zq") || value.contains("qz") || !value.equals(value.strip())
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('$') >= 0) {
            return false;
        }
        return switch (kind) {
            case IDENT -> IDENT_RE.matcher(value).matches();
            case NUMBER -> NUMBER_RE.matcher(value).matches();
            case RESOURCE -> RESOURCE_RE.matcher(value).matches();
            case HEX -> HEX_RE.matcher(value).matches();
            case COMMAND -> inertCommand(value);
            case TEXT -> value.indexOf('\\') < 0 && value.indexOf('\'') < 0 && value.indexOf('"') < 0
                    && value.indexOf('@') < 0 && value.indexOf('#') < 0 && noRewrite(value);
            case COMMANDS -> commandLines(value);
            case LITERAL -> false;
        };
    }

    /** {@code attribute <who> <id>} and {@code attributes:[…]}: what attribute_ids.py rewrites. */
    private static final Pattern ATTRIBUTE_REWRITE = Pattern.compile(
            "\\battribute\\s|attributes\\s*:\\s*\\[|dyed_color=\\{|(attribute_modifiers|enchantments)=\\{",
            Pattern.CASE_INSENSITIVE);

    /**
     * Whole command lines the compiler writes out as they are: each a {@code /command}, with no {@code $}, no single
     * quote (component_syntax unquotes single-quoted text), no {@code nbt=}, no old-spelling component or attribute
     * id, and only bare selectors.
     */
    private static boolean commandLines(String v) {
        if (v.contains("zq") || v.contains("qz") || v.indexOf('$') >= 0
                || v.indexOf('\r') >= 0 || v.contains("nbt=") || ATTRIBUTE_REWRITE.matcher(v).find()) {
            return false;
        }
        boolean any = false;
        for (String line : v.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.length() < 2 || t.charAt(0) != '/' || !bareSelectorsOnly(t) || !quotesLeftAlone(t)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** A single-quoted name component, or a single quote inside a lore/messages/pages list, is unquoted. */
    private static final Pattern QUOTED_TEXT = Pattern.compile(
            "(?:custom_name|item_name)=\\s*'|\\b(?:CustomName|custom_name)\\s*:\\s*'");
    private static final Pattern TEXT_LIST = Pattern.compile(
            "(?:(?:minecraft:)?lore=|\\b(?:messages|pages|Lore)\\s*:\\s*)\\[");

    /**
     * component_syntax.py unquotes a single-quoted SNBT string in exactly two places: a name component and the items
     * of a lore/messages/pages list. A single quote anywhere else (an item_model, a strings list) it leaves alone.
     */
    private static boolean quotesLeftAlone(String line) {
        if (line.indexOf('\'') < 0) {
            return true;
        }
        if (QUOTED_TEXT.matcher(line).find()) {
            return false;
        }
        Matcher m = TEXT_LIST.matcher(line);
        return !m.find() || line.indexOf('\'', m.start()) < 0;
    }

    private static boolean bareSelectorsOnly(String v) {
        int at = v.indexOf('@');
        while (at >= 0) {
            if (!BARE_SELECTOR.matcher(v).region(at, v.length()).lookingAt()) {
                return false;
            }
            at = v.indexOf('@', at + 1);
        }
        return true;
    }

    private static boolean inertCommand(String v) {
        if (!noRewrite(v) || v.indexOf('\'') >= 0 || v.indexOf('\\') >= 0) {
            return false;
        }
        // Every @ must be a bare selector: one with an argument list is rebuilt by the compiler.
        return bareSelectorsOnly(v);
    }

    private static boolean noRewrite(String v) {
        String low = v.toLowerCase(java.util.Locale.ROOT);
        for (String w : REWRITTEN) {
            if (low.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** The sentinel for a placeholder, scoped (an item's effects each have their own SECONDS). */
    public static String sentinel(String scope, String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT);
        return "zq" + (scope == null || scope.isEmpty() ? "" : scope.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]", "") + "_") + s + "qz";
    }

    /**
     * Collects the slots of one composition (one template, or an item's shape and its effects) before any of it is
     * rendered, so a literal anywhere can have every identity value replaced.
     */
    public static final class Composer {
        private final Map<String, String> slots = new LinkedHashMap<>();
        private final Map<String, Kind> kinds = new LinkedHashMap<>();
        private final Set<String> blocks = new java.util.LinkedHashSet<>();
        private final Set<String> lines = new java.util.LinkedHashSet<>();

        /** Note the slots {@code template} has, filled from {@code values} under {@code scope}. */
        public Composer scan(String template, Map<String, String> values, String scope) {
            Matcher m = PLACEHOLDER.matcher(template);
            while (m.find()) {
                String name = m.group(1);
                String v = values.get(name);
                Kind k = kindOf(name);
                if (k == Kind.COMMANDS && !aloneOnItsLine(template, m.start(), m.end())) {
                    continue;   // a block stands for whole lines: mid-line it is literal
                }
                if (v != null && accepts(k, v)) {
                    if (k == Kind.COMMANDS) {
                        blocks.add(sentinel(scope, name));
                    }
                    String s = sentinel(scope, name);
                    slots.put(s, v);
                    kinds.put(s, k);
                }
            }
            return this;
        }

        /**
         * Note one value as a slot even where no template names it, so it is still taken out of the blocks a tool built
         * (an item's name is in its give line, which its shape template never spells).
         */
        public Composer slot(String scope, String name, String value) {
            Kind k = kindOf(name);
            if (k != Kind.COMMANDS && value != null && accepts(k, value)) {
                String s = sentinel(scope, name);
                slots.put(s, value);
                kinds.put(s, k);
            }
            return this;
        }

        /** Render {@code template} with sentinels in its slots and, in its literals, identity values replaced. */
        public String render(String template, Map<String, String> values, String scope, Set<String> raw) {
            Pattern inner = innerPattern();
            Matcher m = PLACEHOLDER.matcher(template);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String name = m.group(1);
                String v = values.get(name);
                String s = sentinel(scope, name);
                String out;
                if (v == null) {
                    out = m.group(0);
                } else if (v.equals(slots.get(s))
                        && !(blocks.contains(s) && !aloneOnItsLine(template, m.start(), m.end()))) {
                    out = blocks.contains(s) ? blockToken(s, v) : s;
                } else if (raw != null && raw.contains(name)) {
                    out = v;   // already a skeleton (an item's effect bodies)
                } else {
                    out = literal(v, inner, template, m.start(), m.end());
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(out));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        public Skeleton skeleton(String source) {
            return new Skeleton(source, slots, blocks, lines);
        }

        /**
         * A literal a tool built (a machine's pages, a power's helper functions): each WHOLE command line of it the
         * compiler copies through unchanged ({@link #commandLines}) becomes a line slot of its own, so the same
         * structure with other words in it (a snack's name, its ingredients, a count) is the same skeleton; what is
         * left keeps its identity values replaced. The lines stay where they were, so the compiler's line-numbered
         * function names do not move.
         */
        private String literal(String v, Pattern inner, String template, int start, int end) {
            int ls = template.lastIndexOf('\n', start - 1) + 1;
            int le = template.indexOf('\n', end);
            boolean firstWhole = template.substring(ls, start).isBlank();
            boolean lastWhole = template.substring(end, le < 0 ? template.length() : le).isBlank();
            String[] parts = v.split("\n", -1);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                String line = parts[i];
                String t = line.strip();
                boolean whole = (i > 0 || firstWhole) && (i < parts.length - 1 || lastWhole);
                if (i > 0) {
                    out.append('\n');
                }
                if (whole && !t.isEmpty() && commandLines(t)) {
                    String s = "zqline" + (lines.size() + 1) + "qz";
                    lines.add(s);
                    slots.put(s, t);
                    kinds.put(s, Kind.COMMANDS);
                    int at = line.indexOf(t);
                    out.append(line, 0, at).append('/').append(s).append(line.substring(at + t.length()));
                } else {
                    out.append(inner == null ? line : replaceInner(line, inner));
                }
            }
            return out.toString();
        }

        public Map<String, String> slots() {
            return slots;
        }

        /** The identity values worth looking for inside a literal: long enough not to be found by accident. */
        private Pattern innerPattern() {
            List<Map.Entry<String, String>> found = new ArrayList<>();
            for (var e : slots.entrySet()) {
                Kind k = kinds.get(e.getKey());
                int min = k == Kind.NUMBER ? 5 : 3;
                if (k != Kind.COMMAND && k != Kind.COMMANDS && e.getValue().length() >= min) {
                    found.add(e);
                }
            }
            if (found.isEmpty()) {
                return null;
            }
            found.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> -e.getValue().length())
                    .thenComparing(Map.Entry::getKey));
            StringBuilder alt = new StringBuilder();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (var e : found) {
                if (seen.add(e.getValue())) {
                    alt.append(alt.length() == 0 ? "" : "|").append(Pattern.quote(e.getValue()));
                }
            }
            return Pattern.compile(alt.toString());
        }

        private String replaceInner(String v, Pattern inner) {
            Matcher m = inner.matcher(v);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                // Never right after type=: the compiler puts minecraft: in front of a type with no namespace, so a
                // sentinel there would come back as minecraft:minecraft:… for a value that had one.
                int st = m.start();
                boolean typed = v.startsWith("type=", Math.max(0, st - 5)) || v.startsWith("type=!", Math.max(0, st - 6));
                m.appendReplacement(sb, Matcher.quoteReplacement(typed ? m.group() : sentinelFor(m.group())));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        /** The first sentinel (in slot order of length, then name) holding {@code value}. */
        private String sentinelFor(String value) {
            String best = null;
            for (var e : slots.entrySet()) {
                if (e.getValue().equals(value) && (best == null || e.getKey().compareTo(best) < 0)) {
                    best = e.getKey();
                }
            }
            return best;
        }
    }

    /**
     * A block in a skeleton: one command line, then blank lines up to the block's own line count. The compiler names
     * the functions it generates by SOURCE LINE ({@code line046/execute1}), so a block must keep every later line
     * where it was.
     */
    static String blockToken(String sentinel, String value) {
        return BLOCK_LINE + sentinel + "\n".repeat(value.split("\n", -1).length - 1);
    }

    /** Is {@code template[start, end)} the only thing on its line? */
    static boolean aloneOnItsLine(String template, int start, int end) {
        int ls = template.lastIndexOf('\n', start - 1) + 1;
        int le = template.indexOf('\n', end);
        return template.substring(ls, start).isBlank() && template.substring(end, le < 0 ? template.length() : le).isBlank();
    }

    /** One template, every slot its values allow: the effect catalogue's case. */
    public static Skeleton of(String template, Map<String, String> values) {
        Composer c = new Composer().scan(template, values, "");
        // The creature's tag is in the blocks a tool built (colour_cycle's steps) even where the template never
        // spells it, so it is taken out of those too.
        for (var e : values.entrySet()) {
            if (kindOf(e.getKey()) == Kind.IDENT) {
                c.slot("", e.getKey(), e.getValue());
            }
        }
        return c.skeleton(c.render(template, values, "", null));
    }

    /** Put every sentinel's value back into {@code text}. */
    public static String substitute(String text, Map<String, String> slots) {
        if (slots.isEmpty() || !text.contains("zq")) {
            return text;
        }
        StringBuilder alt = new StringBuilder();
        for (String s : slots.keySet()) {
            alt.append(alt.length() == 0 ? "" : "|").append(Pattern.quote(s));
        }
        Matcher m = Pattern.compile(alt.toString()).matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(slots.get(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
