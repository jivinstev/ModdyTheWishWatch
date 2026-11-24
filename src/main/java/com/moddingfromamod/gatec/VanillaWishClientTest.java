package com.moddingfromamod.gatec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.vanilla.DisplayRig;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>Gate D for the vanilla-compat path — decision D4, and the only run in this spike that starts
 * with a sentence a child typed.</b>
 *
 * <p>Everything else here spawns a rig by calling {@link DisplayRig#spawn} from a harness. This
 * types a real wish into the real wish watch, lets the real agent grant it, and asks what actually
 * arrived in the world. It is the difference between "the rig works" and "a wish reaches the rig",
 * and until {@code VanillaRigBridge} existed the answer to the second was <em>no</em> — the flag
 * had no callers at all.
 *
 * <h2>What Gate C½ could not answer, and why this run exists</h2>
 *
 * <p>The headless tier grants the same wish in 125 s and cannot finish it: {@code sculpt_creature}
 * only spawns a creature when a player is in the world, and a dedicated server's harness player is
 * a {@code FakePlayer} that is not in the player list. So C½ proves the agent GRANTS it and stops
 * exactly one step before the thing D4 is about. A real client has a real player.
 *
 * <h2>The oracle</h2>
 *
 * <p>Three questions, and the third is the one a green run could otherwise dodge:
 *
 * <ol>
 *   <li><b>Did a creature arrive at all?</b> Display entities in the world where there were none.</li>
 *   <li><b>Is anything modded visible?</b> Zero {@link WishMobEntity} — that is D1's claim, made at
 *       the seam where it actually matters rather than in a harness that never had one.</li>
 *   <li><b>Can you SEE it?</b> A photograph with pixels in it. The atom cube has to resolve on
 *       the client for that, and that can fail silently — a rig whose model never reached the
 *       client is a cluster of magenta cubes, which is still "a creature arrived" to the first
 *       two questions. (The per-creature pack this once had to write, select and reload went
 *       with the pack rig in an earlier change.)</li>
 * </ol>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaWishClientTest {

    private VanillaWishClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanillawish".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillawish"));

    /** Base64 so a wish may contain a comma. */
    private static final String WISH = decodeWish();

    private static String decodeWish() {
        String b64 = System.getProperty(P + "wishb64", "");
        if (!b64.isBlank()) {
            return new String(java.util.Base64.getDecoder().decode(b64),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return "make me a Spikor";
    }

    private enum Phase { WAIT_TITLE, WAIT_INGAME, EMPTY, WISH, WAIT_CREATURE, SETTLE, SHOOT, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    /**
     * <b>The ONE wish driver</b> — {@link WishDriver}, route-aware.
     *
     * <p>Unlike the Gate Ds, this test's subject IS the route, so the driver is here to drive the
     * REAL wish box rather than to make a legacy assumption survive. On the shipped default that is
     * three server round trips; the old straight-line version asserted on {@code WishScreen} in the
     * same tick as the right-click and could never have started.
     */
    private static final WishDriver DRIVER = new WishDriver();
    private static boolean groundLaid;
    private static String failLater;
    private static int[][] shotEmpty;
    private static long wishAt;

    private static final List<String> NOTES = new ArrayList<>();
    private static final Map<String, String> RESULTS = new LinkedHashMap<>();

    /**
     * 🔴 <b>A FIXED pose, and the empty-stage baseline is taken from it.</b>
     *
     * <p>The first cut aimed the camera AT the creature once it arrived, which is the obvious
     * thing to do and destroys the only reference the run has: {@code shotEmpty} was taken from
     * the original pose, so differencing against it marks <em>every pixel in the frame</em> as
     * subject. It reported {@code (0,0)-(853,440) 376614 px} and PASSED — and the tell was that
     * <b>376614 was identical to the previous run's</b>, which had failed for an unrelated reason.
     * <b>A pixel count that does not move between two different runs is a measurement of the
     * frame, not of the subject.</b>
     *
     * <p>So the camera never moves. {@code sculpt_creature} stands a creature a framed distance
     * along the player's own yaw (see {@code Framing.distanceFor}), so pinning yaw 0 puts it
     * straight ahead down +Z. If it lands out of frame the run FAILS with "nothing on screen",
     * which is honest — where a moving camera would pass while measuring the whole screen.
     */
    private static final double CAM_X = 8.5;
    private static final double CAM_Y = 96.0;
    private static final double CAM_Z = 8.5;
    private static final float CAM_YAW = 0f;
    private static final float CAM_PITCH = 0f;
    private static final int GROUND_Y = (int) CAM_Y - 2;

    /**
     * How far in front of the camera the creature is STOOD for its photograph.
     *
     * <p>🔴 <b>The creature is moved to be framed, and that is deliberate.</b> A wish stands its
     * creature wherever {@code sculpt_creature} decides — measured on this very run, the carrier
     * landed at (7.4, 95.0, 10.8) against a camera at (8.5, 96.0, 8.5), i.e. <b>2.3 blocks away</b>
     * and filling 91.6% of the frame. That is a fact about where a wish puts things (which other
     * gates own) and it makes this one's question — <em>can you SEE it</em> — unanswerable.
     *
     * <p>Moving the camera instead is the trap this run already fell into: the empty-stage baseline
     * was taken from the original pose, so an aimed camera marks the whole frame as subject and
     * every lower bound passes. Moving the SUBJECT keeps the one reference the run has.
     *
     * <p>At Minecraft's default 70° fov the visible height is {@code 1.40 x distance}, so 16 blocks
     * frames a creature up to ~22 tall with room to spare.
     */
    private static final double STAGE_AWAY = 16.0;

    /** How long the agent gets. A creature wish measured 125 s headless; a client is slower. */
    private static final int WISH_TIMEOUT_TICKS =
            Integer.getInteger(P + "wishticks", 20 * 420);

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
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-wish", ticks, true)) {
                        go(Phase.EMPTY);
                    }
                }
                case EMPTY -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        pinTheSky(mc);
                        ClientHarness.quietParticles(mc);
                        ground(mc);
                        hold(mc);
                        if (ticks > ClientHarness.INGAME_SETTLE + 60) {
                            shotEmpty = grab(mc, "0-empty");
                            // 🔴 REFUSE A DARK BASELINE. The stage is laid and the sky pinned
                            // through `server.execute`, and the client then has to be SENT the
                            // chunks — so a baseline taken on a tick count can be a picture of
                            // unloaded void. This run measured exactly that: an empty frame at
                            // mean (28,25,18) against a lit stage at (155,162,147), which makes
                            // EVERY pixel of the real shot "changed" and reads as a creature
                            // filling 90% of the frame.
                            //
                            // The upper bound on frame fraction caught it, which is the only
                            // reason this is a note rather than a shipped false pass — but that
                            // bound reports "the baseline is wrong", and being told WHICH
                            // baseline is wrong is worth its own check.
                            int lit = luma(shotEmpty);
                            if (lit < 60) {
                                finish(false, "the empty stage photographed DARK (mean luma "
                                        + lit + ") -- the chunks around the camera had not "
                                        + "arrived, so this baseline would make the whole frame "
                                        + "read as creature");
                                return;
                            }
                            NOTES.add("empty stage: mean luma " + lit);
                            go(Phase.WISH);
                        }
                    }
                }
                case WISH -> wish(mc);
                case WAIT_CREATURE -> waitForCreature(mc);
                case SETTLE -> {
                    hold(mc);
                    // 🔴 WAIT OUT ANY RELOAD. On the deleted pack rig manifesting the
                    // creature wrote a pack and asked the client to reload it, and a reload draws Mojang's
                    // LoadingOverlay OVER the world — so a frame taken now is a photograph of a
                    // pink screen, not of a creature. This run measured exactly that: 376,614
                    // "changed" pixels covering the whole frame at mean (238,61,71), with the
                    // creature itself perfectly fine underneath. It is tier 1's second instrument
                    // fault arriving on a path that did not exist when that was written, and the
                    // only reason it was not a false PASS is that `grab` refuses a frame with an
                    // overlay on it.
                    //
                    // Restarting the settle each tick the overlay is up means the sixty ticks are
                    // sixty ticks of a client that is actually back.
                    if (mc.gui.overlay() != null) {
                        ticks = 0;
                        return;
                    }
                    if (ticks > 60) {
                        go(Phase.SHOOT);
                    }
                }
                case SHOOT -> shoot(mc);
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    /**
     * <b>Type the wish through the route that actually ships — the REAL wish box (a dialog since D2).</b>
     *
     * <p>This is one of the two tests whose SUBJECT is the wish route, so it may not take
     * {@code ClientHarness.submitWish}'s shortcut: that helper calls {@code WishService.submit}
     * directly and skips every widget, which is honest for a test about a creature and would be a
     * lie here. It drives {@link WishDriver} instead — the same one every end-to-end wish test drives —
     * which right-clicks the watch, opens the panel, clicks COMPOSE, types into the wish box's
     * text field and presses its Wish button.
     *
     * <p>Tick-driven for that reason: the panel arrives in a SERVER PACKET, so the old one-shot
     * {@code fired} guard could not have worked on this route even pointed at the right class.
     */
    private static void wish(Minecraft mc) {
        if (mc.player == null) {
            finish(false, "no player");
            return;
        }
        if (!DRIVER.drive(mc, WISH)) {
            String why = DRIVER.failure();
            if (why != null) {
                finish(false, "the " + WishDriver.route() + " never took the wish — " + why);
            }
            return;
        }
        wishAt = System.currentTimeMillis();
        NOTES.add("wished via the " + WishDriver.route() + ": \"" + WISH + "\"");
        go(Phase.WAIT_CREATURE);
    }

    private static void waitForCreature(Minecraft mc) {
        hold(mc);
        var server = mc.getSingleplayerServer();
        if (server == null) {
            finish(false, "the integrated server went away mid-wish");
            return;
        }
        if (ticks % 100 == 0) {
            NOTES.add("waiting " + (ticks / 20) + "s: displays=" + countDisplays(server)
                    + " wishMobs=" + countWishMobs(server));
        }
        if (countDisplays(server) > 0) {
            NOTES.add("the creature arrived after "
                    + (System.currentTimeMillis() - wishAt) / 1000 + "s");
            go(Phase.SETTLE);
            return;
        }
        if (ticks > WISH_TIMEOUT_TICKS) {
            finish(false, "no creature after " + (ticks / 20) + "s. displays="
                    + countDisplays(server) + " wishMobs=" + countWishMobs(server)
                    + " -- a wish mob with no displays means the bridge declined it (no sculpt "
                    + "spec?); neither means the agent never built one");
        }
    }

    private static void shoot(Minecraft mc) {
        hold(mc);
        var server = mc.getSingleplayerServer();
        if (server == null) {
            finish(false, "no server");
            return;
        }
        // Let the pinned pose reach the renderer before the shutter — the camera notes' rule that
        // a camera moved this tick photographs where it used to be.
        if (ticks < 20) {
            return;
        }

        List<String> bad = new ArrayList<>();
        Entity carrier = null;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getEntities().getAll()) {
                if (e.entityTags().contains(DisplayRig.RIG_TAG)) {
                    carrier = e;
                }
            }
        }
        if (carrier != null && !staged) {
            staged = true;
            stage(server, carrier);
            ticks = 0;          // give the moves a chance to reach the client
            return;
        }
        int displays = countDisplays(server);
        int wishMobs = countWishMobs(server);
        RESULTS.put("what arrived", displays + " display entities, " + wishMobs + " wish mobs");
        if (carrier != null) {
            RESULTS.put("carrier", carrier.getType().builtInRegistryHolder().key().identifier()
                    + String.format(" %.2f x %.2f at (%.1f, %.1f, %.1f)",
                            carrier.getBbWidth(), carrier.getBbHeight(),
                            carrier.getX(), carrier.getY(), carrier.getZ()));
        } else {
            bad.add("displays exist but nothing carries the rig tag");
        }
        if (wishMobs > 0) {
            bad.add(wishMobs + " modded wish mobs are in the world -- a stock client would see a "
                    + "modded entity type (D1)");
        }
        if (displays == 0) {
            bad.add("no displays");
        }

        int[][] shot = grab(mc, "1-wished");
        int[] box = box(shotEmpty, shot);
        if (box == null) {
            bad.add("the creature is in the world and NOTHING changed on screen -- either it "
                    + "landed outside this fixed frame, or nothing of it is being drawn");
        } else {
            int px = pixels(shotEmpty, shot, box);
            int mag = magenta(shot, box);
            double frame = (double) px / (shot.length * shot[0].length);
            RESULTS.put("photographed", fmt(box) + " " + px + " px ("
                    + String.format("%.1f%%", frame * 100) + " of frame), mean "
                    + meanIn(shot, box));
            RESULTS.put("no missing models", mag + " magenta px");
            if (px < 400) {
                bad.add("only " + px + " px of creature -- too small to be a wished creature");
            }
            // 🔴 The upper bound is the one that matters and the one the first cut lacked. A
            // whole-frame "subject" is a broken baseline (a moved camera, an overlay, a changed
            // sky), never a creature -- and it satisfies every lower bound there is.
            if (frame > 0.60) {
                bad.add(String.format("%.1f%% of the frame reads as subject -- that is not a "
                        + "creature, it is a baseline that no longer matches the shot", frame * 100));
            }
            if (mag > 200) {
                bad.add(mag + " magenta missing-model pixels -- the pack never reached the "
                        + "client, so the child sees a cluster of error cubes");
            }
        }
        for (var e : RESULTS.entrySet()) {
            NOTES.add(e.getKey() + ": " + e.getValue());
        }
        if (failLater != null) {
            bad.add(failLater);
        }
        finish(bad.isEmpty(), String.join("; ", bad));
    }

    private static boolean staged;

    /**
     * Stand the whole rig — carrier and every display hanging off it — in front of the camera.
     *
     * <p>The displays are separate entities that sit at the carrier's position and carry their
     * offset inside their own transform, so the whole creature moves by applying <b>one delta to
     * every member</b>. Moving the carrier alone would leave the body behind and photograph an
     * empty stage, which is exactly the kind of green run this file exists to refuse.
     */
    private static void stage(net.minecraft.server.MinecraftServer server, Entity carrier) {
        String owner = DisplayRig.RIG_OWNER_PREFIX + carrier.getUUID();
        double tx = CAM_X;
        double ty = GROUND_Y + 1;
        double tz = CAM_Z + STAGE_AWAY;
        double dx = tx - carrier.getX();
        double dy = ty - carrier.getY();
        double dz = tz - carrier.getZ();
        server.execute(() -> {
            int moved = 0;
            for (ServerLevel level : server.getAllLevels()) {
                List<Entity> all = new ArrayList<>();
                for (Entity e : level.getEntities().getAll()) {
                    all.add(e);
                }
                for (Entity e : all) {
                    if (e == carrier || e.entityTags().contains(owner)) {
                        e.teleportTo(e.getX() + dx, e.getY() + dy, e.getZ() + dz);
                        e.setNoGravity(true);
                        moved++;
                    }
                }
            }
            NOTES.add("staged " + moved + " entities " + String.format("%.1f", STAGE_AWAY)
                    + " blocks in front of the camera");
        });
    }

    private static void hold(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        com.moddingfromamod.gatec.ClientHarness.setHudHidden(mc, true);
        mc.gui.setScreen(null);
        mc.player.setPos(CAM_X, CAM_Y, CAM_Z);
        mc.player.setYRot(CAM_YAW);
        mc.player.setXRot(CAM_PITCH);
        mc.player.yRotO = CAM_YAW;
        mc.player.xRotO = CAM_PITCH;
        mc.player.xOld = CAM_X;
        mc.player.yOld = CAM_Y;
        mc.player.zOld = CAM_Z;
    }

    // ------------------------------------------------------------------------------- the world

    private static int countDisplays(net.minecraft.server.MinecraftServer server) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof Display) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int countWishMobs(net.minecraft.server.MinecraftServer server) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof WishMobEntity) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void ground(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || groundLaid) {
            return;
        }
        groundLaid = true;
        server.execute(() -> {
            var p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                groundLaid = false;
                return;
            }
            ServerLevel level = p.level();
            var floor = net.minecraft.world.level.block.Blocks.CONCRETE.white().defaultBlockState();
            for (int x = (int) CAM_X - 40; x <= (int) CAM_X + 40; x++) {
                for (int z = (int) CAM_Z - 10; z <= (int) CAM_Z + 60; z++) {
                    level.setBlock(new net.minecraft.core.BlockPos(x, GROUND_Y, z), floor, 3);
                }
            }
            p.setPos(CAM_X, CAM_Y, CAM_Z);
            p.setNoGravity(true);
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
                com.moddingfromamod.mv.DayTime.set(level, 6000L);
                com.moddingfromamod.mv.DayTime.weather(level, 6000, 0, false, false);
            }
            server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false, server);
            server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER,false, server);
        });
    }

    // ------------------------------------------------------------------------- the instruments

    private static final int BODY_THRESHOLD = 70;

    private static int[] box(int[][] before, int[][] after) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < (int) (before.length * 0.92); y++) {
            for (int x = 0; x < before[0].length; x++) {
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

    private static int pixels(int[][] before, int[][] after, int[] box) {
        int n = 0;
        for (int y = box[1]; y <= box[3]; y++) {
            for (int x = box[0]; x <= box[2]; x++) {
                if (delta(before[y][x], after[y][x]) > BODY_THRESHOLD) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int magenta(int[][] shot, int[] box) {
        int n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(shot.length - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(shot[0].length - 1, box[2]); x++) {
                int c = shot[y][x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                if (r > 130 && b > 130 && g < 90 && Math.abs(r - b) < 70) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String meanIn(int[][] shot, int[] box) {
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(shot.length - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(shot[0].length - 1, box[2]); x++) {
                int c = shot[y][x];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        return n == 0 ? "?" : "(" + r / n + "," + g / n + "," + b / n + ")";
    }

    /** Mean luma of a frame — used only to refuse a baseline that is a picture of nothing. */
    private static int luma(int[][] shot) {
        long sum = 0;
        long n = 0;
        for (int[] row : shot) {
            for (int c : row) {
                sum += (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
                n++;
            }
        }
        return n == 0 ? 0 : (int) (sum / n);
    }

    private static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static String fmt(int[] b) {
        return b == null ? "none" : "(" + b[0] + "," + b[1] + ")-(" + b[2] + "," + b[3] + ")";
    }

    private static int[][] grab(Minecraft mc, String name) {
        if (mc.gui.overlay() != null) {
            failLater = "a frame was captured with " + mc.gui.overlay().getClass().getSimpleName()
                    + " still on screen";
        }
        try (var img = Screenshots.take(mc.gameRenderer.mainRenderTarget())) {
            int w = img.getWidth();
            int h = img.getHeight();
            int[][] px = new int[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int abgr = com.moddingfromamod.compat.Pixels.abgr(img, x, y);
                    px[y][x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
                }
            }
            if (name != null) {
                try {
                    java.nio.file.Files.createDirectories(SIG);
                    img.writeToFile(SIG.resolve(name + ".png"));
                } catch (Exception ignored) {
                    // the pixels carry the verdict
                }
            }
            return px;
        }
    }

    private static void go(Phase next) {
        phase = next;
        ticks = 0;
        DRIVER.reset();
    }

    private static void finish(boolean pass, String message) {
        ClientHarness.verdict(SIG, pass,
                (pass ? "PASS" : "FAIL — " + message) + "\n" + String.join("\n", NOTES));
        phase = Phase.DONE;
        Minecraft.getInstance().stop();
    }
}
