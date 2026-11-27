package com.moddingfromamod.wish;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moddingfromamod.world.Regions;
import com.moddingfromamod.world.WorldRegion;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The charge, asked region first</b> (#182). A question about a wish goes to the charge of the region the wish was
 * made in (its own spot, locked at submit: so the region that charged it is the one that refunds it); any other
 * question about a player to the region he stands in; and to the world's own ({@code world}) when there is none or
 * the region leaves it. A question with nobody in it (the price list, a menu's preview line) is the world's. Only
 * used while some plug-in has a region ({@link WishCharge#get()}).
 */
final class RegionalCharge implements WishCharge {

    private final WishCharge world;

    RegionalCharge(WishCharge world) {
        this.world = world;
    }

    private WishCharge of(WorldRegion r) {
        WishCharge c = r == null ? null : r.charge();
        return c != null ? c : world;
    }

    private WishCharge of(UUID player) {
        return of(Regions.ofPlayer(player));
    }

    private WishCharge of(ServerPlayer player) {
        return player == null ? world : of(player.getUUID());
    }

    private WishCharge of(Wish wish) {
        return wish == null ? world : of(Regions.ofWish(wish));
    }

    private WishCharge of(WishAsk ask) {
        return ask == null ? world : of(Regions.of(ask));
    }

    /** A wish by its id: the region it was made in when the wish book knows it, else where its player stands. */
    private WishCharge of(UUID player, UUID wishId) {
        net.minecraft.server.MinecraftServer s = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        Optional<Wish> w = s == null || wishId == null ? Optional.empty() : WishBook.get(s).byId(wishId);
        return w.isPresent() ? of(w.get()) : of(player);
    }

    @Override public boolean priced(UUID player) { return of(player).priced(player); }
    @Override public int wishPrice() { return world.wishPrice(); }
    @Override public Map<String, String> guideValues() { return world.guideValues(); }
    @Override public String panelTitle(String base, UUID player) { return of(player).panelTitle(base, player); }
    @Override public String composeTitle(String base, UUID player) { return of(player).composeTitle(base, player); }
    @Override public List<String> wishesCommand(UUID player) { return of(player).wishesCommand(player); }
    @Override public void showBalance(ServerPlayer player) { of(player).showBalance(player); }
    @Override public String preview(Again kind) { return world.preview(kind); }
    @Override public String loreLine(Again kind) { return world.loreLine(kind); }
    @Override public Optional<String> refuses(WishAsk ask) { return of(ask).refuses(ask); }

    @Override
    public boolean refusesAtSubmit(ServerPlayer player, boolean distress, String text, boolean recipe) {
        return of(player).refusesAtSubmit(player, distress, text, recipe);
    }

    @Override public int restLeft(WishAsk ask) { return of(ask).restLeft(ask); }
    @Override public Paid atPickup(Wish wish) { return of(wish).atPickup(wish); }
    @Override public Paid charge(UUID player, UUID wishId, boolean agent) { return of(player, wishId).charge(player, wishId, agent); }
    @Override public Paid charge(WishAsk ask, UUID wishId) { return of(ask).charge(ask, wishId); }
    @Override public AgainPaid chargeAgain(ServerPlayer player, Again kind) { return of(player).chargeAgain(player, kind); }

    @Override
    public AgainPaid chargeAgain(ServerPlayer player, Again kind, WishAsk ask) {
        return of(player).chargeAgain(player, kind, ask);
    }

    @Override public void afterAgain(ServerPlayer player, AgainPaid paid) { of(player).afterAgain(player, paid); }
    @Override public void refundAgain(ServerPlayer player, AgainPaid paid) { of(player).refundAgain(player, paid); }
    @Override public String refundUndone(ServerPlayer player, Wish wish) { return of(wish).refundUndone(player, wish); }
    @Override public void onStatus(Wish before, Wish after) { of(after != null ? after : before).onStatus(before, after); }
}
