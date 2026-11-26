package com.moddingfromamod.redstone;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.BuildMechanismTool;
import com.moddingfromamod.agent.tools.BuildStructureTool;
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
 * Each real grant that went wrong in the first 40-wish sample, as a test through the real tool: a rebuild of the
 * building must not overwrite a built door; a building whose box is wider than its walls (a roof overhang, a fence)
 * still gets its door; lamps inside a house can be wired; a pay-to-open door opens when paid (the logic bridge).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class RedstoneRegressionGameTest {

    /** Deep ground, air above, chunks forced; a building from {@code json} put up on it. */
    static BlockPos site(GameTestHelper helper, int height, String json) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, height, 0);
        for (int x = -14; x <= 26; x++) {
            for (int z = -14; z <= 26; z++) {
                for (int y = -8; y <= -1; y++) {
                    level.setBlock(anchor.offset(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                }
                for (int y = 0; y <= 10; y++) {
                    level.setBlock(anchor.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (int cx = (anchor.getX() - 14) >> 4; cx <= (anchor.getX() + 26) >> 4; cx++) {
            for (int cz = (anchor.getZ() - 14) >> 4; cz <= (anchor.getZ() + 26) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString(json).getAsJsonObject()).withSpots(java.util.List.of());
        BuildRegistry.put(level.getServer(), plan);
        Builder.submit(level, anchor, plan);
        return anchor;
    }

    static ToolResult mech(String json) {
        return new BuildMechanismTool().call(JsonParser.parseString(json).getAsJsonObject());
    }

    interface Steps {
        String run();
    }

    static void drive(GameTestHelper helper, String id, String worker, Steps steps) {
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildSpotsGameTest.bind(helper.getLevel(), mock, "a test wish", worker);
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Boolean> started = new AtomicReference<>(false);
        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBuilding(id), Component.literal("waiting for the building"));
            if (!started.get()) {
                started.set(true);
                BuildSpotsGameTest.offThread(() -> AgentContext.with(worker, () -> {
                    try {
                        out.set(steps.run());
                    } catch (Throwable t) {
                        out.set("threw " + t);
                    }
                    return null;
                }));
            }
            helper.assertTrue(out.get() != null, Component.literal("waiting for the steps"));
            if (!out.get().isEmpty()) {
                throw helper.assertionException(out.get());
            }
        });
    }

    static final String HOUSE = "{\"id\":\"ID\",\"name\":\"House\",\"note\":\"gate\",\"shapes\":["
            + "{\"kind\":\"box\",\"name\":\"walls\",\"dx\":1,\"dy\":0,\"dz\":1,\"width\":11,\"height\":6,\"depth\":11,"
            + "\"block\":\"minecraft:white_concrete\",\"hollow\":true},"
            + "{\"kind\":\"box\",\"name\":\"roof\",\"dx\":0,\"dy\":6,\"dz\":0,\"width\":13,\"height\":1,\"depth\":13,"
            + "\"block\":\"minecraft:dark_oak_planks\"},"
            + "{\"kind\":\"box\",\"name\":\"door\",\"dx\":6,\"dy\":1,\"dz\":1,\"width\":1,\"height\":1,\"depth\":1,"
            + "\"block\":\"minecraft:oak_door\"}]}";

    /** A house with a roof overhang: an iron door in its own doorway, and lamps in its floor. */
    @GameTest(template = "empty_test", batch = "redstone_regress", timeoutTicks = 4000)
    public static void aHouseWithAnOverhangGetsItsDoorAndLamps(GameTestHelper helper) {
        String id = "gt_rs_overhang";
        site(helper, 120, HOUSE.replace("ID", id));
        drive(helper, id, "gt-rs-overhang", () -> {
            ToolResult d = mech("{\"mechanism\":\"iron_door\",\"at\":\"" + id + ":front\",\"controls\":[{\"kind\":"
                    + "\"button\",\"at\":\"" + id + ":front\"}]}");
            ToolResult l = mech("{\"mechanism\":\"lamps\",\"count\":6,\"at\":\"" + id + ":inside\",\"controls\":[{\"kind\":"
                    + "\"lever\",\"at\":\"" + id + ":inside\"}]}");
            String err = "";
            if (d.isError() || !d.text().contains("in the doorway")) {
                err += "door: " + d.text() + " || ";
            }
            if (l.isError()) {
                err += "lamps: " + l.text();
            }
            return err;
        });
    }

    /** The same house, lamps only (no door wired first), to tell the two apart. */
    @GameTest(template = "empty_test", batch = "redstone_regress3", timeoutTicks = 4000)
    public static void lampsAloneInAHouseWithAnOverhang(GameTestHelper helper) {
        String id = "gt_rs_lamps_alone";
        site(helper, 140, HOUSE.replace("ID", id));
        drive(helper, id, "gt-rs-lamps-alone", () -> {
            ToolResult l = mech("{\"mechanism\":\"lamps\",\"count\":6,\"at\":\"" + id + ":inside\",\"controls\":[{\"kind\":"
                    + "\"lever\",\"at\":\"" + id + ":inside\"}]}");
            return l.isError() ? "lamps: " + l.text() : "";
        });
    }

    /** A barn whose box takes in its fence, 4 blocks out: its own double doorway gets a piston door. */
    @GameTest(template = "empty_test", batch = "redstone_regress", timeoutTicks = 4000)
    public static void aBarnBehindItsFenceGetsItsDoor(GameTestHelper helper) {
        String id = "gt_rs_barn";
        site(helper, 150, ("{\"id\":\"ID\",\"name\":\"Barn\",\"note\":\"gate\",\"shapes\":["
                + "{\"kind\":\"box\",\"name\":\"walls\",\"dx\":4,\"dy\":0,\"dz\":5,\"width\":13,\"height\":7,\"depth\":11,"
                + "\"block\":\"minecraft:spruce_planks\",\"hollow\":true},"
                + "{\"kind\":\"box\",\"name\":\"doors\",\"dx\":10,\"dy\":1,\"dz\":5,\"width\":2,\"height\":1,\"depth\":1,"
                + "\"block\":\"minecraft:oak_door\"},"
                + "{\"kind\":\"box\",\"name\":\"fence\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":21,\"height\":1,\"depth\":4,"
                + "\"block\":\"minecraft:oak_fence\",\"hollow\":true}]}").replace("ID", id));
        drive(helper, id, "gt-rs-barn", () -> {
            ToolResult d = mech("{\"mechanism\":\"piston_door\",\"at\":\"" + id + ":front\",\"controls\":[{\"kind\":"
                    + "\"button\",\"at\":\"" + id + ":front\",\"toggle\":true}]}");
            return d.isError() || !d.text().contains("2 wide") ? "barn door: " + d.text() : "";
        });
    }

    /** A door built into a hall, then the hall rebuilt in place: the door must still be there and still work. */
    @GameTest(template = "empty_test", batch = "redstone_regress2", timeoutTicks = 5000)
    public static void aRebuildDoesNotOverwriteADoor(GameTestHelper helper) {
        String id = "gt_rs_rebuild";
        ServerLevel level = helper.getLevel();
        BlockPos anchor = site(helper, 120, HOUSE.replace("ID", id));
        drive(helper, id, "gt-rs-rebuild", () -> {
            ToolResult d = mech("{\"mechanism\":\"piston_door\",\"on\":\"close\",\"at\":\"" + id + ":front\","
                    + "\"controls\":[{\"kind\":\"lever\",\"at\":\"" + id + ":front\"}]}");
            if (d.isError()) {
                return "door: " + d.text();
            }
            ToolResult b = new BuildStructureTool().call(JsonParser.parseString("{\"id\":\"" + id + "\",\"start_from\":\""
                    + id + "\",\"shapes\":[{\"kind\":\"box\",\"name\":\"lamp\",\"dx\":3,\"dy\":1,\"dz\":3,\"width\":1,"
                    + "\"height\":1,\"depth\":1,\"block\":\"minecraft:lantern\"}]}").getAsJsonObject());
            if (b.isError()) {
                return "rebuild: " + b.text();
            }
            for (int i = 0; i < 100 && AgentBridge.onServer(() -> Builder.isBuilding(id), 5_000L); i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return "interrupted";
                }
            }
            // the door's pistons are still in the wall: the doorway at u=-2 from the door
            int pistons = AgentBridge.onServer(() -> {
                int n = 0;
                for (int x = -4; x <= 18; x++) {
                    for (int z = -2; z <= 4; z++) {
                        for (int y = 0; y <= 3; y++) {
                            if (level.getBlockState(anchor.offset(x, y, z)).is(Blocks.STICKY_PISTON)) {
                                n++;
                            }
                        }
                    }
                }
                return n;
            }, 5_000L);
            return pistons >= 2 ? "" : "after the rebuild only " + pistons + " sticky pistons are left";
        });
    }

    /** Pay an emerald into the barrel and the door opens (the logic bridge's ticker), then shuts again. */
    @GameTest(template = "empty_test", batch = "redstone_regress2", timeoutTicks = 5000)
    public static void payAnEmeraldAndTheDoorOpens(GameTestHelper helper) {
        String id = "gt_rs_paid";
        ServerLevel level = helper.getLevel();
        site(helper, 160, HOUSE.replace("ID", id));
        drive(helper, id, "gt-rs-paid", () -> {
            ToolResult d = mech("{\"mechanism\":\"iron_door\",\"at\":\"" + id + ":front\",\"controls\":[{\"kind\":"
                    + "\"logic\",\"at\":\"" + id + ":front\",\"when\":{\"if\":\"paid\",\"item\":\"minecraft:emerald\","
                    + "\"count\":1,\"for_seconds\":60}}]}");
            if (d.isError()) {
                return "door: " + d.text();
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("barrel at (-?\\d+) (-?\\d+) (-?\\d+)").matcher(d.text());
            if (!m.find()) {
                return "no barrel reported: " + d.text();
            }
            BlockPos barrel = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
            java.util.regex.Matcher dm = java.util.regex.Pattern.compile("doorway at (-?\\d+) (-?\\d+) (-?\\d+)").matcher(d.text());
            dm.find();
            BlockPos door = new BlockPos(Integer.parseInt(dm.group(1)), Integer.parseInt(dm.group(2)),
                    Integer.parseInt(dm.group(3)));
            boolean shutFirst = AgentBridge.onServer(() -> !level.getBlockState(door).getValue(
                    net.minecraft.world.level.block.DoorBlock.OPEN), 5_000L);
            AgentBridge.onServer(() -> {
                if (level.getBlockEntity(barrel) instanceof net.minecraft.world.Container c) {
                    c.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 1));
                }
                return null;
            }, 5_000L);
            boolean opened = false;
            for (int i = 0; i < 100 && !opened; i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return "interrupted";
                }
                opened = AgentBridge.onServer(() -> level.getBlockState(door).getValue(
                        net.minecraft.world.level.block.DoorBlock.OPEN), 5_000L);
            }
            int left = AgentBridge.onServer(() -> level.getBlockEntity(barrel) instanceof net.minecraft.world.Container c
                    ? c.getItem(0).getCount() : -1, 5_000L);
            if (!shutFirst) {
                return "the door was open before anyone paid";
            }
            if (!opened) {
                return "paying did not open the door";
            }
            // one paid emerald, taken; and the door shuts again when its time is up
            boolean shutAgain = false;
            for (int i = 0; i < 400 && !shutAgain; i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return "interrupted";
                }
                shutAgain = AgentBridge.onServer(() -> !level.getBlockState(door).getValue(
                        net.minecraft.world.level.block.DoorBlock.OPEN), 5_000L);
            }
            if (left != 0) {
                return "paying should take the emerald; " + left + " left";
            }
            return shutAgain ? "" : "the door stayed open after its paid time";
        });
    }

    /** A second door asked for in the first one's new doorway is refused, never built over it (t096 in v2). */
    @GameTest(template = "empty_test", batch = "redstone_regress_overlap", timeoutTicks = 4000)
    public static void aSecondDoorOverTheFirstIsRefused(GameTestHelper helper) {
        String id = "gt_rs_overlap";
        site(helper, 120, HOUSE.replace("ID", id));
        drive(helper, id, "gt-rs-overlap", () -> {
            String ask = "{\"mechanism\":\"piston_door\",\"at\":\"" + id + ":west\",\"new_opening\":true,MAT"
                    + "\"controls\":[{\"kind\":\"button\",\"at\":\"" + id + ":inside\"}]}";
            ToolResult first = mech(ask.replace("MAT", ""));
            if (first.isError()) {
                return "first door: " + first.text();
            }
            ToolResult second = mech(ask.replace("MAT", "\"material\":\"minecraft:bricks\","));
            String err = !second.isError() || !second.text().contains("is already there")
                    ? "second door over the first: " + second.text() : "";
            new com.moddingfromamod.agent.tools.RemoveMechanismTool().call(JsonParser.parseString(
                    "{\"id\":\"" + BuildMechanismGameTest.builtId(first.text()) + "\"}").getAsJsonObject());
            return err;
        });
    }

    /** A shower aimed at the floor still holds its water after its proof: it is turned to face open air (t022). */
    @GameTest(template = "empty_test", batch = "redstone_regress_shower", timeoutTicks = 4000)
    public static void aShowerAimedAtTheFloorKeepsItsBucket(GameTestHelper helper) {
        String id = "gt_rs_shower";
        BlockPos anchor = site(helper, 120, HOUSE.replace("ID", id));
        drive(helper, id, "gt-rs-shower", () -> {
            ToolResult r = mech("{\"mechanism\":\"dispensers\",\"at\":\"" + id + ":inside\",\"items\":"
                    + "\"minecraft:water_bucket*1\",\"aim\":\"down\",\"controls\":[{\"kind\":\"pressure_plate\",\"at\":\""
                    + id + ":inside\"}]}");
            if (r.isError()) {
                return "shower: " + r.text();
            }
            boolean held = AgentBridge.onServer(() -> {
                for (BlockPos p : BlockPos.betweenClosed(anchor.offset(-4, -2, -4), anchor.offset(16, 4, 16))) {
                    if (helper.getLevel().getBlockEntity(p) instanceof net.minecraft.world.Container c) {
                        for (int k = 0; k < c.getContainerSize(); k++) {
                            if (c.getItem(k).is(net.minecraft.world.item.Items.WATER_BUCKET)) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }, 10_000L);
            new com.moddingfromamod.agent.tools.RemoveMechanismTool().call(JsonParser.parseString(
                    "{\"id\":\"" + BuildMechanismGameTest.builtId(r.text()) + "\"}").getAsJsonObject());
            return held ? "" : "after its proof no dispenser holds the water bucket: " + r.text();
        });
    }

    /**
     * The routing fix (plan §13 step 1): devices driven one feed each, nothing routed between them. Twelve lamps in
     * one house beside its iron door (v3: 1 of 12 fitted), inverted lamps (v3: failed their own proof), and a timer
     * driving several note blocks (a multi-device mechanism from a logic rule).
     */
    @GameTest(template = "empty_test", batch = "redstone_regress_driven", timeoutTicks = 6000)
    public static void devicesDrivenOneFeedEach(GameTestHelper helper) {
        String id = "gt_rs_driven";
        site(helper, 120, HOUSE.replace("ID", id));
        drive(helper, id, "gt-rs-driven", () -> {
            String err = "";
            ToolResult d = mech("{\"mechanism\":\"iron_door\",\"at\":\"" + id + ":front\",\"controls\":[{\"kind\":"
                    + "\"button\",\"at\":\"" + id + ":front\"}]}");
            if (d.isError()) {
                err += "door: " + d.text() + " || ";
            }
            ToolResult l = mech("{\"mechanism\":\"lamps\",\"count\":12,\"at\":\"" + id + ":inside\",\"controls\":"
                    + "[{\"kind\":\"lever\",\"at\":\"" + id + ":inside\"}]}");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) lamp\\(s\\)").matcher(l.text());
            if (l.isError() || !m.find() || Integer.parseInt(m.group(1)) < 6) {
                err += "12 lamps: " + l.text() + " || ";
            }
            if (!l.isError()) {
                new com.moddingfromamod.agent.tools.RemoveMechanismTool().call(JsonParser.parseString(
                        "{\"id\":\"" + BuildMechanismGameTest.builtId(l.text()) + "\"}").getAsJsonObject());
            }
            ToolResult inv = mech("{\"mechanism\":\"lamps\",\"count\":4,\"inverted\":true,\"at\":\"" + id
                    + ":inside\",\"controls\":[{\"kind\":\"lever\",\"at\":\"" + id + ":inside\"}]}");
            if (inv.isError()) {
                err += "inverted lamps: " + inv.text() + " || ";
            } else {
                new com.moddingfromamod.agent.tools.RemoveMechanismTool().call(JsonParser.parseString(
                        "{\"id\":\"" + BuildMechanismGameTest.builtId(inv.text()) + "\"}").getAsJsonObject());
            }
            ToolResult bell = mech("{\"mechanism\":\"note_blocks\",\"at\":\"" + id + ":inside\",\"instrument\":\"bell\","
                    + "\"notes\":[\"12\",\"16\",\"19\"],\"controls\":[{\"kind\":\"logic\",\"at\":\"" + id
                    + ":inside\",\"when\":{\"if\":\"every\",\"seconds\":30,\"for_seconds\":2}}]}");
            if (bell.isError()) {
                err += "timed bells: " + bell.text() + " || ";
            } else {
                new com.moddingfromamod.agent.tools.RemoveMechanismTool().call(JsonParser.parseString(
                        "{\"id\":\"" + BuildMechanismGameTest.builtId(bell.text()) + "\"}").getAsJsonObject());
            }
            if (!d.isError()) {
                new com.moddingfromamod.agent.tools.RemoveMechanismTool().call(JsonParser.parseString(
                        "{\"id\":\"" + BuildMechanismGameTest.builtId(d.text()) + "\"}").getAsJsonObject());
            }
            return err;
        });
    }
}
