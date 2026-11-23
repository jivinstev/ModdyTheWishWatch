package com.moddingfromamod.wish;

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
 * <b>The command tree a build really registers</b>, without booting Minecraft: every class in the mod's own
 * classes directory that names {@link RegisterCommandsEvent} is loaded, and each of its static handlers is handed one
 * event over a fresh dispatcher, as NeoForge does at server start. On {@code publicTest}'s classpath that is the public
 * build alone (no private class, no plug-in); on {@code test}'s it is the whole mod. A handler that needs registries
 * gets a build context that answers every lookup with an empty one (registration only stores it).
 *
 * <p>Used by {@code PublicCommandsReadmeTest} and the private build's matching check.
 */
public final class CommandTree {

    private CommandTree() {
    }

    private static final byte[] EVENT = "net/neoforged/neoforge/event/RegisterCommandsEvent".getBytes(StandardCharsets.UTF_8);

    /** Every root the build registers, with every literal under it. */
    public static CommandDispatcher<CommandSourceStack> build() throws Exception {
        CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        RegisterCommandsEvent ev = new RegisterCommandsEvent(d, Commands.CommandSelection.INTEGRATED, emptyContext());
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
    public static TreeSet<String> paths(CommandDispatcher<CommandSourceStack> d) {
        TreeSet<String> out = new TreeSet<>();
        for (CommandNode<CommandSourceStack> root : d.getRoot().getChildren()) {
            out.add(root.getName());
            if (root.getName().equals("moddy") || root.getName().equals("moddyop")) {
                for (CommandNode<CommandSourceStack> b : root.getChildren()) {
                    if (b instanceof LiteralCommandNode) {
                        out.add(root.getName() + " " + b.getName());
                    }
                }
            }
        }
        return out;
    }

    private static List<Class<?>> handlers() throws Exception {
        Path dir = Path.of(WishCommands.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> out = new ArrayList<>();
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
        return out;
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
