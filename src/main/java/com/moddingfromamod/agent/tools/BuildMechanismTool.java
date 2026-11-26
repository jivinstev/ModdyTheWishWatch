package com.moddingfromamod.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.place.At;
import com.moddingfromamod.place.PlaceFinder;
import com.moddingfromamod.redstone.ControlSpots;
import com.moddingfromamod.redstone.DeviceModule;
import com.moddingfromamod.redstone.Devices;
import com.moddingfromamod.redstone.Control;
import com.moddingfromamod.redstone.Doorway;
import com.moddingfromamod.redstone.LevelView;
import com.moddingfromamod.redstone.MechanismPlan;
import com.moddingfromamod.redstone.MechanismPlanner;
import com.moddingfromamod.redstone.MechanismProof;
import com.moddingfromamod.redstone.MechanismStore;
import com.moddingfromamod.redstone.MechanismWorld;
import com.moddingfromamod.redstone.Module;
import com.moddingfromamod.redstone.PistonDoor;
import com.moddingfromamod.redstone.RsDir;
import com.moddingfromamod.redstone.RsPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Builds a working redstone mechanism into the world: the agent says WHAT (the mechanism and its controls) and
 * WHERE (the {@code at} places); every block, facing, wire route, repeater and casing is the planner's
 * ({@code redstone/}). The mechanism is then PROVED in the world — each control used for real and the outputs
 * checked, twice over — and taken back down if the proof fails, so a broken machine never stays.
 */
public final class BuildMechanismTool implements McpTool {

