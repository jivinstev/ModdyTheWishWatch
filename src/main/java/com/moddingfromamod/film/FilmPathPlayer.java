package com.moddingfromamod.film;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;

import com.moddingfromamod.net.FilmPathHandover;
import com.moddingfromamod.net.ModdedClient;
import com.moddingfromamod.net.Send;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>{@code /moddyop filmcam path …}: play a keyframed camera path through {@link FilmCamera}</b>
 * (a take's {@code camera.keys} / {@code camera.cut}).
 *
 * <p>The path is DATA ({@link CameraKeys} documents the file); a timeline runner loads it and plays
 * it over RCON, and nothing here knows the runner exists. Each server tick the path is
 * evaluated at {@code (gameTick - startTick) × 50 ms} — tick count, never wall time — and the camera is
 * PLACED there exactly ({@link FilmCamera#place}): the same invisible {@code item_display} and
 * {@code setCamera} the site films use, so a stock client sees it with no mod.
 *
 * <pre>
 * /moddyop filmcam path load &lt;name&gt; &lt;file.json | {inline json}&gt;
 * /moddyop filmcam path play &lt;name&gt; &lt;player&gt; [trace]
 * /moddyop filmcam path stop &lt;player&gt;
 * /moddyop filmcam path status
 * /moddyop filmcam path list
 * </pre>
 *
 * <p>Every answer is ONE line, and {@code play}/{@code stop}/{@code status} are JSON, for an RCON
 * reader. {@code play} answers with the game tick it started on and each key's PLANNED game tick; an
 * RCON reply cannot arrive later, so the tick each key was ACTUALLY reached is logged as it happens
 * ({@code [filmcam] … key i … reached on game tick G}) and listed by {@code status} (and {@code stop}),
 * for the runner's planned-against-actual log. {@code status} also carries {@code trace_hash}, a hash
 * of the camera's read-back position and facing on every tick from 0 to the path's last key (one
 * pass of a loop): two finished plays of one path agree tick for tick exactly when their hashes do.
 * {@code trace} logs that read-back each tick.
 *
 * <p><b>Inline JSON over RCON stays under ~1,400 bytes.</b> Vanilla's RCON reads one 1,460-byte packet
 * and a longer command drops the connection (measured on the lite server), so a longer path is
 * a {@code .json} file, resolved against the server directory (or absolute), and loaded by name.
 *
 * <p>One camera, one playback: {@link FilmCamera} has one rig, so playing a path for a second player
 * stops the first. When the path ends (and does not loop) the camera HOLDS the last key until
 * {@code stop}, which gives the player his own eyes back.
 *
 * <p><b>A modded viewer draws the path every frame</b> (the smooth film camera, 2026-11-16). The rig moves
 * once a tick, so a stock client's picture steps at 20 Hz: at a recorder's ~58 fps, three frames in four
 * hold and the fourth jumps. A viewer whose client has our jar is also handed the path itself
 * ({@link FilmPathPayload}: the keys as {@link CameraKeys} JSON, the game time of play tick 0, the rig's
 * id, and the entity each key's look resolved to), and draws it at {@code (ticks + partialTick) × 50 ms}
 * on the same play clock ({@code client/FilmPathClient}, {@link FilmClock}). The rig still moves every
 * tick (chunk loading, a stock viewer), the read-back and {@code trace_hash} are the server's as before,
 * and the handover is re-sent only when the rig, a look entity or the play clock changes
 * ({@code status.handovers}). {@code stop} (or a play for someone else) sends the viewer a stop.
 *
 * <p><b>FOV is not applied.</b> A stock client's field of view comes from its own options, scaled only
 * by its own player's speed and effects, and not at all while it looks through another entity; no
 * packet sets it. The path's FOV is eased and reported ({@code status.fov}) so a post step can crop to
 * it, but the picture is the client's own FOV.
 */
public final class FilmPathPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/filmcam");

    static final String USAGE = "usage: /moddyop filmcam path load <name> <file.json|{json}>"
            + " | /moddyop filmcam path play <name> <player> [trace] | /moddyop filmcam path stop <player>"
            + " | /moddyop filmcam path status | /moddyop filmcam path list";

    /** A path file is a few keys; anything this big is the wrong file. */
    static final long MAX_FILE_BYTES = 1 << 20;

    /** Twenty minutes of ticks: more than any take. */
    static final int MAX_READ_BACK = 24_000;

    private static final Map<String, CameraKeys.Path> PATHS = new LinkedHashMap<>();

    /** The one playback (or the last one, kept for {@code status} after it stopped). */
    private static Playback current;

    /** The current (or last) playback; for a GameTest. */
    public static Playback current() {
        return current;
    }

    private FilmPathPlayer() {
    }

    /** One play of one path for one player. */
    public static final class Playback {
        public final String name;
        public final CameraKeys.Path path;
        public final UUID player;
        public final String playerName;
        public final long startTick;
        public final boolean trace;
        public final long[] reachedTick;
        public final Map<String, EntitySelector> selectors;
        public long lastT = -1;
        public boolean playing = true;
        public double[] lastLook;
        public long lookLostTicks;
        public long hash = 0xcbf29ce484222325L;   // FNV-1a 64
        public long hashedTicks;
        /** The read-back {x, y, z, yaw, pitch} of each hashed tick, for a test to compare (capped). */
        public final List<double[]> readBack = new ArrayList<>();
        public double[] pos;
        public float yaw, pitch;
        public double fov;
        public int key;
        /** Per key, the network id its look resolved to (FilmPathHandover.NO_ENTITY for a point or none). */
        public final int[] lookIds;
        /** The server level's game time on play tick 0, as last handed to the viewer. */
        public long startGameTime;
        /** What the viewer was last handed: the rig id and the look ids (the handover is re-sent on a change). */
        public int sentRig = Integer.MIN_VALUE;
        public int[] sentLookIds;
        public long sentStart = Long.MIN_VALUE;
        /** How many times the path was handed to the viewer's client (0 for a stock client). */
        public int handovers;
        /** The path as CameraKeys JSON, written once, or null when it is too big to hand over. */
        public String json;

        Playback(String name, CameraKeys.Path path, ServerPlayer p, long startTick, boolean trace,
                 Map<String, EntitySelector> selectors) {
            this.name = name;
            this.path = path;
            this.player = p.getUUID();
            this.playerName = p.getGameProfile().getName();
            this.startTick = startTick;
            this.trace = trace;
            this.selectors = selectors;
            this.reachedTick = new long[path.keys().size()];
            java.util.Arrays.fill(reachedTick, -1);
            this.lookIds = new int[path.keys().size()];
            java.util.Arrays.fill(lookIds, FilmPathHandover.NO_ENTITY);
        }

        void mix(double d) {
            long bits = Double.doubleToLongBits(d);
            for (int i = 0; i < 8; i++) {
                hash ^= (bits >>> (8 * i)) & 0xff;
                hash *= 0x100000001b3L;
            }
        }
    }

    // ---------------------------------------------------------------- the tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Playback pb = current;
        if (pb == null || !pb.playing) {
            return;
        }
        MinecraftServer server = event.getServer();
        long t = server.getTickCount() - pb.startTick;
        if (t <= pb.lastT) {
            return;   // a play run from chat lands inside the tick it started on: tick 0 is done already
        }
        ServerPlayer p = server.getPlayerList().getPlayer(pb.player);
        if (p == null) {
            LOGGER.info("[filmcam] path {} stopped: {} left", pb.name, pb.playerName);
            pb.playing = false;
            FilmCamera.release(null);
            return;
        }
        step(server, pb, p, t);
    }

    /** Evaluate the path at play-tick {@code t}, place the camera, and note any key reached. */
    static void step(MinecraftServer server, Playback pb, ServerPlayer p, long t) {
        pb.lastT = t;
        long ms = CameraKeys.msAtTick(t);
        CameraKeys.Sample sm = CameraKeys.sample(pb.path, ms);
        ServerLevel level = p.level();
        List<CameraKeys.Key> pathKeys = pb.path.keys();
        double[] look = CameraKeys.look(pb.path, sm, k -> {
            if (k.look() != null) {
                return k.look();
            }
            Entity e = resolveEntity(server, level, pb, k, sm.pos());
            pb.lookIds[k == pathKeys.get(sm.from()) ? sm.from() : sm.to()] = e == null ? FilmPathHandover.NO_ENTITY : e.getId();
            return eyes(e);
        });
        if (look == null) {
            pb.lookLostTicks++;
            look = pb.lastLook != null ? pb.lastLook : new double[] {sm.pos()[0], sm.pos()[1], sm.pos()[2] + 1};
        }
        pb.lastLook = look;
        FilmCamera.place(p, sm.pos(), look);
        handOver(p, pb, level.getGameTime() - t);
        Vec3 at = FilmCamera.where();
        float[] yp = FilmCamera.facing();
        if (at != null && yp != null) {
            pb.pos = new double[] {at.x, at.y, at.z};
            pb.yaw = yp[0];
            pb.pitch = yp[1];
        }
        // The hash covers the path's own span (one pass of a loop), so two finished plays compare
        // equal however long each was held afterwards.
        if (at != null && yp != null && ms <= pb.path.durationMs()) {
            pb.hashedTicks++;
            pb.mix(at.x);
            pb.mix(at.y);
            pb.mix(at.z);
            pb.mix(yp[0]);
            pb.mix(yp[1]);
            if (pb.readBack.size() < MAX_READ_BACK) {
                pb.readBack.add(new double[] {at.x, at.y, at.z, yp[0], yp[1]});
            }
        }
        pb.fov = sm.fov();
        pb.key = sm.from();
        List<CameraKeys.Key> keys = pb.path.keys();
        for (int i = 0; i < keys.size(); i++) {
            if (pb.reachedTick[i] < 0 && ms >= keys.get(i).atMs()) {
                long g = pb.startTick + t;
                pb.reachedTick[i] = g;
                LOGGER.info("[filmcam] path {} key {} (at_ms {}, ease {}) reached on game tick {} (play tick {}, {} ms, +{} ms)",
                        pb.name, i, keys.get(i).atMs(), keys.get(i).ease().id, g, t, ms, ms - keys.get(i).atMs());
            }
        }
        if (pb.trace) {
            LOGGER.info("[filmcam] trace {} t={} pos={} yaw={} pitch={} fov={}", pb.name, t,
                    pb.pos == null ? "-" : String.format(Locale.ROOT, "%.4f,%.4f,%.4f", pb.pos[0], pb.pos[1], pb.pos[2]),
                    pb.yaw, pb.pitch, String.format(Locale.ROOT, "%.2f", pb.fov));
        }
    }

    // ---------------------------------------------------------------- the modded viewer's copy

    /**
     * Hand the path to the viewer's client if it has our jar (a stock client is never sent anything and
     * keeps the per-tick rig), and hand it again whenever what it needs changes: the rig was made anew,
     * a key's look now resolves to a different entity, or the game time slid against the play clock
     * (a lag spike, {@code /tick freeze}). {@code start} is the level's game time on play tick 0 as
     * this tick sees it.
     */
    private static void handOver(ServerPlayer p, Playback pb, long start) {
        if (pb.sentStart == Long.MIN_VALUE || Math.abs(start - pb.startGameTime) >= 2) {
            pb.startGameTime = start;
        }
        if (!ModdedClient.can(p, ModdedClient.Channel.FILM_PATH)) {
            return;
        }
        FilmPathHandover payload = payload(pb);
        if (payload == null) {
            return;
        }
        if (payload.rigId() == pb.sentRig && pb.startGameTime == pb.sentStart
                && java.util.Arrays.equals(pb.lookIds, pb.sentLookIds)) {
            return;
        }
        Send.to(p, ModdedClient.filmPath(payload));
        pb.sentRig = payload.rigId();
        pb.sentStart = pb.startGameTime;
        pb.sentLookIds = pb.lookIds.clone();
        pb.handovers++;
        LOGGER.info("[filmcam] path {} handed to {}'s client (rig {}, start game time {}, handover {})",
                pb.name, pb.playerName, pb.sentRig, pb.sentStart, pb.handovers);
    }

    /**
     * What a modded viewer is handed for this playback as it stands, or null when the path is too
     * big to hand over (the server still plays it; the viewer sees the per-tick rig).
     */
    public static FilmPathHandover payload(Playback pb) {
        if (pb.json == null) {
            if (pb.path.keys().size() > FilmPathHandover.MAX_KEYS) {
                return null;
            }
            String j = CameraKeys.toJson(pb.path);
            if (j.length() > FilmPathHandover.MAX_JSON) {
                return null;
            }
            pb.json = j;
        }
        List<Integer> ids = new ArrayList<>(pb.lookIds.length);
        for (int id : pb.lookIds) {
            ids.add(id);
        }
        return new FilmPathHandover(true, pb.json, pb.startGameTime, FilmCamera.rigId(), ids);
    }

    /**
     * Resolve every key's look entity once, at play, so the viewer is handed the entity for a key
     * before the camera reaches it; each tick then refreshes the keys it is between.
     */
    static void preResolve(MinecraftServer server, ServerLevel level, Playback pb) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<CameraKeys.Key> keys = pb.path.keys();
        for (int i = 0; i < keys.size(); i++) {
            CameraKeys.Key k = keys.get(i);
            if (k.lookEntity() == null) {
                continue;
            }
            Integer id = seen.get(k.lookEntity());
            if (id == null) {
                Entity e = resolveEntity(server, level, pb, k, k.pos());
                id = e == null ? FilmPathHandover.NO_ENTITY : e.getId();
                seen.put(k.lookEntity(), id);
            }
            pb.lookIds[i] = id;
        }
    }

    /** A key's look as a point now: its entity's eyes, or null when there is none. */
    private static double[] eyes(Entity e) {
        if (e == null) {
            return null;
        }
        Vec3 v = e.getEyePosition();
        return new double[] {v.x, v.y, v.z};
    }

    /** The entity a key looks at now (null for a point look, or when it cannot be found). */
    private static Entity resolveEntity(MinecraftServer server, ServerLevel level, Playback pb, CameraKeys.Key k, double[] eye) {
        if (k.look() != null) {
            return null;
        }
        Entity e = null;
        UUID id = uuid(k.lookEntity());
        if (id != null) {
            e = level.getEntity(id);
        } else {
            EntitySelector sel = pb.selectors.get(k.lookEntity());
            if (sel != null) {
                try {
                    CommandSourceStack src = server.createCommandSourceStack().withLevel(level)
                            .withPosition(new Vec3(eye[0], eye[1], eye[2])).withSuppressedOutput();
                    List<? extends Entity> found = sel.findEntities(src);
                    e = found.isEmpty() ? null : found.get(0);
                } catch (CommandSyntaxException ex) {
                    e = null;
                }
            }
        }
        if (e == null || e.isRemoved() || FilmCamera.isCamera(e)) {
            return null;
        }
        return e;
    }

    private static UUID uuid(String s) {
        try {
            return s != null && s.length() == 36 ? UUID.fromString(s) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the commands

    /** {@code /moddyop filmcam …}. Added by {@code OpsCommands}. */
    public static LiteralArgumentBuilder<CommandSourceStack> opBranch() {
        return Commands.literal("filmcam")
                .executes(FilmPathPlayer::usage)
                .then(Commands.literal("path")
                        .executes(FilmPathPlayer::usage)
                        .then(Commands.literal("load")
                                .executes(FilmPathPlayer::usage)
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("source", StringArgumentType.greedyString())
                                                .executes(FilmPathPlayer::load))))
                        .then(Commands.literal("play")
                                .executes(FilmPathPlayer::usage)
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> play(ctx, false))
                                                .then(Commands.literal("trace").executes(ctx -> play(ctx, true))))))
                        .then(Commands.literal("stop")
                                .executes(FilmPathPlayer::usage)
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(FilmPathPlayer::stop)))
                        .then(Commands.literal("status").executes(FilmPathPlayer::status))
                        .then(Commands.literal("list").executes(FilmPathPlayer::list)));
    }

    private static int usage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(USAGE), false);
        return 0;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    private static int load(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        String source = StringArgumentType.getString(ctx, "source").trim();
        String json;
        if (source.startsWith("{")) {
            json = source;
        } else {
            if (!source.toLowerCase(Locale.ROOT).endsWith(".json")) {
                return fail(ctx, "filmcam load " + name + ": '" + source + "' is neither inline JSON nor a .json file");
            }
            java.nio.file.Path file = ctx.getSource().getServer().getServerDirectory().resolve(source);
            try {
                if (!Files.isRegularFile(file)) {
                    return fail(ctx, "filmcam load " + name + ": no file " + file);
                }
                if (Files.size(file) > MAX_FILE_BYTES) {
                    return fail(ctx, "filmcam load " + name + ": " + file + " is over 1 MB");
                }
                json = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return fail(ctx, "filmcam load " + name + ": cannot read " + file + ": " + e.getMessage());
            }
        }
        CameraKeys.Path path;
        try {
            path = CameraKeys.parse(json);
            selectors(path);   // a selector that does not parse is refused at load, not mid-take
        } catch (IllegalArgumentException e) {
            return fail(ctx, "filmcam load " + name + ": " + e.getMessage());
        }
        PATHS.put(name, path);
        LOGGER.info("[filmcam] path {} loaded: {}", name, CameraKeys.describe(path));
        ctx.getSource().sendSuccess(() -> Component.literal(
                "filmcam path " + name + " loaded: " + CameraKeys.describe(path)), true);
        return 1;
    }

    /** Parse each key's entity selector once; a UUID needs none. */
    static Map<String, EntitySelector> selectors(CameraKeys.Path path) {
        Map<String, EntitySelector> out = new LinkedHashMap<>();
        for (int i = 0; i < path.keys().size(); i++) {
            String s = path.keys().get(i).lookEntity();
            if (s == null || out.containsKey(s) || uuid(s) != null) {
                continue;
            }
            try {
                StringReader r = new StringReader(s);
                EntitySelector sel = new EntitySelectorParser(r, true).parse();
                if (r.canRead()) {
                    throw new IllegalArgumentException("key " + i + ": look entity '" + s + "' has extra text after the selector");
                }
                out.put(s, sel);
            } catch (CommandSyntaxException e) {
                throw new IllegalArgumentException("key " + i + ": look entity '" + s + "' is not a UUID or a selector: "
                        + e.getMessage());
            }
        }
        return out;
    }

    private static int play(CommandContext<CommandSourceStack> ctx, boolean trace) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer p = player(ctx);
        if (p == null) {
            return fail(ctx, "filmcam: no player '" + StringArgumentType.getString(ctx, "player") + "' online");
        }
        CameraKeys.Path path = PATHS.get(name);
        if (path == null) {
            return fail(ctx, "filmcam play: no path '" + name + "' (load it first; loaded: " + PATHS.keySet() + ")");
        }
        MinecraftServer server = ctx.getSource().getServer();
        Playback old = current;
        if (old != null && old.playing) {
            old.playing = false;
            ServerPlayer was = server.getPlayerList().getPlayer(old.player);
            if (was != null && !was.getUUID().equals(p.getUUID())) {
                FilmCamera.release(was);
                Send.to(was, ModdedClient.filmPath(FilmPathHandover.stop()));
            }
            LOGGER.info("[filmcam] path {} for {} stopped: {} started", old.name, old.playerName, name);
        }
        long start = server.getTickCount();
        Playback pb = new Playback(name, path, p, start, trace, selectors(path));
        current = pb;
        preResolve(server, p.level(), pb);
        step(server, pb, p, 0);   // tick 0 now, so the first frame is already the path's
        JsonObject o = new JsonObject();
        o.addProperty("filmcam", "play");
        o.addProperty("path", name);
        o.addProperty("player", pb.playerName);
        o.addProperty("start_tick", start);
        o.addProperty("ms_per_tick", CameraKeys.MS_PER_TICK);
        JsonArray keys = new JsonArray();
        for (int i = 0; i < path.keys().size(); i++) {
            JsonObject k = new JsonObject();
            k.addProperty("i", i);
            k.addProperty("at_ms", path.keys().get(i).atMs());
            k.addProperty("planned_tick", start + CameraKeys.tickReaching(path.keys().get(i).atMs()));
            keys.add(k);
        }
        o.add("keys", keys);
        LOGGER.info("[filmcam] {}", o);
        ctx.getSource().sendSuccess(() -> Component.literal(o.toString()), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = player(ctx);
        if (p == null) {
            return fail(ctx, "filmcam: no player '" + StringArgumentType.getString(ctx, "player") + "' online");
        }
        Playback pb = current;
        boolean was = stop(p);
        JsonObject o = was ? report(ctx.getSource().getServer(), pb) : new JsonObject();
        o.addProperty("filmcam", "stop");
        o.addProperty("stopped", was);
        LOGGER.info("[filmcam] {}", o);
        ctx.getSource().sendSuccess(() -> Component.literal(o.toString()), true);
        return was ? 1 : 0;
    }

    /**
     * The player named, by name or UUID. A word, not {@code EntityArgument.player()}: vanilla's selector
     * refuses a bare UUID there ("only players may be affected"), and the runner may well send one.
     */
    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) {
        String s = StringArgumentType.getString(ctx, "player");
        var list = ctx.getSource().getServer().getPlayerList();
        UUID id = uuid(s);
        return id != null ? list.getPlayer(id) : list.getPlayerByName(s);
    }

    /** Stop {@code p}'s playback, if it is his, and give him his own eyes back. True if one stopped. */
    public static boolean stop(ServerPlayer p) {
        Playback pb = current;
        boolean was = pb != null && pb.playing && pb.player.equals(p.getUUID());
        if (was) {
            pb.playing = false;
            Send.to(p, ModdedClient.filmPath(FilmPathHandover.stop()));
        }
        if (was || pb == null || !pb.playing) {   // never take the camera from SOMEONE ELSE's play
            FilmCamera.release(p);
        }
        return was;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Playback pb = current;
        JsonObject o = pb == null ? new JsonObject() : report(ctx.getSource().getServer(), pb);
        o.addProperty("filmcam", "status");
        if (pb == null) {
            o.addProperty("playing", false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(o.toString()), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = new ArrayList<>();
        PATHS.forEach((n, p) -> lines.add(n + " (" + CameraKeys.describe(p) + ")"));
        ctx.getSource().sendSuccess(() -> Component.literal(
                "filmcam paths: " + (lines.isEmpty() ? "none" : String.join("; ", lines))), false);
        return lines.size();
    }

    /** The playback as one JSON object: where the camera is and when each key was reached. */
    static JsonObject report(MinecraftServer server, Playback pb) {
        JsonObject o = new JsonObject();
        o.addProperty("path", pb.name);
        o.addProperty("player", pb.playerName);
        o.addProperty("playing", pb.playing);
        o.addProperty("start_tick", pb.startTick);
        o.addProperty("game_tick", server.getTickCount());
        o.addProperty("tick", pb.lastT);
        o.addProperty("ms", CameraKeys.msAtTick(pb.lastT));
        o.addProperty("done", !pb.path.loop() && CameraKeys.msAtTick(pb.lastT) >= pb.path.durationMs());
        o.addProperty("key", pb.key);
        if (pb.pos != null) {
            JsonArray pos = new JsonArray();
            for (double d : pb.pos) {
                pos.add(d);
            }
            o.add("pos", pos);
            o.addProperty("yaw", pb.yaw);
            o.addProperty("pitch", pb.pitch);
        }
        o.addProperty("fov", Math.round(pb.fov * 100) / 100.0);
        o.addProperty("look_lost_ticks", pb.lookLostTicks);
        o.addProperty("trace_hash", String.format(Locale.ROOT, "%016x", pb.hash));
        o.addProperty("hashed_ticks", pb.hashedTicks);
        o.addProperty("handovers", pb.handovers);
        JsonArray keys = new JsonArray();
        for (int i = 0; i < pb.path.keys().size(); i++) {
            JsonObject k = new JsonObject();
            long at = pb.path.keys().get(i).atMs();
            k.addProperty("i", i);
            k.addProperty("at_ms", at);
            k.addProperty("planned_tick", pb.startTick + CameraKeys.tickReaching(at));
            if (pb.reachedTick[i] >= 0) {
                k.addProperty("reached_tick", pb.reachedTick[i]);
            }
            keys.add(k);
        }
        o.add("keys", keys);
        return o;
    }
}
