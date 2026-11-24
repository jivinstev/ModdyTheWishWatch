package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"use it on a block", through the real tool and the real right-click.</b>
 *
 * <p>Each test makes the wand with {@code make_item} exactly as the agent would, hands it to a
 * player who is LOOKING at a block, right-clicks through {@code gameMode.useItem} (the path a real
 * click takes), and then asks the world which blocks changed. The neighbours are the half that
 * matters: a wand that changes the block beside the one he pointed at, or eats the chest next to
 * it, is worse than no wand. Red on {@code main}, where {@code change_block} was not an effect.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ChangeBlockGameTest {

    /** Its own batch: mock players and the reloads each make_item does are process-wide. */
    private static final String BATCH = "changeblock";

    /** Where he stands, and the block three ahead of his eyes that he is looking at. */
    private static final BlockPos FEET = new BlockPos(1, 2, 1);
    private static final BlockPos AIMED = new BlockPos(1, 3, 4);

    private static JsonObject wand(String name, String from, String to, int radius) {
        return wand(name, from, to, radius, null);
    }

    /**
     * ⚠ The OLD shape on purpose: {@code change_block} grouped, with BLOCK. make_item renames it
     * to turn_into's FROM/TO at the edge, so every test here also proves an old-shaped call still makes
     * a working wand. TurnIntoGameTest drives the new shape.
     */
    private static JsonObject wand(String name, String from, String to, int radius, String base) {
        JsonObject o = JsonParser.parseString("""
                {"name":"%s","icon":"wand","colour":"purple","shape":"held","count":1,
                 "effect":"change_block","params":{"change_block":
                   {"FROM":"%s","BLOCK":"%s","RADIUS":"%d"}}}""".formatted(name, from, to, radius))
                .getAsJsonObject();
        if (base != null) {
            o.addProperty("base_item", base);
        }
        return o;
    }

    /**
     * Make the wand, give it to a mock player standing at {@link #FEET} facing south (towards
     * {@link #AIMED}), and right-click once. Returns null, having failed the test, on a refusal.
     */
    private static ServerPlayer zap(GameTestHelper helper, String name, String from, String to, int radius) {
        return zap(helper, name, from, to, radius, null, false);
    }

    /**
     * @param base      a real item to build it on (base_item), or null for one made from scratch
     * @param clickBlock click AT the aimed block the way a client does in reach — use-on-the-block
     *                  first, the air-use only if that did not take the click — rather than in air
     */
    private static ServerPlayer zap(GameTestHelper helper, String name, String from, String to, int radius,
                                    String base, boolean clickBlock) {
        if (!CbScriptCompiler.available()) {
            System.out.println("CHANGE_BLOCK: SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return null;
        }
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(wand(name, from, to, radius, base));
        if (r.isError()) {
            helper.fail(Component.literal("make_item refused change_block: " + r.text()));
            return null;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        String line = MakeItemTool.gives("held", tool.lastMadeId(), ItemCatalogue.icon("wand"), 1,
                "Right-click to use it.", java.util.List.of(new MakeItemTool.Variant(name, 0x9966FF)),
                ItemRecipient.ME, null, base == null ? ItemBase.WISH_ITEM : base);
        var server = helper.getLevel().getServer();
        p.getInventory().clearContent();
        server.getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(),
                line.strip().substring(1));
        ItemStack wand = ItemStack.EMPTY;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).getHoverName().getString().equals(name)) {
                wand = p.getInventory().getItem(i).copy();
            }
        }
        if (wand.isEmpty()) {
            MockPlayerNetwork.remove(p);
            helper.fail(Component.literal("no wand arrived from: " + line));
            return null;
        }
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, wand);
        var feet = helper.absolutePos(FEET);
        // Facing +z (south, yaw 0), level: his eyes at y+1.62 look straight into AIMED.
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        boolean taken = false;
        if (clickBlock) {
            BlockPos at = helper.absolutePos(AIMED);
            var hit = new net.minecraft.world.phys.BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(at), net.minecraft.core.Direction.NORTH, at, false);
            taken = p.gameMode.useItemOn(p, helper.getLevel(), p.getItemInHand(InteractionHand.MAIN_HAND),
                    InteractionHand.MAIN_HAND, hit).consumesAction();
        }
        if (!taken) {
            p.gameMode.useItem(p, helper.getLevel(), p.getItemInHand(InteractionHand.MAIN_HAND),
                    InteractionHand.MAIN_HAND);
        }
        return p;
    }

    private static boolean is(GameTestHelper helper, BlockPos pos, Block block) {
        return helper.getBlockState(pos).is(block);
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900)
    public static void aWandTurnsTheBlockHePointsAtAndOnlyThatOne(GameTestHelper helper) {
        helper.setBlock(AIMED, Blocks.DIRT);
        helper.setBlock(AIMED.east(), Blocks.DIRT);        // beside it: must stay dirt
        helper.setBlock(AIMED.south(), Blocks.DIRT);       // behind it: must stay dirt
        ServerPlayer p = zap(helper, "Grass Wand", "minecraft:dirt", "minecraft:grass_block", 0);
        if (p == null) {
            return;
        }
        try {
            helper.assertTrue(is(helper, AIMED, Blocks.GRASS_BLOCK),
                    Component.literal("the block he pointed at is still " + helper.getBlockState(AIMED)));
            helper.assertTrue(is(helper, AIMED.east(), Blocks.DIRT), Component.literal("the block BESIDE it changed"));
            helper.assertTrue(is(helper, AIMED.south(), Blocks.DIRT), Component.literal("the block BEHIND it changed"));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
        }
    }

    /**
     * <b>Built on a REAL shovel and clicked at the dirt, it is the wish that happens.</b> Before
     * The bridge, the shovel's own use-on-a-block won — the dirt became a PATH — and the
     * air-use that runs the wish never came (measured in Gate D: 0 of 2 shovels changed a block).
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900)
    public static void aRealShovelClickedAtDirtMakesGrassNotAPath(GameTestHelper helper) {
        helper.setBlock(AIMED, Blocks.DIRT);
        helper.setBlock(AIMED.below(), Blocks.STONE);
        helper.setBlock(AIMED.above(), Blocks.AIR);
        ServerPlayer p = zap(helper, "Grass Shovel", "minecraft:dirt", "minecraft:grass_block", 0,
                "minecraft:iron_shovel", true);
        if (p == null) {
            return;
        }
        try {
            helper.assertFalse(is(helper, AIMED, Blocks.DIRT_PATH),
                    Component.literal("the shovel made a PATH — its own use won and the wish never ran"));
            helper.assertTrue(is(helper, AIMED, Blocks.GRASS_BLOCK),
                    Component.literal("the dirt he clicked is still " + helper.getBlockState(AIMED)));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
        }
    }

    /** Air is "make it disappear" — and the aim fires ONCE, not again at the block behind. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900)
    public static void aVanishingWandRemovesOneBlockNotATunnel(GameTestHelper helper) {
        helper.setBlock(AIMED, Blocks.STONE);
        helper.setBlock(AIMED.south(), Blocks.STONE);
        ServerPlayer p = zap(helper, "Vanish Wand", "any", "minecraft:air", 0);
        if (p == null) {
            return;
        }
        try {
            helper.assertTrue(helper.getBlockState(AIMED).isAir(),
                    Component.literal("the block he pointed at did not disappear: " + helper.getBlockState(AIMED)));
            helper.assertTrue(is(helper, AIMED.south(), Blocks.STONE),
                    Component.literal("the block BEHIND it vanished too — the aim fired twice and dug a tunnel"));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
        }
    }

    /** "Any block" is ground and building material, and never a chest with his things in it. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 900)
    public static void anythingNeverMeansHisChest(GameTestHelper helper) {
        helper.setBlock(AIMED, Blocks.STONE);
        helper.setBlock(AIMED.east(), Blocks.CHEST);
        helper.setBlock(AIMED.west(), Blocks.OAK_PLANKS);
        ServerPlayer p = zap(helper, "Glass Wand", "any", "minecraft:glass", 1);
        if (p == null) {
            return;
        }
        try {
            helper.assertTrue(is(helper, AIMED, Blocks.GLASS), Component.literal("the stone he pointed at is not glass"));
            helper.assertTrue(is(helper, AIMED.west(), Blocks.GLASS), Component.literal("planks are building material "
                    + "and within the radius, so they should be glass"));
            helper.assertTrue(is(helper, AIMED.east(), Blocks.CHEST), Component.literal("'any block' turned his CHEST "
                    + "into glass — and everything in it is gone"));
            helper.assertTrue(helper.getBlockState(AIMED.above()).isAir(),
                    Component.literal("air around the block was 'changed' into glass"));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(p);
        }
    }

    /** A radius of 0 on a thrown thing would change nothing, so it is refused, in words. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aThrownOneMustReachTheBlockItLandsBeside(GameTestHelper helper) {
        JsonObject call = wand("Glass Bomb", "any", "minecraft:glass", 0);
        call.addProperty("shape", "thrown");
        ToolResult r = new MakeItemTool().call(call);
        helper.assertTrue(r.isError() && r.text().contains("RADIUS"),
                Component.literal("a thrown change_block with RADIUS 0 was accepted: " + r.text()));
        helper.succeed();
    }

    /**
     * <b> — "a brush that paints blocks rainbow": ONE brush, a different colour each use, in
     * rainbow order.</b> Red on {@code main}: BLOCK took exactly one block, so the list was refused
     * as not a block id and the agent built one brush per colour. Four uses on the same block: red,
     * orange, yellow, and red again (the cycle wraps).
     */
    @GameTest(template = "empty_test", batch = "changeblock_rainbow", timeoutTicks = 900)
    public static void aRainbowBrushPaintsTheNextColourEachUse(GameTestHelper helper) {
        helper.setBlock(AIMED, Blocks.STONE);
        ServerPlayer p = zap(helper, "Rainbow Brush " + helper.getLevel().getGameTime(), "any",
                "cycle: minecraft:red_wool, minecraft:orange_wool, minecraft:yellow_wool", 0);
        if (p == null) {
            return;
        }
        try {
            Block[] want = {Blocks.RED_WOOL, Blocks.ORANGE_WOOL, Blocks.YELLOW_WOOL, Blocks.RED_WOOL};
            for (int use = 0; use < want.length; use++) {
                if (use > 0) {
                    p.gameMode.useItem(p, helper.getLevel(), p.getItemInHand(InteractionHand.MAIN_HAND),
                            InteractionHand.MAIN_HAND);
                }
                helper.assertTrue(is(helper, AIMED, want[use]), Component.literal("use " + (use + 1) + " painted "
                        + helper.getBlockState(AIMED) + ", not " + want[use] + " — a cycle is the NEXT colour"));
            }
            helper.assertTrue(is(helper, AIMED.east(), Blocks.AIR), Component.literal("the brush painted beside the block"));
        } finally {
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }

    /** A list that does not say how to choose is refused, never guessed. */
    @GameTest(template = "empty_test", batch = "changeblock_rainbow_refuse", timeoutTicks = 100)
    public static void aListWithNoModeIsRefused(GameTestHelper helper) {
        ToolResult r = new MakeItemTool().call(wand("Unsure Brush", "any",
                "minecraft:red_wool, minecraft:blue_wool", 0));
        helper.assertTrue(r.isError() && r.text().contains("cycle:"),
                Component.literal("a bare list was accepted, or refused without saying how: " + r.text()));
        helper.succeed();
    }
}
