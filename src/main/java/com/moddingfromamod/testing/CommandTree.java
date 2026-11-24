package com.moddingfromamod.testing;

import com.moddingfromamod.wish.WishCommands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * <b>The command tree a build really registers</b>: every class in the mod's own
 * classes directory that names {@link RegisterCommandsEvent} is loaded, and each of its static handlers is handed one
 * event over a fresh dispatcher, as NeoForge does at server start. On {@code publicTest}'s classpath that is the public
 * build alone (no private class, no plug-in); on {@code test}'s it is the whole mod. A handler that needs registries
 * gets a build context that answers every lookup with an empty one (registration only stores it).
 *
 * <p>From Minecraft 1.21.11 this needs a running game: {@code Commands}' static {@code PermissionCheck} fields reach
 * {@code BuiltInRegistries}, which refuses to load before Bootstrap, and Bootstrap needs FML. So its user is
 * {@code PublicCommandsReadmeGameTest} (Gate B), which hands {@link #build(CommandBuildContext)} the live server's
 * context; {@link #paths} is pure brigadier and has its own unit test.
 */
public final class CommandTree {

    private CommandTree() {
    }

    private static final byte[] EVENT = "net/neoforged/neoforge/event/RegisterCommandsEvent".getBytes(StandardCharsets.UTF_8);

    /** Where {@link #build} looked for handlers, and how many it found: for a failure message. */
    public static String describeSearch() {
        try {
            return "class dirs " + modClassDirs() + ", code source "
                    + WishCommands.class.getProtectionDomain().getCodeSource().getLocation() + ", " + handlers().size()
                    + " handler class(es)";
        } catch (Exception e) {
            return "class dirs unreadable: " + e;
        }
    }

    /** Every root the build registers, with every literal under it. */
    public static CommandDispatcher<CommandSourceStack> build() throws Exception {
        return build(emptyContext());
    }

    /** {@link #build()} with a real build context (a GameTest's: the live server's registries). */
    public static CommandDispatcher<CommandSourceStack> build(CommandBuildContext context) throws Exception {
        CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        RegisterCommandsEvent ev = new RegisterCommandsEvent(d, Commands.CommandSelection.INTEGRATED, context);
        for (Class<?> c : handlers()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == RegisterCommandsEvent.class) {
                    m.setAccessible(true);
                    m.invoke(null, ev);
                }
            }
        }
        return d;
    }

    /**
     * The commands as a README names them: each root ({@code wish}, {@code paint}, …) and, under {@code moddy} and
     * {@code moddyop}, each first-level branch ({@code moddy magician}).
     */
    public static <S> TreeSet<String> paths(CommandDispatcher<S> d) {
        TreeSet<String> out = new TreeSet<>();
        for (CommandNode<S> root : d.getRoot().getChildren()) {
            out.add(root.getName());
            if (root.getName().equals("moddy") || root.getName().equals("moddyop")) {
                for (CommandNode<S> b : root.getChildren()) {
                    if (b instanceof LiteralCommandNode) {
                        out.add(root.getName() + " " + b.getName());
                    }
                }
            }
        }
        return out;
    }

    private static List<Class<?>> handlers() throws Exception {
        List<Class<?>> out = new ArrayList<>();
        for (Path dir : modClassDirs()) {
            handlersIn(dir, out);
        }
        return out;
    }

    /**
     * The core's classes, plus every other mod's the build put on this classpath (the house's and each level's
     * source set, build/classes/java/&lt;name&gt;); never the test classes or the public half's copy of the core.
     */
    private static List<Path> modClassDirs() throws Exception {
        Path core = coreClasses();
        List<Path> dirs = new ArrayList<>(List.of(core));
        for (String e : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            Path p = Path.of(e);
            Path parent = p.getParent();
            if (Files.isDirectory(p) && parent != null && parent.equals(core.getParent()) && !p.equals(core)
                    && !List.of("test", "public", "publicTest").contains(p.getFileName().toString())) {
                dirs.add(p);
            }
        }
        return dirs;
    }

    /**
     * The mod's own classes directory: its code source when that holds the classes (a unit test's classpath), else the
     * checkout's build/classes/java/main (a dev run's code source is build/resources/main, the classes served beside
     * it).
     */
    private static Path coreClasses() throws Exception {
        try {
            Path p = Path.of(WishCommands.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(p.resolve("com/moddingfromamod/wish/WishCommands.class"))) {
                return p;
            }
        } catch (RuntimeException ignored) {
            // not a file: URI (a union filesystem); look for the checkout instead
        }
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("build/classes/java/main/com/moddingfromamod"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cannot find the mod's classes directory from " + System.getProperty("user.dir"));
        }
        return p.resolve("build/classes/java/main");
    }

    private static void handlersIn(Path dir, List<Class<?>> out) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                if (!contains(Files.readAllBytes(f), EVENT)) {
                    continue;
                }
                String name = dir.relativize(f).toString().replace(java.io.File.separatorChar, '.');
                out.add(Class.forName(name.substring(0, name.length() - ".class".length()), false,
                        CommandTree.class.getClassLoader()));
            }
        }
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static CommandBuildContext emptyContext() {
        Object lookup = Proxy.newProxyInstance(CommandTree.class.getClassLoader(),
                new Class<?>[] {HolderLookup.RegistryLookup.class}, (p, m, a) -> switch (m.getName()) {
                    case "listElements", "listTags", "listElementIds", "listTagIds" -> Stream.empty();
                    case "get" -> Optional.empty();
                    case "toString" -> "empty lookup";
                    case "hashCode" -> 0;
                    case "equals" -> p == a[0];
                    default -> null;
                });
        return (CommandBuildContext) Proxy.newProxyInstance(CommandTree.class.getClassLoader(),
                new Class<?>[] {CommandBuildContext.class}, (p, m, a) -> switch (m.getName()) {
                    case "lookup" -> Optional.of(lookup);
                    case "lookupOrThrow" -> lookup;
                    case "listRegistryKeys" -> Stream.empty();
                    case "toString" -> "empty build context";
                    case "hashCode" -> 0;
                    case "equals" -> p == a[0];
                    default -> null;
                });
    }
}
