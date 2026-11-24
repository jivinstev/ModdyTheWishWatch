package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.AuthorDatapackTool;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>One datapack with a refused tick function must not stop every other datapack's clock</b>.
 *
 * <p>Minecraft drops a function it cannot parse, and a dropped tick function is a missing reference
 * in {@code #minecraft:tick}, which Minecraft refuses WHOLE — so every wish's clock in the world
 * stops, silently. {@code apply_effect} learned to take such a pack back off (chunk 3);
 * {@code author_datapack} and {@code make_item} did not, and a switched-off pack came back on at
 * the next restart because the off-list lived in memory only.
 *
 * <p>One batch EACH: these reload the server's datapacks, and two reloads overlapping in one batch
 * tripped a NeoForge data-map NPE during the first Gate B run (both still passed). Batches run one
 * after another, so separate batches cannot overlap.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class TickGuardGameTest {

    private static final String BAD_COMMAND = "damage @e[type=minecraft:allay,distance=..3] 12";

    private static boolean ticks(MinecraftServer server, String ns) {
        return server.getFunctions().getTag(Identifier.withDefaultNamespace("tick")).stream()
                .anyMatch(f -> ns.equals(f.id().getNamespace()));
    }

    private static String clock(String command) {
        return "clock tick\n    /" + command + "\nend\n";
    }

    /**
     * The guard, end to end: a pack with a refused tick function is kept OFF before the reload ever
     * sees it, so the good pack's clock never stops; the refusal is still reported; and a restart
     * keeps it off.
     *
     * <p>Until 2026-11-20 this test first reproduced the bug — the bad pack switched on, the good
     * clock gone, then taken back off. That let Minecraft's reload refuse the function, which logs
     * {@code Failed to load function} with a stack trace, and the public repo's Port CI read the
     * trace as a crash. {@link PackParseCheck} now refuses the pack before it is selected, so the
     * bug's half cannot be reproduced through our activation any more, which is the point.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "tick_guard")
    public static void aRefusedTickFunctionNeverStopsAnotherPacksClock(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        MinecraftServer server = helper.getLevel().getServer();
        long t = helper.getLevel().getGameTime();
        String good = "gt_tick_good_" + t;
        String bad = "gt_tick_bad_" + t;
        try {
            helper.assertTrue(DatapackForge.compile(good, "gt good clock", clock("tag @a remove gt_nothing")).ok(),
                    Component.literal("the good pack did not compile"));
            helper.assertTrue(DatapackForge.compile(bad, "gt bad clock", clock(BAD_COMMAND)).ok(),
                    Component.literal("the bad pack did not compile (it must compile: the SERVER is what refuses it)"));
            DatapackForge.activate(server, good);
            helper.assertTrue(ticks(server, good), Component.literal("the good pack's clock never ran, so nothing below means anything"));

            // Switched on, the bad pack is refused before the reload: the good clock keeps running.
            DatapackForge.activate(server, bad);
            helper.assertTrue(ticks(server, good),
                    Component.literal("a pack with a refused tick function was switched on and stopped the good clock"));
            helper.assertFalse(server.getPackRepository().getSelectedIds().contains(DatapackReloader.packId(bad)),
                    Component.literal("the refused pack was switched on"));

            DatapackVerifier.Report r = DatapackReloader.takeBackIfRefused(server, bad);
            helper.assertTrue(r != null && !r.missing().isEmpty(), Component.literal("the guard did not report the refused function"));
            String badId = DatapackReloader.packId(bad);

            // A RESTART: the in-memory off-list is gone, and level.dat is all that remembers.
            helper.assertTrue(server.getWorldData().getDataConfiguration().dataPacks().getDisabled().contains(badId),
                    Component.literal("the world did not record the pack as switched off, so a restart cannot know"));
            DatapackReloader.forgetSeedForTest();
            helper.assertFalse(DatapackReloader.selectionWith(server, List.of(good)).contains(badId),
                    Component.literal("after a restart the next activation switched the refused pack back on"));
            helper.assertTrue(DatapackReloader.disabledIds().contains(badId), Component.literal("the off-list was not re-read from the world"));

            // A takeBack on a HEALTHY pack must leave it alone.
            helper.assertTrue(DatapackReloader.takeBackIfRefused(server, good) == null,
                    Component.literal("a healthy pack was taken off"));
            DatapackReloader.disableAndReload(server, good);
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not install: " + e));
            return;
        }
        helper.succeed();
    }

    /** The real {@code author_datapack}: a refused tick function comes back as NOT live, and off. */
    // A WALL-CLOCK wait, not a tick count: the tool compiles CBScript (seconds of Python) while the
    // 1.21.8 test server runs ~1000 ticks a second idle, so 1200 ticks ran out in about one.
    // The tick timeout is only a backstop; the deadline below is the test's.
    @GameTest(template = "empty_test", timeoutTicks = 2_000_000, batch = "tick_guard_tool")
    public static void authorDatapackTakesARefusedPackBackOff(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        String id = "gt_author_bad_" + helper.getLevel().getGameTime();
        JsonObject args = new JsonObject();
        args.addProperty("feature_id", id);
        args.addProperty("description", "gt refused tick function");
        args.addProperty("cbscript", clock(BAD_COMMAND));
        // The tool hops to the server thread, and a GameTest body already IS it — so it is called
        // from its own thread, as the agent calls it, while the server keeps ticking.
        AtomicReference<Object> out = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                out.set(new AuthorDatapackTool().call(args));
            } catch (Throwable e) {
                out.set(e);
            }
        }, "gt-author-datapack");
        caller.setDaemon(true);
        caller.start();
        long deadline = System.currentTimeMillis() + 120_000L;
        helper.succeedWhen(() -> {
            Object o = out.get();
            if (o == null && System.currentTimeMillis() > deadline) {
                helper.fail(Component.literal("author_datapack did not return within 120 s"));
            }
            helper.assertTrue(o != null, Component.literal("author_datapack has not returned yet"));
            helper.assertTrue(o instanceof ToolResult, Component.literal("author_datapack threw: " + o));
            ToolResult r = (ToolResult) o;
            helper.assertTrue(r.isError() && r.text().contains("NOT live"),
                    Component.literal("a pack whose tick function the server dropped was reported as loaded: " + r.text()));
            helper.assertTrue(DatapackReloader.disabledIds().contains(DatapackReloader.packId(id)),
                    Component.literal("the refused pack was left on, which stops every datapack's clock in the world"));
        });
    }
}