    static final List<String> MECHANISMS = List.of("piston_door", "iron_door", "lamps", "gate", "dispensers",
            "droppers", "note_blocks");
    static final List<String> INSTRUMENTS = List.of("harp", "bell", "flute", "chime", "guitar", "xylophone", "bass",
            "basedrum", "snare", "hat", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling");
    static final List<String> CONTROL_KINDS = List.of("lever", "button", "pressure_plate");

    @Override
    public String name() {
        return "build_mechanism";
    }

    @Override
    public String description() {
        return "Build a WORKING redstone mechanism into the world, wired to the switches the child asked for. "
                + "Use it for anything that MOVES, LIGHTS, SHOOTS or PLAYS with a switch, button, pressure plate or "
                + "lever: doors, lights, gates, dispensers and droppers, note blocks. You choose only WHAT "
                + "(`mechanism`, `controls`, a few options) and WHERE (`at`); the tool works out every block, wire, "
                + "repeater and facing, hides the wiring underground, builds it, then TESTS it by using each control "
                + "for real. If the test fails it takes everything back down and says why; nothing half-working is "
                + "left. Mechanisms: "
                + "piston_door: a flush 2-tall door of the wall's own blocks (or `material`), in the doorway of the "
                + "wall at `at` (its existing doorway, 1 or 2 wide, or a new one). "
                + "iron_door: an iron door (1 or 2 wide) in that doorway. "
                + "For both doors a switch, button or plate OPENS it (shut the rest of the time); on:\"close\" when he "
                + "says the switch CLOSES it. A door shut the rest of the time always gets a way to open it from "
                + "inside. "
                + "lamps: `count` redstone lamps set flush into the floor of the building at `at` (or the ground round "
                + "a spot), lit by the switches (`inverted`: lit until switched off). "
                + "gate: the fence gate(s) near `at` (or a new one there) opened by the switches. "
                + "dispensers / droppers: `count` of them on the ground at `at`, loaded with `items`, facing `aim`; "
                + "a switch fires them (fireworks: aim up; arrows: aim at a place). "
                + "note_blocks: one note block per entry of `notes` (0-24) on `instrument`, played by the switches "
                + "(a doorbell is one note). "
                + "Remove one with remove_mechanism.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        JsonObject mech = new JsonObject();
        mech.addProperty("type", "string");
        JsonArray kinds = new JsonArray();
        MECHANISMS.forEach(kinds::add);
        mech.add("enum", kinds);
        mech.addProperty("description", "Which mechanism. piston_door: a hidden door of the wall's own blocks.");
        s.getAsJsonObject("properties").add("mechanism", mech);
        Schemas.prop(s, "at", "string", At.describe("the mechanism",
                "its front",
                "For a door: the building whose doorway gets it (\"<house>:front\" is its entrance), or \"me:front\" "
                        + "for the wall he is looking at."));
        JsonObject controls = new JsonObject();
        controls.addProperty("type", "array");
        controls.addProperty("maxItems", 4);
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject kind = new JsonObject();
        kind.addProperty("type", "string");
        JsonArray ck = new JsonArray();
        CONTROL_KINDS.forEach(ck::add);
        kind.add("enum", ck);
        kind.addProperty("description", "A switch (lever) stays on or off; a button and a pressure plate are only on "
                + "while pressed or stood on.");
        props.add("kind", kind);
        JsonObject cat = new JsonObject();
        cat.addProperty("type", "string");
        cat.addProperty("description", "Where it goes, in the same <thing>:<spot> words: \"<house>:front\" puts it "
                + "outside beside the door, \"<house>:inside\" inside beside it, \"me:here\" where he stands.");
        props.add("at", cat);
        item.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("kind");
        req.add("at");
        item.add("required", req);
        controls.add("items", item);
        controls.addProperty("description", "The switches that work it, one entry each (at least one).");
        s.getAsJsonObject("properties").add("controls", controls);
        Schemas.prop(s, "material", "string", "Optional: what the door is MADE of, a block id (e.g. "
                + "\"minecraft:iron_block\" for a bank vault, \"minecraft:gold_block\"). Leave it out and the door is the "
                + "wall's own block, so it disappears into the wall when shut (a secret door). It must be a plain full "
                + "block a piston can push: not glass, not obsidian, not a chest.");
        JsonObject on = new JsonObject();
        on.addProperty("type", "string");
        JsonArray onv = new JsonArray();
        onv.add("open");
        onv.add("close");
        on.add("enum", onv);
        on.addProperty("description", "What switching it ON (or pressing, or standing on) does: \"open\" (the default) "
                + "or \"close\" (\"a door I can shut with a lever\"). The other way round happens when it is off.");
        s.getAsJsonObject("properties").add("on", on);
        Schemas.prop(s, "count", "integer", "How many lamps, dispensers or droppers (1-12; default 4 lamps, 1 else).");
        Schemas.prop(s, "items", "string", "For dispensers/droppers: what to load, \"<item id>*<count>\" joined with ';' "
                + "(e.g. \"minecraft:arrow*64\", \"minecraft:firework_rocket*32\", \"minecraft:cookie*64\").");
        Schemas.prop(s, "aim", "string", "For dispensers/droppers: up, down, north/south/east/west, or a place "
                + "(\"<thing>:<spot>\") to face. Default up.");
        Schemas.list(s, "notes", "For note_blocks: the notes, 0 (low F#) to 24 (high F#), one block each; 12 is "
                + "middle F#. Write them as numbers.", 24);
        Schemas.prop(s, "instrument", "string", "For note_blocks: harp (default), bell, flute, chime, guitar, "
                + "xylophone, bass, basedrum, snare, hat, iron_xylophone, cow_bell, didgeridoo, bit, banjo, pling.");
        Schemas.prop(s, "inverted", "boolean", "Lamps and other devices: ON until a switch turns them OFF.");
        Schemas.prop(s, "width", "integer", "Only for a NEW doorway: 1 or 2 wide (default 2). An existing doorway "
                + "keeps its own width.");
        Schemas.required(s, "mechanism", "at", "controls");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String mechanism = str(args, "mechanism");
        if (!MECHANISMS.contains(mechanism)) {
            return ToolResult.error("mechanism must be one of " + MECHANISMS + ", not '" + mechanism + "'.");
        }
        String at = str(args, "at");
        if (At.parse(at) == null) {
            return ToolResult.error("at must read \"<thing>:<spot>\" (a building's id, a creature or me), not '"
                    + at + "'.");
        }
        List<String[]> asked = new ArrayList<>();
        JsonElement ce = args.get("controls");
        if (ce == null || !ce.isJsonArray() || ce.getAsJsonArray().isEmpty()) {
            return ToolResult.error("controls needs at least one {kind, at}: a mechanism nobody can switch is no use.");
        }
        for (JsonElement e : ce.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                return ToolResult.error("each control is an object {kind, at}.");
            }
            String k = str(e.getAsJsonObject(), "kind").toLowerCase(Locale.ROOT);
            String a = str(e.getAsJsonObject(), "at");
            if (!CONTROL_KINDS.contains(k)) {
                return ToolResult.error("a control's kind is one of " + CONTROL_KINDS + ", not '" + k + "'.");
            }
            if (At.parse(a) == null) {
                return ToolResult.error("a control's at must read \"<thing>:<spot>\", not '" + a + "'.");
            }
            asked.add(new String[] {k, a});
        }
        String material = str(args, "material");
        String onArg = str(args, "on").toLowerCase(Locale.ROOT);
        if (!onArg.isEmpty() && !onArg.equals("open") && !onArg.equals("close")) {
            return ToolResult.error("on is \"open\" or \"close\".");
        }
        boolean opens = !onArg.equals("close");
        int count = args.has("count") && args.get("count").isJsonPrimitive() ? args.get("count").getAsInt() : 0;
        if (count < 0 || count > 12) {
            return ToolResult.error("count is 1 to 12.");
        }
        String items = str(args, "items");
        String itemsWhy = itemsProblem(items);
        if (itemsWhy != null) {
            return ToolResult.error(itemsWhy);
        }
        List<Integer> notes = new ArrayList<>();
        if (args.has("notes") && args.get("notes").isJsonArray()) {
            for (JsonElement n : args.getAsJsonArray("notes")) {
                try {
                    notes.add(Integer.parseInt(n.getAsString().trim()));
                } catch (RuntimeException e) {
                    return ToolResult.error("notes are numbers from 0 to 24, not '" + n + "'.");
                }
            }
        }
        String instrument = str(args, "instrument").isEmpty() ? "harp" : str(args, "instrument").toLowerCase(Locale.ROOT);
        if (!INSTRUMENTS.contains(instrument)) {
            return ToolResult.error("instrument is one of " + INSTRUMENTS + ".");
        }
        boolean inverted = args.has("inverted") && args.get("inverted").isJsonPrimitive()
                && args.get("inverted").getAsBoolean();
        Spec spec = new Spec(mechanism, at, asked, 0, material, opens, count, items, str(args, "aim"), notes,
                instrument, inverted);
        int width = args.has("width") && args.get("width").isJsonPrimitive() ? args.get("width").getAsInt() : 2;
        if (width != 1 && width != 2) {
            return ToolResult.error("width is 1 or 2.");
        }

        // 1-3: resolve, plan and place on the server thread
        Built built;
        try {
            Spec sp = new Spec(spec.mechanism(), spec.at(), spec.asked(), width, spec.material(), spec.opens(),
                    spec.count(), spec.items(), spec.aim(), spec.notes(), spec.instrument(), spec.inverted());
            built = AgentBridge.onServer(() -> buildOnServer(sp), 30_000L);
        } catch (RuntimeException e) {
            return ToolResult.error("Could not build it: " + e.getMessage());
        }
        if (built.error != null) {
            return built.error;
        }
        // 4: prove it, off the server thread (it waits for the game to tick)
        MechanismProof.Result proof = MechanismProof.prove(built.level, built.plan);
        if (!proof.ok()) {
            AgentBridge.onServer(() -> {
                MechanismWorld.takeBack(built.level, built.placed);
                return null;
            }, 30_000L);
            return ToolResult.error("Built it, TESTED it, and it did not work, so it was taken back down and the world is "
                    + "as it was. What failed: " + proof.why() + ". Try a different spot or control, or tell the "
                    + "child this one could not be made here.");
        }
        String id = AgentBridge.onServer(() -> {
            String fresh = MechanismStore.freshId(built.level, mechanism);
            List<BlockPos> controls = built.plan.controls().stream().map(c -> LevelView.bp(c.use())).toList();
            MechanismStore.put(built.level, new MechanismStore.Entry(fresh, mechanism, built.owner,
                    built.level.dimension().location().toString(), built.summary, built.placed, controls));
            return fresh;
        }, 30_000L);
        StringBuilder b = new StringBuilder();
        b.append("Built and TESTED '").append(id).append("': ").append(built.summary).append('\n');
        for (Control c : built.plan.controls()) {
            b.append("- a ").append(c.kind().replace('_', ' ')).append(" at ").append(c.use().x()).append(' ')
                    .append(c.use().y()).append(' ').append(c.use().z()).append('\n');
        }
        b.append("Test: ").append(String.join("; ", proof.steps())).append('\n');
        built.plan.notes().forEach(n -> b.append("Note: ").append(n).append('\n'));
        b.append(built.shutAtRest ? "It is SHUT now; a switch on (or a press) opens it."
                        : built.plan.kind().equals("piston_door") || built.plan.kind().equals("iron door")
                        ? "It is OPEN now; a switch on shuts it." : "It is in its resting state now.")
                .append(" Tell the child which switch does what. remove_mechanism id '").append(id)
                .append("' takes it all back out.");
        return ToolResult.ok(b.toString());
    }

