package com.moddingfromamod.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Which shapes a wish may take on a real game item.</b>
 *
 * <p>The principle is one sentence: <b>a wish rides WITH the item's own action, never instead of
 * it</b> (docs/ITEM_TRIGGERS_PLAN.md §3). A magic cookie is still eaten; a shield still blocks; a bow
 * still shoots. So a shape that runs the wish on right-click ({@code held}, {@code thrown}) cannot
 * share a base whose right-click already does something, and the table names the shape the wish
 * should ride instead ({@link Verdict#redirect()}).
 *
 * <p>The table is DATA, {@code items/trigger-compat.json}, because it has three readers —
 * {@code make_item}, E1 ({@code tools/parts-coverage.py}) and the tests — and the bug this exists
 * for was an instrument that agreed with the defect because it never asked the question. One file
 * means no reader can keep a private copy that drifts.
 *
 * <p>Minecraft-free: a base is a string id, so this is testable without a game.
 */
public final class ItemTriggers {

    /** The action a base with no entry has: nothing of its own happens on right-click. */
    public static final String NONE = "none";

    private static final Table TABLE = load();

    private ItemTriggers() {
    }

    /**
     * The answer for one (base, shape) pair.
     *
     * @param action   what the base does on right-click in vanilla ({@link #NONE} if nothing)
     * @param ok       whether the shape can work on this base without taking over its own action
     * @param redirect the shape the wish should ride instead, or {@code null} when none exists
     * @param reason   why not, in words the agent can act on; empty when {@code ok}
     */
    public record Verdict(String base, String shape, String action, boolean ok, String redirect,
                          String reason) {
    }

    /** What {@code base} does on right-click in vanilla: an action name, or {@link #NONE}. */
    public static String actionOf(String base) {
        String id = normalise(base);
        if (id == null) {
            return NONE;
        }
        String named = TABLE.byItem.get(id);
        if (named != null) {
            return named;
        }
        if (!id.startsWith("minecraft:")) {
            return NONE;
        }
        for (String[] rule : TABLE.suffixes) {
            if (id.endsWith(rule[0])) {
                return rule[1];
            }
        }
        return NONE;
    }

    /** What the action does, in words ({@code "is eaten"}), for a message. */
    public static String describe(String action) {
        return TABLE.what.getOrDefault(action, "does nothing of its own");
    }

    /** The shape a wish that would take this action's click should use instead, or null. */
    public static String redirectFor(String action) {
        return TABLE.redirect.get(action);
    }

    /** The bases named for any of {@code shape}'s required actions, e.g. the three that shoot. */
    public static List<String> basesFor(String shape) {
        List<String> actions = TABLE.needsAction.get(shape);
        List<String> out = new ArrayList<>();
        if (actions != null) {
            TABLE.byItem.forEach((item, action) -> {
                if (actions.contains(action)) {
                    out.add(item);
                }
            });
        }
        return out;
    }

    /**
     * Whether {@code shape} can work on {@code base}. A wish with no real base (made from scratch,
     * or built on our own carrier) is always fine: there is no vanilla action to take over.
     */
    public static Verdict check(String base, String shape) {
        String id = normalise(base);
        String sh = shape == null ? "" : shape.trim().toLowerCase(Locale.ROOT);
        String action = actionOf(id);
        if (id == null || sh.isEmpty() || ItemBase.isWishItem(id)) {
            return new Verdict(id, sh, action, true, null, "");
        }
        List<String> needs = TABLE.needsAction.get(sh);
        if (needs != null && !needs.contains(action)) {
            List<String> bases = basesFor(sh);
            return new Verdict(id, sh, action, false, null,
                    "shape `" + sh + "` only works on " + String.join(", ", bases) + ", and " + id
                            + (NONE.equals(action) ? " has nothing it could ride on"
                            : " " + describe(action)) + ".");
        }
        if (TABLE.neverOnARealBase.contains(sh)) {
            String to = NONE.equals(action) ? "held" : redirectFor(action);
            return new Verdict(id, sh, action, false, to,
                    "shape `" + sh + "` never runs on a real item — only on one made from scratch"
                            + (to == null ? "." : "; on " + id + " use `" + to + "`."));
        }
        if (TABLE.takesTheClick.contains(sh) && !NONE.equals(action)) {
            String to = redirectFor(action);
            return new Verdict(id, sh, action, false, to,
                    id + " " + describe(action) + " when he uses it, and shape `" + sh
                            + "` would take that away from him. The wish rides WITH the item's own "
                            + "action" + (to == null ? "; no shape rides this one yet, so build it "
                            + "from scratch or on something that does nothing of its own."
                            : ": use `" + to + "`."));
        }
        return new Verdict(id, sh, action, true, null, "");
    }

    /**
     * <b>The per-base guidance the agent reads</b> (make_item's schema and its refusals), generated
     * from the table so the words cannot drift from the rule: for each action whose ride is BUILT,
     * which bases do it and which shape the wish rides.
     */
    public static String guidance(java.util.Collection<String> shipped) {
        StringBuilder out = new StringBuilder("A REAL ITEM KEEPS ITS OWN USE, AND THE WISH RIDES ON "
                + "IT — never instead of it:");
        Map<String, List<String>> bases = new LinkedHashMap<>();
        TABLE.byItem.forEach((item, action) -> bases.computeIfAbsent(action, a -> new ArrayList<>()).add(
                item.substring(item.indexOf(':') + 1)));
        TABLE.what.forEach((action, what) -> {
            String to = TABLE.redirect.get(action);
            List<String> named = bases.getOrDefault(action, List.of());
            if (to == null || named.isEmpty()) {
                return;
            }
            String some = String.join(", ", named.subList(0, Math.min(4, named.size())))
                    + (named.size() > 4 ? "…" : "");
            out.append("\n  • ").append(some).append(" (").append(what).append(") → ")
                    .append(shipped.contains(to) ? "`" + to + "`" : "not yet: leave base_item out");
            for (String also : TABLE.alsoRides.getOrDefault(action, List.of())) {
                if (shipped.contains(also)) {
                    out.append(" or `").append(also).append("`");
                }
            }
        });
        return out.append("\n  • anything that does nothing of its own in the air (a stick, a sword, a "
                + "torch) may take `held` or `thrown`. `placed` and `machine` never run on a real item.")
                .toString();
    }

    /** The icons that are something you DRINK from, for {@link #defaultBase}. */
    private static final Set<String> DRINK_ICONS = Set.of("cup", "bottle", "bucket");

    /**
     * <b>The real item a shape needs when the wish names none</b>: {@code on_consume} has
     * nothing to eat on a wish made from scratch, so it is built on a real drink when its icon is
     * something you drink from (a cup, a bottle), and on a cookie otherwise. {@code null} for every
     * shape that works from scratch.
     */
    public static String defaultBase(String shape, String icon) {
        if ("on_block".equals(shape)) {
            return "minecraft:shield";
        }
        if ("on_throw_land".equals(shape)) {
            return "minecraft:snowball";
        }
        if ("on_use_done".equals(shape)) {
            return "minecraft:goat_horn";
        }
        if ("on_consume".equals(shape)) {
            return icon != null && DRINK_ICONS.contains(icon.trim().toLowerCase(Locale.ROOT))
                    ? "minecraft:potion" : "minecraft:cookie";
        }
        return null;
    }

    /** {@code minecraft:}-qualified and lower-cased, or null for a missing base. */
    static String normalise(String base) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String id = base.trim().toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    // ── the table ──────────────────────────────────────────────────────────────────────────────

    private record Table(Map<String, String> byItem, Map<String, String> what, Map<String, String> redirect,
                         Map<String, List<String>> alsoRides,
                         Map<String, List<String>> needsAction, Set<String> takesTheClick,
                         Set<String> neverOnARealBase, List<String[]> suffixes) {
    }

    private static Table load() {
        try (InputStream in = ItemTriggers.class.getResourceAsStream("/items/trigger-compat.json")) {
            if (in == null) {
                throw new IllegalStateException("items/trigger-compat.json is not on the classpath");
            }
            JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, String> byItem = new LinkedHashMap<>();
            Map<String, String> what = new LinkedHashMap<>();
            Map<String, String> redirect = new LinkedHashMap<>();
            Map<String, List<String>> alsoRides = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("actions").entrySet()) {
                JsonObject a = e.getValue().getAsJsonObject();
                what.put(e.getKey(), a.get("what").getAsString());
                JsonElement to = a.get("redirect");
                redirect.put(e.getKey(), to == null || to.isJsonNull() ? null : to.getAsString());
                if (a.has("alsoRides")) {
                    alsoRides.put(e.getKey(), strings(a.getAsJsonArray("alsoRides")));
                }
                for (JsonElement item : a.getAsJsonArray("items")) {
                    String prior = byItem.put(item.getAsString(), e.getKey());
                    if (prior != null) {
                        throw new IllegalStateException(item.getAsString() + " is under two actions");
                    }
                }
            }
            Map<String, List<String>> needs = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("needsAction").entrySet()) {
                needs.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
            }
            List<String[]> suffixes = new ArrayList<>();
            for (JsonElement rule : root.getAsJsonArray("suffixes")) {
                JsonArray r = rule.getAsJsonArray();
                suffixes.add(new String[]{r.get(0).getAsString(), r.get(1).getAsString()});
            }
            return new Table(java.util.Collections.unmodifiableMap(byItem), Map.copyOf(what), java.util.Collections.unmodifiableMap(redirect),
                    Map.copyOf(alsoRides),
                    Map.copyOf(needs), Set.copyOf(strings(root.getAsJsonArray("takesTheClick"))),
                    Set.copyOf(strings(root.getAsJsonArray("neverOnARealBase"))), List.copyOf(suffixes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.getAsString()));
        return out;
    }
}
