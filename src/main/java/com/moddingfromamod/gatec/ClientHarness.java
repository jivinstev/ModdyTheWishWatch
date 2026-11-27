package com.moddingfromamod.gatec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;

import com.moddingfromamod.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * <b>The plumbing every real-client test needs, and the guards each one learned the hard way.</b>
 *
 * <p>A Gate C film and an end-to-end wish test are deliberately
 * different shapes — one is many wishes judged on one dimension, the other is one wish judged on
 * every lifecycle join — and neither should absorb the other. But they both create a world, type
 * into the real watch, wait on the agent, photograph, and write a verdict, and they had each
 * reimplemented all of it.
 *
 * <p>That duplication was not cosmetic. Four real bugs were found in this plumbing in a single day
 * and <b>every one was fixed in only one copy</b>. Most seriously, Gate D still had no guard against
 * Minecraft pausing on lost focus — which opens the Game Menu <i>and stops the integrated server
 * ticking</i>, so an unattended client silently stops making progress. In Gate C that produced four
 * screenshots of the pause menu and a <b>PASS</b>; in Gate D it would stall the agent wait and
 * report a meaningless timeout.
 *
 * <p>So the guards live here, once:
 *
 * <ol>
 *   <li><b>Never pause.</b> {@code pauseOnLostFocus = false}, or the server stops ticking.</li>
 *   <li><b>{@code hideGui} must be set frames BEFORE the shutter.</b> {@code takeScreenshot} grabs
 *       the framebuffer that has already been rendered, so setting it on the capture tick cannot
 *       affect the frame captured.</li>
 *   <li><b>Leave spectator before touching the watch.</b> Minecraft ignores {@code useItem} in
 *       spectator, so a run that posed a creature could never submit its next wish.</li>
 *   <li><b>A screen open at the shutter is fatal.</b> A picture of a menu is not evidence.</li>
 * </ol>
 */
public final class ClientHarness {

    /**
     * THE difficulty every client test world is built on: a real game's default
     * ({@code MODDY_DIFFICULTY}, {@code easy} in {@code the server installer}). It was
     * Peaceful, and Peaceful DELETES a hostile mob on its next tick — so a wished pet enderman, a
     * giant slime and a mini warden all vanished in Gate D while the agent was told only "it died or
     * unloaded", and three tests here had to switch the difficulty themselves to see a
     * zombie at all. A test world that differs from the one a child plays in measures a game nobody
     * plays. A test that needs Peaceful asks for it, by name.
     */
    public static final Difficulty TEST_DIFFICULTY = Difficulty.EASY;

    private ClientHarness() {
    }

    /** Ticks to let the title screen settle before creating a world. */
    public static final int TITLE_SETTLE = 60;
    /** Ticks to let the world settle once the server is ready. */
    public static final int INGAME_SETTLE = 40;

    // ── the empty stage ──────────────────────────────────────────────────────────────────────

    /** Client ticks between two frames of the empty stage that must agree before it is the reference. */
    public static final int STAGE_STILL_TICKS = StageSettle.STILL_TICKS;
    /** Ticks a film waits for the empty stage to settle; past them it ends as "could not run", never judged. */
    public static final int STAGE_SETTLE_MAX = StageSettle.SETTLE_MAX;
    private static final StageSettle STAGE = new StageSettle();

