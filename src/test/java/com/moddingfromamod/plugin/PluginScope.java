package com.moddingfromamod.plugin;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * {@code @ExtendWith(PluginScope.class)}: the test class runs with a registry of its OWN as {@link Plugins#shared()}
 *, empty to begin with, so whatever it adds (a stub hook) is seen only by it and gone when the class ends.
 * The mod's real registry is never touched. {@link RealPlugins} is the same with the real private plug-ins loaded.
 *
 * <p>Why: the real registry has no remove, and a unit test that loaded the plug-ins into it left them behind
 * for every later test in its JVM. Tests then passed or failed by class order ({@code safety/MuseFallbackTest},
 * {@code vanilla/PackHostCanaryTest}).
 */
public class PluginScope implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(PluginScope.class);

    /** The registry this class's tests see; override to fill it. */
    protected Plugins fresh() {
        return new Plugins();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        Plugins mine = fresh();
        Plugins was = Plugins.scopeForTest(mine);
        context.getStore(NS).put("was", was == null ? NONE : was);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Object was = context.getStore(NS).remove("was");
        Plugins.scopeForTest(was instanceof Plugins p ? p : null);
    }

    private static final Object NONE = new Object();
}
