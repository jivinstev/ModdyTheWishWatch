package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.datapack.DatapackReloader;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WishRun;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Putting it back — <b>the mod doing it itself, not asking the agent to.</b>
 *
 * <p>That is the whole design constraint. By the time an undo runs, the worker has been refused
 * every tool and told to park; asking it to tidy up would mean un-refusing it, which is how "stop"
 * quietly becomes "stop, eventually, if it feels like it". So an undo replays the same registry
 * code the {@code remove_*} tools already run, from the list of things the wish recorded making.
 *
 * <p><b>It is deliberately not universal, and that is the point.</b> Undo is only ever OFFERED when
 * {@link com.moddingfromamod.marshal.UndoContract} says every tool the wish used had an inverse, so
 * this method is never reached for a wish that ran {@code run_command}. Never show an undo button
 * that lies.
 *
 * <p>No region snapshots. A 200,000-block castle is replayed from its plan and anchor
 * ({@link BuildRegistry}), which is both cheaper and already how {@code remove_build} works.
 */
public final class WishUndo {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("mfam-wish-undo");

    /** What an undo managed to reverse, in words a child can be told. */
    public record Result(int reversed, List<String> did, List<String> couldNot) {

        public boolean anything() {
            return reversed > 0;
        }

        /** One short sentence, or "" when nothing was reversed. */
        public String sentence() {
            if (did.isEmpty()) {
                return "";
            }
            return "(" + String.join(", ", did) + ")";
        }
    }

    private WishUndo() {
    }

    /**
     * Reverse everything this wish made, newest first.
     *
     * <p>Newest-first matters: a later step may sit on top of an earlier one, and taking the
     * foundation away first leaves the thing above it stranded.
     *
     * <p>Must be called on the server thread.
     */
    public static Result undo(MinecraftServer server, WishRun run) {
        List<String> did = new ArrayList<>();
        List<String> couldNot = new ArrayList<>();
        if (server == null || run == null) {
            return new Result(0, did, couldNot);
        }
        // Anything still going up stops before anything is taken down, or the builder would keep
        // placing blocks into the hole the undo just made.
        Builder.cancelJobsFor(run.wishId());

        int reversed = 0;
        for (WishRun.Made made : run.made()) {
            boolean ok;
            try {
                ok = undoOne(server, made.kind(), made.id(), run.wishId(), run);
            } catch (RuntimeException e) {
                // One reversal failing must not abandon the rest half-undone; the child is told
                // honestly what did and did not come back.
                ok = false;
            }
            if (ok) {
                reversed++;
                did.add(made.id());
            } else {
                couldNot.add(made.id());
            }
        }
        // The footing a creature of this wish was given over water or lava goes too: it
        // was laid for the creature, not asked for.
        com.moddingfromamod.vanilla.SafePlacement.undoFootings(server, run.wishId());
        // EVERYTHING IT HANDED OVER: from his hands, anywhere in his inventory, and
        // his Wish Bag — exactly what WishDelivery recorded against this wish, no more.
        int back = com.moddingfromamod.item.delivery.WishDelivery.takeBack(server, run.wishId().toString());
        if (back > 0) {
            reversed++;
            did.add(back + " thing" + (back == 1 ? "" : "s") + " handed over");
        }
        return new Result(reversed, did, couldNot);
    }

    /**
     * The same, for a wish that may have no run in the marshal (an earlier issue's 👎 on a wish granted
     * offline, from a template, or whose run was forgotten). With a run this IS {@link #undo}; with
     * none, the parts recorded against the wish id itself still come back — what it handed over and
     * the footing it laid — because a missing run must never mean "nothing to take away".
     */
    public static Result undo(MinecraftServer server, java.util.UUID wishId, WishRun run) {
        if (run != null) {
            return undo(server, run);
        }
        List<String> did = new ArrayList<>();
        if (server == null || wishId == null) {
            return new Result(0, did, new ArrayList<>());
        }
        Builder.cancelJobsFor(wishId);
        com.moddingfromamod.vanilla.SafePlacement.undoFootings(server, wishId);
        int back = com.moddingfromamod.item.delivery.WishDelivery.takeBack(server, wishId.toString());
        if (back > 0) {
            did.add(back + " thing" + (back == 1 ? "" : "s") + " handed over");
        }
        return new Result(did.size(), did, new ArrayList<>());
    }

    /**
     * Reverse ONE thing a wish made.
     *
     * <p>Split out of the loop above so each kind's reversal can be driven directly by a test. It
     * could not be before: the only way in was a whole {@code WishRun}, so every case in this
     * switch was reachable only through a full granted wish. That is a long way to go to find out
     * whether an undo puts a picture back.
     *
     * <p><b>An unknown kind is FALSE, not an exception.</b> A kind added later and forgotten here
     * must report "I could not put that back" — which the child is told — rather than throwing and
     * abandoning the rest of the wish half-undone.
     */
    public static boolean undoOne(MinecraftServer server, String kind, String id) {
        return undoOne(server, kind, id, null);
    }

