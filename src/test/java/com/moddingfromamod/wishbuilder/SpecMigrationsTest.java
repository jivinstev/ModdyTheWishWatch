package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderSpecTest.j;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** every stored version upgrades; a version this build cannot read is refused, never guessed. */
class SpecMigrationsTest {

    /** The B0u prototype's real echo (Gate C and the Bedrock phone), verbatim. */
    static final String V0 = "{\"builder\":0,\"kind\":\"creature\",\"from\":{\"preset\":\"dragon\"},"
            + "\"powers\":[\"power:flies\",\"power:rideable\"],\"look\":{\"size\":3,\"scheme\":\"forest\"},"
            + "\"count\":2,\"name\":\"Blaze Jr\"}";

    @Test
    void theV0PrototypeEchoUpgrades() {
        JsonObject up = SpecMigrations.upgrade(j(V0));
        assertEquals(1, up.get("builder").getAsInt());
        assertEquals("preset:dragon", up.get("from").getAsString());
        assertFalse(up.has("look"), "look is flattened");
        BuilderSpec s = BuilderSpec.read(j(V0));
        assertEquals(3, s.size());
        assertEquals("forest", s.scheme());
        assertEquals(2, s.count());
        assertEquals("Blaze Jr", s.name());
        assertEquals(List.of("power:flies", "power:rideable"), s.powers());
    }

    @Test
    void aMobFromUpgradesToAMobId() {
        JsonObject up = SpecMigrations.upgrade(j("{\"builder\":0,\"kind\":\"creature\",\"from\":{\"mob\":\"minecraft:cow\"}}"));
        assertEquals("mob:minecraft:cow", up.get("from").getAsString());
    }

    @Test
    void theStoredObjectIsNeverChanged() {
        JsonObject stored = j(V0);
        SpecMigrations.upgrade(stored);
        assertEquals(j(V0), stored);
    }

    @Test
    void aNewerOrMissingVersionIsRefused() {
        var newer = assertThrows(IllegalArgumentException.class,
                () -> SpecMigrations.upgrade(j("{\"builder\":" + (BuilderSpec.CURRENT + 1) + ",\"kind\":\"creature\"}")));
        assertTrue(newer.getMessage().contains("newer"));
        assertThrows(IllegalArgumentException.class, () -> SpecMigrations.upgrade(j("{\"kind\":\"creature\"}")));
        assertThrows(IllegalArgumentException.class, () -> SpecMigrations.upgrade(j("{\"builder\":-1}")));
    }
}