    /** What the server-thread half made: the plan, what it placed, or the error to return. */
    static final class Built {
        ServerLevel level;
        MechanismPlan plan;
        List<MechanismWorld.Placed> placed;
        String owner = "", summary = "";
        ToolResult error;
        boolean shutAtRest;

        static Built fail(ToolResult r) {
            Built b = new Built();
            b.error = r;
            return b;
        }
    }

    /** Everything the agent asked for, parsed. */
    record Spec(String mechanism, String at, List<String[]> asked, int width, String material, boolean opens,
                int count, String items, String aim, List<Integer> notes, String instrument, boolean inverted) {
    }

    static Built buildOnServer(String mechanism, String at, List<String[]> asked, int width, String wanted,
                               boolean opens) {
        return buildOnServer(new Spec(mechanism, at, asked, width, wanted, opens, 0, "", "", List.of(), "harp", false));
    }

    static Built buildOnServer(Spec spec) {
        List<String[]> asked = spec.asked();
        var server = AgentBridge.serverOrNull();
        ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);
        if (player == null) {
            return Built.fail(ToolResult.noPlayerOnline("Nobody is in the world to build it for."));
        }
        PlaceFinder.Place place = PlaceFinder.find(server, player, spec.at(), com.moddingfromamod.builder.BuildSpots.FRONT);
        if (!place.ok()) {
            return Built.fail(ToolResult.error("Could not find " + spec.at() + ": " + place.why()));
        }
        ServerLevel level = place.level();
        LevelView view = new LevelView(level);
        BlockPos feet = place.block();
        RsPos start;
        RsDir in;
        if ("building".equals(place.kind())) {
            start = LevelView.rs(feet);
            in = snap(place.ox() - (feet.getX() + 0.5), place.oz() - (feet.getZ() + 0.5));
        } else {
            // a body (him, a creature): the wall he is looking at, from where he stands
            start = LevelView.rs(player.blockPosition());
            double r = Math.toRadians(player.getYRot());
            in = snap(-Math.sin(r), Math.cos(r));
        }
        int[] box = place.buildId() == null ? null : PlaceFinder.buildingBox(place.buildId());
        boolean isDoor = spec.mechanism().equals("piston_door") || spec.mechanism().equals("iron_door");
        Doorway.Found door = null;
        if (isDoor) {
            door = box != null ? Doorway.inBuilding(view, box, start, spec.width())
                    : Doorway.find(view, start, in, spec.width());
            if (!door.ok()) {
                return Built.fail(ToolResult.error("No door could go there: " + door.why() + "."));
            }
        } else if (box != null) {
            Doorway.Found d = Doorway.inBuilding(view, box, start, 2);
            door = d.ok() ? d : null;     // only to put switches "at the door"
        }
        RsPos ground = LevelView.rs(feet).up(-1);
        Module module;
        String summary;
        boolean shutAtRest = false;
        RsPos centre;
        String wall = door == null ? null : door.wallBlock();
        switch (spec.mechanism()) {
            case "piston_door" -> {
                String towerTop = conducts(level, wall) ? wall : "minecraft:stone";
                String material = wall;
                if (!spec.material().isEmpty()) {
                    String why = doorBlockProblem(level, spec.material());
                    if (why != null) {
                        return Built.fail(ToolResult.error("The door cannot be made of " + spec.material() + ": " + why
                                + ". Leave material out for the wall's own block, or pick a plain full block."));
                    }
                    material = spec.material().contains(":") ? spec.material() : "minecraft:" + spec.material();
                } else if (doorBlockProblem(level, wall) != null) {
                    material = towerTop;           // a glass wall: the door is stone rather than nothing
                }
                module = PistonDoor.plan(door.frame(), door.width(), material, towerTop, wall, spec.opens());
                shutAtRest = spec.opens();
                summary = "a hidden piston door " + door.width() + " wide and 2 tall in the "
                        + (door.existing() ? "doorway" : "new doorway") + " at " + where(door.frame().origin())
                        + ", made of " + material;
                centre = door.frame().origin();
            }
            case "iron_door" -> {
                List<DeviceModule.Device> devs = new ArrayList<>();
                for (int u = 0; u < door.width(); u++) {
                    devs.add(Devices.ironDoor(door.frame().at(u, 0, 0), door.frame().in(), u == 1));
                }
                DeviceModule.Result r = DeviceModule.plan(view, "iron door", devs, spec.opens(), Set.of(),
                        new ArrayList<>(List.of("an iron door: " + (spec.opens() ? "switched on (or pressed) it OPENS"
                                : "switched on it CLOSES"))));
                if (!r.ok()) {
                    return Built.fail(ToolResult.error("The iron door could not be wired: " + r.why() + "."));
                }
                module = r.module();
                shutAtRest = spec.opens();
                summary = "an iron door " + door.width() + " wide in the " + (door.existing() ? "doorway" : "new doorway")
                        + " at " + where(door.frame().origin());
                centre = door.frame().origin();
            }
            default -> {
                int n = spec.count() > 0 ? spec.count() : defaultCount(spec.mechanism(), spec.notes().size());
                List<DeviceModule.Device> devs = new ArrayList<>();
                String what;
                switch (spec.mechanism()) {
                    case "lamps" -> {
                        List<RsPos> at2 = box != null ? Devices.floorGrid(view, box, n)
                                : Devices.groundRing(view, ground, n, 2);
                        at2.forEach(p -> devs.add(Devices.lamp(p)));
                        what = devs.size() + " lamp(s) set into the " + (box != null ? "floor" : "ground");
                    }
                    case "gate" -> {
                        List<RsPos> gates = existingGates(level, ground, box, n);
                        if (gates.isEmpty()) {
                            gates = List.of(ground.up(1));
                        }
                        for (RsPos g : gates) {
                            var st = level.getBlockState(LevelView.bp(g));
                            String id = st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                                    ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString()
                                    : "minecraft:oak_fence_gate";
                            RsDir f = st.hasProperty(net.minecraft.world.level.block.FenceGateBlock.FACING)
                                    ? RsDir.valueOf(st.getValue(net.minecraft.world.level.block.FenceGateBlock.FACING).name())
                                    : in;
                            devs.add(Devices.fenceGate(g, f, id));
                        }
                        what = devs.size() + " gate(s)";
                    }
                    case "dispensers", "droppers" -> {
                        boolean dropper = spec.mechanism().equals("droppers");
                        String facing = aimFacing(server, player, spec.aim(), ground);
                        for (RsPos g : Devices.groundRing(view, ground, n, 1)) {
                            devs.add(Devices.dispenser(g.up(1), facing, spec.items(), dropper));
                        }
                        what = devs.size() + " " + spec.mechanism() + " facing " + facing
                                + (spec.items().isEmpty() ? " (empty)" : ", loaded with " + spec.items().replace(";", ", "));
                    }
                    case "note_blocks" -> {
                        List<Integer> notes = spec.notes().isEmpty() ? List.of(12) : spec.notes();
                        List<RsPos> spots = Devices.groundRing(view, ground, notes.size(), 1);
                        for (int i = 0; i < spots.size(); i++) {
                            devs.add(Devices.noteBlock(spots.get(i).up(1), Math.max(0, Math.min(24, notes.get(i))),
                                    spec.instrument()));
                        }
                        what = devs.size() + " note block(s) (" + spec.instrument() + ")";
                    }
                    default -> {
                        return Built.fail(ToolResult.error("Unknown mechanism " + spec.mechanism() + "."));
                    }
                }
                if (devs.isEmpty()) {
                    return Built.fail(ToolResult.error("There was no room for any " + spec.mechanism() + " at "
                            + spec.at() + " (they need flat solid ground with room above)."));
                }
                DeviceModule.Result r = DeviceModule.plan(view, spec.mechanism(), devs, !spec.inverted(), Set.of(),
                        new ArrayList<>(List.of(spec.inverted() ? "on at rest; a switch turns them OFF"
                                : "a switch turns them ON")));
                if (!r.ok()) {
                    return Built.fail(ToolResult.error("The " + spec.mechanism() + " could not be wired: " + r.why()
                            + ". Try fewer, or a flatter spot."));
                }
                module = r.module();
                summary = what + " at " + spec.at();
                centre = devs.get(0).feed();
            }
        }