    /**
     * The empty stage as a film's reference frame, once the world has finished drawing it: null until then; call it every
     * tick. The rule is {@link StageSettle}'s (nothing left to build or upload, then two frames
     * {@link #STAGE_STILL_TICKS} apart that agree with no chunk arriving and no section drawn between them); this reads
     * the client for it. On 1.21.10 under llvmpipe the far row of the floor appeared 7 px higher after a reference taken
     * too early, so every body box was its whole strip. {@code grab} takes one frame.
     *
     * <p>A stage that has not settled within {@link #STAGE_SETTLE_MAX} ticks is a fault of the rig, not a finding about
     * the creature: the film ends with {@link #couldNotRun} (client-test.sh exit 2) and the last frame diff, and is never
     * judged against an unsettled reference. It then returns null for good.
     */
    public static int[][] settledStage(Minecraft mc, Path sigDir, int ticks, java.util.function.Supplier<int[][]> grab) {
        var sections = mc.levelRenderer.getSectionRenderDispatcher();
        boolean idle = sections == null || (sections.isQueueEmpty() && sections.getToUpload() == 0);
        switch (STAGE.step(ticks, idle)) {
            case GIVE_UP -> {
                String busy = idle ? "the chunk sections were idle"
                        : "chunk sections still building: " + sections.getCompileQueueSize() + " queued, "
                        + sections.getToUpload() + " to upload";
                String diff = STAGE.lastDiff();
                couldNotRun(sigDir, "the empty stage never settled within " + STAGE_SETTLE_MAX + " client ticks ("
                        + busy + "; last frame diff: " + (diff == null ? "no two frames taken" : diff)
                        + "), so no film was judged against it");
                mc.stop();
                return null;
            }
            case SAMPLE -> {
                int chunks = mc.level == null ? -1 : mc.level.getChunkSource().getLoadedChunksCount();
                int drawn = mc.levelRenderer.countRenderedSections();
                return STAGE.offer(grab.get(), ticks, chunks, drawn);
            }
            default -> {
                return null;
            }
        }
    }

    // ── world ────────────────────────────────────────────────────────────────────────────────

    /**
     * Get past the title screen and start a fresh world. Returns true once the world is being
     * created; call it every tick until then.
     *
     * <p>Sets {@code pauseOnLostFocus = false} on every call rather than once, because it is the
     * single setting whose absence makes an unattended run silently stop.
     */
    public static boolean bootstrapWorld(Minecraft mc, String namePrefix, int ticks) {
        return bootstrapWorld(mc, namePrefix, ticks, false);
    }

    /**
     * @param flat build a SUPERFLAT world.
     *
     *     <p>For any gate that photographs or measures something, terrain is a lottery you did not
     *     ask to enter. A fixed spawn point in a freshly seeded world has landed in open sky, an
     *     ocean, a tree canopy, a cliff edge and the inside of a mountain on consecutive runs — and
     *     every one of those reads as a bug in the thing under test (an empty frame, a black frame,
     *     a seabed) rather than as the ground moving. Carving the terrain away instead does not
     *     work either: {@code /fill} refuses more than 32768 blocks and refuses quietly.
     *
     *     <p>The restaurant gate reached the same conclusion for the same reason — "normal terrain
     *     adds nothing to the gate but does make runs a lottery".
     */
    public static boolean bootstrapWorld(Minecraft mc, String namePrefix, int ticks, boolean flat) {
        return bootstrap(mc, namePrefix, ticks, flat, false);
    }

    /**
     * A superflat world with a real world's depth of ground under it
     * ({@link ClientWorlds#createDeepFlat}) — for a studio that
     * photographs a building which digs down. On the vanilla FLAT preset the ground is four blocks
     * above the void, so such a building is cut off and the camera sees grass.
     */
    public static boolean bootstrapDeepFlatWorld(Minecraft mc, String namePrefix, int ticks) {
        return bootstrap(mc, namePrefix, ticks, true, true);
    }

