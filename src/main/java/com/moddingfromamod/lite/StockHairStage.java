package com.moddingfromamod.lite;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.VanillaRigAnimator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b> on a genuinely STOCK client: a "copy of me" turns to look at the child standing behind
 * it</b> ({@code tools/stock-hair-shot.sh}, {@code stockhair=on}).
 *
 * <p>{@code GateC:vanillahair} proves the fix on a NeoForge client. The reported client was a stock
 * 26.3 behind a version translator (ViaProxy), and a stock client cannot be read, only photographed — so the scene is
 * staged from the SERVER, as {@link StockAtomStage} stages its own: the same fixture
 * ({@link com.moddingfromamod.vanilla.CopyOfMeFixture}, hair a root, built through the sculpt tool's own
 * assembly), the shipping {@link VanillaRigAnimator}, and the carrier's own vanilla
 * {@code LookControl} pointed at the child every tick.
 *
 * <p>Four phases, each announced in the log so the script photographs the child's screen at the
 * right moment: {@code front} (the child is seated in front of the creature, which has not looked
 * yet: the face-on REFERENCE), {@code back} (behind it, at rest), {@code look} (the carrier starts
 * looking at him) and {@code looked} (ten seconds later: the verdict frame).
 */
public final class StockHairStage {

    private StockHairStage() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-stock-hair");

    public static final String PROPERTY = ModdingFromAModMod.MODID + ".stockhair";

    private static final double X = 8.5;
    private static final double FOOT_Y = 95.2;
    private static final double STAGE_Z = 20.5;
    private static final double STANDOFF = 4.0;

    private static volatile boolean staged;
    private static DisplayRig rig;
    private static int stagedAtTick;
    private static String phase = "";

    public static boolean active() {
        return "on".equalsIgnoreCase(System.getProperty(PROPERTY, "off"));
    }

    public static void tick(MinecraftServer server, ServerPlayer subject, long elapsedSeconds) {
        if (!active() || server == null || subject == null) {
            return;
        }
        ServerPlayer child = theChild(server, subject);
        if (child == null) {
            return;
        }
        if (!staged) {
            if (elapsedSeconds < 20) {
                return;
            }
            staged = true;
            stagedAtTick = server.getTickCount();
            try {
                build(server, child);
            } catch (RuntimeException e) {
                LOG.error("could not stage the stock-client hair scene", e);
                return;
            }
        }
        if (rig == null) {
            return;
        }
        // The SERVER's clock, not `elapsedSeconds`: that one is since the LAST join, and a camera
        // or rejoin resets it -- measured, the phases restarted half way through the first run.
        Mob base = rig.base();
        long t = (server.getTickCount() - stagedAtTick) / 20;
        if ((server.getTickCount() - stagedAtTick) % 100 == 0) {
            LOG.info("STOCKHAIR state t={} child=({}, {}, {} yaw {}) carrier yHead={} yBody={} yRot={} "
                            + "xRot={}", t, String.format("%.1f", child.getX()),
                    String.format("%.1f", child.getY()), String.format("%.1f", child.getZ()),
                    String.format("%.0f", child.getYRot()),
                    String.format("%.1f", base.getYHeadRot()), String.format("%.1f", base.yBodyRot),
                    String.format("%.1f", base.getYRot()), String.format("%.1f", base.getXRot()));
        }
        if (t < 20) {
            announce("front", base);
            seat(child, STAGE_Z + STANDOFF, 180f);
        } else if (t < 40) {
            announce("back", base);
            seat(child, STAGE_Z - STANDOFF, 0f);
        } else {
            seat(child, STAGE_Z - STANDOFF, 0f);
            announce(t < 55 ? "look" : "looked", base);
            base.getLookControl().setLookAt(child.getX(), child.getEyeY(), child.getZ());
            base.getLookControl().tick();
        }
    }

