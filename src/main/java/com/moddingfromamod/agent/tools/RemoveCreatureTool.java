package com.moddingfromamod.agent.tools;


import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.server.level.ServerLevel;

/**
 * Remove a wish creature from the world.
 *
 * <p>The mob analogue of {@link RemoveTrainTool}, and it exists for the same reason: the agent had
 * NO way to clean up a creature. A wish mob is an entity, and {@code /kill} is not on the command
 * allowlist, so a creature the child rejected — or a duplicate left over from tweaking a look — just
 * stayed. Now the agent can take it back out before making a replacement.
 *
 * <p>Removes the living creatures of that species; the DESIGN and its spawn egg are kept, so the same
 * creature can be spawned again instantly.
 */
public final class RemoveCreatureTool implements McpTool {

    @Override
    public String name() {
        return "remove_creature";
    }

    @Override
    public String description() {
        return "Remove a wish creature from the world — use this to clean up a creature the child "
                + "rejected, or duplicates left from fixing its look, BEFORE spawning a replacement. "
                + "Pass the creature's id. The design and spawn egg are kept, so you can spawn it "
                + "again any time.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.prop(Schemas.object(), "id", "string",
                "The id of the creature to remove.");
        return Schemas.required(s, "id");
    }

    /**
     * every creature is an ATOM RIG — a vanilla carrier wearing the species tag,
     * plus display parts and hit proxies — so a sweep over {@code WishMobEntity} alone found none
     * and told the agent the creature was "already gone" while it stood there. A carrier is found
     * the one way the rest of the rig code finds it ({@code DisplayRig.speciesOn}, through the same
     * tag function the spawn writes), and removed the way a rebuild removes one: its driver
     * forgotten, its parts and proxies reaped, then the carrier discarded. Collected first and
     * removed after, so the level's entity list is never changed under its own iterator.
     *
     * @return how many creatures were removed
     */
    public static int removeRigs(ServerLevel level, String speciesId) {
        java.util.List<net.minecraft.world.entity.Entity> carriers = new java.util.ArrayList<>();
        for (var e : level.getEntities().getAll()) {
            if (e instanceof net.minecraft.world.entity.LivingEntity
                    && !e.isRemoved()
                    && e.getTags().contains(com.moddingfromamod.vanilla.DisplayRig.RIG_TAG)) {
                var sp = com.moddingfromamod.vanilla.DisplayRig.speciesOn(e);
                if (sp != null && speciesId.equalsIgnoreCase(sp.id())) {
                    carriers.add(e);
                }
            }
        }
        int removed = 0;
        for (var carrier : carriers) {
            if (carrier instanceof net.minecraft.world.entity.LivingEntity living
                    && com.moddingfromamod.mob.Creatures.remove(level, living)) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = args.has("id") ? args.get("id").getAsString() : null;
        if (id == null || id.isBlank()) {
            return ToolResult.error("Which creature? Pass the id to remove.");
        }
        if (!SpeciesRegistry.has(id)) {
            return ToolResult.error("I have no record of a creature called '" + id + "'.");
        }
        String wanted = id.trim();
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                int removed = 0;
                // A creature can wander into any dimension, so sweep them all (loaded chunks).
                for (ServerLevel level : server.getAllLevels()) {
                    // Carriers only: no WishMobEntity is ever in a level (the bridge
                    // cancels its join), so the loop that looked for them removed nothing.
                    removed += removeRigs(level, wanted);
                }
                if (removed == 0) {
                    return ToolResult.ok("No '" + wanted + "' creatures are currently in the world "
                            + "(maybe already gone). Its design is still saved, so you can spawn it "
                            + "again.");
                }
                // SAY WHAT SURVIVES —. The design is kept on purpose, and so are the abilities
                // bound to it; an agent told only "removed" assumed the fire breath went too.
                var sp = SpeciesRegistry.get(wanted);
                String abilities = sp == null || sp.abilityPacks().isEmpty() ? ""
                        : " Its " + sp.abilityPacks().size() + " authored ability/abilities ("
                          + String.join(", ", sp.abilityPacks()) + ") stay loaded and will act on it "
                          + "again the moment you spawn one — remove_datapack them if he wants them "
                          + "gone for good.";
                return ToolResult.ok("Removed " + removed + " '" + wanted + "' creature(s). The "
                        + "design and spawn egg are kept, so you can spawn it again any time."
                        + abilities);
            }, 15_000L);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
