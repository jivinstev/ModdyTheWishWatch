package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * <b>Every special move a Builder creature can have DOES what its tile says</b>. A small cat is made
 * through the real Builder with one move, monsters are stood round it, and the world is watched: zombies burn
 * or slow, flowers or snow are left where it walks, treats and honey are dropped, crystal spikes come up,
 * monsters are knocked away. Each move gets up to 25 s of real time, because each is on its own clock.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderCreatureAbilityGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderCreatureAbilityGameTest() {
    }

    @GameTest(template = "empty_test", batch = "builderCreatureAbility", timeoutTicks = 120000)
    public static void everySpecialMoveDoesWhatItsTileSays(GameTestHelper helper) {
        if (!com.moddingfromamod.datapack.CbScriptCompiler.canBuildReadyMades()) {
            helper.succeed();
            return;
        }
        var p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtmoves");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.setYRot(0);
        p.setXRot(25);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] done = {false};
        Thread t = new Thread(() -> {
            long rise = BuilderMake.riseMillis;
            BuilderMake.riseMillis = 0;
            try {
                for (BuilderAbilities.Ability a : BuilderAbilities.ALL) {
                    String r = runOne(helper, server, p, a);
                    results.add(a.id() + ": " + (r == null ? "OK" : r));
                    LOGGER.info("builder creature ability GT: {} -> {}", a.id(), r == null ? "OK" : r);
                    if (r != null) {
                        wrong.add(a.id() + ": " + r);
                    }
                }
            } catch (Throwable e) {
                wrong.add("threw: " + e);
            } finally {
                BuilderMake.riseMillis = rise;
                done[0] = true;
            }
        }, "builder-moves-gt");
        t.setDaemon(true);
        t.start();
        helper.onEachTick(() -> {
            if (done[0] && !wrong.isEmpty()) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion(wrong.size() + " of "
                        + BuilderAbilities.ALL.size() + " special moves did nothing: " + wrong);
            }
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done[0], Component.literal("still at " + results.size() + " of " + BuilderAbilities.ALL.size()));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("; ", wrong)));
        });
    }

    static String runOne(GameTestHelper helper, net.minecraft.server.MinecraftServer server, ServerPlayer p,
                         BuilderAbilities.Ability a) throws Exception {
        String json = "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":1,\"name\":\"Mover\","
                + "\"powers\":[\"ability:" + a.id() + "\"]}";
        BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(json).getAsJsonObject());
        BuilderMake.Result made = BuilderAbilityGameTest.onServer(server,
                () -> BuilderMake.run(server, BuilderMake.Who.of(p), spec, null, BuilderAbilityGameTest.protocol(), false, false))
                .get(120, TimeUnit.SECONDS).get(120, TimeUnit.SECONDS);
        if (made.outcome() != BuilderMake.Outcome.MADE) {
            return "not made: " + made.outcome() + " " + made.detail() + " " + made.calls();
        }
        String id = made.madeId();
        ServerLevel level = helper.getLevel();
        Supplier<String> proof = BuilderAbilityGameTest.<Supplier<String>>onServer(server, () -> {
            Entity body = creature(level, p, id);
            if (body == null) {
                return (Supplier<String>) () -> "no '" + id + "' to watch";
            }
            BlockPos c = body.blockPosition();
            List<Zombie> zs = new ArrayList<>();
            int[][] around = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}};
            for (int[] d : around) {
                Zombie z = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                z.setNoAi(true);
                z.setPersistenceRequired();
                // A helmet, or it burns in daylight: a full Gate B run once found all four dead before the
                // knock_away cat reached them ("zombie 7.9 blocks from it (dead)"), and a dead zombie is never
                // pushed. Whether it is day depends on which test last moved the world's clock.
                z.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
                z.setPos(c.getX() + d[0] + 0.5, c.getY(), c.getZ() + d[1] + 0.5);
                z.addTag("gt_moves");
                level.addFreshEntity(z);
                zs.add(z);
            }
            // Where each zombie stood when it was put down, and the farthest it has been pushed since. A
            // NoAI zombie stays where a push leaves it, but the farthest is kept anyway so one poll that
            // lands between the slices of a ramped push can never read as "nothing happened".
            net.minecraft.world.phys.Vec3[] start = zs.stream().map(Entity::position).toArray(net.minecraft.world.phys.Vec3[]::new);
            double[] farthest = new double[zs.size()];
            int[] step = {0};
            return (Supplier<String>) () -> {
                Entity b = creature(level, p, id);
                if (b == null) {
                    return "it is gone";
                }
                // A walker leaves its trail as it covers ground: walk it, a block at a time.
                if (a.effect().equals("trail") && step[0]++ % 5 == 0) {
                    b.teleportTo(b.getX() + 1, b.getY(), b.getZ());
                }
                AABB box = b.getBoundingBox().inflate(8);
                return switch (a.id()) {
                    case "fire_breath" -> zs.stream().anyMatch(z -> z.isOnFire() || !z.isAlive()) ? null : "no zombie caught fire";
                    case "frost_breath" -> zs.stream().anyMatch(z -> z.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS))
                            ? null : "no zombie slowed";
                    case "flower_trail" -> blocks(level, b.blockPosition(), Blocks.POPPY) > 0 ? null : "no flowers where it walked";
                    case "snow_trail" -> blocks(level, b.blockPosition(), Blocks.SNOW) > 0 ? null : "no snow where it walked";
                    case "treats" -> dropped(level, box, "cookie") ? null : "no treat dropped";
                    case "honey" -> dropped(level, box, "honey_bottle") ? null : "no honey dropped";
                    case "spikes" -> blocks(level, b.blockPosition(), Blocks.AMETHYST_BLOCK) > 0 ? null : "no crystal spikes came up";
                    case "knock_away" -> {
                        // Measured as a real horizontal DISTANCE: the old x*1000+z sum could not see a
                        // push along z of under half a block.
                        StringBuilder where = new StringBuilder();
                        for (int i = 0; i < zs.size(); i++) {
                            Zombie z = zs.get(i);
                            double dx = z.getX() - start[i].x;
                            double dz = z.getZ() - start[i].z;
                            farthest[i] = Math.max(farthest[i], Math.sqrt(dx * dx + dz * dz));
                            if (farthest[i] > 0.5 || z.getDeltaMovement().horizontalDistance() > 0.05) {
                                yield null;
                            }
                            where.append(String.format(java.util.Locale.ROOT, " zombie %.1f blocks from it%s;",
                                    z.position().distanceTo(b.position()), z.isAlive() ? "" : " (dead)"));
                        }
                        yield "no monster was knocked away (it stood at " + b.blockPosition().toShortString() + ";" + where + ")";
                    }
                    default -> "no check for " + a.id();
                };
            };
        }).get(30, TimeUnit.SECONDS);
        String last = "never checked";
        long until = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < until) {
            last = BuilderAbilityGameTest.onServer(server, proof::get).get(10, TimeUnit.SECONDS);
            if (last == null) {
                break;
            }
            Thread.sleep(250);
        }
        BuilderAbilityGameTest.onServer(server, () -> {
            for (Entity e : level.getEntitiesOfClass(Entity.class, p.getBoundingBox().inflate(48),
                    e -> e.getTags().contains("gt_moves") || e instanceof ItemEntity)) {
                e.discard();
            }
            Entity b = creature(level, p, id);
            if (b != null) {
                b.discard();
            }
            clearLeftovers(level, p.blockPosition());
            return null;
        }).get(10, TimeUnit.SECONDS);
        return last;
    }

    /**
     * put the ground back between moves. A move's blocks outlive the move — the spikes' amethyst
     * stands for seconds after its check has passed, flowers and snow stay — and the NEXT move's creature
     * is placed where the child's aim lands. When that was on top of the spikes, the knock_away cat was
     * set down three blocks up, its zombies were stood at that height, and creature and zombies ended
     * up out of each other's reach: a move that works, failing on terrain the move before had left.
     * Only the blocks a move makes are taken, and only in this test's plot.
     */
    static void clearLeftovers(ServerLevel level, BlockPos c) {
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-16, -4, -16), c.offset(16, 8, 16))) {
            var state = level.getBlockState(q);
            if (state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.POPPY) || state.is(Blocks.SNOW)) {
                level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    static Entity creature(ServerLevel level, ServerPlayer p, String id) {
        for (Entity e : level.getEntitiesOfClass(Entity.class, p.getBoundingBox().inflate(48))) {
            var sp = com.moddingfromamod.vanilla.DisplayRig.speciesOn(e);
            if (sp != null && sp.id().equals(id) && e instanceof net.minecraft.world.entity.Mob) {
                return e;
            }
        }
        return null;
    }

    static int blocks(ServerLevel level, BlockPos c, net.minecraft.world.level.block.Block block) {
        return (int) BlockPos.betweenClosedStream(c.offset(-10, -3, -10), c.offset(10, 5, 10))
                .filter(q -> level.getBlockState(q).is(block)).count();
    }

    static boolean dropped(ServerLevel level, AABB box, String item) {
        return !level.getEntitiesOfClass(ItemEntity.class, box,
                e -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).getPath().equals(item)).isEmpty();
    }
}
