package com.moddingfromamod.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Invent a creature. This is the heart of the "make me a giant pink monster" experience: the agent
 * describes a creature as data and it exists immediately — <b>no game restart</b>, because it's an
 * instance of the one registered entity type wearing a species.
 */
public final class DefineSpeciesTool implements McpTool {

    @Override
    public String name() {
        return "define_creature";
    }

    @Override
    public String description() {
        return "LAST RESORT — invent a creature out of vanilla parts. Use this ONLY when "
                + "find_content shows that nothing installed is what the child asked for, or when "
                + "they need a specific BODY COLOUR (this is the only tool that can repaint a "
                + "creature; modify_mob can only add a coloured glow). If a real mob exists, summon "
                + "it with run_command and adjust it with modify_mob instead — a child who asked "
                + "for a wither storm wants the real boss, not a lookalike built from a spider. "
                + "Pick a body shape and texture from the allowed lists; make scary things big and "
                + "tough, friendly things small and harmless. Re-defining the same id updates every "
                + "creature of that kind already in the world.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        // A STATUS THAT RIDES ALONG WITH THE WORK. Calling set_status separately costs a
        // full model round trip -- ~4s, and 12% of one measured session -- for a tool that
        // executes in under 8ms. Passed here it is shown the moment the call arrives, in the
        // agent's own words, for nothing.
        Schemas.prop(s, "status", "string",
                "A SHORT kid-facing line saying what you are doing, shown the instant this "
                        + "call arrives, e.g. 'Giving him his powers'. Pass it EVERY time: it is "
                        + "free here, whereas a separate set_status costs the child a wait.");
        Schemas.prop(s, "id", "string", "Short unique name, e.g. 'blorp'. Re-use it to edit.");
        Schemas.prop(s, "name", "string", "Display name shown above the creature.");
        Schemas.prop(s, "body", "string",
                "Body shape: humanoid | slime | spider | quadruped | bird.");
        Schemas.prop(s, "texture", "string",
                "Look. One of: " + String.join(", ", Species.textureKeys()));
        Schemas.prop(s, "tint", "string",
                "Colour it! A name (" + String.join(", ", Species.colourNames())
                        + ") or a hex value like '#FF69B4'. Omit for the plain look.");
        Schemas.prop(s, "scale", "number", "Size, 0.2 (tiny) to 8.0 (enormous). 1.0 is player-sized.");
        Schemas.prop(s, "health", "number", "Hit points, 1 to 500.");
        Schemas.prop(s, "speed", "number", "Move speed, 0.05 (slow) to 0.6 (very fast). 0.25 normal.");
        Schemas.prop(s, "damage", "number", "Attack damage, 0 to 40: the damage its body would have, like a "
                + "vanilla mob its size. A friendly creature never attacks whatever this says, so never 0 to make it "
                + "gentle; 0 lands nothing.");
        Schemas.prop(s, "armor", "number", "Armour, 0 to 30.");
        Schemas.prop(s, "knockbackResistance", "number", "0 to 1. High = hard to push around.");
        Schemas.prop(s, "powers", "array",
                "Any of: hostile, friendly, leaps, fireproof, skittish, glowing, persistent, "
                        + "flies, hops, naps, rideable. 'rideable' = he climbs on by using it and steers; "
                        + "put it here when the wish is something he rides. "
                        + "'friendly' = HARMLESS; without 'friendly' or 'skittish' it ATTACKS players. "
                        + "'hostile' = it attacks PLAYERS, him included; a creature that fights monsters "
                        + "for him is friendly + base 'guardian'. "
                        + SculptCreatureTool.MOVES);
        Schemas.prop(s, "base", "string", SculptCreatureTool.INSTINCTS);
        Schemas.prop(s, com.moddingfromamod.species.BehavesLike.ARG, "string",
                com.moddingfromamod.species.BehavesLike.SCULPT_DESCRIBE);
        Schemas.prop(s, "spawn", "boolean", "Spawn one next to the child right now (default true).");
        Schemas.prop(s, "count", "integer", "How many to spawn, 1 to "
                + com.moddingfromamod.mob.SpawnBudget.SCULPTED_MAX_COUNT + " (default 1). A big "
                + "creature comes in a smaller wave; the result says how many came and what to say.");
        Schemas.prop(s, "at", "string", com.moddingfromamod.place.At.describe("the creature", "its front",
                "In a wish that built something and names no place, it stands at that building's front "
                        + "— never at its anchor, which is its foundations. A boss on \"castle:throne\", "
                        + "a herd at \"castle:around\" spreads round it."));
        // as on sculpt_creature, required, and never refused: one left out is chosen (MagicianBehaviour).
        return Schemas.required(s, "id", com.moddingfromamod.species.BehavesLike.ARG);
    }

