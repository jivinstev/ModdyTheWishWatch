package com.moddingfromamod.mob;


import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wish.WishExecutor;

import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * the world guard on wished creatures, through the paths that carried no number before —
 * an {@code execute} that fans one command out to many, a datapack function, and the control: the
 * same summons with no wish behind them are none of its business.
 *
 * <p>Every creature is removed at the end and every test asserts on what it made itself (counts
 * taken before and after), in its own batch so nothing else's creatures land in the count.
 *
 * <p>🔴 <b>Verified to fail</b> with {@code WishMobLimit} not registered: every creeper joins.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WishMobLimitGameTest {

    private static final String STAND = "moddy_1265_stand";

    private WishMobLimitGameTest() {
    }

    /**
     * One {@code execute as @e[…] run summon} — a single wish command — fanned out to three waves'
     * worth of creepers: the wish gets ONE wave, and the scope hands back the true sentence.
     */
    @GameTest(template = "empty_test", batch = "wishmoblimit", timeoutTicks = 200)
    public static void anExecuteThatFansOutStillMeetsOneWave(GameTestHelper helper) {
        int want = SpawnBudget.perWish(SpawnBudget.Kind.EXPLOSIVE) * 3;
        List<Entity> stands = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            Entity s = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(1 + i % 3, 2, 1 + (i / 3) % 3));
            s.addTag(STAND);
            stands.add(s);
        }
        int before = creepers(helper);
        int liveBefore = WishMobLimit.live(helper.getLevel().dimension(), SpawnBudget.Kind.EXPLOSIVE);
        String wish = "gametest-1265-" + System.nanoTime();
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 2, 1)));
        List<String> said = SpawnScope.during(wish, com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, () -> {
            WishExecutor.Result r = WishExecutor.runOne(helper.getLevel().getServer(), helper.getLevel(), at,
                    null, "execute as @e[type=minecraft:armor_stand,tag=" + STAND
                            + "] at @s run summon minecraft:creeper ~ ~ ~ {NoAI:1b}");
            helper.assertTrue(r.success(), Component.literal("the summon itself must run: " + r.joined()));
            return SpawnScope.current().lines();
        });
        int made = creepers(helper) - before;
        helper.assertTrue(made == SpawnBudget.perWish(SpawnBudget.Kind.EXPLOSIVE),
                Component.literal("one wish's execute made " + made + " creepers; its wave is " + SpawnBudget.perWish(SpawnBudget.Kind.EXPLOSIVE)));
        helper.assertTrue(SpawnScope.madeBy(wish, SpawnBudget.Kind.EXPLOSIVE) == SpawnBudget.perWish(SpawnBudget.Kind.EXPLOSIVE),
                Component.literal("the wish's tally is " + SpawnScope.madeBy(wish, SpawnBudget.Kind.EXPLOSIVE)));
        helper.assertTrue(said.size() == 1 && said.get(0).equals(
                        SpawnBudget.waveLine(SpawnBudget.Kind.EXPLOSIVE, "minecraft:creeper",
                                SpawnBudget.perWish(SpawnBudget.Kind.EXPLOSIVE))),
                Component.literal("the scope must hand back the wave words, got " + said));
        stands.forEach(Entity::discard);
        cleanUp(helper, liveBefore);
    }

    /**
     * A datapack — no wish behind it — summoning more creepers than the WORLD may hold of wished
     * explosives: it meets the ceiling, not a wave. Proves the function manager's scope (the mixin).
     */
    @GameTest(template = "empty_test", batch = "wishmoblimit2", timeoutTicks = 200)
    public static void aDatapackMeetsTheWorldCeiling(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        int liveBefore = WishMobLimit.live(level.dimension(), SpawnBudget.Kind.EXPLOSIVE);
        int before = creepers(helper);
        BlockPos p = helper.absolutePos(new BlockPos(1, 2, 1));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < SpawnBudget.worldLive(SpawnBudget.Kind.EXPLOSIVE) + 40; i++) {
            lines.add("summon minecraft:creeper " + (p.getX() + i % 3) + " " + p.getY() + " "
                    + (p.getZ() + (i / 3) % 3) + " {NoAI:1b}");
        }
        var source = server.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
        CommandFunction<net.minecraft.commands.CommandSourceStack> fn = CommandFunction.fromLines(
                Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, "gametest_1265"),
                server.getCommands().getDispatcher(), source, lines);
        server.getFunctions().execute(fn, source);
        int made = creepers(helper) - before;
        int room = SpawnBudget.worldLive(SpawnBudget.Kind.EXPLOSIVE) - liveBefore;
        helper.assertTrue(made == room, Component.literal("a datapack made " + made + " creepers; the world had room for "
                + room + " wished explosives (ceiling " + SpawnBudget.worldLive(SpawnBudget.Kind.EXPLOSIVE) + ")"));
        helper.assertTrue(SpawnScope.current() == null, Component.literal("the function's scope must close behind it"));
        cleanUp(helper, liveBefore);
    }

    /** The control: the same summons with no wish scope — a player's own, the world's — all join. */
    @GameTest(template = "empty_test", batch = "wishmoblimit3", timeoutTicks = 200)
    public static void creaturesNobodyWishedAreNoneOfItsBusiness(GameTestHelper helper) {
        int before = creepers(helper);
        int liveBefore = WishMobLimit.live(helper.getLevel().dimension(), SpawnBudget.Kind.EXPLOSIVE);
        int want = SpawnBudget.worldLive(SpawnBudget.Kind.EXPLOSIVE) + 20;
        for (int i = 0; i < want; i++) {
            helper.spawn(EntityType.CREEPER, new BlockPos(1 + i % 3, 2, 1 + (i / 3) % 3)).setNoAi(true);
        }
        int made = creepers(helper) - before;
        helper.assertTrue(made == want, Component.literal("unwished creepers were capped: " + made + " of " + want));
        cleanUp(helper, liveBefore);
    }

    /**
     * a wished summon into a chunk nobody has loaded. The join event fires (the entity
     * manager raises it whatever the section's visibility) but a hidden-section entity is never
     * tracked, so it never fires a leave either — the old running total kept it forever, one of the
     * ways the count crept to the 400 ceiling in long Gate B runs. The level holds no such tracked
     * creature, so the count must come back to where it was.
     *
     * <p>🔴 <b>Verified to fail</b> on the running total: {@code 1 still counted}.
     */
    @GameTest(template = "empty_test", batch = "wishmoblimit4", timeoutTicks = 200)
    public static void aWishedSummonIntoAnUnloadedChunkIsNotCountedForever(GameTestHelper helper) {
        var level = helper.getLevel();
        int liveBefore = WishMobLimit.live(level.dimension(), SpawnBudget.Kind.EXPLOSIVE);
        BlockPos far = helper.absolutePos(new BlockPos(1, 2, 1)).offset(200_000, 0, 200_000);
        helper.assertFalse(level.hasChunkAt(far), Component.literal("the far chunk must not be loaded"));
        String wish = "gametest-2399-far-" + System.nanoTime();
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 2, 1)));
        SpawnScope.during(wish, com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, () -> {
            WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level, at, null,
                    "summon minecraft:creeper " + far.getX() + " " + far.getY() + " " + far.getZ() + " {NoAI:1b}");
            helper.assertTrue(r.success(), Component.literal("the far summon itself must run: " + r.joined()));
            return null;
        });
        helper.assertTrue(SpawnScope.madeBy(wish, SpawnBudget.Kind.EXPLOSIVE) == 1,
                Component.literal("the far creeper must have been admitted as wished (the path under test), made "
                        + SpawnScope.madeBy(wish, SpawnBudget.Kind.EXPLOSIVE)));
        helper.assertFalse(level.hasChunkAt(far), Component.literal("the summon loaded the far chunk; the test no longer reaches a hidden section"));
        expectCount(helper, liveBefore, "a creeper in a chunk nobody loaded");
    }

    /**
     * a wished creature the entity manager REFUSES — {@code addFreshEntity} with a UUID the
     * level already holds returns false. The join event fires before the manager checks the UUID,
     * so the old running total counted a creature that never entered the level, and no leave could
     * ever take it back. ({@code /summon} checks the UUID itself before adding, so this is the
     * code path: a spawn site that copies a creature with its saved data.)
     *
     * <p>🔴 <b>Verified to fail</b> on the running total: {@code 1 still counted}.
     */
    @GameTest(template = "empty_test", batch = "wishmoblimit5", timeoutTicks = 200)
    public static void aWishedCreatureTheLevelRefusesIsNotCounted(GameTestHelper helper) {
        var level = helper.getLevel();
        var first = helper.spawn(EntityType.CREEPER, new BlockPos(1, 2, 1));   // nobody's wish: not counted
        first.setNoAi(true);
        int liveBefore = WishMobLimit.live(level.dimension(), SpawnBudget.Kind.EXPLOSIVE);
        String wish = "gametest-2399-dup-" + System.nanoTime();
        boolean added = SpawnScope.during(wish, com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, () -> {
            var copy = EntityType.CREEPER.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            copy.setUUID(first.getUUID());
            copy.setNoAi(true);
            copy.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2))));
            return level.addFreshEntity(copy);
        });
        helper.assertFalse(added, Component.literal("a second creature with the same UUID must be refused"));
        helper.assertTrue(SpawnScope.madeBy(wish, SpawnBudget.Kind.EXPLOSIVE) == 1,
                Component.literal("the copy must have been admitted as wished before the refusal (the path under test), made "
                        + SpawnScope.madeBy(wish, SpawnBudget.Kind.EXPLOSIVE)));
        helper.assertTrue(creepers(helper) == 1, Component.literal("only the first creeper stands, found " + creepers(helper)));
        expectCount(helper, liveBefore, "a creeper the level refused");
        first.discard();
    }

    /** A few ticks on, the level's count is what it was: nothing the level does not hold is counted. */
    private static void expectCount(GameTestHelper helper, int liveBefore, String what) {
        var level = helper.getLevel();
        helper.runAfterDelay(3, () -> {
            int now = WishMobLimit.live(level.dimension(), SpawnBudget.Kind.EXPLOSIVE);
            helper.assertTrue(now == liveBefore, Component.literal(what + " stayed counted ("
                    + (now - liveBefore) + " still counted, " + now + " against " + liveBefore + ")"));
            helper.succeed();
        });
    }

    private static int creepers(GameTestHelper helper) {
        return helper.getEntities(EntityType.CREEPER).size();
    }

    /** Remove what we made and prove the guard gave the budget back. */
    private static void cleanUp(GameTestHelper helper, int liveBefore) {
        var level = helper.getLevel();
        helper.getEntities(EntityType.CREEPER).forEach(Entity::discard);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(WishMobLimit.live(level.dimension(), SpawnBudget.Kind.EXPLOSIVE) == liveBefore,
                    Component.literal("removing them did not give the budget back ("
                            + WishMobLimit.live(level.dimension(), SpawnBudget.Kind.EXPLOSIVE) + " still counted)"));
            helper.succeed();
        });
    }
}
