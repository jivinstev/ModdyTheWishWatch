package com.moddingfromamod.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <b>A resource that another mod's jar may ship</b>: this mod's own jar first, then every loaded mod's file.
 *
 * <p>Under FML each mod is its own module, so {@code Class.getResourceAsStream} from a core class never sees a file in
 * another mod's jar. A mod built on this one (a level, or a server's own extension) can ship data the core reads by
 * name, such as a world's manifest ({@code worlds/<id>.json}) or the creator list ({@code creators/}); this is the one
 * place that looks for it. With no mod loader (a unit test) the own jar, which is the classpath, is the only place.
 */
public final class ModResources {

    private ModResources() {
    }

    /** The text of {@code /<dir>/<name>}: from this mod's jar, else from the first loaded mod that ships it; null if none. */
    public static String text(String dir, String name) {
        String own = ownJar("/" + dir + "/" + name);
        return own != null ? own : modFiles(dir, name);
    }

    /**
     * The bytes of an absolute resource path ({@code /starter-library/index.txt}): from this mod's jar, else from the
     * first loaded mod that ships it; null if none. For a class in another mod's jar (the house, a level) reading a file
     * the core ships, or the reverse — its own {@code getResourceAsStream} sees only its own module.
     */
    public static byte[] bytes(String resource) {
        String path = resource.startsWith("/") ? resource : "/" + resource;
        try (InputStream in = ModResources.class.getResourceAsStream(path)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException | RuntimeException e) {
            // fall through to the other mods
        }
        Path p = modFile(path.substring(1).split("/"));
        try {
            return p == null ? null : Files.readAllBytes(p);
        } catch (IOException e) {
            return null;
        }
    }

    /** {@link #bytes} as a stream, or null; the caller closes it. */
    public static InputStream open(String resource) {
        byte[] b = bytes(resource);
        return b == null ? null : new java.io.ByteArrayInputStream(b);
    }

    /** {@link #bytes} as UTF-8 text, or null. */
    public static String text(String resource) {
        byte[] b = bytes(resource);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static Path modFile(String... path) {
        net.neoforged.fml.ModList mods;
        try {
            mods = net.neoforged.fml.ModList.get();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        if (mods == null) {
            return null;
        }
        for (net.neoforged.neoforgespi.language.IModFileInfo info : mods.getModFiles()) {
            try {
                Path p = info.getFile().findResource(path);
                if (p != null && Files.isRegularFile(p)) {
                    return p;
                }
            } catch (RuntimeException e) {
                // a broken mod file is not this resource's: the next mod
            }
        }
        return null;
    }

    private static String ownJar(String resource) {
        try (InputStream in = ModResources.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String modFiles(String dir, String name) {
        net.neoforged.fml.ModList mods;
        try {
            mods = net.neoforged.fml.ModList.get();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        if (mods == null) {
            return null;   // no mod loader: the own jar was the only place to look
        }
        for (net.neoforged.neoforgespi.language.IModFileInfo info : mods.getModFiles()) {
            try {
                Path p = info.getFile().findResource(dir, name);
                if (p != null && Files.isRegularFile(p)) {
                    return Files.readString(p, StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException e) {
                // a broken mod file is not this resource's: the next mod
            }
        }
        return null;
    }
}
