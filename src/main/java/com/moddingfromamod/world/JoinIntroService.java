package com.moddingfromamod.world;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.runtime.RuntimeConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The first-join intro, said once per player per world</b> — the Minecraft half of
 * {@link JoinIntro}. A child arriving in a world for the first time is told, a few seconds after he
 * has loaded in, what the world is, how to use Moddy and (on a world with a wish bank) what his
 * wishes are; {@link IntroSeenStore} remembers it, so he is told once per world.
 *
 * <p>The wish bank's first-visit line is FOLDED in rather than said beside it: {@code WishBankService}
 * asks {@link #introducing} at the login and, when the intro is coming, hands its line over
 * ({@link #afterIntro}) instead of saying it straight away — so the three parts arrive in order and
 * none twice, whichever login handler NeoForge happens to run first.
 *
 * <p>A service client (the camera, {@link PlayerKind#isChild}) is never introduced. Gameplay events,
 * so {@code NeoForge.EVENT_BUS} (the default bus of {@link EventBusSubscriber}).
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class JoinIntroService {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/intro");

    /** How long after the login the intro is said: long enough for the world to have drawn (3 s at 20 tps). */
    public static final int DELAY_TICKS = 60;

    /** One player's intro, waiting for its tick. */
    static final class Pending {
        final int dueTick;
        final IntroSeenStore store;
        final WorldFacts world;
        volatile String bankLine;

        Pending(int dueTick, IntroSeenStore store, WorldFacts world) {
            this.dueTick = dueTick;
            this.store = store;
            this.world = world;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static volatile IntroSeenStore live;

    private JoinIntroService() {
    }

    static Path storeFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("wishes").resolve(IntroSeenStore.FILE);
    }

    /** Whether the intro runs on this server (see {@link JoinIntro#enabled}). */
    /** Step 8: re-read who has seen the intro, after the re-key rewrote the file. */
    public static void reloadSeen() {
        IntroSeenStore s = live;
        if (s != null) {
            s.load();
        }
    }

    public static boolean enabled() {
        return JoinIntro.enabled(RuntimeConfig.intro(), WorldFacts.manifestId());
    }

    /**
     * True when {@code player} is being introduced on this login — asked by the wish bank so its
     * first-visit line can follow the intro instead of arriving before it. Independent of which
     * login handler ran first: it asks the store, not the pending queue.
     */
    public static boolean introducing(ServerPlayer player) {
        IntroSeenStore s = live;
        if (player == null || s == null) {
            return false;
        }
        if (PENDING.containsKey(player.getUUID())) {
            return true;
        }
        return JoinIntro.due(enabled(), PlayerKind.isChild(player.getUUID()), s.usable(), s.seen(player.getUUID()));
    }

    /**
     * Hand a line to the intro, to be said after it (the wish bank's first-visit line). Returns false
     * when no intro is coming, and then the caller says it itself.
     */
    public static boolean afterIntro(ServerPlayer player, String line) {
        if (!introducing(player)) {
            return false;
        }
        Pending p = PENDING.computeIfAbsent(player.getUUID(),
                id -> new Pending(player.getServer().getTickCount() + DELAY_TICKS, live, WorldFacts.current()));
        p.bankLine = line;
        return true;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        PENDING.clear();
        live = new IntroSeenStore(storeFile(event.getServer()));
        if (enabled()) {
            WorldFacts w = WorldFacts.current();
            LOGGER.info("join intro: on for {} ({} world line(s), then how to use Moddy)",
                    w.name().isEmpty() ? "this world" : w.name(), w.intro().size());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        live = null;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (introducing(player)) {
            schedule(player, live, WorldFacts.current());
        } else if (enabled()) {
            // a returning player's guide book gets today's words (a world with no book: nothing)
            GuideBookService.refreshHeld(player, WorldFacts.current());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // Not said, so not seen: a child who leaves inside the delay is introduced next time.
        PENDING.remove(event.getEntity().getUUID());
    }

    /** Queue {@code player}'s intro against {@code store} (the GameTest hands its own). */
    public static void schedule(ServerPlayer player, IntroSeenStore store, WorldFacts world) {
        PENDING.computeIfAbsent(player.getUUID(),
                id -> new Pending(player.getServer().getTickCount() + DELAY_TICKS, store, world));
    }

    /** {@link #schedule}, with the line the wish bank would hand over (the GameTest's path to it). */
    public static void schedule(ServerPlayer player, IntroSeenStore store, WorldFacts world, String bankLine) {
        schedule(player, store, world);
        Pending p = PENDING.get(player.getUUID());
        if (p != null) {
            p.bankLine = bankLine;
        }
    }

    public static boolean pending(UUID player) {
        return PENDING.containsKey(player);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        for (Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pending> e = it.next();
            Pending p = e.getValue();
            if (now < p.dueTick) {
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                continue;
            }
            if (p.store != null && p.store.seen(e.getKey())) {
                bank(player, p.bankLine);          // introduced meanwhile (another login): the bank's line alone
                continue;
            }
            say(player, JoinIntro.help(p.world), p.world.intro().size());
            bank(player, p.bankLine);
            // And the world's guide book, once — it rides this once-per-world record, so it
            // needs none of its own. A world with no guide gives nothing.
            GuideBookService.give(player, p.world);
            if (p.store != null && !p.store.markSeen(e.getKey(), System.currentTimeMillis())) {
                LOGGER.warn("join intro: said to {}, but could not record it — he may hear it again",
                        player.getScoreboardName());
            }
        }
    }

    /** {@code /moddy help}: this world's intro and the how-to, any time. */
    public static void help(ServerPlayer player) {
        WorldFacts w = WorldFacts.current();
        say(player, JoinIntro.help(w), w.intro().size());
    }

    /**
     * The lines, styled by part: the world's own lines in aqua, the how-to in white, the bank's line
     * in gold (as {@code WishFeedback.bank} says every bank line).
     */
    private static void say(ServerPlayer player, List<String> lines, int worldLines) {
        for (int i = 0; i < lines.size(); i++) {
            ChatFormatting colour = i < worldLines ? ChatFormatting.AQUA
                    : lines.get(i).equals(JoinIntro.HOW_TO_HEADING) ? ChatFormatting.GRAY : ChatFormatting.WHITE;
            player.sendSystemMessage(Component.literal(lines.get(i)).withStyle(colour));
        }
    }

    private static void bank(ServerPlayer player, String line) {
        if (line != null && !line.isBlank()) {
            com.moddingfromamod.wish.WishFeedback.bank(player, line);
        }
    }
}
