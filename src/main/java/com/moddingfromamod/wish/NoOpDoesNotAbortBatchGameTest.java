package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.RunCommandTool;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A command with nothing to do must not throw away the rest of the batch.</b>
 *
 * <p>The unit tests in {@code CommandOutcomeTest} pin down the distinction; this one proves it
 * survives the trip through {@code run_command}, a real server and real vanilla commands — which is
 * where it was broken, and where it can break again.
 *
 * <p><b>This test fails on the pre-fix code.</b> That is the point of it: the bug was invisible to
 * every gate in the repo, and was found only by reading a live session's action log after the child
 * said his factory had no grinders in it.
 */
@GameTestHolder("moddingfromamod")
public class NoOpDoesNotAbortBatchGameTest {

    /** {@code run_command} hops to the server thread, so a GameTest must call it from off it. */
    private static void inBackground(AtomicReference<String> out, JsonObject args) {
        Thread t = new Thread(() -> {
            try {
                ToolResult r = new RunCommandTool().call(args);
                out.set((r.isError() ? "ERROR " : "") + r.text());
            } catch (RuntimeException e) {
                out.set("run_command threw: " + e);
            }
        }, "noop-batch");
        t.setDaemon(true);
        t.start();
    }

    private static JsonObject batch(String... commands) {
        JsonArray arr = new JsonArray();
        for (String c : commands) {
            arr.add(c);
        }
        JsonObject a = new JsonObject();
        a.add("command", arr);
        return a;
    }

    /**
     * <b>The live failure, reconstructed.</b>
     *
     * <p>Clear a region that is <i>already</i> air, then place the thing that matters. Vanilla
     * reports the clear as <i>"No blocks were filled"</i>; before the fix the batch stopped there
     * and the block behind it was never placed. In the child's world that discarded block was the
     * repeating command block that made his grinder work.
     */
    @GameTest(template = "empty_test", timeoutTicks = 2000, batch = "noopbatch")
    public void aNoOpFillDoesNotDiscardTheCommandsBehindIt(GameTestHelper helper) {
        // Absolute coordinates: the tool runs real commands against the real world, not the
        // helper's relative space.
        BlockPos target = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos emptyRegion = helper.absolutePos(new BlockPos(1, 4, 1));

        helper.setBlock(new BlockPos(2, 2, 2), Blocks.AIR);

        AtomicReference<String> said = new AtomicReference<>();
        inBackground(said, batch(
                // Nothing to do — that air is already air.
                "fill " + emptyRegion.getX() + " " + emptyRegion.getY() + " " + emptyRegion.getZ()
                        + " " + emptyRegion.getX() + " " + emptyRegion.getY() + " "
                        + emptyRegion.getZ() + " minecraft:air",
                // THE ONE THAT MATTERS. Everything after a "failure" used to be discarded.
                "setblock " + target.getX() + " " + target.getY() + " " + target.getZ()
                        + " minecraft:gold_block"));

        // ⚠ REAL TIME, not 120 ticks: run_command answers from a background thread, and a
        // single-test batch on 1.21.4 runs ~20k ticks a second (test.RealTime) -- so "120 ticks"
        // was sometimes over before the thread had started, and read as "never came back".
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            String text = said.get();
            helper.assertTrue(text != null, Component.literal("run_command never came back"));
            if (helper.getLevel().getBlockState(target).getBlock() != Blocks.GOLD_BLOCK) {
                helper.fail(Component.literal("The command behind a no-op was thrown away. A `fill … air` over air "
                        + "reports \"No blocks were filled\", which is not a failure — the world is "
                        + "already what was asked for. Live, this discarded the repeating command "
                        + "block that WAS the child's grinder. run_command said: ["
                        + String.valueOf(text).replace('\n', ' ') + "]"));
                return;
            }
            // ...and the agent is still TOLD it changed nothing, rather than being handed a
            // plain "Ran:" that would let a wish quietly build in the wrong place forever.
            if (!String.valueOf(text).contains("Already like that")) {
                helper.fail(Component.literal("The batch completed, but the no-op was reported as an ordinary "
                        + "success. The agent has to be able to tell 'I changed it' from 'it was "
                        + "already like that' — otherwise a wish building at the wrong "
                        + "coordinates looks exactly like one that is working. Said: ["
                        + String.valueOf(text).replace('\n', ' ') + "]"));
                return;
            }
        });
    }

    /**
     * <b>And a REAL failure still stops the batch.</b>
     *
     * <p>The other half, asserted so the fix cannot quietly widen into "never stop". A typo'd block
     * id means the wall the agent believes it built is not there, and every command behind it is
     * standing on nothing.
     */
    @GameTest(template = "empty_test", timeoutTicks = 2000, batch = "noopbatch2")
    public void aGenuineFailureStillStopsTheBatch(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(4, 2, 4));
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.AIR);

        AtomicReference<String> said = new AtomicReference<>();
        inBackground(said, batch(
                "setblock " + target.getX() + " " + target.getY() + " " + target.getZ()
                        + " minecraft:definitely_not_a_real_block",
                "setblock " + target.getX() + " " + target.getY() + " " + target.getZ()
                        + " minecraft:diamond_block"));

        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(said.get() != null, Component.literal("run_command never came back"));
            if (helper.getLevel().getBlockState(target).getBlock() == Blocks.DIAMOND_BLOCK) {
                helper.fail(Component.literal("A batch carried on past a genuinely broken command. Forgiving "
                        + "\"already like that\" must not become forgiving everything — the stop "
                        + "rule is what stops a report blaming the wrong command"));
                return;
            }
        });
    }
}
