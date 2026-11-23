package com.moddingfromamod.datapack;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.AuthorDatapackTool;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * <b>a Magician power with no working Python</b>. The real {@code author_datapack}, after the setup found no
 * Python 3 (the remembered {@link PythonCheck} answer), returns the one clear tool error the agent can relay — Python 3
 * is missing, do not retry or rewrite the source — never a compile failure or a stack trace, and writes no pack.
 *
 * <p>Its own batch: the remembered answer is process-wide, and tests in a batch run concurrently.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PythonMissingPowerGameTest {

    @GameTest(template = "empty_test", timeoutTicks = 2_000_000, batch = "python_missing_power")
    public static void aPowerWithNoPythonGetsTheClearMessage(GameTestHelper helper) {
        String propWas = System.getProperty(ModdingFromAModMod.MODID + ".cbscript.python");
        if (System.getenv("CBSCRIPT_PYTHON") != null && !System.getenv("CBSCRIPT_PYTHON").isBlank()) {
            helper.fail(Component.literal("CBSCRIPT_PYTHON is set, which always wins: unset it to run this test"));
            return;
        }
        System.clearProperty(ModdingFromAModMod.MODID + ".cbscript.python");
        PythonCheck.setLastForTest(new PythonCheck.Verdict(PythonCheck.Kind.STORE_STUB,
                "C:\\Users\\kid\\AppData\\Local\\Microsoft\\WindowsApps\\python.exe", "", ""));
        Runnable putBack = () -> {
            PythonCheck.setLastForTest(null);
            if (propWas != null) {
                System.setProperty(ModdingFromAModMod.MODID + ".cbscript.python", propWas);
            }
        };
        String id = "gt_no_python_" + helper.getLevel().getGameTime();
        JsonObject args = new JsonObject();
        args.addProperty("feature_id", id);
        args.addProperty("description", "gt a power with no python");
        args.addProperty("cbscript", "reset\n    /say a power\nend\n");
        // Called from its own thread, as the agent calls it (the tool hops to the server thread for its work).
        AtomicReference<Object> out = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                out.set(new AuthorDatapackTool().call(args));
            } catch (Throwable e) {
                out.set(e);
            }
        }, "gt-no-python");
        caller.setDaemon(true);
        caller.start();
        long deadline = System.currentTimeMillis() + 60_000L;
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            Object o = out.get();
            if (o == null && System.currentTimeMillis() < deadline) {
                return;
            }
            done[0] = true;
            try {
                helper.assertTrue(o != null, Component.literal("author_datapack did not return within 60 s"));
                helper.assertTrue(o instanceof ToolResult, Component.literal("author_datapack threw: " + o));
                ToolResult r = (ToolResult) o;
                helper.assertTrue(r.isError() && PythonCheck.isAgentMessage(r.text()),
                        Component.literal("not the clear Python message: " + r.text()));
                helper.assertTrue(r.text().contains("Microsoft Store") && r.text().contains("python.org")
                                && !r.text().contains("did not compile") && !r.text().contains("Exception")
                                && !r.text().contains("Traceback"),
                        Component.literal("a raw failure, or no fix named: " + r.text()));
                helper.assertTrue(!java.nio.file.Files.exists(DatapackStore.ownZipFor(id)),
                        Component.literal("a pack was written with no Python"));
            } finally {
                putBack.run();
            }
            helper.succeed();
        });
    }
}
