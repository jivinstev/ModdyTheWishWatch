package com.moddingfromamod.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginsTest {

    interface Greeter { String hi(); }

    /** Listed in src/test/resources/META-INF/services, so ServiceLoader really finds it. */
    public static final class TestPlugin implements ModdyPlugin {
        @Override public String name() { return "test"; }
        @Override public void register(Plugins plugins) { plugins.add(Greeter.class, () -> "from test"); }
    }

    /** Also listed; it throws, and must be skipped without stopping the others. */
    public static final class BrokenPlugin implements ModdyPlugin {
        @Override public String name() { return "broken"; }
        @Override public void register(Plugins plugins) { throw new IllegalStateException("boom"); }
    }

    @Test
    void serviceLoaderFindsListedPluginsAndSkipsABrokenOne() {
        Plugins p = new Plugins();
        List<String> log = new ArrayList<>();
        p.load(PluginsTest.class.getClassLoader(), log::add);
        // The main jar's own plug-ins, if any, load too; this asks only about the test's two.
        assertTrue(p.loaded().contains("test"), "the listed plug-in loads: " + p.loaded());
        assertTrue(p.loaded().stream().noneMatch(n -> n.contains("broken")), "the broken one does not: " + p.loaded());
        assertEquals("from test", p.first(Greeter.class).orElseThrow().hi());
        assertTrue(log.stream().anyMatch(l -> l.contains("FAILED") && l.contains("BrokenPlugin")), String.valueOf(log));
    }

    @Test
    void aListedClassThatIsMissingIsSkippedNotFatal() {
        // the public jar ships with private plug-in lines stripped; a stale line naming a class that is not
        // in the jar makes ServiceLoader's ITERATOR throw ServiceConfigurationError, outside register(). The mod's
        // constructor calls load(), so that must be skipped and logged, never crash the game.
        Plugins p = new Plugins();
        List<String> log = new ArrayList<>();
        p.load(PluginsTest.class.getClassLoader(), log::add);
        assertTrue(p.loaded().contains("test"), "the good plug-in still loads: " + p.loaded());
        assertTrue(log.stream().anyMatch(l -> l.contains("NoSuchPluginStrippedFromThisJar")), String.valueOf(log));
    }

    @Test
    void aSecondLoadDoesNothing() {
        Plugins p = new Plugins();
        p.load(PluginsTest.class.getClassLoader(), s -> { });
        p.load(PluginsTest.class.getClassLoader(), s -> { });
        assertEquals(1, p.all(Greeter.class).size(), "loading twice must not register twice");
    }

    @Test
    void anAbsentHookIsEmptyNotAnError() {
        Plugins p = new Plugins();
        assertTrue(p.all(Greeter.class).isEmpty());
        assertTrue(p.first(Greeter.class).isEmpty());
    }

    /** a GameTest can drive the core as the public mod runs it, and put the plug-in back after. */
    @Test
    void aHiddenHookReadsAsAbsentUntilShownAgain() {
        Plugins p = new Plugins();
        p.add(Greeter.class, () -> "a");
        p.hideForTest(Greeter.class, true);
        assertTrue(p.all(Greeter.class).isEmpty());
        assertTrue(p.first(Greeter.class).isEmpty());
        p.hideForTest(Greeter.class, false);
        assertEquals(1, p.all(Greeter.class).size());
    }

    /** a GameTest drives the core with an example plug-in standing in for every registered one. */
    @Test
    void aReplacedHookAnswersOnlyTheStandInUntilPutBack() {
        Plugins p = new Plugins();
        p.add(Greeter.class, () -> "a");
        p.add(Greeter.class, () -> "b");
        p.replaceForTest(Greeter.class, () -> "x");
        assertEquals(List.of("x"), p.all(Greeter.class).stream().map(Greeter::hi).toList());
        assertEquals("x", p.first(Greeter.class).orElseThrow().hi());
        p.replaceForTest(Greeter.class, null);
        assertEquals(List.of("a", "b"), p.all(Greeter.class).stream().map(Greeter::hi).toList());
    }

    @Test
    void registrationOrderIsKept() {
        Plugins p = new Plugins();
        p.add(Greeter.class, () -> "a");
        p.add(Greeter.class, () -> "b");
        assertEquals(List.of("a", "b"), p.all(Greeter.class).stream().map(Greeter::hi).toList());
    }
}
