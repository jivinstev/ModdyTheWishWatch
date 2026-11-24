package com.moddingfromamod.item;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>: the behaviour matrix — every (real base, shape) an item wish in the corpus uses,
 * made through the real {@code make_item}, handed over by the real give, and USED the real way.</b>
 *
 * <p>The pairs are generated, never typed: {@code items/trigger-matrix-pairs.json}, written by
 * {@code tools/item-trigger-audit.py --pairs --write} from {@code parts-needed.jsonl} after each row's
 * redirect, and {@code --check} fails when it is stale. So a wish added to the corpus on a new base
 * joins the matrix, and a pair nothing here can use fails by name rather than being skipped.
 *
 * <p>For every pair, BOTH halves are asserted: the vanilla action happened (the bow drew and shot,
 * the cookie was eaten, the shield blocked, the snowball left his hand once, the block broke, the
 * helmet is on his head) AND the wish happened (a glowstone where the shape says it lands). One test
 * per trigger family, each in its own batch, each walking its pairs one after another.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class TriggerCorpusMatrixGameTest {

    record Pair(String base, String shape, List<String> wishes) {
    }

    static List<Pair> pairs(String shape) {
        try (InputStream in = TriggerCorpusMatrixGameTest.class.getResourceAsStream("/items/trigger-matrix-pairs.json")) {
            if (in == null) {
                throw GameTestCompat.assertion("items/trigger-matrix-pairs.json is not on the classpath");
            }
            List<Pair> out = new ArrayList<>();
            for (var e : JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("pairs")) {
                var o = e.getAsJsonObject();
                if (o.get("shape").getAsString().equals(shape)) {
                    List<String> ws = new ArrayList<>();
                    o.getAsJsonArray("wishes").forEach(w -> ws.add(w.getAsString()));
                    out.add(new Pair(o.get("base").getAsString(), shape, ws));
                }
            }
            return out;
        } catch (IOException e) {
            throw GameTestCompat.assertion("cannot read the matrix pairs: " + e);
        }
    }

    /** Every family the pairs name has a test here; a new one fails until it does. */
    static final List<String> FAMILIES = List.of("held", "arrow", "on_consume", "on_use_done", "on_throw_land",
            "on_block", "on_hit", "on_mine", "worn");

    // ── shared steps ─────────────────────────────────────────────────────────────────────────────

    private static BlockPos feet(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(1, 2, 1));
    }

    private static ServerPlayer player(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        // TRULY survival: every other mock answers isCreative() true whatever its mode, and a mace
        // will not break a block for a creative player — the first run of this matrix read that as
        // "the mace cannot mine" (asSurvival is the answer).
        return MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper));
    }

    private static void face(ServerPlayer p, BlockPos feet) {
        p.stopUsingItem();
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        p.setYHeadRot(0.0F);
    }

    /** make_item + the real give, one stack in his main hand; the shape as make_item BUILDS it. */
    private static ItemStack make(GameTestHelper helper, ServerPlayer p, Pair pair, String shape, int count) {
        String name = "Matrix " + pair.base().substring(10) + " " + helper.getLevel().getGameTime();
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FFAA00");
        o.addProperty("shape", shape);
        o.addProperty("effect", "place");
        o.addProperty("count", count);
        o.addProperty("base_item", pair.base());
        JsonObject params = new JsonObject();
        params.addProperty("BLOCK", "minecraft:glowstone");
        o.add("params", params);
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        if (r.isError()) {
            throw GameTestCompat.assertion(pair + ": make_item refused it: " + r.text());
        }
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(),
                "wishdatapack " + tool.lastMadeId() + " give");
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                ItemStack held = s.copy();
                p.getInventory().clearContent();
                p.setItemInHand(InteractionHand.MAIN_HAND, held);
                helper.assertTrue(BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(pair.base()),
                        Component.literal(pair + ": he was handed " + held + ", not the real " + pair.base()));
                return held;
            }
        }
        throw GameTestCompat.assertion(pair + ": nothing arrived from the give");
    }

    private static boolean glowstone(GameTestHelper helper, BlockPos centre, int r) {
        for (BlockPos q : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
            if (helper.getLevel().getBlockState(q).is(Blocks.GLOWSTONE)) {
                return true;
            }
        }
        return false;
    }

    private static void clear(GameTestHelper helper, BlockPos feet) {
        for (BlockPos q : BlockPos.betweenClosed(feet.offset(-4, -1, -3), feet.offset(4, 5, 8))) {
            if (helper.getLevel().getBlockState(q).is(Blocks.GLOWSTONE)) {
                helper.getLevel().setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void wall(GameTestHelper helper, BlockPos feet, int distance) {
        BlockPos w = feet.offset(0, 1, distance);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                helper.getLevel().setBlockAndUpdate(w.offset(dx, dy, 0), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static void use(ServerPlayer p) {
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    private static void invoke(LivingEntity who, String method) {
        try {
            java.lang.reflect.Method m = LivingEntity.class.getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(who);
        } catch (ReflectiveOperationException e) {
            throw GameTestCompat.assertion("cannot call " + method + ": " + e);
        }
    }

    /**
     * Walk the family's pairs one after another: set up and use, then wait for both halves.
     *
     * @param use   uses the pair's item the real way and asserts what is immediate; returns the
     *              check that must eventually pass (the wish, and anything vanilla does later)
     */
    private static void walk(GameTestHelper helper, String family, java.util.function.BiFunction<ServerPlayer, Pair, Runnable> use) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        List<Pair> ps = pairs(family);
        ServerPlayer p = player(helper);
        GameTestSequence seq = helper.startSequence();
        Runnable[] check = new Runnable[1];
        for (Pair pair : ps) {
            seq = seq.thenExecute(() -> {
                check[0] = null;
                clear(helper, feet(helper));
                face(p, feet(helper));
                try {
                    check[0] = use.apply(p, pair);
                } catch (GameTestAssertException e) {
                    check[0] = () -> {
                        throw e;
                    };
                } catch (RuntimeException e) {
                    // Anything else would escape the sequence and take the whole server down (it
                    // did, the first time this matrix was made to fail): report it as this test's.
                    check[0] = () -> {
                        throw GameTestCompat.assertion(pair + ": " + e);
                    };
                }
            }).thenWaitUntil(() -> {
                if (check[0] == null) {
                    throw GameTestCompat.assertion(pair + ": the use step never finished");
                }
                check[0].run();
            });
        }
        seq.thenExecute(() -> MockPlayerNetwork.remove(p)).thenSucceed();
    }

    // ── the families ─────────────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "matrix_held", timeoutTicks = 2400)
    public static void heldPairs(GameTestHelper helper) {
        walk(helper, "held", (p, pair) -> {
            make(helper, p, pair, "held", 1);
            use(p);
            // Nothing of its own happens on a click in the air for these bases; the stack is
            // still there, and the wish is where he points.
            helper.assertFalse(p.getMainHandItem().isEmpty(), Component.literal(pair + ": the item was used up by a click"));
            BlockPos ahead = feet(helper).offset(0, 1, 5);
            return () -> {
                if (!glowstone(helper, ahead, 2)) {
                    throw GameTestCompat.assertion(pair + ": clicked, and the wish never came");
                }
            };
        });
    }

    @GameTest(template = "empty_test", batch = "matrix_arrow", timeoutTicks = 2400)
    public static void arrowPairs(GameTestHelper helper) {
        wall(helper, feet(helper), 4);
        walk(helper, "arrow", (p, pair) -> {
            make(helper, p, pair, "arrow", 1);
            p.getInventory().setItem(1, new ItemStack(Items.ARROW, 8));
            use(p);
            helper.assertTrue(p.isUsingItem(), Component.literal(pair + ": it cannot be drawn"));
            AABB around = new AABB(feet(helper)).inflate(12);
            int before = helper.getLevel().getEntitiesOfClass(Projectile.class, around).size();
            p.getMainHandItem().releaseUsing(helper.getLevel(), p, p.getMainHandItem().getUseDuration(p) - 30);
            p.stopUsingItem();
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(Projectile.class, around).size() > before,
                    Component.literal(pair + ": drawn and released, and nothing was shot"));
            BlockPos at = feet(helper).offset(0, 1, 3);
            return () -> {
                if (!glowstone(helper, at, 1)) {
                    throw GameTestCompat.assertion(pair + ": shot, and no wish where it landed");
                }
            };
        });
    }

    @GameTest(template = "empty_test", batch = "matrix_consume", timeoutTicks = 2400)
    public static void consumePairs(GameTestHelper helper) {
        walk(helper, "on_consume", (p, pair) -> {
            p.getFoodData().setFoodLevel(2);
            ItemStack s = make(helper, p, pair, "on_consume", 4);
            int count = s.getCount();
            use(p);
            helper.assertTrue(p.isUsingItem(), Component.literal(pair + ": it cannot be eaten or drunk"));
            BlockPos ahead = feet(helper).offset(0, 1, 5);
            helper.assertFalse(glowstone(helper, ahead, 2), Component.literal(pair + ": the wish came on the click"));
            invoke(p, "completeUsingItem");
            ItemStack left = p.getMainHandItem();
            helper.assertTrue(left.is(Items.GLASS_BOTTLE) || left.getCount() < count || left.isEmpty(),
                    Component.literal(pair + ": finished, and nothing was eaten or drunk: " + left));
            return () -> {
                if (!glowstone(helper, ahead, 2)) {
                    throw GameTestCompat.assertion(pair + ": eaten or drunk, and the wish never came");
                }
            };
        });
    }

    @GameTest(template = "empty_test", batch = "matrix_usedone", timeoutTicks = 2400)
    public static void useDonePairs(GameTestHelper helper) {
        // A brush brushes what his EYES are on (vanilla raycasts from the view), so a block at eye
        // height two ahead; the wish lands past it, where he points.
        BlockPos floor = feet(helper).offset(0, 1, 2);
        walk(helper, "on_use_done", (p, pair) -> {
            helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            make(helper, p, pair, "on_use_done", 1);
            if (pair.base().equals("minecraft:goat_horn")) {
                helper.getLevel().getServer().getCommands().performPrefixedCommand(
                        p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(),
                        "item modify entity @s weapon.mainhand {function:\"minecraft:set_instrument\","
                                + "options:\"#minecraft:goat_horns\"}");
                use(p);
            } else {
                // A brush is used ON a block.
                p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0, -0.5),
                                net.minecraft.core.Direction.NORTH, floor, false));
            }
            helper.assertTrue(p.isUsingItem(), Component.literal(pair + ": it cannot be used"));
            BlockPos ahead = feet(helper).offset(0, 1, 5);
            p.releaseUsingItem();
            return () -> {
                if (!glowstone(helper, ahead, 3) && !glowstone(helper, floor, 2)) {
                    throw GameTestCompat.assertion(pair + ": used and let go, and the wish never came");
                }
            };
        });
    }

    @GameTest(template = "empty_test", batch = "matrix_throw", timeoutTicks = 2400)
    public static void throwPairs(GameTestHelper helper) {
        wall(helper, feet(helper), 5);
        walk(helper, "on_throw_land", (p, pair) -> {
            make(helper, p, pair, "on_throw_land", 4);
            AABB around = new AABB(feet(helper)).inflate(12);
            int before = helper.getLevel().getEntitiesOfClass(Projectile.class, around).size();
            use(p);
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(Projectile.class, around).size() == before + 1,
                    Component.literal(pair + ": it was not thrown once, as itself"));
            helper.assertTrue(p.getMainHandItem().getCount() == 3, Component.literal(pair + ": not one fewer in his hand"));
            BlockPos at = feet(helper).offset(0, 1, 4);
            return () -> {
                if (!glowstone(helper, at, 1)) {
                    throw GameTestCompat.assertion(pair + ": thrown, and no wish where it landed");
                }
            };
        });
    }

    @GameTest(template = "empty_test", batch = "matrix_block", timeoutTicks = 2400)
    public static void blockPairs(GameTestHelper helper) {
        walk(helper, "on_block", (p, pair) -> {
            ItemStack shield = make(helper, p, pair, "on_block", 1);
            use(p);
            helper.assertTrue(p.isUsingItem(), Component.literal(pair + ": it cannot be raised"));
            try {
                var f = LivingEntity.class.getDeclaredField("useItemRemaining");
                f.setAccessible(true);
                f.setInt(p, p.getUseItem().getUseDuration(p) - 20);
                p.setInvulnerableTime(0);
                p.connection.markClientLoaded();   // 1.21.11: the client-loaded flag is the connection's
            } catch (ReflectiveOperationException e) {
                throw GameTestCompat.assertion("cannot step the raise: " + e);
            }
            helper.assertTrue(p.isBlocking(), Component.literal(pair + ": raised, and not blocking"));
            Zombie z = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 2, 3));
            z.setNoAi(true);
            z.snapTo(p.getX(), p.getY(), p.getZ() + 1.8, 180.0F, 0.0F);
            float before = p.getHealth();
            com.moddingfromamod.compat.Damage.hurt(p, p.damageSources().mobAttack(z), 4.0F);
            helper.assertTrue(p.getHealth() == before, Component.literal(pair + ": the hit was not blocked"));
            // Where a shield's wish lands: 1.5 in front of his eyes, where what he blocked
            // stands — not a wand's 5 (MakeItemTool.BLOCK_REACH). Radius 2 round z+2 does not reach z+5.
            BlockPos ahead = feet(helper).offset(0, 1, 2);
            return () -> {
                if (!glowstone(helper, ahead, 2)) {
                    throw GameTestCompat.assertion(pair + ": blocked, and the wish never came");
                }
                z.discard();
            };
        });
    }

    @GameTest(template = "empty_test", batch = "matrix_hit", timeoutTicks = 2400)
    public static void hitPairs(GameTestHelper helper) {
        walk(helper, "on_hit", (p, pair) -> {
            make(helper, p, pair, "on_hit", 1);
            Zombie z = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 2, 3));
            z.setNoAi(true);
            z.snapTo(p.getX(), p.getY(), p.getZ() + 1.5, 180.0F, 0.0F);
            float before = z.getHealth();
            p.attack(z);
            helper.assertTrue(z.getHealth() < before || z.isRemoved(), Component.literal(pair + ": swung, and the zombie was not hit"));
            BlockPos at = BlockPos.containing(z.position());
            return () -> {
                if (!glowstone(helper, at, 3)) {
                    throw GameTestCompat.assertion(pair + ": hit, and the wish never came");
                }
                z.discard();
            };
        });
    }

    @GameTest(template = "empty_test", batch = "matrix_mine", timeoutTicks = 2400)
    public static void minePairs(GameTestHelper helper) {
        walk(helper, "on_mine", (p, pair) -> {
            make(helper, p, pair, "on_mine", 1);
            BlockPos rock = feet(helper).offset(0, 0, 2);
            Block kind = pair.base().contains("shovel") ? Blocks.DIRT : Blocks.STONE;
            helper.getLevel().setBlockAndUpdate(rock, kind.defaultBlockState());
            p.snapTo(rock.getX() + 0.5, rock.getY() + 1, rock.getZ() - 1.5, 0.0F, 30.0F);
            var state = helper.getLevel().getBlockState(rock);
            boolean broke = p.gameMode.destroyBlock(rock);
            helper.assertTrue(broke, Component.literal(pair + ": it cannot break " + kind + " (attackBlock "
                    + p.getMainHandItem().getItem().canDestroyBlock(p.getMainHandItem(), state, helper.getLevel(), rock, p)
                    + ", mode " + p.gameMode.getGameModeForPlayer() + ", in hand " + p.getMainHandItem()
                    + ", now " + helper.getLevel().getBlockState(rock) + ")"));
            return () -> {
                if (!helper.getLevel().getBlockState(rock).is(Blocks.GLOWSTONE) && !glowstone(helper, rock, 1)) {
                    throw GameTestCompat.assertion(pair + ": mined, and the wish never came");
                }
            };
        });
    }

    @GameTest(template = "empty_test", batch = "matrix_worn", timeoutTicks = 2400)
    public static void wornPairs(GameTestHelper helper) {
        if (!com.moddingfromamod.compat.Wearables.SUPPORTED) {
            // 1.21.1 has no worn shape; make_item refuses it there by name.
            helper.succeed();
            return;
        }
        walk(helper, "worn", (p, pair) -> {
            EquipmentSlot slot = pair.base().endsWith("_boots") ? EquipmentSlot.FEET
                    : pair.base().endsWith("_helmet") ? EquipmentSlot.HEAD
                    : pair.base().endsWith("_leggings") ? EquipmentSlot.LEGS : EquipmentSlot.CHEST;
            String shape = "worn_" + slot.getName();
            ItemStack s = make(helper, p, pair, shape, 1);
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            p.setItemSlot(slot, s);
            helper.assertTrue(p.getItemBySlot(slot).is(s.getItem()), Component.literal(pair + ": it is not on him"));
            return () -> {
                if (!glowstone(helper, p.blockPosition(), 3)) {
                    throw GameTestCompat.assertion(pair + ": worn, and the wish never came");
                }
                p.setItemSlot(slot, ItemStack.EMPTY);
            };
        });
    }

    /** No family the corpus uses is left without a test here. */
    @GameTest(template = "empty_test", batch = "matrix_families", timeoutTicks = 200)
    public static void everyFamilyInTheCorpusHasATest(GameTestHelper helper) {
        for (String shape : List.of("held", "thrown", "placed", "machine", "on_hit", "arrow", "on_mine", "on_shot",
                "on_consume", "on_block", "on_throw_land", "on_use_done", "worn")) {
            if (!pairs(shape).isEmpty()) {
                helper.assertTrue(FAMILIES.contains(shape), Component.literal("the corpus uses shape " + shape
                        + " and the matrix has no family for it"));
            }
        }
        helper.succeed();
    }
}
