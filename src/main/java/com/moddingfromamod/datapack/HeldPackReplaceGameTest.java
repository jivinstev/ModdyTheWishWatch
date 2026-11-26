package com.moddingfromamod.datapack;

import java.nio.file.Files;
import java.nio.file.Path;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * <b>An edited datapack replaces the loaded one on Windows too.</b> Windows refuses to replace a file another handle has
 * open, and a loaded pack's zip is open in the server's resources: every edit of an item or an effect failed there with
 * "being used by another process" (the platform smoke, 2026-11-26), while Linux and macOS replace it under the handle.
 * {@link DatapackReloader#installAndReload} now unloads the pack, replaces it and loads it again. This drives that path
 * on every OS: the seam makes the move refuse the way Windows does, once, and the new pack must be the one that runs.
 * The platform smoke's Windows leg drives it for real. Own batch: it reloads the server's resources.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class HeldPackReplaceGameTest {

    private static final String PACK = "gt_held_replace";

    private static Path compile(String function) throws Exception {
        Path tmp = Files.createTempDirectory("gt_held_");
        Path zip = tmp.resolve(PACK + ".zip");
        CompileResult r = CbScriptCompiler.compile(tmp, PACK, "gametest",
                "function " + function + "()\n    /say " + function + "\nend\nreset\nend\n", zip);
        if (!r.ok()) {
            throw new IllegalStateException("the test pack did not compile: " + r.failureSummary());
        }
        return zip;
    }

    private static boolean has(MinecraftServer server, String function) {
        return server.getFunctions().get(ResourceLocation.fromNamespaceAndPath(PACK, function)).isPresent();
    }

    @GameTest(template = "empty_test", batch = "datapack_held_replace", timeoutTicks = 1200)
    public static void anEditReplacesAPackWhoseZipIsHeldOpen(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        DatapackReloader.installAndReload(server, PACK, compile("first_version"));
        Runnable[] step = new Runnable[1];
        int[] waited = {0};
        int[] phase = {0};
        step[0] = () -> {
            waited[0] += 5;
            boolean settled = !DatapackReloader.reloadInProgress() && !DatapackReloader.reloadDeferred();
            try {
                if (phase[0] == 0 && settled && has(server, "first_version")) {
                    // the edit: the replace is refused as Windows refuses a held file
                    DatapackReloader.holdForTest = p -> p.getFileName().toString().equals(PACK + ".zip");
                    DatapackReloader.installAndReload(server, PACK, compile("second_version"));
                    helper.assertTrue(DatapackReloader.holdForTest == null,
                            Component.literal("the replace was never refused: the test did not reach the held path"));
                    phase[0] = 1;
                    waited[0] = 0;
                } else if (phase[0] == 1 && settled && has(server, "second_version")) {
                    helper.assertFalse(has(server, "first_version"),
                            Component.literal("the old pack still runs beside the new one"));
                    DatapackReloader.disableAndReload(server, PACK);
                    Files.deleteIfExists(DatapackReloader.worldDatapackDir(server).resolve(PACK + ".zip"));
                    helper.succeed();
                    return;
                } else if (waited[0] > 1000) {
                    helper.fail(Component.literal("phase " + phase[0] + ": the "
                            + (phase[0] == 0 ? "first" : "edited") + " pack never loaded"));
                    return;
                }
            } catch (Exception e) {
                DatapackReloader.holdForTest = null;
                helper.fail(Component.literal("the edit of a held pack failed: " + e));
                return;
            }
            helper.runAfterDelay(5, () -> step[0].run());   // a FRESH Runnable each time (SourceRuleSweepTest)
        };
        helper.runAfterDelay(1, () -> step[0].run());
    }
}
