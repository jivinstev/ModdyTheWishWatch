package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * a Builder or Apprentice creature does what its body does by itself — a dragon flies and
 * never burns — without a pick, through the real lowering, and the Python reference agrees on every body.
 */
class BuilderNaturalTest {

    static final Map<String, List<String>> EXPECTED = Map.ofEntries(
            Map.entry("species/pet_dragon", List.of("flies", "fireproof")),
            Map.entry("species/trex", List.of("leaps", "fireproof")),
            Map.entry("species/fire_imp", List.of("fireproof")),
            Map.entry("species/frog", List.of("leaps")),
            Map.entry("species/cat", List.of()),
            Map.entry("bodies/rabbit", List.of("hops")),
            Map.entry("bodies/raptor", List.of("leaps")),
            Map.entry("bodies/phoenix", List.of("flies", "fireproof", "glowing")),
            Map.entry("bodies/ghost", List.of("flies", "glowing")),
            Map.entry("bodies/hamster", List.of("naps")),
            Map.entry("bodies/eagle", List.of("flies")),
            Map.entry("bodies/plane", List.of("flies")),
            Map.entry("bodies/truck", List.of()));

    @Test
    void eachBodyDoesWhatItsKindDoes() {
        EXPECTED.forEach((id, want) ->
                assertEquals(want, BuilderNatural.of(id, LibraryPresets.SHIPPED.read(id)), id));
    }

    @Test
    void aBuilderDragonFliesNeverBurnsAndLivesLikeADragon() {
        JsonObject a = sculpt("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/pet_dragon\"}");
        List<String> powers = strings(a.getAsJsonArray("powers"));
        assertTrue(powers.contains("flies") && powers.contains("fireproof"), "powers " + powers);
        assertEquals("dragon", a.get("behaves_like").getAsString());
    }

    @Test
    void aBodyBringsItsMovesAndItsFireproofingNotOnlyFlying() {
        assertTrue(strings(sculpt("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:bodies/rabbit\"}")
                .getAsJsonArray("powers")).contains("hops"));
        List<String> phoenix = strings(sculpt("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:bodies/phoenix\"}")
                .getAsJsonArray("powers"));
        assertTrue(phoenix.containsAll(List.of("flies", "fireproof", "glowing")), "phoenix " + phoenix);
        List<String> trex = strings(sculpt("{\"builder\":1,\"kind\":\"ride\",\"from\":\"preset:species/trex\"}")
                .getAsJsonArray("powers"));
        assertTrue(trex.containsAll(List.of("leaps", "fireproof", "rideable")), "trex " + trex);
    }

    @Test
    void thePowersPageShowsWhatItDoesAlreadyTicked() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("creature");
        f.press("species/pet_dragon");
        assertEquals(List.of("flies", "fireproof"), f.naturalPowers());
        var flies = f.powerTiles().stream().filter(t -> t.id().equals("power:flies")).findFirst().orElseThrow();
        assertTrue(flies.selected(), "Flies is ticked on a dragon");
        assertEquals(List.of(BuilderFlow.NATURAL_LINE), flies.lore());
    }

    @Test
    void theTreeSaysWhatEachBodyDoesForTheApprentice() {
        JsonObject tree = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED).toJson();
        for (var kind : tree.getAsJsonArray("kinds")) {
            if (!kind.getAsJsonObject().get("id").getAsString().equals("creature")) {
                continue;
            }
            for (var form : kind.getAsJsonObject().getAsJsonArray("forms")) {
                for (var slot : form.getAsJsonObject().getAsJsonArray("slots")) {
                    if (!slot.getAsJsonObject().get("id").getAsString().equals("from")) {
                        continue;
                    }
                    for (var e : slot.getAsJsonObject().getAsJsonArray("entries")) {
                        JsonObject en = e.getAsJsonObject();
                        String id = en.get("id").getAsString();
                        if (EXPECTED.containsKey(id) && !EXPECTED.get(id).isEmpty()) {
                            assertEquals(String.join(",", EXPECTED.get(id)),
                                    en.getAsJsonObject("facts").get("has").getAsString(), id);
                        }
                    }
                }
            }
        }
    }

    private static JsonObject sculpt(String spec) {
        for (BuilderLowering.Call c : BuilderLoweringTest.lower(spec)) {
            if (c.tool().equals("sculpt_creature")) {
                return c.args();
            }
        }
        throw new AssertionError("no sculpt_creature for " + spec);
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(e -> out.add(e.getAsString()));
        return out;
    }
}
