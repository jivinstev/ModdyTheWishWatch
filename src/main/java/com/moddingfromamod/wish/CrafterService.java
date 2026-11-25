package com.moddingfromamod.wish;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.world.WorldAccess;
import com.moddingfromamod.world.WorldCrafters;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Wish Crafters in the game</b> (step 5): reads who is who, says {@link Crafters}' words, and
 * keeps the watch's name and the pause menu in step. Every decision is {@link Crafters}' (pure).
 *
 * <ul>
 *   <li><b>Who is a Crafter</b>: where the world's door is open ({@link WorldCrafters#open}), a player
 *       NOT on the world's list (vanilla's whitelist FILE, read directly — the list the account service
 *       already seats people onto) and no operator. Everyone else is a Wish Magician.</li>
 *   <li><b>Live</b>: a seat given while he plays turns him into a Magician within a second, said out loud,
 *       with no rejoin (the account service's {@code whitelist.add} lands in that file).</li>
 *   <li><b>Ranks</b> are the world's goal advancements; a rank-up is said when it is earned.</li>
 *   <li><b>Nudges</b> point grown-ups at the site, at most one unasked every ten minutes.</li>
 * </ul>
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class CrafterService {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/crafters");

    /** Per player, this server run: the role and rank last seen, the last nudge, ready-made wishes made. */
    private record Seen(Crafters.Role role, String rank, long nudgedAt, int made) {
    }

    private static final Map<UUID, Seen> SEEN = new ConcurrentHashMap<>();

    /** Test seam: treat an operator as not one (a client test's player is the singleplayer owner). */
    public static final String IGNORE_OP_PROPERTY = "moddingfromamod.crafters.ignoreOperator";

    private CrafterService() {
    }

    // ── who is who ───────────────────────────────────────────────────────────────────────────────

    /** The server a player is on, or null (a player with no server-side level). */
    private static MinecraftServer serverOf(ServerPlayer p) {
        return p != null && p.level() instanceof ServerLevel level ? level.getServer() : null;
    }

    public static Crafters.Role role(ServerPlayer p) {
        if (p == null || serverOf(p) == null) {
            return Crafters.Role.MAGICIAN;
        }
        return role(serverOf(p), p.getUUID(), p.getGameProfile());
    }

    /**
     * The same answer for a player by id, online or not (#81: a wish put back in line may belong to a player who has
     * left). The server's lists are keyed by id, so an offline player is read from them just as an online one is.
     */
    public static Crafters.Role role(net.minecraft.server.MinecraftServer server, UUID id) {
        if (server == null || id == null) {
            return Crafters.Role.MAGICIAN;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        return online != null ? role(online) : role(server, id, new com.mojang.authlib.GameProfile(id, ""));
    }

    private static Crafters.Role role(net.minecraft.server.MinecraftServer server, UUID id,
                                      com.mojang.authlib.GameProfile profile) {
        if (com.moddingfromamod.seam.PlayerSeams.isCrafter(id)) {
            return Crafters.Role.CRAFTER;   // a test's bot on a shared server; nothing that ships sets it
        }
        if (AlwaysMagician.anyone(id)) {
            // a server's canary (through AlwaysMagician) asks "is Moddy still GRANTING wishes", so its wish is always the agent's. It is
            // on no world's list, so behind an open door it was a Crafter, its words went to the Apprentice, no
            // wish was made and every canary said NOT SUBMITTED. No player can hold this id.
            return Crafters.Role.MAGICIAN;
        }
        var list = server.getPlayerList();
        boolean open = WorldCrafters.current().open();
        if (!open) {
            return Crafters.Role.MAGICIAN;
        }
        // A client test's player is the singleplayer OWNER, which vanilla counts as an operator — and
        // an operator is always a Magician. IGNORE_OP_PROPERTY is the A/B seam that photographs the
        // Crafter's view on a real client (CrafterShotsClientTest); nothing ships setting it.
        net.minecraft.server.players.NameAndId who = new net.minecraft.server.players.NameAndId(profile);
        boolean op = list.isOp(who) && !"true".equals(System.getProperty(IGNORE_OP_PROPERTY));
        return Crafters.role(true, list.getWhiteList().isWhiteListed(who), op);
    }

    public static boolean isCrafter(ServerPlayer p) {
        return role(p) == Crafters.Role.CRAFTER;
    }

    /** The rank a Crafter holds now, from the world's goal advancements. */
    public static WorldCrafters.Rank rank(ServerPlayer p) {
        return Crafters.rank(WorldCrafters.current().ranks(), id -> done(p, id));
    }

    private static boolean done(ServerPlayer p, String id) {
        try {
            AdvancementHolder h = serverOf(p).getAdvancements().get(ResourceLocation.parse(id));
            return h != null && p.getAdvancements().getOrStartProgress(h).isDone();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What the watch's name says about him: {@code "✦✦ Skilled Crafter"}, {@code "✪ Wish Magician"}, or blank (door shut). */
    public static String title(ServerPlayer p) {
        if (p == null || !WorldCrafters.current().open()) {
            return "";
        }
        Crafters.Role r = role(p);
        return Crafters.title(r, r == Crafters.Role.CRAFTER ? rank(p) : null);
    }

    // ── what a Crafter is told ───────────────────────────────────────────────────────────────────

    /**
     * <b>Where a grown-up becomes a Wish Magician, as a player READS it</b>: one short
     * address, the same in the wish box, the two nudges and Help &amp; links, because a Bedrock form cannot carry
     * a link and a child reads the address out to a grown-up. {@code playmoddy.com} is the site's short name
     * and {@code /magician} is a redirect on the site to the sign-in page. A CLICK
     * on Java still opens the world's own {@code crafters.signup} (it carries which world he came from).
     * A constant, not a manifest key: changing it is a release, and it must never name an address that is not live.
     */
    public static final String MAGICIAN_ADDRESS = "playmoddy.com/magician";

    /** A link to the sign-up page, in the chat's own clickable style: it reads {@link #MAGICIAN_ADDRESS}. */
    static MutableComponent link(String url) {
        return Component.literal(MAGICIAN_ADDRESS).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(com.moddingfromamod.compat.ChatEvents.click(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(com.moddingfromamod.compat.ChatEvents.hoverText(Component.literal(url))));
    }

    /** The sign-up address as a child reads it (Help &amp; links; the wish box). */
    public static String signupShown() {
        return MAGICIAN_ADDRESS;
    }

    /** The sign-up address (the Apprentice's capped line about Wish Magicians links to it). */
    public static String signupUrl() {
        return signup();
    }

    /**
     * May an unasked nudge be said to him now (at most one in ten minutes, §5)? Taking it starts the ten minutes,
     * so every nudge, from any feature, shares the one cap.
     */
    public static boolean takeNudge(ServerPlayer p) {
        Seen s = SEEN.getOrDefault(p.getUUID(), new Seen(role(p), "", 0L, 0));
        if (!Crafters.mayNudge(s.nudgedAt(), System.currentTimeMillis())) {
            return false;
        }
        touch(p, true);
        return true;
    }

    private static String signup() {
        String s = WorldCrafters.current().signup();
        return s.isBlank() ? "https://moddythewishwatch.com" : s;
    }

    /** A Crafter's ready-made wish was served: the 1st and every 5th may carry a nudge, ten minutes apart. */
    public static void afterReadyMade(ServerPlayer p) {
        if (!isCrafter(p)) {
            return;
        }
        Seen s = SEEN.getOrDefault(p.getUUID(), new Seen(Crafters.Role.CRAFTER, "", 0L, 0));
        int made = s.made() + 1;
        long now = System.currentTimeMillis();
        boolean say = Crafters.nudgeAfterMade(made) && Crafters.mayNudge(s.nudgedAt(), now);
        SEEN.put(p.getUUID(), new Seen(s.role(), s.rank(), say ? now : s.nudgedAt(), made));
        if (say) {
            p.sendSystemMessage(Component.literal(Crafters.AFTER_MADE).withStyle(ChatFormatting.GRAY)
                    .append(link(signup())));
        }
    }

    private static void touch(ServerPlayer p, boolean nudged) {
        Seen s = SEEN.getOrDefault(p.getUUID(), new Seen(role(p), "", 0L, 0));
        SEEN.put(p.getUUID(), new Seen(s.role(), s.rank(), nudged ? System.currentTimeMillis() : s.nudgedAt(), s.made()));
    }

    // ── the pause menu ───────────────────────────────────────────────────────────────────────────

    /** The world's Server Links for this player (the ✪ one only to a Crafter). Pure but for the read. */
    static List<ServerLinks.Entry> links(WorldCrafters w, boolean crafter) {
        List<ServerLinks.Entry> out = new ArrayList<>();
        for (WorldCrafters.Link l : w.links()) {
            if (l.crafters() && !crafter) {
                continue;
            }
            try {
                out.add(ServerLinks.Entry.custom(Component.literal(l.label()), URI.create(l.url())));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("crafters: a link that is not a URL was skipped: {}", l.url());
            }
        }
        return out;
    }

    static void sendLinks(ServerPlayer p) {
        WorldCrafters w = WorldCrafters.current();
        List<ServerLinks.Entry> entries = links(w, isCrafter(p));
        if (!entries.isEmpty()) {
            p.connection.send(new ClientboundServerLinksPacket(new ServerLinks(entries).untrust()));
        }
    }

    // ── the events ───────────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        greet(p);
    }

    /**
     * #82: once at server start, whether this world's access settings could be read, said in ONE warning line when
     * they could not (or the door is open with the server's allow list off). A warning only: nothing changes.
     * {@link WorldAccess#current()} keeps the answer for a server plug-in's health report.
     */
    @SubscribeEvent
    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        String id = com.moddingfromamod.world.WorldFacts.manifestId();
        WorldAccess a = WorldAccess.classify(id, () -> com.moddingfromamod.world.WorldFacts.manifestText(id),
                event.getServer().getPlayerList().isUsingWhitelist());
        WorldAccess.publish(a);
        if (a.warns()) {
            LOGGER.warn(a.line());
        }
    }

    /** What a joining player is sent: the pause menu's links, and a Crafter's welcome. Public for a client test. */
    public static void greet(ServerPlayer p) {
        sendLinks(p);
        Crafters.Role r = role(p);
        WorldCrafters.Rank rk = r == Crafters.Role.CRAFTER ? rank(p) : null;
        SEEN.put(p.getUUID(), new Seen(r, rk == null ? "" : rk.name(), 0L, 0));
        if (r == Crafters.Role.CRAFTER) {
            p.sendSystemMessage(Component.literal(Crafters.welcome(Crafters.title(r, rk))).withStyle(ChatFormatting.AQUA));
        }
    }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        ApprenticeService.forget(event.getEntity().getUUID());   //: his page, his words, his toggle's cache
        SEEN.remove(event.getEntity().getUUID());
    }

    /** A rank earned by playing is said at once, and the watch says it. */
    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && isCrafter(p)) {
            check(p);
        }
    }

    /** Once a second: a seat given while he plays (Crafter → Magician) is said, and the watch follows. */
    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0 || !WorldCrafters.current().open()) {
            return;
        }
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            check(p);
        }
    }

    /** Compare what he is now with what he was; say what changed. Public so a GameTest drives the tick's path. */
    public static void check(ServerPlayer p) {
        Seen was = SEEN.get(p.getUUID());
        Crafters.Role r = role(p);
        WorldCrafters.Rank rk = r == Crafters.Role.CRAFTER ? rank(p) : null;
        String rankName = rk == null ? "" : rk.name();
        if (was == null) {
            SEEN.put(p.getUUID(), new Seen(r, rankName, 0L, 0));
            return;
        }
        if (was.role() == r && was.rank().equals(rankName)) {
            return;
        }
        SEEN.put(p.getUUID(), new Seen(r, rankName, was.nudgedAt(), was.made()));
        if (was.role() == Crafters.Role.CRAFTER && r == Crafters.Role.MAGICIAN) {
            p.sendSystemMessage(Component.literal(Crafters.PROMOTED).withStyle(ChatFormatting.LIGHT_PURPLE));
            LOGGER.info("crafters: {} is a Wish Magician now", p.getScoreboardName());
            sendLinks(p);
        } else if (r == Crafters.Role.CRAFTER && !rankName.isEmpty() && !was.rank().equals(rankName)) {
            List<WorldCrafters.Rank> ranks = WorldCrafters.current().ranks();
            int i = ranks.indexOf(rk);
            String next = i >= 0 && i + 1 < ranks.size() ? ranks.get(i + 1).name() : "";
            p.sendSystemMessage(Component.literal(Crafters.rankUp(Crafters.title(r, rk), next))
                    .withStyle(ChatFormatting.GOLD));
        }
        WishCharge.get().showBalance(p);
    }

    /** Forget a test's players. */
    public static void forgetForTest(UUID id) {
        SEEN.remove(id);
    }
}
