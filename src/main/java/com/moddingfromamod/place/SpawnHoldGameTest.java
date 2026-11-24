package com.moddingfromamod.place;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.LevelData;

/**
 * <b>Gate B for {@link SpawnHold}</b>: an action that falls back to the world spawn summons a creature
 * there, and the same action's follow-up finds it.
 *
 * <p>1.21.9 removed spawn chunks. So the world spawn is moved here to a spot thousands of blocks from
 * any test structure, and the test first checks that its chunk really is not loaded; then
 * {@code run_command}, with nobody online and no aimed spot, takes its spawn fallback and summons a pig
 * at {@code ~ ~ ~}. Three looks must all find it: the tool's own handle diff ("You created: …"), the
 * agent's next call ({@code execute if entity …}, a separate {@code run_command}), and the world itself
 * on a later tick. Without the hold the summon still says "Ran" and all three come back empty.
 *
 * <p>Its own batch: it moves the world spawn, which every playerless test reads.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SpawnHoldGameTest {

    private static final String BATCH = "spawnHold";

    /** Far enough from the test grid that nothing else has loaded it. */
    private static final int FAR = 6_000;

    private SpawnHoldGameTest() {
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 4_000)
    public static void aSummonAtAnUnloadedSpawnIsFoundByTheSameActionsFollowUp(GameTestHelper helper) {
        MockPlayerNetwork.requireNobodyIsOnline(helper);
        helper.assertTrue(com.moddingfromamod.agent.ActingChild.currentStandIn() == null,
                Component.literal("a stand-in child is registered, so run_command would not take the spawn fallback"));
        ServerLevel level = helper.getLevel().getServer().overworld();
        LevelData.RespawnData was = level.getRespawnData();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos far = new BlockPos(origin.getX() + FAR, 200, origin.getZ() + FAR);
        int cx = SpawnHoldPlan.chunkOf(far.getX());
        int cz = SpawnHoldPlan.chunkOf(far.getZ());
        helper.assertTrue(level.getChunkSource().getChunkNow(cx, cz) == null && !level.isPositionEntityTicking(far),
                Component.literal("the far spawn " + far + " is already loaded, so this test would prove nothing"));

        String tag = "gt_spawnhold_" + UUID.randomUUID().toString().substring(0, 8);
        level.setRespawnData(new LevelData.RespawnData(GlobalPos.of(Level.OVERWORLD, far), 0f, 0f));
        MockPlayerNetwork.afterTest(helper, () -> {
            for (Pig p : pigs(level, tag)) {
                p.discard();
            }
            level.setRespawnData(was);
        });

        AtomicReference<ToolResult> summoned = new AtomicReference<>();
        AtomicReference<ToolResult> looked = new AtomicReference<>();
        Thread t = new Thread(() -> {
            summoned.set(new RunCommandTool().call(json("{\"command\":\"summon minecraft:pig ~ ~ ~ {Tags:[\\\"" + tag
                    + "\\\"],NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b}\"}")));
            looked.set(new RunCommandTool().call(json("{\"command\":\"execute if entity @e[type=minecraft:pig,tag="
                    + tag + "]\"}")));
        }, "gametest-spawnhold");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(looked.get() != null, Component.literal("waiting for both run_command calls"));
            ToolResult s = summoned.get();
            helper.assertFalse(s.isError(), Component.literal("the summon at the spawn must run: " + s.text()));
            helper.assertTrue(s.text().contains("You created:"),
                    Component.literal("the tool's own lookup must find what it just summoned at the unloaded spawn "
                            + far + ": " + s.text()));
            helper.assertFalse(looked.get().isError(),
                    Component.literal("the agent's next call must still find the pig: " + looked.get().text()));
            helper.assertTrue(pigs(level, tag).size() == 1,
                    Component.literal("the world must hold exactly one pig tagged " + tag + " near " + far
                            + ", found " + pigs(level, tag).size()));
            helper.assertTrue(SpawnHold.isHeld(level, far),
                    Component.literal("the spawn must still be held in its tail, for the agent's next call"));
        });
    }

    private static List<? extends Pig> pigs(ServerLevel level, String tag) {
        return level.getEntities(EntityTypeTest.forClass(Pig.class), p -> p.getTags().contains(tag));
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
