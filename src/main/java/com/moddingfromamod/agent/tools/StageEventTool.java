package com.moddingfromamod.agent.tools;

import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.event.design.DayBank;
import com.moddingfromamod.event.design.EventBuild;
import com.moddingfromamod.event.design.EventDesign;
import com.moddingfromamod.event.design.EventDesigns;
import com.moddingfromamod.event.shape.FallShape;
import com.moddingfromamod.wish.WishProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>A world event, in one call</b> (E5): a tidal wave, a flood that rises and drains, a
 * meteor and its crater, it raining chickens, an earthquake, a landslide, a countdown before a blast.
 *
 * <p>Every field is an enum or a bounded number except the block and creature ids (checked against
 * the live registry) and one title (swept for a child's eyes). The design is KEPT in his world, so the
 * same name stages it again, a world rule can stage it ({@code moddyevent stage <name>} in its
 * {@code do}), and {@code every_days} brings it back on its own. The reply says what will happen —
 * the agent reads it back before telling him.
 */
public final class StageEventTool implements McpTool {

    /** The parts this tool IS (tools/parts-coverage.py). */
    public static final Set<String> PARTS = Set.of("event_motion", "event_effect", "event_schedule",
            "event_follow_on");

    private String lastId;
    /** The design's name: what "it" means to the next wish, whether or not it is running now. */
    private String lastDesign;

    @Override
    public String name() {
        return "stage_event";
    }

    @Override
    public String description() {
        return "A WORLD EVENT that HAPPENS: something that moves, rises, falls or shakes over time and "
                + "then puts the world back — a tidal wave rolling across the land, a flood that rises "
                + "and drains, a meteor streaking down (then a crater), it raining creatures or blocks "
                + "from the sky, an earthquake cracking the ground, a hill sliding down, a countdown "
                + "before something goes off, a tornado, a whirlpool or a hurricane spinning (vortex). ONE CALL: pick a `motion`, a `size`, what it is made of, "
                + "what it does to what it catches, and what follows. It happens at the spot he aimed "
                + "(or ahead of him). EVERYTHING IT CHANGES IS REMEMBERED: waves, floods, falling "
                + "things and rains put the world back by themselves; craters, cracks and slides stay "
                + "until remove_event. A crater, crack or slide that stays breaks what it reaches, his "
                + "builds too (undo rebuilds them); waves, floods and anything that puts itself "
                + "back flow round what he built. It never breaks a chest, and it never takes him "
                + "below one heart. The design is "
                + "KEPT under its `name`: the same name again stages it again; `every_days` makes it "
                + "come back on its own; to make it happen WHEN something happens (the boss dies, "
                + "he steps on a block), call set_rule with `do: [\"moddyevent stage <name>\"]`. For "
                + "an item that sets it off, make_item with the `event` effect and DESIGN <name>. Read "
                + "the reply: it says exactly what will happen.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "name", "string", "REQUIRED. A short name, e.g. 'chocolate_wave'. The same name "
                + "again changes or re-stages this design.");
        Schemas.prop(s, "motion", "string", "REQUIRED. moving_front (a wave or wall rolling along the way "
                + "he faces), layer (rises, holds, drains: a flood, a lake of lava, a snow drift), "
                + "impact_stamp (a crater, a cone or a mound, in one go), falling_body (a ball streaking "
                + "down at a slant he can watch; give it a `then` for what it leaves), from_the_sky "
                + "(creatures or blocks raining over an area), spreading_cracks (the ground cracks open "
                + "in wandering lines), slide (the nearest hillside comes down; nothing on flat ground), "
                + "countdown (seconds on his screen, then `at_zero`), transform (what is ALREADY there "
                + "turns into `material` where it stands, rippling out from the spot: the ground by "
                + "default, or the block or #tag he named in `replaces` — 'turn the ground to gold', "
                + "'make the trees pink'; it stays until undone), crowd (MANY of one creature or item, "
                + "standing there and kept: 'lots of sheep in my field', 'twenty pigs all round me', 'a "
                + "circle of iron golems' — `drop` is what, `count` how many, `pattern` how they stand; ONE mob, or a mob "
                + "you then change, is summon instead), vortex (something that SPINS and moves: a tornado, a "
                + "twister, a dust devil, a waterspout, a fire whirl — shape funnel, the default; a whirlpool, "
                + "a maelstrom, quicksand — shape drain; a hurricane, a cyclone storm — shape spiral. It lifts and "
                + "throws what its SIZE can carry and tears up blocks by size, then the world comes back), "
                + "none (no blocks at all: only what "
                + "`catch` does — an earthquake's shaking, a gust).");
        Schemas.prop(s, "size", "string", "REQUIRED. little, medium, big or gigantic. Each is a hard "
                + "budget of blocks; a wish for 'the whole world' is gigantic, which is still a place, "
                + "not the world — say so to him. On a vortex the size is what it CAN do: little (a dust devil) "
                + "lifts only light things (items, chickens, cats) and tears up flowers; medium (a tornado) lifts "
                + "people, animals, boats and minecarts and tears up leaves, sand and glass; big lifts horses and "
                + "rides and tears up logs, dirt and fences; gigantic (a category 4/5) lifts iron golems and tears "
                + "up his builds too (given back when it passes). Match the size to what he wants it to pick up. HIS "
                + "WORD sets it first: tiny/little/small → little; big/large → big; giant, huge, gigantic, "
                + "enormous, massive, ginormous, category 4 or 5 → gigantic (never big).");
        Schemas.prop(s, "material", "string", "A block id it is made of: water, lava, a block of honey, "
                + "chocolate as brown_concrete… Needed by moving_front, layer, impact_stamp, falling_body, transform. "
                + "vortex: the block its spinning walls are DRAWN in (never placed): leave it out for plain wind "
                + "(white glass) or a whirlpool's water; sand for a dust devil or quicksand, orange_stained_glass for "
                + "fire (it then sets alight what it catches), light_blue_stained_glass for a waterspout, "
                + "snow_block, oak_leaves, pink_wool for candy floss, purple_stained_glass for ender, white_wool "
                + "for a hurricane's clouds.");
        Schemas.prop(s, "core", "string", "impact_stamp: the block at the very middle (magma_block for a "
                + "fresh crater). falling_body: the lumps in the tumbling rock, mixed with `material` (a "
                + "gold meteor: gold_block with raw_gold_block lumps); blackstone if left out.");
        Schemas.prop(s, "rim", "string", "impact_stamp: the block the raised rim is made of.");
        Schemas.prop(s, "form", "string", "impact_stamp: crater (default), cone or mound.");
        Schemas.prop(s, "drop", "string", "from_the_sky: a creature id (chicken, frog) or a block id "
                + "(snow, a coloured wool) that falls. crowd: a creature id (horse, cat, snow_golem) or an "
                + "item id (diamond, cake) the crowd is made of. vortex: what spins IN it from the start — a "
                + "creature (cats spinning in a cyclone: they are flung out and land and stay until undo) or a block "
                + "(leaves or petals flying round it).");
        Schemas.prop(s, "shape", "string", "vortex only: funnel (default: narrow at the ground, wide at the top — "
                + "a tornado), drain (a cone spinning DOWN into water or sand — a whirlpool, quicksand; aimed "
                + "near water it goes in the water) or spiral (a wide ring of spinning cloud arms high up with a "
                + "calm eye and a storm: rain, thunder, harmless lightning — a hurricane).");
        JsonObject sparesArr = new JsonObject();
        sparesArr.addProperty("type", "array");
        sparesArr.addProperty("description", "moving_front and vortex only: what it leaves alone. These WRECK by default: "
                + "they smash through his builds and leave the damage until remove_event (which rebuilds every "
                + "block; a chest is never touched), and they hurt what they catch and may kill it — never his pets, "
                + "the creatures he wished, or him below one heart. \"builds\": it only takes natural ground and the "
                + "world comes back when it passes. \"creatures\": it carries them and never hurts them. Only when his "
                + "words ask (\"that doesn't break my house\", \"a safe tornado\", \"don't hurt the animals\").");
        JsonObject spareItem = new JsonObject();
        spareItem.addProperty("type", "string");
        sparesArr.add("items", spareItem);
        s.getAsJsonObject("properties").add("spares", sparesArr);
        Schemas.prop(s, "look", "string", "vortex only, and only when his words say how it LOOKS: solid (in "
                + "its blocks), mist (see-through swirling bands — 'misty', 'ghostly', 'see-through', 'sparkly air') or "
                + "streaks (thin whipping lines — 'windy lines', 'zoomy', 'like a cartoon'). Left out, its material "
                + "chooses: wind, sand, snow, candy floss and a whirlpool are mist, fire is streaks, everything else is "
                + "solid.");
        Schemas.prop(s, "path", "string", "vortex only, and only when his words say how it moves: stay (stays in "
                + "one spot), wander (dances about where he aimed), line (cuts a straight swath across his view), "
                + "circle (goes round and round the spot, or round the building in `at`), follow (chases him). "
                + "Left out it is chosen for you: a whirlpool and a hurricane stay, a little or medium tornado "
                + "wanders, a big or gigantic one cuts a swath.");
        Schemas.prop(s, "count", "integer", "crowd: how many he said (1-" + EventDesign.MAX_CROWD + "); "
                + "left out, the size decides (little 8 … gigantic 64). A big ask comes as one wish's wave "
                + "and the reply says how many came. vortex: how many funnels (1-3, 'twin tornadoes').");
        Schemas.prop(s, "traits", "object",
                "What EVERY creature it brings is, from the moment it arrives: modify_mob's fields — "
                        + "follow, friendly, name, persistent, base, powers, abilities, scale, health… "
                        + "An army that fights for him: {\"friendly\":true,\"follow\":true,"
                        + "\"base\":\"guardian\",\"name\":\"Enderman Soldier\"}. Given here, "
                        + "none of them is ever wild first, and none wanders out of reach of a "
                        + "modify_mob afterwards (use the @event: handle the reply gives).");
        Schemas.prop(s, "baby", "boolean", "crowd: true for young ones (baby cows, piglets, kittens), "
                + "where the creature has a young.");
        Schemas.prop(s, "pattern", "string", "crowd: cluster (default: bunched at the spot — a herd, a "
                + "group), ring (a circle round the spot, facing out) or around_me (a ring round HIM).");
        Schemas.prop(s, "replaces", "string", "transform: what turns into `material` — 'ground' (the "
                + "default: the top block everywhere in the area), a block id (stone, grass_block) or a "
                + "block tag with # (#leaves for the trees, #logs, #sand). Only what he named changes. Never air: "
                + "filling a space with something is layer.");
        Schemas.prop(s, "breaks", "boolean", "moving_front: true (default) curls over and crashes like a "
                + "real wave; false slides along as a wall (a sandstorm).");
        JsonObject catchItem = Schemas.object();
        Schemas.prop(catchItem, "where", "string", "inside (in the water, under the flood, in the rock) "
                + "or near (within `reach` of the middle, strongest there)");
        Schemas.prop(catchItem, "reach", "integer", "near: blocks, 1-" + EventDesign.MAX_REACH);
        Schemas.prop(catchItem, "every", "integer", "only one tick in this many (a quake's jolts: 2)");
        Schemas.prop(catchItem, "from", "integer", "from this tick of the event");
        Schemas.prop(catchItem, "to", "integer", "until this tick (a blast: from 0 to 20)");
        Schemas.list(catchItem, "do", "carry, lift, shake, ignite, hurt:<1-" + EventDesign.MAX_HURT
                + ">, status:<kind effect>[:seconds]", EventDesign.MAX_EFFECTS);
        JsonObject catchArr = new JsonObject();
        catchArr.addProperty("type", "array");
        catchArr.addProperty("description", "What it does to creatures and players it catches (up to "
                + EventDesign.MAX_CATCHES + "). A wave: {where: inside, do: [carry, lift]}. A quake: "
                + "{where: near, reach: 20, every: 2, do: [shake]}. Hurt never takes him below one "
                + "heart; status is only the kind effects (slow_falling, glowing, …).");
        catchArr.add("items", catchItem);
        s.getAsJsonObject("properties").add("catch", catchArr);
        JsonObject blockItem = Schemas.object();
        Schemas.prop(blockItem, "hit", "string", "break_fragile (glass, ice, leaves, crops, torches — "
                + "sometimes, not every one) or fire (only on bare ground, never next to wood)");
        Schemas.prop(blockItem, "reach", "integer", "blocks from the middle");
        Schemas.prop(blockItem, "per_tick", "integer", "strikes a tick, 1-40");
        Schemas.prop(blockItem, "strength", "number", "0 to 1");
        JsonObject blocksArr = new JsonObject();
        blocksArr.addProperty("type", "array");
        blocksArr.addProperty("description", "What it does to blocks (up to " + EventDesign.MAX_BLOCK_HITS
                + "): hail that smashes glass, a meteor that scorches the ground. Both come back on undo.");
        blocksArr.add("items", blockItem);
        s.getAsJsonObject("properties").add("blocks", blocksArr);
        Schemas.prop(s, "afterwards", "string", "restore (the world puts itself back when it ends) or "
                + "until_undo (it stays until remove_event). Leave it out unless his words say: what an "
                + "event LEAVES stays (a crater, cracks, a landslide, the things that fell from the sky) and "
                + "what only PASSES goes (a wave, a flood, a countdown, shaking). Undo always puts everything "
                + "back, so keeping it is never a risk.");
        Schemas.prop(s, "seconds", "integer", "countdown: seconds to get out (5-120); none: how long it "
                + "lasts; any other motion: keep it going (and whatever it catches: shaking, burning) at "
                + "least this long. \"Make it last longer\" is this, not a bigger size.");
        Schemas.prop(s, "at_zero", "string", "countdown: the NAME of a design (staged first) that happens "
                + "when it runs out.");
        JsonObject thenItem = Schemas.object();
        Schemas.prop(thenItem, "if", "string", "water_near, slope_near, on_land or always");
        Schemas.prop(thenItem, "design", "string", "the name of another design (stage it first)");
        JsonObject thenArr = new JsonObject();
        thenArr.addProperty("type", "array");
        thenArr.addProperty("description", "What follows when it ends, chosen by the spot (up to "
                + EventDesign.MAX_THEN + "; the first that holds): a meteor with [{if: water_near, design: "
                + "big_wave}, {if: on_land, design: rock_crater}].");
        thenArr.add("items", thenItem);
        s.getAsJsonObject("properties").add("then", thenArr);
        Schemas.prop(s, "title", "string", "Big words across his screen when it starts (up to "
                + EventDesign.MAX_TITLE + " characters), e.g. 'TIDAL WAVE!'. A countdown shows its "
                + "seconds under it.");
        Schemas.prop(s, "now", "boolean", "Stage it now (default true). False only keeps the design — for "
                + "an at_zero or a then, or one set_rule or an item will set off.");
        Schemas.prop(s, "every_days", "integer", "Also bring it back on its own every this many Minecraft "
                + "days (" + DayBank.MIN_DAYS + "-" + DayBank.MAX_DAYS + "). Flipping day and night "
                + "hurries it; winding the clock back does not.");
        Schemas.prop(s, "at", "string", com.moddingfromamod.place.At.describe("the event",
                "its top — the event lands ON it",
                "\"A meteor hits my castle\" → at:\"castle:top\" (the meteor lands on the roof); \"snow over my "
                        + "house\" → at:\"house:top\"; \"a flood around my base\" → at:\"base:around\" (the "
                        + "ground beside it, not its roof); \"a wave crashes over my fort\" → at:\"fort\" (a "
                        + "wave is born beyond it and rolls over it toward him). The camera keeps the "
                        + "building in the shot."));
        Schemas.prop(s, "note", "string", com.moddingfromamod.provenance.ProvenanceContext.NOTE_HINT);
        Schemas.required(s, "name", "motion", "size");
        return s;
    }

    @Override
    public String lastMadeId() {
        return lastId;
    }

    @Override
    public String lastSubjectId() {
        return lastDesign;
    }

    @Override
    public ToolResult call(JsonObject args) {
        lastId = null;
        lastDesign = null;
        com.moddingfromamod.provenance.ProvenanceContext.note(
                args != null && args.has("note") && args.get("note").isJsonPrimitive()
                        ? args.get("note").getAsString() : null);
        EventDesign.Parsed p = EventDesign.parse(args);
        if (!p.ok()) {
            // A refused design is the moment the rules matter, so the lesson rides on the refusal.
            return ToolResult.error("That event was not kept: " + String.join("; ", p.errors())
                    + com.moddingfromamod.wish.WishLessons.appendTo("event"));
        }
        EventDesign d = p.design();
        boolean now = !args.has("now") || args.get("now").getAsBoolean();
        Integer every = args.has("every_days") ? args.get("every_days").getAsInt() : null;
        // AT a named place. Grammar checked before anything is kept.
        final String at = args.has("at") && args.get("at").isJsonPrimitive() ? args.get("at").getAsString().trim() : "";
        if (!at.isEmpty() && com.moddingfromamod.place.At.parse(at) == null) {
            return ToolResult.error("at must read \"<thing>:<spot>\" — a building's id, a creature or me, then a "
                    + "spot (top, around, inside, front, …), not '" + at + "'.");
        }
        if (every != null && (every < DayBank.MIN_DAYS || every > DayBank.MAX_DAYS)) {
            return ToolResult.error("every_days must be " + DayBank.MIN_DAYS + " to " + DayBank.MAX_DAYS + ".");
        }
        try {
            // Read on THIS thread: the wish binding is thread-local to the MCP handler, and the
            // body below runs on the server thread, where it would be empty.
            final String wishAbout = aboutThisWish();
            final var run = com.moddingfromamod.agent.AgentContext.currentRun();
            final boolean[] again = {false};
            ToolResult staged = AgentBridge.onServer(() -> {
                MinecraftServer server = AgentBridge.serverOrNull();
                if (server == null) {
                    return ToolResult.error("The world is not running yet.");
                }
                for (EventDesign.Then t : d.then()) {
                    if (EventDesigns.get(server, t.design()) == null) {
                        return ToolResult.error("then: there is no design called '" + t.design()
                                + "' yet. Stage it first with now: false, then this one.");
                    }
                }
                if (d.atZero() != null && EventDesigns.get(server, d.atZero()) == null) {
                    return ToolResult.error("at_zero: there is no design called '" + d.atZero()
                            + "' yet. Stage it first with now: false, then this one.");
                }
                ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);
                if (now && player == null) {
                    return ToolResult.noPlayerOnline("stage an event");
                }
                boolean isNew = !EventDesigns.store(server).names().contains(d.name());
                // THE SAME DESIGN AGAIN puts back a kept event: nothing new to film (2026-10-03)
                again[0] = every == null && d.sameEventAs(EventDesigns.get(server, d.name()));   // the library's too
                String why = EventDesigns.put(server, d);
                if (why != null) {
                    return ToolResult.error(why);
                }
                // Every kept design publishes its provenance (docs/WISH_PROVENANCE.md): the design IS
                // the artifact, and it is what `moddyevent stage <name>` replays.
                com.moddingfromamod.provenance.ProvenancePublisher.publish(
                        "event", d.name(), "event.json", d.toJson().toString());
                StringBuilder out = new StringBuilder("Kept '" + d.name() + "': " + d.describe() + ".");
                if (every != null) {
                    String w = EventDesigns.every(server, d.name(), every,
                            player == null ? "" : player.getGameProfile().name());
                    if (w != null) {
                        return ToolResult.error(w);
                    }
                    out.append(" It will come back on its own every ").append(every)
                            .append(every == 1 ? " Minecraft day." : " Minecraft days.");
                }
                lastDesign = d.name();
                String left = isNew ? stillKeptOld(server, d, wishAbout) : null;
                if (now) {
                    // where it happens — the named place, or (no `at`) where he aimed.
                    java.util.function.Supplier<EventBuild.Result> where;
                    String whereSaid = "";
                    if (!at.isEmpty()) {
                        var place = com.moddingfromamod.place.PlaceFinder.find(server, player, at, "top");
                        if (!place.ok()) {
                            return ToolResult.error("Kept, but it did not happen: it could not take " + at + ": "
                                    + place.why() + ". Stage it again with an at that stands, or none (then it "
                                    + "happens where he aimed).");
                        }
                        whereSaid = " It is happening at " + place.r().link() + ".";
                        where = () -> stageAt(player, d, place);
                    } else {
                        where = () -> stageAtAim(player, d);
                    }
                    // a camera that can film it is here, so the first visible tick waits for it
                    // (released when the take is sent; never longer than EVENT_CUE_MAX_MS).
                    EventBuild.Result r = !again[0] && com.moddingfromamod.agent.tools.PhotographTool.canFilmEvents(server)
                            ? EventBuild.withCue(System.currentTimeMillis()
                                    + com.moddingfromamod.agent.tools.PhotographTool.EVENT_CUE_MAX_MS, where)
                            : where.get();
                    if (!r.ok()) {
                        return ToolResult.error("Kept, but it did not happen: " + r.error());
                    }
                    lastId = r.id();
                    out.append(whereSaid);
                    String wave = EventBuild.crowdWave(d);
                    if (wave != null) {
                        out.append(' ').append(wave);
                    }
                    if (EventBuild.bringsCreatures(d)) {
                        // every creature it brings, wherever it wanders.
                        out.append(" Every creature it brings is ").append(ModifyMobTool.EVENT_HANDLE)
                                .append(r.id()).append(" — modify_mob target '").append(ModifyMobTool.EVENT_HANDLE)
                                .append(r.id()).append("' reaches all of them, wherever they go.");
                        if (!d.traits().isEmpty()) {
                            out.append(" Each arrives already: ").append(com.moddingfromamod.mob.MobTweak.fromJson(
                                    com.google.gson.JsonParser.parseString(d.traits()).getAsJsonObject()).summary())
                                    .append('.');
                        }
                    }
                    out.append(" It is happening now (").append(r.id()).append("). remove_event with this id "
                            + "gives everything back. It builds up over a few seconds, so a picture taken now "
                            + "shows it just starting: that is what an event looks like at first, not a "
                            + "problem to fix.");
                } else {
                    lastId = d.name();
                }
                if (left != null) {
                    out.append(' ').append(left);
                }
                return ToolResult.ok(out.toString());
            });
            if (now && !staged.isError() && again[0] && run != null) {
                com.moddingfromamod.wish.CameraNeed.shared().reused(run.wishId(), "event", lastId,
                        System.currentTimeMillis());
                return ToolResult.ok(staged.text() + " It is a kept event put back as it was, so it is not "
                        + "filmed again — finish without a photograph.");
            }
            if (now && !staged.isError()) {
                // FILM IT AS IT STARTS. An event's motion is over by the time the wish asks
                // for its photograph — measured: a meteor filmed after the studio's 3 s settle was a
                // crater in all 48 frames, a tidal wave nothing at all. So the clip runs here, while
                // it happens, and the wish's photograph returns it (PhotographTool.captureEvent).
                ToolResult film = com.moddingfromamod.agent.tools.PhotographTool.filmEventNow();
                if (film != null) {
                    return ToolResult.ok(staged.text() + " It was filmed as it started — finish_building's "
                            + "photograph shows that clip.");
                }
            }
            return staged;
        } catch (Exception e) {
            return ToolResult.error("stage_event failed: " + e.getMessage());
        }
    }

    /**
     * <b>"Instead" leaves one, not two</b> (C3). When this wish was about a kept design and a
     * NEW name of the same motion is kept (a gold meteor re-made as {@code ice_meteor}), say that the
     * old one is still kept and how to let it go. Opus read the same rule in the lesson and kept both,
     * 6 of 6; a reply is read at the moment it matters. Null when there is nothing to say.
     */
    static String aboutThisWish() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? null : run.aboutArtifact();
    }

    /** The same, given what the wish was about; public so a test can drive it without a wish. */
    public static String stillKeptOld(MinecraftServer server, EventDesign d, String about) {
        if (about == null || about.isBlank()) {
            return null;
        }
        var kept = EventDesigns.store(server).names();
        String aboutName = EventDesign.clean(about);
        EventDesign was = kept.contains(aboutName) ? EventDesigns.get(server, aboutName) : null;
        if (was == null || was.name().equals(d.name())) {
            return null;
        }
        java.util.List<EventDesign> family = new java.util.ArrayList<>();
        family.add(was);
        for (EventDesign.Then t : was.then()) {
            EventDesign f = kept.contains(t.design()) ? EventDesigns.get(server, t.design()) : null;
            if (f != null) {
                family.add(f);
            }
        }
        for (EventDesign old : family) {
            if (old.motion() == d.motion() && !old.name().equals(d.name())) {
                return "⚠ This wish was about '" + was.name() + "', and '" + old.name()
                        + "' is still kept beside '" + d.name() + "'. If this REPLACES it (\"instead\", "
                        + "\"make it ice\"), remove_event name '" + old.name() + "' so he has one, not both;"
                        + " if he asked for another one, keep both and say so.";
            }
        }
        return null;
    }

    /**
     * <b>At a named place</b>: the centre and heading {@link EventAim} gives for this motion,
     * in the place's own world, with the camera told to keep the building in the shot.
     */
    public static EventBuild.Result stageAt(ServerPlayer player, EventDesign d,
                                            com.moddingfromamod.place.PlaceFinder.Place p) {
        net.minecraft.server.level.ServerLevel level = p.level();
        int out = d.motion() == EventDesign.Motion.MOVING_FRONT
                ? com.moddingfromamod.event.design.DesignSizes.wave(d.scale(), d.breaks()).breaksAt() : 0;
        boolean here = player != null && player.level() == level;
        var aim = com.moddingfromamod.event.design.EventAim.of(p.r(), "building".equals(p.kind()), d.motion(),
                here ? player.getX() : Double.NaN, here ? player.getZ() : Double.NaN,
                player != null ? player.getYRot() : 0f, out,
                (x, z) -> EventBuild.ground(level, x, z).getY());
        int[] b = com.moddingfromamod.place.PlaceFinder.buildingBox(p.buildId());
        double[] box = b == null ? null
                : new double[]{b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1};
        String owner = player == null ? "" : player.getGameProfile().name();
        BlockPos centre = new BlockPos(aim.x(), aim.y(), aim.z());
        return EventBuild.aimedAt(box, () -> EventBuild.stage(level, d, centre, aim.yaw(), player, owner, 0));
    }

    /** At the spot the wish was aimed at, locked when he wished; ahead of him if nothing was locked. */
    static EventBuild.Result stageAtAim(ServerPlayer player, EventDesign d) {
        BlockPos aim = WishProgress.manifestAnchor(player);
        String owner = player.getGameProfile().name();
        if (aim == null) {
            return EventBuild.nearPlayer(player, d, owner);
        }
        int x = aim.getX(), z = aim.getZ();
        if (d.motion() == EventDesign.Motion.FALLING_BODY) {
            // A falling body he can watch lands at least MIN_AIM out: closer, it starts over his head.
            double dx = x + 0.5 - player.getX(), dz = z + 0.5 - player.getZ();
            double dist = Math.hypot(dx, dz);
            if (dist < FallShape.MIN_AIM) {
                double r = Math.toRadians(player.getYRot());
                x = (int) Math.floor(player.getX() - Math.sin(r) * FallShape.MIN_AIM);
                z = (int) Math.floor(player.getZ() + Math.cos(r) * FallShape.MIN_AIM);
            }
        }
        return EventBuild.stage(player.level(), d, EventBuild.ground(player.level(), x, z),
                player.getYRot(), player, owner, 0);
    }
}
