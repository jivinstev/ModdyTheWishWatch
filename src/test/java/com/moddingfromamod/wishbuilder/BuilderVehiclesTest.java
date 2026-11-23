package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderSpecTest.j;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Which Builder bodies are vehicles is a fact in their DATA</b> (follow-up).
 * Every body says {@code "vehicle": true|false} in its species file, a vehicle's life is {@code behaves: vehicle}
 * (parked until driven) in both its files, and it is rideable — so a new body cannot slip into the creature parts
 * page by being forgotten, and a car no longer wanders. And the life reaches the made thing: the lowering hands
 * {@code behaves_like} to {@code sculpt_creature}.
 */
class BuilderVehiclesTest {

    /** The vehicles, pinned: a body that stops being one, or a new one nobody marked, fails here by name. */
    static final Set<String> VEHICLES = Set.of("boat", "car", "flying_saucer", "helicopter", "hot_air_balloon",
            "motorbike", "plane", "rocket", "submarine", "tank", "truck");

    static List<String> bodies() throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = BuilderVehiclesTest.class.getClassLoader().getResourceAsStream("builder-bodies/index.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.strip();
                if (line.startsWith("species/")) {
                    out.add(line.substring("species/".length()));
                }
            }
        }
        assertTrue(out.size() > 40, "the bodies index lists " + out.size());
        return out;
    }

    @Test
    @DisplayName("every Builder body says whether it is a vehicle, and the vehicles are the pinned set")
    void everyBodySaysWhetherItIsAVehicle() throws IOException {
        List<String> wrong = new ArrayList<>();
        Set<String> marked = new TreeSet<>();
        for (String b : bodies()) {
            JsonObject sp = LibraryPresets.SHIPPED.species("bodies/" + b);
            JsonObject sc = LibraryPresets.SHIPPED.read("bodies/" + b);
            if (sp == null || !sp.has("vehicle") || !sp.get("vehicle").isJsonPrimitive()
                    || !sp.get("vehicle").getAsJsonPrimitive().isBoolean()) {
                wrong.add(b + ": no \"vehicle\": true|false in species.json");
                continue;
            }
            boolean v = sp.get("vehicle").getAsBoolean();
            if (v) {
                marked.add(b);
            }
            boolean lives = "vehicle".equals(str(sp, "behaves")) && "vehicle".equals(str(sc, "behaves_like"));
            if (v != lives) {
                wrong.add(b + ": vehicle=" + v + " but behaves=" + str(sp, "behaves") + " / behaves_like=" + str(sc, "behaves_like"));
            }
            if (v && !(sp.get("powers").toString().contains("\"rideable\"") && sc.get("powers").toString().contains("\"rideable\""))) {
                wrong.add(b + ": a vehicle nobody can ride (powers lack rideable)");
            }
            if (BuilderParts.vehicle("bodies/" + b, sp) != v) {
                wrong.add(b + ": BuilderParts.vehicle disagrees with the marker");
            }
        }
        assertEquals(List.of(), wrong);
        assertEquals(new TreeSet<>(VEHICLES), marked, "the vehicles");
    }

    @Test
    @DisplayName("a made vehicle gets its life: the lowering hands behaves_like to sculpt_creature (the car included)")
    void aMadeVehicleIsParkedUntilDriven() {
        for (String b : List.of("car", "truck", "boat")) {
            for (String kind : List.of("ride", "creature")) {
                JsonObject a = sculpt(BuilderSpec.read(j("{\"builder\":1,\"kind\":\"" + kind
                        + "\",\"from\":\"preset:bodies/" + b + "\"}")));
                assertEquals("vehicle", str(a, "behaves_like"), kind + " → " + b + ": " + a);
                assertTrue(a.get("powers").toString().contains("\"rideable\""), kind + " → " + b + ": rideable");
            }
        }
        // ...and a body with a creature's own life keeps it (a butterfly buzzes)
        JsonObject bf = sculpt(BuilderSpec.read(j("{\"builder\":1,\"kind\":\"creature\","
                + "\"from\":\"preset:bodies/butterfly\"}")));
        assertEquals("buzzer", str(bf, "behaves_like"));
    }

    /** The sculpt_creature call a spec lowers to. */
    static JsonObject sculpt(BuilderSpec spec) {
        return BuilderLowering.lower(spec, "mine_1", LibraryPresets.SHIPPED).stream()
                .filter(c -> c.tool().equals("sculpt_creature")).findFirst().orElseThrow().args();
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