    /**
     * The same, for a kind whose inverse depends on WHICH WISH is being undone.
     *
     * <p>{@link MadeKind#MOB} is the one so far, and it is the odd kind out for the same reason it
     * is odd in {@code MadeKind}: every other reversal deletes something the wish brought into
     * existence, and the artifact id is enough to find it. A mob was already standing there, so what
     * is reversed is the CHANGE — and a mob may carry changes from several wishes, so putting it
     * back needs to know whose. Hence the extra argument rather than a lookup: there is no
     * "the" previous state of a mob, only this wish's.
     *
     * <p>A null {@code wishId} therefore cannot undo a mob and says so by returning false, which is
     * the honest answer for a caller that does not know which wish it is reversing.
     */
    public static boolean undoOne(MinecraftServer server, String kind, String id,
                                  java.util.UUID wishId) {
        return undoOne(server, kind, id, wishId, null);
    }

    /**
     * The same, with the wish's RUN — which a creature's undo needs to know which creatures this
     * wish spawned. Null reverts to the species-wide removal, for a caller with no run.
     */
    public static boolean undoOne(MinecraftServer server, String kind, String id,
                                  java.util.UUID wishId, WishRun run) {
        if (MadeKind.MOB.equals(kind)) {
            return undoMob(server, id, wishId);
        }
        return switch (kind) {
            case MadeKind.BUILD -> undoBuild(server, id);
            case MadeKind.CREATURE -> undoCreature(server, id, run);
            case MadeKind.DATAPACK -> undoDatapack(server, id);
            case MadeKind.PAINTING -> undoPainting(server, id);
            // The event's journal puts back every block; a design that was only kept is forgotten.
            case MadeKind.EVENT -> com.moddingfromamod.event.EventDirector.undo(server, id)
                    || com.moddingfromamod.event.design.EventDesigns.forget(server, id);
            // Anything else — including "contraption" and "train", which an undo ledger written
            // may still hold — has nothing this mod can reverse any more.
            default -> false;
        };
    }

    /**
     * Put a building back the way it was before the last wish about it —.
     *
     * <p>This used to clear {@code BuildRegistry.get(id)}, the CURRENT merged plan, so undoing
     * <i>"put a dome on it"</i> took the castle and the dome together. The thing he asked to undo
     * was the only part that survived. It reached that path on every refinement, because
     * {@code McpProtocol} records {@code made(BUILD, id)} on every {@code build_structure} call
     * including a {@code start_from} edit.
     *
     * <p>Two different acts behind one word, and the history is what tells them apart:
     *
     * <ul>
     *   <li><b>a refinement</b> — there is a previous version, so the building goes back to it and
     *       stays standing;</li>
     *   <li><b>a creation</b> — there is none, so the building is removed, which is what undoing a
     *       creation has always meant.</li>
     * </ul>
     *
     * <p><b>The older version becomes what PERSISTS</b>, via {@code restore} rather than
     * {@code put}: the next refinement has to inherit the reverted castle, not the undone one.
     * Fixing the world and leaving the record ahead of it is an earlier issue's R3 bug in a new place — and
     * {@code restore} deliberately does not re-stack the version being undone, or a second undo
     * would walk forwards into what he just reverted.
     */
    private static boolean undoBuild(MinecraftServer server, String id) {
        BuildPlan plan = BuildRegistry.get(id);
        Long anchor = BuildRegistry.anchorOf(id);
        if (plan == null || anchor == null) {
            return false;
        }
        ServerLevel level = com.moddingfromamod.agent.ArtifactLevel.resolve(
                server, BuildRegistry.dimensionOf(id));
        BlockPos at = BlockPos.of(anchor);
        BuildPlan previous = BuildRegistry.popPrevious(id);
        if (previous == null) {
            boolean cleared = Builder.clearBuild(level, at, plan) > 0;      // a creation: remove it
            BuildRegistry.markRemoved(server, id);
            return cleared;
        }
        // Clear what is standing before putting the older one back, or the two are merged and the
        // dome survives inside the castle it was added to.
        Builder.clearBuild(level, at, plan);
        BuildRegistry.restore(server, previous);
        Builder.submit(level, at, previous, true,
                com.moddingfromamod.builder.BuildGround.Ground.inPlace(id), null);
        return true;
    }

