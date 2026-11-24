package com.moddingfromamod.world;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>A world's rules, as its {@code worlds/rules/<id>.rules.json} declares them</b> —
 * parsed, checked and immutable. Minecraft-free: the schema check IS {@link #parse}, so Gate A runs
 * it over every committed file ({@code WorldRulesFilesTest}) and the running server runs the same
 * code on every hot reload ({@link WorldRulesReloader}), which is what makes "invalid keeps the
 * previous rules" a property of one parser rather than of two that agree by luck.
 *
 * <h2>The file</h2>
 * <pre>
 * {"store": "world-rules", "v": 1, "world": "islands", "workshop": "Moddy's Workshop",
 *  "brief": ["one line the agent reads", …],
 *  "rules": [{"id": "no-time", "type": "deny_world_effect", "effects": ["time"],
 *             "message": {"rule": "…", "why": "…"}}, …]}
 * </pre>
 * <p>Every word in it a child reads — the {@code workshop}'s name and each rule's {@code message.rule} and
 * {@code message.why} — passes the safety lexicon here, as his chat lines pass it at the text sweep: a file with one
 * it flags is refused, naming the field, like any other bad field. The {@code brief} lines are the agent's, not his.
 *
 * Strict on purpose: an unknown key, type or effect is an ERROR, never ignored, because a typo in a
 * rules file that silently allowed everything would be the quietest possible failure — the world
 * looks governed and is not. Keys starting with {@code _} are notes and are skipped.
 *
 * <h2>Two fallbacks, and which is which</h2>
 * {@link #ALLOW_ALL} is a no-rules world's: no file, nothing denied, nothing graded — such a world
 * sees no change at all. {@link #CLOSED} is a bases world's before its file arrives: every world effect
 * and the base reach denied, which is exactly what the code enforced, so lifting it behind a
 * file can never loosen a world that simply has not received one.
 */
public final class WorldRules {

    /** The envelope's {@code store}. */
    public static final String STORE = "world-rules";
    /** The one version this reader writes and reads. A higher one is refused, never guessed at. */
    public static final int VERSION = 1;
    public static final String DEFAULT_WORKSHOP = "Moddy's Workshop";

    static final int MAX_RULES = 32;
    static final int MAX_BRIEF = 8;
    static final int MAX_BRIEF_LINE = 240;
    static final int MAX_RULE_WORDS = 120;
    static final int MAX_WHY_WORDS = 160;
    private static final Pattern WORLD_ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern RULE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern PLUGIN_TYPE = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final Pattern MILESTONE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

    /** A no-rules world's rules: nothing denied. */
    public static final WorldRules ALLOW_ALL = new WorldRules("", DEFAULT_WORKSHOP, List.of(), List.of(), "");

    /** A bases world with no rules file yet: everything the reach rule refused, still refused. */
    public static final WorldRules CLOSED = closed();

    private final String world;
    private final String workshop;
    private final List<String> brief;
    private final List<WorldRule> rules;
    private final Map<WorldEffect, WorldRule> byEffect = new EnumMap<>(WorldEffect.class);
    private final String sha;

    private WorldRules(String world, String workshop, List<String> brief, List<WorldRule> rules, String sha) {
        this.world = world;
        this.workshop = workshop;
        this.brief = List.copyOf(brief);
        this.rules = List.copyOf(rules);
        this.sha = sha;
        for (WorldRule r : this.rules) {
            if (r.type().denies() && r.enforced()) {
                for (WorldEffect e : r.effects()) {
                    byEffect.put(e, r);
                }
            }
        }
    }

    private static WorldRules closed() {
        List<WorldRule> rs = new ArrayList<>();
        rs.add(new WorldRule("closed-world-effects", WorldRule.Type.DENY_WORLD_EFFECT,
                EnumSet.of(WorldEffect.TIME, WorldEffect.WEATHER, WorldEffect.GAMERULES_DIFFICULTY,
                        WorldEffect.WORLDWIDE_DATAPACKS_STANDING_RULES, WorldEffect.RECIPES_DROPS),
                Map.of(), "On this world, what changes the whole world is not for wishes",
                "because every player shares it"));
        rs.add(new WorldRule("closed-base-reach", WorldRule.Type.BASE_REACH,
                EnumSet.of(WorldEffect.OUTSIDE_OWN_BASE, WorldEffect.OTHERS_BASES, WorldEffect.OTHER_DIMENSIONS),
                Map.of(), "On this world a wish stays on your own base",
                "so nobody's wish lands on somebody else's"));
        return new WorldRules("", DEFAULT_WORKSHOP, List.of(), rs, "closed");
    }

    // ── what a rules object answers ──────────────────────────────────────────────────────────────

    /** The world id the file names ("" for the two fallbacks). */
    public String world() {
        return world;
    }

    /** Where a denied wish WORKS, for the refusal's "where it works" part. */
    public String workshop() {
        return workshop;
    }

    /** The lines this world adds to the agent's brief. */
    public List<String> brief() {
        return brief;
    }

    /** Every rule, in file order, enforced or not. */
    public List<WorldRule> rules() {
        return rules;
    }

    /** sha1 of the file this came from ({@code ""} / {@code "closed"} for the fallbacks). */
    public String sha() {
        return sha;
    }

    /** Is this effect denied here? */
    public boolean denies(WorldEffect e) {
        return e != null && byEffect.containsKey(e);
    }

    /** The enforced rule that denies this effect, or null. */
    public WorldRule ruleFor(WorldEffect e) {
        return e == null ? null : byEffect.get(e);
    }

    /** Every denied effect. */
    public Set<WorldEffect> denied() {
        return byEffect.isEmpty() ? EnumSet.noneOf(WorldEffect.class) : EnumSet.copyOf(byEffect.keySet());
    }

    /** Does this world deny anything at all? False on a no-rules world, where nothing is graded. */
    public boolean deniesAnything() {
        return !byEffect.isEmpty();
    }

    /** Is the base-reach rule on — a wish must land on its caster's base? */
    public boolean baseReach() {
        WorldRule a = byEffect.get(WorldEffect.OUTSIDE_OWN_BASE);
        WorldRule b = byEffect.get(WorldEffect.OTHERS_BASES);
        return (a != null && a.type() == WorldRule.Type.BASE_REACH)
                || (b != null && b.type() == WorldRule.Type.BASE_REACH);
    }

    /** The scopes a standing rule may have here ({@code own_base}, {@code pets}), or empty. */
    public List<String> allowedScopes() {
        List<String> out = new ArrayList<>();
        for (WorldRule r : rules) {
            if (r.type() == WorldRule.Type.ALLOW_BASE_SCOPED) {
                out.addAll(r.strings("scopes"));
            }
        }
        return out;
    }

    /**
     * The first rule of this type, or null when the file declares none. A plug-in reads its own rule through
     * this (the wish bank's {@code wish_bank}), so the core never names what the rule means.
     */
    public WorldRule first(WorldRule.Type type) {
        for (WorldRule r : rules) {
            if (r.type() == type) {
                return r;
            }
        }
        return null;
    }

    /** The first rule of a plug-in's type ({@link WorldRuleType#key()}), or null when the file declares none. */
    public WorldRule first(String pluginType) {
        for (WorldRule r : all(pluginType)) {
            return r;
        }
        return null;
    }

    /** Every rule of a plug-in's type, in file order. */
    public List<WorldRule> all(String pluginType) {
        List<WorldRule> out = new ArrayList<>();
        for (WorldRule r : rules) {
            if (r.type() == WorldRule.Type.PLUGIN && r.pluginType().equals(pluginType)) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * May another player be a place to go or point at here? {@code off} unless the file
     * declares a {@code player_reach} rule — so a world that says nothing, and both fallbacks, keep
     * today's behaviour.
     */
    public com.moddingfromamod.agent.PlayerReach.Mode playerReach() {
        for (WorldRule r : rules) {
            if (r.type() == WorldRule.Type.PLAYER_REACH) {
                Object m = r.params().get("mode");
                com.moddingfromamod.agent.PlayerReach.Mode mode = m instanceof String s
                        ? com.moddingfromamod.agent.PlayerReach.Mode.ofKey(s) : null;
                return mode == null ? com.moddingfromamod.agent.PlayerReach.Mode.OFF : mode;
            }
        }
        return com.moddingfromamod.agent.PlayerReach.Mode.OFF;
    }

    /** May this world-wide command head run here? */
    public boolean allowsCommand(String head) {
        WorldEffect e = WorldEffect.ofCommand(head);
        return e != null && !denies(e);
    }

    @Override
    public String toString() {
        return "WorldRules[" + (world.isEmpty() ? sha : world) + ", denies " + denied() + "]";
    }

    // ── the schema ───────────────────────────────────────────────────────────────────────────────

    /** A parse: rules, or the errors that stopped it (then {@code rules} is null). */
    public record Parsed(WorldRules rules, List<String> errors) {
        public boolean ok() {
            return rules != null;
        }
    }

    /**
     * Parse and check a rules file's text. {@code expectWorld} (nullable) must match its {@code world}. The rule
     * library is the built-in types plus every {@link WorldRuleType} a plug-in registered.
     */
    public static Parsed parse(String text, String expectWorld) {
        return parse(text, expectWorld, com.moddingfromamod.plugin.Plugins.shared().all(WorldRuleType.class));
    }

    /** {@link #parse(String, String)} with the plug-in types given, so a test names its own library. */
    public static Parsed parse(String text, String expectWorld, List<WorldRuleType> pluginTypes) {
        return parse(text, expectWorld, pluginTypes, com.moddingfromamod.safety.SafetyLexicon.shared());
    }

    /**
     * {@link #parse(String, String, List)} judging the words a child reads with {@code lexicon}, so a test names its
     * own list. A line the lexicon puts CLEARLY in a band refuses the file, as the text sweep refuses such a line in his
     * chat ({@code SafetyTextSweep}); a wellbeing note alone does not.
     */
    public static Parsed parse(String text, String expectWorld, List<WorldRuleType> pluginTypes,
                               com.moddingfromamod.safety.SafetyLexicon lexicon) {
        java.util.function.Predicate<String> notForAChild = t -> lexicon != null && lexicon.check(t)
                .map(v -> v.overall() == com.moddingfromamod.safety.SafetyVerdict.Band.CLEARLY).orElse(false);
        List<String> err = new ArrayList<>();
        Map<String, WorldRuleType> library = library(pluginTypes, err);
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(text == null ? "" : text);
            if (!el.isJsonObject()) {
                return fail("the file is not a JSON object");
            }
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return fail("the file is not JSON: " + e.getMessage());
        }
        for (String k : o.keySet()) {
            if (!k.startsWith("_") && !Set.of("store", "v", "world", "workshop", "brief", "rules").contains(k)) {
                err.add("unknown key '" + k + "'");
            }
        }
        if (!STORE.equals(str(o, "store"))) {
            err.add("store must be \"" + STORE + "\"");
        }
        Integer v = integer(o.get("v"));
        if (v == null) {
            err.add("v must be a number");
        } else if (v > VERSION) {
            err.add("v " + v + " was written by a newer Moddy than this one (reads v" + VERSION
                    + "); refused rather than guessed at");
        } else if (v != VERSION) {
            err.add("v must be " + VERSION);
        }
        String world = str(o, "world");
        if (world == null || !WORLD_ID.matcher(world).matches()) {
            err.add("world must be a world id like \"islands\"");
        } else if (expectWorld != null && !expectWorld.isEmpty() && !expectWorld.equals(world)) {
            err.add("world is '" + world + "' but this file is for '" + expectWorld + "'");
        }
        String workshop = o.has("workshop") ? str(o, "workshop") : DEFAULT_WORKSHOP;
        if (workshop == null || workshop.isBlank() || workshop.length() > 60) {
            err.add("workshop must be a place name of 1–60 characters");
        } else if (notForAChild.test(workshop)) {
            err.add("workshop: a child reads this name, and the safety lexicon flags it");
        }
        List<String> brief = new ArrayList<>();
        if (o.has("brief")) {
            if (!o.get("brief").isJsonArray()) {
                err.add("brief must be a list of lines");
            } else {
                JsonArray a = o.getAsJsonArray("brief");
                if (a.size() > MAX_BRIEF) {
                    err.add("brief has " + a.size() + " lines; at most " + MAX_BRIEF);
                }
                for (JsonElement e : a) {
                    String line = e.isJsonPrimitive() ? e.getAsString() : null;
                    if (line == null || line.isBlank() || line.length() > MAX_BRIEF_LINE) {
                        err.add("every brief line is text of 1–" + MAX_BRIEF_LINE + " characters");
                    } else {
                        brief.add(line.strip());
                    }
                }
            }
        }
        List<WorldRule> rules = new ArrayList<>();
        if (!o.has("rules") || !o.get("rules").isJsonArray()) {
            err.add("rules must be a list");
        } else {
            JsonArray a = o.getAsJsonArray("rules");
            if (a.size() > MAX_RULES) {
                err.add("rules has " + a.size() + " entries; at most " + MAX_RULES);
            }
            Set<String> ids = new HashSet<>();
            Map<WorldEffect, String> claimed = new EnumMap<>(WorldEffect.class);
            int i = 0;
            for (JsonElement e : a) {
                String where = "rules[" + i++ + "]";
                if (!e.isJsonObject()) {
                    err.add(where + " is not an object");
                    continue;
                }
                WorldRule r = rule(e.getAsJsonObject(), where, err, library, notForAChild);
                if (r == null) {
                    continue;
                }
                if (r.type() == WorldRule.Type.PLUGIN && library.get(r.pluginType()).single() && rules.stream()
                        .anyMatch(x -> x.type() == WorldRule.Type.PLUGIN && x.pluginType().equals(r.pluginType()))) {
                    err.add(where + ": a world has one " + r.pluginType());
                }
                if (r.type() == WorldRule.Type.WISH_BANK && rules.stream()
                        .anyMatch(x -> x.type() == WorldRule.Type.WISH_BANK)) {
                    err.add(where + ": a world has one wish_bank");
                }
                if (r.type() == WorldRule.Type.PLAYER_REACH && rules.stream()
                        .anyMatch(x -> x.type() == WorldRule.Type.PLAYER_REACH)) {
                    err.add(where + ": a world has one player_reach");
                }
                if (r.type() == WorldRule.Type.CLAMP && rules.stream()
                        .anyMatch(x -> x.type() == WorldRule.Type.CLAMP)) {
                    err.add(where + ": a world has one clamp");
                }
                if (!ids.add(r.id())) {
                    err.add(where + ": the id '" + r.id() + "' is used twice");
                }
                for (WorldEffect eff : r.effects()) {
                    String before = claimed.putIfAbsent(eff, r.id());
                    if (before != null) {
                        err.add(where + ": " + eff.key() + " is already denied by '" + before
                                + "' — one rule per effect, so a refusal names one rule");
                    }
                }
                rules.add(r);
            }
        }
        if (!err.isEmpty()) {
            return new Parsed(null, List.copyOf(err));
        }
        return new Parsed(new WorldRules(world, workshop, brief, rules, sha1(text)), List.of());
    }

    /**
     * The plug-in types by key. A registration that cannot be told from a built-in type, or from an earlier
     * registration, is an error on every parse: a file must never mean two things.
     */
    private static Map<String, WorldRuleType> library(List<WorldRuleType> pluginTypes, List<String> err) {
        Map<String, WorldRuleType> out = new LinkedHashMap<>();
        for (WorldRuleType t : pluginTypes == null ? List.<WorldRuleType>of() : pluginTypes) {
            String k = t.key();
            if (k == null || !PLUGIN_TYPE.matcher(k).matches()) {
                err.add("a plug-in registered a rule type with the key '" + k + "'; a key is like \"lighthouse_hours\" "
                        + "(a-z, 0-9, _)");
            } else if (WorldRule.Type.ofKey(k) != null || k.equals(WorldRule.Type.PLUGIN.key())) {
                err.add("a plug-in registered the rule type '" + k + "', which is a built-in type");
            } else if (out.putIfAbsent(k, t) != null) {
                err.add("two plug-ins registered the rule type '" + k + "'");
            }
        }
        return out;
    }

    private static WorldRule rule(JsonObject r, String where, List<String> err, Map<String, WorldRuleType> library,
                                  java.util.function.Predicate<String> notForAChild) {
        int before = err.size();
        for (String k : r.keySet()) {
            if (!k.startsWith("_") && !Set.of("id", "type", "effects", "params", "message").contains(k)) {
                err.add(where + ": unknown key '" + k + "'");
            }
        }
        String id = str(r, "id");
        if (id == null || !RULE_ID.matcher(id).matches()) {
            err.add(where + ": id must be like \"no-time\" (a-z, 0-9, -)");
            id = "?";
        }
        where = where + " '" + id + "'";
        String typeKey = str(r, "type");
        WorldRuleType plugin = typeKey == null ? null : library.get(typeKey);
        WorldRule.Type type = plugin != null ? WorldRule.Type.PLUGIN : WorldRule.Type.ofKey(typeKey);
        if (type == null) {
            err.add(where + ": type must be one of " + typeKeys(library));
            return null;
        }
        String shown = plugin != null ? typeKey : type.key();
        Set<WorldEffect> effects = EnumSet.noneOf(WorldEffect.class);
        if (r.has("effects")) {
            if (!r.get("effects").isJsonArray()) {
                err.add(where + ": effects must be a list");
            } else {
                for (JsonElement e : r.getAsJsonArray("effects")) {
                    WorldEffect eff = e.isJsonPrimitive() ? WorldEffect.ofKey(e.getAsString()) : null;
                    if (eff == null) {
                        err.add(where + ": unknown effect " + e + " (one of " + effectKeys() + ")");
                    } else if (!effects.add(eff)) {
                        err.add(where + ": effect " + eff.key() + " listed twice");
                    }
                }
            }
        }
        if (type.denies() && effects.isEmpty()) {
            err.add(where + ": a " + shown + " rule must name the effects it denies");
        }
        if (!type.denies() && !effects.isEmpty()) {
            err.add(where + ": a " + shown + " rule denies nothing, so it takes no effects");
        }
        if (type == WorldRule.Type.BASE_REACH) {
            for (WorldEffect e : effects) {
                if (!e.isPlace()) {
                    err.add(where + ": base_reach enforces places (others_bases, outside_own_base, "
                            + "other_dimensions), not " + e.key());
                }
            }
        }
        if (type == WorldRule.Type.DENY_WORLD_EFFECT) {
            for (WorldEffect e : effects) {
                if (e == WorldEffect.OTHERS_BASES || e == WorldEffect.OUTSIDE_OWN_BASE) {
                    err.add(where + ": " + e.key() + " is a place — a base_reach rule enforces it");
                }
            }
        }
        String rule = "";
        String why = "";
        if (type.denies()) {
            JsonObject m = r.has("message") && r.get("message").isJsonObject() ? r.getAsJsonObject("message") : null;
            rule = m == null ? null : str(m, "rule");
            why = m == null ? null : str(m, "why");
            if (m == null) {
                err.add(where + ": a rule that denies needs a message {rule, why}");
            } else {
                for (String k : m.keySet()) {
                    if (!k.startsWith("_") && !Set.of("rule", "why").contains(k)) {
                        err.add(where + ": message has an unknown key '" + k + "'");
                    }
                }
                if (rule == null || rule.isBlank() || rule.length() > MAX_RULE_WORDS) {
                    err.add(where + ": message.rule is the rule in a child's words, 1–" + MAX_RULE_WORDS + " characters");
                } else if (notForAChild.test(rule)) {
                    err.add(where + ": message.rule is read by a child, and the safety lexicon flags it");
                }
                if (why == null || why.isBlank() || why.length() > MAX_WHY_WORDS) {
                    err.add(where + ": message.why is the reason in a child's words, 1–" + MAX_WHY_WORDS + " characters");
                } else if (notForAChild.test(why)) {
                    err.add(where + ": message.why is read by a child, and the safety lexicon flags it");
                }
            }
        } else if (r.has("message")) {
            err.add(where + ": a " + shown + " rule refuses nothing, so it has no message");
        }
        Map<String, Object> params = plugin != null
                ? pluginParams(plugin, r.has("params") ? r.get("params") : null, where, err)
                : params(type, r.has("params") ? r.get("params") : null, where, err);
        if (err.size() > before) {
            return null;
        }
        return new WorldRule(id, type, effects, params, rule == null ? "" : rule.strip(),
                why == null ? "" : why.strip(), plugin != null ? typeKey : "");
    }

    /** A plug-in type's parameters, checked by the plug-in. A check that throws refuses the file and says so. */
    private static Map<String, Object> pluginParams(WorldRuleType plugin, JsonElement raw, String where,
                                                    List<String> err) {
        JsonObject p = raw == null || raw.isJsonNull() ? new JsonObject() : raw.isJsonObject() ? raw.getAsJsonObject() : null;
        if (p == null) {
            err.add(where + ": params must be an object");
            return Map.of();
        }
        try {
            Map<String, Object> out = plugin.params(p.deepCopy(), where, err);
            if (out == null) {
                return Map.of();
            }
            // Walk the entries rather than ask containsKey(null)/containsValue(null): an immutable Map.of throws on
            // those, and a map that cannot hold a null simply has none to find.
            for (Map.Entry<String, Object> e : out.entrySet()) {
                if (e.getKey() == null) {
                    err.add(where + ": the " + plugin.key() + " plug-in returned a parameter with no name");
                    return Map.of();
                }
                if (e.getValue() == null) {
                    err.add(where + ": the " + plugin.key() + " plug-in returned a parameter with no value ('"
                            + e.getKey() + "')");
                    return Map.of();
                }
            }
            return out;
        } catch (RuntimeException e) {
            err.add(where + ": the " + plugin.key() + " plug-in could not check its parameters (" + e + ")");
            return Map.of();
        }
    }

    /** Each type's parameters, checked. The economy's are checked now so a file carrying them is honest. */
    private static Map<String, Object> params(WorldRule.Type type, JsonElement raw, String where, List<String> err) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonObject p = raw == null || raw.isJsonNull() ? new JsonObject() : raw.isJsonObject() ? raw.getAsJsonObject() : null;
        if (p == null) {
            err.add(where + ": params must be an object");
            return out;
        }
        Set<String> allowed = switch (type) {
            case DENY_WORLD_EFFECT, BASE_REACH, SALVAGE_LOCK -> Set.of();
            case ALLOW_BASE_SCOPED -> Set.of("scopes", "lifetimeDays", "renewByPlay");
            case VALUE_GATE -> Set.of("by");
            case PRICE_BY_SIZE -> Set.of("small", "medium", "large");
            case MILESTONE_PAYOUT -> Set.of("milestones");
            case CREATURE_CAP -> Set.of("max");
            case WISH_BANK -> Set.of("start", "refillMinutes", "cap", "pricePerWish");
            case PLAYER_REACH -> Set.of("mode");
            case CLAMP -> Set.of("powerSeconds", "stacksPerKind", "kindsPerWish", "xpLevels");
            case PLUGIN -> Set.of();   // never reached: a plug-in type checks its own (pluginParams)
        };
        for (String k : p.keySet()) {
            if (!k.startsWith("_") && !allowed.contains(k)) {
                err.add(where + ": " + type.key() + " takes no parameter '" + k + "'"
                        + (allowed.isEmpty() ? "" : " (it takes " + allowed + ")"));
            }
        }
        switch (type) {
            case ALLOW_BASE_SCOPED -> {
                List<String> scopes = new ArrayList<>();
                if (p.has("scopes") && p.get("scopes").isJsonArray()) {
                    for (JsonElement e : p.getAsJsonArray("scopes")) {
                        String s = e.isJsonPrimitive() ? e.getAsString() : "";
                        if (!Set.of("own_base", "pets").contains(s)) {
                            err.add(where + ": scopes are own_base and/or pets, not '" + s + "'");
                        } else if (!scopes.contains(s)) {
                            scopes.add(s);
                        }
                    }
                }
                if (scopes.isEmpty()) {
                    err.add(where + ": allow_base_scoped needs scopes: [\"own_base\", \"pets\"]");
                }
                out.put("scopes", List.copyOf(scopes));
                // a standalone pack's lifetime (days; absent = none) and whether playing renews it
                if (p.has("lifetimeDays")) {
                    Integer d = integer(p.get("lifetimeDays"));
                    if (d == null || d < 1 || d > 365) {
                        err.add(where + ": allow_base_scoped.lifetimeDays must be 1–365 days");
                    } else {
                        out.put("lifetimeDays", d);
                    }
                }
                if (p.has("renewByPlay")) {
                    if (!p.get("renewByPlay").isJsonPrimitive() || !p.get("renewByPlay").getAsJsonPrimitive().isBoolean()) {
                        err.add(where + ": allow_base_scoped.renewByPlay must be true or false");
                    } else {
                        out.put("renewByPlay", p.get("renewByPlay").getAsBoolean());
                    }
                }
            }
            case VALUE_GATE -> {
                if (!"island_progress".equals(str(p, "by"))) {
                    err.add(where + ": value_gate's by must be \"island_progress\" (the only gate locked)");
                } else {
                    out.put("by", "island_progress");
                }
            }
            case PRICE_BY_SIZE -> {
                int last = 0;
                for (String k : List.of("small", "medium", "large")) {
                    Integer n = integer(p.get(k));
                    if (n == null || n < 0 || n > 20) {
                        err.add(where + ": price_by_size." + k + " must be a whole number of charges, 0–20");
                    } else if (n < last) {
                        err.add(where + ": price_by_size." + k + " is cheaper than a smaller wish");
                    } else {
                        last = n;
                        out.put(k, n);
                    }
                }
            }
            case MILESTONE_PAYOUT -> {
                List<String> named = new ArrayList<>();
                JsonArray ms = p.has("milestones") && p.get("milestones").isJsonArray()
                        ? p.getAsJsonArray("milestones") : null;
                if (ms == null || ms.isEmpty() || ms.size() > 30) {
                    err.add(where + ": milestones is a short named list (1–30), never inferred");
                } else {
                    for (JsonElement e : ms) {
                        JsonObject m = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
                        String mid = str(m, "id");
                        String name = str(m, "name");
                        Integer pays = integer(m.get("pays"));
                        if (mid == null || !MILESTONE_ID.matcher(mid).matches() || named.contains(mid)
                                || name == null || name.isBlank() || name.length() > 60
                                || pays == null || pays < 1 || pays > 20) {
                            err.add(where + ": each milestone is {id, name (≤60), pays (1–20)}, ids unique");
                        } else {
                            named.add(mid);
                        }
                    }
                    out.put("milestones", List.copyOf(named));
                }
            }
            case CREATURE_CAP -> {
                Integer n = integer(p.get("max"));
                if (n == null || n < 1 || n > 500) {
                    err.add(where + ": creature_cap.max must be 1–500 wished creatures per base");
                } else {
                    out.put("max", n);
                }
            }
            case WISH_BANK -> {
                Integer start = integer(p.get("start"));
                Integer every = integer(p.get("refillMinutes"));
                Integer cap = integer(p.get("cap"));
                Integer price = p.has("pricePerWish") ? integer(p.get("pricePerWish")) : Integer.valueOf(1);
                if (cap == null || cap < 1 || cap > 100) {
                    err.add(where + ": wish_bank.cap must be 1–100 wishes");
                } else if (start == null || start < 0 || start > cap) {
                    err.add(where + ": wish_bank.start must be 0–cap wishes");
                } else if (every == null || every < 1 || every > 1440) {
                    err.add(where + ": wish_bank.refillMinutes must be 1–1440 (one wish every N minutes)");
                } else if (price == null || price < 1 || price > 20) {
                    err.add(where + ": wish_bank.pricePerWish must be 1–20");
                } else {
                    out.put("start", start);
                    out.put("refillMinutes", every);
                    out.put("cap", cap);
                    out.put("pricePerWish", price);
                }
            }
            case CLAMP -> {
                for (String k : List.of("powerSeconds", "stacksPerKind", "kindsPerWish", "xpLevels")) {
                    Integer n = integer(p.get(k));
                    int max = k.equals("powerSeconds") ? 86_400 : k.equals("xpLevels") ? 1_000 : 64;
                    if (n == null || n < 1 || n > max) {
                        err.add(where + ": clamp." + k + " must be a whole number, 1–" + max);
                    } else {
                        out.put(k, n);
                    }
                }
            }
            case PLAYER_REACH -> {
                String mode = str(p, "mode");
                if (mode == null || com.moddingfromamod.agent.PlayerReach.Mode.ofKey(mode) == null) {
                    err.add(where + ": player_reach.mode must be \"open\" (another player may be a place"
                            + " to go) or \"off\"");
                } else {
                    out.put("mode", mode);
                }
            }
            default -> {
            }
        }
        return out;
    }

    private static Parsed fail(String why) {
        return new Parsed(null, List.of(why));
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isString()
                    ? o.get(k).getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer integer(JsonElement e) {
        try {
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            double d = e.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) < 1e9 ? (int) d : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String typeKeys(Map<String, WorldRuleType> library) {
        List<String> out = new ArrayList<>();
        for (WorldRule.Type t : WorldRule.Type.values()) {
            if (t != WorldRule.Type.PLUGIN) {
                out.add(t.key());
            }
        }
        out.addAll(library.keySet());
        return String.join(", ", out);
    }

    private static String effectKeys() {
        List<String> out = new ArrayList<>();
        for (WorldEffect e : WorldEffect.values()) {
            out.add(e.key());
        }
        return String.join(", ", out);
    }

    static String sha1(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
