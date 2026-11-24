package com.moddingfromamod.item;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: {@code on_block} — a wished shield still BLOCKS, and the wish rides the block.</b>
 *
 * <p>A {@code held} wish on a shield cancelled raising it: three of group A's wishes were shields
 * that could not block. This shape waits for a real block — the hit he did not take — and runs the
 * wish then. The same moment carries "a shield that bounces arrows back" ({@code reflect}): the arrow
 * he blocked goes back where it came from, his now, instead of dropping at his feet.
 *
 * <p>The shield is raised through the real {@code useItem}. A GameTest's mock player is never
 * ticked, so the five-tick raise is stepped by setting the remaining use time vanilla counts down;
 * the hit is a real zombie's attack and a real arrow, and vanilla decides whether it was blocked.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class OnBlockShapeGameTest {

    private static ItemStack made(GameTestHelper helper, ServerPlayer p, String name, String effect,
                                  JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "shield");
        o.addProperty("colour", "#3060FF");
        o.addProperty("shape", "on_block");
        o.addProperty("effect", effect);
        o.addProperty("count", 1);
        o.addProperty("base_item", "minecraft:shield");
        o.add("params", params);
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        if (r.isError()) {
            helper.fail(Component.literal("make_item refused on_block (" + effect + "): " + r.text()));
            return ItemStack.EMPTY;
        }
        String line = MakeItemTool.gives("on_block", tool.lastMadeId(), ItemCatalogue.icon("shield"), 1,
                "A hint.", java.util.List.of(new MakeItemTool.Variant(name, 0x3060FF)),
                com.moddingfromamod.datapack.ItemRecipient.ME, null, "minecraft:shield");
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(), line.strip().substring(1));
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                return s.copy();
            }
        }
        helper.fail(Component.literal("nothing arrived from: " + line));
        return ItemStack.EMPTY;
    }

    /** He raises the shield through the real use, and it comes all the way up (the 5-tick raise). */
    private static void raise(GameTestHelper helper, ServerPlayer p, ItemStack shield) {
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, shield);
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        helper.assertTrue(p.isUsingItem(), Component.literal("the wished shield cannot be RAISED"));
        try {
            java.lang.reflect.Field f = LivingEntity.class.getDeclaredField("useItemRemaining");
            f.setAccessible(true);
            f.setInt(p, p.getUseItem().getUseDuration(p) - 20);
        } catch (ReflectiveOperationException e) {
            throw GameTestCompat.assertion("cannot step the raise: " + e);
        }
        helper.assertTrue(p.isBlocking(), Component.literal("the raised shield is not blocking"));
    }

    /**
     * Hurtable NOW. A freshly joined player is invulnerable for his first seconds, and a hurt he is
     * invulnerable to returns before the shield is ever asked — so "his health did not drop" would
     * read as a block that never happened. The first version of this test fell into exactly that.
     */
    private static void hurtable(ServerPlayer p) {
        p.setInvulnerableTime(0);
        // The two targets protect a new player differently: 1.21.4 until his client says it has
        // LOADED (a mock never does), 1.21.1 for a spawn timer. Whichever this Minecraft has.
        // 1.21.11 moved "his client has loaded" to the connection (ServerGamePacketListenerImpl.markClientLoaded)
        boolean done = false;
        if (p.connection != null) {
            p.connection.markClientLoaded();
            done = true;
        }
        try {
            java.lang.reflect.Field f = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            f.setAccessible(true);
            f.setInt(p, 0);
            done = true;
        } catch (ReflectiveOperationException ignored) {
            // not 1.21.1
        }
        if (!done) {
            throw GameTestCompat.assertion("cannot end a new player's protection on this Minecraft");
        }
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos feet) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.setGameMode(GameType.SURVIVAL);
        hurtable(p);
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);   // facing south
        p.setYHeadRot(0.0F);
        return p;
    }

    private static boolean glowstoneNear(GameTestHelper helper, BlockPos centre, int r) {
        for (BlockPos q : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
            if (helper.getLevel().getBlockState(q).is(Blocks.GLOWSTONE)) {
                return true;
            }
        }
        return false;
    }

    @GameTest(template = "empty_test", batch = "onblock_hit", timeoutTicks = 900)
    public static void aWishedShieldBlocksAndTheWishRidesTheBlock(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = player(helper, feet);
        JsonObject params = new JsonObject();
        params.addProperty("BLOCK", "minecraft:glowstone");
        ItemStack shield = made(helper, p, "Glow Shield " + helper.getLevel().getGameTime(), "place", params);
        if (shield.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        raise(helper, p, shield);
        // Where a shield's wish lands: 1.5 in front of his eyes, where what he blocked
        // stands — not a wand's 5 (MakeItemTool.BLOCK_REACH). Radius 2 round z+2 does not reach z+5.
        BlockPos ahead = feet.offset(0, 1, 2);
        helper.assertFalse(glowstoneNear(helper, ahead, 2), Component.literal("the wish happened on the RAISE, before any block"));
        Zombie z = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 2, 3));
        z.setNoAi(true);
        z.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 1.8, 180.0F, 0.0F);
        // THE CONTROL: the same hit with the shield down must hurt, or "not hurt" below proves nothing.
        ItemStack held = p.getMainHandItem().copy();
        p.stopUsingItem();
        hurtable(p);
        float full = p.getHealth();
        com.moddingfromamod.compat.Damage.hurt(p, p.damageSources().mobAttack(z), 4.0F);
        helper.assertTrue(p.getHealth() < full, Component.literal("the control failed: an unshielded hit did not hurt him"));
        helper.assertFalse(glowstoneNear(helper, ahead, 2), Component.literal("the wish happened on a hit he did NOT block"));
        p.setHealth(p.getMaxHealth());
        raise(helper, p, held);
        hurtable(p);
        float before = p.getHealth();
        com.moddingfromamod.compat.Damage.hurt(p, p.damageSources().mobAttack(z), 4.0F);
        helper.assertTrue(p.getHealth() == before,
                Component.literal("the zombie's hit was not blocked: health " + before + " -> " + p.getHealth()));
        helper.succeedWhen(() -> {
            if (!glowstoneNear(helper, ahead, 2)) {
                throw GameTestCompat.assertion("the shield blocked and the wish never came");
            }
            z.discard();
            MockPlayerNetwork.remove(p);
        });
    }

    /**
     * <b>What he blocked is what the wish reaches.</b> A shield that "makes mobs bounce away"
     * (on_block + launch) blocked the zombie's hit in the Opus pass and the zombie never moved: an
     * effect aimed AHEAD ran five blocks in front of his eyes, as a wand's does, and the zombie he
     * blocked stands one or two blocks away &mdash; outside the reach of that point. The mob keeps its
     * physics here ({@code removeFreeWill}, not NoAI, which knockback cannot move).
     */
    @GameTest(template = "empty_test", batch = "onblock_launch", timeoutTicks = 900)
    public static void aShieldThatKnocksMobsAwayLaunchesTheMobItBlocked(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = player(helper, feet);
        JsonObject params = new JsonObject();
        params.addProperty("POWER", "launch");
        ItemStack shield = made(helper, p, "Bouncy Shield " + helper.getLevel().getGameTime(), "launch", params);
        if (shield.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        raise(helper, p, shield);
        Zombie z = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 2, 3));
        z.removeFreeWill();
        z.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 1.8, 180.0F, 0.0F);
        net.minecraft.world.phys.Vec3 from = z.position();
        hurtable(p);
        float before = p.getHealth();
        com.moddingfromamod.compat.Damage.hurt(p, p.damageSources().mobAttack(z), 4.0F);
        helper.assertTrue(p.getHealth() == before,
                Component.literal("the zombie's hit was not blocked: health " + before + " -> " + p.getHealth()));
        helper.succeedWhen(() -> {
            double moved = z.position().distanceTo(from);
            if (moved < 1.5) {
                throw GameTestCompat.assertion("the shield blocked the zombie and the wish never reached it: "
                        + "it moved " + String.format("%.2f", moved) + " blocks");
            }
            z.discard();
            MockPlayerNetwork.remove(p);
        });
    }

    @GameTest(template = "empty_test", batch = "onblock_reflect", timeoutTicks = 900)
    public static void aShieldThatBouncesArrowsBackSendsTheBlockedArrowBack(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = player(helper, feet);
        ItemStack shield = made(helper, p, "Bounce Shield " + helper.getLevel().getGameTime(), "reflect",
                new JsonObject());
        if (shield.isEmpty()) {
            MockPlayerNetwork.remove(p);
            return;
        }
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        raise(helper, p, shield);
        hurtable(p);
        Zombie archer = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1, 2, 7));
        archer.setNoAi(true);
        archer.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 6.5, 180.0F, 0.0F);
        Arrow arrow = new Arrow(EntityTypes.ARROW, helper.getLevel());
        arrow.setOwner(archer);
        arrow.setPos(feet.getX() + 0.5, feet.getY() + 1.4, feet.getZ() + 4.5);
        arrow.setDeltaMovement(new Vec3(0, 0, -1.6));   // north, at him
        String fn = WishItem.blockFunction(p.getUseItem());
        var server = helper.getLevel().getServer();
        String atStart = "fn " + fn + " loaded=" + server.getFunctions().get(
                net.minecraft.resources.Identifier.parse(fn)).isPresent()
                + " reloading=" + com.moddingfromamod.datapack.DatapackReloader.reloadInProgress()
                + " deferred=" + com.moddingfromamod.datapack.DatapackReloader.reloadDeferred();
        helper.getLevel().addFreshEntity(arrow);
        float before = p.getHealth();
        StringBuilder trace = new StringBuilder();
        int[] seen = {0};
        helper.onEachTick(() -> {
            if (seen[0]++ < 8) {
                trace.append(String.format(" [t%d arrow z%+.2f vz%+.2f owner=%s | him z%+.2f blocking=%s using=%s mode=%s inv=%s tag=%s hp=%.1f]",
                        seen[0], arrow.getZ() - feet.getZ(), arrow.getDeltaMovement().z,
                        arrow.getOwner() == p ? "him" : String.valueOf(arrow.getOwner() == null ? null : arrow.getOwner().getType()),
                        p.getZ() - feet.getZ(), p.isBlocking(), p.isUsingItem(), p.gameMode.getGameModeForPlayer(),
                        p.getAbilities().invulnerable, p.entityTags().contains(ReflectProjectiles.TAG), p.getHealth())
                        + " arrow in " + helper.getLevel().getBlockState(arrow.blockPosition()).getBlock()
                        + "/" + helper.getLevel().getFluidState(arrow.blockPosition()).getType());
                if (seen[0] == 1) {   // what stands in the arrow's path, z feet .. feet+5
                    for (int dz = 0; dz <= 5; dz++) {
                        BlockPos q = BlockPos.containing(feet.getX() + 0.5, feet.getY() + 1.4, feet.getZ() + dz);
                        trace.append(" path+").append(dz).append('=')
                                .append(helper.getLevel().getBlockState(q).getBlock().getDescriptionId());
                    }
                    trace.append(" entities near: ").append(helper.getLevel().getEntities((net.minecraft.world.entity.Entity) null,
                            new AABB(feet).inflate(8), e -> e != p && e != arrow && e != archer).stream()
                            .map(e -> e.getType() + "@" + e.blockPosition().subtract(feet)).toList());
                }
            }
        });
        helper.succeedWhen(() -> {
            if (p.getHealth() < before) {
                throw GameTestCompat.assertion("the arrow was not blocked: health " + before + " -> "
                        + p.getHealth() + "; " + atStart + trace);
            }
            if (arrow.getOwner() != p || arrow.getDeltaMovement().z < 0.8) {
                throw GameTestCompat.assertion("the blocked arrow did not go back: owner "
                        + arrow.getOwner() + ", moving " + arrow.getDeltaMovement() + ", removed "
                        + arrow.isRemoved() + "; " + atStart + "; fn loaded now="
                        + server.getFunctions().get(net.minecraft.resources.Identifier.parse(fn)).isPresent()
                        + trace);
            }
            archer.discard();
            arrow.discard();
            MockPlayerNetwork.remove(p);
        });
    }

    /** make_item moves a click-taking shape on a shield onto the block, and says so. */
    @GameTest(template = "empty_test", batch = "onblock_redirect", timeoutTicks = 900)
    public static void aHeldShieldIsBuiltToBlock(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", "Ride Shield " + helper.getLevel().getGameTime());
        o.addProperty("icon", "shield");
        o.addProperty("colour", "#3060FF");
        o.addProperty("shape", "held");
        o.addProperty("effect", "light");
        o.addProperty("count", 1);
        o.addProperty("base_item", "minecraft:shield");
        JsonObject params = new JsonObject();
        params.addProperty("GLOW_SECONDS", "0");
        o.add("params", params);
        ToolResult r = new MakeItemTool().call(o);
        helper.assertTrue(!r.isError() && r.text().contains("Built as `on_block`, not `held`"),
                Component.literal("a held shield must be built to BLOCK, and said so: " + r.text()));
        helper.succeed();
    }
}
