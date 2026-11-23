package com.moddingfromamod.rules;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.SetRuleTool;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"When X happens, do Y", through the real tool and Minecraft's real events</b>
 * ({@link SetRuleTool} and {@link WorldRules}).
 *
 * <p>What a unit test cannot prove, and this does: each event really reaches its rule (a real
 * death, a real spawn, a real fall, a real jump, a real TNT blast, a real snowball), the queued
 * firing really runs a command AS the mob it happened to, a {@code harmless} explosion really keeps
 * its blocks, a timed {@code world} rule really fires from the server tick, and bone meal really
 * grows a crop.
 *
 * <p>ONE BATCH OF ITS OWN: rules are WORLD-wide, so a rule set here fires on every matching mob in
 * every test running beside it. Each test also uses its own kind of mob for the same reason. Not
 * covered here, because a GameTest has no real player: sneak, sprint, near_block and fish_caught.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WorldRulesGameTest {

    private static final String BATCH = "worldrules";

    /** The plot's floor, found (it is one block higher on 1.21.1). */
    static int floor(GameTestHelper helper) {
        return helper.getBlockState(new BlockPos(4, 0, 4)).isAir() ? 1 : 0;
    }

    /** set_rule hops to the server thread, so a GameTest calls it from off it. */
    private static AtomicReference<String> tool(String... calls) {
        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            StringBuilder all = new StringBuilder();
            for (String json : calls) {
                try {
                    ToolResult r = new SetRuleTool().call(JsonParser.parseString(json).getAsJsonObject());
                    all.append(r.isError() ? "ERROR " : "").append(r.text()).append('\n');
                } catch (RuntimeException e) {
                    all.append("ERROR set_rule threw: ").append(e).append('\n');
                }
            }
            out.set(all.toString());
        }, "set-rule-test");
        t.setDaemon(true);
        t.start();
        return out;
    }

    private static void assertSet(GameTestHelper helper, AtomicReference<String> r) {
        helper.assertTrue(r.get() != null, Component.literal("waiting for set_rule"));
        helper.assertTrue(!r.get().contains("ERROR") && r.get().contains("SET."),
                Component.literal("set_rule refused or failed: " + r.get()));
    }

    /**
     * Remove the rule once, and pass only when it is gone: a rule left behind persists in the
     * reused GameTest world and would fire in the next run. Call it last inside succeedWhen.
     */
    private static void removed(GameTestHelper helper, AtomicReference<AtomicReference<String>> rm,
                                String name) {
        if (rm.get() == null) {
            rm.set(tool("{remove:true, name:'" + name + "'}"));
        }
        String gone = rm.get().get();
        helper.assertTrue(gone != null, Component.literal("waiting for the rule to be removed"));
        helper.assertTrue(gone.contains("Removed"), Component.literal("remove failed: " + gone));
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aMobDyingRunsItsRuleAsThatMob(GameTestHelper helper) {
        String name = "t6c_test_husk_dies";
        AtomicReference<AtomicReference<String>> rm = new AtomicReference<>();
        AtomicReference<String> set = tool("{when:'mob_dies', who:'husk', name:'" + name
                + "', do:['setblock ~ ~1 ~ minecraft:gold_block']}");
        BlockPos at = new BlockPos(2, floor(helper) + 1, 2);
        AtomicBoolean killed = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (set.get() != null && killed.compareAndSet(false, true)) {
                Husk h = helper.spawn(EntityType.HUSK, at);
                h.hurt(h.damageSources().generic(), 1000f);
            }
        });
        helper.succeedWhen(() -> {
            assertSet(helper, set);
            boolean gold = false;
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-2, 0, -2), at.offset(2, 3, 2))) {
                gold |= helper.getBlockState(q).is(Blocks.GOLD_BLOCK);
            }
            helper.assertTrue(gold, Component.literal("a husk died under 'when a husk dies, gold appears' and no gold did"));
            removed(helper, rm, name);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aMobSpawningIsTaggedByItsRule(GameTestHelper helper) {
        String name = "t6c_test_mooshroom_spawns";
        AtomicReference<AtomicReference<String>> rm = new AtomicReference<>();
        AtomicReference<String> set = tool("{when:'mob_spawns', who:'mooshroom', name:'" + name
                + "', do:['tag @s add t6c_spawned']}");
        AtomicReference<net.minecraft.world.entity.Entity> cow = new AtomicReference<>();
        helper.onEachTick(() -> {
            if (set.get() != null && cow.get() == null) {
                cow.set(helper.spawn(EntityType.MOOSHROOM, new BlockPos(2, floor(helper) + 1, 2)));
            }
        });
        helper.succeedWhen(() -> {
            assertSet(helper, set);
            helper.assertTrue(cow.get() != null && cow.get().getTags().contains("t6c_spawned"),
                    Component.literal("a mooshroom appeared under 'when a mooshroom appears, tag it' and was not tagged"));
            removed(helper, rm, name);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aLandingPigIsPushedBackUp(GameTestHelper helper) {
        String name = "t6c_test_pig_lands";
        AtomicReference<AtomicReference<String>> rm = new AtomicReference<>();
        AtomicReference<String> set = tool("{when:'lands', who:'pig', name:'" + name
                + "', push:{up:0.8}, do:['tag @s add t6c_landed']}");
        int fl = floor(helper);
        AtomicReference<Pig> pig = new AtomicReference<>();
        double[] bounce = {Double.NEGATIVE_INFINITY};
        helper.onEachTick(() -> {
            if (set.get() != null && pig.get() == null) {
                pig.set(helper.spawn(EntityType.PIG, new BlockPos(2, fl + 5, 2)));
            }
            Pig p = pig.get();
            if (p != null && p.getTags().contains("t6c_landed")) {
                bounce[0] = Math.max(bounce[0], p.getY() - helper.absolutePos(new BlockPos(0, fl, 0)).getY());
            }
        });
        helper.succeedWhen(() -> {
            assertSet(helper, set);
            helper.assertTrue(pig.get() != null && pig.get().getTags().contains("t6c_landed"),
                    Component.literal("a pig fell four blocks and its landing rule never ran"));
            helper.assertTrue(bounce[0] > 1.8, Component.literal("the landed pig was never pushed back up (highest "
                    + "after landing: " + bounce[0] + " blocks above the floor)"));
            removed(helper, rm, name);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aJumpingSheepRunsItsRule(GameTestHelper helper) {
        String name = "t6c_test_sheep_jumps";
        AtomicReference<AtomicReference<String>> rm = new AtomicReference<>();
        AtomicReference<String> set = tool("{when:'jumps', who:'sheep', name:'" + name
                + "', do:['tag @s add t6c_jumped']}");
        AtomicReference<Sheep> sheep = new AtomicReference<>();
        helper.onEachTick(() -> {
            if (set.get() != null && sheep.get() == null) {
                sheep.set(helper.spawn(EntityType.SHEEP, new BlockPos(2, floor(helper) + 1, 2)));
            }
            Sheep r = sheep.get();
            if (r != null) {
                r.getJumpControl().jump();
            }
        });
        helper.succeedWhen(() -> {
            assertSet(helper, set);
            helper.assertTrue(sheep.get() != null && sheep.get().getTags().contains("t6c_jumped"),
                    Component.literal("a sheep jumped under 'when a sheep jumps, tag it' and was not tagged"));
            removed(helper, rm, name);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aHarmlessExplosionBreaksNothingAndStillRunsItsCommands(GameTestHelper helper) {
        String name = "t6c_test_tnt_harmless";
        AtomicReference<AtomicReference<String>> rm = new AtomicReference<>();
        AtomicReference<String> set = tool("{when:'explodes', who:'tnt', harmless:true, name:'" + name
                + "', do:['setblock ~ ~3 ~ minecraft:emerald_block']}");
        int fl = floor(helper);
        BlockPos at = new BlockPos(4, fl + 1, 4);
        AtomicBoolean lit = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (set.get() != null && lit.compareAndSet(false, true)) {
                for (BlockPos q : BlockPos.betweenClosed(at.offset(-1, 0, -1), at.offset(1, 0, 1))) {
                    if (!q.equals(at)) {
                        helper.setBlock(q, Blocks.STONE);
                    }
                }
                PrimedTnt tnt = helper.spawn(EntityType.TNT, at);
                tnt.setFuse(2);
            }
        });
        helper.succeedWhen(() -> {
            assertSet(helper, set);
            boolean emerald = false;
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-1, 1, -1), at.offset(1, 5, 1))) {
                emerald |= helper.getBlockState(q).is(Blocks.EMERALD_BLOCK);
            }
            helper.assertTrue(emerald, Component.literal("the TNT went off and its rule's command never ran"));
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-1, 0, -1), at.offset(1, 0, 1))) {
                if (!q.equals(at)) {
                    helper.assertBlockPresent(Blocks.STONE, q.immutable());
                }
            }
            removed(helper, rm, name);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aSnowballHitRunsItsRuleOnWhatItHit(GameTestHelper helper) {
        String name = "t6c_test_snowball_hits";
        AtomicReference<AtomicReference<String>> rm = new AtomicReference<>();
        AtomicReference<String> set = tool("{when:'projectile_hits', projectile:'snowball', who:'cow', "
                + "name:'" + name + "', push:{up:0.3}, do:['tag @s add t6c_hit']}");
        int fl = floor(helper);
        AtomicReference<Cow> cow = new AtomicReference<>();
        AtomicBoolean thrown = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (set.get() == null) {
                return;
            }
            if (cow.get() == null) {
                Cow c = helper.spawn(EntityType.COW, new BlockPos(4, fl + 1, 4));
                c.setNoAi(true);
                cow.set(c);
            } else if (thrown.compareAndSet(false, true)) {
                Vec3 above = cow.get().position().add(0, 3, 0);
                Snowball s = new Snowball(EntityType.SNOWBALL, helper.getLevel());
                s.setPos(above);
                s.setDeltaMovement(0, -1.2, 0);
                helper.getLevel().addFreshEntity(s);
            }
        });
        helper.succeedWhen(() -> {
            assertSet(helper, set);
            helper.assertTrue(cow.get() != null && cow.get().getTags().contains("t6c_hit"),
                    Component.literal("a snowball hit a cow under 'when a snowball hits a cow' and the rule never ran"));
            removed(helper, rm, name);
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aTimedWorldRuleFiresFromTheServerTick(GameTestHelper helper) {
        String name = "t6c_test_every_second";
        AtomicReference<AtomicReference<String>> rm = new AtomicReference<>();
        BlockPos mark = helper.absolutePos(new BlockPos(1, floor(helper) + 1, 6));
        AtomicReference<String> set = tool("{when:'every', seconds:1, who:'world', name:'" + name
                + "', do:['setblock " + mark.getX() + " " + mark.getY() + " " + mark.getZ()
                + " minecraft:lapis_block']}");
        helper.succeedWhen(() -> {
            assertSet(helper, set);
            helper.assertTrue(helper.getLevel().getBlockState(mark).is(Blocks.LAPIS_BLOCK),
                    Component.literal("an 'every second' world rule has not fired"));
            removed(helper, rm, name);
        });
    }

    /**
     * <b>A rule's give is the world's doing, not a hand-over</b>: into a full hotbar and a
     * main inventory with one free slot, 64 land in that slot and the rest on the floor at his feet —
     * never his Wish Bag, and Moddy says nothing. A rule giving every second is exactly the runaway
     * that used to grow the bag without bound.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aRulesGiveGoesToTheInventoryThenHisFeetNeverTheBag(GameTestHelper helper) {
        String name = "t1351_test_rule_gives";
        AtomicReference<AtomicReference<String>> rm = new AtomicReference<>();
        var p = com.moddingfromamod.item.delivery.WishGiveKindGameTest.packed(helper, "mfam-kind-rule", false);
        var cookie = net.minecraft.world.item.Items.COOKIE;
        AtomicReference<String> set = tool("{when:'every', seconds:1, who:'player', name:'" + name
                + "', do:['give @s minecraft:cookie 80']}");
        helper.succeedWhen(() -> {
            assertSet(helper, set);
            helper.assertTrue(p.getInventory().getItem(com.moddingfromamod.item.delivery.WishGiveKindGameTest.FREE)
                    .is(cookie), Component.literal("the rule has not given yet"));
            removed(helper, rm, name);
            try {
                helper.assertValueEqual(com.moddingfromamod.item.delivery.WishGiveKindGameTest.inMain(p, cookie), 64,
                        Component.literal("cookies in his main inventory (the hotbar is full)"));
                int floor = com.moddingfromamod.item.delivery.WishGiveKindGameTest.countOnFloor(p, cookie);
                helper.assertTrue(floor >= 16, Component.literal("cookies at his feet (his inventory is full): " + floor));
                helper.assertValueEqual(com.moddingfromamod.item.delivery.WishGiveKindGameTest.inBag(p, cookie), 0,
                        Component.literal("cookies in his Wish Bag — a rule's give may NEVER go there"));
                helper.assertValueEqual(com.moddingfromamod.item.delivery.WishGiveKindGameTest
                        .moddySaid(p, "Cookie").size(), 0, Component.literal("Moddy announced a rule's give"));
            } finally {
                com.moddingfromamod.item.delivery.WishGiveKindGameTest.done(p);
            }
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void aSummoningRuleStopsAtTheCrowdCap(GameTestHelper helper) {
        String name = "t6c_test_crowd";
        AtomicReference<AtomicReference<String>> rm = new AtomicReference<>();
        BlockPos spot = helper.absolutePos(new BlockPos(6, floor(helper) + 1, 6));
        String at = spot.getX() + " " + spot.getY() + " " + spot.getZ();
        int before = WorldRules.crowd;
        WorldRules.crowd = 12;
        AtomicReference<String> set = tool("{when:'every', seconds:1, who:'world', name:'" + name
                + "', do:['summon minecraft:armor_stand " + at + " {Tags:[\"t6c_crowd\"],NoGravity:1b}', "
                + "'summon minecraft:armor_stand " + at + " {Tags:[\"t6c_crowd\"],NoGravity:1b}', "
                + "'summon minecraft:armor_stand " + at + " {Tags:[\"t6c_crowd\"],NoGravity:1b}']}");
        long[] startedAt = {-1};
        helper.succeedWhen(() -> {
            assertSet(helper, set);
            if (startedAt[0] < 0) {
                startedAt[0] = helper.getTick();
            }
            int n = helper.getLevel().getEntities((net.minecraft.world.entity.Entity) null,
                    new net.minecraft.world.phys.AABB(spot).inflate(4), e -> e.getTags().contains("t6c_crowd")).size();
            helper.assertTrue(n <= 12 + 3, Component.literal("the crowd cap let " + n + " armour stands pile up"));
            helper.assertTrue(helper.getTick() - startedAt[0] > 400 && n >= 12,
                    Component.literal("still filling (" + n + " so far)"));
            WorldRules.crowd = before;
            removed(helper, rm, name);
            if (rm.get().get() != null) {
                helper.getLevel().getEntities((net.minecraft.world.entity.Entity) null,
                        new net.minecraft.world.phys.AABB(spot).inflate(4), e -> e.getTags().contains("t6c_crowd"))
                        .forEach(e -> e.discard());
            }
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 100)
    public static void growBoneMealsTheCropsAroundTheSpot(GameTestHelper helper) {
        int fl = floor(helper);
        BlockPos crop = new BlockPos(3, fl + 1, 3);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(crop.offset(dx, -1, dz), Blocks.FARMLAND);
                helper.setBlock(crop.offset(dx, 0, dz), Blocks.WHEAT);
            }
        }
        int grown = WorldRules.grow(helper.getLevel(), helper.absolutePos(crop), 2);
        helper.assertTrue(grown > 0, Component.literal("grow found nothing to bone-meal among nine wheat plants"));
        int aged = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                var s = helper.getBlockState(crop.offset(dx, 0, dz));
                if (s.getBlock() instanceof CropBlock c && c.getAge(s) > 0) {
                    aged++;
                }
            }
        }
        helper.assertTrue(aged > 0, Component.literal("grow reported " + grown + " but no wheat got older"));
        helper.succeed();
    }

    /** The store's own save and load, and the latch on a copy from a newer build. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 40)
    public static void theStoreRoundTripsAndRefusesANewerCopy(GameTestHelper helper) {
        var lookup = helper.getLevel().registryAccess();
        WorldRules.Store s = new WorldRules.Store();
        WorldRule r = WorldRule.parse(JsonParser.parseString("{when:'sunrise', who:'cat', "
                + "do:['loot spawn ~ ~ ~ loot minecraft:gameplay/cat_morning_gift']}").getAsJsonObject());
        s.rules.put(r.name(), r);
        CompoundTag tag = s.save(new CompoundTag(), lookup);
        WorldRules.Store back = WorldRules.Store.load(tag, lookup);
        helper.assertTrue(back.refused == null && back.rules.size() == 1
                        && back.rules.get(r.name()).equals(r),
                Component.literal("the rule did not come back from the save: " + back.rules));
        tag.putInt("v", WorldRules.Store.VERSION + 1);
        WorldRules.Store newer = WorldRules.Store.load(tag, lookup);
        helper.assertTrue(newer.refused != null && newer.rules.isEmpty(),
                Component.literal("a copy from a newer build must be refused, not read"));
        helper.succeed();
    }

    /**
     *, through the real tool and the real registry: a `who` that is no entity type is refused
     * before anything is saved, and a real mob still is not. The refusal is the whole fix — before
     * it the rule was saved, reported SET, and could never fire.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aWhoThatIsNoMobTypeIsRefusedAndARealOneIsNot(GameTestHelper helper) {
        AtomicReference<String> bad = tool("{when:'mob_dies', who:'notamob_1673', name:'t1673_bad', do:['say hi']}");
        AtomicReference<String> good = tool("{when:'mob_dies', who:'zombie', name:'t1673_good', do:['say hi']}");
        AtomicReference<AtomicReference<String>> rm = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertTrue(bad.get() != null && good.get() != null, Component.literal("waiting for set_rule"));
            helper.assertTrue(bad.get().startsWith("ERROR") && bad.get().contains("not a kind of mob"),
                    Component.literal("an unknown who must be refused: " + bad.get()));
            assertSet(helper, good);
            removed(helper, rm, "t1673_good");
        });
    }
}