    /** Marks the server hop's answer as a failure; stripped before the agent sees it. */
    private static final String NOTHING_STANDING = "\u0000nothing-standing\u0000";

    @Override
    public ToolResult call(JsonObject args) {
        String unknownPowers = args == null ? "" : Species.Power.unknownWords(args.get("powers"));
        if (!unknownPowers.isEmpty()) {   //: dropped in silence before
            return ToolResult.error("Nothing was defined: " + unknownPowers);
        }
        String behavesRefused = SculptCreatureTool.resolveBehavesLike(args);
        if (!behavesRefused.isEmpty()) {   //
            return ToolResult.error("Nothing was defined: " + behavesRefused);
        }
        // none named is chosen for it; a flier never lives on the ground, a sea creature swims.
        final String behavesNote = args == null ? "" : SculptCreatureTool.settleBehaviour(args, java.util.List.of());
        if (args != null && args.has(com.moddingfromamod.species.BehavesLike.ARG)) {
            args.addProperty("behaves", args.get(com.moddingfromamod.species.BehavesLike.ARG).getAsString());
        }
        Species species = Species.fromJson(args);
        if (species == null) {
            return ToolResult.error("A creature needs an 'id'.");
        }
        Boolean explicitSpawn = args.has("spawn") ? args.get("spawn").getAsBoolean() : null;
        int count = 1;
        if (args.has("count")) {
            try {
                count = Math.max(1, Math.min(com.moddingfromamod.mob.SpawnBudget.SCULPTED_MAX_COUNT,
                        args.get("count").getAsInt()));
            } catch (RuntimeException ignored) {
                count = 1;
            }
        }
        final int howMany = count;
        // a spot in a building — wait, off the server thread, until the building is up.
        String seatAt = args.has("at") && args.get("at").isJsonPrimitive() ? args.get("at").getAsString() : "";
        com.moddingfromamod.vanilla.SpotSeat.awaitBuilt(seatAt, 15_000L);
        try {
            final String[] lastHandle = {""};
            final String[] settledNote = {""};
            String said = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                // Re-defining an existing id is a FIX iteration (the put updates every instance in
                // place), so don't spawn ANOTHER copy by default — only the first define spawns.
                boolean reEdit = SpeciesRegistry.has(species.id());
                boolean spawn = explicitSpawn != null ? explicitSpawn : !reEdit;
                Species stored = SpeciesRegistry.put(server, species);
                // record WHICH PATH made this creature. The flag is read here, at the mint,
                // rather than at world load -- a world opened once on the rig path and never
                // wished in contains no rigs and must not claim to.
                // Always the vanilla path retired the legacy renderer: there is
                // one implementation now, so the mark records WHICH it was rather than which of
                // two it was. It still matters -- a world opened by an older jar reads it.
                com.moddingfromamod.lite.MintStore.stamp(server, "species", true);
                if (stored == null) {
                    return "Couldn't save that creature (too many species already?).";
                }
                // Tell every client, so the renderer knows how to draw it.
                com.moddingfromamod.net.ModdedClient.broadcastSpecies();

                StringBuilder sb = new StringBuilder("Creature '" + stored.id() + "' ("
                        + stored.name() + ") is ready: " + stored.body().name().toLowerCase()
                        + " shape, " + stored.texture() + " look, scale " + stored.scale()
                        + ".");
                // #101: what its numbers mean, and a guard that cannot hit, caught before the reveal
                var drawn = com.moddingfromamod.runtime.ModelRepository.geometry(stored.modelKey());
                double vol = drawn == null ? Double.NaN : drawn.volume();
                sb.append(' ').append(com.moddingfromamod.species.PowerLine.of(stored, vol));
                String zeroHit = com.moddingfromamod.species.PowerLine.zeroHitFlag(stored, vol);
                if (!zeroHit.isEmpty()) {
                    sb.append(' ').append(zeroHit);
                }
                String farAbove = com.moddingfromamod.species.PowerLine.farAbove(stored, vol);
                if (!farAbove.isEmpty()) {
                    sb.append(' ').append(farAbove);
                }

                ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);
                if (spawn && player != null) {
                    // on a building's named spot when it names one (or this wish built one).
                    var seat0 = com.moddingfromamod.vanilla.SpotSeat.choose(server, player, seatAt,
                            stored.id(), 0, howMany);
                    ServerLevel level = seat0.level();
                    // At the wish-time LOCKED spot the child aimed at, or in front of him — and
                    // never so close that his eyes are inside it: CreatureSpawn.spotFor.
                    // COUNTED FROM WHAT STANDS THERE, and the handle minted from the CARRIER
                    //. This minted only `if (level.addFreshEntity(wishMob))`, which the
                    // rig bridge makes false every time — so it said "Spawned 0" and never handed
                    // out a handle, while its own comment promised one.
                    int spawned = 0;
                    // Priced by displays: the first one tells us how many there are room for.
                    int displaysBefore = com.moddingfromamod.mob.WishMobLimit.displays(level.dimension());
                    int allowed = howMany;
                    for (int i = 0; i < allowed; i++) {
                        var seat = i == 0 ? seat0 : com.moddingfromamod.vanilla.SpotSeat.choose(
                                server, player, seatAt, stored.id(), i, howMany);
                        var carrier = com.moddingfromamod.vanilla.CreatureSpawn.spawn(level,
                                stored.id(), seat.x(), seat.y(), seat.z(), seat.yaw(),
                                EntitySpawnReason.COMMAND);
                        if (carrier != null) {
                            spawned++;
                            com.moddingfromamod.vanilla.SpotSeat.remember(carrier, stored.id(), seat);
                            // Safe where it stands: a footing, or out of the rock.
                            var settled = com.moddingfromamod.vanilla.SafePlacement.settleSpawn(
                                    player, carrier, spawned == 1);
                            if (spawned == 1) {
                                settledNote[0] = settled.forTheAgent();
                            }
                            lastHandle[0] = com.moddingfromamod.vanilla.CreatureSpawn
                                    .remember(carrier);
                            if (spawned == 1) {
                                allowed = com.moddingfromamod.mob.WishMobLimit.sculptedAllowed(
                                        level.dimension(), displaysBefore, howMany);
                            }
                        }
                    }
                    if (spawned == 0) {
                        return NOTHING_STANDING + "Creature '" + stored.id()
                                + "' is saved, but NOTHING is standing in the world — the "
                                + "spawn failed (no room there?). "
                                + "Try spawning it again somewhere open with run_command "
                                + "`wishmob spawn " + stored.id() + "`, and do not tell him it is "
                                + "here until a spawn reports it.";
                    }
                    sb.append(" Spawned ").append(spawned).append(seat0.onSpot()
                                    ? " on " + seat0.link() + "." : " next to the child.")
                            .append(settledNote[0]).append(seat0.note());
                    if (allowed < howMany) {
                        sb.append(' ').append(com.moddingfromamod.mob.SpawnBudget.forTheAgent(
                                com.moddingfromamod.mob.SpawnBudget.waveLine(
                                        com.moddingfromamod.mob.SpawnBudget.Kind.SCULPTED,
                                        stored.name(), spawned)));
                    }
                    if (!lastHandle[0].isEmpty()) {
                        sb.append(" Handle: ").append(lastHandle[0])
                          .append(" (use it as modify_mob's target).");
                    }
                } else if (spawn) {
                    sb.append(" (Nobody is in the world, so I didn't spawn one.)");
                }
                // what this creature will DO, said while the agent can still change it.
                for (String line : com.moddingfromamod.species.InstinctNotes.forSpecies(stored,
                        spawn && player != null ? howMany : 0)) {
                    sb.append('\n').append(line);
                }
                return sb.toString();
            }, 10_000L);
            // A spawn that stood nothing up is an ERROR the agent reads as one, not a
            // number inside an ok result.
            return said != null && said.startsWith(NOTHING_STANDING)
                    ? ToolResult.error(said.substring(NOTHING_STANDING.length()))
                    : ToolResult.ok(behavesNote.isEmpty() ? said : said + "\n" + behavesNote);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