    /**
     * Put a painted surface back.
     *
     * <p>Two different acts behind one word, and the record is what tells them apart. A panel this
     * wish PUT THERE is taken away; a panel that was already there gets the picture it had before.
     * Guessing between them from the world is impossible after the fact — the block entity holds
     * only the current picture — which is the whole reason {@code PaintRecord} keeps the previous
     * one rather than inferring it.
     */
    private static boolean undoPainting(MinecraftServer server, String id) {
        com.moddingfromamod.paint.PaintRecord rec = com.moddingfromamod.paint.PaintRegistry.get(id);
        if (rec == null) {
            return false;
        }
        // The dimension is stored as its id string, so it can be read back on a world where that
        // dimension no longer exists without an unresolvable Holder — the "wrong-side registry"
        // shape. ArtifactLevel falls back to the overworld for anything it cannot resolve.
        net.minecraft.resources.ResourceLocation dim =
                net.minecraft.resources.ResourceLocation.tryParse(rec.dimension());
        ServerLevel level = com.moddingfromamod.agent.ArtifactLevel.resolve(server,
                dim == null ? null : net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (level == null) {
            return false;
        }
        BlockPos at = new BlockPos(rec.x(), rec.y(), rec.z());
        // ── TAKE THE VANILLA COPY DOWN FROM THE RECORD, not from the world. After the path-2 flip
        // a picture on a static wall is map art and there is NO PANEL at this position at all — so
        // the facing and the span this needs cannot be read back off a block state, which is why
        // PaintRecord now carries them. The pre-flip form early-returned here on "no panel" and
        // would have left every map of a 6x5 mural hanging over a wish it reported as reversed.
        int tilesDown = 0;
        if (rec.hasVanillaCopy()) {
            net.minecraft.core.Direction f =
                    net.minecraft.core.Direction.byName(rec.facing());
            if (f != null) {
                tilesDown = com.moddingfromamod.paint.MapArtPanels.takeDown(
                        level, at, f, rec.wide(), rec.tall());
            }
        }
        if (!(level.getBlockEntity(at) instanceof
                com.moddingfromamod.paint.PaintPanelBlockEntity panel)) {
            // ── UNDOING A REPAINT ON THE VANILLA ROUTE PUTS THE EARLIER PICTURE BACK, which is
            // the same promise the panel route has always kept and the reason PaintRecord carries
            // the previous recipe. Taken down and re-hung rather than repainted in place, because
            // the earlier picture may be a different SIZE and repainting would leave the extra
            // tiles of the newer one hanging round the edges.
            boolean restored = false;
            if (rec.hasVanillaCopy() && rec.hadAPictureBefore()) {
                net.minecraft.core.Direction f =
                        net.minecraft.core.Direction.byName(rec.facing());
                try {
                    if (f != null && com.moddingfromamod.paint.PaintArt.write(
                            rec.previousKey(), rec.previousRecipe()) != null) {
                        restored = com.moddingfromamod.paint.MapArtPanels.hang(level, at, f,
                                rec.previousRecipe(), rec.previousWidth(),
                                rec.previousHeight()).ok();
                    }
                } catch (java.io.IOException e) {
                    restored = false;
                }
            }
            // No panel, no maps and nothing restored means somebody has already mined it, or it
            // rode away on a contraption. Nothing to put back, and saying so honestly is better
            // than reporting a reversal that did not happen.
            // AND THE BOARD IT STOOD ON: a picture hung from the library or a button
            // (/wishpaint) stood up a dark-oak board and cleared the cells in front of it; taking
            // only the frames down left that board in the child's world. Not when an earlier
            // picture was just re-hung — that one needs its wall.
            int boardBack = restored ? 0
                    : com.moddingfromamod.paint.PictureCommand.takeDownBoard(level, rec);
            com.moddingfromamod.paint.PaintRegistry.remove(server, id);
            return tilesDown > 0 || restored || boardBack > 0;
        }
        boolean done;
        if (rec.placedPanel()) {
            done = level.removeBlock(at, false);
        } else if (rec.hadAPictureBefore()) {
            // Re-bake it: the image on disk was overwritten by the paint we are reversing, and the
            // recipe is precisely what makes that recoverable without having kept a PNG.
            try {
                com.moddingfromamod.paint.PaintArt.write(rec.previousKey(), rec.previousRecipe());
            } catch (java.io.IOException e) {
                return false;
            }
            panel.setArt(rec.previousKey(), rec.previousRecipe(),
                    rec.previousWidth(), rec.previousHeight());
            done = true;
        } else {
            // It was a blank panel before, and it is one again.
            panel.setArt("", com.moddingfromamod.paint.PaintRecipe.blank(),
                    rec.previousWidth(), rec.previousHeight());
            done = true;
        }
        if (done) {
            com.moddingfromamod.paint.PaintRegistry.remove(server, id);
        }
        return done;
    }

    /**
     * Put a creature back the way it was before the last wish about it —.
     *
     * <p>Two different acts behind one word, and which one this is decides everything:
     *
     * <ul>
     *   <li><b>a refinement</b> — there is a previous sculpt, so the creature goes back to it and
     *       KEEPS STANDING. Undoing <i>"give him tiny black wings"</i> leaves him there without
     *       wings.</li>
     *   <li><b>a creation</b> — there is none, so the mobs are discarded and the DESIGN is kept, as
     *       {@code remove_creature} keeps it: he stopped the wish, he did not ask for the idea to
     *       be forgotten, and a spawn egg that still works is how he gets it back.</li>
     * </ul>
     *
     * <p>The old behaviour was the second one applied to both, which is the worst possible split:
     * he lost his creature and re-spawning it gave him the winged one back — the thing he asked to
     * undo was the only part that survived.
     *
     * <p>Reverting is a FOLDER and a REGISTRY ROW together. {@code SpeciesRegistry.put} updates
     * every instance already in the world in place, so the mobs standing there re-render from the
     * restored sculpt rather than having to be discarded and re-spawned; and the restored row is
     * what persists, so the next {@code start_from} edit inherits the reverted creature.
     */
    private static boolean undoCreature(MinecraftServer server, String id, WishRun run) {
        String wanted = id.trim();
        var previous = com.moddingfromamod.runtime.CreatureHistory.pop(wanted);
        if (previous != null
                && com.moddingfromamod.runtime.CreatureHistory.restoreFolder(wanted, previous)) {
            // The row too, or the registry's scale and health stay ahead of the model.
            com.moddingfromamod.species.SpeciesRegistry.put(server, previous.species());
            com.moddingfromamod.net.ModdedClient.broadcastSpecies();
            return true;
        }
        // ── A CREATION: take away the creatures THIS WISH spawned ──────────────
        //
        // This discarded every `WISH_MOB` of the species — and there are none: the
        // bridge swaps each for a vanilla carrier, display parts and hit proxies. So an undo
        // removed nothing and returned false, and the creature went on standing there.
        //
        // PER WISH, like every other reversal here: the carriers this run recorded spawning
        // (WishRun.creaturesSpawned), each removed with its parts and proxies by the one
        // removal remove_creature uses. A brother's creature of the same kind, or this child's
        // next wish's, is not this wish's to take. The DESIGN is kept, as remove_creature keeps
        // it: he stopped the wish, he did not ask for the idea to be forgotten.
        if (run != null) {
            int removed = 0;
            for (var spawned : run.creaturesSpawned()) {
                if (spawned.carrier() == null || !wanted.equalsIgnoreCase(spawned.species())) {
                    continue;
                }
                for (ServerLevel level : server.getAllLevels()) {
                    var e = level.getEntity(spawned.carrier());
                    if (e instanceof net.minecraft.world.entity.LivingEntity carrier
                            && com.moddingfromamod.mob.Creatures.remove(level, carrier)) {
                        removed++;
                    }
                }
            }
            LOG.info("undo: wish {} took away {} '{}' creature(s) it spawned", run.shortId(),
                    removed, wanted);
            // TRUE even at zero: nothing this wish put in the world is standing any more, which is
            // what undoing it means. The ones it spawned may already have died.
            return true;
        }
        // No run to say which: every creature of the species (a direct caller, a test).
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            removed += com.moddingfromamod.agent.tools.RemoveCreatureTool.removeRigs(level, wanted);
        }
        return removed > 0;
    }

