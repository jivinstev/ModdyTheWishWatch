package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * <b>Gate B for the whole "a wish gives you a THING" class.</b>
 *
 * <p>Three shapes cover the two big unserved wish classes between them, and this drives each through
 * the real item rather than a stand-in:
 *
 * <ul>
 *   <li><b>a thing you put down</b> — drops the tagged, invisible, player-facing anchor a placeable
 *       datapack is built around;</li>
 *   <li><b>a thing that does something</b> — runs a datapack function, which is what removes the
 *       advancement-trigger machinery a datapack-only item needs;</li>
 *   <li><b>a thing you throw</b> — spawns a tagged projectile, the exact entity a CBScript throwable
 *       already knows how to watch.</li>
 * </ul>
 */
@GameTestHolder(GateShards.CI)
public class WishItemGameTest {

    /** A thing you put down: the anchor lands on the aimed cell, tagged and facing the player. */
    @GameTest(template = "empty_test")
    public static void aPlaceableWishDropsItsTaggedAnchor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floorRel = new BlockPos(2, 1, 2);
        helper.setBlock(floorRel, Blocks.STONE);
        BlockPos floor = helper.absolutePos(floorRel);

        ItemStack stack = WishItem.of(ModItems.WISH_ITEM.get(), 1, "wishthing_probe");
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 3.5);
        player.setYRot(180.0F);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        stack.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false)));

        List<ArmorStand> found = level.getEntities(EntityType.ARMOR_STAND,
                new AABB(floor.above()).inflate(1.5), e -> e.entityTags().contains("wishthing_probe"));
        helper.assertTrue(found.size() == 1,
                Component.literal("the wish's own tag must reach the anchor, or nothing can find what was placed"));
        helper.assertTrue(found.get(0).blockPosition().equals(floor.above()),
                Component.literal("the anchor must land on the aimed cell"));
        helper.assertTrue(found.get(0).isInvisible(),
                Component.literal("the anchor is scaffolding, not scenery — a visible armour stand inside the thing is a bug"));
        helper.succeed();
    }

    /**
     * A thing you throw: the projectile carries the wish's tag.
     *
     * <p>The tag is the whole contract — a CBScript throwable acts on
     * {@code @e[type=snowball, tag=...]}, so if the tag does not reach the entity the wish's effect
     * simply never happens, with nothing erroring anywhere.
     */
    @GameTest(template = "empty_test")
    public static void aThrowableWishTagsItsProjectile(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));

        ItemStack stack = WishItem.thrown(ModItems.WISH_ITEM.get(), 9, "wishthrow_probe", 0x00B4FF);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        stack.getItem().use(level, player, InteractionHand.MAIN_HAND);

        List<Snowball> balls = level.getEntities(EntityType.SNOWBALL,
                new AABB(at).inflate(6.0), e -> e.entityTags().contains("wishthrow_probe"));
        helper.assertTrue(balls.size() == 1,
                Component.literal("throwing must produce exactly one tagged projectile, produced " + balls.size()));
        helper.assertTrue(balls.get(0).getDeltaMovement().lengthSqr() > 0.5,
                Component.literal("it must actually be thrown, not dropped at the player's feet"));
        helper.assertTrue(stack.getCount() == 0, Component.literal("a thrown thing is used up in survival"));
        helper.succeed();
    }

    /**
     * The colour a wish asks for survives onto the stack.
     *
     * <p>Whether it RENDERS is a client question and belongs to {@code IconTintClientTest}; what a
     * server can prove is that the wish's chosen colour is really carried, which is the half that
     * would otherwise be lost silently between the agent and the icon.
     */
    @GameTest(template = "empty_test")
    public static void aWishsChosenColourAndIconRideOnTheStack(GameTestHelper helper) {
        ItemStack stack = WishItem.doing(ModItems.WISH_ITEM.get(), 3, "probe:effect", 0x00B4FF);
        var dyed = stack.get(DataComponents.DYED_COLOR);
        var cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        helper.assertTrue(dyed != null && dyed.rgb() == 0x00B4FF,
                Component.literal("the wish's colour must ride on the stack; the tint handler reads it from there"));
        helper.assertTrue(cmd != null && com.moddingfromamod.compat.ModelData.id(cmd) == 3,
                Component.literal("the icon number must ride on the stack, or every wish wears the default artwork"));
        helper.assertTrue(WishItem.onUse(stack).equals("probe:effect"),
                Component.literal("and the function it runs"));
        helper.succeed();
    }

    /**
     * <b>The two archetypal wishes, checked as a wish would produce them.</b>
     *
     * <p>These read the shipped {@code spike/*.cbscript} artifacts and put their {@code /give} through
     * the real 1.21.1 item parser — which is where a component that does not exist, or a malformed
     * one, stops being a typo and becomes a give that fails at runtime while the datapack loads
     * perfectly happily.
     *
     * <p>It also checks the thing no parser can: that the function the item names is a function the
     * artifact actually <b>defines</b>. A typo there is the purest silent failure in this whole
     * class — the item appears, the child clicks it, and nothing happens, with no error anywhere.
     */
    @GameTest(template = "empty_test")
    public static void theArchetypalWishesGiveAWorkingItem(GameTestHelper helper) {
        check(helper, "/spike/wand_wish.cbscript", "WishOnUse", true);
        check(helper, "/spike/star_wish.cbscript", "WishThrowTag", false);
        helper.succeed();
    }

    private static void check(GameTestHelper helper, String resource, String key, boolean namesFunction) {
        String src;
        try (var in = WishItemGameTest.class.getResourceAsStream(resource)) {
            helper.assertTrue(in != null, Component.literal(resource + " must ship"));
            // Judged in this build's dialect, which is what the compile hands the game
            // (DatapackForge -> wish.CommandDialect).
            src = com.moddingfromamod.wish.CommandDialect.correct(
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).command();
        } catch (Exception e) {
            helper.fail(Component.literal("could not read " + resource + ": " + e));
            return;
        }

        String give = src.lines().map(String::strip)
                .filter(l -> l.startsWith("/give @s moddingfromamod:wish_item"))
                .findFirst().orElse(null);
        helper.assertTrue(give != null, Component.literal(resource + " no longer hands out a wish_item"));

        String arg = give.substring("/give @s ".length());
        int lastSpace = arg.lastIndexOf(' ');
        arg = lastSpace < 0 ? arg : arg.substring(0, lastSpace);

        ItemStack stack;
        try {
            var parsed = new net.minecraft.commands.arguments.item.ItemParser(
                    helper.getLevel().registryAccess())
                    .parse(new com.mojang.brigadier.StringReader(arg));
            stack = new ItemStack(parsed.item(), 1);
            stack.applyComponents(parsed.components());
        } catch (Exception e) {
            helper.fail(Component.literal(resource + "'s give is not valid on this version: " + e));
            return;
        }

        helper.assertTrue(stack.getItem() == ModItems.WISH_ITEM.get(), Component.literal("it must give the wish item"));
        var cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        helper.assertTrue(cmd != null && WishIcons.all().stream().anyMatch(i -> i.id() == com.moddingfromamod.compat.ModelData.id(cmd)),
                Component.literal(resource + " asks for icon " + (cmd == null ? "none" : com.moddingfromamod.compat.ModelData.id(cmd))
                        + ", which is not in the shipped library — it would wear the default artwork"));
        helper.assertTrue(stack.get(DataComponents.DYED_COLOR) != null,
                Component.literal(resource + " sets no colour, so its icon stays grey"));
        helper.assertTrue(stack.getHoverName().getString().length() > 3, Component.literal("it must be named"));

        String value = key.equals("WishOnUse") ? WishItem.onUse(stack) : WishItem.throwTag(stack);
        helper.assertTrue(!value.isEmpty(), Component.literal(resource + " sets no " + key + ", so using it does nothing"));
        if (namesFunction) {
            String fn = value.substring(value.indexOf(':') + 1);
            helper.assertTrue(src.contains("function " + fn + "()"),
                    Component.literal(resource + " points at '" + value + "' but defines no such function — the child "
                            + "would click it and nothing would happen, with no error anywhere"));
        }
    }

    /**
     * A thrown thing carries its landing instruction with it.
     *
     * <p>Landing is the half of a throwable that a datapack cannot get cheaply — the only CBScript
     * route is trailing a marker entity every tick and firing when the projectile stops existing,
     * per wish, correctly, including the tidy-up. The mod does it once; what a headless server can
     * prove is that the instruction really rides on the projectile. That it FIRES at the impact
     * point belongs to Gate C, because it needs a loaded pack with a function to call.
     */
    @GameTest(template = "empty_test")
    public static void aThrownThingCarriesItsLandingInstruction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 8));

        ItemStack stack = WishItem.thrown(ModItems.WISH_ITEM.get(), 9, "boom_probe", 0xD64747);
        var data = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        data.putString(WishItem.HIT_FN, "probe:boom");
        stack.set(DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(data));

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        stack.getItem().use(level, player, InteractionHand.MAIN_HAND);

        List<Snowball> balls = level.getEntities(EntityType.SNOWBALL,
                new AABB(at).inflate(8.0), s -> s.entityTags().contains("boom_probe"));
        helper.assertTrue(balls.size() == 1, Component.literal("one tagged projectile"));
        helper.assertTrue(balls.get(0).getPersistentData()
                        .getStringOr(WishThrowImpact.HIT_FN, "").equals("probe:boom"),
                Component.literal("the landing instruction must ride on the projectile — in its persistent data and "
                        + "not in a tag, because a tag is a bare string and a function id has a colon"));
        helper.succeed();
    }

    /** An item with nothing asked of it must do nothing, rather than half-acting. */
    @GameTest(template = "empty_test")
    public static void aWishItemWithNoInstructionsIsInert(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack bare = new ItemStack(ModItems.WISH_ITEM.get());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absolutePos(new BlockPos(6, 2, 6)).getCenter().x, 2, 6);
        player.setItemInHand(InteractionHand.MAIN_HAND, bare);
        var result = com.moddingfromamod.compat.Interactions.use(bare.getItem(), level, player, InteractionHand.MAIN_HAND);
        helper.assertTrue(result == net.minecraft.world.InteractionResult.PASS,
                Component.literal("an item carrying no instructions must PASS, so it never eats a click it cannot honour"));
        helper.succeed();
    }
}
