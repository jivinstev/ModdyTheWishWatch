package com.moddingfromamod.item;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.agent.tools.RemoveItemTool;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.testing.MockPlayerNetwork;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Blocks with effects</b>, each part driven through the real {@code make_item}, the real
 * give, a real right-click on the ground and the compiled pack's own clock — the gates the issue
 * names: inside the radius and never outside it, a zombie and not a cow, a fill that skips a chest
 * and is taken back on undo, a walk-through bedrock refused, and a block that goes off when broken.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class MagicBlockGameTest {

    private static boolean skipped(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("MAGIC BLOCK: SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return true;
        }
        return false;
    }

    private static JsonObject spec(String name, String effect, JsonObject params, JsonObject block) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "orb");
        o.addProperty("colour", "#6A3FB5");
        o.addProperty("shape", "magic_block");
        o.addProperty("effect", effect);
        o.addProperty("count", 1);
        o.add("params", params);
        o.add("block", block);
        return o;
    }

    private static JsonObject obj(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            o.addProperty(kv[i], kv[i + 1]);
        }
        return o;
    }

    /** Make it, give it, and put it down on {@code floor}. Returns the item's id. */
    private static String makeAndPlace(GameTestHelper helper, ServerPlayer p, JsonObject call, BlockPos floor) {
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(call);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused: " + r.text()));
        String name = call.get("name").getAsString();
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(),
                "wishdatapack " + tool.lastMadeId() + " give");
        ItemStack it = ItemStack.EMPTY;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                it = s.copy();
            }
        }
        helper.assertTrue(!it.isEmpty(), Component.literal("the real give handed over nothing named " + name));
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, it);
        p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
        return tool.lastMadeId();
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos floor, double awayZ) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper));
        p.snapTo(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5 + awayZ, 180.0F, 0.0F);
        return p;
    }

    /**
     * A creature that stays where it is put. Keep it INSIDE the 9x9 plot: the first cut stood a
     * cow three blocks west of (2,1,2), inside the plot's surrounding blocks, and it suffocated at
     * exactly one heart per ten ticks — which read as the storm block hitting the cow.
     */
    private static <T extends Mob> T still(GameTestHelper helper, EntityType<T> type, BlockPos at) {
        // helper.spawn (relative, the same on both Minecraft versions); `at` is absolute.
        BlockPos rel = at.subtract(helper.absolutePos(BlockPos.ZERO));
        T m = helper.spawn(type, rel);
        m.setNoAi(true);
        m.setPersistenceRequired();
        return m;
    }

    private static boolean anchorsLeft(GameTestHelper helper, String id, BlockPos near) {
        return !helper.getLevel().getEntitiesOfClass(ArmorStand.class, new AABB(near).inflate(3),
                a -> a.entityTags().contains(id + "_built")).isEmpty();
    }

    /**
     * A launch pad throws a PLAYER, not only a mob. Motion written by a command does nothing to a
     * player on a real client, so launch lifts a player with levitation (launch.cbs); a mock player
     * WOULD take Motion, which is how a pad that left every real child standing on it passed here.
     * Found by filming a real client (EffectBlockFilmClientTest).
     */
    @GameTest(template = "empty_test", batch = "magic_block", timeoutTicks = 600)
    public static void aLaunchPadLiftsAPlayerNotJustAMob(GameTestHelper helper) {
        if (skipped(helper)) {
            return;
        }
        BlockPos floor = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        ServerPlayer p = player(helper, floor, 2);
        makeAndPlace(helper, p, spec("Pad Test " + helper.getLevel().getGameTime(), "launch",
                obj("POWER", "launch"),
                obj("when", "stepped_on", "who", "everyone", "look", "minecraft:slime_block")), floor);
        BlockPos body = floor.above();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!helper.getLevel().getBlockState(body).is(Blocks.SLIME_BLOCK)) {
                        throw GameTestCompat.assertion("the pad was put down and no body appeared");
                    }
                })
                .thenExecute(() -> p.teleportTo(body.getX() + 0.5, body.getY() + 1, body.getZ() + 0.5))
                .thenWaitUntil(() -> {
                    if (!p.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)) {
                        throw GameTestCompat.assertion("a player stood on the launch pad and was given "
                                + "no levitation — Motion alone never moves a real player");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "empty_test", batch = "magic_block", timeoutTicks = 600)
    public static void aPullReachesInsideItsRadiusAndNeverOutsideIt(GameTestHelper helper) {
        if (skipped(helper)) {
            return;
        }
        BlockPos floor = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        ServerPlayer p = player(helper, floor, 6);
        makeAndPlace(helper, p, spec("Pull Test " + helper.getLevel().getGameTime(), "pull",
                obj("STRENGTH", "strong", "REACH", "3"),
                obj("when", "every", "seconds", "0.25", "who", "everyone", "radius", "3")), floor);
        Vec3 centre = Vec3.atBottomCenterOf(floor.above());
        var inside = still(helper, EntityType.COW, floor.above().east(2).north(1));
        var outside = still(helper, EntityType.COW, floor.above().west(4));
        double inBefore = inside.position().distanceTo(centre);
        Vec3 outBefore = outside.position();
        helper.runAfterDelay(80, () -> {
            double inAfter = inside.position().distanceTo(centre);
            helper.assertTrue(inAfter < inBefore - 0.5,
                    Component.literal("a cow " + String.format("%.2f", inBefore) + " blocks away, inside the radius, was "
                            + "not pulled in (now " + String.format("%.2f", inAfter) + ")"));
            helper.assertTrue(inAfter >= 1.0, Component.literal("the pull must stop short of the block, not stand the "
                    + "cow inside it: " + String.format("%.2f", inAfter)));
            helper.assertTrue(outside.position().distanceTo(outBefore) < 0.01,
                    Component.literal("a cow OUTSIDE the radius moved " + outside.position().distanceTo(outBefore)
                            + " — a pull with no edge tugs at the whole world"));
            inside.discard();
            outside.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "magic_block", timeoutTicks = 600)
    public static void aStormBlockHitsTheMonsterAndNotTheCow(GameTestHelper helper) {
        if (skipped(helper)) {
            return;
        }
        BlockPos floor = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        ServerPlayer p = player(helper, floor, 7);
        // NO `who`: anything that hurts reaches hostile mobs unless the wish said otherwise.
        makeAndPlace(helper, p, spec("Storm Test " + helper.getLevel().getGameTime(), "lightning",
                obj("STRIKES", "1"),
                obj("seconds", "0.5", "where", "on_each", "radius", "5")), floor);
        LivingEntity monster = still(helper, EntityType.HUSK, floor.above().east(3));
        LivingEntity cow = still(helper, EntityType.COW, floor.above().west(3));
        float monsterHp = monster.getHealth();
        float cowHp = cow.getHealth();
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(!monster.isAlive() || monster.getHealth() < monsterHp,
                    Component.literal("the storm block never struck the husk within its radius"));
            helper.assertTrue(cow.isAlive() && cow.getHealth() >= cowHp,
                    Component.literal("the storm block hurt the COW (" + cow.getHealth() + " of " + cowHp + ") — a "
                            + "harmful block must leave his animals alone unless he asked"));
            monster.discard();
            cow.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "magic_block", timeoutTicks = 800)
    public static void aFillSkipsTheChestAndIsTakenBackOnUndo(GameTestHelper helper) {
        if (skipped(helper)) {
            return;
        }
        BlockPos floor = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        BlockPos chest = floor.above().east();
        helper.getLevel().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        if (helper.getLevel().getBlockEntity(chest) instanceof ChestBlockEntity c) {
            c.setItem(0, new ItemStack(Items.DIAMOND, 5));
        }
        ServerPlayer p = player(helper, floor, 6);
        String id = makeAndPlace(helper, p, spec("Fill Test " + helper.getLevel().getGameTime(),
                "fill_blocks", obj("BLOCK", "minecraft:glass", "RADIUS", "1"),
                obj("seconds", "0.5")), floor);
        BlockPos above = floor.above().above();
        AtomicReference<ToolResult> undo = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!helper.getLevel().getBlockState(above).is(Blocks.GLASS)) {
                        throw GameTestCompat.assertion("the space round the block never filled");
                    }
                })
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getBlockState(chest).is(Blocks.CHEST),
                            Component.literal("the fill replaced the CHEST"));
                    helper.assertTrue(helper.getLevel().getBlockEntity(chest) instanceof ChestBlockEntity c
                                    && c.getItem(0).is(Items.DIAMOND) && c.getItem(0).getCount() == 5,
                            Component.literal("the chest's diamonds are gone"));
                    JsonObject a = new JsonObject();
                    a.addProperty("id", id);
                    Thread t = new Thread(() -> undo.set(new RemoveItemTool().call(a)), "magic-undo");
                    t.setDaemon(true);
                    t.start();
                })
                .thenWaitUntil(() -> {
                    if (undo.get() == null) {
                        throw GameTestCompat.assertion("remove_item has not answered yet");
                    }
                })
                .thenExecute(() -> {
                    helper.assertTrue(!undo.get().isError(), Component.literal("undo refused: " + undo.get().text()));
                    int glass = 0;
                    for (BlockPos q : BlockPos.betweenClosed(floor.offset(-1, 0, -1), floor.offset(1, 3, 1))) {
                        if (helper.getLevel().getBlockState(q).is(Blocks.GLASS)) {
                            glass++;
                        }
                    }
                    helper.assertTrue(glass == 0, Component.literal(glass + " glass blocks were left behind after undo"));
                    helper.assertTrue(helper.getLevel().getBlockState(chest).is(Blocks.CHEST),
                            Component.literal("undo took the CHEST"));
                    helper.assertTrue(!anchorsLeft(helper, id, floor.above()),
                            Component.literal("the block itself is still standing after undo"));
                    helper.assertTrue(helper.getLevel().getBlockState(floor.above()).isAir(),
                            Component.literal("undo left the block's body behind: " + helper.getLevel().getBlockState(floor.above())));
                    helper.getLevel().setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
                })
                .thenSucceed();
    }

    @GameTest(template = "empty_test", batch = "magic_block", timeoutTicks = 200)
    public static void aBodyThatCannotBeBrokenOrHoldsThingsIsRefused(GameTestHelper helper) {
        MakeItemTool tool = new MakeItemTool();
        ToolResult bedrock = tool.call(spec("Ghost Bedrock", "heal", obj("SECONDS", "5"),
                obj("look", "minecraft:bedrock", "walk_through", "true")));
        helper.assertTrue(bedrock.isError(), Component.literal("a walk-through BEDROCK was made: " + bedrock.text()));
        helper.assertTrue(bedrock.text().contains("never be broken"), Component.literal(bedrock.text()));
        ToolResult chest = tool.call(spec("Chest Body", "heal", obj("SECONDS", "5"),
                obj("look", "minecraft:chest")));
        helper.assertTrue(chest.isError(), Component.literal("a body that is a real chest was made: " + chest.text()));
        JsonObject held = spec("Held Dials", "heal", obj("SECONDS", "5"), obj("radius", "4"));
        held.addProperty("shape", "held");
        ToolResult wrongShape = tool.call(held);
        helper.assertTrue(wrongShape.isError(), Component.literal("`block` on a HELD thing was silently ignored: "
                + wrongShape.text()));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "magic_block", timeoutTicks = 600)
    public static void aBlockThatGoesOffWhenBrokenGivesASurpriseAndNotItself(GameTestHelper helper) {
        if (skipped(helper)) {
            return;
        }
        BlockPos floor = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        ServerPlayer p = player(helper, floor, 4);
        String id = makeAndPlace(helper, p, spec("Surprise Test " + helper.getLevel().getGameTime(),
                "outcome_table",
                obj("PRESENTS", "minecraft:diamond*2", "SURPRISES", "minecraft:chicken", "BOOM", "no"),
                obj("when", "broken", "look", "minecraft:gold_block")), floor);
        BlockPos body = floor.above();
        AABB round = new AABB(body).inflate(3);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!helper.getLevel().getBlockState(body).is(Blocks.GOLD_BLOCK)) {
                        throw GameTestCompat.assertion("the block was put down and no body appeared");
                    }
                })
                .thenExecute(() -> helper.getLevel().destroyBlock(body, true))
                .thenWaitUntil(() -> {
                    boolean present = !helper.getLevel().getEntitiesOfClass(ItemEntity.class, round,
                            e -> e.getItem().is(Items.DIAMOND)).isEmpty();
                    boolean surprise = !helper.getLevel().getEntitiesOfClass(
                            net.minecraft.world.entity.animal.chicken.Chicken.class, round).isEmpty();
                    if (!present && !surprise) {
                        throw GameTestCompat.assertion("breaking it produced neither a present nor a surprise");
                    }
                })
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, round,
                                    e -> e.getItem().is(Items.GOLD_BLOCK)).isEmpty(),
                            Component.literal("breaking it dropped a free GOLD BLOCK — the body must never be the prize"));
                    helper.assertTrue(!anchorsLeft(helper, id, body),
                            Component.literal("a used-up block's anchor is still there, so it could go off again"));
                    helper.getLevel().getEntitiesOfClass(ItemEntity.class, round).forEach(e -> e.discard());
                    helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.chicken.Chicken.class,
                            round).forEach(e -> e.discard());
                })
                .thenSucceed();
    }

    /**
     * A NEW BLOCK with no power at all: the smoke's held-out "a new block of his own" came
     * back as a keepsake he could not place, because make_item demanded an effect. It is a body he
     * puts down and breaks, and breaking it hands it back.
     */
    @GameTest(template = "empty_test", batch = "magic_block", timeoutTicks = 600)
    public static void aBlockWithNoPowerIsStillABlockHePlacesAndGetsBack(GameTestHelper helper) {
        if (skipped(helper)) {
            return;
        }
        BlockPos floor = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        ServerPlayer p = player(helper, floor, 4);
        String name = "Plain Block Test " + helper.getLevel().getGameTime();
        JsonObject call = new JsonObject();
        call.addProperty("name", name);
        call.addProperty("icon", "block");
        call.addProperty("colour", "#33CCAA");
        call.addProperty("shape", "magic_block");
        call.addProperty("count", 1);
        call.add("block", obj("look", "minecraft:prismarine_bricks"));
        String id = makeAndPlace(helper, p, call, floor);
        BlockPos body = floor.above();
        AABB round = new AABB(body).inflate(3);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!helper.getLevel().getBlockState(body).is(Blocks.PRISMARINE_BRICKS)) {
                        throw GameTestCompat.assertion("the block was put down and no body appeared");
                    }
                })
                .thenExecute(() -> helper.getLevel().destroyBlock(body, true))
                .thenWaitUntil(() -> {
                    boolean back = p.getInventory().getNonEquipmentItems().stream()
                            .anyMatch(s -> !s.isEmpty() && s.getHoverName().getString().equals(name));
                    if (!back) {
                        throw GameTestCompat.assertion("breaking it did not hand the block back");
                    }
                })
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, round,
                                    e -> e.getItem().is(Items.PRISMARINE_BRICKS)).isEmpty(),
                            Component.literal("breaking it dropped a free block of what it LOOKS like, as well as itself"));
                    helper.assertTrue(!anchorsLeft(helper, id, body), Component.literal("its anchor is still there"));
                    helper.getLevel().getEntitiesOfClass(ItemEntity.class, round).forEach(e -> e.discard());
                })
                .thenSucceed();
    }

    /**
     * A refusal that comes AFTER the design is stored must take it back: the store is
     * written first, and a build that then fails used to leave a design no pack carries, which the
     * next re-wish inherited. A present that is not a real item is refused at that late step.
     */
    @GameTest(template = "empty_test", batch = "magic_block", timeoutTicks = 200)
    public static void aLateRefusalLeavesNoDesignBehind(GameTestHelper helper) {
        if (skipped(helper)) {
            return;
        }
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(spec("Ghost Test " + helper.getLevel().getGameTime(), "outcome_table",
                obj("PRESENTS", "minecraft:not_a_real_item", "SURPRISES", "none", "BOOM", "no"),
                obj("when", "broken")));
        helper.assertTrue(r.isError(), Component.literal("a present that is not a real item was accepted: " + r.text()));
        helper.assertTrue(com.moddingfromamod.item.ItemSpecStore.own(tool.lastMadeId()) == null,
                Component.literal("the refused item's design was left in the store: " + tool.lastMadeId()));
        helper.succeed();
    }
}
