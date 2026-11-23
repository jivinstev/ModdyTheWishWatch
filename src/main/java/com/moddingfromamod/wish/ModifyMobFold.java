package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * <b>A {@code modify_mob} change in a button becomes {@code wishsummon}</b> (the real-mob row).
 * Pure: no Minecraft import, so the folding rule is a unit test.
 *
 * <p>{@code modify_mob} is a TOOL, so a learned button — a list of commands — cannot run it. When the
 * agent taught {@code summon minecraft:snow_golem …} followed by a hand-written
 * {@code modify_mob scope:one target:snow_golem follow:true persistent:true}, {@code add_template}
 * refused the second line ("Command not allowed in a template") without saying what to write
 * instead, and the agent re-taught the bare summon: the button's snow golem neither followed nor
 * stayed. The command that replays it exists — {@code wishsummon <mob> <modify_mob's JSON>},
 * applied by the tool's own code — so this folds the pair into it:
 *
 * <ul>
 *   <li>the {@code modify_mob} line is matched to the {@code summon} it changes: the nearest one
 *       BEFORE it whose mob is the line's target (a handle {@code @snow_golem2}, a type word
 *       {@code snow_golem}, {@code minecraft:snow_golem}), or simply the nearest summon for
 *       {@code @last} / no target;</li>
 *   <li>the summon becomes {@code wishsummon <mob> {spec}} — several changes to one summon merge;
 *       a position other than {@code ~ ~ ~} is kept as {@code execute positioned <pos> run …};</li>
 *   <li>summon NBT that is only {@code Tags} is kept as a {@code tag … add} on the mob just made;
 *       any other NBT cannot ride {@code wishsummon}, so nothing is folded and the refusal names the
 *       line to write;</li>
 *   <li>a {@code modify_mob} with no summon to fold into is refused, naming the {@code wishsummon}
 *       line that would summon the mob WITH its change.</li>
 * </ul>
 */
public final class ModifyMobFold {

    private ModifyMobFold() {
    }

    /**
     * The commands with every {@code modify_mob} folded, what was done (for the agent), and — when
     * a line could not be folded — the refusal to return instead of saving anything.
     */
    public record Result(List<String> commands, List<String> notes, String refusal) {
        public Result {
            commands = List.copyOf(commands);
            notes = List.copyOf(notes);
        }
    }

    /** The keys that say WHICH mob, never what to change: they have no place in a wishsummon spec. */
    public static final Set<String> NOT_SPEC = Set.of("target", "scope", "radius", "count");

    private static final Pattern SUMMON = Pattern.compile("(?i)^summon\\s+(\\S+)(.*)$");
    private static final Pattern TAGS_ONLY = Pattern.compile(
            "^\\{\\s*Tags\\s*:\\s*\\[([^\\[\\]{}]*)]\\s*}$");

    /** Is this line a hand-written {@code modify_mob} call? */
    public static boolean isModifyLine(String line) {
        if (line == null) {
            return false;
        }
        String t = line.strip();
        if (t.startsWith("/")) {
            t = t.substring(1);
        }
        return t.toLowerCase(Locale.ROOT).matches("^modify_mob(\\s.*)?$");
    }

    /** A parsed {@code modify_mob} line: its target ("" for the nearest) and its change. */
    public record Change(String target, JsonObject spec) {
    }

    /**
     * Read a {@code modify_mob} line in any of the shapes the agent writes: {@code key:value} or
     * {@code key=value} words, a JSON object, or a leading target word before either. Null when the
     * line is not one.
     */
    public static Change parse(String line) {
        if (!isModifyLine(line)) {
            return null;
        }
        String rest = line.strip();
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        rest = rest.substring("modify_mob".length()).strip();
        JsonObject all = new JsonObject();
        String target = "";
        int brace = rest.indexOf('{');
        String words = brace >= 0 ? rest.substring(0, brace) : rest;
        if (brace >= 0) {
            try {
                JsonElement el = JsonParser.parseString(rest.substring(brace));
                if (el.isJsonObject()) {
                    el.getAsJsonObject().entrySet().forEach(e -> all.add(e.getKey(), e.getValue()));
                }
            } catch (RuntimeException e) {
                return new Change("", new JsonObject());
            }
        }
        for (String w : words.strip().split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            int sep = indexOfSep(w);
            if (sep <= 0) {
                target = w;                     // a bare word: the target ("@snow_golem", "cow")
                continue;
            }
            String k = w.substring(0, sep).strip();
            if (!all.has(k)) {
                all.add(k, value(w.substring(sep + 1).strip()));
            }
        }
        if (all.has("target") && all.get("target").isJsonPrimitive()) {
            target = all.get("target").getAsString();
        }
        JsonObject spec = new JsonObject();
        all.entrySet().forEach(e -> {
            if (!NOT_SPEC.contains(e.getKey())) {
                spec.add(e.getKey(), e.getValue());
            }
        });
        return new Change(target == null ? "" : target.strip(), spec);
    }

