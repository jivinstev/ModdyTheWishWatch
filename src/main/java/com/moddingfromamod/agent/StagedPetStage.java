package com.moddingfromamod.agent;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.vanilla.VanillaRigAnimator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A real child's own creature, made for him from a recorded recipe, and watched</b>.
 *
 * <p>A player filmed a wished dragon spinning on the spot on a phone and asked whether that is how it really
 * behaves. A headless stage ({@code bxstage}) makes a creature for a stand-in; this makes it for the FIRST REAL
 * CHILD who joins — a stock Java client or a Bedrock phone — as his own wish, so it is his pet and lives as it does
 * in play, and then:
 * <ul>
 *   <li>logs the creature's heading every tick it turned ({@code STAGEDPET yaw}), and a summary every five
 *       seconds: the most it turned in one tick, and how many ticks it turned more than 40 degrees;</li>
 *   <li>with {@code stagedpetview=hover}, carries the child round it in the air as a flying player would be —
 *       out to the side, in over its back, straight above it, and out again — looking at it throughout.</li>
 *   <li>with {@code stagedpetview=overhead}, holds him straight above it, drifting a little.</li>
 * </ul>
 *
 * <p>Off unless {@code -Dmoddingfromamod.stagedpet=<name>} names a recipe in {@code /stages/<name>.json}: a list
 * of {@code {"tool", "args"}} calls, as a behaviour stage is.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class StagedPetStage {

    private StagedPetStage() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-staged-pet");
    public static final String PROPERTY = ModdingFromAModMod.MODID + ".stagedpet";
    /** Seconds after the child joins: a stock Java client applies the item pack by reloading. */
    private static final int SETTLE_TICKS = 20 * 20;

    private static int joinedTicks;
    private static boolean staged;
    private static volatile boolean made;
    private static Mob pet;
    private static float lastYaw = Float.NaN;
    private static int watched;
    private static int bigTurns;
    private static float worstTurn;
    private static double turned;

    static String recipe() {
        return System.getProperty(PROPERTY, "").trim();
    }

    private static String view() {
        return System.getProperty(PROPERTY + "view", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean hover() {
        return view().equals("hover") || view().equals("overhead");
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (recipe().isEmpty()) {
            return;
        }
        var server = event.getServer();
        var service = PlayerKind.serviceClients();
        // stagedpetchild=NAME: that child, when he is not the first to join (a Bedrock rig's own bot joins first)
        String named = System.getProperty(PROPERTY + "child", "").trim();
        ServerPlayer child = server.getPlayerList().getPlayers().stream()
                .filter(p -> PlayerKind.isChild(p.getUUID(), service))
                .filter(p -> named.isEmpty() || named.equalsIgnoreCase(p.getGameProfile().getName()))
                .findFirst().orElse(null);
        if (child == null) {
            return;
        }
        joinedTicks++;
        if (!staged) {
            if (joinedTicks >= SETTLE_TICKS) {
                staged = true;
                stage(child.getUUID());
            }
            return;
        }
        if (!made) {
            return;
        }
        ServerLevel level = (ServerLevel) child.level();
        if (pet == null || pet.isRemoved()) {
            List<Mob> near = level.getEntitiesOfClass(Mob.class, new AABB(child.blockPosition()).inflate(48),
                    m -> VanillaRigAnimator.weightsOf(m) != null);
            if (near.isEmpty()) {
                return;
            }
            pet = near.get(0);
            lastYaw = Float.NaN;
            LOG.info("STAGEDPET watching {} at ({}, {}, {})", pet.getType().toShortString(),
                    fmt(pet.getX()), fmt(pet.getY()), fmt(pet.getZ()));
        }
        watch(child);
        if (hover()) {
            hover(child, watched);
        }
    }

    /** One tick of the creature's heading: every turn is logged, and a summary every five seconds. */
    private static void watch(ServerPlayer child) {
        // The heading he SEES: what the rig drew (RigTurn), which is no longer the carrier's own every tick.
        float drawn = VanillaRigAnimator.drawnYawOf(pet);
        float yaw = Mth.wrapDegrees(Float.isNaN(drawn) ? pet.yBodyRot : drawn);
        if (!Float.isNaN(lastYaw)) {
            float d = Mth.wrapDegrees(yaw - lastYaw);
            if (Math.abs(d) > 0.5f) {
                LOG.info("STAGEDPET yaw tick {} {} ({}) pos ({}, {}, {}) child {} away", watched, fmt(yaw),
                        (d > 0 ? "+" : "") + fmt(d), fmt(pet.getX()), fmt(pet.getY()), fmt(pet.getZ()),
                        fmt(Math.sqrt(pet.distanceToSqr(child))));
            }
            turned += Math.abs(d);
            worstTurn = Math.max(worstTurn, Math.abs(d));
            if (Math.abs(d) > 40f) {
                bigTurns++;
            }
        }
        lastYaw = yaw;
        watched++;
        if (watched % 100 == 0) {
            LOG.info("STAGEDPET summary: {} ticks watched, turned {} degrees in all, most in one tick {}, ticks over "
                    + "40 degrees {}", watched, fmt(turned), fmt(worstTurn), bigTurns);
        }
    }

    /**
     * Where a flying child is on this tick, relative to the creature: a lap every 16 seconds at 5 blocks up, its
     * radius going 6 → 0 → 6 over 12 seconds (so he passes straight over it), looking at it. Pure.
     *
     * @return {dx, dy, dz} from the creature's feet
     */
    public static double[] hoverOffset(int tick) {
        double a = tick * (2 * Math.PI / 320.0);
        double r = 6.0 * Math.abs(Math.cos(tick * (Math.PI / 240.0)));
        return new double[] {r * Math.cos(a), 5.0, r * Math.sin(a)};
    }

    /**
     * {@code stagedpetview=overhead}: a child hovering STRAIGHT ABOVE the creature, as the player was when he filmed
     * a dragon spinning — 6 blocks up, drifting round a circle only 0.4 of a block across, a lap every 2 seconds.
     * From the creature, "which way is he" then swings right round every lap although he has barely moved. Pure.
     */
    public static double[] overheadOffset(int tick) {
        double a = tick * (2 * Math.PI / 40.0);
        return new double[] {0.4 * Math.cos(a), 6.0, 0.4 * Math.sin(a)};
    }

    private static void hover(ServerPlayer child, int tick) {
        double[] o = view().equals("overhead") ? overheadOffset(tick) : hoverOffset(tick);
        double x = pet.getX() + o[0];
        double y = pet.getY() + o[1];
        double z = pet.getZ() + o[2];
        Vec3 to = new Vec3(pet.getX() - x, pet.getY() + 1.0 - (y + child.getEyeHeight()), pet.getZ() - z);
        float yaw = (float) (Mth.atan2(to.z, to.x) * (180.0 / Math.PI)) - 90f;
        float pitch = (float) -(Mth.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * (180.0 / Math.PI));
        child.connection.teleport(x, y, z, yaw, pitch);
        child.setDeltaMovement(Vec3.ZERO);
        child.fallDistance = 0f;
        child.setNoGravity(true);
    }

    /** The recipe's calls, as the child's own wish, on a worker thread bound as an agent is. */
    private static void stage(UUID childId) {
        String at = "/stages/" + recipe() + ".json";
        Thread t = new Thread(() -> {
            UUID wish = UUID.randomUUID();
            String worker = "stagedpet-" + wish;
            Marshal.global().submit(wish, childId, "staged: " + recipe());
            Marshal.global().assign(worker, wish);
            AgentContext.bind(worker);
            try (InputStream in = StagedPetStage.class.getResourceAsStream(at)) {
                if (in == null) {
                    LOG.error("STAGEDPET no recipe {}", at);
                    return;
                }
                ToolRegistry tools = WishAgent.buildTools();
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (var el : JsonParser.parseString(json).getAsJsonArray()) {
                    var call = el.getAsJsonObject();
                    String name = call.get("tool").getAsString();
                    McpTool tool = tools.byName(name);
                    if (tool == null) {
                        LOG.error("STAGEDPET no tool {}", name);
                        return;
                    }
                    ToolResult r = tool.call(call.getAsJsonObject("args").deepCopy());
                    LOG.info("STAGEDPET staged {} -> {}{}", name, r.isError() ? "ERROR " : "",
                            r.text().length() > 240 ? r.text().substring(0, 240) + "…" : r.text());
                    if (r.isError()) {
                        return;
                    }
                }
                made = true;
            } catch (Exception e) {
                LOG.error("STAGEDPET staging threw", e);
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
        }, "stagedpet");
        t.setDaemon(true);
        t.start();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
