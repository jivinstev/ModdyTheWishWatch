package com.moddingfromamod.redstone;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.BuildMechanismTool;
import com.moddingfromamod.agent.tools.RemoveMechanismTool;
import com.moddingfromamod.builder.AutoSpotsGameTest;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.BuildSpotsGameTest;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * The tool path, end to end: a wished hall stands on real ground; {@code build_mechanism} puts a piston door into its
 * doorway with a lever at {@code hall:front}, proves it, and reports; {@code remove_mechanism} puts the hall's own
 * door back. This is the vault-door wish ("a door I can close with a switch in front of my house") minus the model.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BuildMechanismGameTest {

    @GameTest(template = "empty_test", batch = "redstone_tool", timeoutTicks = 3000)
    public static void aVaultDoorInTheHallsDoorwayWithALeverOutFront(GameTestHelper helper) {
        String hall = "gt_rs_hall";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildPlan plan = AutoSpotsGameTest.plainHall(hall);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 120, 0);
        // real ground, six deep: the wiring goes under the floor
        for (int x = -12; x <= 22; x++) {
            for (int z = -12; z <= 24; z++) {
                for (int y = -6; y <= -1; y++) {
                    level.setBlock(anchor.offset(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                }
                for (int y = 0; y <= 9; y++) {
                    level.setBlock(anchor.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (int cx = (anchor.getX() - 12) >> 4; cx <= (anchor.getX() + 22) >> 4; cx++) {
            for (int cz = (anchor.getZ() - 12) >> 4; cz <= (anchor.getZ() + 24) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        BuildRegistry.put(level.getServer(), plan);
        Builder.submit(level, anchor, plan);
        BuildSpotsGameTest.bind(level, mock, "a vault door i can close with a switch in front of my hall", "gt-rs-tool");
        BlockPos doorLow = anchor.offset(5, 1, 10);
        AtomicReference<String> built = new AtomicReference<>(), removed = new AtomicReference<>();
        AtomicReference<String> doorwayWhileBuilt = new AtomicReference<>();
        AtomicReference<Boolean> started = new AtomicReference<>(false);
        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBuilding(hall), Component.literal("waiting for the hall"));
            if (!started.get()) {
                started.set(true);
                BuildSpotsGameTest.offThread(() -> AgentContext.with("gt-rs-tool", () -> {
                    ToolResult r = new BuildMechanismTool().call(JsonParser.parseString(
                            "{\"mechanism\":\"piston_door\",\"at\":\"" + hall + ":front\",\"controls\":[{\"kind\":"
                                    + "\"lever\",\"at\":\"" + hall + ":front\"}]}").getAsJsonObject());
                    built.set((r.isError() ? "ERROR " : "") + r.text());
                    if (!r.isError()) {
                        doorwayWhileBuilt.set(com.moddingfromamod.agent.AgentBridge.onServer(
                                () -> level.getBlockState(doorLow).toString(), 10_000L));
                        ToolResult x = new RemoveMechanismTool().call(JsonParser.parseString("{\"id\":\"piston_door\"}")
                                .getAsJsonObject());
                        removed.set((x.isError() ? "ERROR " : "") + x.text());
                    }
                    return null;
                }));
            }
            helper.assertTrue(built.get() != null, Component.literal("waiting for build_mechanism"));
            if (built.get().startsWith("ERROR")) {
                throw helper.assertionException("build_mechanism failed: " + built.get());
            }
            helper.assertTrue(removed.get() != null, Component.literal("waiting for remove_mechanism"));
            helper.assertTrue(built.get().contains("Built and TESTED"), Component.literal(built.get()));
            helper.assertTrue(doorwayWhileBuilt.get().contains("air"),
                    Component.literal("while built, the doorway (open at rest) holds " + doorwayWhileBuilt.get()));
            helper.assertFalse(removed.get().startsWith("ERROR"), Component.literal(removed.get()));
            // taken back: the hall's own oak door is in its doorway again
            helper.assertTrue(level.getBlockState(doorLow).is(Blocks.OAK_DOOR),
                    Component.literal("after remove_mechanism the doorway holds " + level.getBlockState(doorLow)));
        });
    }
}