    /**
     * Fold every {@code modify_mob} line in {@code commands} into the summon it changes.
     * {@code extra} are changes made by the TOOL during the wish (target + spec), matched to the
     * nearest summon of their mob the same way; one with no summon is reported in {@code notes}
     * rather than refused, since nobody wrote it into the list.
     */
    public static Result fold(List<String> commands, List<Change> extra) {
        List<String> lines = new ArrayList<>(commands == null ? List.of() : commands);
        Map<Integer, JsonObject> specs = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        List<Integer> drop = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Change c = parse(lines.get(i));
            if (c == null) {
                continue;
            }
            if (c.spec().isEmpty()) {
                return new Result(commands, notes, "NOT SAVED — `" + lines.get(i) + "` is not a command "
                        + "(modify_mob is a TOOL, and a button can only run commands), and I could not read "
                        + "what it changes. Summon the mob WITH its change instead: `wishsummon <mob> "
                        + "<the same JSON you gave modify_mob>`, e.g. wishsummon minecraft:wolf "
                        + "{\"follow\":true,\"persistent\":true}.");
            }
            int s = summonFor(lines, i, c.target());
            if (s < 0) {
                return new Result(commands, notes, refusalNoSummon(lines.get(i), c));
            }
            merge(specs, s, c.spec());
            drop.add(i);
        }
        for (Change c : extra == null ? List.<Change>of() : extra) {
            int s = summonFor(lines, lines.size(), c.target());
            if (s < 0) {
                notes.add("a modify_mob change (" + c.spec() + ") on '" + c.target() + "' had no summon "
                        + "in these commands to ride on — if the button should make the mob WITH it, "
                        + "teach `" + wishsummonFor(guessMob(c.target()), c.spec()) + "`.");
                continue;
            }
            merge(specs, s, c.spec());
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (drop.contains(i)) {
                continue;
            }
            JsonObject spec = specs.get(i);
            if (spec == null) {
                out.add(lines.get(i));
                continue;
            }
            List<String> folded = foldOne(lines.get(i), spec);
            if (folded == null) {
                return new Result(commands, notes, refusalNbt(lines.get(i), spec));
            }
            out.addAll(folded);
            notes.add("folded `" + lines.get(i) + "` and its modify_mob change into `"
                    + String.join("` + `", folded) + "` — a button cannot run modify_mob, and "
                    + "wishsummon replays exactly that change");
        }
        return new Result(out, notes, null);
    }

    /** {@link #fold} for a list with no tool-made changes. */
    public static Result fold(List<String> commands) {
        return fold(commands, List.of());
    }

    // ── the pieces ───────────────────────────────────────────────────────────────────────────

    private static void merge(Map<Integer, JsonObject> specs, int at, JsonObject spec) {
        JsonObject into = specs.computeIfAbsent(at, x -> new JsonObject());
        spec.entrySet().forEach(e -> into.add(e.getKey(), e.getValue()));
    }

    /** The index of the summon a change to {@code target} belongs to, searching back from {@code before}. */
    static int summonFor(List<String> lines, int before, String target) {
        String want = mobOf(target);
        for (int j = before - 1; j >= 0; j--) {
            Matcher m = SUMMON.matcher(strip(lines.get(j)));
            if (!m.matches()) {
                continue;
            }
            if (want == null || path(m.group(1)).equals(want)) {
                return j;
            }
        }
        return -1;
    }

    /** The mob path a target names ("@snow_golem2" → "snow_golem"), or null for "any": @last / nearest. */
    static String mobOf(String target) {
        if (target == null) {
            return null;
        }
        String t = target.strip().toLowerCase(Locale.ROOT);
        if (t.startsWith("@")) {
            t = t.substring(1);
            if (t.equals("last") || t.isEmpty()) {
                return null;
            }
            t = t.replaceFirst("\\d+$", "");
        }
        return t.isEmpty() ? null : path(t);
    }

    private static String guessMob(String target) {
        String m = mobOf(target);
        return m == null ? "<mob>" : "minecraft:" + m;
    }

    private static String path(String id) {
        String t = id.toLowerCase(Locale.ROOT);
        int colon = t.indexOf(':');
        return colon >= 0 ? t.substring(colon + 1) : t;
    }

    private static String strip(String line) {
        String t = line == null ? "" : line.strip();
        return t.startsWith("/") ? t.substring(1) : t;
    }

    /** The folded lines for one summon, or null when its NBT cannot ride wishsummon. */
    static List<String> foldOne(String summonLine, JsonObject spec) {
        Matcher m = SUMMON.matcher(strip(summonLine));
        if (!m.matches()) {
            return null;
        }
        String id = m.group(1);
        String rest = m.group(2).strip();
        String pos = "";
        String nbt = "";
        if (rest.startsWith("{")) {
            nbt = rest;
        } else if (!rest.isEmpty()) {
            String[] parts = rest.split("\\s+", 4);
            if (parts.length < 3) {
                return null;
            }
            pos = parts[0] + " " + parts[1] + " " + parts[2];
            nbt = parts.length == 4 ? parts[3].strip() : "";
        }
        List<String> tags = new ArrayList<>();
        if (!nbt.isEmpty()) {
            Matcher t = TAGS_ONLY.matcher(nbt);
            if (!t.matches()) {
                return null;
            }
            for (String tag : t.group(1).split(",")) {
                String name = tag.strip().replaceAll("^\"|\"$|^'|'$", "");
                if (!name.isEmpty()) {
                    tags.add(name);
                }
            }
        }
        String here = pos.isEmpty() || pos.equals("~ ~ ~") ? "" : "execute positioned " + pos + " run ";
        List<String> out = new ArrayList<>();
        out.add(here + wishsummonFor(id, spec));
        for (String tag : tags) {
            out.add(here + "tag @e[type=" + id + ",sort=nearest,limit=1] add " + tag);
        }
        return out;
    }

    static String wishsummonFor(String mob, JsonObject spec) {
        return "wishsummon " + mob + " " + spec;
    }

    private static String refusalNoSummon(String line, Change c) {
        return "NOT SAVED — `" + line + "` is not a command: modify_mob is a TOOL, and a button can "
                + "only run commands. To make the mob WITH that change in a button, summon it with "
                + "wishsummon, which applies modify_mob's own JSON:\n  "
                + wishsummonFor(guessMob(c.target()), c.spec())
                + "\nPut that line in place of the summon (and drop the modify_mob line), then call "
                + "add_template again.";
    }

    private static String refusalNbt(String summonLine, JsonObject spec) {
        Matcher m = SUMMON.matcher(strip(summonLine));
        String id = m.matches() ? m.group(1) : "<mob>";
        return "NOT SAVED — a button cannot run modify_mob, and `" + summonLine + "` carries NBT that "
                + "wishsummon cannot take, so I could not fold them together. Teach the mob WITH its "
                + "change as:\n  " + wishsummonFor(id, spec)
                + "\nand move what the NBT did into that JSON (name, scale, health, glow…) — then call "
                + "add_template again.";
    }

    private static int indexOfSep(String w) {
        int colon = w.indexOf(':');
        int eq = w.indexOf('=');
        if (colon < 0) {
            return eq;
        }
        if (eq < 0) {
            return colon;
        }
        return Math.min(colon, eq);
    }

    private static JsonElement value(String raw) {
        String v = raw.replaceAll("^\"|\"$|^'|'$", "");
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            return new JsonPrimitive(Boolean.parseBoolean(v.toLowerCase(Locale.ROOT)));
        }
        try {
            if (v.matches("-?\\d+")) {
                return new JsonPrimitive(Long.parseLong(v));
            }
            if (v.matches("-?\\d*\\.\\d+")) {
                return new JsonPrimitive(Double.parseDouble(v));
            }
        } catch (NumberFormatException e) {
            // falls through to a string
        }
        return new JsonPrimitive(v);
    }
}
