package com.moddingfromamod.agent;

import com.moddingfromamod.testing.gt.GameTestCompat;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.RunCommandTool;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * {@code run_command} really runs a whole batch, in order, and really stops at a failure.
 *
 * <p>{@link RunCommandBatchTest} covers the argument shapes and the refusal path, which need no
 * world. What it structurally cannot see is whether the commands EXECUTE — that the second one runs
 * after the first, that a mid-batch failure leaves the earlier work standing and the later work
 * undone, and that the report names where it stopped. Those are the properties an agent relies on
 * when it decides to send ten commands instead of one.
 *
 * <p><b>Every call here runs on a background thread</b> and is polled with {@code succeedWhen}. The
 * tool hops onto the server thread and blocks; a {@code @GameTest} body already runs THERE, so
 * calling it synchronously deadlocks — the hazard {@link AgentBridge}'s same-thread guard exists to
 * catch. Commands use ABSOLUTE coordinates because with no player online the tool positions itself
 * at world spawn, which is nowhere near this plot.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class RunCommandBatchGameTest {

    private static JsonObject batch(String... commands) {
        JsonArray list = new JsonArray();
        for (String c : commands) {
            list.add(c);
        }
        JsonObject args = new JsonObject();
        args.add("command", list);
        return args;
    }

    private static String setblock(GameTestHelper helper, BlockPos rel, String block) {
        BlockPos p = helper.absolutePos(rel);
        return "setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + block;
    }

    /** Fire the tool off-thread; the returned holder fills in when it finishes. */
    private static AtomicReference<String> offThread(JsonObject args) {
        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(new RunCommandTool().call(args).text()), "batch-test");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /** Ten commands in one call place ten blocks — the whole point of the change. */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void awholeBatchRunsEveryCommand(GameTestHelper helper) {
        String[] cmds = new String[10];
        for (int i = 0; i < cmds.length; i++) {
            cmds[i] = setblock(helper, new BlockPos(1 + i, 2, 1), "minecraft:stone");
        }
        AtomicReference<String> result = offThread(batch(cmds));
        helper.succeedWhen(() -> {
            String text = result.get();
            if (text == null) {
                throw GameTestCompat.assertion("still running");
            }
            for (int i = 0; i < 10; i++) {
                helper.assertBlockPresent(Blocks.STONE, new BlockPos(1 + i, 2, 1));
            }
        });
    }

    /**
     * A failure mid-batch stops there: the work before it stands, the work after it never happens,
     * and the agent is told how many were skipped.
     *
     * <p>Continuing past a failure would build on a state the agent believes exists and does not,
     * and the report would blame whichever command failed last rather than first.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void afailureStopsTheBatchAndSaysSo(GameTestHelper helper) {
        AtomicReference<String> result = offThread(batch(
                setblock(helper, new BlockPos(1, 2, 3), "minecraft:stone"),
                setblock(helper, new BlockPos(2, 2, 3), "minecraft:definitely_not_a_block"),
                setblock(helper, new BlockPos(3, 2, 3), "minecraft:stone")));
        helper.succeedWhen(() -> {
            String text = result.get();
            if (text == null) {
                throw GameTestCompat.assertion("still running");
            }
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(1, 2, 3));
            helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 2, 3));
            if (!text.contains("NOT run")) {
                throw GameTestCompat.assertion("the agent must be told the rest did not run, or it will assume the whole "
                                + "batch applied: " + text);
            }
        });
    }

    /**
     * A batch containing a blocked command changes NOTHING — not even the allowed commands before
     * it. Whether a command is allowed needs no execution to decide, so a batch that was always
     * going to be rejected must not half-apply.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void arefusedCommandLeavesTheWorldUntouched(GameTestHelper helper) {
        AtomicReference<String> result = offThread(batch(
                setblock(helper, new BlockPos(1, 2, 5), "minecraft:stone"),
                "stop",
                setblock(helper, new BlockPos(2, 2, 5), "minecraft:stone")));
        helper.succeedWhen(() -> {
            String text = result.get();
            if (text == null) {
                throw GameTestCompat.assertion("still running");
            }
            helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(1, 2, 5));
            helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(2, 2, 5));
            if (!text.contains("nothing was run")) {
                throw GameTestCompat.assertion("it must say the batch did not partially apply: " + text);
            }
        });
    }

    /** The old single-string shape still executes, so nothing the agent already knows breaks. */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void asingleStringStillExecutes(GameTestHelper helper) {
        JsonObject args = new JsonObject();
        args.addProperty("command", setblock(helper, new BlockPos(4, 2, 5), "minecraft:stone"));
        AtomicReference<String> result = offThread(args);
        helper.succeedWhen(() -> {
            if (result.get() == null) {
                throw GameTestCompat.assertion("still running");
            }
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 2, 5));
        });
    }
}
