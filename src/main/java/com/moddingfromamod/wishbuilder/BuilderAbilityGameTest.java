package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * <b>Does a Builder item DO what it says?</b> Every item the Builder offers is made through the real Builder ({@link BuilderMake}, the
 * real tools), handed to a stand-in child, and USED the way he would — the sword hits a zombie, the wand is used,
 * the cookie is eaten, the boots are worn and jumped in, the lucky block is put down and broken, and every machine
 * (the slushie and B5's 23 snacks) is put down, filled with what it takes and pulled. Each is then checked in the
 * world: a lightning bolt, a moved child, a shrunken child, a jump boost, things out of the block, the drink or
 * snack in his hand. The use mechanics are the ones {@code TriggerCorpusMatrixGameTest} and
 * {@code LoadAndPourGameTest} measured. One case after another, each named in the failure.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderAbilityGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderAbilityGameTest() {
    }

    /** One item: the spec, the name it is made under, how it is used (server thread), what proves it worked. */
    record Case(String id, String spec, String name, java.util.function.Function<Ctx, Supplier<String>> use) {
    }

    /** What a use step works with. */
    record Ctx(GameTestHelper helper, ServerPlayer p, ItemStack item, BlockPos floor, Witness w) {
    }

    /**
     * what a case must SEE happen, looked for on EVERY server tick and remembered, not only when the poller
     * looks. A bolt lives a few ticks and the fire it starts 160; a GameTest server ticks as fast as it can between
     * the make's reloads, so 160 ticks can be over in a fraction of a second while the poller (a wall-clock loop on a
     * thread a loaded box may not schedule for a while) looks every 100 ms or worse. By day the zombie's own sunburn
     * kept the evidence alive; by night the strike was over before anyone looked, and a Lightning Bow that had
     * struck read "nothing struck or burned it". The test's {@code onEachTick} drives {@link #tick}.
     */
    static final class Witness {
        private volatile java.util.function.BooleanSupplier watch;
        private volatile boolean seen;

        /** From now on, look for {@code happened} every tick (server thread). */
        void watch(java.util.function.BooleanSupplier happened) {
            seen = false;
            watch = happened;
        }

        void stop() {
            watch = null;
            seen = false;
        }

        /** Did what it watches for hold on any tick since {@link #watch}? */
        boolean seen() {
            return seen;
        }

        void tick() {
            var w = watch;
            if (w != null && !seen && w.getAsBoolean()) {
                seen = true;
            }
        }
    }

    @GameTest(template = "empty_test", batch = "builderAbility", timeoutTicks = 120000)
    public static void everyBuilderItemDoesWhatItSays(GameTestHelper helper) {
        if (!com.moddingfromamod.datapack.CbScriptCompiler.canBuildReadyMades()) {
            helper.succeed();
            return;
        }
        var p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtability");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        List<Case> cases = cases();
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] done = {false};
        Witness w = new Witness();
        helper.onEachTick(w::tick);
        Thread t = new Thread(() -> {
            long rise = BuilderMake.riseMillis;
            BuilderMake.riseMillis = 0;   // this test is about what the items DO; the pillar's rise is BuilderMakeGameTest's
            try {
                for (Case c : cases) {
                    String r = runCase(helper, server, p, c, w, null);
                    results.add(c.id() + ": " + (r == null ? "OK" : r));
                    LOGGER.info("builder ability GT: {} -> {}", c.id(), r == null ? "OK" : r);
                    if (r != null) {
                        wrong.add(c.id() + ": " + r);
                    }
                }
            } catch (Throwable e) {
                wrong.add("threw: " + e);
            } finally {
                BuilderMake.riseMillis = rise;
                done[0] = true;
            }
        }, "builder-ability-gt");
        t.setDaemon(true);
        t.start();
        // Every case has run and some failed: fail NOW, naming each. (A fail inside succeedWhen is retried until the
        // timeout, which held a finished red run for half an hour.)
        helper.onEachTick(() -> {
            if (done[0] && !wrong.isEmpty()) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion(wrong.size() + " of " + cases.size()
                        + " Builder items did not do what they say: " + wrong);
            }
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done[0], Component.literal("still at " + results.size() + " of " + cases.size()));
            helper.assertTrue(wrong.isEmpty(), Component.literal("did not do what they say: " + wrong));
        });
    }

    /**
     *, the Lightning Bow that "struck nothing" (7 of 98 runs, 2026-11-13..16): its strike is over in ticks, and
     * by NIGHT nothing keeps the evidence alive (by day the zombie's sunburn did). Staged here: midnight, a zombie that
     * outlives the strike, and the first look held back until the bolt is gone and the fire it started is out (at least
     * 200 ticks) — the poller a loaded box did not schedule in time. The test first proves the staging (at the first look there
     * is no bolt, no fire and a live zombie: what the old once-per-poll check saw), then that the case still knows the
     * bow struck. Red before the Witness: "used on a zombie, and nothing struck or burned it".
     */
    @GameTest(template = "empty_test", batch = "builderAbilityNight", timeoutTicks = 20000)
    public static void aStrikeOverBeforeTheFirstLookStillCounts(GameTestHelper helper) {
        if (!com.moddingfromamod.datapack.CbScriptCompiler.canBuildReadyMades()) {
            helper.succeed();
            return;
        }
        var level = helper.getLevel();
        var server = level.getServer();
        long dayWas = level.getDayTime();
        var cycle = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT);
        boolean cycleWas = cycle.get();
        cycle.set(false, server);
        level.setDayTime(18000);   // midnight: no sunburn to keep the zombie alight
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> {
            cycle.set(cycleWas, server);
            level.setDayTime(dayWas);
        });
        var p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtnight");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, p);
        Case bow = madeUp(BuilderItemPowers.stock("bow"), BuilderItemPowers.power("lightning"));
        String[] atFirstLook = {null};
        net.minecraft.world.entity.monster.Zombie[] target = {null};
        int[] usedAt = {Integer.MAX_VALUE};
        Case staged = new Case(bow.id(), bow.spec(), bow.name(), c -> {
            Supplier<String> proof = bow.use().apply(c);
            usedAt[0] = server.getTickCount();
            // The arrow is loosed but has not landed: the zombie it flies at outlives the bolt and the fire.
            var z = level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class,
                    c.p().getBoundingBox().inflate(10)).stream().findFirst().orElse(null);
            target[0] = z;
            if (z != null) {
                z.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE,
                        6000, 4, false, false));
            }
            return () -> {
                if (atFirstLook[0] == null) {
                    atFirstLook[0] = z == null ? "no zombie"
                            : "bolt=" + !level.getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class,
                                    z.getBoundingBox().inflate(8)).isEmpty() + " fire=" + z.isOnFire() + " alive=" + z.isAlive();
                }
                return proof.get();
            };
        });
        String[] verdict = {"still running"};
        boolean[] done = {false};
        Witness w = new Witness();
        helper.onEachTick(w::tick);
        Thread t = new Thread(() -> {
            long rise = BuilderMake.riseMillis;
            BuilderMake.riseMillis = 0;
            try {
                verdict[0] = runCase(helper, server, p, staged, w, () -> server.getTickCount() - usedAt[0] >= 200
                        && (target[0] == null || !target[0].isOnFire() && level.getEntitiesOfClass(
                                net.minecraft.world.entity.LightningBolt.class, target[0].getBoundingBox().inflate(8)).isEmpty()));
            } catch (Throwable e) {
                verdict[0] = "threw: " + e;
            } finally {
                BuilderMake.riseMillis = rise;
                done[0] = true;
            }
        }, "builder-night-gt");
        t.setDaemon(true);
        t.start();
        helper.onEachTick(() -> {
            if (!done[0]) {
                return;
            }
            if (!"bolt=false fire=false alive=true".equals(atFirstLook[0])) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion(
                        "the staging did not hold, so this proves nothing: at the first look " + atFirstLook[0]);
            }
            if (verdict[0] != null) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion(
                        "a bow that struck, judged once the strike was over: " + verdict[0]);
            }
            helper.succeed();
        });
    }

    /**
     * One case, off the server thread: make, use (on it), then wait for the proof (8 s and 160 ticks). Null = it worked.
     * {@code holdFirstLook}, when not null, holds the first look back until it is true (on the server thread): a poller
     * that was not scheduled in time, staged on purpose ({@link #aStrikeOverBeforeTheFirstLookStillCounts}).
     */
    static String runCase(GameTestHelper helper, net.minecraft.server.MinecraftServer server, ServerPlayer p, Case c,
                          Witness w, java.util.function.BooleanSupplier holdFirstLook) throws Exception {
        BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(c.spec()).getAsJsonObject());
        var who = BuilderMake.Who.of(p);
        BuilderMake.Result made = onServer(server,
                () -> BuilderMake.run(server, who, spec, null, protocol(), false, false)).get(120, TimeUnit.SECONDS)
                .get(120, TimeUnit.SECONDS);
        if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
            return "not made: " + made.outcome() + " " + made.detail();
        }
        Supplier<String> proof = BuilderAbilityGameTest.<Supplier<String>>onServer(server, () -> {
            ItemStack item = take(p, c.name());
            if (item == null) {
                return (Supplier<String>) () -> "it never reached his bag (" + carrying(p) + ")";
            }
            BlockPos floor = p.blockPosition().below().relative(Direction.SOUTH, 3);
            clear(helper, floor);
            w.stop();
            return c.use().apply(new Ctx(helper, p, item, floor, w));
        }).get(30, TimeUnit.SECONDS);
        String last = "never checked";
        long holdCap = System.currentTimeMillis() + 120_000;
        while (holdFirstLook != null && System.currentTimeMillis() < holdCap
                && !onServer(server, holdFirstLook::getAsBoolean).get(10, TimeUnit.SECONDS)) {
            Thread.sleep(20);
        }
        // 8 s of real time AND 160 server ticks (8 game seconds), whichever is later, capped at 60 s. A
        // proof is game work (an arrow's flight, a hit, the datapack's answer) as often as wall-clock work, and a
        // loaded server once used up the 8 s on a Lightning Bow before its arrow could land.
        long until = System.currentTimeMillis() + 8000;
        long cap = System.currentTimeMillis() + 60_000;
        int tick0 = server.getTickCount();
        while (System.currentTimeMillis() < cap
                && (System.currentTimeMillis() < until || server.getTickCount() - tick0 < 160)) {
            last = onServer(server, proof::get).get(10, TimeUnit.SECONDS);
            if (last == null) {
                break;
            }
            Thread.sleep(100);
        }
        onServer(server, () -> {
            w.stop();
            p.getInventory().clearContent();
            for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET}) {
                p.setItemSlot(slot, ItemStack.EMPTY);
            }
            p.removeAllEffects();
            p.getTags().removeIf(t -> t.startsWith("mfam_go_home") || t.startsWith("mfam_call_pets"));
            p.clearFire();
            var scale = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
            if (scale != null) {
                scale.removeModifiers();
                scale.setBaseValue(1.0);
            }
            p.setPos(helper.absolutePos(new BlockPos(1, 2, 1)).getCenter().add(0, -0.5, 0));
            return null;
        }).get(10, TimeUnit.SECONDS);
        return last;
    }

    // ------------------------------------------------------------------ the cases

    static List<Case> cases() {
        List<Case> out = new ArrayList<>();
        out.add(new Case("lightning sword", spec("item", "items/lightning_sword_lightning"), "Lightning Sword", c -> {
            var z = EntityType.ZOMBIE.create(c.helper().getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            z.setNoAi(true);
            z.setPos(c.p().getX(), c.p().getY(), c.p().getZ() + 2);
            c.helper().getLevel().addFreshEntity(z);
            c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
            float hp = c.p().getHealth();
            c.p().attack(z);
            // the bolt at the zombie, two blocks off, reaches him too; his own sword must never strike him.
            // Judged once the zombie WAS struck (the bolt damages everything in reach on the same tick), never on the
            // bolt merely existing, which it does a tick before it strikes.
            return () -> c.p().getHealth() < hp ? "his own lightning hurt him: " + hp + " -> " + c.p().getHealth()
                    : z.isOnFire() || !z.isAlive() || z.getHealth() < z.getMaxHealth() ? null
                    : "hit a zombie, and no lightning struck it";
        }));
        out.add(new Case("teleport wand", spec("item", "items/teleport_wand_teleport"), "Teleport Wand", c -> {
            Vec3 from = c.p().position();
            c.p().setXRot(10);
            c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
            use(c.p());
            return () -> c.p().position().distanceTo(from) > 2 ? null : "used, and he did not move";
        }));
        out.add(new Case("tiny cookie", spec("item", "items/tiny_cookie_grow"), "Tiny Cookie", c -> {
            c.p().getFoodData().setFoodLevel(2);
            c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
            use(c.p());
            invoke(c.p(), "completeUsingItem");
            return () -> c.p().getScale() < 0.9f ? null : "eaten, and he is still his own size (" + c.p().getScale() + ")";
        }));
        out.add(new Case("double jump boots", spec("item", "items/double_jump_boots_jump"), "Double Jump Boots", c -> {
            c.p().setItemSlot(EquipmentSlot.FEET, c.item());
            invoke(c.p(), "jumpFromGround");
            return () -> c.p().hasEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST) ? null
                    : "worn and jumped in, and no jump boost";
        }));
        out.add(new Case("lucky block", spec("magic_block", "items/lucky_block_outcome_table"), "Lucky Block", c -> {
            place(c.p(), c.item(), c.floor());
            // Staged, as MagicBlockGameTest does it: the block's body appears a tick later; then it is broken.
            BlockPos[] body = {null};
            int[] before = {0};
            return () -> {
                if (body[0] == null) {
                    for (BlockPos q : BlockPos.betweenClosed(c.floor().offset(-2, 1, -2), c.floor().offset(2, 3, 2))) {
                        if (c.helper().getLevel().getBlockState(q).is(Blocks.GOLD_BLOCK)) {
                            body[0] = q.immutable();
                        }
                    }
                    if (body[0] == null) {
                        return "put down, and no lucky block stands";
                    }
                    before[0] = around(c.helper(), body[0]);
                    c.helper().getLevel().destroyBlock(body[0], true);
                    return "broken; waiting for what comes out";
                }
                return around(c.helper(), body[0]) > before[0] ? null : "put down and broken, and nothing came out";
            };
        }));
        // every magic block the Builder offers: each (trigger, effect) pair, put down and set off the way it is.
        out.addAll(blockCases());
        // every made-up item the Builder offers. Every (trigger, power) pair is made on a stock item with
        // that trigger and USED the way that item is used; the stock items of a trigger take turns, so every stock
        // item is made and used at least once (docs/EFFECT_VALIDATION.md: the GameTest proves the mechanism).
        out.addAll(madeUpCases());
        // Every machine: the slushie and B5's snacks. Put down, fill with what it takes, pull until it pours.
        out.add(machine("slushie machine", "items/slushie_machine_load_and_pour", "Slushie Machine"));
        for (String m : Catalogue.presets()) {
            if (m.startsWith("items/") && !m.equals("items/slushie_machine_load_and_pour")) {
                JsonObject j = LibraryPresets.SHIPPED.read(m);
                if (j != null && "machine".equals(str(j, "shape"))) {
                    out.add(machine(m.substring("items/".length()), m, str(j, "name")));
                }
            }
        }
        return out;
    }

    /**
     * "Turn things into…", made through the real Builder from its From and To picks and USED: a wand pointed at
     * grass turns it to gold blocks; a wand of ores into what a furnace makes, aimed at the air with raw iron in his bag,
     * hands him iron; stone into gravel pointed at cobblestone; two From groups at once; a sword that hits a zombie on
     * the grass; a snowball that lands on sand.
     */
    @GameTest(template = "empty_test", batch = "builderTurnInto", timeoutTicks = 60000)
    public static void turnIntoPicksDoWhatTheySay(GameTestHelper helper) {
        if (!com.moddingfromamod.datapack.CbScriptCompiler.canBuildReadyMades()) {
            helper.succeed();
            return;
        }
        var p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtturn");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        List<Case> cases = turnIntoCases();
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] done = {false};
        Witness w = new Witness();
        helper.onEachTick(w::tick);
        Thread t = new Thread(() -> {
            long rise = BuilderMake.riseMillis;
            BuilderMake.riseMillis = 0;
            try {
                for (Case c : cases) {
                    String r = runCase(helper, server, p, c, w, null);
                    LOGGER.info("builder turn-into GT: {} -> {}", c.id(), r == null ? "OK" : r);
                    if (r != null) {
                        wrong.add(c.id() + ": " + r);
                    }
                }
            } catch (Throwable e) {
                wrong.add("threw: " + e);
            } finally {
                BuilderMake.riseMillis = rise;
                done[0] = true;
            }
        }, "builder-turn-gt");
        t.setDaemon(true);
        t.start();
        helper.onEachTick(() -> {
            if (done[0] && !wrong.isEmpty()) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion(wrong.size() + " of " + cases.size()
                        + " turn-into items did not do what they say: " + wrong);
            }
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done[0], Component.literal("still running"));
            helper.assertTrue(wrong.isEmpty(), Component.literal("did not do what they say: " + wrong));
        });
    }

    static List<Case> turnIntoCases() {
        List<Case> out = new ArrayList<>();
        out.add(turnWall("wand", "any_block", "gold", "minecraft:grass_block", "minecraft:gold_block"));
        out.add(turnBag("wand", "ores", "furnace", "minecraft:raw_iron", "minecraft:iron_ingot"));
        out.add(turnWall("staff", "stone", "gravel", "minecraft:cobblestone", "minecraft:gravel"));
        out.add(turnWall("wand", "dirt_grass,sand", "glass", "minecraft:sand", "minecraft:glass"));
        out.add(turnBag("staff", "dirt_grass,sand", "glass", "minecraft:dirt", "minecraft:glass"));
        out.add(turnHit("sword", "dirt_grass", "planks", "minecraft:oak_planks"));
        return out;
    }

    static String turnSpec(String stock, String from, String to, String name) {
        BuilderItemPowers.Stock st = BuilderItemPowers.stock(stock);
        return "{\"builder\":1,\"kind\":\"item\",\"from\":\"\",\"name\":\"" + name + "\",\"opts\":{"
                + "\"stock\":\"" + stock + "\",\"shape\":\"shape:" + st.shape() + "\",\"effect\":\"effect:"
                + com.moddingfromamod.datapack.TurnInto.EFFECT + "\",\"power\":\"" + BuilderTurnInto.POWER + "\",\""
                + BuilderTurnInto.FROM_SLOT + "\":\"" + from + "\",\"" + BuilderTurnInto.TO_SLOT + "\":\"" + to
                + "\",\"strength\":\"2\"}}";
    }

    /** Held, pointed at a wall of {@code wallBlock} two blocks ahead: some of it becomes {@code becomes}. */
    static Case turnWall(String stock, String from, String to, String wallBlock, String becomes) {
        String name = BuilderItemPowers.name(stock, BuilderTurnInto.POWER, to);
        return new Case("turn into: " + stock + " " + from + " -> " + to + " (pointed at " + wallBlock + ")",
                turnSpec(stock, from, to, name), name, c -> {
                    var level = c.helper().getLevel();
                    BlockPos base = c.p().blockPosition().relative(Direction.SOUTH, 2);
                    for (BlockPos q : BlockPos.betweenClosed(base.offset(-1, 0, 0), base.offset(1, 2, 0))) {
                        level.setBlockAndUpdate(q, block(wallBlock).defaultBlockState());
                    }
                    c.p().snapTo(c.p().getX(), c.p().getY(), c.p().getZ(), 0.0F, 10.0F);
                    c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
                    use(c.p());
                    return () -> BlockPos.betweenClosedStream(base.offset(-1, 0, 0), base.offset(1, 2, 0))
                            .anyMatch(q -> level.getBlockState(q).is(block(becomes))) ? null
                            : "pointed at " + wallBlock + ", and none of it became " + becomes;
                });
    }

    /** Held, aimed at the open sky with two {@code carried} in his bag: one becomes {@code becomes}. */
    static Case turnBag(String stock, String from, String to, String carried, String becomes) {
        String name = BuilderItemPowers.name(stock, BuilderTurnInto.POWER, to);
        return new Case("turn into: " + stock + " " + from + " -> " + to + " (air, " + carried + " in his bag)",
                turnSpec(stock, from, to, name), name, c -> {
                    c.p().getInventory().setItem(1, new ItemStack(item(carried), 2));   // a set slot, never Inventory.add
                    c.p().snapTo(c.p().getX(), c.p().getY(), c.p().getZ(), 0.0F, -85.0F);   // straight up: nothing to point at
                    c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
                    use(c.p());
                    return () -> c.p().getInventory().countItem(item(becomes)) > 0
                            && c.p().getInventory().countItem(item(carried)) == 1 ? null
                            : "used at the air with " + carried + " in his bag, and no " + becomes + " (" + carrying(c.p()) + ")";
                });
    }

    /** Swung at a zombie standing on {@code from}'s ground: the ground round it becomes {@code becomes}. */
    static Case turnHit(String stock, String from, String to, String becomes) {
        String name = BuilderItemPowers.name(stock, BuilderTurnInto.POWER, to);
        return new Case("turn into: " + stock + " " + from + " -> " + to + " (hit a zombie on grass)",
                turnSpec(stock, from, to, name), name, c -> {
                    var level = c.helper().getLevel();
                    var z = zombie(c, 2);
                    BlockPos under = z.blockPosition().below();
                    for (BlockPos q : BlockPos.betweenClosed(under.offset(-1, 0, -1), under.offset(1, 0, 1))) {
                        level.setBlockAndUpdate(q, Blocks.GRASS_BLOCK.defaultBlockState());
                    }
                    c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
                    c.p().attack(z);
                    return () -> BlockPos.betweenClosedStream(under.offset(-2, -1, -2), under.offset(2, 1, 2))
                            .anyMatch(q -> level.getBlockState(q).is(block(becomes))) ? null
                            : "hit a zombie on grass, and none of the ground became " + becomes;
                });
    }

    /** Every (trigger, power) once, on the stock items of that trigger in turn; every stock item at least once. */
    static List<Case> madeUpCases() {
        List<Case> out = new ArrayList<>();
        java.util.Map<String, List<BuilderItemPowers.Stock>> byTrigger = new java.util.LinkedHashMap<>();
        for (BuilderItemPowers.Stock s : BuilderItemPowers.STOCK) {
            byTrigger.computeIfAbsent(s.shape(), k -> new ArrayList<>()).add(s);
        }
        byTrigger.forEach((trigger, stocks) -> {
            java.util.LinkedHashSet<BuilderItemPowers.Power> powers = new java.util.LinkedHashSet<>();
            stocks.forEach(s -> powers.addAll(BuilderItemPowers.fitting(s)));
            java.util.Set<String> used = new java.util.HashSet<>();
            int i = 0;
            for (BuilderItemPowers.Power w : powers) {
                if (w.id().equals(BuilderTurnInto.POWER)) {
                    continue;   //: its picks are the From and To pages: turnIntoCases(), its own test below
                }
                if (w.effect().isEmpty()) {
                    continue;   //: "Just for fun" does nothing to prove; BuilderLooksGameTest makes one
                }
                for (int k = 0; k < stocks.size(); k++) {
                    BuilderItemPowers.Stock s = stocks.get((i + k) % stocks.size());
                    if (w.fits(s)) {
                        out.add(madeUp(s, w));
                        used.add(s.id());
                        break;
                    }
                }
                i++;
            }
            for (BuilderItemPowers.Stock s : stocks) {
                if (!used.contains(s.id())) {
                    out.add(madeUp(s, BuilderItemPowers.fitting(s).get(0)));
                }
            }
        });
        return out;
    }

    /** A made-up item: made from its stock item and power at strength 2, used as that item is used, then proven. */
    static Case madeUp(BuilderItemPowers.Stock stock, BuilderItemPowers.Power w) {
        String name = BuilderItemPowers.name(stock.id(), w.id());
        String shape = stock.shape();
        // A power with sizes (the slushie) arrives as "Small Slushie", "Medium Slushie"…: find it by the power's word.
        String finds = w.extra().contains("variants") ? w.word() : name;
        String spec = "{\"builder\":1,\"kind\":\"item\",\"from\":\"\",\"name\":\"" + name + "\",\"opts\":{"
                + "\"stock\":\"" + stock.id() + "\",\"shape\":\"shape:" + shape + "\",\"effect\":\"effect:" + w.effect()
                + "\",\"power\":\"" + w.id() + "\",\"strength\":\"2\"}}";
        return new Case("made up: " + name + " (" + shape + ")", spec, finds, c -> {
            var level = c.helper().getLevel();
            net.minecraft.world.entity.monster.Zombie[] z = {null};
            Vec3 from = c.p().position();
            BlockPos[] mined = {null};
            int[] had = {0};
            switch (shape) {
                case "on_hit" -> {
                    z[0] = zombie(c, 2);
                    c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
                    c.p().attack(z[0]);
                }
                case "arrow", "on_throw_land" -> {
                    // Ranged: a zombie well past where a bolt would reach him (spare_players holds a bolt off within 4).
                    z[0] = zombie(c, 7);
                    c.p().snapTo(c.p().getX(), c.p().getY(), c.p().getZ(), 0.0F, -2.0F);
                    c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
                    c.p().getInventory().setItem(1, new ItemStack(net.minecraft.world.item.Items.ARROW, 8));
                    shoot(c);
                }
                case "on_mine" -> {
                    BlockPos rock = c.p().blockPosition().relative(Direction.SOUTH, 2);
                    level.setBlockAndUpdate(rock, (stock.base().contains("shovel") ? Blocks.DIRT : Blocks.STONE).defaultBlockState());
                    c.p().snapTo(rock.getX() + 0.5, rock.getY(), rock.getZ() - 1.5, 0.0F, 30.0F);
                    c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
                    mined[0] = rock;
                    if (!c.p().gameMode.destroyBlock(rock)) {
                        return () -> "it cannot break the block in front of him";
                    }
                }
                case "held" -> {
                    if (w.id().equals("pets")) {
                        // His own wolf, sitting 12 blocks off: the wand must bring it (CallPets moves an OWNED pet).
                        var wolf = EntityType.WOLF.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                        wolf.setPos(c.p().getX() + 12, c.p().getY(), c.p().getZ());
                        wolf.tame(c.p());
                        wolf.setOrderedToSit(true);
                        wolf.addTag("gt_pet");
                        level.addFreshEntity(wolf);
                    }
                    if (w.effect().equals("place")) {
                        had[0] = count(c.helper(), c.p().blockPosition(), block(w.params(2).get("BLOCK")));   // the control
                    }
                    c.p().setXRot(10);
                    c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
                    use(c.p());
                }
                case "worn_head", "worn_chest", "worn_legs", "worn_feet" -> {
                    c.p().setItemSlot(switch (shape) {
                        case "worn_head" -> EquipmentSlot.HEAD;
                        case "worn_chest" -> EquipmentSlot.CHEST;
                        case "worn_legs" -> EquipmentSlot.LEGS;
                        default -> EquipmentSlot.FEET;
                    }, c.item());
                    invoke(c.p(), "jumpFromGround");
                }
                case "on_consume" -> {
                    c.p().getFoodData().setFoodLevel(2);
                    c.p().setItemInHand(InteractionHand.MAIN_HAND, c.item());
                    use(c.p());
                    invoke(c.p(), "completeUsingItem");
                }
                default -> throw new IllegalStateException("no way to use a " + shape);
            }
            var p = c.p();
            // what happens to the zombie is over in ticks (a bolt lives a few, its fire 160, a freeze or a
            // fling's slow fall not much longer), so it is looked for on every tick (Witness), not only when polled.
            java.util.function.BooleanSupplier happened = switch (w.id()) {
                case "lightning", "fire" -> () -> !level.getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class,
                        z[0].getBoundingBox().inflate(8)).isEmpty() || z[0].isOnFire() || !z[0].isAlive();
                case "freeze" -> () -> z[0].getTicksFrozen() > 0
                        || z[0].hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS);
                case "fling" -> () -> z[0].getY() > c.floor().getY() + 2.5 || z[0].getDeltaMovement().y > 0.1
                        || z[0].hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING);
                default -> null;
            };
            if (happened != null) {
                c.w().watch(happened);
            }
            return () -> switch (w.id()) {
                case "lightning", "fire" -> c.w().seen() || happened.getAsBoolean() ? null
                        : "used on a zombie, and nothing struck or burned it";
                case "freeze" -> c.w().seen() || happened.getAsBoolean() ? null : "used on a zombie, and it is not frozen";
                case "fling" -> c.w().seen() || happened.getAsBoolean() ? null : "used on a zombie, and it did not fly up";
                case "teleport" -> p.position().distanceTo(from) > 2 ? null : "used, and he did not move";
                case "speed" -> p.hasEffect(net.minecraft.world.effect.MobEffects.SPEED) ? null : "no speed on him";
                case "haste" -> p.hasEffect(net.minecraft.world.effect.MobEffects.HASTE) ? null : "no fast digging on him";
                case "jump" -> p.hasEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST) ? null : "no jump boost on him";
                case "heal" -> p.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION) ? null : "no healing on him";
                case "giant" -> p.getScale() > 1.1f ? null : "he did not grow (" + p.getScale() + ")";
                case "tiny" -> p.getScale() < 0.9f ? null : "he did not shrink (" + p.getScale() + ")";
                case "shield" -> p.hasEffect(net.minecraft.world.effect.MobEffects.RESISTANCE) ? null : "no protection on him";
                case "fly" -> p.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION) ? null : "he is not lifting off";
                case "slushie" -> p.hasEffect(net.minecraft.world.effect.MobEffects.SPEED) ? null : "drunk, and he is not quick";
                case "glow" -> BlockPos.betweenClosedStream(p.blockPosition().offset(-8, -3, -8), p.blockPosition().offset(8, 4, 8))
                        .anyMatch(q -> level.getBlockState(q).is(Blocks.LIGHT)) ? null
                        : mined[0] != null ? "mined, and no light where he mined" : "no light where he pointed";
                case "home" -> p.getTags().contains("mfam_go_home") || p.position().distanceTo(from) > 2 ? null
                        : "used, and he is not on his way home";
                case "pets" -> level.getEntitiesOfClass(net.minecraft.world.entity.animal.wolf.Wolf.class,
                        p.getBoundingBox().inflate(32), wf -> wf.getTags().contains("gt_pet")).stream()
                        .anyMatch(wf -> wf.distanceTo(p) < 5) ? null : "used, and his wolf did not come";
                // the block put down where he pointed (and taken away again, so water or lava does not run into
                // the next case); the thing he carried turned into the other; the block he pointed at changed.
                default -> switch (w.effect()) {
                    case "place" -> placed(c.helper(), p.blockPosition(), block(w.params(2).get("BLOCK")), had[0])
                            ? null : "used, and no " + w.params(2).get("BLOCK") + " where he pointed";
                    default -> "no check written for " + w.id();
                };
            };
        });
    }

    /** how many {@code b} stand within 8 blocks of {@code at}, at his feet and above (the floor is below). */
    static int count(GameTestHelper helper, BlockPos at, net.minecraft.world.level.block.Block b) {
        var level = helper.getLevel();
        return (int) BlockPos.betweenClosedStream(at.offset(-8, 0, -8), at.offset(8, 4, 8))
                .filter(q -> level.getBlockState(q).is(b)).count();
    }

    /** a vanilla block or item by id. */
    static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.ResourceLocation.parse(id));
    }

    static net.minecraft.world.item.Item item(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.ResourceLocation.parse(id));
    }

    /**
     * is there a {@code b} within 8 blocks of {@code at}? When there is, every fluid there is taken away again, so
     * water or lava a Place wand put down cannot run into the next case.
     */
    static boolean placed(GameTestHelper helper, BlockPos at, net.minecraft.world.level.block.Block b, int before) {
        var level = helper.getLevel();
        boolean found = count(helper, at, b) > before;
        if (found) {
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-12, -3, -12), at.offset(12, 5, 12))) {
                if (!level.getFluidState(q).isEmpty() || level.getBlockState(q).is(b) && q.getY() >= at.getY()) {
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        return found;
    }

    /**
     * every (trigger, effect) pair a magic block can be composed of, once, each on a different look (never
     * stone, which is the floor), at strength 2: put down, set off the way its trigger is (broken, stood on, someone
     * near, or simply waited for), and checked in the world (docs/EFFECT_VALIDATION.md: the GameTest proves the
     * mechanism). A monster that must be frozen or struck stands well clear of him: a bolt holds off within 4 of a player.
     */
    static List<Case> blockCases() {
        List<Case> out = new ArrayList<>();
        List<BuilderBlocks.Look> looks = BuilderBlocks.LOOKS.stream().filter(l -> !l.id().equals("stone")).toList();
        int i = 0;
        for (BuilderBlocks.Does d : BuilderBlocks.DOES) {
            for (String w : d.whens()) {
                out.add(block(looks.get(i++ % looks.size()), w, d));
            }
        }
        return out;
    }

    static Case block(BuilderBlocks.Look look, String when, BuilderBlocks.Does d) {
        String name = BuilderBlocks.name(look, d);
        com.google.gson.JsonObject spec = com.google.gson.JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"magic_block\",\"from\":\"\"}").getAsJsonObject();
        spec.addProperty("name", name);
        com.google.gson.JsonObject opts = new com.google.gson.JsonObject();
        BuilderBlocks.opts(look.id(), when, d.id(), 2).forEach(opts::addProperty);
        spec.add("opts", opts);
        net.minecraft.world.level.block.Block body = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.ResourceLocation.parse(look.block()));
        return new Case("block: " + name + " (" + when + ")", spec.toString(), name, c -> {
            var level = c.helper().getLevel();
            var p = c.p();
            p.setHealth(10);
            place(p, c.item(), c.floor());
            LivingEntity[] monster = {null};
            if (d.dials().containsKey("who")) {
                // A husk (it does not burn by day) two blocks past the block: inside its reach, 5+ from him.
                var h = EntityType.HUSK.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                h.setNoAi(true);
                h.setPersistenceRequired();
                h.setPos(c.floor().getX() + 0.5, c.floor().getY() + 1, c.floor().getZ() + 2.5);
                level.addFreshEntity(h);
                monster[0] = h;
            }
            float monsterHp = monster[0] == null ? 0 : monster[0].getHealth();
            BlockPos[] at = {null};
            int[] before = {0};
            boolean[] set = {false};
            return () -> {
                if (at[0] == null) {
                    for (BlockPos q : BlockPos.betweenClosed(c.floor().offset(-2, 1, -2), c.floor().offset(2, 3, 2))) {
                        if (level.getBlockState(q).is(body)) {
                            at[0] = q.immutable();
                        }
                    }
                    if (at[0] == null) {
                        return "put down, and no " + look.label() + " block stands";
                    }
                    before[0] = d.id().equals("chickens")
                            ? level.getEntitiesOfClass(net.minecraft.world.entity.animal.Chicken.class,
                                    new net.minecraft.world.phys.AABB(at[0]).inflate(6)).size()
                            : around(c.helper(), at[0]);
                }
                if (!set[0]) {
                    set[0] = true;
                    switch (when) {
                        case "broken" -> level.destroyBlock(at[0], true);
                        case "stepped_on" -> p.teleportTo(at[0].getX() + 0.5, at[0].getY() + 1, at[0].getZ() + 0.5);
                        default -> {
                            // near: he is 3 blocks off, inside its reach (and so is the monster); every: it goes on its own
                        }
                    }
                    return "set off (" + when + "); waiting";
                }
                return switch (d.id()) {
                    case "lucky" -> around(c.helper(), at[0]) > before[0] ? null : "broken, and nothing came out";
                    case "chickens" -> level.getEntitiesOfClass(net.minecraft.world.entity.animal.Chicken.class,
                            new net.minecraft.world.phys.AABB(at[0]).inflate(6)).size() > before[0] ? null : "no chicken popped out";
                    case "heal" -> p.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION) || p.getHealth() > 10 ? null
                            : "no healing on him";
                    case "speed" -> p.hasEffect(net.minecraft.world.effect.MobEffects.SPEED) ? null : "no speed on him";
                    case "jump" -> p.hasEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST) ? null : "no jump boost on him";
                    case "shield" -> p.hasEffect(net.minecraft.world.effect.MobEffects.RESISTANCE) ? null : "no protection on him";
                    // A light BLOCK, not the glowing effect: glowing alone passed while the block lit nothing (an earlier issue's film).
                    case "glow" -> BlockPos.betweenClosedStream(at[0].offset(-8, -3, -8), at[0].offset(8, 5, 8))
                            .anyMatch(q -> level.getBlockState(q).is(Blocks.LIGHT)) ? null : "no light about it";
                    case "freeze" -> monster[0].getTicksFrozen() > 0 || monster[0].hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS)
                            ? null : "the husk by it is not frozen";
                    case "zap" -> !level.getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class,
                            monster[0].getBoundingBox().inflate(4)).isEmpty() || !monster[0].isAlive() || monster[0].getHealth() < monsterHp
                            ? null : "nothing struck the husk by it";
                    default -> "no check written for " + d.id();
                };
            };
        });
    }

    /** A still zombie {@code ahead} blocks south of him (he faces south). */
    static net.minecraft.world.entity.monster.Zombie zombie(Ctx c, int ahead) {
        var z = EntityType.ZOMBIE.create(c.helper().getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        z.setNoAi(true);
        z.setPos(c.p().getX(), c.p().getY(), c.p().getZ() + ahead);
        c.helper().getLevel().addFreshEntity(z);
        return z;
    }

    /**
     * Shoot or throw what is in his hand, the way he does it: a bow drawn, a crossbow loaded then fired, a throw. A
     * trident is THROWN: its releaseUsing spawns a ThrownTrident, and the power happens where that lands (review).
     */
    static void shoot(Ctx c) {
        var p = c.p();
        var level = c.helper().getLevel();
        ItemStack held = p.getMainHandItem();
        if (held.getItem() instanceof net.minecraft.world.item.CrossbowItem) {
            use(p);
            // 1.21.5+ loads a crossbow in onUseTick once it is fully drawn (PropertyRideGameTest).
            held.getItem().onUseTick(level, p, held, held.getUseDuration(p)
                    - net.minecraft.world.item.CrossbowItem.getChargeDuration(held, p));
            held.releaseUsing(level, p, 0);
            p.stopUsingItem();
            use(p);
        } else if (held.getItem() instanceof net.minecraft.world.item.BowItem
                || held.getItem() instanceof net.minecraft.world.item.TridentItem) {
            use(p);
            held.releaseUsing(level, p, held.getUseDuration(p) - 30);
            p.stopUsingItem();
        } else {
            use(p);   // a snowball: thrown on the click
        }
    }

    static String spec(String kind, String preset) {
        return "{\"builder\":1,\"kind\":\"" + kind + "\",\"from\":\"preset:" + preset + "\"}";
    }

    /** A machine: put it down, hold what it takes, pull the lever (twice for one you fill then pour). */
    static Case machine(String id, String preset, String name) {
        return new Case(id, spec("machine", preset), name, c -> {
            JsonObject j = LibraryPresets.SHIPPED.read(preset);
            JsonObject params = j.getAsJsonObject("params");
            String input = params.has("FROM") ? params.get("FROM").getAsString()     //: turn_into
                    : params.has("INPUT") ? params.get("INPUT").getAsString()
                    : params.has("INGREDIENTS") ? params.get("INGREDIENTS").getAsString().split(",")[0].strip() : "";
            String output = params.has("TO") ? params.get("TO").getAsString()
                    : params.has("OUTPUT") ? params.get("OUTPUT").getAsString() : "";
            JsonObject food = output.matches("[a-z0-9_]+") ? LibraryPresets.SHIPPED.read("outputs/" + output) : null;
            if (food == null && output.matches("[a-z0-9_]+")) {
                food = LibraryPresets.SHIPPED.read("items/" + output);   // the slushie's drink is a starter item
            }
            String makes = food != null ? str(food, "name")
                    : new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                            net.minecraft.resources.ResourceLocation.parse(output))).getHoverName().getString();
            place(c.p(), c.item(), c.floor());
            var in = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                    net.minecraft.resources.ResourceLocation.parse(input));
            // Staged: the machine builds itself a tick later (LoadAndPourGameTest waits for its lever too).
            BlockPos[] lever = {null};
            int[] pulls = {0};
            return () -> {
                if (lever[0] == null) {
                    lever[0] = findLever(c.helper(), c.floor().above());
                    return lever[0] == null ? "put down, and no lever (yet)" : "lever found";
                }
                if (pulls[0] < 3) {   // fill with what it takes, then pull empty-handed (a load-and-pour pours on that)
                    c.p().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(in, 3));
                    pull(c.p(), lever[0]);
                    c.p().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    pull(c.p(), lever[0]);
                    pulls[0]++;
                }
                return holds(c.p(), makes) || near(c.helper(), lever[0], makes) ? null
                        : "filled with " + input + " and pulled, and no " + makes + " came (" + carrying(c.p()) + ")";
            };
        });
    }

    // ------------------------------------------------------------------ helpers

    static <T> CompletableFuture<T> onServer(net.minecraft.server.MinecraftServer server, Callable<T> c) {
        CompletableFuture<T> f = new CompletableFuture<>();
        server.execute(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    /** The stack in his bag named {@code name}, taken out of it; null when there is none. */
    static ItemStack take(ServerPlayer p, String name) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getHoverName().getString().contains(name)) {
                inv.setItem(i, ItemStack.EMPTY);
                return s;
            }
        }
        return null;
    }

    static boolean holds(ServerPlayer p, String name) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty() && inv.getItem(i).getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
                    .contains(name.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String carrying(ServerPlayer p) {
        StringBuilder b = new StringBuilder();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) {
                b.append(inv.getItem(i).getCount()).append(' ').append(inv.getItem(i).getHoverName().getString()).append('|');
            }
        }
        return b.length() == 0 ? "nothing" : b.toString();
    }

    static void use(ServerPlayer p) {
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    static void invoke(LivingEntity who, String method) {
        for (Class<?> k = who.getClass(); k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Method m = k.getDeclaredMethod(method);
                m.setAccessible(true);
                m.invoke(who);
                return;
            } catch (NoSuchMethodException e) {
                // up the hierarchy
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot call " + method + ": " + e);
            }
        }
        throw new IllegalStateException("no method " + method);
    }

    static void place(ServerPlayer p, ItemStack it, BlockPos floor) {
        p.setItemInHand(InteractionHand.MAIN_HAND, it.copy());
        p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
    }

    static void pull(ServerPlayer p, BlockPos lever) {
        p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(lever), Direction.UP, lever, false));
    }

    static BlockPos findLever(GameTestHelper helper, BlockPos around) {
        for (BlockPos q : BlockPos.betweenClosed(around.offset(-2, -1, -2), around.offset(2, 3, 2))) {
            if (helper.getLevel().getBlockState(q).is(Blocks.LEVER)) {
                return q.immutable();
            }
        }
        return null;
    }

    /** Clear a 7×5×7 space above a stone floor where a thing is put down. */
    static void clear(GameTestHelper helper, BlockPos floor) {
        for (BlockPos q : BlockPos.betweenClosed(floor.offset(-3, 0, -3), floor.offset(3, 5, 3))) {
            helper.getLevel().setBlockAndUpdate(q, q.getY() == floor.getY() ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState());
        }
        for (var e : helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                new net.minecraft.world.phys.AABB(floor).inflate(8), e -> !(e instanceof ServerPlayer))) {
            e.discard();
        }
    }

    /** A dropped item, or one in a container, named {@code name} within 4 blocks of {@code at}. */
    static boolean near(GameTestHelper helper, BlockPos at, String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        for (var e : helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(at).inflate(4))) {
            if (e.getItem().getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(n)) {
                return true;
            }
        }
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-3, -2, -3), at.offset(3, 3, 3))) {
            if (helper.getLevel().getBlockEntity(q) instanceof net.minecraft.world.Container box) {
                for (int i = 0; i < box.getContainerSize(); i++) {
                    if (box.getItem(i).getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(n)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Items, mobs and changed blocks around a spot: something came out of it. */
    static int around(GameTestHelper helper, BlockPos at) {
        // Presents (dropped items) and surprises (creatures) only: a magic block keeps a marker entity that goes
        // when it is broken, so counting every entity let a present that replaced it look like nothing.
        return helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                new net.minecraft.world.phys.AABB(at).inflate(6), e -> e instanceof net.minecraft.world.entity.item.ItemEntity
                        || (e instanceof net.minecraft.world.entity.Mob && !(e instanceof ServerPlayer))).size();
    }

    static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static volatile McpProtocol protocol;

    static McpProtocol protocol() {
        if (protocol == null) {
            synchronized (BuilderAbilityGameTest.class) {
                if (protocol == null) {
                    protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
            }
        }
        return protocol;
    }
}
