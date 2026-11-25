package com.moddingfromamod.redstone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.AgentBridge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The redstone rubric (docs/REDSTONE.md §5), graded on the SAVED world, independently of the tool.</b>
 *
 * <p>Two halves. {@link #snapshot} runs in the wish's own run, after the setup (his buildings) and before the wish:
 * it writes every block in the area. The grade runs in a SECOND server boot on the same world (so a mechanism that
 * does not survive a reload fails here): it diffs the world against the snapshot, finds what the wish added, fires
 * every new control ITSELF, and checks what the case asked for. It reads the tool's journal only for the reported
 * footprint (side effect S1 is "outside what it said it changed"); it never trusts the tool's own proof.
 *
 * <p>Switched on by {@code -Dmoddingfromamod.rsgrade=<case.json>} with {@code rsgradeout=<dir>}; it halts the server
 * when done. Never on in play.
 */
@EventBusSubscriber
public final class RubricGrader {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/rubric");
    private static final String P = "moddingfromamod.";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RubricGrader() {
    }

    // ── the snapshot ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Writes every non-air block in {@code box} ({x0,y0,z0,x1,y1,z1}), with the named buildings' boxes, to
     * {@code out}. Server thread.
     */
    public static void snapshot(ServerLevel level, int[] box, Map<String, int[]> buildings, Path out) {
        JsonObject o = new JsonObject();
        o.add("box", ints(box));
        JsonObject b = new JsonObject();
        buildings.forEach((k, v) -> b.add(k, ints(v)));
        o.add("buildings", b);
        JsonObject blocks = new JsonObject();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = box[0]; x <= box[3]; x++) {
            for (int y = box[1]; y <= box[4]; y++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (!s.isAir()) {
                        blocks.addProperty(x + "," + y + "," + z, BlockStateParser.serialize(s));
                    }
                }
            }
        }
        o.add("blocks", blocks);
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, o.toString());
        } catch (Exception e) {
            LOGGER.warn("could not write the snapshot {}: {}", out, e.toString());
        }
    }

    static JsonArray ints(int[] a) {
        JsonArray j = new JsonArray();
        for (int v : a) {
            j.add(v);
        }
        return j;
    }

    static int[] ints(JsonArray a) {
        int[] v = new int[a.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = a.get(i).getAsInt();
        }
        return v;
    }

    // ── the grade boot ──────────────────────────────────────────────────────────────────────────────────

    private static volatile boolean started;
    private static int ticks;

    public static boolean active() {
        return !System.getProperty(P + "rsgrade", "").isEmpty();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!active() || started) {
            return;
        }
        if (++ticks < 60) {          // let the world load and settle
            return;
        }
        started = true;
        MinecraftServer server = event.getServer();
        Thread t = new Thread(() -> {
            JsonObject verdict;
            try {
                verdict = grade(server, Path.of(System.getProperty(P + "rsgrade")));
            } catch (Throwable e) {
                verdict = new JsonObject();
                verdict.addProperty("verdict", "ERROR");
                verdict.addProperty("why", "the grader threw " + e);
                LOGGER.error("the rubric grader threw", e);
            }
            try {
                Path out = Path.of(System.getProperty(P + "rsgradeout", "."));
                Files.createDirectories(out);
                Files.writeString(out.resolve("verdict.json"), GSON.toJson(verdict));
            } catch (Exception e) {
                LOGGER.error("could not write the verdict: {}", e.toString());
            }
            LOGGER.info("RUBRIC {}", verdict);
            server.execute(() -> server.halt(false));
        }, "rubric-grader");
        t.setDaemon(true);
        t.start();
    }

    /**
     * One case. Its JSON: {@code snapshot} (the file {@link #snapshot} wrote), {@code building} (whose door it is),
     * {@code controls}: [{kind, side: outside|inside|any, maxDist}], {@code onIs}: closed|open (what a control
     * switched ON does to the doorway), {@code cycles} (default 3).
     */
    static JsonObject grade(MinecraftServer server, Path caseFile) throws Exception {
        JsonObject spec = JsonParser.parseString(Files.readString(caseFile)).getAsJsonObject();
        JsonObject snap = JsonParser.parseString(Files.readString(caseFile.resolveSibling(
                spec.get("snapshot").getAsString()))).getAsJsonObject();
        ServerLevel level = server.overworld();
        int[] box = ints(snap.getAsJsonArray("box"));
        String building = spec.get("building").getAsString();
        int[] bbox = ints(snap.getAsJsonObject("buildings").getAsJsonArray(building));
        Map<BlockPos, String> before = new HashMap<>();
        for (var e : snap.getAsJsonObject("blocks").entrySet()) {
            String[] p = e.getKey().split(",");
            before.put(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])),
                    e.getValue().getAsString());
        }
        JsonObject v = new JsonObject();
        List<String> notes = new ArrayList<>();
        // no player is on in this boot: keep the area ticking, as a child standing there would
        AgentBridge.onServer(() -> {
            for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
                for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                }
            }
            return null;
        }, 30_000L);

        // the diff (server thread): every position whose block changed
        Map<BlockPos, String[]> diff = AgentBridge.onServer(() -> {
            Map<BlockPos, String[]> d = new LinkedHashMap<>();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int x = box[0]; x <= box[3]; x++) {
                for (int y = box[1]; y <= box[4]; y++) {
                    for (int z = box[2]; z <= box[5]; z++) {
                        m.set(x, y, z);
                        String was = before.getOrDefault(m, "minecraft:air");
                        String now = BlockStateParser.serialize(level.getBlockState(m));
                        if (!was.equals(now)) {
                            d.put(m.immutable(), new String[] {was, now});
                        }
                    }
                }
            }
            return d;
        }, 30_000L);
        v.addProperty("changed", diff.size());

        // the reported footprint: every mechanism journal position (the tool's own report)
        Set<BlockPos> footprint = new HashSet<>();
        AgentBridge.onServer(() -> {
            for (MechanismStore.Entry e : MechanismStore.all(level)) {
                e.journal().forEach(p -> footprint.add(p.pos()));
            }
            return null;
        }, 30_000L);

        // the building's original doorway: its door blocks on the ground storey, in the snapshot
        List<BlockPos> doorway = new ArrayList<>();
        for (var e : before.entrySet()) {
            BlockPos p = e.getKey();
            if (e.getValue().contains("_door[") && e.getValue().contains("half=lower") && inBox(p, bbox)
                    && p.getY() <= bbox[1] + 3) {
                doorway.add(p);
                doorway.add(p.above());
            }
        }

        // what was added: controls and pistons
        List<BlockPos> controls = new ArrayList<>();
        List<String> controlKinds = new ArrayList<>();
        int pistons = 0;
        for (var e : diff.entrySet()) {
            String now = e.getValue()[1];
            String id = now.contains("[") ? now.substring(0, now.indexOf('[')) : now;
            if (id.equals("minecraft:lever") || id.endsWith("_button") || id.endsWith("_pressure_plate")) {
                controls.add(e.getKey());
                controlKinds.add(id.equals("minecraft:lever") ? "lever" : id.endsWith("_button") ? "button"
                        : "pressure_plate");
            }
            if (id.equals("minecraft:sticky_piston") || id.equals("minecraft:piston")) {
                pistons++;
            }
        }

        // F1 built
        boolean f1 = pistons > 0 && !controls.isEmpty();
        v.addProperty("F1_built", f1);
        if (!f1) {
            notes.add("no mechanism found: " + pistons + " pistons and " + controls.size() + " controls were added");
        }

        // F3 what was wished: the door in the building's own doorway, controls of the kinds and places asked
        boolean f3 = true;
        if (doorway.isEmpty()) {
            notes.add("the building had no door in the snapshot; F3 judged on the controls only");
        } else if (diff.keySet().stream().noneMatch(doorway::contains)) {
            f3 = false;
            notes.add("nothing changed in the building's own doorway " + doorway.get(0).toShortString()
                    + ": the door went somewhere else");
        }
        BlockPos dc = doorway.isEmpty() ? null : doorway.get(0);
        for (JsonElement ce : spec.getAsJsonArray("controls")) {
            JsonObject c = ce.getAsJsonObject();
            String kind = c.get("kind").getAsString();
            String side = c.has("side") ? c.get("side").getAsString() : "any";
            int maxDist = c.has("maxDist") ? c.get("maxDist").getAsInt() : 12;
            boolean found = false;
            for (int i = 0; i < controls.size(); i++) {
                BlockPos p = controls.get(i);
                if (!controlKinds.get(i).equals(kind)) {
                    continue;
                }
                boolean outside = !inBox(p, bbox);
                boolean sideOk = side.equals("any") || side.equals("outside") == outside;
                boolean near = dc == null || Math.sqrt(p.distSqr(dc)) <= maxDist;
                if (sideOk && near) {
                    found = true;
                }
            }
            if (!found) {
                f3 = false;
                notes.add("no " + kind + " " + side + " within " + maxDist + " of the door (added: " + controlKinds
                        + " at " + controls + ")");
            }
        }
        v.addProperty("F3_as_wished", f3);

        // F2 works and F4 usable: fire every new control ourselves, 3 cycles; the doorway open at rest (usable)
        boolean f2 = false, f4 = false;
        if (f1 && !doorway.isEmpty()) {
            String on = spec.has("onIs") && spec.get("onIs").getAsString().equals("open") ? "#open" : "#shut";
            String off = on.equals("#open") ? "#shut" : "#open";
            List<Expect> expects = new ArrayList<>();
            for (BlockPos p : doorway) {
                expects.add(new Expect(LevelView.rs(p), off, on, "the building's doorway"));
            }
            List<Control> cs = new ArrayList<>();
            for (int i = 0; i < controls.size(); i++) {
                cs.add(new Control(controlKinds.get(i), LevelView.rs(controls.get(i)), List.of(), null, 0));
            }
            MechanismPlan plan = new MechanismPlan("graded", List.of(), expects, cs, List.of(), List.of(), null);
            int cycles = spec.has("cycles") ? spec.get("cycles").getAsInt() : 3;
            MechanismProof.Result r = MechanismProof.prove(level, plan, cycles);
            f2 = r.ok();
            v.addProperty("F2_works", f2);
            if (!f2) {
                notes.add("firing the controls ourselves: " + r.why());
            }
            // usable: at rest (every control off) the doorway is open, which the proof's rest state checked
            f4 = f2 && off.equals("#open");
            if (f2 && !off.equals("#open")) {
                notes.add("the doorway is SHUT at rest: nobody can get in until a switch is used");
            }
        } else {
            v.addProperty("F2_works", false);
        }
        v.addProperty("F4_usable", f4);

        // side effects
        int s1 = 0, s5 = 0;
        boolean s3 = false;
        List<String> s1Where = new ArrayList<>();
        for (var e : diff.entrySet()) {
            BlockPos p = e.getKey();
            if (footprint.contains(p) || doorway.contains(p)) {
                continue;
            }
            String was = e.getValue()[0], now = e.getValue()[1];
            if (now.startsWith("minecraft:water") || now.startsWith("minecraft:lava") || now.startsWith("minecraft:fire")) {
                s3 = true;
            }
            if (!was.equals("minecraft:air") && !natural(was)) {
                s1++;
                if (s1Where.size() < 8) {
                    s1Where.add(was + " at " + p.toShortString() + " is now " + now);
                }
            } else {
                s5++;
            }
        }
        v.addProperty("S1_player_blocks_lost", s1);
        if (s1 > 0) {
            notes.add("player blocks changed outside the reported footprint: " + s1Where);
        }
        v.addProperty("S3_environment", s3);
        v.addProperty("S5_other_changes", s5);

        // S4: a clock nobody asked for. Watch the footprint at rest for 60 ticks: nothing may change.
        Map<BlockPos, String> rest = AgentBridge.onServer(() -> states(level, footprint), 10_000L);
        waitTicks(server, 60);
        Map<BlockPos, String> later = AgentBridge.onServer(() -> states(level, footprint), 10_000L);
        boolean s4 = !rest.equals(later);
        v.addProperty("S4_runs_by_itself", s4);
        if (s4) {
            notes.add("the mechanism changes by itself at rest (a clock)");
        }

        boolean hard = s1 > 0 || s3 || s4;
        String verdict = f1 && f2 && f3 && f4 && !hard ? "PASS" : f1 && f2 && !hard ? "PARTIAL" : "FAIL";
        v.addProperty("verdict", verdict);
        JsonArray n = new JsonArray();
        notes.forEach(n::add);
        v.add("notes", n);
        return v;
    }

    static Map<BlockPos, String> states(ServerLevel level, Set<BlockPos> at) {
        Map<BlockPos, String> m = new HashMap<>();
        for (BlockPos p : at) {
            m.put(p, BlockStateParser.serialize(level.getBlockState(p)));
        }
        return m;
    }

    static boolean inBox(BlockPos p, int[] b) {
        return p.getX() >= b[0] && p.getX() <= b[3] && p.getY() >= b[1] && p.getY() <= b[4] && p.getZ() >= b[2]
                && p.getZ() <= b[5];
    }

    /** Ground and growing things: what a dig through the soil may change without counting as his blocks. */
    static boolean natural(String state) {
        String id = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
        return id.matches("minecraft:(dirt|grass_block|coarse_dirt|podzol|rooted_dirt|stone|deepslate|granite|diorite"
                + "|andesite|tuff|gravel|sand|red_sand|sandstone|clay|short_grass|tall_grass|fern|large_fern|dandelion"
                + "|poppy|snow|water|seagrass|kelp|kelp_plant|.*_leaves|.*_log|vine|dead_bush|sugar_cane"
                + "|calcite|mud|moss_block|moss_carpet)");
    }

    /** Off the server thread: waits until the server has ticked {@code n} more times (or 60 s pass). */
    static void waitTicks(MinecraftServer server, int n) {
        int start = AgentBridge.onServer(server::getTickCount, 10_000L);
        long deadline = System.currentTimeMillis() + 60_000L;
        while (AgentBridge.onServer(server::getTickCount, 10_000L) < start + n && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
