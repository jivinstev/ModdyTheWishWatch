package com.moddingfromamod.lite;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.vanilla.VanillaRigBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A winged starter FLYING a banked loop in front of a test player</b> (the owner asked for Bedrock films of
 * wings flapping, before and after the stream cuts). A copy of a starter species is registered with the {@code flies}
 * power (and no abilities), spawned through the shipping path (a wish mob, then {@link VanillaRigBridge}), and its
 * carrier is flown round a loop each tick: across, toward and away, climbing and diving, facing where it goes. Off the
 * ground with {@code flies}, the rig's gait plays the flap, as for any wished flier.
 *
 * <p>Off unless {@code -Dmoddingfromamod.flystage=<starter species with wings>} is set (test houses only, as
 * {@link StockWalkStage}); e.g. {@code flystage=pet_dragon}. The loop is centred {@link #CENTRE} ahead of the seat
 * a test puts the player at (8.5, 120, 8.5, facing +Z).
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class FlyStage {

    private FlyStage() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-fly-stage");

    public static final String PROPERTY = ModdingFromAModMod.MODID + ".flystage";

    static final double[] CENTRE = {8.5, 121.5, 14.0};
    /** One lap every 80 ticks (~3 blocks a second): 1.6 across, 1.6 toward and away, 0.7 up and down. */
    static final int LAP_TICKS = 80;
    private static final int SETTLE_TICKS = 20 * 20;

    private static Mob flier;
    private static int ticks;

    /** Where the flier is and which way it faces on lap tick {@code t}: {x, y, z, yaw}. Pure. */
    static double[] at(int t) {
        double w = 2 * Math.PI / LAP_TICKS;
        double a = t * w;
        double x = CENTRE[0] + 1.6 * Math.sin(a);
        double z = CENTRE[2] + 1.6 * Math.cos(a);
        double y = CENTRE[1] + 0.7 * Math.sin(2 * a);
        double vx = 1.6 * Math.cos(a) * w;
        double vz = -1.6 * Math.sin(a) * w;
        double yaw = Math.toDegrees(Math.atan2(-vx, vz));
        return new double[] {x, y, z, yaw};
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        String starter = System.getProperty(PROPERTY, "").trim();
        if (starter.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        ticks++;
        if (flier == null) {
            if (ticks == SETTLE_TICKS) {
                try {
                    flier = spawn(server.overworld(), starter);
                } catch (Exception e) {
                    LOG.error("could not stage the flier", e);
                }
            }
            return;
        }
        double[] p = at(ticks);
        double[] q = at(ticks + 1);
        flier.setNoAi(true);
        flier.setNoGravity(true);
        flier.setPos(p[0], p[1], p[2]);
        flier.setDeltaMovement(q[0] - p[0], q[1] - p[1], q[2] - p[2]);
        flier.setOnGround(false);
        float yaw = (float) p[3];
        flier.setYRot(yaw);
        flier.setYBodyRot(yaw);
        flier.setYHeadRot(yaw);
    }

    private static Mob spawn(ServerLevel level, String starter) throws Exception {
        JsonObject sp;
        try (InputStream in = FlyStage.class.getResourceAsStream("/starter-library/species/" + starter + "/species.json")) {
            if (in == null) {
                throw new IllegalStateException("no starter species " + starter);
            }
            sp = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        String id = "stage_flier";
        sp.addProperty("id", id);
        sp.addProperty("name", "Stage Flier");
        sp.remove("abilityPacks");
        JsonArray powers = new JsonArray();
        powers.add("friendly");
        powers.add("flies");
        sp.add("powers", powers);
        SpeciesRegistry.put(level.getServer(), Species.fromJson(sp));
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        double[] p = at(0);
        mob.snapTo(p[0], p[1], p[2], (float) p[3], 0f);
        mob.setSpeciesId(id);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.COMMAND, null);
        LivingEntity carrier = VanillaRigBridge.manifestNow(level, mob);
        if (!(carrier instanceof Mob m)) {
            throw new IllegalStateException("the bridge made no rig for " + id);
        }
        m.setPermanentlyInvulnerable(true);
        m.setSilent(true);
        LOG.info("FLYSTAGE staged '{}' (a flying copy of {}) on a {}-tick loop at {}", id, starter, LAP_TICKS,
                java.util.Arrays.toString(CENTRE));
        return m;
    }
}