    private static void announce(String p, Mob base) {
        if (!p.equals(phase)) {
            phase = p;
            LOG.info("STOCKHAIR {} yHeadRot={} yBodyRot={} yRot={} xRot={}", p,
                    String.format("%.1f", base.getYHeadRot()), String.format("%.1f", base.yBodyRot),
                    String.format("%.1f", base.getYRot()), String.format("%.1f", base.getXRot()));
        }
    }

    private static ServerPlayer theChild(MinecraftServer server, ServerPlayer fallback) {
        var service = com.moddingfromamod.marshal.PlayerKind.serviceClients();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID(), service)) {
                return p;
            }
        }
        return com.moddingfromamod.marshal.PlayerKind.isChild(fallback.getUUID(), service)
                ? fallback : null;
    }

    private static void build(MinecraftServer server, ServerPlayer child) {
        ServerLevel level = child.level();
        for (ServerLevel l : server.getAllLevels()) {
            com.moddingfromamod.mv.DayTime.set(l, 6000L);
            l.getGameRules().set(GameRules.ADVANCE_TIME,false, server);
            l.getGameRules().set(GameRules.ADVANCE_WEATHER,false, server);
            com.moddingfromamod.mv.DayTime.weather(l, 6000, 0, false, false);
        }
        var floor = net.minecraft.world.level.block.Blocks.CONCRETE.white().defaultBlockState();
        for (int x = (int) X - 12; x <= (int) X + 12; x++) {
            for (int z = (int) STAGE_Z - 12; z <= (int) STAGE_Z + 12; z++) {
                level.setBlock(new net.minecraft.core.BlockPos(x, (int) Math.floor(FOOT_Y) - 1, z),
                        floor, 3);
                for (int y = (int) Math.floor(FOOT_Y); y < (int) FOOT_Y + 6; y++) {
                    level.setBlock(new net.minecraft.core.BlockPos(x, y, z),
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        List<CreatureSculpt.Part> parts = SculptCreatureTool.withKits(
                CreatureSculpt.parseParts(JsonParser.parseString(
                        com.moddingfromamod.vanilla.CopyOfMeFixture.JSON)
                        .getAsJsonArray()), new JsonObject());
        var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
        // Yaw 0 faces +Z: away from the seat behind it.
        rig = DisplayRig.spawn(level, new Vec3(X, Math.floor(FOOT_Y), STAGE_Z), 0f, parts, 1.0,
                CreatureAnatomy.widthBlocks(extents), CreatureAnatomy.heightBlocks(extents));
        Mob base = rig.base();
        base.setNoAi(true);
        base.setPermanentlyInvulnerable(true);
        base.setSilent(true);
        base.setYHeadRot(0f);
        base.setYBodyRot(0f);
        VanillaRigAnimator.drive(rig, com.moddingfromamod.vanilla.RigFit.intoRigSpace(parts), java.util.Map.of());
        StringBuilder hair = new StringBuilder();
        for (CreatureSculpt.Part p : parts) {
            if (p.name().startsWith("hair")) {
                hair.append(p.name()).append("->").append(p.parent()).append(' ');
            }
        }
        int already = level.getEntitiesOfClass(net.minecraft.world.entity.Display.class,
                new net.minecraft.world.phys.AABB(X - 3, FOOT_Y - 2, STAGE_Z - 3,
                        X + 3, FOOT_Y + 4, STAGE_Z + 3)).size() - rig.displays().size();
        LOG.info("STOCKHAIR staged {} display(s); {} other display(s) already on the spot; "
                + "as saved: {}", rig.displays().size(), already, hair.toString().trim());
    }

    private static void seat(ServerPlayer child, double z, float yaw) {
        child.connection.teleport(X, Math.floor(FOOT_Y) + 0.0, z, yaw, 8.0f);
        child.setDeltaMovement(Vec3.ZERO);
        child.fallDistance = 0f;
        child.setNoGravity(true);
    }
}
