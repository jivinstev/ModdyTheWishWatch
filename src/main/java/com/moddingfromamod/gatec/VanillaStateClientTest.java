package com.moddingfromamod.gatec;

import net.minecraft.world.entity.EntitySpawnReason;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigCarrier;
import com.moddingfromamod.vanilla.RigTransform;
import com.moddingfromamod.vanilla.SpikeFixture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>Gate C, ladder tier 1 — the state effects that are predicted FREE, actually looked at.</b>
 *
 * <p>{@code docs/VANILLA_RIG_PARITY.md} §5 predicts, from 1.21.1 bytecode, that burning, the name
 * tag and potion swirls all still work on the vanilla rig even though the carrier is invisible —
 * because vanilla draws each of them in WORLD space at the entity's position rather than on its
 * model. The two lines that decide it sit next to each other in {@code EntityRenderDispatcher} and
 * disagree:
 *
 * <pre>
 *   if (entity.displayFireAnimation()) { renderFlame(...); }          // no invisibility guard
 *   if (... &amp;&amp; !entity.isInvisible()) { renderShadow(...); }          // explicit guard
 * </pre>
 *
 * <p>So "free" is a PREDICTION FROM READING, and this run is the first time anything has looked. A
 * prediction from bytecode is an instrument like any other, and §9.6 is a list of instruments that
 * lied in the reassuring direction.
 *
 * <h2>Everything here is a DIFFERENCE against the same creature in its plain state</h2>
 *
 * <p>The trap this design exists to avoid: "there are orange pixels near arm B" is satisfied by a
 * creature that happens to be orange. Every number below is
 * {@code shot(state) - shot(PLAIN)} inside a box, so the creature itself cancels and only what the
 * state ADDED is counted. The plain state is measured against itself first, and a non-zero there is
 * a broken instrument rather than a finding.
 *
 * <h2>Why both arms in one frame</h2>
 *
 * <p>A state effect is a yes/no per arm, not a pixel-parity number, so the side-by-side layout the
 * walk film uses is right here too — and it means arm A is a live control for every measurement:
 * if the fire count is zero for BOTH arms, the test failed to set anything on fire, which is a
 * completely different fix from "the rig cannot show fire".
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaStateClientTest {

    private VanillaStateClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanillastate".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillastate"));

    private static final String CASE = System.getProperty(P + "cases", "thornshell");

    /** Ticks to let a state settle and reach the client before the shutter. */
    private static final int SETTLE_TICKS =
            Integer.parseInt(System.getProperty(P + "settleticks", "30"));

    /**
     * Frames to accumulate for the swirl count. Particles are emitted at 1-in-4 (visible carrier)
     * and 1-in-15 (invisible one), so a single frame is a coin toss and the whole point is the
     * RATIO between the arms — that needs a sample, not a shutter.
     */
    /** Ticks after undoing the previous state before the paired baseline is taken. */
    private static final int RESET_TICKS =
            Integer.parseInt(System.getProperty(P + "resetticks", "30"));

    private static final int SWIRL_FRAMES =
            Integer.parseInt(System.getProperty(P + "swirlframes", "200"));

    private enum Phase { WAIT_TITLE, WAIT_INGAME, EMPTY, PREPARE, RELOAD, SPAWN, SETTLE, STATES,
                         DONE }

    /** The states, in the order they are applied. PLAIN is the baseline every other one subtracts. */
    private enum State { PLAIN, BURNING, NAMED, SWIRLS, SHADOW_A, SHADOW_Z, SHADOW_B, GLOW,
        GLOW_COLOUR, NIGHT, EMISSIVE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    private static boolean reloadDone;
    private static final List<String> NOTES = new ArrayList<>();
    private static String failLater;

    // Same stage as the walk film, so the two runs are directly comparable by eye.
    private static final double CAM_X = 8.5;
    /**
     * 🔴 <b>RAISED from 97 and TILTED from 6 degrees for tier 2, and the reason is trigonometry
     * rather than taste.</b> A shadow is a disc on the FLOOR, so what it is worth in pixels is the
     * grazing angle: from y=97 at a 17-block standoff the ground sits 6.7 degrees below horizontal,
     * and a 1-block disc foreshortens to 20 x 2 px — a measurement whose whole signal is smaller
     * than this instrument's own floor. From y=100 it is 16.4 degrees, and the same disc is
     * 20 x 6 px. The creature barely changes size either way (104 px tall against 100), because
     * raising the camera and tilting it back down almost cancel.
     *
     * <p>⚠ The tier-1 numbers in the ladder doc were taken at 97/6 and are NOT comparable to a run
     * at 100/12 pixel-for-pixel. Their conclusions are (every row is a difference against a
     * baseline in the same pose), but do not diff the counts across the change.
     */
    private static final double CAM_Y = 100.0;
    private static final float CAM_PITCH = 12f;
    private static final double CAM_Z = 8.5;
    private static final double STAGE_Z =
            CAM_Z + Double.parseDouble(System.getProperty(P + "standoff", "17.0"));
    private static final double FOOT_Y = 95.0;
    /**
     * The floor the shadow lands on. {@code EntityRenderDispatcher.renderShadow} scans blocks from
     * {@code floor(y - min(strength/0.5, radius))} up to {@code floor(y)} — so with a 0.5 radius
     * the ground has to be within HALF A BLOCK of the creature's feet or there is simply nothing
     * to draw on. Every subject in this harness has {@code setNoGravity(true)} and floats at
     * {@code FOOT_Y}, so the platform is placed rather than assumed.
     */
    private static final int GROUND_Y = (int) FOOT_Y - 1;
    private static final double LANE = Double.parseDouble(System.getProperty(P + "lane", "10.0"));
    private static final double LANE_A = CAM_X + LANE;
    private static final double LANE_B = CAM_X - LANE;

    private static String key;
    private static Map<String, BedrockAnimation> clips = Map.of();
    private static UUID mobA;
    private static UUID mobZ;
    private static DisplayRig rig;

    private static int[][] shotEmpty;
    private static int[][] shotPlain;
    private static int[][] shotBefore;
    private static boolean[][] maskA;
    private static boolean[][] maskB;

    /** Per-arm creature boxes, derived from EMPTY vs PLAIN: {x0,y0,x1,y1}. */
    private static int[] boxA;
    private static int[] boxB;
    private static int[] boxZ;

    private static boolean baselined;
    private static boolean groundLaid;
    private static long filmStart;
    private static int stateIndex;
    private static int swirlA;
    private static int swirlB;
    private static int swirlFramesTaken;
    private static final Map<String, String> RESULTS = new LinkedHashMap<>();

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || phase == Phase.DONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            switch (phase) {
                case WAIT_TITLE -> {
                    if (ticks > ClientHarness.TITLE_SETTLE) {
                        go(Phase.WAIT_INGAME);
                    }
                }
                case WAIT_INGAME -> {
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-state", ticks, true)) {
                        go(Phase.EMPTY);
                    }
                }
                case EMPTY -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        hold(mc);
                        pinTheSky(mc);
                        // The empty stage, BEFORE anything is spawned. This is what makes the
                        // creature boxes derivable rather than hard-coded -- a hard-coded box that
                        // misses the creature reports zero for every state and reads as "the rig
                        // cannot show fire".
                        ground(mc);
                        if (ticks > ClientHarness.INGAME_SETTLE + 10) {
                            go(Phase.PREPARE);
                        }
                    }
                }
                case PREPARE -> prepare(mc);
                case RELOAD -> {
                    // 🔴 `reloadResourcePacks().thenRun(...)` FIRING IS NOT THE CLIENT BEING BACK.
                    // The Mojang loading overlay is still drawn over the world and fades out on
                    // its own clock, and while it is up it TINTS THE WHOLE FRAME pink. Measured:
                    // the plain baseline came back as a pink Mojang splash with one creature
                    // half-drawn behind it, mean rgb (208,122,144) against the real (166,196,246),
                    // so every difference taken against it was ~100% of the box. The overlay's
                    // absence is the real condition, and it is one call.
                    if (reloadDone && mc.getOverlay() == null && ticks > 20) {
                        go(Phase.SPAWN);
                    }
                }
                case SPAWN -> {
                    hold(mc);
                    // 🔴 THE EMPTY STAGE IS GRABBED HERE, not before the resource reload -- the
                    // Mojang overlay is up during that, and a pink reference frame makes every
                    // box the whole screen. Post-reload, sky pinned, nothing spawned: this one
                    // frame is the reference for every measurement below, so there is no colour
                    // rule anywhere in this test and nothing to tune.
                    if (ticks > 10) {
                        if (shotEmpty == null) {
                            shotEmpty = grab(mc, "0-empty");
                        }
                        spawn(mc);
                    }
                }
                case SETTLE -> {
                    hold(mc);
                    if (ticks > SETTLE_TICKS) {
                        go(Phase.STATES);
                    }
                }
                case STATES -> states(mc);
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    // ------------------------------------------------------------------------------- the setup

    private static void prepare(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        key = "vstate_" + RigCarrier.safe(CASE);
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        try {
            CreatureFiles.write(key, parts, "{}");
            clips = ModelRepository.animations(key);
        } catch (Exception e) {
            finish(false, "creature write failed: " + e);
            return;
        }
        // (the pack-rig arm's per-creature pack write + select was deleted by an earlier change.) DEselect, as
        // every other atom-rig harness does: a stale selected rig pack shadows the carrier table
        // and every atom resolves its last creature group (the atom rig's design notes).
        NOTES.add(ClientHarness.excludePack(mc, "file/mfam-vanilla-rig"));
        mc.reloadResourcePacks().thenRun(() -> reloadDone = true);
        go(Phase.RELOAD);
    }

    private static void spawn(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        var server = mc.getSingleplayerServer();
        if (server == null) {
            finish(false, "no integrated server");
            return;
        }
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        double scale = SpikeFixture.scale(CASE);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            ServerLevel level = p.level();
            SpeciesRegistry.put(server, Species.fromJson(JsonParser.parseString(
                    "{\"id\":\"" + key + "\",\"modelKey\":\"" + key + "\",\"health\":40,"
                            + "\"speed\":0.0,\"scale\":" + scale + "}").getAsJsonObject()));

            // ⚰️ ARM A WAS A wish_mob DRAWN BY THE MOD RENDERER, and since an earlier change deleted that
            // renderer VanillaRigBridge cancels every wish_mob the moment it joins. So armA() was
            // null, `apply` returned on `if (a == null || b == null)` for EVERY state, and the rig
            // was never set on fire, named, glowed or potioned: "arm B 0 px changed" on every row,
            // reported as the rig's failure (measured identically on origin/main, 2026-09-23).
            // Arm A's JOB in every row is "a creature drawn the ordinary way, so a zero here means
            // the harness never applied the state" -- which any visible vanilla mob does natively.
            // An iron golem: large enough to frame like a creature, and stable with AI off.
            Mob mob = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
            if (mob == null) {
                finish(false, "could not create the control mob");
                return;
            }
            mob.snapTo(LANE_A, FOOT_Y, STAGE_Z, 270f, 0f);
            mob.setYBodyRot(270f);
            mob.setYHeadRot(270f);
            mob.setNoGravity(true);
            mob.setNoAi(true);
            mob.setSilent(true);
            mob.setPersistenceRequired();
            level.addFreshEntity(mob);
            mobA = mob.getUUID();

            var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
            rig = DisplayRig.spawn(level, new Vec3(LANE_B, FOOT_Y, STAGE_Z), 270f, parts, scale,
                    CreatureAnatomy.widthBlocks(extents) * scale,
                    CreatureAnatomy.heightBlocks(extents) * scale, null);
            rig.base().setNoGravity(true);
            rig.base().setNoAi(true);
            rig.base().setSilent(true);
            for (var d : rig.all()) {
                d.setNoGravity(true);
            }
            // 🔴 BOTH must be invulnerable BEFORE anything is set on fire. Fire damage would kill
            // arm A over twenty seconds, and a creature that dies mid-run changes its own picture
            // (the death flop, then nothing) -- which would read as the fire test failing.
            mob.setInvulnerable(true);
            rig.base().setInvulnerable(true);

            // 🔴 A VANILLA MOB IN THE SAME FRAME, and it is the control the burning row needs.
            // "Both arms agree" says the two PATHS match; it says nothing about whether what
            // they agree on is what Minecraft does. A plain zombie, on fire, one camera, one
            // tick -- that is the only thing that can answer "is this really vanilla's fire".
            // 🔴 A COW, NOT A ZOMBIE, and the reason is worth keeping: the harness builds its
            // world on PEACEFUL, and `Mob.checkDespawn` discards anything whose
            // `shouldDespawnInPeaceful()` is true BEFORE it consults `isPersistenceRequired()` --
            // so the zombie control was created, logged its hitbox, and was gone by the shutter,
            // leaving an empty patch of sky that the sky filter then reported as a 137x40 "body".
            // A cow also happens to be the better control: 0.9 x 1.4 is far closer to this
            // creature's width-to-height than a zombie's 0.6 x 1.95, so the flame comparison is
            // between two similar shapes rather than two opposite ones.
            var z = net.minecraft.world.entity.EntityType.COW.create(level, EntitySpawnReason.COMMAND);
            if (z != null) {
                z.snapTo(CAM_X, FOOT_Y, STAGE_Z, 270f, 0f);
                z.setYBodyRot(270f);
                z.setNoGravity(true);
                z.setNoAi(true);
                z.setSilent(true);
                z.setInvulnerable(true);
                z.setPersistenceRequired();
                level.addFreshEntity(z);
                mobZ = z.getUUID();
                // 🔴 PRINT THE CARRIER'S HITBOX TOO. Everything vanilla draws AROUND a mob is
                // sized or positioned off its bounding box -- the flame's width, the name plate's
                // height, the shadow's radius -- so a carrier whose box differs from the wish
                // mob's shows up in every one of those rows at once, looking like three separate
                // small defects. Measuring it here names the single cause.
                NOTES.add(String.format("hitboxes: control golem %.2f x %.2f | rig carrier %.2f x %.2f "
                                + "(%+.1f%% wide, %+.1f%% tall) | vanilla cow %.2f x %.2f",
                        mob.getBbWidth(), mob.getBbHeight(),
                        rig.base().getBbWidth(), rig.base().getBbHeight(),
                        100.0 * (rig.base().getBbWidth() / mob.getBbWidth() - 1),
                        100.0 * (rig.base().getBbHeight() / mob.getBbHeight() - 1),
                        z.getBbWidth(), z.getBbHeight()));
                // The SCALE attribute, printed because the shadow radius is a function of it and
                // nothing else in the report would explain a factor of seven.
                NOTES.add(String.format("shadow radii: mod path %.2f (its 0.5 base x scale "
                                + "%.3f) | rig %.2f (half its own %.2f width) | vanilla cow %.2f "
                                + "(authored 0.7 x scale %.2f)",
                        DisplayRig.MOD_PATH_SHADOW_RADIUS * mob.getScale(), mob.getScale(),
                        DisplayRig.shadowRadiusFor(rig.base()), rig.base().getBbWidth(),
                        0.7f * z.getScale(), z.getScale()));
            }
            NOTES.add("arm A = 1 iron golem (the ordinary-renderer control) at x=" + LANE_A + " | arm B = " + rig.displays().size()
                    + " item_display + 1 "
                    + (rig.choice() == null ? "?" : rig.choice().id()) + " at x=" + LANE_B);
        });
        go(Phase.SETTLE);
    }

    /**
     * <b>Stop the background moving.</b> Noon, no clock, no weather, no clouds. Every number in
     * this run is a pixel difference, and a drifting sky is a difference — the first run measured
     * seventeen million of them.
     */
    private static void ground(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || groundLaid) {
            return;
        }
        groundLaid = true;
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                groundLaid = false;
                return;
            }
            ServerLevel level = p.level();
            // WHITE, because a shadow is a subtraction and the darker the floor the less there is
            // to subtract. On grass this measurement would be a handful of pixels.
            var floor = net.minecraft.world.level.block.Blocks.WHITE_CONCRETE.defaultBlockState();
            int n = 0;
            for (int x = (int) CAM_X - 16; x <= (int) CAM_X + 16; x++) {
                for (int z = (int) STAGE_Z - 8; z <= (int) STAGE_Z + 8; z++) {
                    level.setBlock(new net.minecraft.core.BlockPos(x, GROUND_Y, z), floor, 3);
                    n++;
                }
            }
            NOTES.add("stage floor: " + n + " white concrete at y=" + GROUND_Y
                    + " (feet at " + FOOT_Y + ")");
        });
    }

    private static void pinTheSky(Minecraft mc) {
        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (ServerLevel level : server.getAllLevels()) {
                level.setDayTime(6000L);
                level.setWeatherParameters(6000, 0, false, false);
            }
            server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)
                    .set(false, server);
            server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE)
                    .set(false, server);
        });
    }

    // ------------------------------------------------------------------------------ the states

    private static void states(Minecraft mc) {
        hold(mc);
        // the SHIPPED mirror, every tick, as VanillaRigAnimator.driveOne calls it. This rig
        // is deliberately not driven (a clip would move it between baseline and shutter), so the
        // one line of the driver this take needs is called here instead. Nothing in this class
        // writes the displays' glow any more: the glow rows glow the CARRIER, as a wish does.
        var mirrorServer = mc.getSingleplayerServer();
        if (mirrorServer != null && rig != null) {
            mirrorServer.execute(() -> rig.mirrorGlow(rig.base()));
        }
        State state = State.values()[stateIndex];
        if (!fired) {
            fired = true;
            // 🔴 UNDO EVERYTHING FIRST. The first run measured the name tag against a baseline
            // that was STILL ON FIRE -- BURNING sets 600 ticks and NAMED cleared it as part of
            // applying itself, so the "name tag" number was really the flames going out, and arm
            // B scored HIGHER than arm A on a row where arm B shows nothing at all. Each state
            // now starts from the same plain creature.
            reset(mc, state);
            return;
        }
        if (!baselined) {
            if (ticks <= RESET_TICKS) {
                return;
            }
            baselined = true;
            ticks = 0;
            // 🔴 A PAIRED BASELINE, taken NOW rather than once at the start. The first run diffed
            // every state against one PLAIN shot taken ~40 seconds earlier and got a box covering
            // the WHOLE half-screen -- the sky itself had drifted, so "changed" meant "the weather
            // moved" and the swirl count came out at 17 MILLION pixels. Clouds and the clock are
            // pinned as well (see pinTheSky), but a baseline seconds old is the real fix: it makes
            // the measurement immune to anything slow.
            shotBefore = grab(mc, "before-" + state.name().toLowerCase(java.util.Locale.ROOT));
            filmStart = System.currentTimeMillis();
            apply(mc, state);
            if (isShadow(state)) {
                mc.options.entityShadows().set(true);
            }
            return;
        }
        if (ticks <= SETTLE_TICKS) {
            return;
        }
        if (state == State.SWIRLS) {
            // A SAMPLE, not a shutter, and counted by COLOUR rather than by difference: a particle
            // is a non-sky pixel that is not part of the creature. That is drift-proof by
            // construction, where a difference against any baseline is not.
            int[][] now = grab(mc, null);
            swirlA += loose(now, pad(boxA, 24, 24), maskA);
            swirlB += loose(now, pad(boxB, 24, 24), maskB);
            swirlFramesTaken++;
            if (swirlFramesTaken < SWIRL_FRAMES) {
                return;
            }
            grab(mc, String.valueOf(stateIndex) + "-swirls");
            writeFilmWindow("swirls");
            record(state);
            advance();
            return;
        }
        int[][] shot = grab(mc, stateIndex + "-" + state.name().toLowerCase(java.util.Locale.ROOT));
        if (state == State.PLAIN) {
            shotPlain = shot;
            // 🔴 THE BOX COMES FROM COLOUR, NOT FROM A DIFF. Deriving it by diffing against an
            // empty stage looked cleaner and gave (0,0)-(426,479): everything that had changed
            // since, including the sky. A creature is the only non-sky thing above the horizon,
            // and that is a statement about THIS frame with nothing to drift.
            int w = shot[0].length;
            boxA = box(shotEmpty, shot, 0, (int) (w * 0.40));
            boxZ = box(shotEmpty, shot, (int) (w * 0.40), (int) (w * 0.60));
            boxB = box(shotEmpty, shot, (int) (w * 0.60), w);
            if (boxA == null || boxB == null) {
                finish(false, "one of the creatures never drew anything: A=" + java.util.Arrays
                        .toString(boxA) + " B=" + java.util.Arrays.toString(boxB));
                return;
            }
            // 🔴 THE LANES MUST BE FAR ENOUGH APART THAT A FLAME DOES NOT CROSS A SPLIT. At
            // lane 4.5 the creature's own edge landed one pixel past the column boundary, so its
            // overflow WAS the control's "body" -- the cow measured 137 px wide (the entire
            // centre band) and 67 px tall for an animal that is 19 x 30 px at this distance. A
            // box that is exactly its own search range is never a body; it is the range.
            if (boxZ != null && boxZ[2] - boxZ[0] > (int) (shot[0].length * 0.19)) {
                finish(false, "the control's box (" + fmt(boxZ) + ") fills its whole column "
                        + "range, so it is the two creatures spilling across the split rather "
                        + "than the cow. Widen the lanes.");
                return;
            }
            if (boxA[3] - boxA[1] > shot.length / 2 || boxB[3] - boxB[1] > shot.length / 2) {
                finish(false, "a creature box is half the screen tall (A=" + fmt(boxA) + " B="
                        + fmt(boxB) + ") -- the empty-stage reference is not empty, or the two "
                        + "frames disagree wholesale. Compare their mean colours before anything "
                        + "else: " + mean(shotEmpty) + " vs " + mean(shot));
                return;
            }
            maskA = mask(shot, pad(boxA, 24, 24));
            maskB = mask(shot, pad(boxB, 24, 24));
            NOTES.add("stage: arm A box " + fmt(boxA) + ", cow box " + fmt(boxZ)
                    + ", arm B box " + fmt(boxB));
            // THE INSTRUMENT'S OWN FLOOR, measured the way every real row is measured: two frames
            // of the same unchanged thing, seconds apart.
            RESULTS.put("floor", "plain-vs-plain " + changed(shotBefore, shot, pad(boxA, 30, 30))
                    + " px (A) / " + changed(shotBefore, shot, pad(boxB, 30, 30)) + " px (B)"
                    + "; mean rgb before " + mean(shotBefore) + " after " + mean(shot));
        } else {
            record(state, shot);
        }
        writeFilmWindow(state.name().toLowerCase(java.util.Locale.ROOT));
        advance();
    }

    private static void advance() {
        stateIndex++;
        if (stateIndex >= State.values().length) {
            score();
            return;
        }
        ticks = 0;
        fired = false;
        baselined = false;
    }

    /**
     * Put both creatures back to plain. Called before every state's baseline.
     *
     * <p>⚠ It takes the state it is resetting FOR, because two rows need a non-plain baseline:
     * NIGHT measures the difference between day and night, so its baseline is DAY; EMISSIVE
     * measures a bone brightening in the dark, so its baseline must already BE night, or the row
     * would report the darkening and the brightening added together and call it emissive.
     */
    private static void reset(Minecraft mc, State next) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        // Client-side state, so outside server.execute. Shadows are a global client option and
        // that is exactly why they make a clean A/B: one switch turns off arm A's, the cow's and
        // the rig's at once, so the SHADOW row is the only thing that differs between its frames.
        mc.options.entityShadows().set(!isShadow(next));
        server.execute(() -> {
            Mob a = armA(server);
            Mob b = rig == null ? null : rig.base();
            for (Mob m : new Mob[] {a, b, byId(server, mobZ)}) {
                if (m == null) {
                    continue;
                }
                m.clearFire();
                m.setCustomName(null);
                m.setCustomNameVisible(false);
                m.removeEffect(MobEffects.STRENGTH);
            }
            for (Mob m : new Mob[] {a, b, byId(server, mobZ)}) {
                if (m != null) {
                    m.setGlowingTag(false);
                }
            }
            if (b != null) {
                var board = server.getScoreboard();
                var team = board.getPlayersTeam(b.getScoreboardName());
                if (team != null) {
                    board.removePlayerFromTeam(b.getScoreboardName(), team);
                }
            }
            if (rig != null) {
                rig.setName(null);
                // NO rig.setGlowing(false): the carrier's tag went off above, and the
                // mirror clearing the displays is what the NIGHT row checks.
                rig.setShadow(0f, 0f);
                // Back to the carrier's own light -- undoes EMISSIVE, and is also the only thing
                // that makes NIGHT's baseline honest.
                rig.clearEmissive();
            }
            boolean night = next == State.EMISSIVE;
            for (ServerLevel level : server.getAllLevels()) {
                level.setDayTime(night ? 18000L : 6000L);
            }
        });
    }

    private static void apply(Minecraft mc, State state) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            Mob a = armA(server);
            Mob b = rig == null ? null : rig.base();
            if (a == null || b == null) {
                return;
            }
            switch (state) {
                case PLAIN -> { }
                case BURNING -> {
                    a.setRemainingFireTicks(600);
                    b.setRemainingFireTicks(600);
                    Mob z = byId(server, mobZ);
                    if (z != null) {
                        z.setRemainingFireTicks(600);
                    }
                }
                case NAMED -> {
                    // Arm A is a visible mob, so vanilla's own name tag is the whole mechanism.
                    a.setCustomName(Component.literal("Spikor"));
                    a.setCustomNameVisible(true);
                    // Arm B cannot use it -- LivingEntityRenderer.shouldShowName ends
                    // `&& !isInvisibleTo(player)` and the carrier is invisible -- so the rig hangs
                    // its own text_display. The carrier is still named, because a name is state
                    // and something else may read it; it just renders nothing.
                    b.setCustomName(Component.literal("Spikor"));
                    b.setCustomNameVisible(true);
                    if (rig != null) {
                        rig.setName(Component.literal("Spikor"));
                    }
                    // The COW gets one too, and it is the control: a plate that matches vanilla's
                    // in size and position beside one that IS vanilla's is the comparison.
                    Mob z = byId(server, mobZ);
                    if (z != null) {
                        z.setCustomName(Component.literal("Spikor"));
                        z.setCustomNameVisible(true);
                    }
                }
                case SHADOW_A, SHADOW_Z, SHADOW_B -> {
                    // Nothing is set on the mobs at all: arm A and the cow already cast one, and
                    // `reset` switched the whole client option OFF for the baseline. Turning it
                    // back on is the entire state. (Set on the client thread below.)
                    if (rig != null) {
                        // NOT the bare 0.5 -- see DisplayRig.shadowRadiusFor. A mob's renderer
                        // multiplies its base radius by the entity's scale every frame; a Display
                        // does not, so the multiply has to happen here.
                        rig.setShadow(DisplayRig.shadowRadiusFor(rig.living()),
                                DisplayRig.DEFAULT_SHADOW_STRENGTH);
                    }
                }
                case GLOW -> {
                    a.setGlowingTag(true);
                    Mob zg = byId(server, mobZ);
                    if (zg != null) {
                        zg.setGlowingTag(true);
                    }
                    // 🔴 THE CARRIER, as every shipped path does. This row used to call
                    // rig.setGlowing by hand, which proved the displays CAN glow while nothing in
                    // the product ever made them -- and never once glowed the carrier, so the
                    // horse-shaped outline a tester filmed was never in frame. No team, so the
                    // outline is vanilla's white on every subject: pure parity.
                    b.setGlowingTag(true);
                }
                case GLOW_COLOUR -> {
                    // Arm A stays WHITE and that is the point: `Display.getTeamColor()` returns
                    // glow_color_override when one is set, and a wish_mob has no such hook, so a
                    // per-creature outline colour is something the rig can do that the mod path
                    // cannot. The measurement is "how many of the changed pixels are red-dominant"
                    // -- on arm A that must be ~none.
                    a.setGlowingTag(true);
                    Mob zc = byId(server, mobZ);
                    if (zc != null) {
                        zc.setGlowingTag(true);
                    }
                    // The colour comes the way vanilla's does: the CARRIER on a coloured team, and
                    // the mirror turning that into the displays' glow_color_override.
                    var board = server.getScoreboard();
                    var team = board.getPlayerTeam(GLOW_TEAM);
                    if (team == null) {
                        team = board.addPlayerTeam(GLOW_TEAM);
                    }
                    team.setColor(net.minecraft.ChatFormatting.RED);
                    board.addPlayerToTeam(b.getScoreboardName(), team);
                    b.setGlowingTag(true);
                }
                case NIGHT -> {
                    // 🔴 The first thing that ever exercises `matchLight` in the dark. It pins
                    // every display to the CARRIER's block/sky light so a rig is not lit as
                    // nineteen separate things -- and it has only ever run in a fully-lit test
                    // world, where it is inert. If a sky-light override of 15 makes the rig
                    // immune to nightfall, this row is where that shows up: arm A darkens and
                    // arm B does not.
                    for (ServerLevel level : server.getAllLevels()) {
                        level.setDayTime(18000L);
                    }
                }
                case EMISSIVE -> {
                    // Already night (reset put it there). Force the chosen groups fullbright.
                    if (rig != null) {
                        int n = rig.setEmissive(VanillaStateClientTest::emissiveBone);
                        emissiveGroups = n;
                    }
                }
                case SWIRLS -> {
                    a.setCustomNameVisible(false);
                    b.setCustomNameVisible(false);
                    // DAMAGE_BOOST on purpose: bright red particles and no damage. A poison would
                    // hurt the carrier, and an ambient effect divides the emission rate by five
                    // again (`areAllEffectsAmbient`), which would confound the ratio this measures.
                    a.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 2000, 0,
                            false, true, false));
                    b.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 2000, 0,
                            false, true, false));
                }
                default -> { }
            }
        });
    }

    private static void record(State state, int[][] shot) {
        switch (state) {
            case BURNING -> {
                // Fire is drawn 1.4x the hitbox wide and the full height, so the box is padded
                // generously -- and the pad is the same for both arms, which is what makes the
                // comparison fair.
                int fa = changed(shotBefore, shot, pad(boxA, 30, 30));
                int fb = changed(shotBefore, shot, pad(boxB, 30, 30));
                int oa = fireColoured(shotBefore, shot, pad(boxA, 30, 30));
                int ob = fireColoured(shotBefore, shot, pad(boxB, 30, 30));
                RESULTS.put("burning", "arm A " + fa + " px changed (" + oa + " fire-coloured), "
                        + "arm B " + fb + " px changed (" + ob + " fire-coloured)");
                // The SHAPE, not just the presence. Vanilla stacks the flame in units of the
                // entity's WIDTH (f = bbWidth * 1.4, quads 0.45f apart until bbHeight is
                // covered), so a WIDE mob gets a disproportionately tall flame. Measuring the
                // ratio against a plain zombie in the same frame is what turns "that looks
                // enormous" into a number that either matches vanilla's own arithmetic or does
                // not.
                RESULTS.put("flame shape", "arm A " + flameShape(shot, boxA)
                        + " | vanilla cow " + flameShape(shot, boxZ)
                        + " | arm B " + flameShape(shot, boxB));
            }
            case NAMED -> {
                // ABOVE the creature, where a name tag renders. Using the creature's own box would
                // count nothing, since the tag floats clear of the head.
                int[] bandA = above(boxA);
                int[] bandB = above(boxB);
                // WHERE the plate is, not just that something appeared. A count cannot tell a
                // plate that is 0.25 blocks too high from one that is the right size in the right
                // place -- and 0.25 blocks is exactly one text line, the difference between a
                // text_display (which hangs its text UP from its position) and vanilla's name tag
                // (which hangs it DOWN).
                int[] plateA = box(shotBefore, shot, bandA[0], bandA[2]);
                int[] plateB = box(shotBefore, shot, bandB[0], bandB[2]);
                RESULTS.put("nametag", "arm A " + changed(shotBefore, shot, bandA)
                        + " px above the head, arm B " + changed(shotBefore, shot, bandB) + " px"
                        + "; plate boxes A=" + fmt(plateA) + " B=" + fmt(plateB)
                        + (plateA == null || plateB == null ? ""
                        : "; B sits " + (plateA[1] - plateB[1]) + " px higher than A"));
            }
            case SHADOW_A, SHADOW_Z, SHADOW_B -> {
                // The WHOLE frame: at this pose the camera holds one subject and its own patch of
                // floor, and the only thing that changed between the two shots is whether shadows
                // are drawn at all.
                // 🔴 THE FLOOR, AND ONLY THE MIDDLE OF IT — the second contamination in this
                // one row. Vertically: the lower 40% of the frame, because arm A's own render is
                // not static frame to frame (~850 px of flicker, against arm B's 0) and counting
                // the whole frame adds all of it to arm A's shadow and none to arm B's.
                // Horizontally: the middle 40%, because the NEIGHBOURING subject is still in
                // frame at the edge and ITS shadow toggles too. Measured on the run that found
                // this: arm A scored 1327 px, of which 574 in a single 40-px window at x=806 --
                // the cow, ten blocks away, at the right-hand edge. The diff image is what
                // showed it; the number looked entirely reasonable.
                int wpx = shot[0].length;
                int[] floorBand = {(int) (wpx * 0.30), (int) (shot.length * 0.60),
                        (int) (wpx * 0.70), shot.length - 1};
                int px = changed(shotBefore, shot, floorBand);
                // 🔴 THE FLOOR ONLY. The first cut scanned the whole frame and reported a
                // 498 x 213 "disc" for arm A -- because the mod path's own render is not static
                // frame to frame (the floor row measures ~850 px of arm-A flicker against arm B's
                // 0), and any of that above BODY_THRESHOLD widened the box. A shadow is on the
                // ground; look at the ground.
                int[] disc = boxIn(shotBefore, shot, floorBand);
                String who = state == State.SHADOW_A ? "arm A (mod path)"
                        : state == State.SHADOW_Z ? "vanilla cow" : "arm B (rig)";
                if (state == State.SHADOW_A) {
                    shadowA = px;
                } else if (state == State.SHADOW_B) {
                    shadowB = px;
                } else {
                    shadowZ = px;
                }
                RESULTS.put("shadow " + who, px + " px of floor darkened, disc " + fmt(disc)
                        + (disc == null ? "" : " (" + (disc[2] - disc[0] + 1) + " x "
                        + (disc[3] - disc[1] + 1) + " px)"));
            }
            case GLOW -> {
                glowA = changed(shotBefore, shot, pad(boxA, 14, 14));
                glowB = changed(shotBefore, shot, pad(boxB, 14, 14));
                RESULTS.put("glow", "arm A " + glowA + " px outlined, vanilla cow "
                        + changed(shotBefore, shot, pad(boxZ, 14, 14))
                        + " px, arm B " + glowB + " px");
                outlinesGlow = clientOutlines(Minecraft.getInstance(), 0xFFFFFF);
                RESULTS.put("glow outlines", describe(outlinesGlow));
            }
            case GLOW_COLOUR -> {
                RESULTS.put("glow colour", "red-dominant outline px: arm A (no override) "
                        + redOutline(shotBefore, shot, pad(boxA, 14, 14))
                        + ", arm B (carrier on a red team, 0x" + Integer.toHexString(GLOW_RGB)
                        + ") " + redOutline(shotBefore, shot, pad(boxB, 14, 14)));
                outlinesColour = clientOutlines(Minecraft.getInstance(), GLOW_RGB);
                RESULTS.put("glow colour outlines", describe(outlinesColour));
            }
            case NIGHT -> {
                // The first row after the glow came off the CARRIER -- so this is where "turning
                // it off clears the rig" is asked.
                outlinesAfter = clientOutlines(Minecraft.getInstance(), 0xFFFFFF);
                RESULTS.put("glow off outlines", describe(outlinesAfter));
                double da = luma(shotBefore, boxA) - luma(shot, boxA);
                double dz = luma(shotBefore, boxZ) - luma(shot, boxZ);
                double db = luma(shotBefore, boxB) - luma(shot, boxB);
                RESULTS.put("night", String.format(
                        "luma lost day->night: arm A %.1f, vanilla cow %.1f, arm B %.1f "
                                + "(A day %.1f night %.1f | B day %.1f night %.1f)",
                        da, dz, db, luma(shotBefore, boxA), luma(shot, boxA),
                        luma(shotBefore, boxB), luma(shot, boxB)));
            }
            case EMISSIVE -> {
                // Baseline is ALREADY night (see reset), so this is the brightening alone.
                double gain = luma(shot, boxB) - luma(shotBefore, boxB);
                double control = luma(shot, boxA) - luma(shotBefore, boxA);
                RESULTS.put("emissive", String.format(
                        "%d of %d groups forced 15/15; arm B gained %.1f luma at night "
                                + "(arm A, untouched, moved %.1f)",
                        emissiveGroups, rig == null ? 0 : rig.displays().size(), gain, control));
            }
            default -> { }
        }
    }

    private static void record(State state) {
        if (state == State.SWIRLS) {
            double ratio = swirlA == 0 ? -1 : (double) swirlB / swirlA;
            RESULTS.put("swirls", String.format(
                    "over %d frames: arm A %d particle px, arm B %d px (B/A = %s; "
                            + "vanilla emits 1-in-4 for a visible carrier and 1-in-15 for an "
                            + "invisible one, so ~0.27 is the predicted ratio)",
                    swirlFramesTaken, swirlA, swirlB,
                    ratio < 0 ? "n/a" : String.format("%.2f", ratio)));
        }
    }

    // ------------------------------------------------------------------------------ the verdict

    private static void score() {
        List<String> bad = new ArrayList<>();
        if (failLater != null) {
            bad.add(failLater);
        }
        for (var e : RESULTS.entrySet()) {
            NOTES.add(e.getKey() + ": " + e.getValue());
        }

        // Each row is a pair of claims, and the SECOND is the control: if arm A also shows nothing
        // then the test never set the state, which is a different bug with the same number.
        checkPair(bad, "burning", "the flame overlay");

        // 🔴 THE NAME TAG WAS A KNOWN GAP AND IS NOW BUILT, so this assertion is the other way
        // round from the one that shipped in tier 1: arm B must show a plate. Left as a pair
        // rather than a single check, because "arm A shows nothing either" means the test failed
        // to name anything (or hid the HUD, which switches vanilla's tags off wholesale) and says
        // nothing at all about the rig.
        int[] nt = twoNumbers(RESULTS.getOrDefault("nametag", ""));
        if (nt[0] <= 0) {
            bad.add("arm A shows no name tag either (" + RESULTS.get("nametag") + ") -- the test "
                    + "failed to name the creature, or the HUD is hidden (Minecraft.renderNames() "
                    + "is literally !options.hideGui), so this says nothing about the rig");
        } else if (nt[1] <= 0) {
            bad.add("the rig's text_display name plate did not render: " + RESULTS.get("nametag"));
        } else {
            NOTES.add("nametag: the rig's text_display plate renders -- arm A (vanilla tag) "
                    + nt[0] + " px above the head, arm B (text_display) " + nt[1] + " px");
        }
        // ---- tier 2. Each is a PAIR again: arm A (or the cow) showing nothing means the state
        // never applied, which is a harness bug wearing the same number as a rig bug.
        if (shadowZ <= 0) {
            bad.add("THE VANILLA COW casts no shadow (A=" + shadowA + " cow=" + shadowZ + " B="
                    + shadowB + "), so this row says nothing about the rig. Three causes with the "
                    + "same number: the camera is further than 16 blocks from the subject "
                    + "(strength = (1 - d^2/256) * shadowStrength, and the whole block is SKIPPED "
                    + "at <= 0 -- this is why the side-by-side pose measured zero for everything); "
                    + "the floor is missing or further below the feet than min(strength/0.5, "
                    + "radius); or entityShadows never came back on");
        } else if (shadowB <= 0) {
            bad.add("the rig casts no shadow while the vanilla cow does: cow " + shadowZ
                    + " px, arm A " + shadowA + " px, arm B 0");
        } else {
            NOTES.add("shadow: the rig casts one -- arm B " + shadowB + " px of floor darkened, "
                    + "vanilla cow " + shadowZ + " px (the control), mod path " + shadowA + " px");
            if (shadowA * 3 < shadowB) {
                NOTES.add("shadow: ⚠ THE MOD PATH'S IS ALL BUT INVISIBLE -- " + shadowA
                        + " px against the rig's " + shadowB + ". Its radius is a humanoid "
                        + "default (0.5) times the species scale, which for a creature this wide "
                        + "puts the whole disc under its own body. See DisplayRig.shadowRadiusFor "
                        + "for why the rig deliberately does NOT match it");
            }
        }
        if (glowA <= 0) {
            NOTES.add("glow: SKIPPED -- arm A shows no outline either, so the entity-outline post "
                    + "chain is not running in this client (LevelRenderer.shouldShowEntityOutlines "
                    + "needs entityTarget AND entityEffect). That is a renderer capability "
                    + "question, not a rig one, and it is not scored: " + RESULTS.get("glow"));
        } else if (glowB <= 0) {
            bad.add("the rig shows no glow outline while arm A does: " + RESULTS.get("glow"));
        }
        // Read off the client's own entities rather than pixels.
        for (var row : new Object[][] {{"glow", outlinesGlow}, {"glow colour", outlinesColour}}) {
            int[] o = (int[]) row[1];
            if (o == null) {
                bad.add(row[0] + " outlines were never read");
                continue;
            }
            if (o[0] != 0) {
                bad.add(row[0] + ": the INVISIBLE CARRIER is outlined -- the horse-shaped "
                        + "outline of " + describe(o));
            }
            if (o[2] == 0 || o[1] != o[2]) {
                bad.add(row[0] + ": not every display is outlined: " + describe(o));
            }
            if (o[3] != 0) {
                bad.add(row[0] + ": displays outlined in the wrong colour: " + describe(o));
            }
        }
        if (outlinesAfter == null || outlinesAfter[1] != 0 || outlinesAfter[0] != 0) {
            bad.add("glow off: the rig is still outlined after the carrier stopped glowing: "
                    + (outlinesAfter == null ? "never read" : describe(outlinesAfter)));
        }
        if (swirlA <= 0) {
            bad.add("neither arm emitted potion particles -- the effect never applied, so the "
                    + "swirl ratio measures nothing");
        } else if (swirlB <= 0) {
            bad.add("arm A emitted " + swirlA + " particle px and arm B NONE -- swirls do not "
                    + "reach an invisible carrier after all");
        }
        finish(bad.isEmpty(), String.join("; ", bad));
    }

    private static String describe(int[] o) {
        return "carrier outlined=" + (o[0] != 0) + ", displays outlined " + o[1] + "/" + o[2]
                + ", wrong colour " + o[3];
    }

    private static void checkPair(List<String> bad, String row, String what) {
        String v = RESULTS.get(row);
        if (v == null) {
            bad.add(row + " was never measured");
            return;
        }
        int[] n = twoNumbers(v);
        if (n[0] <= 0) {
            bad.add("arm A shows nothing for " + what + " either (" + v + ") -- the test failed to "
                    + "put the creature in that state, which says nothing about the rig");
        } else if (n[1] <= 0) {
            bad.add(what + " does NOT reach the vanilla rig: " + v);
        }
    }

    /** First two integers in a result line — arm A's then arm B's. */
    private static int[] twoNumbers(String s) {
        int[] out = {0, 0};
        int found = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) px").matcher(s);
        while (m.find() && found < 2) {
            out[found++] = Integer.parseInt(m.group(1));
        }
        return out;
    }

    // ------------------------------------------------------------------------------- the pixels

    private static int[][] grab(Minecraft mc, String name) {
        if (mc.getOverlay() != null) {
            // Belt and braces for the pink-splash baseline: nothing measured through an overlay
            // means anything, and a frame that LOOKS plausible is the expensive kind of wrong.
            failLater = "a frame was captured with " + mc.getOverlay().getClass().getSimpleName()
                    + " still on screen -- every pixel number in this run is measuring that";
        }
        try (var img = Screenshots.take(mc.getMainRenderTarget())) {
            int w = img.getWidth();
            int h = img.getHeight();
            int[][] px = new int[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // 🔴 getPixelRGBA IS ABGR, whatever the name says. NativeImage holds bytes in
                    // R,G,B,A order and packs them into a little-endian int, so the int reads
                    // A,B,G,R from the top. Every other pixel helper in this repo only ever
                    // SUBTRACTS two shots, and a difference does not care which channel is which
                    // -- so the bug has been latent in `grab` for as long as it has existed and
                    // this is the first test to INTERPRET a colour rather than compare one.
                    //
                    // What it looked like: the sky predicate (`blue beats red`) read the red
                    // channel as blue, called the entire frame non-sky, and the creature box came
                    // back as the whole half-screen. Same family as the Mod Repo's map-art bug,
                    // where a red banner rendered blue with every gate green.
                    int abgr = com.moddingfromamod.compat.Pixels.abgr(img, x, y);
                    px[y][x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
                }
            }
            if (name != null) {
                try {
                    java.nio.file.Files.createDirectories(SIG);
                    img.writeToFile(SIG.resolve(name + ".png"));
                } catch (Exception ignored) {
                    // the pixels carry the verdict; the file is for a human
                }
            }
            return px;
        }
    }

    /** How many pixels inside {@code box} differ between two shots, beyond sampling noise. */
    private static int changed(int[][] a, int[][] b, int[] box) {
        if (a == null || b == null || box == null) {
            return 0;
        }
        int n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(a.length - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(a[0].length - 1, box[2]); x++) {
                if (far(a[y][x], b[y][x])) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Of the changed pixels, how many are FLAME-coloured — orange/yellow and clearly not sky. */
    private static int fireColoured(int[][] a, int[][] b, int[] box) {
        if (a == null || b == null || box == null) {
            return 0;
        }
        int n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(a.length - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(a[0].length - 1, box[2]); x++) {
                int c = b[y][x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int bl = c & 0xFF;
                if (far(a[y][x], c) && r > 140 && r > bl + 55 && g > bl && g < r) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * How far the flame reaches above the body, as a MULTIPLE of the body's own height — the one
     * number that can be compared between a creature and a zombie.
     */
    private static String flameShape(int[][] shot, int[] body) {
        if (body == null) {
            return "?";
        }
        int[] lit = box(shotEmpty, shot, Math.max(0, body[0] - 110),
                Math.min(shot[0].length, body[2] + 110));
        if (lit == null) {
            return "none";
        }
        int bodyH = body[3] - body[1] + 1;
        int total = body[3] - lit[1] + 1;
        return String.format("%dx%d px of flame over a %dx%d body — %.2fx its height, %.2fx wide",
                lit[2] - lit[0] + 1, total, body[2] - body[0] + 1, bodyH,
                (double) total / bodyH, (double) (lit[2] - lit[0] + 1) / (body[2] - body[0] + 1));
    }

    /** Mean colour of a whole frame — the cheapest way to see a WHOLESALE difference. */
    private static String mean(int[][] shot) {
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = 0; y < shot.length; y += 3) {
            for (int x = 0; x < shot[0].length; x += 3) {
                int c = shot[y][x];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        return n == 0 ? "?" : "(" + r / n + "," + g / n + "," + b / n + ")";
    }

    /** The creature's own pixels inside a padded box, so a particle count can exclude them. */
    private static boolean[][] mask(int[][] shot, int[] box) {
        boolean[][] m = new boolean[box[3] - box[1] + 1][box[2] - box[0] + 1];
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m[0].length; x++) {
                int py = box[1] + y;
                int px = box[0] + x;
                if (py >= 0 && py < shot.length && px >= 0 && px < shot[0].length) {
                    m[y][x] = far(shotEmpty[py][px], shot[py][px]);
                }
            }
        }
        return m;
    }

    /** Non-sky pixels inside the box that are NOT the creature — i.e. particles. */
    private static int loose(int[][] shot, int[] box, boolean[][] creature) {
        if (shot == null || box == null || creature == null) {
            return 0;
        }
        int n = 0;
        for (int y = 0; y < creature.length; y++) {
            for (int x = 0; x < creature[0].length; x++) {
                int py = box[1] + y;
                int px = box[0] + x;
                if (py < 0 || py >= shot.length || px < 0 || px >= shot[0].length) {
                    continue;
                }
                if (!creature[y][x] && far(shotEmpty[py][px], shot[py][px])) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int delta(int p, int q) {
        return Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF))
                + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                + Math.abs((p & 0xFF) - (q & 0xFF));
    }

    private static boolean far(int p, int q) {
        return delta(p, q) > 24;
    }

    /**
     * Bounding box of what appeared between two shots, within a column range. Null if nothing.
     *
     * <p>Deliberately a MUCH coarser threshold than the state measurements use. A body against
     * open sky is a colour difference of hundreds; what a box must not pick up is the residual
     * global drift between two frames taken a few seconds apart — the client eases its sky colour
     * toward the server's clock for a moment after the time is pinned, which shifted the whole
     * frame by ~4 per channel and stretched both boxes to the bottom of the screen. 70 sits an
     * order of magnitude above that and an order below a creature.
     */
    private static final int BODY_THRESHOLD = 70;

    /** {@link #box} over an explicit rectangle rather than a column range. */
    private static int[] boxIn(int[][] before, int[][] after, int[] region) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        for (int y = Math.max(0, region[1]); y <= Math.min(before.length - 1, region[3]); y++) {
            for (int x = Math.max(0, region[0]); x <= Math.min(before[0].length - 1, region[2]);
                    x++) {
                if (delta(before[y][x], after[y][x]) > BODY_THRESHOLD) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[] {x0, y0, x1, y1};
    }

    private static int[] box(int[][] before, int[][] after, int xFrom, int xTo) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        int yMax = (int) (before.length * 0.90);
        for (int y = 0; y < yMax; y++) {
            for (int x = xFrom; x < Math.min(xTo, before[0].length); x++) {
                if (delta(before[y][x], after[y][x]) > BODY_THRESHOLD) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[] {x0, y0, x1, y1};
    }

    private static int[] pad(int[] b, int dx, int dy) {
        return b == null ? null : new int[] {b[0] - dx, b[1] - dy, b[2] + dx, b[3] + dy};
    }

    /** The band a name tag lives in: directly over the creature, clear of its head. */
    private static int[] above(int[] b) {
        return b == null ? null : new int[] {b[0] - 40, b[1] - 46, b[2] + 40, b[1] - 3};
    }

    /**
     * The floor in front of the creature's feet — where a shadow lands.
     *
     * <p>It starts a few pixels ABOVE the box's bottom edge on purpose: the "bottom of the
     * creature" is the lowest pixel its silhouette reaches, and a shadow disc is centred on the
     * entity's position, so half of it is behind the feet and would be cut off by a band that
     * began exactly at the box edge.
     */
    private static int[] below(int[] b) {
        return b == null ? null : new int[] {b[0] - 34, b[3] - 8, b[2] + 34, b[3] + 44};
    }

    /**
     * Pixels that CHANGED and came out red-dominant — the glow-colour override's own signature.
     * White (vanilla's default outline) has r ≈ g ≈ b and scores zero here whatever its brightness,
     * which is what makes this a test of the COLOUR rather than of the outline.
     */
    private static int redOutline(int[][] a, int[][] b, int[] box) {
        if (a == null || b == null || box == null) {
            return 0;
        }
        int n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(a.length - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(a[0].length - 1, box[2]); x++) {
                int c = b[y][x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int bl = c & 0xFF;
                if (far(a[y][x], c) && r > 110 && r > g + 45 && r > bl + 45) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Mean luminance inside a box, 0..255. The instrument for "did this darken". */
    private static double luma(int[][] shot, int[] box) {
        if (shot == null || box == null) {
            return -1;
        }
        long sum = 0;
        long n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(shot.length - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(shot[0].length - 1, box[2]); x++) {
                int c = shot[y][x];
                sum += (299 * ((c >> 16) & 0xFF) + 587 * ((c >> 8) & 0xFF) + 114 * (c & 0xFF))
                        / 1000;
                n++;
            }
        }
        return n == 0 ? -1 : (double) sum / n;
    }

    /**
     * Which bones glow. There is no emissive opt-in in the sculpt schema and the sculpt tool is
     * frozen for this spike, so the harness picks by name — which is also honest about what row
     * 2.4 actually delivers: the PLUMBING, not the authoring.
     */
    private static boolean emissiveBone(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("head") || n.contains("eye") || n.contains("face");
    }

    /**
     * The GLOW_COLOUR row's outline colour: the carrier's TEAM colour, carried to the displays by
     * the mirror. A red nothing else in frame is.
     */
    private static final int GLOW_RGB = net.minecraft.ChatFormatting.RED.getColor();
    private static final String GLOW_TEAM = "vanillastate_red";

    /**
     * What the CLIENT will draw an outline around, read off its own entities — the question
     * {@code LevelRenderer} asks per entity ({@code Minecraft.shouldEntityAppearGlowing}), so
     * there is no pixel instrument between it and the answer. Integrated-server entity ids are
     * the client's ids.
     *
     * @return {carrier outlined (1/0), displays outlined, displays, displays in the wrong colour}
     */
    private static int[] clientOutlines(Minecraft mc, int wantRgb) {
        int[] out = {0, 0, 0, 0};
        if (mc.level == null || rig == null) {
            return out;
        }
        var carrier = mc.level.getEntity(rig.base().getId());
        out[0] = carrier != null && mc.shouldEntityAppearGlowing(carrier) ? 1 : 0;
        for (var d : rig.displays()) {
            var c = mc.level.getEntity(d.getId());
            if (c == null) {
                continue;
            }
            out[2]++;
            if (mc.shouldEntityAppearGlowing(c)) {
                out[1]++;
                if ((c.getTeamColor() & 0xFFFFFF) != (wantRgb & 0xFFFFFF)) {
                    out[3]++;
                }
            }
        }
        return out;
    }

    private static int[] outlinesGlow;
    private static int[] outlinesColour;
    private static int[] outlinesAfter;

    private static int emissiveGroups;

    /**
     * ⚠ The tier-2 rows keep their numbers in FIELDS and not only in the report string.
     * {@code twoNumbers} reads the first two "N px" out of a row, which was right while every row
     * was a pair — and every tier-2 row is a TRIPLE (arm A, the vanilla control, arm B), so it
     * would have scored the rig's shadow against the COW's and never said so. Parsing your own
     * formatted output is an instrument, and this one had already silently changed meaning.
     */
    private static int shadowA;
    private static int shadowZ;
    private static int shadowB;

    private static boolean isShadow(State s) {
        return s == State.SHADOW_A || s == State.SHADOW_Z || s == State.SHADOW_B;
    }
    private static int glowA;
    private static int glowB;

    private static String fmt(int[] b) {
        return b == null ? "none"
                : "(" + b[0] + "," + b[1] + ")-(" + b[2] + "," + b[3] + ")";
    }

    private static Mob armA(net.minecraft.server.MinecraftServer server) {
        return byId(server, mobA);
    }

    private static Mob byId(net.minecraft.server.MinecraftServer server, UUID id) {
        if (id == null) {
            return null;
        }
        for (ServerLevel l : server.getAllLevels()) {
            if (l.getEntity(id) instanceof Mob m) {
                return m;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------- the camera

    private static void hold(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        // 🔴 HIDING THE HUD ALSO HIDES NAME TAGS. `Minecraft.renderNames()` is literally
        // `!options.hideGui`, and `LivingEntityRenderer.shouldShowName` is gated on it -- so the
        // camera rule this repo carries everywhere (hide the HUD before the shutter) SILENTLY
        // switches off the very thing the NAMED row measures. Measured: arm A, a plainly visible
        // mob with a visible custom name, scored 0 px above its head.
        //
        // The HUD is left up for that one state instead. Safe because every number here is a
        // difference against a baseline taken inside the SAME state, so the hotbar and crosshair
        // are in both frames and cancel -- and the band being measured is above the creature's
        // head, nowhere near either.
        mc.options.hideGui = phase != Phase.STATES
                || State.values()[Math.min(stateIndex, State.values().length - 1)] != State.NAMED;
        mc.setScreen(null);
        double x = CAM_X;
        double y = CAM_Y;
        double z = CAM_Z;
        float pitch = CAM_PITCH;
        if (phase == Phase.STATES) {
            State st = State.values()[Math.min(stateIndex, State.values().length - 1)];
            if (isShadow(st)) {
                x = st == State.SHADOW_A ? LANE_A : st == State.SHADOW_B ? LANE_B : CAM_X;
                y = FOOT_Y + SHADOW_CAM_UP;
                z = STAGE_Z - SHADOW_CAM_BACK;
                pitch = SHADOW_CAM_PITCH;
            }
        }
        mc.player.setPos(x, y, z);
        mc.player.setYRot(0f);
        mc.player.setXRot(pitch);
        mc.player.xOld = x;
        mc.player.yOld = y;
        mc.player.zOld = z;
        mc.player.yRotO = 0f;
        mc.player.xRotO = pitch;
    }

    /**
     * 🔴 <b>THE SHADOW ROW GETS ITS OWN CAMERA, ONE SUBJECT AT A TIME, AND THE REASON IS A LAW OF
     * VANILLA RATHER THAN A CHOICE.</b>
     *
     * <p>The first tier-2 run measured <b>zero shadow pixels for all three subjects — including the
     * vanilla cow</b>, on a stage with 561 blocks of white concrete laid under it. The control is
     * what makes that readable: a cow casting no shadow is not a rig problem.
     *
     * <p>{@code EntityRenderDispatcher.render} computes
     * {@code strength = (1 - distanceToSqr/256) * shadowStrength} and <b>skips the shadow entirely
     * when that is {@code <= 0}</b>. 256 is 16², so <b>nothing in Minecraft casts a shadow more
     * than 16 blocks from the camera.</b> The side-by-side pose is ~20 blocks out, because two
     * 4.5-block creatures 20 blocks apart cannot be framed any closer — so the side-by-side camera
     * and the shadow are mutually exclusive, and no amount of tuning the floor would have helped.
     *
     * <p>Hence three passes. Each is still a paired A/B in one pose (shadows off, then on, one
     * client option), so it is exactly as rigorous as the side-by-side rows; it just cannot put the
     * three subjects in one frame. 7.5 blocks out gives strength ~0.78 and a 37-degree grazing
     * angle, against 0.0 and 6.7 degrees at the side-by-side pose.
     */
    private static final double SHADOW_CAM_UP = 4.5;

    private static final double SHADOW_CAM_BACK = 6.0;

    private static final float SHADOW_CAM_PITCH = 30f;

    /**
     * The recorder's contract — see tools/record-client.sh. One window per state, so the contact
     * sheet is four short clips rather than one long take with nothing happening in most of it.
     *
     * <p>Worth filming at all only because two of these states MOVE: a flame flickers and a swirl
     * is an emission RATE, and neither survives a single frame. The name tag is a still, and is
     * filmed only so the four clips read as one comparison.
     */
    private static void writeFilmWindow(String label) {
        if (filmStart == 0L) {
            return;
        }
        try {
            Path windows = SIG.resolve("film-windows.tsv");
            java.nio.file.Files.createDirectories(windows.getParent());
            java.nio.file.Files.writeString(windows,
                    filmStart + "\t" + System.currentTimeMillis() + "\t" + label + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            NOTES.add("film window '" + label + "' " + filmStart + ".."
                    + System.currentTimeMillis());
        } catch (Exception ignored) {
            // no recorder attached; the assertions still stand
        }
        filmStart = 0L;
    }

    private static void go(Phase next) {
        phase = next;
        ticks = 0;
        fired = false;
        baselined = false;
    }

    private static void finish(boolean pass, String message) {
        ClientHarness.verdict(SIG, pass,
                (pass ? "PASS" : "FAIL — " + message) + "\n" + String.join("\n", NOTES));
        phase = Phase.DONE;
        Minecraft.getInstance().stop();
    }
}
