package com.moddingfromamod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * every manifest reader goes through {@link WorldFacts#manifestText}, which looks in the core's own jar and then
 * in every loaded mod's file, so a LEVEL jar's {@code worlds/<id>.json} is found (under FML a mod's resources are
 * invisible to another mod's {@code Class.getResourceAsStream}). The loaded-mods half needs a running mod loader and is
 * proven in a real client (a level jar's playtests); this pins the order and the guards.
 */
class WorldFactsManifestTextTest {

    @Test
    @DisplayName("the first source with text wins: the core's own jar before any mod's")
    void firstSourceWins() {
        List<Supplier<String>> own = List.of(() -> "{\"id\":\"own\"}", () -> "{\"id\":\"mod\"}");
        assertEquals("{\"id\":\"own\"}", WorldFacts.firstOf(own));
        List<Supplier<String>> modOnly = List.of(() -> null, () -> "{\"id\":\"mod\"}");
        assertEquals("{\"id\":\"mod\"}", WorldFacts.firstOf(modOnly));
    }

    @Test
    @DisplayName("a source that throws is skipped, and nothing anywhere is null")
    void brokenSourceIsSkipped() {
        List<Supplier<String>> broken = List.of(() -> {
            throw new IllegalStateException("a broken mod file");
        }, () -> "{\"id\":\"mod\"}");
        assertEquals("{\"id\":\"mod\"}", WorldFacts.firstOf(broken));
        assertNull(WorldFacts.firstOf(List.of(() -> null, () -> null)));
    }

    @Test
    @DisplayName("an id that is not a manifest id reads nothing (no path is built from it)")
    void badIdReadsNothing() {
        assertNull(WorldFacts.manifestText(null));
        assertNull(WorldFacts.manifestText("../secrets"));
        assertNull(WorldFacts.manifestText("Isle"));
        assertNull(WorldFacts.manifestText("no-such-world"));   // no loader in a unit test: the own jar only
    }
}
