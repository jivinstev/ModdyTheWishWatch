package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.RiderInput;
import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.mob.RideSeat;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Which VANILLA mobs can a child actually ride once {@code modify_mob} says rideable?</b>
 * (2026-10-11). The e2e grant of "a baby ender dragon I can ride" summoned a real ender dragon and
 * made it rideable; its own boss AI flew it 92 blocks away. Every living vanilla type (bar the
 * wither, whose spawn explodes) is made rideable, climbed on with an empty hand, steered south for
 * {@value #TICKS} ticks, and printed: on at the end, blocks carried south, and how far it ended
 * from where he steered it. Measured 2026-10-11: every type RIDES except the horse family (horse,
 * donkey, mule, llama, skeleton/zombie horse threw him off untamed -- now tamed to him on climbing
 * on, and asserted), creaking, giant and shulker (printed, not asserted).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class VanillaRideProbeGameTest {

    static final int TICKS = 60;
    /** The horse family: untamed, every one threw him off (2026-10-11). */
    static final java.util.Set<String> HORSES = java.util.Set.of("horse", "donkey", "mule", "llama",
            "trader_llama", "skeleton_horse", "zombie_horse");

    @GameTest(template = "empty_test", batch = "vanilla_ride_probe", timeoutTicks = 20 * 900)
    public static void whichVanillaMobsCanBeRidden(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-vanilla-ride");
        List<String> ids = new ArrayList<>();
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            MobCategory c = t.getCategory();
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath();
            if (c != MobCategory.MISC && !id.equals("wither") && !id.equals("player")) {
                ids.add(id);
            }
        }
        for (String extra : List.of("iron_golem", "snow_golem")) {
            if (!ids.contains(extra)) {
                ids.add(extra);
            }
        }
        List<String> rows = new ArrayList<>();
        GameTestSequence seq = helper.startSequence();
        Mob[] cur = new Mob[1];
        double[] z0 = new double[2];
        for (String id : ids) {
            seq.thenExecute(() -> {
                cur[0] = null;
                var type = BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(id));
                var raw = type.create(helper.getLevel(), EntitySpawnReason.COMMAND);
                if (!(raw instanceof Mob m)) {
                    if (raw != null) {
                        raw.discard();
                    }
                    return;
                }
                var at = helper.absolutePos(new BlockPos(4, 2, 1));
                m.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
                helper.getLevel().addFreshEntity(m);
                PetBehaviours.apply(m, new PetBehaviour(null, true, true, null), kid.getUUID());
                kid.stopRiding();
                kid.getInventory().clearContent();
                kid.teleportTo(m.getX(), m.getY(), m.getZ() - 1);
                NeoForge.EVENT_BUS.post(com.moddingfromamod.mv.Interact.event(kid, InteractionHand.MAIN_HAND, m));
                kid.setYRot(0f);
                kid.setXRot(0f);
                cur[0] = m;
                z0[0] = m.getZ();
                z0[1] = m.getX();
            });
            for (int t = 0; t < TICKS; t += 5) {
                seq.thenExecuteAfter(5, () -> {
                    if (cur[0] != null) {
                        RiderInput.hold(kid, 1f, 0f);
                    }
                });
            }
            seq.thenExecute(() -> {
                Mob m = cur[0];
                if (m == null) {
                    return;
                }
                boolean on = RideSeat.mountOf(kid) == m;
                double south = m.getZ() - z0[0];
                double off = Math.abs(m.getX() - z0[1]);
                String verdict = on && south >= 3 && off < 3 ? "RIDES" : on && south >= 1 ? "poor" : "NO";
                rows.add(String.format("%-18s %-5s on=%-5s south %6.1f  sideways %6.1f  alive=%s",
                        id, verdict, on, south, off, m.isAlive()));
                kid.stopRiding();
                m.discard();
            });
        }
        seq.thenExecute(() -> System.out.println("VANILLA-RIDE-PROBE (" + TICKS
                + " ticks holding W facing south; RIDES = on, >=3 south, <3 sideways):\n  "
                + String.join("\n  ", rows)));
        // The horse family threw him off untamed; climbing on now tames it to him (RideSeat.mount).
        seq.thenExecute(() -> {
            List<String> bad = new ArrayList<>();
            for (String r : rows) {
                String id = r.split("\\s+")[0];
                if (HORSES.contains(id) && !r.contains(" RIDES ")) {
                    bad.add(r.trim());
                }
            }
            helper.assertTrue(bad.isEmpty(), Component.literal("a horse he was told he can ride still cannot be ridden: " + bad));
        }).thenSucceed();
    }

    /**
     * <b>A boss is never a pet or a mount</b>: {@code wishsummon} refuses an ender dragon asked to be
     * rideable and summons nothing (a learned button replays exactly that), and modify_mob's check
     * refuses one standing there, naming the creature that works -- a sculpted one with flies.
     */
    @GameTest(template = "empty_test", batch = "boss_not_a_pet", timeoutTicks = 200)
    public static void aBossIsNeverAPetOrAMount(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(2, 2, 2));
        var src = level.getServer().createCommandSourceStack().withLevel(level)
                .withPosition(net.minecraft.world.phys.Vec3.atBottomCenterOf(at)).withSuppressedOutput();
        int before = level.getEntities(EntityTypes.ENDER_DRAGON, e -> true).size();
        level.getServer().getCommands().performPrefixedCommand(src,
                "wishsummon minecraft:ender_dragon {\"rideable\":true,\"friendly\":true,\"scale\":0.3}");
        helper.assertTrue(level.getEntities(EntityTypes.ENDER_DRAGON, e -> true).size() == before,
                Component.literal("wishsummon made a rideable ender dragon; a boss's AI flies it away from him"));
        var tweak = com.moddingfromamod.mob.MobTweak.fromJson(com.google.gson.JsonParser.parseString(
                "{\"rideable\":true}").getAsJsonObject());
        String said = com.moddingfromamod.agent.tools.ModifyMobTool.bossCannotBeAPet(tweak, "ender_dragon");
        helper.assertTrue(said != null && said.contains("SCULPT") && said.contains("flies"),
                Component.literal("modify_mob must refuse a rideable boss and name the sculpted creature instead: " + said));
        helper.assertTrue(com.moddingfromamod.agent.tools.ModifyMobTool.bossCannotBeAPet(tweak, "cow") == null,
                Component.literal("a cow is no boss"));
        var scaleOnly = com.moddingfromamod.mob.MobTweak.fromJson(com.google.gson.JsonParser.parseString(
                "{\"scale\":0.3}").getAsJsonObject());
        helper.assertTrue(com.moddingfromamod.agent.tools.ModifyMobTool.bossCannotBeAPet(scaleOnly, "ender_dragon") == null,
                Component.literal("only follow and rideable are refused on a boss; its size is his to change"));
        helper.succeed();
    }

    /**
     * <b>Holding the watch, he climbs onto his own saddled horse</b> (2026-10-12). "A unicorn I can
     * ride" came as a tamed, saddled horse, and a child holds Moddy's watch from the moment he joins;
     * the watch refuses every entity click so it is never put on a horse, and that refusal also
     * stopped him getting on. The watch must stay in his hand, not go on the horse.
     */
    @GameTest(template = "empty_test", batch = "watch_on_horse", timeoutTicks = 200)
    public static void holdingTheWatchHeClimbsOntoHisSaddledHorse(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-watch-horse");
        var horse = EntityTypes.HORSE.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        var at = helper.absolutePos(new BlockPos(4, 2, 1));
        horse.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        helper.getLevel().addFreshEntity(horse);
        horse.tameWithName(kid);
        horse.setItemSlot(net.minecraft.world.entity.EquipmentSlot.SADDLE, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SADDLE)); // 1.21.5: the saddle is an equipment slot
        helper.assertTrue(com.moddingfromamod.mob.RideSeat.vanillaMount(horse), Component.literal("the horse should be tamed and saddled"));
        var watch = com.moddingfromamod.wish.VanillaWatchItem.stack();
        kid.getInventory().clearContent();
        kid.setItemInHand(InteractionHand.MAIN_HAND, watch);
        boolean carrier = com.moddingfromamod.item.VanillaCarrierAbilities.isCarrier(kid.getMainHandItem());
        kid.teleportTo(horse.getX(), horse.getY(), horse.getZ() - 1);
        NeoForge.EVENT_BUS.post(com.moddingfromamod.mv.Interact.event(kid, InteractionHand.MAIN_HAND, horse));
        helper.assertTrue(kid.getVehicle() == horse, Component.literal("holding the watch (carrier=" + carrier
                + "), he could not climb onto his own tamed, saddled horse"));
        helper.assertTrue(com.moddingfromamod.wish.VanillaWatchItem.isWatch(kid.getMainHandItem()),
                Component.literal("the watch left his hand"));
        kid.stopRiding();
        horse.discard();
        helper.succeed();
    }
}
