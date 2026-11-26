package com.moddingfromamod.redstone;

import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.datapack.CompileResult;
import com.moddingfromamod.datapack.DatapackForge;
import com.moddingfromamod.datapack.DatapackReloader;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * The Minecraft half of {@link RedstoneScript}: makes sure the world's redstone engine is installed and running,
 * puts the named markers at a driven mechanism's switches and feed, and takes them away again.
 */
public final class MechanismDrive {

    private static final Object ENGINE_LOCK = new Object();

    private MechanismDrive() {
    }

    /**
     * Makes sure the engine runs in this world, installing it the first time (one datapack reload, ever, per world).
     * Off the server thread; waits for its first tick. Null when it runs; else why not.
     */
    public static String ensureEngine() {
        synchronized (ENGINE_LOCK) {
            String why = null;
            boolean on = AgentBridge.onServer(() -> {
                MinecraftServer s = AgentBridge.serverOrNull();
                if (!DatapackReloader.isEnabled(s, RedstoneScript.ENGINE)) {
                    return false;
                }
                setAlive(s, 0);            // a saved score says nothing: wait for a tick to set it again
                return true;
            }, 10_000L);
            if (!on) {
                CompileResult r = DatapackForge.compile(RedstoneScript.ENGINE,
                        "Moddy's redstone engine: every mechanism's switches power its own redstone",
                        RedstoneScript.engine());
                if (!r.ok()) {
                    return "the redstone engine did not compile: " + r.failureSummary();
                }
                why = AgentBridge.onServer(() -> {
                    MinecraftServer s = AgentBridge.serverOrNull();
                    try {
                        DatapackForge.activate(s, RedstoneScript.ENGINE);
                        var refused = DatapackReloader.takeBackIfRefused(s, RedstoneScript.ENGINE);
                        return refused == null ? null : "the server refused the redstone engine: " + refused.missing();
                    } catch (java.io.IOException | RuntimeException e) {
                        return "the redstone engine could not be installed: " + e.getMessage();
                    }
                }, 60_000L);
            }
            for (int i = 0; why == null && i < 150; i++) {
                if (AgentBridge.onServer(() -> alive(AgentBridge.serverOrNull()), 5_000L)) {
                    return null;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }
            return why != null ? why : "the redstone engine never started running";
        }
    }

    static Objective objective(Scoreboard sb, String name) {
        Objective o = sb.getObjective(name);
        if (o == null) {
            o = sb.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(name),
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
        }
        return o;
    }

    static void setAlive(MinecraftServer s, int v) {
        Scoreboard sb = s.getScoreboard();
        sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly(RedstoneScript.ALIVE), objective(sb, RedstoneScript.ON)).set(v);
    }

    static boolean alive(MinecraftServer s) {
        Objective o = s.getScoreboard().getObjective(RedstoneScript.ON);
        if (o == null) {
            return false;
        }
        var info = s.getScoreboard().getPlayerScoreInfo(ScoreHolder.forNameOnly(RedstoneScript.ALIVE), o);
        return info != null && info.value() > 0;
    }

    /** Puts the markers, numbered for the engine (server thread): every switch and logic rule, every feed. */
    public static void mark(ServerLevel level, String id, MechanismPlan plan) {
        Scoreboard sb = level.getServer().getScoreboard();
        RedstoneScript.OBJECTIVES.forEach(o -> objective(sb, o));
        var next = sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly("#next"), objective(sb, RedstoneScript.ID));
        next.add(1);
        int number = next.get();
        int n = 0;
        for (Control c : plan.controls()) {
            List<String> tags = new ArrayList<>(List.of(RedstoneScript.ALL, RedstoneScript.IN, RedstoneScript.mechTag(id),
                    RedstoneScript.inTag(id, n++)));
            if (c.kind().equals("logic")) {
                tags.add(RedstoneScript.SCRIPT);
            } else if (c.toggle()) {
                tags.add(RedstoneScript.TOGGLE);
            }
            marker(level, c.use(), number, tags);
        }
        for (RsPos f : plan.feeds()) {
            marker(level, f, number, List.of(RedstoneScript.ALL, RedstoneScript.OUT, RedstoneScript.mechTag(id),
                    RedstoneScript.outTag(id)));
        }
    }

    /**
     * Switches the logic input at {@code at} (a script marker) on or off. False when there is none there (a wired
     * mechanism, whose logic cell takes a redstone block instead). Server thread.
     */
    public static boolean setScript(ServerLevel level, net.minecraft.core.BlockPos at, boolean on) {
        List<Marker> ms = level.getEntitiesOfClass(Marker.class, new net.minecraft.world.phys.AABB(at),
                m -> m.getTags().contains(RedstoneScript.SCRIPT));
        if (ms.isEmpty()) {
            return false;
        }
        Scoreboard sb = level.getServer().getScoreboard();
        for (Marker m : ms) {
            sb.getOrCreatePlayerScore(m, objective(sb, RedstoneScript.ON)).set(on ? 1 : 0);
        }
        return true;
    }

    static void marker(ServerLevel level, RsPos at, int number, List<String> tags) {
        Marker m = EntityType.MARKER.create(level, EntitySpawnReason.COMMAND);
        if (m == null) {
            throw new IllegalStateException("could not make a marker");
        }
        m.setPos(at.x() + 0.5, at.y() + 0.5, at.z() + 0.5);
        tags.forEach(m::addTag);
        level.addFreshEntity(m);
        Scoreboard sb = level.getServer().getScoreboard();
        sb.getOrCreatePlayerScore(m, objective(sb, RedstoneScript.ID)).set(number);
    }

    /** Takes away every marker of mechanism {@code id} and their scores (server thread). */
    public static void unmark(ServerLevel level, String id) {
        String tag = RedstoneScript.mechTag(id);
        List<Marker> gone = new ArrayList<>();
        for (var e : level.getAllEntities()) {
            if (e instanceof Marker m && m.getTags().contains(tag)) {
                gone.add(m);
            }
        }
        for (Marker m : gone) {
            level.getServer().getScoreboard().resetAllPlayerScores(m);
            m.discard();
        }
    }

    /** What the engine sees of a driven mechanism, for a failure report (server thread). */
    public static String describe(ServerLevel level, String id, MechanismPlan plan) {
        StringBuilder b = new StringBuilder(" || drive: feeds");
        for (RsPos f : plan.feeds()) {
            b.append(' ').append(f).append('=').append(level.getBlockState(LevelView.bp(f)).getBlock()
                    .getDescriptionId().replace("block.minecraft.", ""));
        }
        Scoreboard sb = level.getServer().getScoreboard();
        for (var e : level.getAllEntities()) {
            if (e instanceof Marker m && m.getTags().contains(RedstoneScript.mechTag(id))) {
                b.append("; marker ").append(m.blockPosition().toShortString()).append(' ')
                        .append(m.getTags().stream().filter(t -> t.startsWith("rs.") && t.length() < 12 || t.startsWith("rs." + id + "."))
                                .sorted().toList());
                for (String o : RedstoneScript.OBJECTIVES) {
                    var info = sb.getPlayerScoreInfo(m, objective(sb, o));
                    b.append(' ').append(o).append('=').append(info == null ? "-" : info.value());
                }
            }
        }
        b.append("; alive=").append(alive(level.getServer()));
        return b.toString();
    }
}
