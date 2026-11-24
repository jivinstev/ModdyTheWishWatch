package com.moddingfromamod.agent.tools;

import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.item.Locate;
import com.moddingfromamod.item.LocateTargets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * <b>{@code locate structure|biome <id>} in {@code run_command}, answered rather than run</b>.
 *
 * <p>A child wished "teleport me to a far away plains"; the agent's first move was
 * {@code locate biome minecraft:plains}, refused, and it then threw him across the map four times
 * guessing (live, 2026-10-16). Three wishes in three weeks went the same way. So {@code locate} is
 * allowed — but never DISPATCHED: vanilla's search runs on the server thread, and a structure search
 * once froze it for 59 s. The finding compass already searches safely ({@link Locate#find}:
 * structures {@code STRUCTURE_CHUNKS} placement cells out, biomes {@code BIOME_BLOCKS} blocks and
 * off the server thread), so this asks it, and answers with what the next call needs — where it is,
 * how far, and the ground there, so a single {@code tp} lands him on it.
 *
 * <p>Nothing about plains or teleports is special here: any structure or biome id or tag, and the
 * answer is coordinates the agent uses however the wish wants.
 */
final class WishLocate {

    private WishLocate() {
    }

    /** Answer one {@code locate structure|biome <id>} command (already passed by {@code CommandGuard}). */
    static ToolResult answer(String command) {
        String[] p = command.trim().split("\\s+");
        boolean structure = p[1].equalsIgnoreCase("structure");
        LocateTargets.Kind kind = structure ? LocateTargets.Kind.STRUCTURE : LocateTargets.Kind.BIOME;
        String id = p[2].toLowerCase(Locale.ROOT);
        String bare = id.startsWith("#") ? id.substring(1) : id;
        if (ResourceLocation.tryParse(bare) == null) {
            return ToolResult.error("'" + p[2] + "' is not a " + p[1] + " id — write it like minecraft:plains, "
                    + "or a tag like #minecraft:is_forest.");
        }
        // A WORLD WHERE A WISH STAYS ON HIS OWN BASE: every place a search finds is off it,
        // and the tp that would follow is refused by BaseReach — so say that now, not after a search.
        var rules = com.moddingfromamod.world.WorldRulesService.current();
        if (rules.baseReach()) {
            var rule = rules.ruleFor(com.moddingfromamod.world.WorldEffect.OUTSIDE_OWN_BASE);
            return ToolResult.ok("Not searched: on this world " + (rule == null ? "a wish stays on his own base"
                    : rule.rule()) + ", so there is nowhere a " + p[1] + " search could take him. "
                    + "Make what he asked for on his own base instead (a little plains, a village of his own), "
                    + "or tell him it works in " + rules.workshop() + ".");
        }
        LocateTargets.Target t = new LocateTargets.Target(id, kind, List.of(id), id);
        try {
            // Who it is measured from, and the id checked against this world's registries — one hop.
            Where w = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer him = com.moddingfromamod.agent.ActingChild.of(server);
                ServerLevel level = him != null ? him.level() : server.overworld();
                BlockPos from = him != null ? him.blockPosition() : level.getRespawnData().pos();
                return new Where(level, from, exists(level, structure, id));
            });
            if (!w.known()) {
                return ToolResult.error("There is no " + p[1] + " called '" + p[2] + "' in this world. Use its "
                        + "full id, like minecraft:" + (structure ? "village_plains" : "plains")
                        + ", or a tag, like #minecraft:" + (structure ? "village" : "is_forest") + ".");
            }
            long t0 = System.nanoTime();
            // A structure search generates structure starts, which is only safe on the server thread
            // (and is bounded); a biome search only samples climate noise, so it runs right here.
            Optional<BlockPos> found = structure
                    ? AgentBridge.onServer(() -> Locate.find(w.level(), w.from(), t), 120_000)
                    : Locate.find(w.level(), w.from(), t);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (found.isEmpty()) {
                return ToolResult.ok("No " + id + " within reach of him (searched "
                        + (structure ? Locate.STRUCTURE_CHUNKS + " structure cells out" : Locate.BIOME_BLOCKS + " blocks out") + ", " + ms + " ms). "
                        + "Tell him so plainly, or pick a different place — do not guess coordinates.");
            }
            BlockPos at = found.get();
            // The ground there: the one chunk is generated if it has to be, on the server thread.
            int ground = AgentBridge.onServer(() ->
                    w.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()));
            int blocks = (int) Math.round(Math.sqrt(w.from().distSqr(new BlockPos(at.getX(), w.from().getY(), at.getZ()))));
            return ToolResult.ok("The nearest " + id + " is about " + blocks + " blocks away, at x=" + at.getX()
                    + " z=" + at.getZ() + " (" + w.level().dimension().location() + "). The ground there is at y="
                    + ground + ", so `tp @s " + at.getX() + " " + (ground + 1) + " " + at.getZ()
                    + "` stands him on it." + (structure && at.getY() < ground - 4
                    ? " The structure itself starts lower, at y=" + at.getY() + "." : ""));
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private record Where(ServerLevel level, BlockPos from, boolean known) {
    }

    /** Is this id (or tag) a structure / biome this world knows? */
    private static boolean exists(ServerLevel level, boolean structure, String id) {
        boolean tag = id.startsWith("#");
        ResourceLocation rl = ResourceLocation.tryParse(tag ? id.substring(1) : id);
        if (rl == null) {
            return false;
        }
        if (structure) {
            var reg = level.holderLookup(Registries.STRUCTURE);
            return tag ? reg.get(TagKey.create(Registries.STRUCTURE, rl)).isPresent()
                    : reg.get(ResourceKey.create(Registries.STRUCTURE, rl)).isPresent();
        }
        var reg = level.holderLookup(Registries.BIOME);
        return tag ? reg.get(TagKey.create(Registries.BIOME, rl)).isPresent()
                : reg.get(ResourceKey.create(Registries.BIOME, rl)).isPresent();
    }
}
