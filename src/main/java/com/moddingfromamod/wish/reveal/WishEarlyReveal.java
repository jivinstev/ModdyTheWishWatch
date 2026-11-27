package com.moddingfromamod.wish.reveal;

import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.vanilla.RigReveal;
import com.moddingfromamod.vanilla.WishShimmer;
import com.moddingfromamod.wish.BuildGate;
import com.moddingfromamod.wish.WishFeedback;
import com.moddingfromamod.wish.WishProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The pillar bursts when the creature first stands there, not when the wish is finished</b>.
 *
 * <p>On the gold-dragon demo the dragon stood in the world 35 s in, and the pillar stayed up for
 * another 108 s, until the camera gave up. A pillar that stays up after the creature is there is
 * confusing: the pillar should shatter and reveal the creature with the first (shimmering)
 * sculpt. So after every tool call, the seam in
 * {@code McpProtocol} asks this class whether a creature came to stand in the world during the
 * call ({@link RigReveal#stoodSince}). The first time one did for a wish:
 * <ol>
 *   <li>the creature reveal plays now ({@link Reveals#earlyCreature}), taking the pillar, which
 *       does not come back ({@code WishOrbs.release});</li>
 *   <li>the creature is released from the veil and its species starts to shimmer
 *       ({@link WishShimmer});</li>
 *   <li>Moddy says why it is shimmering ({@link WishFeedback#appearing}) and the status line says
 *       what is happening.</li>
 * </ol>
 * After that, a repaint updates the status line, and the first change of shape is said once
 * ({@link WishFeedback#reshaping}). {@code finish_building} ends it: the shimmer stops and the
 * "all done" plays.
 *
 * <p>The early reveal is driven by what the WORLD did, never by which tool was called: a creature
 * the agent designs with {@code sculpt_creature} and spawns later with {@code run_command} (the
 * Muse path) reveals on the spawn, exactly when it really stands there — the reason creature
 * reveals were held back to the finish in the first place.
 */
public final class WishEarlyReveal {

    private WishEarlyReveal() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-early-reveal");

    /** Wishes whose creature has been shown. */
    private static final Set<UUID> SHOWN = ConcurrentHashMap.newKeySet();

    /** Wish/species pairs already announced, so a second creature in one wish gets its own line. */
    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

    /** At most this many creatures are named as they appear in one wish; the rest just shimmer in. */
    static final int MAX_NAMED = 3;

    /** Wishes that have already been told "trying that bit again". */
    private static final Set<UUID> RESHAPE_SAID = ConcurrentHashMap.newKeySet();

    public static final String STATUS_SHAPING = "🌈 Wishing it into shape…";
    public static final String STATUS_PAINTING = "🎨 Painting it — watch the colours come in!";
    public static final String STATUS_RESHAPING = "🔧 Reshaping it…";
    public static final String STATUS_CHANGING = "🌈 Changing it…";

    /** Tools that change the SHAPE of a creature he may already be watching. */
    private static final Set<String> RESHAPERS = Set.of("sculpt_creature", "define_creature", "modify_mob");

    /** Tools that design or repaint a creature named by {@code id}. */
    private static final Set<String> DESIGNERS = Set.of("sculpt_creature", "define_creature", "paint_creature");

    /** How long to wait for a standing creature's rebuild: the animator does it within a tick or two. */
    static final long REBUILD_WAIT_MS = 1_000L;

    /** Was this wish's creature revealed early? */
    public static boolean shown(UUID wishId) {
        return wishId != null && SHOWN.contains(wishId);
    }

    /**
     * Which of the creatures that just stood belong to THIS wish. Pure.
     *
     * <p>With one wish in flight, all of them. With several, only the ones this wish made: two
     * agents' calls can overlap, and a creature attributed to the wrong wish would burst the wrong
     * pillar.
     */
    public static List<String> attribute(List<String> stood, List<String> madeCreatureIds, int liveWishes) {
        if (stood.isEmpty() || liveWishes <= 1) {
            return List.copyOf(stood);
        }
        List<String> out = new ArrayList<>();
        for (String s : stood) {
            if (madeCreatureIds.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Called by {@code McpProtocol} after every tool call of a bound wish. Any thread.
     *
     * @param mark {@link RigReveal#stoodMark()}, taken just before the call
     */
    public static void afterTool(WishRun run, String tool, long mark) {
        afterTool(run, tool, mark, null, true);
    }

    /**
     * @param args the call's arguments (a sculpt's {@code id} and {@code at}), or null
     * @param ok   did the call succeed
     */
    public static void afterTool(WishRun run, String tool, long mark,
                                 com.google.gson.JsonObject args, boolean ok) {
        if (run == null || run.state().isFinished()
                || BuildGate.forWish(run.wishId()).wasFinishBuildingCalled()) {
            return;
        }
        boolean wasShown = SHOWN.contains(run.wishId());
        // Only what stood since THIS wish was handed to its worker: await_wish's call spans the gap
        // between wishes, and the last wish's creature is not this one's.
        final long since = run.assignedMs();
        List<String> stood = RigReveal.stoodSince(mark, since);
        // The creature this call designed or repainted, by id.
        String designed = null;
        if (ok && DESIGNERS.contains(tool) && args != null && args.has("id")
                && args.get("id").isJsonPrimitive()) {
            designed = args.get("id").getAsString();
        }
        // AN EDIT LANDS A TICK LATE. A re-sculpt or repaint of a creature already standing is
        // rebuilt by the animator on its next tick, after this call has returned, so nothing has
        // stood yet. Wait for it rather than take it for a creature designed and not spawned: that
        // stood a frozen preview beside his real dragon.
        boolean alreadyStanding = !wasShown && designed != null && stood.isEmpty()
                && standing(designed);
        // A RESIZE (modify_mob scale) rebuilds or reshapes on the next tick too, and names a handle,
        // not an id (filmed 2026-10-22: "make my Spikor bigger" grew him with no shimmer and no
        // "Changing your Spikor!" until the crown's sculpt came after it).
        boolean resized = !wasShown && stood.isEmpty() && ok && "modify_mob".equals(tool)
                && args != null && args.has("scale");
        if (alreadyStanding || resized) {
            stood = awaitStood(mark, since, REBUILD_WAIT_MS);
            alreadyStanding = alreadyStanding || resized;
        }
        // A creature DESIGNED and not spawned (spawn:false, the schema's advice for anything with a
        // power) stands as a preview, so the early reveal is not pushed to the end (WishShimmer).
        String previewOf = stood.isEmpty() && !wasShown && !alreadyStanding
                && "sculpt_creature".equals(tool) && !run.kept()
                && !com.moddingfromamod.world.WorldOwners.is(run.playerId())   // a world's design has no audience
                ? designed : null;   // a kept make stands nothing (#70)
        if (stood.isEmpty() && !wasShown && previewOf == null) {
            return;
        }
        // HIS creature changing, not a new one arriving: everything that stood
        // was already standing, or the wish said it is about this very creature.
        final boolean rebuiltOnly = alreadyStanding || RigReveal.onlyRebuiltSince(mark, since);
        final String about = run.aboutArtifact();
        final String previewSpecies = previewOf;
        final String previewAt = args != null && args.has("at") && args.get("at").isJsonPrimitive()
                ? args.get("at").getAsString() : "";
        List<String> made = new ArrayList<>();
        for (WishRun.Made m : run.made()) {
            if (MadeKind.CREATURE.equals(m.kind()) || MadeKind.MOB.equals(m.kind())) {
                made.add(m.id());
            }
        }
        int live = 0;
        for (WishRun r : com.moddingfromamod.marshal.Marshal.global().all()) {
            if (r.state().isLive()) {
                live++;
            }
        }
        List<String> attributed = attribute(stood, made, live);
        try {
            AgentBridge.onServerRun(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return;
                }
                List<String> mine = attributed;
                if (previewSpecies != null) {
                    var preview = WishShimmer.preview(server, run.playerId(), previewSpecies, previewAt);
                    if (preview == null) {
                        return;
                    }
                    LOG.info("[early-reveal] wish {}: '{}' designed and not spawned -- standing a "
                            + "preview until the real one arrives or the wish ends", run.shortId(),
                            previewSpecies);
                    mine = List.of(previewSpecies);
                }
                ServerPlayer owner = run.playerId() == null ? null
                        : server.getPlayerList().getPlayer(run.playerId());
                if (!mine.isEmpty()) {
                    WishShimmer.begin(run.wishId(), mine, server.overworld().getGameTime());
                    // Release whatever the veil took during this call (a camera serving): from
                    // now on this species is never veiled (RigVeilService.veil).
                    RigReveal.reveal(server);
                    if (SHOWN.add(run.wishId())) {
                        Reveals.earlyCreature(run, mine.get(0));
                        boolean edit = rebuiltOnly || (about != null && mine.contains(about));
                        for (String sp : mine) {
                            ANNOUNCED.add(run.wishId() + "/" + sp);
                        }
                        if (owner != null && edit) {
                            WishFeedback.changing(owner, nameOf(mine.get(0)));
                            WishProgress.setStatus(owner, STATUS_CHANGING, "building");
                        } else if (owner != null) {
                            WishFeedback.appearing(owner, nameOf(mine.get(0)));
                            WishProgress.setStatus(owner, STATUS_SHAPING, "building");
                        }
                        LOG.info("[early-reveal] wish {}: {} {} -- pillar burst, shimmering until "
                                + "finish_building", run.shortId(), mine,
                                edit ? "changing (an edit)" : "stands in the world");
                        return;
                    }
                }
                // A SECOND creature in a wish already shown (a castle and its seahorses): it
                // shimmers with the first, and is named as it appears, once.
                if (wasShown && owner != null && !rebuiltOnly) {
                    for (String sp : mine) {
                        if (ANNOUNCED.add(run.wishId() + "/" + sp)) {
                            WishShimmer.begin(run.wishId(), List.of(sp), server.overworld().getGameTime());
                            RigReveal.reveal(server);
                            String prefix = run.wishId() + "/";
                            if (ANNOUNCED.stream().filter(k -> k.startsWith(prefix)).count() <= MAX_NAMED) {
                                WishFeedback.appearing(owner, nameOf(sp));   // a zoo is not ten lines
                            }
                        }
                    }
                }
                if (!wasShown || owner == null) {
                    return;
                }
                if ("paint_creature".equals(tool)) {
                    WishProgress.setStatus(owner, STATUS_PAINTING, "building");
                } else if (RESHAPERS.contains(tool)) {
                    WishProgress.setStatus(owner, STATUS_RESHAPING, "building");
                    if (RESHAPE_SAID.add(run.wishId())) {
                        WishFeedback.reshaping(owner);
                    }
                }
            });
        } catch (AgentBridge.Unavailable e) {
            LOG.warn("[early-reveal] wish {}: could not reach the server: {}", run.shortId(),
                    e.getMessage());
        }
    }

    private static boolean standing(String species) {
        try {
            Boolean b = AgentBridge.onServer(() -> WishShimmer.standing(AgentBridge.serverOrNull(), species));
            return b != null && b;
        } catch (AgentBridge.Unavailable e) {
            return false;
        }
    }

    private static List<String> awaitStood(long mark, long since, long maxMs) {
        long until = System.currentTimeMillis() + maxMs;
        List<String> stood = RigReveal.stoodSince(mark, since);
        while (stood.isEmpty() && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            stood = RigReveal.stoodSince(mark, since);
        }
        return stood;
    }

    /** What he calls it: the species' own name, or "" when it has none. */
    private static String nameOf(String species) {
        var sp = com.moddingfromamod.species.SpeciesRegistry.get(species);
        return sp == null || sp.name() == null ? "" : sp.name();
    }

    /**
     * The wish is at its end ({@code finish_building}): stop the shimmer. Server thread. Returns
     * whether this wish's creature had been revealed early (so the end says "finished", not "here").
     */
    public static boolean end(net.minecraft.server.MinecraftServer server, UUID wishId) {
        WishShimmer.end(server, wishId);
        RESHAPE_SAID.remove(wishId);
        ANNOUNCED.removeIf(k -> k.startsWith(wishId + "/"));
        return SHOWN.remove(wishId);
    }
}
