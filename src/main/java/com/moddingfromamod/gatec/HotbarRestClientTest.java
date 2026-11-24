package com.moddingfromamod.gatec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.safety.SafetyVerdict;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.wish.WishCharge;
import com.moddingfromamod.wish.WishHotbar;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>Gate C for a hotbar wish's rest: the sweep a stock client draws runs the length the world's charge names.</b>
 *
 * <p>A world charge that rests every wish for ten seconds is put in force, a wish goes on his hotbar through the real
 * "Add to hotbar" (the first empty slot), and it is fired through the real {@code WishHotbar.use}. Five seconds later (past the core's own
 * one-second rest) the client's OWN cooldown for the proxy is read and the screen, hotbar included, is photographed:
 * the sweep must be about half way, not finished.
 *
 * <pre>xvfb-run -a ./gradlew runClient -Pstore_jar=false -Ptestprops="testmode=hotbarrest,home=/tmp/moddy-dev,sigdir=/tmp/hotbarrest"</pre>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class HotbarRestClientTest {

    private HotbarRestClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";
    private static final boolean ENABLED = "hotbarrest".equalsIgnoreCase(System.getProperty(P + "testmode", ""));
    private static final Path SIG = Path.of(System.getProperty(P + "sigdir", "/tmp/moddingfromamod-hotbarrest"));

    /** The world's rest: ten seconds. */
    private static final int REST = 200;
    /** When the photograph is taken after the fire: half the world's rest, four times the core's own. */
    private static final int SHOT_AT = 100;

    private enum Phase { WAIT_TITLE, WAIT_INGAME, SETUP, FIRE, WAIT, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static volatile String outcome = "";
    private static int slot = -1;
    private static final List<String> NOTES = new ArrayList<>();

    /** Rests every wish for {@link #REST} ticks of the server's clock after a fire it allows. */
    static final class TenSeconds implements WishCharge {
        private final MinecraftServer server;
        private final java.util.Map<String, Integer> until = new java.util.concurrent.ConcurrentHashMap<>();

        TenSeconds(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public boolean refusesAtSubmit(ServerPlayer player, boolean distress, String text, boolean recipe) {
            int now = server.getTickCount();
            if (until.getOrDefault(text, 0) > now) {
                player.sendSystemMessage(Component.literal("Still resting."));
                return true;
            }
            until.put(text, now + REST);
            return false;
        }

        @Override
        public int restLeft(com.moddingfromamod.wish.WishAsk ask) {
            return Math.max(0, until.getOrDefault(ask.recipe(), 0) - server.getTickCount());
        }
    }

    private static final String RECIPE = "gatec_hotbar_rest";

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
                    if (ClientHarness.bootstrapWorld(mc, "hotbar-rest", ticks, true)) {
                        go(Phase.SETUP);
                    }
                }
                case SETUP -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        var server = mc.getSingleplayerServer();
                        server.execute(() -> {
                            ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                            Plugins.shared().replaceForTest(WishCharge.class, new TenSeconds(server));
                            SafetyGrader.forTest(t -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
                            List<String> with = new ArrayList<>(LearnedTemplates.savedJsonForTest());
                            with.add("{\"id\":\"" + RECIPE + "\",\"all\":[\"gatechotbarrest\"],\"ownerId\":\""
                                    + sp.getStringUUID() + "\",\"commands\":[\"say a resting wish\"]}");
                            LearnedTemplates.restoreForTest(with, server);
                            WishHotbar.add(sp, new WatchPanel.Cell(0, WatchPanel.Family.CREATION,
                                    WatchPanel.Action.CARD, "Blaze Rod", "Blaze Rod", RECIPE, "Blaze Rod",
                                    "minecraft:blaze_rod", List.of()));
                        });
                        com.moddingfromamod.gatec.ClientHarness.setHudHidden(mc, false);
                        go(Phase.FIRE);
                    }
                }
                case FIRE -> {
                    if (ticks == 20) {
                        for (int i = 0; i < 9; i++) {
                            slot = WishHotbar.recipeOf(mc.player.getInventory().getItem(i)) != null && slot < 0 ? i : slot;
                        }
                        if (slot < 0) {
                            finish(false, "the wish never reached his hotbar");
                            return;
                        }
                        mc.player.getInventory().setSelectedSlot(slot);
                        final int at = slot;
                        var server = mc.getSingleplayerServer();
                        server.execute(() -> {
                            ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                            outcome = String.valueOf(WishHotbar.use(sp, sp.getInventory().getItem(at)));
                        });
                        go(Phase.WAIT);
                    }
                }
                case WAIT -> {
                    if (ticks == SHOT_AT) {
                        ItemStack held = mc.player.getInventory().getItem(slot);
                        float pct = mc.player.getCooldowns().getCooldownPercent(held, 0f);
                        NOTES.add("fire: " + outcome);
                        NOTES.add("slot " + (slot + 1) + " holds " + held.getHoverName().getString() + ", a proxy: "
                                + (WishHotbar.recipeOf(held) != null));
                        NOTES.add(String.format("client cooldown %.0f%% left, %d ticks after the fire (%d-tick rest)",
                                pct * 100, SHOT_AT, REST));
                        String problem = ClientHarness.snap(mc, SIG, "hotbar-rest-midway.png", true);
                        boolean ok = problem == null && WishHotbar.recipeOf(held) != null && pct > 0.3f && pct < 0.7f;
                        finish(ok, problem != null ? problem : ok ? "the sweep is part-way through the world's rest"
                                : "the sweep is not part-way through a ten-second rest");
                    }
                }
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
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
