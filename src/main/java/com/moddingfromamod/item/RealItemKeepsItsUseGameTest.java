package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.VanillaCarrier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a wish on a real item never takes that item's own use away.</b>
 *
 * <p>The laser bow, and every item like it: since wishes ride real bases, a {@code held} wish on a real base ran on
 * right-click and then CANCELLED the click, so a bow could not be drawn, a shield could not block, a
 * cookie could not be eaten and a snowball's own throw was replaced. Red on {@code main} before
 * this stage: each {@code isUsingItem} below was false.
 *
 * <p>Through the REAL server path ({@code ServerPlayerGameMode.useItem} on a real
 * {@link ServerPlayer}), with stacks carrying the same wish data a give line writes — the backstop
 * for any stack given before make_item stopped building them. And {@code make_item} itself: a
 * {@code held} bow comes back as an {@code arrow} bow, and a {@code held} water bucket — whose own
 * use no shape rides — is refused, never built to break.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RealItemKeepsItsUseGameTest {

    /** Places a diamond block where it runs: one assertion for "did the wish ride along". */
    private static final String PROBE_FN = ModdingFromAModMod.MODID + ":ab_probe";

    private static ItemStack heldWishOn(Item item, int count) {
        ItemStack s = new ItemStack(item, count);
        CompoundTag tag = new CompoundTag();
        tag.putString(WishItem.ON_USE, PROBE_FN);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    private static boolean probeNear(GameTestHelper helper, BlockPos at) {
        boolean found = false;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 2, 1))) {
            if (helper.getLevel().getBlockState(q).is(Blocks.DIAMOND_BLOCK)) {
                helper.getLevel().setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
                found = true;
            }
        }
        return found;
    }

    /** Right-click in the air through the real server path, then say whether vanilla's use began. */
    private static boolean useBegins(GameTestHelper helper, ServerPlayer p, ItemStack stack, String what) {
        p.stopUsingItem();
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        p.gameMode.useItem(p, p.level(), p.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
        boolean using = p.isUsingItem() && p.getUseItem().is(stack.getItem());
        helper.assertTrue(probeNear(helper, p.blockPosition()),
                Component.literal(what + ": the wish did not ride along with the item's own use"));
        return using;
    }

    // OWN BATCH: player() clears every stale mock, including a concurrent sibling's (see
    // VanillaCarrierAbilityGameTest's mark test); batches are sequential, so this is isolation.
    @GameTest(template = "empty_test", batch = "realitemuse", timeoutTicks = 600)
    public static void aWishOnARealItemLeavesItsOwnUseAlone(GameTestHelper helper) {
        BlockPos standRel = new BlockPos(4, 2, 4);
        helper.setBlock(standRel.below(), Blocks.STONE);
        VanillaCarrier.force(true);
        try {
            com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
            ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
            BlockPos stand = helper.absolutePos(standRel);
            p.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            // SURVIVAL: in creative nothing is consumed, so "thrown once, one fewer in hand" could
            // not be read, and a bow draws with no arrow at all.
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            p.getFoodData().setFoodLevel(4);

            helper.assertTrue(useBegins(helper, p, heldWishOn(Items.COOKIE, 4), "cookie"),
                    Component.literal("a wished cookie cannot be EATEN: the wish took the eating away (#952)"));
            helper.assertTrue(useBegins(helper, p, heldWishOn(Items.POTION, 1), "potion"),
                    Component.literal("a wished potion cannot be DRUNK (#952)"));
            helper.assertTrue(useBegins(helper, p, heldWishOn(Items.SHIELD, 1), "shield"),
                    Component.literal("a wished shield cannot BLOCK (#952)"));
            p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.ARROW, 16));
            helper.assertTrue(useBegins(helper, p, heldWishOn(Items.BOW, 1), "bow"),
                    Component.literal("a wished bow cannot be DRAWN — the laser bow (#952)"));
            p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            helper.assertTrue(useBegins(helper, p, heldWishOn(Items.SPYGLASS, 1), "spyglass"),
                    Component.literal("a wished spyglass cannot be LOOKED THROUGH (#952)"));

            // A snowball's use is its throw, which is not a "using" state: it leaves his hand as a
            // snowball, exactly once.
            p.stopUsingItem();
            AABB around = new AABB(stand).inflate(8);
            int before = helper.getLevel().getEntitiesOfClass(Snowball.class, around).size();
            ItemStack balls = heldWishOn(Items.SNOWBALL, 8);
            p.setItemInHand(InteractionHand.MAIN_HAND, balls);
            p.gameMode.useItem(p, p.level(), p.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
            helper.assertTrue(probeNear(helper, p.blockPosition()), Component.literal("snowball: the wish did not ride along"));
            int thrown = helper.getLevel().getEntitiesOfClass(Snowball.class, around).size() - before;
            helper.assertTrue(thrown == 1 && p.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 7,
                    Component.literal("a wished snowball must be thrown once, as itself — thrown " + thrown + ", left "
                            + p.getItemInHand(InteractionHand.MAIN_HAND).getCount() + " of 8 (#952)"));
            com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
        } finally {
            VanillaCarrier.force(null);
        }
        helper.succeed();
    }

    private static JsonObject wish(String name, String shape, String base) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FFAA00");
        o.addProperty("shape", shape);
        o.addProperty("effect", "light");
        o.addProperty("count", 1);
        o.addProperty("base_item", base);
        JsonObject params = new JsonObject();
        params.addProperty("GLOW_SECONDS", "0");
        o.add("params", params);
        return o;
    }

    /** make_item: a click-taking shape on a bow is moved to `arrow` and said so; on food, refused. */
    @GameTest(template = "empty_test", batch = "realitemuse", timeoutTicks = 900)
    public static void makeItemRidesTheRealItemsOwnUse(GameTestHelper helper) {
        MakeItemTool bowTool = new MakeItemTool();
        ToolResult bow = bowTool.call(wish("Ride Bow " + helper.getLevel().getGameTime(), "held",
                "minecraft:bow"));
        helper.assertFalse(bow.isError(), Component.literal("a held bow wish was refused rather than redirected: " + bow.text()));
        helper.assertTrue(bow.text().contains("Built as `arrow`, not `held`"),
                Component.literal("a held bow wish was not moved to `arrow`, or not said so: " + bow.text()));

        // A ride that is not built yet is refused with the path that works today. This was the
        // cookie until on_consume shipped, the shield until on_block, the pearl until on_throw_land. Since
        // then every planned ride ships, and what is left is an action no shape rides at all (a
        // bucket filling: that wish is a property).
        ToolResult bucket = new MakeItemTool().call(wish("Ride Bucket", "held", "minecraft:water_bucket"));
        helper.assertTrue(bucket.isError() && bucket.text().contains("no shape rides this one yet"),
                Component.literal("a held water bucket must be refused, since no shape rides a bucket's own use: "
                        + bucket.text()));

        ToolResult placed = new MakeItemTool().call(wish("Ride Stick", "placed", "minecraft:stick"));
        helper.assertTrue(placed.isError() || placed.text().contains("Built as `held`"),
                Component.literal("`placed` on a real item never runs; it must be moved or refused: " + placed.text()));
        helper.succeed();
    }
}
