package com.moddingfromamod.wish;

import com.moddingfromamod.testing.gt.GameTestCompat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.FinishBuildingTool;
import com.moddingfromamod.item.WishItem;
import com.moddingfromamod.item.delivery.WishDelivery;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"Does this wish's thing exist / does he have it" is asked by IDENTITY</b>, driven
 * through the REAL {@code finish_building} — the rule tested where it RUNS, not where it is defined.
 *
 * <p>Every archived false contradiction is rebuilt and must now HOLD; every real failure is rebuilt
 * and must still FAIL. Each test is one wish and one {@code finish_building} carrying up to three
 * claims ({@link WishClaims#MAX}), and the verdict per claim is read off the refusal, which names
 * exactly the claims the world contradicted (a claim not named held). A test whose claims all hold
 * must not be refused at all.
 *
 * <ul>
 *   <li><b>must HOLD</b>: an item handed over under its wish key ({@code boomerang_plain}, 20 of 24
 *       archived item contradictions); an item delivered into the Wish Bag; Spikor following
 *       him 40 blocks from the spot (the live, wish {@code e3423f8f}); 50 blocks within a
 *       radius of 40 (the rainbow heart, silently cut to 24).</li>
 *   <li><b>must still FAIL</b>: an item never handed over (even one he already has); one handed over
 *       then thrown away; a creature dead, or never spawned; 10 blocks when 50 were asked; a fox not
 *       set to follow; a misnamed datapack; creepers that fell into a sinkhole.</li>
 *   <li><b>loopholes</b>: an older wish's item in his bag; {@code bow_plain} for a {@code spikor_plain}.</li>
 *   <li><b>the radius scan, timed</b> at {@link ClaimCheck#MAX_RADIUS}, so it cannot stall a tick.</li>
 * </ul>
 *
 * <p>Own batches: each drives {@code Marshal.global()} and a named worker.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ClaimIdentityGameTest {

    // ── must HOLD ────────────────────────────────────────────────────────────────────────────

    /** A made item named by its key, in his hotbar; another delivered into his Wish Bag. */
    @GameTest(template = "empty_test", batch = "claims1344items", timeoutTicks = 1000000)
    public static void itemsThisWishHandedOverHoldByKeyAndInTheBag(GameTestHelper helper) {
        Map<String, JsonObject> claims = new LinkedHashMap<>();
        claims.put("it comes back to me", check("item_in_inventory", "boomerang_plain"));
        claims.put("the snacks are in my bag", check("item_in_inventory", "snack_box_plain"));
        claims.put("he has a compass from this wish", check("item_in_inventory", "minecraft:compass"));
        drive(helper, "mfam-ci-items", 2, claims, Set.of("he has a compass from this wish"), null, (ctx) -> {
            ServerPlayer p = ctx.child;
            // Hotbar slots 3–9 left free for the boomerang; the second hand-over fills them first.
            give(p, made("boomerang_plain", "Boomerang", Items.TRIDENT, 1), ctx.wishKey);
            for (int i = 2; i < 9; i++) {
                if (p.getInventory().getItem(i).isEmpty()) {
                    p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
                }
            }
            var landed = give(p, made("snack_box_plain", "Snack Box", Items.BUNDLE, 1), ctx.wishKey);
            ctx.expect(landed.stream().allMatch(l -> l.where() == com.moddingfromamod.item.delivery.DeliveryWords.Where.BAG),
                    "the snack box was meant to land in the Wish Bag: " + landed);
            // Already his, never handed over by THIS wish: it is not this wish's thing.
            p.getInventory().setItem(20, new ItemStack(Items.COMPASS));
        });
    }

    /** The live: Spikor follows him 40 blocks from the spot; and 50 blocks in a radius of 40. */
    @GameTest(template = "empty_test", batch = "claims1344spikor", timeoutTicks = 1000000)
    public static void aFollowerAwayFromTheSpotAndBlocksPastTwentyFourHold(GameTestHelper helper) {
        Map<String, JsonObject> claims = new LinkedHashMap<>();
        JsonObject appears = check("creature_count", "spikor");
        appears.addProperty("near", "wish");
        appears.addProperty("radius", 16);
        claims.put("Spikor appears", appears);
        claims.put("he follows me", check("follows", "spikor"));
        JsonObject heart = check("block_present", "minecraft:red_concrete");
        heart.addProperty("min", 50);
        heart.addProperty("near", "wish");
        heart.addProperty("radius", 40);
        claims.put("a huge heart made of rainbow blocks", heart);
        // A chunk loaded this tick is not yet ENTITY-ticking, so a creature spawned in it is not
        // findable for a moment — which measures the chunk, not the check (seen once in 8 runs).
        // So the far side is loaded first and the wish waits until it ticks entities.
        drive(helper, "mfam-ci-spikor", 9, claims, Set.of(), ctx -> {
            ctx.forceLoad(ctx.spot, 48);
            return helper.getLevel().isPositionEntityTicking(ctx.spot.offset(38, 0, 0))
                    && helper.getLevel().isPositionEntityTicking(ctx.spot.offset(-30, 0, 0));
        }, (ctx) -> {
            BlockPos spot = ctx.spot;
            // He walked 40 blocks east; Spikor, set to follow, came with him.
            ctx.child.teleportTo(spot.getX() + 40.5, spot.getY(), spot.getZ() + 0.5);
            Cow spikor = helper.spawn(EntityType.COW, new BlockPos(4 + 38, ctx.relY, 4));
            spikor.setCustomName(Component.literal("Spikor"));
            PetBehaviours.apply(spikor, new PetBehaviour(true, null, null, null), ctx.child.getUUID());
            ctx.run.creatureSpawned("spikor", spikor.getUUID());
            ctx.expect(helper.getLevel().getEntity(spikor.getUUID()) != null,
                    "Spikor is not in the world 38 blocks out, so this would measure the chunk, not the check");
            ctx.cleanup.add(spikor::discard);
            // The heart's red rows: 50 blocks 28–32 blocks from the spot, outside the old cube of 24.
            for (int i = 0; i < 50; i++) {
                ctx.setBlock(spot.offset(-30, 3 + i / 25, -12 + i % 25), Blocks.RED_CONCRETE.defaultBlockState());
            }
        });
    }

    // ── must still FAIL ──────────────────────────────────────────────────────────────────────

    /** A creature dead, a creature never spawned, a fox not set to follow. */
    @GameTest(template = "empty_test", batch = "claims1344dead", timeoutTicks = 1000000)
    public static void deadAbsentAndUnfollowingCreaturesStillFail(GameTestHelper helper) {
        Map<String, JsonObject> claims = new LinkedHashMap<>();
        claims.put("the dodo is alive", check("creature_count", "dodo"));
        claims.put("a unicorn appears", check("creature_count", "unicorn"));
        claims.put("the fox follows me", check("follows", "minecraft:fox"));
        drive(helper, "mfam-ci-dead", 9, claims, claims.keySet(), null, (ctx) -> {
            Cow dodo = helper.spawn(EntityType.COW, new BlockPos(2, ctx.relY, 2));
            dodo.setCustomName(Component.literal("Dodo"));
            ctx.run.creatureSpawned("dodo", dodo.getUUID());
            dodo.setHealth(0.0F);   // dead: not alive, wherever it is
            var fox = helper.spawn(EntityType.FOX, new BlockPos(3, ctx.relY, 3));
            fox.setNoAi(true);
            ctx.run.commandSpawned(fox.getUUID(), "", "minecraft:fox");
            ctx.cleanup.add(fox::discard);
        });
    }

    /** 10 blocks when 50 were asked; a misnamed datapack; the sinkhole creepers. */
    @GameTest(template = "empty_test", batch = "claims1344sinkhole", timeoutTicks = 1000000)
    public static void tooFewBlocksAWrongPackAndFallenCreepersStillFail(GameTestHelper helper) {
        Map<String, JsonObject> claims = new LinkedHashMap<>();
        JsonObject few = check("block_present", "minecraft:crying_obsidian");
        few.addProperty("min", 50);
        few.addProperty("radius", 40);
        claims.put("a huge obsidian wall", few);
        claims.put("it sneezes fire", check("datapack_active", "breath_cone_pet_dragon_1344_nope"));
        JsonObject creepers = check("creature_count", "creeper");
        creepers.addProperty("near", "child");
        creepers.addProperty("radius", 20);
        claims.put("a sinkhole opens under the creepers", creepers);
        drive(helper, "mfam-ci-sink", 9, claims, claims.keySet(), null, (ctx) -> {
            for (int i = 0; i < 10; i++) {
                ctx.setBlock(ctx.spot.offset(i - 5, 2, -6), Blocks.CRYING_OBSIDIAN.defaultBlockState());
            }
            // Summoned by this wish, then the ground opened and they were gone (archive: "the
            // creepers seem to have vanished", "3 you had already cleared").
            for (int i = 0; i < 3; i++) {
                var creeper = helper.spawn(EntityType.CREEPER, new BlockPos(1 + i, ctx.relY, 1));
                ctx.run.commandSpawned(creeper.getUUID(), "", "minecraft:creeper");
                creeper.discard();
            }
        });
    }

    // ── loopholes, and an item handed over then thrown away ──────────────────────────────────

    /** Handed over then thrown away; an older wish's item in the bag; bow_plain ≠ spikor_plain. */
    @GameTest(template = "empty_test", batch = "claims1344loopholes", timeoutTicks = 1000000)
    public static void thrownAwayOlderWishAndNearMissKeysStillFail(GameTestHelper helper) {
        Map<String, JsonObject> claims = new LinkedHashMap<>();
        claims.put("I get a frisbee", check("item_in_inventory", "frisbee_plain"));
        claims.put("I get a magic lantern", check("item_in_inventory", "magic_lantern_plain"));
        claims.put("I get a bow", check("item_in_inventory", "bow_plain"));
        drive(helper, "mfam-ci-loop", 9, claims, claims.keySet(), null, (ctx) -> {
            ServerPlayer p = ctx.child;
            // An OLDER wish's lantern, in his Wish Bag (his hotbar is full).
            String older = UUID.randomUUID().toString();
            var bagged = give(p, made("magic_lantern_plain", "Magic Lantern", Items.LANTERN, 1), older);
            ctx.expect(!bagged.isEmpty() && bagged.get(0).where()
                            == com.moddingfromamod.item.delivery.DeliveryWords.Where.BAG,
                    "the older lantern was meant to sit in his Wish Bag: " + bagged);
            // This wish's frisbee, handed over — then thrown away.
            p.getInventory().setItem(8, ItemStack.EMPTY);
            give(p, made("frisbee_plain", "Frisbee", Items.SNOWBALL, 1), ctx.wishKey);
            ctx.expect(WishItem.wishFeature(p.getInventory().getItem(8)).equals("frisbee_plain"),
                    "the frisbee was meant to land in hotbar slot 9");
            p.getInventory().setItem(8, ItemStack.EMPTY);
            // This wish's Spikor shell — which is not a bow.
            give(p, made("spikor_plain", "Spikor", Items.TURTLE_HELMET, 1), ctx.wishKey);
        });
    }

    // ── the radius scan, timed ───────────────────────────────────────────────────────────────

    /**
     * {@code block_present} at {@link ClaimCheck#MAX_RADIUS} runs on the server thread twice per
     * check, so it must sit well inside one tick (50 ms). Timed for the three shapes of scan: a
     * block no section holds (every section skipped by its palette), the ground block (the bottom
     * sections walked), and air (every non-empty section walked — the worst it can be asked).
     */
    @GameTest(template = "empty_test", batch = "claims1344scan")
    public static void theRadiusFortyEightScanCannotStallATick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(2, 1, 2));
        // The worst case is the WHOLE cube loaded: load every chunk it touches first (the scan
        // itself never loads one), so none is skipped as unloaded and the timing covers all 97³.
        int r = ClaimCheck.MAX_RADIUS;
        for (int cx = (centre.getX() - r) >> 4; cx <= (centre.getX() + r) >> 4; cx++) {
            for (int cz = (centre.getZ() - r) >> 4; cz <= (centre.getZ() + r) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        StringBuilder took = new StringBuilder();
        long worst = 0;
        for (String what : List.of("minecraft:crying_obsidian", "minecraft:grass_block", "minecraft:air")) {
            ClaimWorld.countBlocks(level, centre, ClaimCheck.MAX_RADIUS, what);   // warm
            long best = Long.MAX_VALUE;
            ClaimWorld.BlockTally t = null;
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                t = ClaimWorld.countBlocks(level, centre, ClaimCheck.MAX_RADIUS, what);
                best = Math.min(best, System.nanoTime() - t0);
            }
            worst = Math.max(worst, best);
            took.append(String.format(java.util.Locale.ROOT, " %s=%.2fms(n=%d,unloaded=%d)",
                    what, best / 1e6, t.n(), t.unloaded()));
        }
        org.slf4j.LoggerFactory.getLogger(ClaimIdentityGameTest.class)
                .info("CLAIM-SCAN radius {} timed:{}", ClaimCheck.MAX_RADIUS, took);
        helper.assertTrue(!took.toString().matches(".*unloaded=[1-9].*"),
                Component.literal("the timed cube was not fully loaded, so this is not the worst case:" + took));
        helper.assertTrue(worst < 25_000_000L, Component.literal("a radius-48 block scan took more than half a tick:" + took));
        helper.succeed();
    }

    // ── the driver ───────────────────────────────────────────────────────────────────────────

    /** What a setup step gets: the child, the wish, its run and spot, and what to put back. */
    static final class Ctx {
        final GameTestHelper helper;
        final ServerPlayer child;
        final String wishKey;
        final WishRun run;
        final BlockPos spot;
        final int relY;
        final List<Runnable> cleanup = new ArrayList<>();
        final List<String> problems = new ArrayList<>();

        Ctx(GameTestHelper helper, ServerPlayer child, String wishKey, WishRun run, BlockPos spot, int relY) {
            this.helper = helper;
            this.child = child;
            this.wishKey = wishKey;
            this.run = run;
            this.spot = spot;
            this.relY = relY;
        }

        void expect(boolean ok, String why) {
            if (!ok) {
                problems.add(why);
            }
        }

        void setBlock(BlockPos at, net.minecraft.world.level.block.state.BlockState state) {
            ServerLevel level = helper.getLevel();
            var was = level.getBlockState(at);
            level.setBlock(at, state, 3);
            cleanup.add(() -> level.setBlock(at, was, 3));
        }

        private boolean loaded;

        void forceLoad(BlockPos around, int r) {
            if (loaded) {
                return;
            }
            loaded = true;
            ServerLevel level = helper.getLevel();
            for (int cx = (around.getX() - r) >> 4; cx <= (around.getX() + r) >> 4; cx++) {
                for (int cz = (around.getZ() - r) >> 4; cz <= (around.getZ() + r) >> 4; cz++) {
                    if (level.setChunkForced(cx, cz, true)) {
                        int fx = cx;
                        int fz = cz;
                        cleanup.add(() -> level.setChunkForced(fx, fz, false));
                    }
                    level.getChunk(cx, cz);   // NOW: a ticket alone loads it a few ticks later

                }
            }
        }
    }

    static JsonObject check(String kind, String what) {
        JsonObject c = new JsonObject();
        c.addProperty("kind", kind);
        c.addProperty("what", what);
        c.addProperty("min", 1);
        return c;
    }

    /** A made item: its wish key in custom_data, as make_item stamps it, and its display name. */
    static ItemStack made(String key, String name, net.minecraft.world.item.Item base, int count) {
        ItemStack s = new ItemStack(base, count);
        CompoundTag t = new CompoundTag();
        t.putString(WishItem.WISH_FEATURE, key);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    static List<com.moddingfromamod.item.delivery.DeliveryWords.Landed> give(ServerPlayer p, ItemStack s, String wishKey) {
        return WishDelivery.give(p, s, wishKey, com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, false);
    }

    /**
     * One wish, one real {@code finish_building} with {@code claims}: those in {@code mustFail}
     * must be named in its refusal, the rest must not; with none to fail it must not be refused.
     *
     * @param hotbarFilledThrough the child's own cobblestone in hotbar slots 3.. up to this (9 = full)
     * @param ready  polled every tick before {@code setup} runs (null: ready at once) — for a world
     *               the setup needs that takes ticks to become true, such as an entity-ticking chunk
     */
    private static void drive(GameTestHelper helper, String childName, int hotbarFilledThrough,
            Map<String, JsonObject> claims, Set<String> mustFail,
            java.util.function.Predicate<Ctx> ready, Consumer<Ctx> setup) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = WishDeliveryGameTest.child(helper, childName, hotbarFilledThrough, false);
        var server = helper.getLevel().getServer();
        long tag = System.nanoTime();
        String worker = "gt-claims1344-" + tag;
        int relY = helper.getBlockState(new BlockPos(4, 0, 4)).isAir() ? 2 : 1;
        BlockPos spot = helper.absolutePos(new BlockPos(4, relY, 4));
        child.teleportTo(spot.getX() + 2.5, spot.getY(), spot.getZ() + 2.5);
        String dim = helper.getLevel().dimension().identifier().toString();
        Wish wish = WishBook.get(server).add(Wish.create("claim identity " + childName, WishSource.TYPED,
                System.currentTimeMillis(), child.getUUID(), "Steve", dim, spot.getX(), spot.getY(), spot.getZ()));
        Marshal.global().submit(wish.id(), child.getUUID(), wish.text());
        Marshal.global().assign(worker, wish.id());
        BuildGate.startWish(wish.id());
        WishRun run = Marshal.global().runForWorker(worker);
        run.lockAnchor(new Spot(dim, spot.getX(), spot.getY(), spot.getZ()));
        WishScope.as(wish.id().toString(), () -> WishClaims.state(new ArrayList<>(claims.keySet())));

        Ctx ctx = new Ctx(helper, child, wish.id().toString(), run, spot, relY);
        AtomicReference<ToolResult> result = new AtomicReference<>();
        AtomicReference<Throwable> died = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean started = new AtomicBoolean();
        Runnable start = () -> {
            started.set(true);
            try {
                setup.accept(ctx);
            } catch (RuntimeException e) {
                died.set(e);
                done.set(true);
                return;
            }
            Thread t = new Thread(() -> {
                try {
                    AgentContext.with(worker, () -> {
                        String label = "gt_claims1344_" + tag;
                        answerTheCamera(label);
                        JsonObject args = new JsonObject();
                        args.addProperty("say", "Here it is!");
                        args.addProperty("label", label);
                        JsonArray arr = new JsonArray();
                        for (var e : claims.entrySet()) {
                            JsonObject c = new JsonObject();
                            c.addProperty("claim", e.getKey());
                            c.addProperty("works", true);
                            c.addProperty("evidence", "made it");
                            c.add("check", e.getValue());
                            arr.add(c);
                        }
                        args.add("claims", arr);
                        result.set(new FinishBuildingTool().call(args));
                        return null;
                    });
                } catch (Throwable e) {
                    died.set(e);
                } finally {
                    done.set(true);
                }
            }, "claim-identity-gametest");
            t.setDaemon(true);
            t.start();
        };
        if (ready == null) {
            start.run();
        }

        Runnable teardown = () -> {
            for (int i = ctx.cleanup.size() - 1; i >= 0; i--) {
                try {
                    ctx.cleanup.get(i).run();
                } catch (RuntimeException ignored) {
                    // best effort: the world is the test's, not the next one's
                }
            }
            ctx.cleanup.clear();
            WishDelivery.takeBack(server, wish.id().toString());
            Marshal.global().finish(wish.id());
            BuildGate.settle(wish.id());
            WishCapture.forgetForTest(child.getUUID());
            WishDeliveryGameTest.done(child);
        };
        long startedMs = System.currentTimeMillis();
        helper.succeedWhen(() -> {
            if (!started.get()) {
                if (System.currentTimeMillis() - startedMs > 20_000L) {
                    teardown.run();
                    helper.testInfo.fail(GameTestCompat.assertion("the world this test needs never became ready in 20 s"));
                    return;
                }
                if (!ready.test(ctx)) {
                    throw GameTestCompat.assertion("waiting for the world this test needs");
                }
                start.run();
            }
            if (!done.get() && System.currentTimeMillis() - startedMs > 50_000L) {
                helper.testInfo.fail(GameTestCompat.assertion("finish_building did not return in 50 s"));
                return;
            }
            helper.assertTrue(done.get(), Component.literal("waiting for finish_building"));
            try {
                helper.assertTrue(died.get() == null, Component.literal("the driver died: " + died.get()));
                helper.assertTrue(ctx.problems.isEmpty(), Component.literal("the setup was not what this test needs: " + ctx.problems));
                ToolResult r = result.get();
                String text = r == null ? "" : String.valueOf(r.text());
                if (mustFail.isEmpty()) {
                    helper.assertTrue(r != null && !r.isError(), Component.literal("every claim should hold, and it was refused: " + text));
                } else {
                    helper.assertTrue(r != null && r.isError() && text.contains("Not yet"),
                            Component.literal("claims " + mustFail + " should have been contradicted: " + text));
                    for (String claim : claims.keySet()) {
                        boolean named = text.contains("\"" + claim + "\": checked");
                        helper.assertTrue(named == mustFail.contains(claim), Component.literal((mustFail.contains(claim)
                                ? "should FAIL and HELD: \"" : "should HOLD and was CONTRADICTED: \"")
                                + claim + "\" — " + text));
                    }
                }
                helper.succeed();
            } catch (GameTestAssertException e) {
                helper.testInfo.fail(GameTestCompat.failure(e));   // the driver is done: a verdict, never "not yet"
            } finally {
                if (done.get()) {
                    teardown.run();
                }
            }
        });
    }

    /** Be the client for one photograph (see CheckedClaimsGameTest). */
    private static void answerTheCamera(String label) {
        com.moddingfromamod.testing.FakeCamera.answer(label);
    }
}