        // A door shut at rest with every switch outside would shut him in: add a button inside beside it.
        String addedInside = null;
        if (shutAtRest && place.buildId() != null && door != null) {
            boolean anyInside = false;
            for (String[] c : asked) {
                PlaceFinder.Place cp = PlaceFinder.find(server, player, c[1], com.moddingfromamod.builder.BuildSpots.FRONT);
                if (cp.ok() && ("inside".equals(cp.r().spot())
                        || ControlSpots.side(door.frame(), LevelView.rs(cp.block())) > 0)) {
                    anyInside = true;
                }
            }
            if (!anyInside) {
                asked = new ArrayList<>(asked);
                asked.add(new String[] {"button", place.buildId() + ":inside"});
                addedInside = "a button inside beside the door, so nobody is shut in";
            }
        }
        // controls: each resolved, then the first spot the planner can wire
        Set<RsPos> foreign = view.foreignRedstone(centre.plus(-24, -10, -24), centre.plus(24, 10, 24));
        List<MechanismPlanner.ControlAsk> chosen = new ArrayList<>();
        MechanismPlan plan = null;
        for (String[] c : asked) {
            PlaceFinder.Place cp = PlaceFinder.find(server, player, c[1], com.moddingfromamod.builder.BuildSpots.FRONT);
            if (!cp.ok()) {
                return Built.fail(ToolResult.error("Could not find the " + c[0] + "'s place " + c[1] + ": " + cp.why()));
            }
            RsPos spot = LevelView.rs(cp.block());
            // a switch at the mechanism's own building goes beside its door: inside for "inside", else outside
            boolean sameBuilding = cp.buildId() != null && cp.buildId().equals(place.buildId()) && door != null;
            List<RsPos> tries = sameBuilding
                    ? ControlSpots.atDoor(door.frame(), door.width(), c[0], "inside".equals(cp.r().spot()) ? 1 : -1)
                    : door != null && ControlSpots.nearDoor(door.frame(), door.width(), spot)
                    ? ControlSpots.atDoor(door.frame(), door.width(), c[0], ControlSpots.side(door.frame(), spot))
                    : ControlSpots.around(spot.up(-1));
            String last = null;
            MechanismPlan ok = null;
            for (RsPos guess : tries) {
                RsPos g = groundNear(view, guess);
                if (g == null) {
                    last = "there is no ground to stand it on near " + guess;
                    continue;
                }
                List<MechanismPlanner.ControlAsk> withIt = new ArrayList<>(chosen);
                withIt.add(new MechanismPlanner.ControlAsk(c[0], g));
                MechanismPlan p = MechanismPlanner.plan(view, module, withIt, foreign);
                if (p.ok()) {
                    ok = p;
                    chosen = withIt;
                    break;
                }
                last = p.why();
            }
            if (ok == null) {
                return Built.fail(ToolResult.error("The " + c[0] + " at " + c[1] + " could not be wired: " + last
                        + ". Nothing was built."));
            }
            plan = ok;
        }
        if (addedInside != null) {
            plan.notes().add("Added " + addedInside + ".");
        }
        Built b = new Built();
        b.level = level;
        b.plan = plan;
        b.owner = player.getGameProfile().getName();
        b.summary = summary + (plan.replaced().isEmpty() ? "" : "; it replaced " + plan.replaced().size() + " block(s)");
        b.shutAtRest = shutAtRest;
        b.placed = MechanismWorld.place(level, plan);
        return b;
    }

    static String where(RsPos p) {
        return p.x() + " " + p.y() + " " + p.z();
    }

    static int defaultCount(String mechanism, int notes) {
        return switch (mechanism) {
            case "lamps" -> 4;
            case "note_blocks" -> Math.max(1, notes);
            default -> 1;
        };
    }

    /** Fence gates already standing near {@code ground} (or in the building's box), nearest first. */
    static List<RsPos> existingGates(ServerLevel level, RsPos ground, int[] box, int n) {
        List<RsPos> out = new ArrayList<>();
        int r = 8;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    RsPos p = ground.plus(dx, dy, dz);
                    if (level.getBlockState(LevelView.bp(p)).getBlock()
                            instanceof net.minecraft.world.level.block.FenceGateBlock) {
                        out.add(p);
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt(p -> p.manhattan(ground)));
        return out.subList(0, Math.min(Math.max(n, 1), out.size()));
    }

    /** A dispenser's facing from `aim`: up/down/a compass word, or toward a place; up when nothing is said. */
    static String aimFacing(net.minecraft.server.MinecraftServer server, ServerPlayer player, String aim, RsPos from) {
        String a = aim == null ? "" : aim.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || a.equals("up") || a.equals("sky")) {
            return "up";
        }
        if (List.of("down", "north", "south", "east", "west").contains(a)) {
            return a;
        }
        if (At.parse(a) != null) {
            PlaceFinder.Place t = PlaceFinder.find(server, player, a, com.moddingfromamod.builder.BuildSpots.FRONT);
            if (t.ok()) {
                return snap(t.r().x() - (from.x() + 0.5), t.r().z() - (from.z() + 0.5)).id();
            }
        }
        return "up";
    }

    /**
     * The real ground in {@code guess}'s column: the block a control stands on, with room above it. A doorway is
     * often a step above the ground outside (a raised floor) or below it, so the guess is searched 2 up to 3 down.
     */
    static RsPos groundNear(LevelView view, RsPos guess) {
        for (int dy = 2; dy >= -3; dy--) {
            RsPos g = guess.up(dy);
            var above = view.kind(g.up(1));
            var k = view.kind(g);
            if ((k == com.moddingfromamod.redstone.WorldView.Kind.SOLID
                    || k == com.moddingfromamod.redstone.WorldView.Kind.NATURAL) && view.conductor(g)
                    && above == com.moddingfromamod.redstone.WorldView.Kind.AIR
                    && view.kind(g.up(2)) == com.moddingfromamod.redstone.WorldView.Kind.AIR) {
                return g;
            }
        }
        return null;
    }

    /** Null when {@code items} reads "<item id>*<count>;…" with real items. */
    static String itemsProblem(String items) {
        if (items.isEmpty()) {
            return null;
        }
        for (String part : items.split(";")) {
            String[] ic = part.trim().split("\\*");
            var rl = net.minecraft.resources.ResourceLocation.tryParse(ic[0].contains(":") ? ic[0] : "minecraft:" + ic[0]);
            if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl)) {
                return "items: there is no item '" + ic[0] + "' (write \"minecraft:arrow*64;minecraft:cookie*16\").";
            }
            if (ic.length > 1) {
                try {
                    int n = Integer.parseInt(ic[1].trim());
                    if (n < 1 || n > 576) {
                        return "items: a count is 1 to 576 (a dispenser holds 9 stacks).";
                    }
                } catch (NumberFormatException e) {
                    return "items: '" + part + "' should read <item>*<count>.";
                }
            }
        }
        return null;
    }

    /** Null when {@code id} is a block a door can be made of: full, solid, pushable, no block entity. */
    static String doorBlockProblem(ServerLevel level, String id) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl)) {
            return "there is no such block";
        }
        var state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(rl).defaultBlockState();
        if (state.hasBlockEntity()) {
            return "it holds things, and a piston cannot move it";
        }
        if (state.getPistonPushReaction() != net.minecraft.world.level.material.PushReaction.NORMAL) {
            return "a piston cannot push it";
        }
        if (!state.isCollisionShapeFullBlock(level, BlockPos.ZERO)) {
            return "it is not a full block, so the door would have gaps";
        }
        return null;
    }

    static boolean conducts(ServerLevel level, String id) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl)) {
            return false;
        }
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(rl).defaultBlockState()
                .isRedstoneConductor(level, BlockPos.ZERO);
    }

    static RsDir snap(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? RsDir.EAST : RsDir.WEST;
        }
        return dz >= 0 ? RsDir.SOUTH : RsDir.NORTH;
    }

    static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }
}
