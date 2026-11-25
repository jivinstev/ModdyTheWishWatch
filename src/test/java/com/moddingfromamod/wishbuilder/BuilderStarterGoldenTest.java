package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * <b>A make saved re-makes exactly as it did</b>. The fixture is every creature and ride ready-made's
 * lowering, recorded on the code before bodies stopped bringing their own powers; reading the same saved make now
 * ({@link SpecMigrations#readyBodyPowers}) must give the same tool calls. Powers compare as a set (the tool reads them
 * as one), plus what the body does by itself ({@link #withNatural}). A creature already standing in a world is never re-lowered, so this is the only path that could change one.
 */
class BuilderStarterGoldenTest {

    static JsonObject fixture() throws Exception {
        try (var in = new InputStreamReader(new GZIPInputStream(Files.newInputStream(Path.of(BuilderStarterGolden.FIXTURE))),
                StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        }
    }

    /**
     * The calls with every {@code powers} array sorted, and without {@code behaves_like}: every make is GIVEN
     * the behaviour its picks choose (BehaviourChoice), a deliberate change this fixture predates. That is pinned by
     * {@code species.BehaviourChoiceTest}; everything else here still re-makes byte for byte.
     */
    static JsonArray normal(JsonArray calls) {
        JsonArray out = calls.deepCopy();
        for (JsonElement c : out) {
            JsonObject args = c.getAsJsonObject().getAsJsonObject("args");
            if (args != null) {
                args.remove("behaves_like");
            }
            if (args != null && args.has("powers") && args.get("powers").isJsonArray()) {
                List<String> p = new ArrayList<>();
                args.getAsJsonArray("powers").forEach(e -> p.add(e.getAsString()));
                p.sort(null);
                JsonArray a = new JsonArray();
                p.forEach(a::add);
                args.add("powers", a);
            }
        }
        return out;
    }

    /**
     *, a deliberate change this fixture predates: a make also does what its body does by
     * itself (a dragon flies, a rabbit hops, BuilderNatural). Added to the recorded calls; everything else is as it was.
     */
    static JsonArray withNatural(JsonArray calls, String preset) {
        JsonArray out = calls.deepCopy();
        for (JsonElement c : out) {
            JsonObject o = c.getAsJsonObject();
            JsonObject args = o.getAsJsonObject("args");
            if (o.get("tool").getAsString().equals("sculpt_creature") && args != null && args.has("powers")) {
                JsonArray p = args.getAsJsonArray("powers");
                for (String n : BuilderNatural.of(preset, LibraryPresets.SHIPPED.read(preset))) {
                    if (!p.contains(new com.google.gson.JsonPrimitive(n))) {
                        p.add(n);
                    }
                }
            }
        }
        return out;
    }

    /**
     * #74, a deliberate change this fixture predates: a creature made from a ready-made SPECIES carries that ready-made's
     * shipped health and attack damage (the pet dragon's 60, in every world), where before it inherited them from the
     * world's own record of the species (20 and 3 in a fresh world). Added to the recorded sculpt; the rest of every
     * saved make still re-makes byte for byte. A body (bodies/...) already sent its own and is unchanged.
     */
    static JsonArray withShipped(JsonArray calls, String preset) {
        JsonArray out = calls.deepCopy();
        JsonObject shipped = LibraryPresets.SHIPPED.species(preset);
        for (JsonElement c : out) {
            JsonObject args = c.getAsJsonObject().getAsJsonObject("args");
            if (shipped != null && c.getAsJsonObject().get("tool").getAsString().equals("sculpt_creature") && args != null
                    && args.has("start_from")) {
                for (String k : new String[] {"health", "damage"}) {
                    if (!args.has(k) && shipped.has(k)) {
                        args.add(k, shipped.get(k).deepCopy());
                    }
                }
            }
        }
        return out;
    }

    /**
     * #99, a deliberate change this fixture predates: every stock creature has a reviewed stat line, so a body's sculpt
     * now sends its reviewed health and damage (the bear's 30/6 where it sent 30/0, the duck's 8/1 where it sent none
     * and was read as 20/3). The recorded sculpt takes the body's shipped numbers; the rest of every saved make still
     * re-makes byte for byte. A species preset's are added by {@link #withShipped}. Since #101 a shipped armour and
     * knockback resistance above 0 go with it too (the turtle's shell, the rock golem's footing).
     */
    static JsonArray withReviewedStats(JsonArray calls, String preset) {
        JsonArray out = calls.deepCopy();
        JsonObject sculpt = preset.startsWith("bodies/") ? LibraryPresets.SHIPPED.read(preset) : null;
        for (JsonElement c : out) {
            JsonObject args = c.getAsJsonObject().getAsJsonObject("args");
            if (!c.getAsJsonObject().get("tool").getAsString().equals("sculpt_creature") || args == null) {
                continue;
            }
            if (sculpt != null) {
                for (String k : new String[] {"health", "damage"}) {
                    if (sculpt.has(k)) {
                        args.add(k, sculpt.get(k).deepCopy());
                    }
                }
            }
            // #101: and its shipped armour and knockback, where it has some, now that the tool takes them
            BuilderLowering.defence(LibraryPresets.SHIPPED.species(preset), args);
        }
        return out;
    }

    @Test
    void everySavedStarterMakeLowersAsItDidBefore() throws Exception {
        JsonObject before = fixture();
        assertTrue(before.size() >= 100, "the fixture holds every creature and ride ready-made: " + before.size());
        List<String> wrong = new ArrayList<>();
        for (String key : before.keySet()) {
            BuilderSpec.Kind kind = BuilderSpec.Kind.of(key.substring(0, key.indexOf('/')));
            String preset = key.substring(key.indexOf('/') + 1);
            JsonArray now = BuilderStarterGolden.lowered(BuilderSpec.read(BuilderStarterGolden.saved(kind, preset)));
            if (!normal(withReviewedStats(withShipped(withNatural(before.getAsJsonArray(key), preset), preset), preset)).equals(normal(now))) {
                wrong.add(key + "\n  before: " + before.get(key) + "\n  now:    " + now);
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void theFixtureCoversEveryReadyMadeStillOffered() throws Exception {
        JsonObject before = fixture();
        for (BuilderSpec.Kind kind : List.of(BuilderSpec.Kind.CREATURE, BuilderSpec.Kind.RIDE)) {
            for (String p : BuilderStarterGolden.starters(kind)) {
                assertTrue(before.has(kind.id() + "/" + p), kind.id() + "/" + p + " is offered and not pinned");
            }
        }
        assertEquals(before.size(), BuilderStarterGolden.starters(BuilderSpec.Kind.CREATURE).size()
                + BuilderStarterGolden.starters(BuilderSpec.Kind.RIDE).size(), "a pinned ready-made is no longer offered");
    }
}
