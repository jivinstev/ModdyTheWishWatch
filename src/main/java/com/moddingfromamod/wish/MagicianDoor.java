package com.moddingfromamod.wish;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>May the Magician be offered to this player?</b> The one answer every page, tile, tooltip and chat line asks before
 * it offers a Magician wish as the next step (#78): the Apprentice's no-fit and "✪ Moddy wishes it from scratch"
 * tiles, the re-wish reader's "✪ Ask the Magician", the 📖 page's "Typed wishes" toggle, the watch's "✍ Wish for
 * something new" tooltip, a thing's "Wish about this" and the wish box's lines. (The sign-up lines a Crafter sees
 * about becoming a Wish Magician are not an offer of a Magician wish and stay as they are.)
 *
 * <p>The Magician is offered only where a wish sent to him would reach him: Magician mode is on (or its check is
 * running, or a developer forced the pool: {@code Magician.allowsAi}), or a plug-in says this player is always a
 * Magician, AND the player is a Wish Magician on this world, not a Crafter. That is exactly when
 * {@link WishRoute#choose} sends a {@link WishRoute.Hint#MAGICIAN} wish to the agent, so an offer can never promise
 * what the route will not do. With the Magician off every typed wish is the Apprentice's, and a player is pointed at
 * the Builder or at other words instead; the host may be pointed at the setup ({@link #hostMayTurnOn}).
 */
public final class MagicianDoor {

    private MagicianDoor() {
    }

    /** Is there a Magician to answer this player's wishes at all (whatever his role)? */
    public static boolean on(UUID player) {
        return com.moddingfromamod.config.Magician.allowsAi() || (player != null && AlwaysMagician.anyone(player));
    }

    /** May a Magician wish be offered to this player, as something that will work? */
    public static boolean offered(ServerPlayer p) {
        return p != null && offered(on(p.getUUID()), CrafterService.role(p));
    }

    /** Pure: the rule itself, the same one {@link WishRoute#choose} applies to a {@code Hint.MAGICIAN} wish. */
    public static boolean offered(boolean magicianOn, Crafters.Role role) {
        return WishRoute.choose(magicianOn, role, false, WishRoute.Hint.MAGICIAN, false) == WishRoute.Route.MAGICIAN;
    }

    /**
     * <b>For a wish already made, put back in line</b> (#81): does the Magician still answer its player? The same
     * decision a fresh wish gets ({@link #offered}), asked by id, so a player who has left is read from the server's
     * lists. With no server or no player there is nothing to ask, and nothing changes from before: true.
     */
    public static boolean answersNow(net.minecraft.server.MinecraftServer server, UUID player) {
        if (server == null || player == null) {
            return true;
        }
        return offered(on(player), CrafterService.role(server, player));
    }

    /** What a child hears when a wish of theirs is no longer the Magician's to make (#81). */
    public static final String NOT_THE_MAGICIANS = "✨ The Magician can't make that wish for you now. Wish it again and "
            + "Moddy's Apprentice will help, or open the 🛠 Wish Builder on your watch!";

    /** The book's note on such a wish. */
    static final String TURNED_BACK_NOTE = "The Magician no longer answers this player, so the wish was not handed back to a worker.";

    /**
     * End a wish that was about to go back in line to a Magician who no longer answers its player (#81), the way the
     * safety layer ends one at the plan step: the run stopped, the book REJECTED with a note, its bar down, and the
     * child told in one line if they are here. Server thread. Never queued, so no worker is woken for it.
     */
    public static void turnBack(net.minecraft.server.MinecraftServer server, Wish wish, String where) {
        if (server == null || wish == null || wish.id() == null) {
            return;
        }
        com.moddingfromamod.marshal.Marshal marshal = com.moddingfromamod.marshal.Marshal.global();
        marshal.cancel(wish.id(), com.moddingfromamod.marshal.CancelMode.STOP, "", "");
        WishBook.get(server).complete(wish.id(), WishStatus.REJECTED, TURNED_BACK_NOTE);
        if (wish.playerId() != null) {
            WishCancel.clearHud(wish.playerId(), wish.id(), marshal);
            ServerPlayer p = server.getPlayerList().getPlayer(wish.playerId());
            if (p != null) {
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal(NOT_THE_MAGICIANS)
                        .withStyle(net.minecraft.ChatFormatting.AQUA));
            }
        }
        org.slf4j.LoggerFactory.getLogger(MagicianDoor.class).info(
                "wish {}: not handed back to a worker at {}: the Magician no longer answers its player (#81)", wish.shortId(), where);
    }

    /** The Magician is off and this player is the one who may turn it on (the watch's "✪ Turn on the Magician"). */
    public static boolean hostMayTurnOn(ServerPlayer p) {
        return p != null && !on(p.getUUID()) && com.moddingfromamod.config.MagicianSetup.watchTile(p)
                .filter(com.moddingfromamod.config.MagicianPresets.TURN_ON::equals).isPresent();
    }
}