    private static boolean bootstrap(Minecraft mc, String namePrefix, int ticks, boolean flat,
                                     boolean deep) {
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof AccessibilityOnboardingScreen) {
            mc.options.onboardAccessibility = false;
            mc.options.save();
            mc.setScreen(new TitleScreen());
            return false;
        }
        if (!(mc.screen instanceof TitleScreen) || ticks < TITLE_SETTLE) {
            return false;
        }
        String name = namePrefix + "-" + Long.toHexString(System.nanoTime());
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false,
                ClientHarness.TEST_DIFFICULTY, true, ClientWorlds.rules(), WorldDataConfiguration.DEFAULT);
        if (deep) {
            ClientWorlds.createDeepFlat(mc, name, settings);
        } else {
            ClientWorlds.createFresh(mc, name, settings, flat);
        }
        return true;
    }

    /** Is the integrated server actually up and the player in it? */
    public static boolean inWorld(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        return server != null && server.isReady() && mc.player != null && mc.level != null;
    }

    // ── the watch ────────────────────────────────────────────────────────────────────────────

    /**
     * Put the player back in a state where the watch works. Returns true when it is safe to submit.
     *
     * <p>Spectator is the trap: photographing leaves the player spectating so they neither fall nor
     * shove the creature, and Minecraft then ignores {@code useItem} entirely — so the next wish
     * could never be submitted and a batch run stopped after one creature. The mode change is a
     * server round trip, hence the settle ticks.
     */
    public static boolean readyToWish(Minecraft mc, int ticks) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (ticks == 0 && server != null) {
            server.execute(() -> {
                try {
                    var p = server.getPlayerList().getPlayers().get(0);
                    p.setGameMode(GameType.CREATIVE);
                    p.connection.teleport(p.getX(), 101, p.getZ(), 0.0f, 0.0f);
                } catch (RuntimeException ignored) {
                    // submitWish reports if the watch still will not open
                }
            });
        }
        if (ticks < 10) {
            return false;
        }
        mc.options.hideGui = false;   // the HUD comes back between photographs
        return true;
    }

    /**
     * Type a wish into the real watch GUI and press enter.
     *
     * @return null on success, or why it failed
     */
    public static String submitWish(Minecraft mc, String wish) {
        if (mc.player == null) {
            return "no player";
        }
        // ⚠ THIS IS NOT THE GUI PATH, and saying so is the point. The wish is typed into a
        // DIALOG reached from a button in the watch's container, which is server round trips
        // and cannot be driven inside one tick — so this submits through the same
        // `WishService.submit` the box's Submit calls, and skips the widgets. That is honest for
        // a test whose subject is a creature or a picture, and it would be a lie for one whose
        // subject is the watch: `VanillaWatchClientTest` and `VanillaWatchFilmClientTest` own
        // proving the real buttons work and drive them directly, and `WishDriver` is the one
        // harness that types through the real widgets.
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return "no integrated server";
        }
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                com.moddingfromamod.wish.WishService.submit(players.get(0), wish,
                        com.moddingfromamod.wish.WishSource.TYPED);
            }
        });
        return null;
    }

    // ── photography ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>Quiet the particles while photographing.</b>
     *
     * <p>Spikor breathes fire, and every one of his four photographs came back as a cloud of flame
     * with a creature somewhere inside it. Several corpus creatures have particle abilities, so
     * without this the eval cannot see a good part of what it is meant to judge.
     *
     * <p>This is a CLIENT RENDERING setting, not a change to the creature: the mob keeps its
     * ability, keeps using it, and is not touched in any way — the camera simply stops drawing the
     * cloud, the same principle as hiding the HUD. Returns what to restore afterwards.
     */
    public static net.minecraft.server.level.ParticleStatus quietParticles(Minecraft mc) {
        var previous = mc.options.particles().get();
        mc.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL);
        clearParticles(mc);
        return previous;
    }

    /**
     * <b>Wipe the particles already on screen.</b>
     *
     * <p>Setting {@code MINIMAL} only slows new ones; it does nothing about the cloud already
     * hanging in the air, and a creature that emits continuously refills it within a tick. A
     * "clean" Spikor photograph came back as a solid black smoke cloud with a creature somewhere
     * inside it — indistinguishable from the un-suppressed pass it was supposed to contrast with.
     *
     * <p>So this is called every tick of the clean pass, not once: clearing at the start and
     * hoping is exactly what failed.
     */
    public static void clearParticles(Minecraft mc) {
        try {
            // ParticleEngine.clearParticles() is private, but setLevel() calls it and otherwise
            // only assigns the field and clears emitters -- so passing the CURRENT level is a pure
            // particle wipe through a public method, with no reflection and no side effect.
            mc.particleEngine.setLevel(mc.level);
        } catch (RuntimeException ignored) {
            // a photograph with particles is worth more than no photograph
        }
    }

    public static void restoreParticles(Minecraft mc,
                                        net.minecraft.server.level.ParticleStatus previous) {
        mc.options.particles().set(previous);
    }

    /** Camera stops around the subject, in degrees. Four sides, ~1.5s in total. */
    public static final int[] BEARINGS = {0, 90, 180, 270};

    /**
     * Aim the camera at an entity, on the CLIENT, from the live entity.
     *
     * <p>Computing this on the server and teleporting six ticks before the shutter left 31 of 80
     * subjects off-centre — and every offset had the SAME SIGN, which rules out a wandering mob and
     * points at the camera: the orbit always advances the same way, so the client was still
     * interpolating its rotation when the frame was captured. Doing it client-side from the entity
     * actually being rendered, then writing previous position and rotation to match current, leaves
     * nothing to interpolate.
     */
    public static boolean aimAt(Minecraft mc, UUID subject, float yaw) {
        if (mc.player == null || mc.level == null || subject == null) {
            return false;
        }
        net.minecraft.world.entity.Entity mob = null;
        for (var e : mc.level.entitiesForRendering()) {
            if (subject.equals(e.getUUID())) {
                mob = e;
                break;
            }
        }
        if (mob == null) {
            return false;
        }
        double height = Math.max(0.6, mob.getBbHeight());
        double aimY = mob.getY() + height * 0.55;
        // DERIVED, not tuned. Minecraft's default vertical FOV is 70 degrees, so the visible
        // height at distance d is 2*d*tan(35) ~= 1.4d. To make the creature cover a fraction F of
        // the frame: d = h / (1.4 * F). At F = 0.55 that is h * 1.3.
        //
        // The old `h * 2.4 + 2` put a 6.5-block Spikor 17.6 blocks away, covering 26% of frame --
        // and, worse, the share DRIFTED with size (11% for a small creature, 27% for a huge one),
        // so photographs of different creatures were not comparable. This holds ~55% for anything
        // over two blocks.
        double dist = Math.max(2.2, Math.min(26.0, height * 1.3));
        double rad = Math.toRadians(yaw);

        double camX = mob.getX() + dist * Math.sin(rad);
        double camZ = mob.getZ() - dist * Math.cos(rad);
        double camY = aimY + height * 0.25 - mc.player.getEyeHeight();
        float pitch = (float) -Math.toDegrees(
                Math.atan2(aimY - (camY + mc.player.getEyeHeight()), dist));

        mc.player.setPos(camX, camY, camZ);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.xOld = camX;
        mc.player.yOld = camY;
        mc.player.zOld = camZ;
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        return true;
    }

    /**
     * Take the photograph.
     *
     * @return null on success, or why the frame should not be trusted
     */
    public static String snap(Minecraft mc, Path sigDir, String name) {
        return snap(mc, sigDir, name, false);
    }

    /**
     * <b>{@code keepView} photographs WHAT IS ON SCREEN, including an open menu and the HUD.</b>
     *
     * <p>Both cameras in this repo were built for world photography and each dismisses any open
     * screen before the shutter — {@code PhotoClient} because singleplayer auto-pauses when the
     * window loses focus, and this one because for every subject so far a menu in frame meant the
     * photograph was ruined. That is right for a creature and exactly wrong for a GUI, and it
     * failed silently rather than loudly: the vanilla-watch gate asked for a picture called
     * {@code watch-panel}, this method closed the panel, and the file it wrote is a picture of the
     * SKY (centre RGB 163,185,213). The run was green, the file existed, and nothing anywhere said
     * the subject had been removed a frame before the capture — the same shape as every entry in
     * the camera notes.
     *
     * <p>So the flag is explicit and defaults to the old behaviour: a caller that wants the world
     * still gets a guaranteed-clean frame and the "a screen was open" complaint, and a caller whose
     * SUBJECT is the screen says so. It touches neither {@code screen} nor {@code hideGui}, because
     * for a GUI shot the HUD is part of the subject too — the hotbar holding Moddy, the boss bar
     * counting a wish down.
     *
     * @param keepView shoot the frame as the player sees it, dismissing nothing
     */
    public static String snap(Minecraft mc, Path sigDir, String name, boolean keepView) {
        String problem = keepView || mc.screen == null ? null
                : "a screen (" + mc.screen.getClass().getSimpleName() + ") was open at the shutter "
                        + "— the photograph is of a menu";
        try {
            if (!keepView) {
                mc.setScreen(null);
                mc.options.hideGui = true;   // backstop only; must already be set, see class javadoc
            }
            var img = Screenshots.take(mc.getMainRenderTarget());
            try {
                Files.createDirectories(sigDir);
                img.writeToFile(sigDir.resolve(name));
            } finally {
                img.close();
            }
        } catch (Exception e) {
            return "screenshot " + name + " failed: " + e;
        }
        return problem;
    }

    // ── verdict ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Take a pack OUT of the repository's selection, the way the GAME reads it.</b>
     *
     * <p>{@code Minecraft.reloadResourcePacks()} opens the REPOSITORY's own selection, not
     * {@code mc.options.resourcePacks}, so this writes both. It survives (the per-creature
     * pack rig was deleted) because a dev client's options can still carry a stale
     * {@code mfam-vanilla-rig} entry from an old run, and a selected per-creature pack rewrote the
     * carrier's model table WHOLE — no entry for the atom band, Moddy's watch or any wish icon, so
     * a display carrying an atom id resolved that pack's LAST creature group instead (measured,
     * the atom rig's design notes: all 62 of Spikor's atoms filmed as a scatter of stretched
     * slabs, with every control green because the model was neither the plain carrier nor the
     * missing one). Deselecting it keeps a harness measuring what production delivers.
     */
    public static String excludePack(net.minecraft.client.Minecraft mc, String packId) {
        var repo = mc.getResourcePackRepository();
        repo.reload();
        java.util.List<String> want = new java.util.ArrayList<>(repo.getSelectedIds());
        want.remove(packId);
        repo.setSelected(want);
        mc.options.resourcePacks.clear();
        mc.options.resourcePacks.addAll(want);
        return "packs available: " + repo.getAvailableIds() + " selected: " + repo.getSelectedIds()
                + " (" + packId + " deliberately NOT selected — the atom rig delivers no "
                + "per-creature pack, and a selected one shadows the carrier table)";
    }

    /**
     * The film could not run: a fault of the rig (its stage, its setup), not a verdict on what it films. The result reads
     * {@code NORUN}, which tools/client-test.sh exits 2 on and tools/gate-c-rig.sh reports as COULD NOT RUN.
     */
    public static void couldNotRun(Path sigDir, String message) {
        System.out.println("CLIENT_TEST: RESULT=NORUN " + message.replace('\n', ' '));
        try {
            Files.createDirectories(sigDir);
            Files.writeString(sigDir.resolve("result"), "NORUN — " + message + "\n");
        } catch (Exception e) {
            System.out.println("could not write the verdict: " + e);
        }
    }

    /** Write PASS/FAIL where the launcher script looks for it. */
    public static void verdict(Path sigDir, boolean pass, String message) {
        // The same verdict on stdout, in the line a log-reading gate (MMVU's Gate C carrier) looks for, so a run
        // can be judged from its log alone when the signal directory is not to hand.
        System.out.println("CLIENT_TEST: RESULT=" + (pass ? "PASS" : "FAIL") + " " + message.replace('\n', ' '));
        try {
            Files.createDirectories(sigDir);
            Files.writeString(sigDir.resolve("result"),
                    (pass ? "PASS" : "FAIL") + " — " + message + "\n");
        } catch (Exception e) {
            System.out.println("could not write the verdict: " + e);
        }
    }
}