    /**
     * Put a mob back the way it was before <b>this wish</b> touched it.
     *
     * <p>Not a deletion, unlike every other reversal here. The animal existed before the wish and
     * has to exist after the undo — what is being reversed is the change, so the inverse is the
     * numbers recorded at the moment they were overwritten.
     *
     * <p>False, honestly, in the two cases where it cannot: nothing was recorded (a mob touched by
     * a build that predates this), or the mob is dead. A child is then told it could not be put
     * back, which is true, rather than being told it was.
     */
    private static boolean undoMob(MinecraftServer server, String handleName, java.util.UUID wishId) {
        var handles = com.moddingfromamod.mob.EntityHandles.global();
        var handle = handles.resolve(handleName);
        if (handle.isEmpty() || wishId == null) {
            return false;
        }
        com.moddingfromamod.mob.MobState was =
                handles.restorePointFor(handleName, wishId.toString());
        if (was == null) {
            return false;
        }
        net.minecraft.world.entity.LivingEntity mob =
                com.moddingfromamod.mob.EntityCapture.findAnywhere(server, handle.get().id());
        if (mob == null) {
            return false;
        }
        return com.moddingfromamod.mob.MobStates.restore(server.getScoreboard(), mob, was);
    }

    private static boolean undoDatapack(MinecraftServer server, String id) {
        String clean = id.trim().toLowerCase(java.util.Locale.ROOT);
        // Off, not deleted — the files stay on disk, so nothing he wished for is actually lost.
        return DatapackReloader.disableAndReload(server, clean);
    }
}
