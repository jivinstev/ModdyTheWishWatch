package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.WishExecutor;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;

/**
 * <b>A herd made in two steps keeps every member at the end of its wish</b>.
 *
 * <p>A herd of three is made the way the Builder's lowering (and a wish worker) makes it: the first by the sculpt,
 * then {@code wishmob spawn <id> 2} a few ticks later. At the end of the wish {@link WishShimmer#end} reaps
 * SCAFFOLDING — per species, of the rigs this wish stood up since its first creature stood, every copy older than the
 * newest. The herd's first member is older than the other two, so whether it was reaped hung on a race: the early
 * reveal stamps the wish's start ({@link WishShimmer#begin}) with the game time at which its task runs, and only when
 * that task ran a tick AFTER the first member stood was the first member outside the window. On the same tick, it was
 * discarded as scaffolding and the herd of three became two — {@code BuilderSpikesGameTest}'s "the herd of 3 never
 * stood whole: 0", about 5 runs in 32. This test pins the begin to the first member's own tick, the side of the race
 * that lost.
 *
 * <p>The control is the rule the reaper exists for: a second copy stood up by the sculpt's own path is still reaped.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ShimmerHerdGameTest {

    private ShimmerHerdGameTest() {
    }

    @GameTest(template = "empty_test", batch = "shimmer_herd", timeoutTicks = 400)
    public static void aHerdAddedByWishmobSpawnIsNeverItsOwnScaffolding(GameTestHelper helper) {
        drive(helper, "gt_shimmer_herd", true);
    }

    @GameTest(template = "empty_test", batch = "shimmer_respawn", timeoutTicks = 400)
    public static void aCreatureStoodUpAgainIsStillReapedAsScaffolding(GameTestHelper helper) {
        drive(helper, "gt_shimmer_respawn", false);
    }

    private static void drive(GameTestHelper helper, String prefix, boolean herd) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        String id = SpeciesOnRigGameTest.plant(helper, prefix + "_" + level.getGameTime(), "{\"powers\":[]}");
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        UUID wish = UUID.randomUUID();
        Marshal.global().submit(wish, child.getUUID(), "three of my " + id);
        String worker = prefix + "-" + level.getGameTime();
        WishRun run = Marshal.global().assign(worker, wish);
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, id);

        // The first member, as the sculpt stands it — and the wish's start stamped on the SAME tick.
        var at = child.position();
        helper.assertTrue(CreatureSpawn.spawn(level, id, at.x + 3, at.y, at.z + 3, 0f, EntitySpawnReason.COMMAND) != null,
                Component.literal("the first member did not stand"));
        WishShimmer.begin(wish, List.of(id), level.getGameTime());

        int[] reaped = {-1};
        helper.runAfterDelay(5, () -> {
            if (herd) {
                // The rest of the herd, as the lowering asks for it.
                com.moddingfromamod.agent.AgentContext.with(worker, () -> WishExecutor.runOne(server, level,
                        child.position(), child, "wishmob spawn " + id + " 2"));
            } else {
                // A second copy by the sculpt's own path: a re-spawn, which IS scaffolding.
                CreatureSpawn.spawn(level, id, at.x + 6, at.y, at.z + 3, 0f, EntitySpawnReason.COMMAND);
            }
        });
        helper.runAfterDelay(10, () -> {
            reaped[0] = WishShimmer.end(server, wish);
            Marshal.global().finish(wish);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reaped[0] >= 0, Component.literal("waiting for the end of the wish"));
            List<Mob> now = carriers(level, id);
            try {
                if (herd) {
                    helper.assertValueEqual(0, reaped[0], Component.literal("members of the herd reaped as scaffolding"));
                    helper.assertValueEqual(3, now.size(), Component.literal("the herd after its wish ended"));
                } else {
                    helper.assertValueEqual(1, reaped[0], Component.literal("superseded copies reaped"));
                    helper.assertValueEqual(1, now.size(), Component.literal("copies after the wish ended"));
                }
            } finally {
                // They stand where later tests look: take them down.
                for (Mob m : now) {
                    DisplayRig.reap(level, m.getUUID());
                    m.discard();
                }
            }
        });
    }

    private static List<Mob> carriers(ServerLevel level, String species) {
        List<Mob> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.isAlive()) {
                var sp = DisplayRig.speciesOn(m);
                if (sp != null && species.equals(sp.id())) {
                    out.add(m);
                }
            }
        }
        return out;
    }
}
