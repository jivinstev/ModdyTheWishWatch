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
 * door back. This is a door-with-a-switch wish minus the model.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BuildMechanismGameTest {

    @GameTest(template = "empty_test", batch = "redstone_tool", timeoutTicks = 3000)
    public static void aPistonDoorInTheHallsDoorwayWithALeverOutFront(GameTestHelper helper) {
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
        BuildSpotsGameTest.bind(level, mock, "a secret stone door for the hall with a lever", "gt-rs-tool");
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
                            "{\"mechanism\":\"piston_door\",\"on\":\"close\",\"at\":\"" + hall + ":front\",\"controls\":[{\"kind\":"
                                    + "\"lever\",\"at\":\"" + hall + ":front\"}]}").getAsJsonObject());
                    built.set((r.isError() ? "ERROR " : "") + r.text());
                    if (!r.isError()) {
                        doorwayWhileBuilt.set(com.moddingfromamod.agent.AgentBridge.onServer(
                                () -> level.getBlockState(doorLow).toString(), 10_000L));
                        ToolResult x = new RemoveMechanismTool().call(JsonParser.parseString("{\"id\":\"" + builtId(r.text()) + "\"}")
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

    /** The mansion the first real grant used (a double oak door in a birch front), at its own front. */
    @GameTest(template = "empty_test", batch = "redstone_tool", timeoutTicks = 3000)
    public static void aPistonDoorInAMansionsDoubleDoor(GameTestHelper helper) {
        String id = "gt_rs_mansion";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 150, 0);
        for (int x = -12; x <= 26; x++) {
            for (int z = -14; z <= 22; z++) {
                for (int y = -6; y <= -1; y++) {
                    level.setBlock(anchor.offset(x, y, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
                for (int y = 0; y <= 11; y++) {
                    level.setBlock(anchor.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (int cx = (anchor.getX() - 12) >> 4; cx <= (anchor.getX() + 26) >> 4; cx++) {
            for (int cz = (anchor.getZ() - 14) >> 4; cz <= (anchor.getZ() + 22) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString(MANSION.replace("ID", id)).getAsJsonObject())
                .withSpots(java.util.List.of());
        BuildRegistry.put(level.getServer(), plan);
        Builder.submit(level, anchor, plan);
        BuildSpotsGameTest.bind(level, mock, "a hidden door for the mansion", "gt-rs-mansion");
        AtomicReference<String> built = new AtomicReference<>();
        AtomicReference<Boolean> started = new AtomicReference<>(false);
        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBuilding(id), Component.literal("waiting for the mansion"));
            if (!started.get()) {
                started.set(true);
                BuildSpotsGameTest.offThread(() -> AgentContext.with("gt-rs-mansion", () -> {
                    ToolResult r = new BuildMechanismTool().call(JsonParser.parseString(
                            "{\"mechanism\":\"piston_door\",\"at\":\"" + id + ":front\",\"controls\":[{\"kind\":"
                                    + "\"lever\",\"at\":\"" + id + ":front\"}]}").getAsJsonObject());
                    built.set((r.isError() ? "ERROR " : "") + r.text());
                    if (!r.isError()) {
                        new RemoveMechanismTool().call(JsonParser.parseString("{\"id\":\"" + builtId(r.text()) + "\"}").getAsJsonObject());
                    }
                    return null;
                }));
            }
            helper.assertTrue(built.get() != null, Component.literal("waiting for build_mechanism"));
            if (built.get().startsWith("ERROR")) {
                StringBuilder dump = new StringBuilder(" | anchor y " + anchor.getY() + "; front row:");
                for (int y = -1; y <= 2; y++) {
                    dump.append(" dy").append(y).append(':');
                    for (int x = 5; x <= 10; x++) {
                        dump.append(' ').append(level.getBlockState(anchor.offset(x, y, 0)).getBlock()
                                .builtInRegistryHolder().key().location().getPath());
                    }
                }
                throw helper.assertionException("build_mechanism failed at the mansion: " + built.get() + dump);
            }
            helper.assertTrue(built.get().contains("2 wide") && built.get().contains("in the doorway"),
                    Component.literal("it should use the mansion's own 2-wide doorway: " + built.get()));
            // and THAT doorway: the front's, on the ground storey (a real grant once cut one upstairs in the back)
            String where = (anchor.getX() + 7) + " " + (anchor.getY() + 1) + " " + anchor.getZ();
            String whereR = (anchor.getX() + 8) + " " + (anchor.getY() + 1) + " " + anchor.getZ();
            helper.assertTrue(built.get().contains("doorway at " + where) || built.get().contains("doorway at " + whereR),
                    Component.literal("the door should be in the front doorway at " + where + ": " + built.get()));
        });
    }

    /** Every device kind through the real tool, one after another, each built, proved and removed, in one hall. */
    @GameTest(template = "empty_test", batch = "redstone_tool_kinds", timeoutTicks = 6000)
    public static void everyDeviceKindInAHall(GameTestHelper helper) {
        String hall = "gt_rs_kinds_hall";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildPlan plan = AutoSpotsGameTest.plainHall(hall);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 120, 0);
        for (int x = -14; x <= 24; x++) {
            for (int z = -12; z <= 26; z++) {
                for (int y = -8; y <= -1; y++) {
                    level.setBlock(anchor.offset(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                }
                for (int y = 0; y <= 9; y++) {
                    level.setBlock(anchor.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (int cx = (anchor.getX() - 14) >> 4; cx <= (anchor.getX() + 24) >> 4; cx++) {
            for (int cz = (anchor.getZ() - 12) >> 4; cz <= (anchor.getZ() + 26) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        BuildRegistry.put(level.getServer(), plan);
        Builder.submit(level, anchor, plan);
        BuildSpotsGameTest.bind(level, mock, "lights and doors for the hall", "gt-rs-kinds");
        String[] calls = {
                "{\"mechanism\":\"lamps\",\"count\":4,\"at\":\"H:front\",\"controls\":[{\"kind\":\"lever\",\"at\":\"H:inside\"}]}",
                "{\"mechanism\":\"iron_door\",\"at\":\"H:front\",\"controls\":[{\"kind\":\"pressure_plate\",\"at\":\"H:front\"}]}",
                "{\"mechanism\":\"dispensers\",\"count\":2,\"items\":\"minecraft:firework_rocket*16\",\"aim\":\"up\","
                        + "\"at\":\"H:front\",\"controls\":[{\"kind\":\"button\",\"at\":\"H:front\"}]}",
                "{\"mechanism\":\"note_blocks\",\"notes\":[\"18\",\"14\"],\"instrument\":\"bell\",\"at\":\"H:front\","
                        + "\"controls\":[{\"kind\":\"button\",\"at\":\"H:front\"}]}",
                "{\"mechanism\":\"piston_door\",\"new_opening\":true,\"at\":\"H:west\",\"controls\":[{\"kind\":"
                        + "\"button\",\"at\":\"H:inside\",\"hidden\":true}]}",
                "{\"mechanism\":\"iron_door\",\"at\":\"H:front\",\"controls\":[{\"kind\":\"button\",\"at\":"
                        + "\"H:front\",\"toggle\":true}]}"};
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<Boolean> started = new AtomicReference<>(false);
        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBuilding(hall), Component.literal("waiting for the hall"));
            if (!started.get()) {
                started.set(true);
                BuildSpotsGameTest.offThread(() -> AgentContext.with("gt-rs-kinds", () -> {
                    StringBuilder out = new StringBuilder();
                    for (String c : calls) {
                        ToolResult r = new BuildMechanismTool().call(JsonParser.parseString(c.replace("H", hall))
                                .getAsJsonObject());
                        if (r.isError()) {
                            out.append("FAILED ").append(c, 0, 40).append(": ").append(r.text()).append(" || ");
                            continue;
                        }
                        new RemoveMechanismTool().call(JsonParser.parseString("{\"id\":\"" + builtId(r.text()) + "\"}")
                                .getAsJsonObject());
                    }
                    done.set(out.toString());
                    return null;
                }));
            }
            helper.assertTrue(done.get() != null, Component.literal("waiting for the tools"));
            if (!done.get().isEmpty()) {
                throw helper.assertionException(done.get());
            }
        });
    }

    /** The id build_mechanism reported ("Built and TESTED '<id>'"): tests in one batch share the store. */
    static String builtId(String text) {
        int a = text.indexOf('\'') + 1;
        return text.substring(a, text.indexOf('\'', a));
    }

    static final String MANSION = "{\"id\":\"ID\",\"name\":\"Mansion\",\"note\":\"gate\",\"shapes\":["
            + "{\"kind\":\"box\",\"name\":\"walls\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":15,\"height\":9,\"depth\":11,"
            + "\"block\":\"minecraft:birch_planks\",\"hollow\":true},"
            + "{\"kind\":\"box\",\"name\":\"floor2\",\"dx\":1,\"dy\":4,\"dz\":1,\"width\":13,\"height\":1,\"depth\":9,"
            + "\"block\":\"minecraft:spruce_planks\"},"
            + "{\"kind\":\"box\",\"name\":\"door\",\"dx\":7,\"dy\":1,\"dz\":0,\"width\":2,\"height\":1,\"depth\":1,"
            + "\"block\":\"minecraft:oak_door\"},"
            + "{\"kind\":\"box\",\"name\":\"window_l\",\"dx\":2,\"dy\":2,\"dz\":0,\"width\":2,\"height\":1,\"depth\":1,"
            + "\"block\":\"minecraft:glass_pane\"},"
            + "{\"kind\":\"box\",\"name\":\"window_r\",\"dx\":11,\"dy\":2,\"dz\":0,\"width\":2,\"height\":1,\"depth\":1,"
            + "\"block\":\"minecraft:glass_pane\"},"
            + "{\"kind\":\"box\",\"name\":\"window_up\",\"dx\":3,\"dy\":6,\"dz\":0,\"width\":9,\"height\":1,\"depth\":1,"
            + "\"block\":\"minecraft:glass_pane\"}]}";
}
