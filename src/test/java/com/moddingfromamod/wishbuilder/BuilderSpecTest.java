package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** a spec is a stored record of what a child chose — it round-trips and is never read loosely. */
class BuilderSpecTest {

    static JsonObject j(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void aCurrentSpecRoundTrips() {
        BuilderSpec s = BuilderSpec.read(j("{\"builder\":1,\"kind\":\"ride\",\"from\":\"preset:species/pet_dragon\","
                + "\"scheme\":\"ocean\",\"size\":3,\"powers\":[\"power:flies\"],\"temper\":\"friendly\",\"count\":2,"
                + "\"at\":\"me:front\",\"name\":\" Sparky \",\"opts\":{\"k\":\"v\"}}"));
        assertEquals(BuilderSpec.Kind.RIDE, s.kind());
        assertEquals("Sparky", s.name(), "the name is stripped");
        assertEquals(List.of("power:flies"), s.powers());
        assertEquals(s, BuilderSpec.read(s.toJson()), "what it writes, it reads back the same");
    }

    @Test
    void defaultsAreTheDialogsOwnInitialValues() {
        BuilderSpec s = BuilderSpec.read(j("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\"}"));
        assertEquals(2, s.size());
        assertEquals(1, s.count());
    }

    @Test
    void anUnknownKindOrANonNumberIsRefusedByName() {
        var e = assertThrows(IllegalArgumentException.class, () -> BuilderSpec.read(j("{\"builder\":1,\"kind\":\"spaceship\"}")));
        assertTrue(e.getMessage().contains("spaceship"));
        var n = assertThrows(IllegalArgumentException.class,
                () -> BuilderSpec.read(j("{\"builder\":1,\"kind\":\"creature\",\"size\":\"huge\"}")));
        assertTrue(n.getMessage().contains("size"));
    }

    @Test
    void everyKindHasAnIdThatReadsBack() {
        for (BuilderSpec.Kind k : BuilderSpec.Kind.values()) {
            assertEquals(k, BuilderSpec.Kind.of(k.id()));
        }
    }
}
