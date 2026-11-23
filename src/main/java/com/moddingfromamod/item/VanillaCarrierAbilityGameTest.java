package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import java.util.List;
import java.util.function.Supplier;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.VanillaCarrier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

// ── TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
// The ability matrix. If the route is abandoned this whole file goes; if it ships, the
// MOD ITEM becomes the legacy path instead. Gated.

/**
 * <b>Comprehensive ability A/B: every shape a wish can ask for, on a VANILLA carrier.</b>
 *
 * <p>{@code make_item}'s frozen schema offers six shapes — {@code held}, {@code thrown},
 * {@code placed}, {@code machine}, {@code on_hit}, {@code plain}. The icon A/B
 * ({@code VanillaItemAbClientTest}) says a wished item can LOOK right on a vanilla carrier. This
 * says whether it can DO the thing it was wished for, which is a different mechanism and, measured
 * over the repo's own real item wishes, the majority case: at least 15 of the 19 wishes in
 * {@code tools/ab-wishes-items*.json} carry behaviour.
 *
 * <h2>How it is driven, and why not more cheaply</h2>
 *
 * <p>Through the REAL server interaction path — {@code ServerPlayerGameMode.useItem} /
 * {@code useItemOn} on a real {@link ServerPlayer} — rather than by calling the handlers. That is
 * the only way the NeoForge hooks this whole route depends on actually fire, and it is the
 * difference between testing the mechanism and testing a call to it. {@code MockPlayerNetwork} is
 * required here rather than {@code makeMockPlayer}: a plain {@code Player} has no
 * {@code gameMode}.
 *
 * <p>Each ability is asserted by its OWN OBSERVABLE, never by a flag: the effect function
 * ({@code moddingfromamod:ab_probe}) places a block <b>at the command source</b>, so one assertion
 * answers both "did it fire" and "did it fire in the right place" — at the player for {@code held},
 * at the target for {@code on_hit}. A boolean would have answered only the first, and position is
 * half of what these shapes promise.
 *
 * <h2>Its own batch, deliberately</h2>
 *
 * <p>The bridge is switched on through a global static, and tests in one batch run concurrently and
 * share every static (CLAUDE.md). A named batch is real isolation because batches are strictly
 * sequential — so every method here declares one, and every one restores the switch in a
 * {@code finally}.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class VanillaCarrierAbilityGameTest {

    private static final String BATCH = "vanillacarrier";

    /** The shipped probe function — places a diamond block wherever it is run. */
    private static final String PROBE_FN = ModdingFromAModMod.MODID + ":ab_probe";

    /**
     * The carrier the icon A/B chose, D1 — <b>marked</b>, because everywhere in this suite it
     * stands for OUR substitution. An unmarked one is a different thing entirely (a wish the child
     * built ON horse armour) and is built explicitly by the one test about that.
     */
    private static ItemStack carrier(String... kv) {
        return mark(withData(new ItemStack(Items.LEATHER_HORSE_ARMOR), kv));
    }

    /** An unmarked carrier: the same ITEM, but not one we minted. */
    private static ItemStack unmarkedCarrier(String... kv) {
        return withData(new ItemStack(Items.LEATHER_HORSE_ARMOR), kv);
    }

    /** Stamp {@link WishItem#CARRIER}, as the give line does when it substitutes. */
    private static ItemStack mark(ItemStack s) {
        CompoundTag tag = s.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.putBoolean(WishItem.CARRIER, true);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    /** The same wish on today's mod item, built through the production setter. */
    private static ItemStack modItem(String... kv) {
        return withData(WishItem.of(ModItems.WISH_ITEM.get(), 1, ""), kv);
    }

    private static ItemStack withData(ItemStack s, String... kv) {
        s.set(DataComponents.CUSTOM_MODEL_DATA, com.moddingfromamod.compat.ModelData.of(1));
        CompoundTag tag = new CompoundTag();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            tag.putString(kv[i], kv[i + 1]);
        }
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    /**
     * Run a body with the carrier route forced on or off, and always put it back.
     *
     * <p>ONE switch, {@link com.moddingfromamod.datapack.VanillaCarrier}. The bridge used to carry
     * its own property, which is the failure its own issue list names: two copies of "is this a
     * carrier" is how one path keeps acting while another stops.
     */
    private static void with(Boolean on, Runnable body) {
        try {
            com.moddingfromamod.datapack.VanillaCarrier.force(on);
            body.run();
        } finally {
            com.moddingfromamod.datapack.VanillaCarrier.force(null);
        }
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos at) {
        com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.setYRot(0.0F);
        p.setXRot(0.0F);
        return p;
    }

    /** Right-click in the air, through the real server path. */
    private static void rightClickAir(ServerPlayer p, ItemStack stack) {
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        p.gameMode.useItem(p, p.level(), p.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND);
    }

    /** Right-click the top face of a block, through the real server path. */
    private static void rightClickBlock(ServerPlayer p, ItemStack stack, BlockPos target) {
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(target).add(0, 0.5, 0), Direction.UP, target, false);
        p.gameMode.useItemOn(p, p.level(), p.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, hit);
    }

    private static void clearProbe(GameTestHelper helper, BlockPos around, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(around.offset(-radius, -radius, -radius),
                around.offset(radius, radius, radius))) {
            if (helper.getLevel().getBlockState(pos).is(Blocks.DIAMOND_BLOCK)) {
                helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static boolean probeFired(GameTestHelper helper, BlockPos around, double radius) {
        ServerLevel level = helper.getLevel();
        for (BlockPos pos : BlockPos.betweenClosed(
                around.offset((int) -radius, (int) -radius, (int) -radius),
                around.offset((int) radius, (int) radius, (int) radius))) {
            if (level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK)) {
                return true;
            }
        }
        return false;
    }

    private static void clearProbe(GameTestHelper helper, BlockPos around, double radius) {
        ServerLevel level = helper.getLevel();
        for (BlockPos pos : BlockPos.betweenClosed(
                around.offset((int) -radius, (int) -radius, (int) -radius),
                around.offset((int) radius, (int) radius, (int) radius))) {
            if (level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK)) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    // ── held ─────────────────────────────────────────────────────────────────────────────────

    /** {@code held}: right-click in the air runs the wish's effect, at the child. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theHeldShapeFiresOnBothArms(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        ServerPlayer p = player(helper, at);

        with(true, () -> {
            clearProbe(helper, at, 3);
            rightClickAir(p, modItem(WishItem.ON_USE, PROBE_FN));
            helper.assertTrue(probeFired(helper, at, 3),
                    Component.literal("arm A: the mod item's held effect did not run — the probe function places a "
                            + "block at the command source and none appeared"));

            clearProbe(helper, at, 3);
            rightClickAir(p, carrier(WishItem.ON_USE, PROBE_FN));
            helper.assertTrue(probeFired(helper, at, 3),
                    Component.literal("arm B: a VANILLA leather_horse_armor carrying the same WishOnUse did not run "
                            + "it. NeoForge's onItemRightClick fires before the item's own code, so "
                            + "if this fails the bridge is not wired, not the mechanism"));
            clearProbe(helper, at, 3);
        });
        helper.succeed();
    }

    // ── thrown ───────────────────────────────────────────────────────────────────────────────

    /** {@code thrown}: a tagged projectile, armed with the impact function, on both arms. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theThrownShapeFiresOnBothArms(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        ServerPlayer p = player(helper, at);

        with(true, () -> {
            for (String arm : List.of("A", "B")) {
                level.getEntities(EntityType.SNOWBALL, new AABB(at).inflate(10), e -> true)
                        .forEach(e -> e.discard());
                ItemStack stack = "A".equals(arm)
                        ? modItem(WishItem.THROW_TAG, "ab_fly", WishItem.HIT_FN, PROBE_FN)
                        : carrier(WishItem.THROW_TAG, "ab_fly", WishItem.HIT_FN, PROBE_FN);
                rightClickAir(p, stack);

                List<Snowball> balls = level.getEntities(EntityType.SNOWBALL,
                        new AABB(at).inflate(10), e -> e.getTags().contains("ab_fly"));
                helper.assertTrue(balls.size() == 1,
                        Component.literal("arm " + arm + ": expected exactly one tagged projectile, got "
                                + balls.size()));
                helper.assertTrue(balls.get(0).getDeltaMovement().lengthSqr() > 0.5,
                        Component.literal("arm " + arm + ": it must be THROWN, not dropped at the child's feet"));
                helper.assertTrue(PROBE_FN.equals(balls.get(0).getPersistentData()
                                .getStringOr(WishThrowImpact.HIT_FN, "")),
                        Component.literal("arm " + arm + ": the impact function must be armed on the projectile — "
                                + "WishThrowImpact reads it from the ENTITY, so this is what makes "
                                + "the landing half carrier-agnostic"));
                balls.forEach(b -> b.discard());
            }
        });
        helper.succeed();
    }

    // ── placed / machine ─────────────────────────────────────────────────────────────────────

    /** {@code placed} and {@code machine}: the tagged anchor lands on the aimed cell, both arms. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void thePlacedAndMachineShapesFireOnBothArms(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        with(true, () -> {
            int slot = 0;
            // `machine` is the SAME mechanism as `placed` in the frozen schema
            // (SHAPE_DATA: WishPlaceTag:"%s_new"), so both tags are driven rather than assumed.
            for (String tag : List.of("ab_here", "ab_new")) {
                for (String arm : List.of("A", "B")) {
                    BlockPos floorRel = new BlockPos(2 + slot * 2, 1, 2);
                    slot++;
                    helper.setBlock(floorRel, Blocks.STONE);
                    BlockPos floor = helper.absolutePos(floorRel);
                    ServerPlayer p = player(helper, floor.offset(0, 1, 3));

                    ItemStack stack = "A".equals(arm)
                            ? modItem(WishItem.PLACE_TAG, tag) : carrier(WishItem.PLACE_TAG, tag);
                    rightClickBlock(p, stack, floor);

                    List<ArmorStand> found = level.getEntities(EntityType.ARMOR_STAND,
                            new AABB(floor.above()).inflate(1.5),
                            e -> e.getTags().contains(tag));
                    helper.assertTrue(found.size() == 1,
                            Component.literal("arm " + arm + " / " + tag + ": expected one tagged anchor, got "
                                    + found.size() + " — a datapack watching for this tag is how "
                                    + "the whole placeable class finds what was put down"));
                    helper.assertTrue(found.get(0).blockPosition().equals(floor.above()),
                            Component.literal("arm " + arm + " / " + tag + ": the anchor must land on the AIMED cell, "
                                    + "not wherever the player is"));
                    helper.assertTrue(found.get(0).isInvisible(),
                            Component.literal("arm " + arm + " / " + tag + ": the anchor is scaffolding, not scenery"));
                    found.forEach(a -> a.discard());
                }
            }
        });
        helper.succeed();
    }

    // ── on_hit ───────────────────────────────────────────────────────────────────────────────

    /**
     * {@code on_hit}: needs NO bridge — asserted with the bridge switched OFF.
     *
     * <p>{@link WishMeleeHit} reads {@code WishOnHit} off the stack and never asks what item it is,
     * so it was already carrier-agnostic. Driving this with the bridge disabled is what proves that
     * rather than accidentally measuring the bridge doing it.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theOnHitShapeNeedsNoBridgeAtAll(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 6));
        ServerPlayer p = player(helper, at);

        with(false, () -> {
            for (String arm : List.of("A", "B")) {
                Zombie target = EntityType.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.COMMAND);
                helper.assertTrue(target != null, Component.literal("could not make a target"));
                target.setPos(at.getX() + 1.5, at.getY(), at.getZ());
                target.setNoAi(true);   // a punching bag, not an opponent — it must not fight back
                helper.getLevel().addFreshEntity(target);

                clearProbe(helper, target.blockPosition(), 3);
                ItemStack stack = "A".equals(arm)
                        ? modItem(WishItem.ON_HIT, PROBE_FN) : carrier(WishItem.ON_HIT, PROBE_FN);
                p.setItemInHand(InteractionHand.MAIN_HAND, stack);
                p.attack(target);

                helper.assertTrue(probeFired(helper, target.blockPosition(), 3),
                        Component.literal("arm " + arm + ": the on-hit effect did not run AT THE TARGET with the "
                                + "carrier bridge switched OFF — which is the claim: WishMeleeHit "
                                + "reads the stack, not the item"));
                clearProbe(helper, target.blockPosition(), 3);
                target.discard();
            }
        });
        helper.succeed();
    }

    // ── the two ways the bridge goes wrong ───────────────────────────────────────────────────

    /**
     * The mod item must not fire TWICE when the bridge is on.
     *
     * <p>Counted with the thrown shape on purpose: the effect function places a block and is
     * therefore idempotent, so it cannot tell one run from two. A projectile can be counted.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theBridgeNeverDoublesTheModItem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(6, 2, 6));
        ServerPlayer p = player(helper, at);

        with(true, () -> {
            level.getEntities(EntityType.SNOWBALL, new AABB(at).inflate(12), e -> true)
                    .forEach(e -> e.discard());
            rightClickAir(p, modItem(WishItem.THROW_TAG, "ab_double", WishItem.HIT_FN, PROBE_FN));
            List<Snowball> balls = level.getEntities(EntityType.SNOWBALL,
                    new AABB(at).inflate(12), e -> e.getTags().contains("ab_double"));
            helper.assertTrue(balls.size() == 1,
                    Component.literal("one right-click on the MOD item produced " + balls.size() + " projectiles — "
                            + "the bridge fired on top of WishItem.use. A doubled effect reads as "
                            + "an over-eager wish rather than as a bug, which is why this is "
                            + "counted rather than eyeballed"));
            balls.forEach(b -> b.discard());
        });
        helper.succeed();
    }

    /** An ordinary interaction with an ordinary item must be left completely alone. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theBridgeLeavesInnocentInteractionsAlone(GameTestHelper helper) {
        BlockPos floorRel = new BlockPos(2, 1, 8);
        helper.setBlock(floorRel, Blocks.STONE);
        BlockPos floor = helper.absolutePos(floorRel);

        with(true, () -> {
            ServerPlayer p = player(helper, floor.offset(0, 1, 3));
            // Placing a block is the commonest interaction in the game; if the bridge eats it the
            // Lite path is unplayable in a way no icon test could ever show.
            rightClickBlock(p, new ItemStack(Items.OAK_PLANKS), floor);
            helper.assertTrue(
                    helper.getLevel().getBlockState(floor.above()).is(Blocks.OAK_PLANKS),
                    Component.literal("a plain oak plank no longer places on a block with the bridge on"));
            helper.assertTrue(
                    helper.getLevel().getEntities(EntityType.ARMOR_STAND,
                            new AABB(floor.above()).inflate(2.0), e -> true).isEmpty(),
                    Component.literal("an ordinary item put down an anchor — the place-tag guard is not holding"));
            helper.getLevel().setBlockAndUpdate(floor.above(),
                    Blocks.AIR.defaultBlockState());
        });
        helper.succeed();
    }

    /** With the bridge off, a carrier is inert — the shipped mod is unchanged. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void withTheBridgeOffACarrierDoesNothing(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        ServerPlayer p = player(helper, at);

        with(false, () -> {
            clearProbe(helper, at, 3);
            rightClickAir(p, carrier(WishItem.ON_USE, PROBE_FN));
            helper.assertFalse(probeFired(helper, at, 3),
                    Component.literal("the carrier acted with the spike switched off — the shipped mod must be "
                            + "behaviourally identical to what it was"));
            helper.assertFalse(com.moddingfromamod.datapack.VanillaCarrier.enabled(),
                    Component.literal("the switch did not take"));
        });
        helper.succeed();
    }

    /**
     * <b>An ORDINARY leather horse armour is left completely alone.</b>
     *
     * <p>The most important safety property of the whole route, and the one a system-property
     * switch could not express: with the carrier on, every leather horse armour on the server is
     * potentially ours. What separates a wished thing from one the child found in a dungeon is the
     * wish data on the STACK — the same thing {@code WishMeleeHit} has always keyed on.
     *
     * <p>Without this, putting armour on a horse would run somebody's wish.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void anOrdinaryCarrierItemIsNotAWishedThing(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 12));
        ServerPlayer p = player(helper, at);

        with(true, () -> {
            ItemStack ordinary = new ItemStack(Items.LEATHER_HORSE_ARMOR);
            helper.assertFalse(VanillaCarrierAbilities.isCarrier(ordinary),
                    Component.literal("a plain leather horse armour must not read as a wished thing"));

            ItemStack wished = carrier(WishItem.ON_USE, PROBE_FN);
            helper.assertTrue(VanillaCarrierAbilities.isCarrier(wished),
                    Component.literal("a carrier with wish data must read as one"));

            ItemStack modItem = modItem(WishItem.ON_USE, PROBE_FN);
            helper.assertFalse(VanillaCarrierAbilities.isCarrier(modItem),
                    Component.literal("the mod item is NOT a carrier — it handles itself, and calling it one is how "
                            + "one right-click becomes two"));

            clearProbe(helper, at, 3);
            rightClickAir(p, ordinary);
            helper.assertFalse(probeFired(helper, at, 3),
                    Component.literal("an ordinary horse armour did something when right-clicked"));
        });
        helper.succeed();
    }

    /**
     * The carrier has no vanilla behaviour of its own to suppress — issue B2, for THIS carrier.
     *
     * <p>Recorded as an open risk because a potion carrier is drinkable and a leather helmet is
     * wearable, and a vanilla client predicts both animations before the server can cancel them.
     * The carrier that was chosen has neither, and that is why it was chosen — asserted so that
     * swapping it re-opens the question loudly instead of quietly.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theChosenCarrierHasNoVanillaBehaviourToSuppress(GameTestHelper helper) {
        ServerPlayer p = player(helper, helper.absolutePos(new BlockPos(5, 2, 12)));
        ItemStack bare = new ItemStack(Items.LEATHER_HORSE_ARMOR);
        helper.assertTrue(bare.getUseDuration(p) == 0,
                Component.literal("the carrier gained a use duration — a child right-clicking a wished cup would "
                        + "start using it, and a vanilla client plays that animation before the "
                        + "server can say no"));
        helper.assertFalse(bare.has(DataComponents.FOOD), Component.literal("the carrier became edible"));

        // IT MUST BE IN HAND. The first version of this asserted on use() without holding the
        // stack, so the swap operated on an EMPTY hand and returned non-PASS — the test failed
        // against a carrier that was behaving perfectly. An assertion about a held item that does
        // not hold the item is measuring something else entirely.
        p.setItemInHand(InteractionHand.MAIN_HAND, bare);
        var result = com.moddingfromamod.compat.Interactions.use(bare.getItem(), p.level(), p, InteractionHand.MAIN_HAND);

        // THE PREDICATE THAT MATTERS IS "did anything happen", not "was it PASS".
        //
        // AnimalArmorItem does inherit ArmorItem.use, which calls Equipable.swapWithEquipmentSlot —
        // so this is not inert by accident and two rounds of reasoning about it were wrong. What
        // actually happens: getEquipmentSlotForItem falls back to MAINHAND (a player cannot use
        // BODY), the swap then compares the held stack with itself, ItemStack.matches is true, and
        // it returns FAIL. FAIL consumes no action, equips nothing and consumes nothing — which is
        // the property B2 is about. Asserting == PASS was asserting an implementation detail, and
        // it went red against a carrier behaving perfectly.
        helper.assertFalse(result.consumesAction(),
                Component.literal("right-clicking the carrier now DOES something (" + result + ") — a child would "
                        + "set off a wished cup by holding it, and a vanilla client plays the "
                        + "prediction before the server can say no. Issue B2 is live and the "
                        + "bridge must suppress the carrier's own use"));
        helper.assertTrue(p.getItemInHand(InteractionHand.MAIN_HAND).is(Items.LEATHER_HORSE_ARMOR),
                Component.literal("the carrier left the child's hand when right-clicked, which is the failure the "
                        + "result code alone would not show"));
        helper.assertTrue(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.BODY).isEmpty(),
                Component.literal("the carrier was EQUIPPED — a wished cup is now body armour"));
        helper.succeed();
    }

    /**
     * <b>End to end: the give line the PRODUCTION path writes really works.</b>
     *
     * <p>Every other test here builds its stack by hand, which measures the bridge and not the
     * chain that feeds it. This one takes the string {@code MakeItemTool} would actually emit —
     * carrier id from {@code ItemBase.id}, icon and tint gated by {@code ItemBase.wearsOurIcons} —
     * runs it as a real {@code /give}, and drives the resulting stack. It is the join between A1,
     * A2 and the abilities, and a fixture that reimplements the path under test tests its
     * reimplementation.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void theProductionGiveLineProducesAWorkingWishedThing(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 12));
        ServerPlayer p = player(helper, at);

        with(true, () -> {
            String arg = com.moddingfromamod.agent.tools.MakeItemTool.itemArg(
                    new com.moddingfromamod.agent.tools.MakeItemTool.Variant("Hot Coffee", 0xC0FFEE),
                    3, "Drink it!", null, null,
                    WishItem.ON_USE + ":\"" + PROBE_FN + "\"");
            helper.assertTrue(arg.startsWith("minecraft:leather_horse_armor["),
                    Component.literal("the production give line did not resolve to the carrier: " + arg));

            var server = helper.getLevel().getServer();
            var source = p.createCommandSourceStack().withPermission(2).withSuppressedOutput();
            server.getCommands().performPrefixedCommand(source, "give @s " + arg + " 1");

            // ⚠ FOUND BY ITS NAME, NOT BY ITS SLOT — and the slot version passed every assertion
            // below while testing the wrong object. With the watch route on, a player is handed
            // Moddy at join, Moddy takes slot 0, and Moddy is ALSO a leather horse armour with a
            // custom name, a tint and custom_model_data. So `getItem(0)` returned him, all four
            // component checks went green, and the right-click opened the wish panel instead of
            // drinking the coffee — reported as "a wished thing did nothing when used", which
            // reads as a broken ability rather than as a fixture reading the wrong stack.
            // The property this test means is "the thing the give line just produced", so it is
            // now asked for by name and is route-independent (methodology §3.E.23).
            ItemStack given = ItemStack.EMPTY;
            for (ItemStack candidate : p.getInventory().getNonEquipmentItems()) {
                var name = candidate.get(DataComponents.CUSTOM_NAME);
                if (name != null && "Hot Coffee".equals(name.getString())) {
                    given = candidate;
                    break;
                }
            }
            helper.assertTrue(!given.isEmpty(),
                    Component.literal("the give line produced nothing called Hot Coffee; the inventory holds "
                            + p.getInventory().getNonEquipmentItems().stream().filter(x -> !x.isEmpty()).toList()));
            helper.assertTrue(given.is(Items.LEATHER_HORSE_ARMOR),
                    Component.literal("the give produced " + given + " — a give line that names an item the game "
                            + "does not know says NOTHING and hands over nothing at all"));
            helper.assertTrue(given.has(DataComponents.CUSTOM_MODEL_DATA),
                    Component.literal("the icon did not survive the production path: this is issue A1, and without "
                            + "it every wished item is a plain horse armour"));
            helper.assertTrue(given.has(DataComponents.DYED_COLOR),
                    Component.literal("the colour did not survive: vanilla's leather handler then falls back to its "
                            + "own brown"));
            helper.assertTrue(given.has(DataComponents.CUSTOM_NAME), Component.literal("the name did not survive"));

            clearProbe(helper, at, 3);
            rightClickAir(p, given);
            helper.assertTrue(probeFired(helper, at, 3),
                    Component.literal("a wished thing built entirely by the production path did nothing when used"));
            clearProbe(helper, at, 3);
        });
        helper.succeed();
    }

    /**
     * <b>A wished thing cannot be put ON a horse.</b>
     *
     * <p>The carrier is body armour, and {@code AbstractHorse.mobInteract} equips body armour on
     * right-click: {@code if (canUseSlot(BODY) && isBodyArmorItem(stack) && !isWearingBodyArmor())}.
     * So without this, a child right-clicking a horse while holding his Hot Coffee puts the coffee
     * on the horse and loses it.
     *
     * <p><b>Found only by writing the disparities out in prose.</b> The earlier "the carrier has no
     * vanilla behaviour to suppress" check drove right-click-in-AIR and declared the whole class
     * closed — the same shape as CAMERA.md's "grep for a guard's CALLERS, never just for the
     * guard". One interaction path is not one class of behaviour.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aWishedThingCannotBePutOnAHorse(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 14));
        ServerPlayer p = player(helper, at);

        with(true, () -> {
            var horse = net.minecraft.world.entity.EntityType.HORSE.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            helper.assertTrue(horse != null, Component.literal("could not make a horse"));
            horse.setPos(at.getX() + 1.0, at.getY(), at.getZ());
            horse.setTamed(true);
            helper.getLevel().addFreshEntity(horse);

            ItemStack wished = carrier(WishItem.ON_USE, PROBE_FN);
            p.setItemInHand(InteractionHand.MAIN_HAND, wished);
            p.interactOn(horse, InteractionHand.MAIN_HAND);

            helper.assertFalse(horse.isWearingBodyArmor(),
                    Component.literal("the child's wished thing was equipped ONTO THE HORSE — he right-clicked a "
                            + "horse and lost his Hot Coffee"));
            helper.assertTrue(p.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 1,
                    Component.literal("the wished thing left the child's hand"));
            horse.discard();
        });
        helper.succeed();
    }

    /**
     * <b>A wished thing does not claim to be armour in its tooltip.</b>
     *
     * <p>{@code ArmorItem}'s constructor memoises {@code defaultModifiers} from the material, so an
     * un-neutralised carrier reads <i>"When on Body: +3 Armor"</i> under a name like Hot Coffee.
     * Cosmetic, and it is the kind of cosmetic that tells a child the thing he was given is really
     * something else.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aWishedThingDoesNotClaimToBeArmour(GameTestHelper helper) {
        with(true, () -> {
            String arg = com.moddingfromamod.agent.tools.MakeItemTool.itemArg(
                    new com.moddingfromamod.agent.tools.MakeItemTool.Variant("Hot Coffee", 0xC0FFEE),
                    3, "Drink it!", null, null, "WishItem:\"hot_coffee\"");
            ServerPlayer p = player(helper, helper.absolutePos(new BlockPos(5, 2, 14)));
            var server = helper.getLevel().getServer();
            server.getCommands().performPrefixedCommand(
                    p.createCommandSourceStack().withPermission(2).withSuppressedOutput(),
                    "give @s " + arg + " 1");

            ItemStack given = null;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                var d = st.get(DataComponents.CUSTOM_DATA);
                if (d != null && "hot_coffee".equals(d.copyTag().getStringOr("WishItem", ""))) {
                    given = st;
                    break;
                }
            }
            helper.assertTrue(given != null, Component.literal("the give line produced nothing: " + arg));
            // ASK THE ACCESSOR THE TOOLTIP USES. The first version read the raw component and
            // passed — because ArmorItem supplies its modifiers through getDefaultAttributeModifiers,
            // not through the component, so the component is empty and says nothing about what is
            // rendered. NeoForge routes both through ItemStack.getAttributeModifiers().
            // ASSERT THE PROPERTY, NOT THE SHAPE. "isEmpty()" was the wrong test twice over: an
            // EMPTY component falls straight through to the carrier's own +3 armor
            // (IItemStackExtension.getAttributeModifiers), so the fix has to write a NON-empty,
            // inert one — and then an isEmpty assertion fails against a working fix. What actually
            // matters is that it grants nothing and says nothing.
            var mods = given.getAttributeModifiers();
            for (var entry : mods.modifiers()) {
                helper.assertTrue(entry.modifier().amount() == 0.0,
                        Component.literal("a wished thing GRANTS " + entry.modifier().amount() + " of "
                                + entry.attribute().getRegisteredName() + " — the carrier's own "
                                + "armour came through: " + mods));
            }
            helper.assertFalse(!given.getOrDefault(DataComponents.TOOLTIP_DISPLAY, net.minecraft.world.item.component.TooltipDisplay.DEFAULT).hiddenComponents().contains(DataComponents.ATTRIBUTE_MODIFIERS),
                    Component.literal("a wished thing ADVERTISES attributes in its tooltip (" + mods + ") — under a "
                            + "name like Hot Coffee, which tells a child the thing he was given is "
                            + "really something else"));
        });
        helper.succeed();
    }

    /**
     * A carrier CAN stack, which retires a limitation this spike wrote down as a fact.
     *
     * <p>The tally said "leather_horse_armor stacks to 1, and no vanilla dyeable item stacks", and
     * treated that as a collision with the frozen schema — {@code make_item}'s {@code count} takes
     * 1–64 and its own text asks for <i>"a handful for something thrown, because throwing the only
     * one you have ends the game"</i>. It is wrong: {@code max_stack_size} is a <b>data
     * component</b>, so the give line can raise it like any other property.
     *
     * <p>The one real constraint is in {@code ItemStack}:
     * {@code if (has(MAX_DAMAGE) && getOrDefault(MAX_STACK_SIZE, 1) > 1)} is an error — and horse
     * armour has no durability, which is checked here rather than assumed, because that is the half
     * that would make the give fail <i>silently</i> ({@code ItemBase.refusals} records the same trap
     * for a golden axe).
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aCarrierCanBeMadeToStackLikeTheModItem(GameTestHelper helper) {
        ItemStack bare = new ItemStack(Items.LEATHER_HORSE_ARMOR);
        helper.assertTrue(bare.getMaxStackSize() == 1,
                Component.literal("the premise has changed: horse armour already stacks to " + bare.getMaxStackSize()));
        helper.assertFalse(bare.has(DataComponents.MAX_DAMAGE),
                Component.literal("the carrier gained durability, which makes it un-stackable — ItemStack refuses "
                        + "MAX_DAMAGE together with MAX_STACK_SIZE > 1, and the give fails silently"));

        ItemStack stacked = carrier(WishItem.THROW_TAG, "ab_stack");
        stacked.set(DataComponents.MAX_STACK_SIZE, 64);
        helper.assertTrue(stacked.getMaxStackSize() == 64,
                Component.literal("a carrier could not be told to stack: " + stacked.getMaxStackSize()));
        stacked.setCount(64);
        helper.assertTrue(stacked.getCount() == 64 && !stacked.isEmpty(),
                Component.literal("a 64-count carrier did not survive being built"));

        ItemStack mod = modItem(WishItem.THROW_TAG, "ab_stack");
        helper.assertTrue(mod.getMaxStackSize() >= 1,
                Component.literal("the mod item lost its stack size"));
        helper.succeed();
    }

    /**
     * The one measured DIFFERENCE between the arms, pinned so it cannot drift unnoticed.
     *
     * <p>A {@code plain} mod item right-clicked on a block still puts down an <i>untagged</i>
     * anchor — {@code WishItem.useOn} places one whether or not there is a tag. The carrier does
     * not, because the bridge has to check the tag before it can know the interaction is ours at
     * all. Recorded in {@code docs/VANILLA_ITEM_SPIKE_ISSUES.md}; asserted here so that if either
     * side changes, the difference is re-examined rather than silently resolved.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aPlainItemPlacesAnUntaggedAnchorOnArmAOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floorRel = new BlockPos(2, 1, 10);
        helper.setBlock(floorRel, Blocks.STONE);
        BlockPos floor = helper.absolutePos(floorRel);
        Supplier<List<ArmorStand>> anchors = () -> level.getEntities(EntityType.ARMOR_STAND,
                new AABB(floor.above()).inflate(1.5), e -> true);

        with(true, () -> {
            anchors.get().forEach(a -> a.discard());
            ServerPlayer p = player(helper, floor.offset(0, 1, 3));

            rightClickBlock(p, carrier(), floor);
            helper.assertTrue(anchors.get().isEmpty(),
                    Component.literal("arm B placed an anchor for a plain item with no place tag"));

            rightClickBlock(p, modItem(), floor);
            helper.assertTrue(anchors.get().size() == 1,
                    Component.literal("arm A stopped placing an untagged anchor for a plain item. That is probably "
                            + "an improvement, but it is a CHANGE to the shipped path and this "
                            + "spike is not allowed to make one — re-read the extraction in "
                            + "WishItemActions.useOn before changing this assertion"));
            anchors.get().forEach(a -> a.discard());
        });
        helper.succeed();
    }

    /**
     * <b>A wish on a carrier must still be RECOGNISABLE to the agent's own tools.</b>
     *
     * <p>Found by asking whether the creative-menu gap is creative-only. It is not, and the
     * reason is one hook: {@link WishItemCatalogue} is written from exactly one place,
     * {@code WishItem.inventoryTick}, which is an override on the mod item. Put the same wish on a
     * leather horse armour and nothing ever calls {@code remember}, so the catalogue stays empty.
     *
     * <p><b>The creative menu is not the only thing downstream of it.</b> Two SERVER-side agent
     * tools gate on {@code WishItemCatalogue.knows(stack)} to find a wished thing in the child's
     * inventory — {@code RemoveItemTool} ("take away the fire sword") and
     * {@code MakeItemTool.clearRetired} (sweeping variants a refinement retired). Neither is
     * creative-only and both fail <i>quietly</i>: {@code RemoveItemTool} still deletes the design
     * and forgets the name, so it reports <i>"Took away 'Hot Coffee'"</i> while the coffee is still
     * in his hotbar.
     *
     * <h2>The two arms are driven differently, and that asymmetry is the finding</h2>
     *
     * <p>Arm B rides the REAL wiring: the stack is put in a real player's inventory and the world
     * is left to tick, which is exactly what happens in play. Arm A cannot be driven that way at
     * all — {@code Inventory.tick()} runs from {@code Player.tick()}, which a {@code ServerPlayer}
     * reaches only inside {@code doTick()}, which is called by
     * {@code ServerGamePacketListenerImpl.tick()}. That is driven by the player's network
     * connection, and a GameTest mock's {@code EmbeddedChannel} is never ticked by the server's
     * connection listener. So {@code inventoryTick} is <b>unreachable from Gate B</b>, full stop.
     * Three runs were spent on that reading as a broken production path, which is why the arm-A
     * call is direct and says so rather than looking like a shortcut.
     *
     * <p>Arm A is still worth asserting: it proves the catalogue accepts this shape of stack, so a
     * failure on arm B cannot be explained away as "the catalogue would have refused it anyway".
     *
     * <h2>Its own batch, because this one has to let the world TICK</h2>
     *
     * <p>Tests in one batch run concurrently (CLAUDE.md) and every sibling's {@code player()} calls
     * {@code MockPlayerNetwork.clearStaleMocks}, which removes this test's player mid-wait. Batches
     * are strictly sequential, so a batch of its own is real isolation. It also removes its own
     * player: the tests that run afterwards are not carrier tests and never call that sweep, and a
     * stray mock becomes "the child" for all of them — the first cut of this test turned <b>20</b>
     * unrelated tests red that way, every one of them reporting a distance to a mob.
     */
    @GameTest(template = "empty_test", batch = "vanillacarriercatalogue", timeoutTicks = 400)
    public static void aWishOnACarrierIsRememberedSoTheAgentCanStillFindIt(GameTestHelper helper) {
        // DIFFERENT behaviour ids on purpose: WishItemCatalogue.keyOf keys on the behaviour tag,
        // so two stacks carrying the same one share a key and arm B would pass on arm A's row.
        ItemStack modWish = named(modItem(WishItem.ON_USE, PROBE_FN), "Catalogue Probe A");
        ItemStack carrierWish = named(carrier(WishItem.ON_USE, PROBE_FN + "_b"), "Catalogue Probe B");

        VanillaCarrier.force(Boolean.TRUE);
        ServerLevel level = helper.getLevel();
        ServerPlayer p = player(helper, helper.absolutePos(new BlockPos(2, 2, 2)));

        // Arm B, driven by the real wiring: put it in his inventory and let the server tick.
        // Through HandOver, not Inventory.add — LEARNINGS records that add()'s boolean is
        // meaningless for a Creative player, and the mock is Creative.
        HandOver.toPlayer(p, carrierWish.copy());

        // Arm A, driven directly — see the javadoc: the tick path cannot reach it here. BOTH sides
        // sample once a second, so the call has to LAND on a multiple of 20 or it returns without
        // doing anything; calling it on whatever tick setup happened to run is how this read as
        // "the catalogue refused the stack" on a run where the catalogue was perfectly happy.
        long onTheSecond = (20L - (level.getGameTime() % 20L)) % 20L;
        helper.runAfterDelay(onTheSecond, () -> modWish.inventoryTick(level, p, net.minecraft.world.entity.EquipmentSlot.MAINHAND));

        // The sweep is once a SECOND, not every tick, so wait well past a whole period rather than
        // guessing which tick we started on.
        helper.runAfterDelay(onTheSecond + 60L, () -> {
            try {
                String seen = " [gameTime=" + level.getGameTime()
                        + " inList=" + (p.getServer() != null
                                && p.getServer().getPlayerList().getPlayer(p.getUUID()) != null)
                        + " heldB=" + p.getInventory().contains(st -> WishItemCatalogue
                                .keyOf(st).equals(WishItemCatalogue.keyOf(carrierWish)))
                        + " keyA=" + WishItemCatalogue.keyOf(modWish)
                        + " keyB=" + WishItemCatalogue.keyOf(carrierWish)
                        + " catalogueRows=" + WishItemCatalogue.count() + " (cumulative, not reset)]";
                helper.assertTrue(WishItemCatalogue.knows(modWish),
                        Component.literal("the catalogue refused this shape of stack outright, so this test can say "
                                + "nothing about the carrier until that is understood" + seen));
                helper.assertTrue(WishItemCatalogue.knows(carrierWish),
                        Component.literal("a wish on a vanilla carrier was never recorded in the catalogue, so "
                                + "remove_item cannot take it out of his inventory and a retired "
                                + "variant is never swept — both report success anyway" + seen));
                helper.succeed();
            } finally {
                // Deliberately does NOT clear the catalogue. Both probe keys are unique to this
                // test (`ab_probe` / `ab_probe_b`), so a shared store needs no reset — and
                // SourceRuleSweepTest fails the build for clearing process-wide state that
                // WishItemCatalogueGameTest also reads, which is the right rule: the neighbour
                // would not go red, it would go green having asserted nothing.
                com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
                VanillaCarrier.force(null);
            }
        });
    }

    /**
     * <b>A wish the child BUILT ON horse armour is not our carrier, and the bridge must ignore it.</b>
     *
     * <p>The third and last instance of one root, and the only structural one: {@code base_item}
     * takes any item id, so his "magic horse armour" and our substitution are the <b>same item with
     * the same behaviour tags</b>. Nothing about the stack distinguishes them — so the two earlier
     * fixes (the neutralised armour, the borrowed icon) worked only because the raw {@code
     * base_item} was still in scope at the give line. At interaction time it is not.
     *
     * <p>Left unfixed the bridge runs his horse armour's {@code held} function while the same wish
     * built on a golden axe does nothing, purely because of which vanilla item we picked as the
     * carrier. {@link WishItem#CARRIER} is stamped at give time and asked here instead.
     */
    // OWN BATCH. It calls player(), and player() calls MockPlayerNetwork.clearStaleMocks, which
    // removes EVERY mock — including one a concurrent sibling is mid-way through using. Batches are
    // strictly sequential, so a batch of its own is real isolation. Measured: adding this test and
    // the broadcast one to the shared batch turned aPlainItemPlacesAnUntaggedAnchorOnArmAOnly red
    // with "arm A stopped placing an untagged anchor" — a message about the shipped path, produced
    // by a player being deleted underneath it.
    @GameTest(template = "empty_test", batch = "vanillacarriermark")
    public static void aWishBuiltOnHorseArmourIsNotOurCarrier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floorRel = new BlockPos(9, 1, 9);
        helper.setBlock(floorRel, Blocks.STONE);
        BlockPos floor = helper.absolutePos(floorRel);

        with(true, () -> {
            ServerPlayer p = player(helper, floor.offset(0, 1, 3));

            // HIS horse armour: the carrier item, the same held tag — and no carrier mark.
            ItemStack his = unmarkedCarrier(WishItem.ON_USE, PROBE_FN);
            helper.assertFalse(WishItem.isOurCarrier(his),
                    Component.literal("the fixture is wrong: this stack must NOT carry the mark"));
            // ⚠ This line used to assert no diamond at `floorRel.above(0)` — the STONE floor, which
            // is never a diamond, so it passed whatever the bridge did. And its premise
            // changed under it: since wishes ride real bases, a wish the child built on ANY real item runs, horse
            // armour included — which is the predictable rule this test asked for (the same wish
            // on a golden axe runs too). So the assertion is now that it runs as HIS wish on a real
            // item, and below, that it is never mistaken for our carrier.
            helper.assertTrue(VanillaCarrierAbilities.isWishOnARealItem(his),
                    Component.literal("his horse armour wish is not treated as a wish on a real item"));
            rightClickAir(p, his);
            helper.assertTrue(probeFired(helper, p.blockPosition(), 1),
                    Component.literal("his horse-armour wish did not run, while the same wish on any other real item "
                            + "does — a rule nobody could predict"));
            clearProbe(helper, p.blockPosition(), 1);

            // OURS: the same stack, marked. It must still work, or the mark broke the feature.
            ItemStack ours = carrier(WishItem.ON_USE, PROBE_FN);
            helper.assertTrue(WishItem.isOurCarrier(ours), Component.literal("the mark did not survive being written"));
            helper.assertTrue(VanillaCarrierAbilities.isCarrier(ours),
                    Component.literal("a MARKED carrier stopped being recognised — the mark has broken the whole "
                            + "route, which every other test in this class would also catch, but "
                            + "this one says why"));
            helper.assertFalse(VanillaCarrierAbilities.isCarrier(his),
                    Component.literal("the child's own horse armour was treated as our carrier: the bridge would "
                            + "run its abilities, while the identical wish on a golden axe does "
                            + "nothing — a rule nobody could predict"));
        });
        helper.succeed();
    }

    /**
     * <b>The crash {@code Send.all} exists to prevent, REPRODUCED — Tier 2 → Tier 1.</b>
     *
     * <p>The ledger recorded this as read-from-source and never executed: {@code
     * ServerCommonPacketListenerImpl.send} calls {@code NetworkRegistry.checkPacket} as its first
     * line, which throws {@code UnsupportedOperationException} for a listener with no such channel.
     * On the Lite path that listener is a <b>vanilla client</b> — and no gate has one.
     *
     * <p>It turns out one is available after all: a GameTest mock player is channel-less unless the
     * harness shims it, which is exactly the condition, and is why {@code net/Send.java} was
     * written in the first place (its javadoc records a dedicated server this killed 24 seconds
     * into a run). So the crash can be produced headlessly, and the guard exercised against it,
     * without a vanilla client existing.
     *
     * <p>Asserted in both directions, because a guard proved only by NOT crashing is equally
     * consistent with a payload nobody was ever going to reject.
     */
    // Own batch, same reason as the mark test above — and doubly so here, because this one takes
    // a player's payload channels away for a moment.
    @GameTest(template = "empty_test", batch = "vanillacarrierbroadcast")
    public static void theGuardedBroadcastSurvivesAClientThatCannotReceive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer p = player(helper, helper.absolutePos(new BlockPos(11, 2, 11)));
        var payload = com.moddingfromamod.net.ModdedClient.wishItems();
        if (payload == null) {   // no modded-client plug-in: no channel, so nothing is ever sent to guard
            com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
            helper.succeed();
            return;
        }

        com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
            helper.assertFalse(com.moddingfromamod.net.Send.canReceive(p, payload.type()),
                    Component.literal("this test needs a player that CANNOT receive and got one that can — the "
                            + "reproduction is gone and Send.all would be unexercised again"));

            // 1. The unguarded call must still blow up, or there is nothing to guard.
            boolean threw = false;
            try {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, payload);
            } catch (RuntimeException expected) {
                threw = true;
            }
            helper.assertTrue(threw,
                    Component.literal("PacketDistributor no longer throws for a channel-less client — either "
                            + "NeoForge changed, or this player can receive after all. Send.all's "
                            + "whole reason for existing has moved; re-read it before trusting "
                            + "the guard"));

            // 2. The guarded one must not — and it is the exact call the carrier sweep makes.
            com.moddingfromamod.net.ModdedClient.broadcastWishItems(level.getServer());
        });

        com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
        helper.succeed();
    }

    /**
     * <b>The creative-menu hook, EXERCISED rather than reasoned — the other Tier 2 claim.</b>
     *
     * <p>The issues doc's §F says a client with the mod gets carrier items in its creative menu,
     * and says it from reading four call sites. This drives the one that actually decides:
     * {@code ModItems.addToCreativeTabs} decodes whatever the catalogue stored and accepts it, so
     * the question is whether a carrier stack survives that round trip and comes back as the same
     * item — not whether the tab was rebuilt, which only a client can show.
     *
     * <p>Deliberately NOT a claim about the menu appearing. Tab contents are assembled client-side
     * from the client's own registries; a headless server can prove the ENTRY is well-formed and
     * nothing more. The remaining half stays Tier 2 and is named as such in the ledger.
     */
    @GameTest(template = "empty_test", batch = "vanillacarriercatalogue", timeoutTicks = 200)
    public static void aCarrierSurvivesTheRoundTripThatFeedsTheCreativeMenu(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VanillaCarrier.force(Boolean.TRUE);
        try {
            ItemStack wish = named(carrier(WishItem.ON_USE, PROBE_FN + "_menu"), "Menu Probe");

            helper.assertTrue(WishItemCatalogue.remember(level.getServer(), wish),
                    Component.literal("the catalogue refused a carrier stack, so nothing could ever reach the menu"));

            WishItemCatalogue.Entry entry = WishItemCatalogue.all().stream()
                    .filter(e -> "Menu Probe".equals(e.name())).findFirst().orElse(null);
            helper.assertTrue(entry != null, Component.literal("the carrier was recorded under a different name"));

            // The exact call ModItems.addToCreativeTabs makes.
            ItemStack back = WishItemCatalogue.stack(entry, level.registryAccess());
            helper.assertFalse(back.isEmpty(),
                    Component.literal("the stored carrier decoded to nothing — the menu entry would be silently "
                            + "skipped, which looks exactly like the item never being wished for"));
            helper.assertTrue(back.getItem() == wish.getItem(),
                    Component.literal("it came back as a different ITEM: " + back.getItem()));
            helper.assertTrue(PROBE_FN.concat("_menu").equals(WishItem.onUse(back)),
                    Component.literal("it came back as a LOOKALIKE — right name, no behaviour. That is the exact "
                            + "trap #114 records for the mod item, arriving on the carrier"));
            helper.assertTrue(WishItem.isOurCarrier(back),
                    Component.literal("the carrier mark did not survive the round trip, so the bridge would ignore "
                            + "an item taken back out of the creative menu"));
        } finally {
            VanillaCarrier.force(null);
        }
        helper.succeed();
    }

    /** Give a stack a display name — the catalogue ignores anything unnamed, deliberately. */
    private static ItemStack named(ItemStack s, String name) {
        s.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(name));
        return s;
    }
}
