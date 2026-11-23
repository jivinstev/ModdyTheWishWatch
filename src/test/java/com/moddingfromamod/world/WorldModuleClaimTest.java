package com.moddingfromamod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * which world module runs is decided PER WORLD when a level claims it ({@link WorldModuleType#claims}): a
 * house's JVM property still wins, a claim comes next, the old "implied when unnamed" last.
 */
class WorldModuleClaimTest {

    private static WorldModuleType type(String id, boolean claims, boolean implied) {
        return new WorldModuleType() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public WorldModule create() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean impliedWhenUnnamed() {
                return implied;
            }

            @Override
            public boolean claims(net.minecraft.server.MinecraftServer server) {
                if ("broken".equals(id)) {
                    throw new IllegalStateException("cannot read the world");
                }
                return claims;
            }
        };
    }

    @Test
    @DisplayName("the property wins, then the world's claim, then the implied module; 'none' is none")
    void order() {
        assertEquals("isle", WorldModuleHost.chooseModule("isle", "arena", "islands"));
        assertEquals("arena", WorldModuleHost.chooseModule(null, "arena", "isle"));
        assertEquals("arena", WorldModuleHost.chooseModule("", "arena", ""));
        assertEquals("isle", WorldModuleHost.chooseModule(null, "", "isle"));
        assertEquals("", WorldModuleHost.chooseModule("none", "arena", "isle"));
        assertEquals("", WorldModuleHost.chooseModule(null, "", ""), "a plain world in an instance with level jars");
    }

    @Test
    @DisplayName("the first module that claims this world is the claimed one; one that throws is skipped")
    void claimed() {
        Map<String, WorldModuleType> types = new LinkedHashMap<>();
        types.put("broken", type("broken", true, false));
        types.put("isle", type("isle", false, true));
        types.put("arena", type("arena", true, false));
        assertEquals("arena", WorldModuleHost.claimedModule(types, null));
        types.remove("arena");
        assertEquals("", WorldModuleHost.claimedModule(types, null), "nobody claims a plain world");
    }
}
