package com.moddingfromamod.plugin;

/**
 * {@code @ExtendWith(RealPlugins.class)}: the test class sees the REAL private plug-ins, loaded as the mod loads them at
 * start (the services list), but into a registry of its own ({@link PluginScope}). The next test class in the
 * JVM sees the public mod again, as it would run alone.
 */
public final class RealPlugins extends PluginScope {

    @Override
    protected Plugins fresh() {
        Plugins p = new Plugins();
        p.load(RealPlugins.class.getClassLoader(), l -> { });
        return p;
    }
}
