package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>A world's own defaults for a datapack's settings</b>: the manifest's
 * {@code datapackDefaults} — {@code {"<objective>": {"<holder>": <value>}}} — written into the world's
 * scoreboard at boot, ONLY where that score is not set yet.
 *
 * <p>Why it exists. A third-party pack keeps its settings as scoreboard scores and sets each default
 * only while the score is unset ({@code execute unless score <key> skyvoid_config matches … run
 * scoreboard players set <key> skyvoid_config 1}). Sky Void Additions 1.3–1.4 added mechanics that
 * default ON (sand from gravel, tuff from magma, …), and a world may choose to keep the pack as it
 * was designed: those off. Writing them here, at server start, lands before the pack's first
 * {@code #load} (which runs on the first tick), so the pack finds them set and keeps them; a
 * grown-up who later turns one on in game is never overruled, because a set score is left alone.
 *
 * <p>Read from the same bundled manifest {@link WorldFacts} reads ({@code /worlds/<id>.json}).
 * The parse and the plan are pure ({@code DatapackDefaultsTest}); {@link #apply} is the only part
 * that touches Minecraft.
 */
public final class DatapackDefaults {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("moddingfromamod/world");

    /** One score to write. */
    public record Set(String objective, String holder, int value) {
    }

    private DatapackDefaults() {
    }

    /** {@code datapackDefaults} of a manifest's text, objective → holder → value; empty when absent or malformed. */
    public static Map<String, Map<String, Integer>> parse(String json) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        try {
            JsonElement root = JsonParser.parseString(json == null ? "" : json);
            if (!root.isJsonObject()) {
                return out;
            }
            JsonElement d = root.getAsJsonObject().get("datapackDefaults");
            if (d == null || !d.isJsonObject()) {
                return out;
            }
            for (var obj : d.getAsJsonObject().entrySet()) {
                if (!validName(obj.getKey()) || !obj.getValue().isJsonObject()) {
                    continue;
                }
                Map<String, Integer> holders = new LinkedHashMap<>();
                for (var h : obj.getValue().getAsJsonObject().entrySet()) {
                    JsonElement v = h.getValue();
                    if (validName(h.getKey()) && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                        holders.put(h.getKey(), v.getAsInt());
                    }
                }
                if (!holders.isEmpty()) {
                    out.put(obj.getKey(), holders);
                }
            }
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** A scoreboard objective or holder name a manifest may use: no spaces, no selectors. Pure. */
    static boolean validName(String s) {
        return s != null && s.matches("[A-Za-z0-9_.$#+-]{1,40}");
    }

    /** The scores to write: every default whose score is NOT already set ({@code isSet(objective, holder)}). Pure. */
    public static List<Set> plan(Map<String, Map<String, Integer>> defaults, BiPredicate<String, String> isSet) {
        List<Set> out = new ArrayList<>();
        defaults.forEach((objective, holders) -> holders.forEach((holder, value) -> {
            if (!isSet.test(objective, holder)) {
                out.add(new Set(objective, holder, value));
            }
        }));
        return out;
    }

    /** This world's bundled manifest's defaults (empty on a world with no bundled manifest, or one that declares none). */
    public static Map<String, Map<String, Integer>> current() {
        String id = WorldFacts.manifestId();
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            return Map.of();
        }
        String text = WorldFacts.manifestText(id);   //: the core's jar or a level jar's
        return text == null ? Map.of() : parse(text);
    }

    /** At server start, before the first tick: write the defaults that are not set yet. Server thread. */
    public static void apply(net.minecraft.server.MinecraftServer server) {
        apply(server, current());
    }

    /** Seam for tests: the same, with the defaults handed in. Returns what was written. */
    public static List<Set> apply(net.minecraft.server.MinecraftServer server,
                                  Map<String, Map<String, Integer>> defaults) {
        if (server == null || defaults.isEmpty()) {
            return List.of();
        }
        var board = server.getScoreboard();
        List<Set> sets = plan(defaults, (objective, holder) -> {
            var obj = board.getObjective(objective);
            return obj != null && board.getPlayerScoreInfo(
                    net.minecraft.world.scores.ScoreHolder.forNameOnly(holder), obj) != null;
        });
        for (Set s : sets) {
            var obj = board.getObjective(s.objective());
            if (obj == null) {
                obj = board.addObjective(s.objective(),
                        net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
                        net.minecraft.network.chat.Component.literal(s.objective()),
                        net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, false, null);
            }
            board.getOrCreatePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(s.holder()), obj)
                    .set(s.value());
        }
        if (!sets.isEmpty()) {
            LOGGER.info("world datapack defaults written (#1828; only where unset): {}", sets);
        }
        return sets;
    }
}
