package com.moddingfromamod.world;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.moddingfromamod.plugin.PluginScope;
import com.moddingfromamod.plugin.Plugins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rule type a plug-in adds ({@link WorldRuleType}): parsed and checked like a built-in one, read back by its key,
 * and refused loudly when nobody registered it. The last test goes through the registry, the way the game parses.
 */
@ExtendWith(PluginScope.class)
class WorldRuleTypeTest {

    /** A lighthouse's opening hours: {@code from} and {@code to}, whole hours 0–23. */
    static final class Hours implements WorldRuleType {
        private final String key;
        private final boolean single;

        Hours(String key, boolean single) {
            this.key = key;
            this.single = single;
        }

        @Override public String key() {
            return key;
        }

        @Override public Map<String, Object> params(JsonObject raw, String where, List<String> err) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String k : raw.keySet()) {
                if (!k.equals("from") && !k.equals("to")) {
                    err.add(where + ": " + key + " takes no parameter '" + k + "'");
                }
            }
            for (String k : List.of("from", "to")) {
                int h = raw.has(k) && raw.get(k).isJsonPrimitive() ? raw.get(k).getAsInt() : -1;
                if (h < 0 || h > 23) {
                    err.add(where + ": " + key + "." + k + " must be an hour, 0–23");
                } else {
                    out.put(k, h);
                }
            }
            return out;
        }

        @Override public boolean single() {
            return single;
        }
    }

    private static final List<WorldRuleType> LIB = List.of(new Hours("lighthouse_hours", true));

    private static String with(String... rules) {
        return "{\"store\":\"world-rules\",\"v\":1,\"world\":\"closed\",\"rules\":[" + String.join(",", rules) + "]}";
    }

    private static final String HOURS = "{\"id\":\"open\",\"type\":\"lighthouse_hours\",\"params\":{\"from\":6,\"to\":20}}";

    private static List<String> errors(String json, List<WorldRuleType> lib) {
        WorldRules.Parsed p = WorldRules.parse(json, null, lib);
        assertNull(p.rules(), "should have been refused: " + json);
        return p.errors();
    }

    private static void assertSays(List<String> errors, String words) {
        assertTrue(errors.stream().anyMatch(e -> e.contains(words)), "no error says '" + words + "': " + errors);
    }

    @Test
    void aPluginTypeParsesAndIsReadBackByItsKey() {
        WorldRules.Parsed p = WorldRules.parse(with(HOURS,
                "{\"id\":\"cap\",\"type\":\"creature_cap\",\"params\":{\"max\":12}}"), "closed", LIB);
        assertTrue(p.ok(), p.errors().toString());
        WorldRule r = p.rules().first("lighthouse_hours");
        assertNotNull(r);
        assertEquals(WorldRule.Type.PLUGIN, r.type());
        assertEquals("lighthouse_hours", r.pluginType());
        assertEquals("lighthouse_hours", r.typeKey());
        assertEquals(6, r.integer("from", -1));
        assertEquals(20, r.integer("to", -1));
        assertTrue(r.enforced() && r.effects().isEmpty(), "a plug-in rule denies nothing and is the plug-in's to enforce");
        assertNull(p.rules().first("lighthouse_keeper"), "another key finds nothing");
        assertEquals("creature_cap", p.rules().first(WorldRule.Type.CREATURE_CAP).typeKey());
        assertNull(p.rules().first(WorldRule.Type.PLUGIN.key()), "'plugin' is not a key a file or a reader can use");
    }

    @Test
    void aTypeNobodyRegisteredRefusesTheFileAndNamesTheLibrary() {
        List<String> none = errors(with(HOURS), List.of());
        assertSays(none, "rules[0] 'open': type must be one of deny_world_effect");
        assertTrue(none.stream().noneMatch(e -> e.contains(", plugin")), "PLUGIN has no spelling: " + none);
        assertSays(errors(with("{\"id\":\"x\",\"type\":\"plugin\"}"), LIB), "lighthouse_hours");
    }

    @Test
    void thePluginChecksItsOwnParameters() {
        assertSays(errors(with("{\"id\":\"open\",\"type\":\"lighthouse_hours\",\"params\":{\"from\":6,\"to\":40}}"), LIB),
                "rules[0] 'open': lighthouse_hours.to must be an hour");
        assertSays(errors(with("{\"id\":\"open\",\"type\":\"lighthouse_hours\",\"params\":{\"from\":6,\"to\":20,\"x\":1}}"), LIB),
                "takes no parameter 'x'");
        assertSays(errors(with("{\"id\":\"open\",\"type\":\"lighthouse_hours\",\"params\":[]}"), LIB),
                "params must be an object");
        assertSays(errors(with("{\"id\":\"open\",\"type\":\"lighthouse_hours\",\"params\":{\"from\":\"dawn\",\"to\":20}}"), LIB),
                "could not check its parameters");
    }

    @Test
    void aPluginRuleDeniesNothingSoItTakesNoEffectsAndNoMessage() {
        assertSays(errors(with("{\"id\":\"open\",\"type\":\"lighthouse_hours\",\"effects\":[\"time\"],"
                + "\"params\":{\"from\":6,\"to\":20}}"), LIB), "a lighthouse_hours rule denies nothing");
        assertSays(errors(with("{\"id\":\"open\",\"type\":\"lighthouse_hours\",\"params\":{\"from\":6,\"to\":20},"
                + "\"message\":{\"rule\":\"a\",\"why\":\"b\"}}"), LIB), "a lighthouse_hours rule refuses nothing");
    }

    @Test
    void oneRuleOfATypeUnlessThePluginSaysOtherwise() {
        String second = HOURS.replace("\"open\"", "\"late\"");
        assertSays(errors(with(HOURS, second), LIB), "rules[1]: a world has one lighthouse_hours");
        WorldRules.Parsed many = WorldRules.parse(with(HOURS, second), null,
                List.of(new Hours("lighthouse_hours", false)));
        assertTrue(many.ok(), many.errors().toString());
        assertEquals(List.of("open", "late"),
                many.rules().all("lighthouse_hours").stream().map(WorldRule::id).toList());
    }

    @Test
    void aRegistrationThatCouldMeanTwoThingsRefusesEveryFile() {
        String plain = with("{\"id\":\"cap\",\"type\":\"creature_cap\",\"params\":{\"max\":12}}");
        assertSays(errors(plain, List.of(new Hours("clamp", true))), "which is a built-in type");
        assertSays(errors(plain, List.of(new Hours("plugin", true))), "which is a built-in type");
        assertSays(errors(plain, List.of(new Hours("Lighthouse", true))), "a key is like");
        assertSays(errors(plain, List.of(new Hours("lighthouse_hours", true), new Hours("lighthouse_hours", true))),
                "two plug-ins registered the rule type 'lighthouse_hours'");
    }

    @Test
    void theGamesOwnParseReadsTheTypesPluginsRegistered() {
        assertSays(WorldRules.parse(with(HOURS), "closed").errors(), "type must be one of");
        Plugins.shared().add(WorldRuleType.class, new Hours("lighthouse_hours", true));
        WorldRules.Parsed p = WorldRules.parse(with(HOURS), "closed");
        assertTrue(p.ok(), p.errors().toString());
        assertEquals(20, p.rules().first("lighthouse_hours").integer("to", -1));
    }

    /** A rule type whose params() returns whatever map it is handed: the shape of the map is the test. */
    private static WorldRuleType returning(Supplier<Map<String, Object>> map) {
        return new WorldRuleType() {
            @Override public String key() {
                return "lighthouse_hours";
            }

            @Override public Map<String, Object> params(JsonObject raw, String where, List<String> err) {
                return map.get();
            }
        };
    }

    /** Map.of (as docs/EXTENDING.md's example returns) throws on containsKey(null); it must parse. */
    @Test
    void aPluginMayReturnAnImmutableMap() {
        WorldRules.Parsed p = WorldRules.parse(with(HOURS), null, List.of(returning(() -> Map.of("from", 6, "to", 20))));
        assertTrue(p.ok(), p.errors().toString());
        assertEquals(20, p.rules().first("lighthouse_hours").integer("to", -1));
        WorldRules.Parsed empty = WorldRules.parse(with(HOURS), null, List.of(returning(Map::of)));
        assertTrue(empty.ok(), empty.errors().toString());
    }

    @Test
    void aPluginMayReturnAnOrdinaryMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("from", 6);
        m.put("to", 20);
        WorldRules.Parsed p = WorldRules.parse(with(HOURS), null, List.of(returning(() -> m)));
        assertTrue(p.ok(), p.errors().toString());
        assertEquals(6, p.rules().first("lighthouse_hours").integer("from", -1));
    }

    @Test
    void aNullValueOrANullKeyIsStillRefusedInWords() {
        Map<String, Object> noValue = new HashMap<>();
        noValue.put("from", 6);
        noValue.put("to", null);
        assertSays(errors(with(HOURS), List.of(returning(() -> noValue))),
                "rules[0] 'open': the lighthouse_hours plug-in returned a parameter with no value ('to')");
        Map<String, Object> noName = new HashMap<>();
        noName.put(null, 6);
        assertSays(errors(with(HOURS), List.of(returning(() -> noName))),
                "rules[0] 'open': the lighthouse_hours plug-in returned a parameter with no name");
    }
}
