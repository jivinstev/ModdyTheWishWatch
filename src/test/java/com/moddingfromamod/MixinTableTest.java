package com.moddingfromamod;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * every mixin has a row in docs/MIXINS.md (target, injection, purpose, where it is needed), and every row a
 * mixin. A config's mixin with no row fails, a row naming a mixin no config lists fails, and so does a row whose config
 * column is not the config that lists it. Also: no public mixin is a {@code @Redirect} or an {@code @Overwrite}
 * (a redirect is exclusive, so two mods redirecting one call fail at load; docs/MIXINS.md, "The rules every row keeps"),
 * and the GameTest-only config is registered by the dev runs, never by neoforge.mods.toml.
 */
class MixinTableTest {

    static final List<String> CONFIGS = List.of("moddingfromamod.mixins.json", "moddingfromamod.gametest.mixins.json");
    static final Path RESOURCES = Path.of("src/main/resources");
    static final Path JAVA = Path.of("src/main/java");
    static final Path HOUSE_RESOURCES = Path.of("house/src/main/resources");
    static final Path TABLE = Path.of("docs/MIXINS.md");
    /** A table row: {@code | `Name` | `config` | …}. */
    static final Pattern ROW = Pattern.compile("^\\|\\s*`([A-Za-z0-9_$]+)`\\s*\\|\\s*`([^`]+)`\\s*\\|", Pattern.MULTILINE);

    /** Where a mixin is listed: its config file and the list in it (mixins, client or server). */
    record Listed(String config, String list) {
    }

    static JsonObject config(String name) throws IOException {
        Path file = name.contains(".gametest.") ? RESOURCES.resolve("gametest").resolve(name) : RESOURCES.resolve(name);
        if (!Files.exists(file) && Files.exists(HOUSE_RESOURCES.resolve(name))) {
            file = HOUSE_RESOURCES.resolve(name);   //: the private config moved with the private mixins
        }
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    /** Mixin simple name -> where it is listed. */
    static Map<String, Listed> listed(List<String> configs) throws IOException {
        Map<String, Listed> out = new TreeMap<>();
        for (String c : configs) {
            JsonObject json = config(c);
            for (String list : List.of("mixins", "client", "server")) {
                if (!json.has(list)) continue;
                for (JsonElement e : json.getAsJsonArray(list)) {
                    Listed prev = out.put(e.getAsString(), new Listed(c, list));
                    assertTrue(prev == null, e.getAsString() + " is listed twice (" + prev + ", " + c + ")");
                }
            }
        }
        return out;
    }

    /** Row mixin -> its second column. */
    static Map<String, String> rows(Path table) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Matcher m = ROW.matcher(Files.readString(table));
        while (m.find()) {
            String prev = out.put(m.group(1), m.group(2));
            assertTrue(prev == null, table + " has two rows for " + m.group(1));
        }
        return out;
    }

    /**
     * What a table and its configs disagree on, worded for the person who adds a mixin. The second column names the
     * config file ({@code byList} false: docs/MIXINS.md) or the list in the one config ({@code byList} true: the
     * private table).
     */
    static List<String> disagreements(Map<String, Listed> listed, Map<String, String> rows, Path table, boolean byList) {
        List<String> bad = new ArrayList<>();
        listed.forEach((name, where) -> {
            String want = byList ? where.list() : where.config();
            if (!rows.containsKey(name)) bad.add(name + " (in " + where.config() + ") has no row in " + table);
            else if (!rows.get(name).equals(want)) bad.add(name + "'s row says " + rows.get(name) + "; it is in " + want);
        });
        rows.keySet().stream().filter(n -> !listed.containsKey(n))
                .forEach(n -> bad.add(table + " has a row for " + n + ", which no mixin config lists"));
        return bad;
    }

    /** A listed mixin's source file, which must exist. */
    static Path source(String config, String name) throws IOException {
        Path src = com.moddingfromamod.testing.SourceRoots.file(
                JAVA.resolve(config(config).get("package").getAsString().replace('.', '/')).resolve(name + ".java"));
        assertTrue(Files.isRegularFile(src), name + " is listed in " + config + " but " + src + " is missing");
        return src;
    }

    @Test
    @DisplayName("every public mixin has a row in docs/MIXINS.md, and every row a mixin, in the right config")
    void everyMixinHasARow() throws IOException {
        List<String> bad = disagreements(listed(CONFIGS), rows(TABLE), TABLE, false);
        assertTrue(bad.isEmpty(), "docs/MIXINS.md and the mixin configs disagree (#2363; add the row in the same PR): "
                + String.join("; ", bad));
    }

    @Test
    @DisplayName("no public mixin is a @Redirect or an @Overwrite")
    void noExclusiveInjections() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Listed> e : listed(CONFIGS).entrySet()) {
            String code = Files.readString(source(e.getValue().config(), e.getKey()))
                    .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            if (code.contains("@Redirect") || code.contains("@Overwrite")) bad.add(e.getKey());
        }
        assertTrue(bad.isEmpty(), "use @WrapOperation (or a narrow @Inject / @Modify*), never @Redirect or @Overwrite: " + bad);
    }

    @Test
    @DisplayName("the GameTest-only config is registered by the dev runs, never by neoforge.mods.toml")
    void theTestConfigIsNotShipped() throws IOException {
        String toml = Files.readString(RESOURCES.resolve("META-INF/neoforge.mods.toml"));
        assertTrue(!toml.contains("gametest.mixins"), "neoforge.mods.toml must not register the GameTest-only mixins; "
                + "build.gradle appends them to the dev runs' processed copy instead");
        String gradle = Files.readString(Path.of("build.gradle"));
        // FML 10 (NeoForge 21.9+) has no --fml.mixinConfig: a config applies only when a mod file's toml declares it,
        // so processResources declares it in the dev copy and the jar blanks those lines (and its check refuses them).
        assertTrue(gradle.contains("config=\\\"gametest/${mod_id}.gametest.mixins.json\\\" ${DEV_MIXINS_MARK}"),
                "build.gradle's processResources must declare the GameTest-only mixins for the dev runs, or Gate B runs without them");
        assertTrue(gradle.contains("filter { String line -> line.endsWith(DEV_MIXINS_MARK) ? '' : line }"),
                "the jar must strip the dev runs' declaration of the GameTest-only mixins");
        assertTrue(gradle.contains("'**/*.gametest.mixins.json'"), "the jar must leave the GameTest-only config out (SHIP_EXCLUDES)");
    }
}
