package com.moddingfromamod.datapack;

import java.util.List;

import com.moddingfromamod.testing.GateShards;
import com.moddingfromamod.testing.ProcessStarts;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * <b>A real Magician datapack wish starts no Python</b> (jivinstev/ModdyTheWishWatch#109). The Magician's powers
 * used to be compiled by a {@code python3} process; the compiler is now in the jar. This drives the real
 * {@code author_datapack} tool, the one the wish agent calls, with the real compiler, while JFR records every
 * process the game starts, and fails on any Python among them. {@code NoPythonTest} (Gate A) holds the sources to the
 * same; the release check runs Gate B with no python3 on PATH at all.
 */
@GameTestHolder(GateShards.CI)
public final class NoPythonGameTest {

    private NoPythonGameTest() {
    }

    static boolean isPython(String command) {
        String c = command.toLowerCase(java.util.Locale.ROOT);
        return c.contains("python") || c.matches("(?s).*\\.py(\\s.*)?");
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
        List<String> started;
        try (ProcessStarts watch = new ProcessStarts()) {
            // THE CONTROL: a process this recording must see, so a recorder that sees nothing cannot pass.
            new ProcessBuilder("true").redirectErrorStream(true).start().waitFor();
            com.moddingfromamod.wish.BuiltAbility.inIsolationForTest(() -> {
                try {
                    new com.moddingfromamod.agent.tools.AuthorDatapackTool().call(args);
                } catch (Throwable pastTheCompile) {
                    // Expected: the server hop (a GameTest body IS the server thread). The compile is done by here.
                }
                said[0] = com.moddingfromamod.wish.BuiltAbility.describe();
            });
            started = watch.stop();
        } catch (Exception e) {
            throw com.moddingfromamod.testing.gt.GameTestCompat.assertion("could not record process starts: " + e);
        }
        helper.assertTrue(started.stream().anyMatch(c -> ProcessStarts.startsProgram(c, "true")),
                Component.literal("the process recorder did not see its own control process, so it proves nothing: "
                        + started));
        List<String> python = started.stream().filter(NoPythonGameTest::isPython).toList();
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
