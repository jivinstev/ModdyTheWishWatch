package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.OffhandMatrix.Col;
import com.moddingfromamod.item.OffhandMatrix.Row;
import com.moddingfromamod.testing.MockPlayerNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The offhand click matrix, one test per kind of wished item.</b>
 *
 * <p>Each cell plays ONE stock-client right-click through the real server path, exactly as
 * {@code Minecraft.startUseItem} sends it: in the air {@code useItem} MAIN_HAND, then — only if the
 * client's own prediction of the main hand was not a success — {@code useItem} OFF_HAND; at a block
 * {@code useItemOn} MAIN, {@code useItem} MAIN, then {@code useItemOn} OFF and (unless that was
 * predicted to place a block) {@code useItem} OFF. The client's prediction is asked of vanilla
 * {@code Item.use} on a twin player holding a copy, with none of our server events in the way,
 * which is the code a stock client runs. Every cell logs {@code OFFHAND-MATRIX …} (the table in
 * {@code docs/OFFHAND_CLICK_MATRIX.md} is those lines) and is asserted against the rule:
 *
 * <ul>
 *   <li>the client stops at the main hand by itself → the offhand is untouched;</li>
 *   <li>the server HANDLED the main hand's click (its ability ran) → the offhand is refused;</li>
 *   <li>the main hand did nothing → the offhand does exactly what it does in an empty hand's click.</li>
 * </ul>
 *
 * <p>A fresh mock per cell: "this click" is per player. Its own batch: {@code VanillaCarrier.force}
 * is a global static.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class OffhandClickMatrixGameTest {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-offhand-matrix");
    private static final String BATCH = "offhand_click_matrix";

    enum Where { AIR, BLOCK }

    /** What one cell did: the client's decision, the main hand, the offhand. */
    record Cell(Row row, Col col, Where where, boolean clientStops, String main, String off) {
        String line() {
            return "OFFHAND-MATRIX row=" + row.letter + ":" + row.key() + " col=" + col.key()
                    + " where=" + where.name().toLowerCase(java.util.Locale.ROOT)
                    + " client=" + (clientStops ? "stops" : "continues") + " main=" + main + " off=" + off;
        }
    }

    // ── one cell ─────────────────────────────────────────────────────────────────────────────

    private static ServerPlayer fresh(GameTestHelper helper, String name, BlockPos at) {
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper, name));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.setYRot(0.0F);
        p.setXRot(-30.0F);
        p.getInventory().clearContent();
        p.getInventory().setSelectedSlot(0);
        // Hungry enough to eat: bread in either hand is refused on a full stomach, which would
        // make the bread column say nothing about the click.
        p.getFoodData().setFoodLevel(10);
        return p;
    }

    /** What a stock client's own {@code Item.use} of this main hand answers (a twin, no events). */
    private static boolean clientStops(GameTestHelper helper, BlockPos at, ItemStack main) {
        ServerPlayer twin = fresh(helper, "omx_twin", at);
        try {
            twin.getInventory().setItem(0, main.copy());
            InteractionResult r = com.moddingfromamod.compat.Interactions.use(
                    main.getItem(), twin.level(), twin, InteractionHand.MAIN_HAND);
            return r.consumesAction();
        } finally {
            twin.stopUsingItem();
            MockPlayerNetwork.remove(twin);
        }
    }

    private static AABB box(BlockPos at) {
        return new AABB(at).inflate(12);
    }

    private static void clean(ServerLevel level, BlockPos at) {
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, box(at),
                e -> e instanceof Snowball || e instanceof ItemEntity || e instanceof ArmorStand)) {
            e.discard();
        }
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-4, -2, -4), at.offset(4, 3, 4))) {
            level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(target(at), Blocks.STONE.defaultBlockState());
    }

    /** The floor block a block-click aims at: in front of him, not under him (an anchor will not
     *  go down where he stands). */
    private static BlockPos target(BlockPos at) {
        return at.offset(0, -1, 2);
    }

    private static boolean gold(ServerLevel level, BlockPos at) {
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-4, -2, -4), at.offset(4, 3, 4))) {
            if (level.getBlockState(q).is(Blocks.GOLD_BLOCK)) {
                return true;
            }
        }
        return false;
    }

    static Cell play(GameTestHelper helper, Row row, Col col, Where where) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        ItemStack mainStack = OffhandMatrix.stack(row, level.registryAccess());
        ItemStack offStack = OffhandMatrix.offhand(col);
        if (mainStack == null || offStack == null) {
            return null;
        }
        clean(level, at);
        boolean stops = clientStops(helper, at, mainStack);
        ServerPlayer p = fresh(helper, "omx_" + row.letter + "_" + col.key(), at);
        int boxes = com.moddingfromamod.wish.WishCompose.openedCount(p.getUUID());
        try {
            p.getInventory().setItem(0, mainStack.copy());
            p.setItemInHand(InteractionHand.OFF_HAND, offStack.copy());
            BlockHitResult floor = new BlockHitResult(Vec3.atCenterOf(target(at)).add(0, 0.5, 0),
                    Direction.UP, target(at), false);
            if (where == Where.BLOCK) {
                p.gameMode.useItemOn(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND, floor);
            }
            p.gameMode.useItem(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND);
            if (!stops) {
                boolean offPlaces = false;
                if (where == Where.BLOCK) {
                    // The client predicts a block item's placement as a success and stops there.
                    offPlaces = p.getOffhandItem().getItem() instanceof BlockItem;
                    p.gameMode.useItemOn(p, level, p.getOffhandItem(), InteractionHand.OFF_HAND, floor);
                }
                if (!offPlaces && !p.getOffhandItem().isEmpty()) {
                    p.gameMode.useItem(p, level, p.getOffhandItem(), InteractionHand.OFF_HAND);
                }
            }
            return new Cell(row, col, where, stops, observeMain(level, at, p, row, boxes), observeOff(level, at, p, col));
        } finally {
            p.stopUsingItem();
            if (p.containerMenu != p.inventoryMenu) {
                p.closeContainer();
            }
            MockPlayerNetwork.remove(p);
            clean(level, at);
        }
    }

    private static String observeMain(ServerLevel level, BlockPos at, ServerPlayer p, Row row, int boxesBefore) {
        List<String> seen = new ArrayList<>();
        // "panel" is MODDY OPENED: his chest, or (on by default, 2026-11-16) the text-first wish box.
        if (p.containerMenu instanceof com.moddingfromamod.wish.WatchMenu
                || com.moddingfromamod.wish.WishCompose.openedCount(p.getUUID()) > boxesBefore) {
            seen.add("panel");
        }
        if (gold(level, at)) {
            seen.add("ability");
        }
        if (!level.getEntitiesOfClass(Snowball.class, box(at),
                s -> s.entityTags().contains(OffhandMatrix.THROW_TAG)).isEmpty()) {
            seen.add("thrown");
        }
        if (!level.getEntitiesOfClass(ArmorStand.class, box(at),
                s -> s.entityTags().contains(OffhandMatrix.PLACE_TAG)).isEmpty()) {
            seen.add("placed");
        }
        if (row == Row.WORN && !p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
            seen.add("equipped");
        }
        if (p.isUsingItem() && p.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            seen.add(p.getUseItem().is(Items.BUNDLE) ? "emptying" : "eating");
        }
        return seen.isEmpty() ? "-" : String.join("+", seen);
    }

    private static String observeOff(ServerLevel level, BlockPos at, ServerPlayer p, Col col) {
        ItemStack off = p.getOffhandItem();
        boolean usingOff = p.isUsingItem() && p.getUsedItemHand() == InteractionHand.OFF_HAND;
        return switch (col) {
            case EMPTY -> off.isEmpty() ? "-" : "?" + off;
            case SNOWBALL -> {
                boolean flew = !level.getEntitiesOfClass(Snowball.class, box(at),
                        s -> !s.entityTags().contains(OffhandMatrix.THROW_TAG)).isEmpty();
                yield flew || off.getCount() != 8 ? "thrown" : "-";
            }
            case BUNDLE -> usingOff || OffhandMatrix.inside(off) != 8 ? "emptying" : "-";
            case SHIELD -> usingOff ? "raised" : "-";
            case BREAD -> usingOff || off.getCount() != 5 ? "eating" : "-";
            case TORCH -> {
                boolean placed = false;
                for (BlockPos q : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, 2, 2))) {
                    placed |= level.getBlockState(q).is(Blocks.TORCH);
                }
                yield placed || off.getCount() != 16 ? "placed" : "-";
            }
        };
    }

    /** Every cell of one row: log it, and compare it with the rule. */
    private static void row(GameTestHelper helper, Row row) {
        List<String> wrong = new ArrayList<>();
        int played = 0;
        try {
            com.moddingfromamod.datapack.VanillaCarrier.force(true);
            for (Where where : Where.values()) {
                for (Col col : Col.values()) {
                    Cell c = play(helper, row, col, where);
                    if (c == null) {
                        LOG.info("OFFHAND-MATRIX row={}:{} col={} where={} n/a on this Minecraft",
                                row.letter, row.key(), col.key(), where);
                        continue;
                    }
                    played++;
                    LOG.info(c.line());
                    String wantMain = OffhandMatrix.expectMain(row, where == Where.BLOCK);
                    String wantOff = OffhandMatrix.expectOff(row, col, where == Where.BLOCK);
                    if (c.clientStops() != OffhandMatrix.expectClientStops(row) || !c.main().equals(wantMain)
                            || !c.off().equals(wantOff)) {
                        wrong.add(c.line() + "   WANT client=" + (OffhandMatrix.expectClientStops(row) ? "stops" : "continues")
                                + " main=" + wantMain + " off=" + wantOff);
                    }
                }
            }
        } finally {
            com.moddingfromamod.datapack.VanillaCarrier.force(null);
        }
        if (played == 0) {
            // A row this Minecraft cannot make (a bundle on 1.21.1) is not a row here.
            helper.succeed();
            return;
        }
        wrong.forEach(w -> LOG.warn("OFFHAND-MATRIX WRONG {}", w));
        // Short: a GameTest failure is also written into the world, and a long one is refused there.
        helper.assertTrue(wrong.isEmpty(), Component.literal(wrong.size() + " cell(s) of row " + row.letter + " ("
                + row.key() + ") break one click, one hand; first: "
                + (wrong.isEmpty() ? "" : wrong.get(0).replace("OFFHAND-MATRIX ", ""))));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aKeepsakesClickLeavesTheOffhandWorking(GameTestHelper helper) {
        row(helper, Row.KEEPSAKE);
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aHeldWishsClickUsesOnlyTheMainHand(GameTestHelper helper) {
        row(helper, Row.HELD);
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aHeldWishOnARealStickUsesOnlyTheMainHand(GameTestHelper helper) {
        row(helper, Row.HELD_ON_STICK);
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aPlacedWishUsesOnlyTheMainHandAtABlockAndNothingInTheAir(GameTestHelper helper) {
        row(helper, Row.PLACED);
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aWishOnRealBreadIsEatenAndTheOffhandIsNeverAsked(GameTestHelper helper) {
        row(helper, Row.REAL_FOOD);
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aWornWishIsPutOnAndTheOffhandIsNeverAsked(GameTestHelper helper) {
        row(helper, Row.WORN);
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aThrownWishIsThrownAloneWithoutTheOffhand(GameTestHelper helper) {
        row(helper, Row.THROWN);
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void aWishedBagEmptiesItselfAndNotTheOffhand(GameTestHelper helper) {
        row(helper, Row.BAG);
    }

    @GameTest(template = "empty_test", batch = BATCH)
    public static void moddyOpensThePanelAndNeverTheOffhand(GameTestHelper helper) {
        row(helper, Row.MODDY);
    }

    /**
     * "This click" ends where the next one begins: a handled click (its offhand refused) followed by
     * a keepsake's click, same player, same tick — the second click's offhand is his again. Real
     * clicks are at least four ticks apart; the same tick is the hardest case for the rule.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theNextClickStartsCleanAfterARefusedOffhand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        ServerPlayer p = fresh(helper, "omx_next", at);
        try {
            com.moddingfromamod.datapack.VanillaCarrier.force(true);
            clean(level, at);
            p.getInventory().setItem(0, OffhandMatrix.stack(Row.HELD, level.registryAccess()));
            p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SNOWBALL, 8));
            p.gameMode.useItem(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND);
            p.gameMode.useItem(p, level, p.getOffhandItem(), InteractionHand.OFF_HAND);
            helper.assertTrue(p.getOffhandItem().getCount() == 8,
                    Component.literal("the held wish's click must leave the offhand alone: " + p.getOffhandItem()));
            p.getInventory().setItem(0, OffhandMatrix.stack(Row.KEEPSAKE, level.registryAccess()));
            p.gameMode.useItem(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND);
            p.gameMode.useItem(p, level, p.getOffhandItem(), InteractionHand.OFF_HAND);
            helper.assertTrue(p.getOffhandItem().getCount() == 7,
                    Component.literal("a keepsake's click after it is a new click, and throws the snowball as ever: "
                            + p.getOffhandItem()));
        } finally {
            com.moddingfromamod.datapack.VanillaCarrier.force(null);
            MockPlayerNetwork.remove(p);
            clean(level, at);
        }
        helper.succeed();
    }
}
