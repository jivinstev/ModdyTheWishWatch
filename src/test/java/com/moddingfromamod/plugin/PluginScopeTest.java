package com.moddingfromamod.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * a unit test's plug-ins stay its own. The real registry is the one the mod loads at start; in a unit JVM
 * nothing may load or add to it, so it is EMPTY here whatever ran before in this fork. That assertion is the canary: if
 * a test leaks a plug-in into the real registry again, it fails here in whichever order the classes ran.
 */
class PluginScopeTest {

    interface Hook {
    }

    @Test
    void theRealRegistryIsEmptyInAUnitJvmWhateverRanBefore() {
        Plugins real = Plugins.shared();
        assertEquals(java.util.List.of(), real.hookTypes(), "a unit test left hooks in the mod's one registry "
                + "(use @ExtendWith(PluginScope.class) or RealPlugins, never Plugins.shared().load/add)");
        assertEquals(java.util.List.of(), real.loaded(), "a unit test loaded the private plug-ins into the mod's one "
                + "registry (use @ExtendWith(RealPlugins.class))");
    }

    @Test
    void aScopeIsSeenWhileItLastsAndThenTheRealOneIsBack() {
        Plugins real = Plugins.shared();
        Plugins mine = new Plugins();
        Plugins was = Plugins.scopeForTest(mine);
        try {
            assertSame(mine, Plugins.shared());
            Plugins.shared().add(Hook.class, new Hook() { });
            assertEquals(1, mine.all(Hook.class).size());
        } finally {
            Plugins.scopeForTest(was);
        }
        assertSame(real, Plugins.shared());
        assertTrue(real.all(Hook.class).isEmpty(), "the scope's hook leaked into the real registry");
    }
}
