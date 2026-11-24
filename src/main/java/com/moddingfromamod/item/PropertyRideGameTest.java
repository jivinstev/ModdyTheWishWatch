package com.moddingfromamod.item;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>T6F follow-ups: two PROPERTIES that change what a real item's own action
 * produces, driven through the real use path.</b>
 *
 * <ul>
 *   <li><b> </b> — "a bow that shoots fireballs": the bow is drawn and loosed exactly as vanilla
 *       does it, and what flies is a fireball owned by him, where the arrow would have been, going
 *       where it was going. No arrow flies. (Red on {@code main}: {@code make_item} refused the
 *       property, which did not exist; the pass graded every run {@code partly} because the arrow
 *       made fire where it LANDED.)</li>
 *   <li><b> </b> — "a bucket that never runs out of water": three pours through the real use
 *       path, three water blocks, and a full wished bucket in his hand after each. (Red on
 *       {@code main}: no such property; a vanilla bucket empties.)</li>
 * </ul>
 *
 * <p>Each test is its own batch: the mock-player helper clears every stale mock, including a
 * concurrent sibling's, and batches are sequential.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PropertyRideGameTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    /**
     * make_item must ACCEPT the wish (the tool is the gate the agent meets), then the item is handed
     * over through the same give line the tool renders, run as a real command.
     */
    private static ItemStack made(GameTestHelper helper, ServerPlayer p, String name, String base, String props) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FF8020");
        o.addProperty("shape", "plain");
        o.addProperty("count", 1);
        o.addProperty("base_item", base);
        o.add("properties", json(props));
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        if (r.isError()) {
            throw GameTestCompat.assertion("make_item refused " + props + " on " + base + ": " + r.text());
        }
        String line = MakeItemTool.gives("plain", tool.lastMadeId(),
                com.moddingfromamod.datapack.ItemCatalogue.icon("wand"), 1, "It's yours to keep.",
                java.util.List.of(new MakeItemTool.Variant(name, 0xFF8020, json(props))),
                com.moddingfromamod.datapack.ItemRecipient.ME, null, base);
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(), line.strip().substring(1));
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                return s.copy();
            }
        }
        throw GameTestCompat.assertion("nothing arrived from: " + line);
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos feet, float pitch) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, pitch);
        return p;
    }

    // ── ─────────────────────────────────────────────────────────────────────────────────

    private static void loosesInsteadOfAnArrow(GameTestHelper helper, String base, String word,
                                               Class<? extends Projectile> expected) {
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = player(helper, feet, 0.0F);
        try {
            ItemStack bow = made(helper, p, "Shooter " + word + " " + helper.getLevel().getGameTime(), base,
                    "{\"projectile\":\"" + word + "\",\"enchantments\":{\"infinity\":1}}");
            helper.assertTrue(WishItem.projectile(bow).equals(
                            com.moddingfromamod.datapack.ItemProperties.PROJECTILES.get(word)),
                    Component.literal("the stack carries the projectile the wish named: " + WishItem.projectile(bow)));
            p.getInventory().clearContent();
            p.setItemInHand(InteractionHand.MAIN_HAND, bow);
            p.getInventory().setItem(1, new ItemStack(Items.ARROW, 4));   // a slot, not add(): LEARNINGS
            p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
            AABB around = new AABB(feet).inflate(24);
            if (base.endsWith("crossbow")) {
                p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
                // 1.21.5+ loads a crossbow in onUseTick once it is fully drawn (releaseUsing no longer
        // does), so draw it fully first, as holding the button does.
        p.getMainHandItem().getItem().onUseTick(helper.getLevel(), p, p.getMainHandItem(),
                p.getMainHandItem().getUseDuration(p)
                        - net.minecraft.world.item.CrossbowItem.getChargeDuration(p.getMainHandItem(), p));
        p.getMainHandItem().releaseUsing(helper.getLevel(), p, 0);
                p.stopUsingItem();
                helper.assertTrue(net.minecraft.world.item.CrossbowItem.isCharged(p.getMainHandItem()),
                        Component.literal("the wished crossbow did not charge — the property took its use away"));
                p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
            } else {
                p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
                helper.assertTrue(p.isUsingItem(), Component.literal("the wished bow cannot be DRAWN — the property took its use away"));
                p.getMainHandItem().releaseUsing(helper.getLevel(), p, p.getMainHandItem().getUseDuration(p) - 30);
                p.stopUsingItem();
            }
            helper.runAfterDelay(2, () -> {
                try {
                    var shots = helper.getLevel().getEntitiesOfClass(expected, around,
                            e -> e.getOwner() == p);
                    // HIS arrows only. The 1.21.8 runner never clears a finished plot (its grid
                    // spawner is built with clearOnBatch false) and lays each batch's plot beside the
                    // last, in hash order — so an arrow another test left lying in a plot within 24
                    // blocks was counted as one his bow loosed. The swap bug this guards would loose
                    // an arrow OWNED BY HIM, which this still catches.
                    var arrows = helper.getLevel().getEntitiesOfClass(AbstractArrow.class, around,
                            e -> e.getOwner() == p);
                    var stray = helper.getLevel().getEntitiesOfClass(AbstractArrow.class, around,
                            e -> e.getOwner() != p);
                    if (shots.isEmpty()) {
                        throw GameTestCompat.assertion(base + " with projectile=" + word + ": nothing of "
                                + expected.getSimpleName() + " owned by him flew; his arrows in the air: " + arrows.size()
                                + ", other arrows near: " + stray.size());
                    }
                    if (!arrows.isEmpty()) {
                        StringBuilder which = new StringBuilder();
                        for (AbstractArrow a : arrows) {
                            which.append(String.format(" [%s owner=%s age=%d at %+.1f,%+.1f,%+.1f moving %s]",
                                    a.getType(), a.getOwner() == p ? "him" : a.getOwner(), a.tickCount,
                                    a.getX() - feet.getX(), a.getY() - feet.getY(), a.getZ() - feet.getZ(),
                                    a.getDeltaMovement()));
                        }
                        throw GameTestCompat.assertion(base + " with projectile=" + word + ": an ARROW flew as "
                                + "well — the swap must replace the arrow, not add to it:" + which);
                    }
                    Projectile shot = shots.get(0);
                    // South (yaw 0) at a full draw: it is moving fast and away from him, not dropped.
                    if (!(shot.getDeltaMovement().z > 0.5)) {
                        throw GameTestCompat.assertion(word + " is not going where the arrow was going: motion "
                                + shot.getDeltaMovement());
                    }
                    helper.succeed();
                } finally {
                    MockPlayerNetwork.remove(p);
                }
            });
        } catch (RuntimeException e) {
            MockPlayerNetwork.remove(p);
            throw e;
        }
    }

    @GameTest(template = "empty_test", batch = "proprides_fireball_bow", timeoutTicks = 400)
    public static void aBowThatShootsFireballsLoosesAFireball(GameTestHelper helper) {
        loosesInsteadOfAnArrow(helper, "minecraft:bow", "fireball",
                net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball.class);
    }

    @GameTest(template = "empty_test", batch = "proprides_egg_bow", timeoutTicks = 400)
    public static void aBowThatShootsEggsLoosesAnEgg(GameTestHelper helper) {
        loosesInsteadOfAnArrow(helper, "minecraft:bow", "egg",
                net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg.class);
    }

    @GameTest(template = "empty_test", batch = "proprides_snow_crossbow", timeoutTicks = 400)
    public static void aCrossbowThatShootsSnowballsLoosesASnowball(GameTestHelper helper) {
        loosesInsteadOfAnArrow(helper, "minecraft:crossbow", "snowball",
                net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball.class);
    }

    /** The control: a plain bow still shoots an arrow (the hook must not touch what a wish did not make). */
    @GameTest(template = "empty_test", batch = "proprides_plain_bow", timeoutTicks = 400)
    public static void aPlainBowStillShootsAnArrow(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = player(helper, feet, 0.0F);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        p.getInventory().setItem(1, new ItemStack(Items.ARROW, 4));
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        p.getMainHandItem().releaseUsing(helper.getLevel(), p, p.getMainHandItem().getUseDuration(p) - 30);
        p.stopUsingItem();
        helper.runAfterDelay(2, () -> {
            try {
                // his own arrow: a stray left in a neighbouring plot must not pass for it
                helper.assertTrue(!helper.getLevel().getEntitiesOfClass(AbstractArrow.class,
                        new AABB(feet).inflate(24), e -> e.getOwner() == p).isEmpty(),
                        Component.literal("a plain bow shot no arrow"));
                helper.succeed();
            } finally {
                MockPlayerNetwork.remove(p);
            }
        });
    }

    /** A projectile on something that is not a launcher is refused, not silently ignored. */
    @GameTest(template = "empty_test", batch = "proprides_refusals", timeoutTicks = 100)
    public static void propertiesOnTheWrongBaseAreRefused(GameTestHelper helper) {
        for (String[] c : new String[][]{
                {"minecraft:diamond_sword", "{\"projectile\":\"fireball\"}"},
                {"minecraft:bow", "{\"projectile\":\"ghast_fireball\"}"},
                {"minecraft:diamond", "{\"never_empty\":1}"}}) {
            JsonObject o = new JsonObject();
            o.addProperty("name", "Wrong " + c[0]);
            o.addProperty("icon", "wand");
            o.addProperty("colour", "#FF8020");
            o.addProperty("shape", "plain");
            o.addProperty("count", 1);
            o.addProperty("base_item", c[0]);
            o.add("properties", json(c[1]));
            ToolResult r = new MakeItemTool().call(o);
            helper.assertTrue(r.isError(), Component.literal("make_item accepted " + c[1] + " on " + c[0] + ": " + r.text()));
            // A refused design must not be REMEMBERED: it was, once (put before the property
            // checks), and the next "make my diamond sword unbreakable" inherited the projectile
            // and was refused for ever (InventoryCountGameTest, the --tier run).
            helper.assertTrue(ItemSpecStore.find("Wrong " + c[0]) == null,
                    Component.literal("a refused make_item left its design in the store: " + c[0] + " " + c[1]));
        }
        helper.succeed();
    }

    // ── ─────────────────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "proprides_bucket", timeoutTicks = 400)
    public static void aBucketThatNeverRunsOutPoursThreeTimesAndStaysFull(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(2, 2, 2));
        // A stone floor to pour onto, and room: he looks straight down at the block in front.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.getLevel().setBlockAndUpdate(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
        ServerPlayer p = player(helper, feet, 90.0F);
        ItemStack bucket;
        try {
            bucket = made(helper, p, "Endless Bucket " + helper.getLevel().getGameTime(),
                    "minecraft:water_bucket", "{\"never_empty\":1}");
        } catch (RuntimeException e) {
            MockPlayerNetwork.remove(p);
            throw e;
        }
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, bucket);
        pour(helper, p, feet, 1);
    }

    /** One pour, then check the water and the bucket on the next tick; three in all. */
    private static void pour(GameTestHelper helper, ServerPlayer p, BlockPos feet, int n) {
        helper.getLevel().setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 90.0F);
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.runAfterDelay(1, () -> {
            try {
                ItemStack held = p.getMainHandItem();
                if (!helper.getLevel().getBlockState(feet).is(Blocks.WATER)) {
                    throw GameTestCompat.assertion("pour " + n + ": no water where a bucket pours (at his feet, "
                            + "looking down) — the bucket's own use did not happen; held " + held);
                }
                if (!held.is(Items.WATER_BUCKET) || !WishItem.neverEmpty(held)) {
                    throw GameTestCompat.assertion("pour " + n + ": he holds " + held + " — the wished bucket "
                            + "did not stay full");
                }
                if (n < 3) {
                    pour(helper, p, feet, n + 1);
                    return;
                }
                MockPlayerNetwork.remove(p);
                helper.succeed();
            } catch (RuntimeException e) {
                MockPlayerNetwork.remove(p);
                throw e;
            }
        });
    }

    /** The control: a plain water bucket still empties (the hook must not touch what a wish did not make). */
    @GameTest(template = "empty_test", batch = "proprides_plain_bucket", timeoutTicks = 200)
    public static void aPlainBucketStillEmpties(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.getLevel().setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
        ServerPlayer p = player(helper, feet, 90.0F);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.runAfterDelay(1, () -> {
            try {
                helper.assertTrue(p.getMainHandItem().is(Items.BUCKET),
                        Component.literal("a plain water bucket did not empty: " + p.getMainHandItem()));
                helper.succeed();
            } finally {
                MockPlayerNetwork.remove(p);
            }
        });
    }
}
