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

    /** The property that switches, for each kind of output the grader knows. */
    static final Map<String, String> SWITCH = Map.ofEntries(
            Map.entry("minecraft:redstone_lamp", "lit"), Map.entry("minecraft:iron_door", "open"),
            Map.entry("minecraft:iron_trapdoor", "open"), Map.entry("minecraft:dispenser", "triggered"),
            Map.entry("minecraft:dropper", "triggered"), Map.entry("minecraft:note_block", "powered"),
            Map.entry("minecraft:sticky_piston", "extended"), Map.entry("minecraft:piston", "extended"));

    /** The case's word for a kind of output, from a block id. */
    static String outputKind(String id) {
        if (id.equals("minecraft:redstone_lamp")) {
            return "lamp";
        }
        if (id.endsWith("_door")) {
            return "door";
        }
        if (id.endsWith("_fence_gate")) {
            return "gate";
        }
        if (id.endsWith("_trapdoor")) {
            return "trapdoor";
        }
        if (id.equals("minecraft:dispenser")) {
            return "dispenser";
        }
        if (id.equals("minecraft:dropper")) {
            return "dropper";
        }
        if (id.equals("minecraft:note_block")) {
            return "note_block";
        }
        if (id.equals("minecraft:sticky_piston") || id.equals("minecraft:piston")) {
            return "piston";
        }
        return null;
    }

    static String switchProp(String id) {
        if (SWITCH.containsKey(id)) {
            return SWITCH.get(id);
        }
        return id.endsWith("_fence_gate") || id.endsWith("_trapdoor") || id.endsWith("_door") ? "open" : null;
    }

    static String idOf(String state) {
        return state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
    }

    static String propOf(String state, String prop) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\[,]" + prop + "=([a-z0-9_]+)").matcher(state);
        return m.find() ? m.group(1) : null;
    }

    /**
     * One case. Its JSON: {@code snapshot} (the file {@link #snapshot} wrote); optional {@code building} (the id whose
     * doorway, box and sides the case speaks of); {@code outputs}: [{kind: lamp|door|gate|trapdoor|dispenser|
     * dropper|note_block|piston, min, maxDist}] what the wish must have added; {@code controls}: [{kind (or any),
     * side: outside|inside|any, maxDist}]; optional {@code items} (an item some added dispenser or dropper holds);
     * {@code onIs}: open|closed for a door (what a control switched ON does), on|off otherwise; {@code cycles}.
     */
    static JsonObject grade(MinecraftServer server, Path caseFile) throws Exception {
        JsonObject spec = JsonParser.parseString(Files.readString(caseFile)).getAsJsonObject();
        JsonObject snap = JsonParser.parseString(Files.readString(caseFile.resolveSibling(
                spec.get("snapshot").getAsString()))).getAsJsonObject();
        ServerLevel level = server.overworld();
        int[] box = ints(snap.getAsJsonArray("box"));
        String building = spec.has("building") && !spec.get("building").isJsonNull()
                ? spec.get("building").getAsString() : null;
        int[] bbox = building != null && snap.getAsJsonObject("buildings").has(building)
                ? ints(snap.getAsJsonObject("buildings").getAsJsonArray(building)) : null;
        Map<BlockPos, String> before = new HashMap<>();
        for (var e : snap.getAsJsonObject("blocks").entrySet()) {
            String[] p = e.getKey().split(",");
            before.put(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])),
                    e.getValue().getAsString());
        }
        JsonObject v = new JsonObject();
        List<String> notes = new ArrayList<>();
        AgentBridge.onServer(() -> {
            for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
                for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                }
            }
            return null;
        }, 30_000L);
        waitTicks(server, 20);

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
        Set<BlockPos> footprint = new HashSet<>();
        AgentBridge.onServer(() -> {
            for (MechanismStore.Entry e : MechanismStore.all(level)) {
                e.journal().forEach(p -> footprint.add(p.pos()));
            }
            return null;
        }, 30_000L);

        // the building's original doorway (its door blocks on the ground storey), for door cases
        List<BlockPos> doorway = new ArrayList<>();
        boolean ownDoorway = spec.has("ownDoorway") && spec.get("ownDoorway").getAsBoolean();
        if (bbox != null && ownDoorway) {
            for (var e : before.entrySet()) {
                BlockPos p = e.getKey();
                if (e.getValue().contains("_door[") && e.getValue().contains("half=lower") && inBox(p, bbox)
                        && p.getY() <= bbox[1] + 3) {
                    doorway.add(p);
                    doorway.add(p.above());
                }
            }
        }

        // the logic rules the mechanisms carry: their parts (code buttons) are not wired controls, so they are not
        // fired as switches; each rule is tested by its own real stimulus below
        List<JsonObject> rules = new ArrayList<>();
        Set<BlockPos> logicParts = new HashSet<>();
        AgentBridge.onServer(() -> {
            for (MechanismStore.Entry e : MechanismStore.all(level)) {
                for (JsonElement l : e.logic()) {
                    JsonObject o = l.getAsJsonObject();
                    rules.add(o);
                    if (o.has("buttons")) {
                        o.getAsJsonArray("buttons").forEach(b -> logicParts.add(MechanismLogic.pos(b)));
                    }
                }
            }
            return null;
        }, 10_000L);
        // what was added: outputs by kind, and controls
        Map<String, List<BlockPos>> outputs = new HashMap<>();
        List<BlockPos> controls = new ArrayList<>();
        List<String> controlKinds = new ArrayList<>();
        boolean toggles = false;
        for (var e : diff.entrySet()) {
            String now = e.getValue()[1];
            String id = idOf(now);
            if ((id.equals("minecraft:lever") || id.endsWith("_button") || id.endsWith("_pressure_plate"))
                    && !logicParts.contains(e.getKey())) {
                controls.add(e.getKey());
                controlKinds.add(id.equals("minecraft:lever") ? "lever" : id.endsWith("_button") ? "button"
                        : "pressure_plate");
            }
            if (id.contains("copper_bulb")) {
                toggles = true;
            }
            String k = outputKind(id);
            if (k != null && !(id.endsWith("_door") && propOf(now, "half") != null
                    && !"lower".equals(propOf(now, "half")))) {
                outputs.computeIfAbsent(k, x -> new ArrayList<>()).add(e.getKey());
            }
        }
        // a piston door shows as pistons; it counts as a door for the case
        if (outputs.containsKey("piston") && !outputs.containsKey("door")) {
            outputs.put("door", outputs.get("piston"));
        }
        JsonObject counted = new JsonObject();
        outputs.forEach((k, l) -> counted.addProperty(k, l.size()));
        v.add("added_outputs", counted);
        v.addProperty("added_controls", controls.size());

        // F1 built: every output kind the case names, and a control
        boolean f1 = !controls.isEmpty() || !rules.isEmpty();
        JsonArray wanted = spec.has("outputs") ? spec.getAsJsonArray("outputs") : new JsonArray();
        for (JsonElement oe : wanted) {
            String k = oe.getAsJsonObject().get("kind").getAsString();
            if (alts(k).stream().noneMatch(outputs::containsKey)) {
                f1 = false;
                notes.add("no " + k + " was added");
            }
        }
        if (controls.isEmpty() && rules.isEmpty()) {
            notes.add("no switch, button, plate or logic condition was added");
        }
        v.addProperty("F1_built", f1);

        // F3 as wished: counts, distances, controls' kinds and sides, items
        boolean f3 = true;
        for (JsonElement oe : wanted) {
            JsonObject o = oe.getAsJsonObject();
            String k = o.get("kind").getAsString();
            int min = o.has("min") ? o.get("min").getAsInt() : 1;
            int maxDist = o.has("maxDist") ? o.get("maxDist").getAsInt() : 0;
            List<BlockPos> got = new ArrayList<>();
            alts(k).forEach(a -> got.addAll(outputs.getOrDefault(a, List.of())));
            List<BlockPos> near = got.stream().filter(p -> maxDist <= 0 || bbox == null || distToBox(p, bbox) <= maxDist)
                    .toList();
            if (near.size() < min) {
                f3 = false;
                notes.add("wanted at least " + min + " " + k + (maxDist > 0 ? " within " + maxDist + " of " + building : "")
                        + ", found " + near.size());
            }
        }
        if (k3(spec, "door") && !doorway.isEmpty() && diff.keySet().stream().noneMatch(doorway::contains)) {
            f3 = false;
            notes.add("nothing changed in the building's own doorway " + doorway.get(0).toShortString()
                    + ": the door went somewhere else");
        }
        BlockPos ref = !doorway.isEmpty() ? doorway.get(0) : bbox != null
                ? new BlockPos((bbox[0] + bbox[3]) / 2, bbox[1], (bbox[2] + bbox[5]) / 2) : null;
        JsonArray wantedControls = spec.has("controls") ? spec.getAsJsonArray("controls") : new JsonArray();
        for (JsonElement ce : wantedControls) {
            JsonObject c = ce.getAsJsonObject();
            String kind = c.has("kind") ? c.get("kind").getAsString() : "any";
            String side = c.has("side") ? c.get("side").getAsString() : "any";
            int maxDist = c.has("maxDist") ? c.get("maxDist").getAsInt() : 16;
            int needN = c.has("min") ? c.get("min").getAsInt() : 1;
            int foundN = 0;
            boolean found = false;
            for (int i = 0; i < controls.size(); i++) {
                BlockPos p = controls.get(i);
                if (!kind.equals("any") && !controlKinds.get(i).equals(kind)) {
                    continue;
                }
                boolean outside = bbox == null || !inBox(p, bbox);
                boolean sideOk = side.equals("any") || side.equals("outside") == outside;
                boolean near = ref == null || Math.sqrt(p.distSqr(ref)) <= maxDist;
                if (sideOk && near) {
                    foundN++;
                }
            }
            found = foundN >= needN;
            if (!found) {
                f3 = false;
                notes.add("no " + kind + " " + side + " within " + maxDist + " (added: " + controlKinds + " at "
                        + controls + ")");
            }
            if (c.has("toggle") && c.get("toggle").getAsBoolean() && !toggles) {
                f3 = false;
                notes.add("the switch should toggle (press on, press off) and nothing toggles");
            }
        }
        if (spec.has("items")) {
            String item = spec.get("items").getAsString();
            boolean has = AgentBridge.onServer(() -> {
                for (String k : List.of("dispenser", "dropper")) {
                    for (BlockPos p : outputs.getOrDefault(k, List.of())) {
                        if (level.getBlockEntity(p) instanceof net.minecraft.world.Container box2) {
                            for (int i = 0; i < box2.getContainerSize(); i++) {
                                var st = box2.getItem(i);
                                if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(item)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                return false;
            }, 10_000L);
            if (!has) {
                f3 = false;
                notes.add("no dispenser or dropper holds " + item);
            }
        }

        // F2 works: fire every added control ourselves; every added output of a wanted kind must switch with it
        boolean f2 = false, f4 = true;
        String onIs = spec.has("onIs") ? spec.get("onIs").getAsString() : "on";
        if (f1) {
            List<Expect> expects = new ArrayList<>();
            Map<BlockPos, String> rest = AgentBridge.onServer(() -> {
                Map<BlockPos, String> r = new HashMap<>();
                for (var en : outputs.entrySet()) {
                    for (BlockPos p : en.getValue()) {
                        r.put(p, BlockStateParser.serialize(level.getBlockState(p)));
                    }
                }
                for (BlockPos p : doorway) {
                    r.put(p, BlockStateParser.serialize(level.getBlockState(p)));
                }
                return r;
            }, 10_000L);
            Boolean doorOpenAtRest = null;     // known only from an iron door's state or his own doorway
            for (JsonElement oe : wanted) {
                String kk = oe.getAsJsonObject().get("kind").getAsString();
                List<BlockPos> those = new ArrayList<>();
                alts(kk).forEach(a -> those.addAll(outputs.getOrDefault(a, List.of())));
                String k = kk.contains("door") ? "door" : kk;
                for (BlockPos p : those) {
                    String st = rest.get(p);
                    String id = idOf(st), prop = switchProp(id);
                    if (prop == null || propOf(st, prop) == null) {
                        continue;
                    }
                    boolean val = Boolean.parseBoolean(propOf(st, prop));
                    expects.add(new Expect(LevelView.rs(p), id + "[" + prop + "=" + val + "]",
                            id + "[" + prop + "=" + !val + "]", "an added " + k));
                    if (k.equals("door") && prop.equals("open")) {
                        doorOpenAtRest = val;
                    }
                }
            }
            if (k3(spec, "door") && !doorway.isEmpty() && outputs.containsKey("piston")) {
                boolean openNow = AgentBridge.onServer(() -> !level.getBlockState(doorway.get(0)).blocksMotion(),
                        10_000L);
                doorOpenAtRest = openNow;
                for (BlockPos p : doorway) {
                    expects.add(new Expect(LevelView.rs(p), openNow ? "#open" : "#shut", openNow ? "#shut" : "#open",
                            "the building's doorway"));
                }
            }
            List<Control> cs = new ArrayList<>();
            for (int i = 0; i < controls.size(); i++) {
                cs.add(new Control(controlKinds.get(i), LevelView.rs(controls.get(i)), List.of(), null, 0, toggles));
            }
            MechanismPlan plan = new MechanismPlan("graded", List.of(), expects, cs, List.of(), List.of(), null);
            int cycles = spec.has("cycles") ? spec.get("cycles").getAsInt() : 3;
            MechanismProof.Result r = cs.isEmpty() ? new MechanismProof.Result(null, List.of())
                    : MechanismProof.prove(level, plan, cycles);
            f2 = r.ok() && !expects.isEmpty();
            // each logic rule by its real stimulus: the outputs must switch on, then come back
            for (JsonObject rule : rules) {
                String why = stimulate(server, level, rule, expects);
                JsonObject one = new JsonObject();
                one.addProperty("rule", rule.getAsJsonObject("rule").get("kind").getAsString());
                one.addProperty("result", why == null ? "works" : why);
                logicResults.add(one);
                if (why != null && !why.startsWith("untested")) {
                    f2 = false;
                    notes.add("the logic (" + one.get("rule").getAsString() + "): " + why);
                } else if (why != null) {
                    notes.add(why);
                }
            }
            if (!r.ok()) {
                notes.add("firing the controls ourselves: " + r.why());
            }
            if (expects.isEmpty()) {
                notes.add("nothing added that a switch can be seen to change");
            }
            // the door's sense: onIs open means a switch ON opens it, so it rests SHUT
            if (k3(spec, "door") && doorOpenAtRest != null) {
                boolean wantShutAtRest = onIs.equals("open");
                if (doorOpenAtRest == wantShutAtRest) {
                    f3 = false;
                    notes.add("the door rests " + (doorOpenAtRest ? "open" : "shut") + " but the wish wants a switch to "
                            + onIs + " it");
                }
                // usable: a door shut at rest opens from BOTH sides of the building
                if (!doorOpenAtRest && bbox != null) {
                    boolean out = controls.stream().anyMatch(p -> !inBox(p, bbox));
                    boolean in = controls.stream().anyMatch(p -> inBox(p, bbox));
                    f4 = out && in;
                    if (!f4) {
                        notes.add("the door rests shut and opens only from the " + (in ? "inside" : "outside"));
                    }
                }
            }
        }
        v.addProperty("F2_works", f2);
        v.addProperty("F3_as_wished", f3);
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
            if (now.startsWith("minecraft:lava") || now.startsWith("minecraft:fire")
                    || now.startsWith("minecraft:water") && !was.startsWith("minecraft:water")) {
                s3 = true;
            }
            boolean byOtherTools = bbox != null && inBox(p, bbox);   // a change inside his building
            if (!was.equals("minecraft:air") && !natural(was) && byOtherTools) {
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
            notes.add("his building's blocks changed outside what the mechanism reported: " + s1Where);
        }
        v.addProperty("S3_environment", s3);
        v.addProperty("S5_other_changes", s5);
        Map<BlockPos, String> rest2 = AgentBridge.onServer(() -> states(level, footprint), 10_000L);
        waitTicks(server, 60);
        Map<BlockPos, String> later = AgentBridge.onServer(() -> states(level, footprint), 10_000L);
        boolean s4 = !rest2.equals(later);
        v.addProperty("S4_runs_by_itself", s4);
        if (s4) {
            notes.add("the mechanism changes by itself at rest (a clock)");
        }
        boolean hard = s1 > 0 || s3 || s4;
        String verdict = f1 && f2 && f3 && f4 && !hard ? "PASS" : f1 && f2 && !hard ? "PARTIAL" : "FAIL";
        if (spec.has("manual") && spec.get("manual").getAsBoolean()) {
            verdict = "MANUAL";      // a wish this grader cannot judge (not a redstone output): judged by a person
        }
        v.addProperty("verdict", verdict);
        JsonArray n = new JsonArray();
        notes.forEach(n::add);
        v.add("notes", n);
        return v;
    }

    static List<String> alts(String kind) {
        return List.of(kind.split("\\|"));
    }

    /**
     * Gives a logic rule its real stimulus and checks the outputs switch on (within the fire limit) and come back.
     * Null when it works; "untested: …" when this boot cannot give it (a player walking up).
     */
    static String stimulate(MinecraftServer server, ServerLevel level, JsonObject o, List<Expect> expects) {
        JsonObject rule = o.getAsJsonObject("rule");
        String kind = rule.get("kind").getAsString();
        if (o.has("disabled") && o.get("disabled").getAsBoolean()) {
            return "untested: built switched off (over the world's clock limit)";
        }
        java.util.function.Supplier<String> fired = () -> AgentBridge.onServer(
                () -> MechanismWorld.check(level, expects, true), 10_000L);
        java.util.function.Supplier<String> rest = () -> AgentBridge.onServer(
                () -> MechanismWorld.check(level, expects, false), 10_000L);
        int onFor = rule.has("onFor") ? rule.get("onFor").getAsInt() : 100;
        switch (kind) {
            case "player_near" -> {
                return "untested: a player walking up cannot be staged in the grading boot (the condition is unit-tested)";
            }
            case "paid" -> {
                BlockPos slot = MechanismLogic.pos(o.get("slot"));
                String item = rule.get("item").getAsString();
                int count = rule.has("count") ? rule.get("count").getAsInt() : 1;
                AgentBridge.onServer(() -> {
                    if (level.getBlockEntity(slot) instanceof net.minecraft.world.Container c) {
                        c.setItem(0, new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.getValue(
                                net.minecraft.resources.ResourceLocation.parse(item)), count));
                    }
                    return null;
                }, 10_000L);
            }
            case "code" -> {
                JsonArray buttons = o.getAsJsonArray("buttons");
                for (JsonElement c : rule.getAsJsonArray("code")) {
                    BlockPos b = MechanismLogic.pos(buttons.get(c.getAsInt()));
                    AgentBridge.onServer(() -> {
                        var st = level.getBlockState(b);
                        if (st.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock bb) {
                            bb.press(st, level, b, null);
                        }
                        return null;
                    }, 10_000L);
                    waitTicks(server, 30);           // a stone button comes up after 20 ticks
                }
            }
            case "target_hit" -> {
                BlockPos t = MechanismLogic.pos(o.get("target"));
                AgentBridge.onServer(() -> {
                    var arrow = net.minecraft.world.entity.EntityType.ARROW.create(level,
                            net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    arrow.setPos(t.getX() + 0.5, t.getY() + 3.5, t.getZ() + 0.5);
                    arrow.setDeltaMovement(0, -1.5, 0);
                    level.addFreshEntity(arrow);
                    return null;
                }, 10_000L);
            }
            case "mob_near" -> {
                BlockPos sensor = MechanismLogic.pos(o.get("sensor"));
                AgentBridge.onServer(() -> {
                    var z = net.minecraft.world.entity.EntityType.ZOMBIE.create(level,
                            net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    z.setPos(sensor.getX() + 0.5, sensor.getY(), sensor.getZ() + 0.5);
                    z.setNoAi(true);
                    z.addTag("moddy_grader_mob");
                    level.addFreshEntity(z);
                    return null;
                }, 10_000L);
            }
            case "night" -> AgentBridge.onServer(() -> {
                level.setDayTime(18000);
                return null;
            }, 10_000L);
            case "day" -> AgentBridge.onServer(() -> {
                level.setDayTime(18000);              // its REST is night: start there, then make it day
                return null;
            }, 10_000L);
            default -> {
            }
        }
        if (kind.equals("day")) {
            waitTicks(server, 20);
            AgentBridge.onServer(() -> {
                level.setDayTime(1000);
                return null;
            }, 10_000L);
        }
        int limit = kind.equals("every") ? rule.get("period").getAsInt() + 40 : 80;
        boolean on = false;
        for (int t = 0; t < limit && !on; t += 2) {
            waitTicks(server, 2);
            on = fired.get() == null;
        }
        if (!on) {
            return "its stimulus did not switch it on: " + fired.get();
        }
        // and back
        switch (kind) {
            case "mob_near" -> AgentBridge.onServer(() -> {
                level.getEntities().getAll().forEach(e -> {
                    if (e.getTags().contains("moddy_grader_mob")) {
                        e.discard();
                    }
                });
                return null;
            }, 10_000L);
            case "night" -> AgentBridge.onServer(() -> {
                level.setDayTime(1000);
                return null;
            }, 10_000L);
            case "day" -> AgentBridge.onServer(() -> {
                level.setDayTime(18000);
                return null;
            }, 10_000L);
            default -> {
            }
        }
        int back = kind.equals("every") ? rule.get("period").getAsInt() + 40 : onFor + 80;
        boolean off = false;
        for (int t = 0; t < back && !off; t += 2) {
            waitTicks(server, 2);
            off = rest.get() == null;
        }
        return off ? null : "it switched on and did not come back: " + rest.get();
    }

    static boolean k3(JsonObject spec, String kind) {
        if (!spec.has("outputs")) {
            return false;
        }
        for (JsonElement e : spec.getAsJsonArray("outputs")) {
            if (alts(e.getAsJsonObject().get("kind").getAsString()).contains(kind)) {
                return true;
            }
        }
        return false;
    }

    static double distToBox(BlockPos p, int[] b) {
        double dx = Math.max(0, Math.max(b[0] - p.getX(), p.getX() - b[3]));
        double dz = Math.max(0, Math.max(b[2] - p.getZ(), p.getZ() - b[5]));
        return Math.hypot(dx, dz);
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
