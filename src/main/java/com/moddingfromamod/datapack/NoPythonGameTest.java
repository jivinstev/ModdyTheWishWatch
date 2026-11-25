package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.moddingfromamod.testing.GateShards;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * <b>A real Magician datapack wish starts no Python</b> (jivinstev/ModdyTheWishWatch#109). The Magician's powers
 * used to be compiled by a {@code python3} process; the compiler is now in the jar. This drives the real
 * {@code author_datapack} tool, the one the wish agent calls, with the real compiler, while a watcher lists every
 * process the game starts, and fails on any Python among them. {@code NoPythonTest} (Gate A) holds the sources to the
 * same; the release check runs Gate B with no python3 on PATH at all.
 */
@GameTestHolder(GateShards.CI)
public final class NoPythonGameTest {

    private NoPythonGameTest() {
    }

    /** Watches the game's child processes (and theirs) until stopped, and names any that is Python. */
    static final class ProcessWatch implements AutoCloseable {
        final Set<String> python = ConcurrentHashMap.newKeySet();
        final AtomicBoolean on = new AtomicBoolean(true);
        final Thread thread;

        ProcessWatch() {
            thread = new Thread(() -> {
                while (on.get()) {
                    look();
                    Thread.onSpinWait();
                }
                look();
            }, "no-python-watch");
            thread.setDaemon(true);
            thread.start();
        }

        private void look() {
            ProcessHandle.current().descendants().forEach(h -> {
                var info = h.info();
                String cmd = info.command().orElse("") + " " + String.join(" ", info.arguments().orElse(new String[0]));
                String name = cmd.toLowerCase(java.util.Locale.ROOT);
                if (name.contains("python") || name.contains(".py ") || name.endsWith(".py")) {
                    python.add(h.pid() + ": " + cmd.strip());
                }
            });
        }

        @Override
        public void close() throws InterruptedException {
            on.set(false);
            thread.join(5_000);
        }
    }

    @GameTest(template = "empty_test", batch = "datapackRefuse")
    public static void aMagicianDatapackWishStartsNoPython(GameTestHelper helper) {
        String id = "gt_nopy_" + helper.getLevel().getGameTime();
        String source = "clock tick\n"
                + "    /execute as @e[tag=gtnopy] at @s as "
                + "@e[nbt={HurtTime:10s},distance=..6,tag=!gtnopy] run "
                + "effect give @s minecraft:glowing 3 0\n"
                + "end\n";
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("feature_id", id);
        args.addProperty("description", "gt no python");
        args.addProperty("cbscript", source);
        args.addProperty("note", "gate: a datapack wish compiles with no Python");

        String[] said = new String[1];
        List<String> python = new ArrayList<>();
        try (ProcessWatch watch = new ProcessWatch()) {
            com.moddingfromamod.wish.BuiltAbility.inIsolationForTest(() -> {
                try {
                    new com.moddingfromamod.agent.tools.AuthorDatapackTool().call(args);
                } catch (Throwable pastTheCompile) {
                    // Expected: the server hop (a GameTest body IS the server thread). The compile is done by here.
                }
                said[0] = com.moddingfromamod.wish.BuiltAbility.describe();
            });
            watch.close();
            python.addAll(watch.python);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        helper.assertTrue(python.isEmpty(), Component.literal("a datapack wish started Python: " + python));
        // The trigger sentence is recorded after a successful compile, so a blank one means the compile failed and
        // this proved nothing.
        helper.assertFalse(said[0] == null || said[0].isBlank(),
                Component.literal("the wish's pack did not compile, so this proved nothing about Python"));
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void theCompilerIsThereWithoutAsking(GameTestHelper helper) {
        // The Magician setup used to probe for Python 3 and tell the player to install it. There is nothing to probe.
        helper.assertTrue(CbScriptCompiler.available(), Component.literal("the CBScript compiler is part of the mod"));
        helper.succeed();
    }
}
