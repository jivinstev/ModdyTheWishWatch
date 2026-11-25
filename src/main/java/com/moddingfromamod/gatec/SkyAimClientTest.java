package com.moddingfromamod.gatec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wishbuilder.BuilderAllowlist;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>Gate C for #114: where a farm plot made from a sky island's edge goes, in a real client.</b>
 *
 * <p>A flat world; an island 120 blocks up and a stone pad 50 blocks below it, 20 out past its edge; he stands on the
 * island's edge facing out. His look is held by the CLIENT every tick (as a hand on the mouse would), so it reaches the
 * server the way a player's does, and the real Builder make is pressed for four looks: level; slightly down past the
 * edge (missing the pad); steadily at the pad; and a one-tick flick to the pad. Each make's anchor height is recorded.
 * Then the tick-to-tick change in his look is measured standing still and walking forward, as the client sends it.
 *
 * <pre>./tools/client-test.sh skyaim</pre>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class SkyAimClientTest {

    private SkyAimClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";
    private static final boolean ENABLED = "skyaim".equalsIgnoreCase(System.getProperty(P + "testmode", ""));
    private static final Path SIG = Path.of(System.getProperty(P + "sigdir", "/tmp/moddingfromamod-skyaim"));

    private static final int ISLAND = 120;
    private static final int PAD = 70;
    private static final String FARM = "{\"builder\":1,\"kind\":\"building\",\"from\":\"preset:buildings/farm_plot\",\"size\":2}";

    /** One look to press from: its name, its pitch (null: at the pad), and whether it flicks there on the press. */
    private record Look(String name, Float pitch, boolean flick) {
    }

    private static final List<Look> LOOKS = List.of(new Look("level", 0f, false),
            new Look("slightly down past the edge", 20f, false), new Look("steadily at the ground far below", null, false),
            new Look("a one-tick flick to the ground far below", null, true));

    private enum Phase { WAIT_TITLE, WAIT_INGAME, SETUP, HOLD, PRESS, WAIT_MADE, OVERVIEW, STILL, WALK, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static int look;
    private static BlockPos o;
    private static float pitch;
    private static float sent;
    private static final AtomicReference<BuilderMake.Result> MADE = new AtomicReference<>();
    private static final List<String> NOTES = new ArrayList<>();
    private static final List<Integer> HEIGHTS = new ArrayList<>();
    private static float lastYaw = Float.NaN, lastPitch = Float.NaN, maxStill, maxWalk;

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
                    if (ClientHarness.bootstrapWorld(mc, "sky-aim", ticks, true)) {
                        go(Phase.SETUP);
                    }
                }
                case SETUP -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        var server = mc.getSingleplayerServer();
                        BlockPos feet = mc.player.blockPosition();
                        o = new BlockPos(feet.getX(), feet.getY() - 1, feet.getZ());
                        server.execute(() -> {
                            System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
                            BuilderMake.takeCard = false;
                            BuilderMake.riseMillis = 0;
                            var level = server.overworld();
                            for (int x = 0; x <= 4; x++) {
                                for (int z = 0; z <= 4; z++) {
                                    level.setBlockAndUpdate(o.offset(x, ISLAND, z), Blocks.GRASS_BLOCK.defaultBlockState());
                                }
                            }
                            for (int x = 23; x <= 25; x++) {
                                for (int z = 1; z <= 3; z++) {
                                    level.setBlockAndUpdate(o.offset(x, PAD, z), Blocks.STONE.defaultBlockState());
                                }
                            }
                        });
                        mc.player.setPos(o.getX() + 4.5, o.getY() + ISLAND + 1, o.getZ() + 2.5);
                        mc.player.getAbilities().flying = false;
                        startLook(mc);
                        go(Phase.HOLD);
                    }
                }
                case HOLD -> {
                    hold(mc);
                    if (ticks >= 30) {
                        Look l = LOOKS.get(look);
                        if (l.flick()) {
                            sent = padPitch(mc);   // swung down: the press reaches the server one tick after
                        }
                        hold(mc);
                        go(Phase.PRESS);
                    }
                }
                case PRESS -> {
                    hold(mc);
                    var server = mc.getSingleplayerServer();
                    MADE.set(null);
                    server.execute(() -> {
                        ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                        BuilderMake.make(sp, BuilderSpec.read(JsonParser.parseString(FARM).getAsJsonObject()),
                                BuilderAllowlist.REVIEWED).thenAccept(MADE::set);
                    });
                    go(Phase.WAIT_MADE);
                }
                case WAIT_MADE -> {
                    hold(mc);
                    BuilderMake.Result r = MADE.get();
                    if (r != null) {
                        Long a = r.madeId() == null ? null : com.moddingfromamod.builder.BuildRegistry.anchorOf(r.madeId());
                        int h = a == null ? Integer.MIN_VALUE : BlockPos.of(a).getY() - o.getY();
                        HEIGHTS.add(h);
                        Look l = LOOKS.get(look);
                        NOTES.add(String.format("%s (pitch %.1f°): %s, the farm stands %s", l.name(), sent, r.outcome(),
                                a == null ? "nowhere" : "at height " + h + " (island " + (ISLAND + 1) + ", pad " + (PAD + 1) + ")"));
                        ClientHarness.snap(mc, SIG, "sky-aim-" + look + ".png", true);
                        look++;
                        if (look < LOOKS.size()) {
                            startLook(mc);
                            go(Phase.HOLD);
                        } else {
                            go(Phase.OVERVIEW);
                        }
                    } else if (ticks > 1200) {
                        finish(false, "the make for '" + LOOKS.get(look).name() + "' never finished");
                    }
                }
                case OVERVIEW -> {
                    // from the side, once the builds have gone up: the island, the farms level with it on their
                    // bases, and the one on the pad 50 below
                    mc.player.getAbilities().flying = true;
                    mc.player.setPos(o.getX() + 16, o.getY() + 96, o.getZ() - 40);
                    mc.player.setDeltaMovement(0, 0, 0);
                    mc.player.setYRot(0f);
                    mc.player.setYHeadRot(0f);
                    mc.player.setXRot(0f);
                    if (ticks == 200) {
                        String problem = ClientHarness.snap(mc, SIG, "sky-aim-side.png", true);
                        NOTES.add("side view: " + (problem == null ? "sky-aim-side.png" : problem));
                        mc.player.setPos(o.getX() + 4.5, o.getY() + ISLAND + 1, o.getZ() + 2.5);
                        mc.player.getAbilities().flying = false;
                        pitch = 0f;
                        sent = 0f;
                        lastYaw = Float.NaN;
                        go(Phase.STILL);
                    }
                }
                case STILL -> {
                    maxStill = Math.max(maxStill, jitter(mc));
                    if (ticks >= 100) {
                        lastYaw = Float.NaN;
                        mc.options.keyUp.setDown(true);
                        go(Phase.WALK);
                    }
                }
                case WALK -> {
                    maxWalk = Math.max(maxWalk, jitter(mc));
                    if (ticks >= 100) {
                        mc.options.keyUp.setDown(false);
                        NOTES.add(String.format("his look as the client sends it, tick to tick: at most %.3f° standing still "
                                + "and %.3f° walking (a scripted client: no hand on the mouse)", maxStill, maxWalk));
                        boolean ok = HEIGHTS.size() == 4 && HEIGHTS.get(0) == ISLAND + 1 && HEIGHTS.get(1) == ISLAND + 1
                                && HEIGHTS.get(2) == PAD + 1 && HEIGHTS.get(3) == ISLAND + 1;
                        finish(ok, ok ? "level and slightly-down stay level on a base; a steady look settles 50 down; a flick is held level"
                                : "the heights were " + HEIGHTS);
                    }
                }
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    private static void startLook(Minecraft mc) {
        Look l = LOOKS.get(look);
        pitch = l.pitch() == null ? padPitch(mc) : l.pitch();
        sent = l.flick() ? 0f : pitch;   // a flick holds level until the press
    }

    /** The pitch that looks at the middle of the pad's top from his eyes. */
    private static float padPitch(Minecraft mc) {
        double dx = o.getX() + 24.5 - mc.player.getX();
        double dy = mc.player.getEyeY() - (o.getY() + PAD + 1);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    /** The client holds his look: east, at the pitch being sent. */
    private static void hold(Minecraft mc) {
        mc.player.setYRot(-90f);
        mc.player.setYHeadRot(-90f);
        mc.player.setXRot(sent);
    }

    /** The largest change in his look since the last tick. */
    private static float jitter(Minecraft mc) {
        float y = mc.player.getYRot(), p = mc.player.getXRot();
        float d = Float.isNaN(lastYaw) ? 0f : Math.max(Math.abs(y - lastYaw), Math.abs(p - lastPitch));
        lastYaw = y;
        lastPitch = p;
        return d;
    }

    private static void go(Phase next) {
        phase = next;
        ticks = 0;
    }

    private static void finish(boolean pass, String message) {
        ClientHarness.verdict(SIG, pass, (pass ? "PASS — " : "FAIL — ") + message + "\n" + String.join("\n", NOTES));
        phase = Phase.DONE;
        Minecraft.getInstance().stop();
    }
}
