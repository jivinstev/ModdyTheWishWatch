package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.item.HandOver;
import com.moddingfromamod.item.ModItems;
import com.moddingfromamod.item.NameMatch;
import com.moddingfromamod.item.WishEggItem;
import com.moddingfromamod.item.WishItemCatalogue;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * <b>"Give me that one again" — handing back a thing the child already has, rather than wishing for
 * a new one.</b>
 *
 * <p>, the server-buildable half of creative-menu findability. §1.5 splits that row in two: an
 * entry in a creative tab is <i>assembled on the client</i> and is a legitimate shim forever, but
 * <b>handing a player a stack is a server call any client accepts</b> — so this half was never a
 * vanilla gap, only unbuilt. {@link WishItemCatalogue#stack} already reconstitutes the exact stack
 * server-side from the encoded JSON the catalogue keeps; until now the only thing that read it was
 * the creative-tab hook, which a stock client never runs.
 *
 * <h2>🔴 This deliberately does NOT cost a wish — on a world with no wish bank</h2>
 *
 * <p>⚠ <b>On a world with a wish bank it costs ONE</b> (
 * {@link WishCharge.Again}): free there it was an unlimited copy of anything he
 * had ever wished for, which bypassed the bank. The charge is taken only once the thing is resolved
 * and rebuilt, immediately before the hand-over, so an unknown, ambiguous or broken request costs
 * nothing; an empty bank is refused in the bank's own words and NOTHING is handed over. Everything
 * below is about the no-bank answer, which is unchanged.
 *
 * <p>{@code /moddy again <recipe>} goes through {@link WishService#submit} because it <b>makes a new
 * thing</b> from a remembered design — a real wish, which the agent may have to run. This hands back
 * a copy of something that already exists. Routing it through {@code submit} would put a queue, a
 * progress bar and possibly an agent turn behind "can I have another one", and — the part that
 * actually matters — it would make <i>"make another one"</i> and <i>"give me mine back"</i> the same
 * button at the wrong price. They are different questions and the child asks them at different
 * moments.
 *
 * <p>It also mints nothing, so there is no {@code MintStore} stamp here: the stack is decoded
 * from what the catalogue already holds, on whichever route minted it, so the world's mode mark is
 * a fact that was recorded when the thing was first made and is not changed by a second copy.
 *
 * <h2>🔴 A sword and a dragon are ONE verb, because a child does not have two words for it</h2>
 *
 * <p>The creature half of this affordance already existed and was <b>unreachable</b>:
 * {@code /wishmob egg <key>} is {@code requires(hasPermission(2))}, i.e. a grown-up at a console.
 * §1.5 names it in exactly those terms — <i>"though at permission 2, so it is a grown-up's command
 * rather than a child's"</i> — so the give-back was built for creatures and not given to the person
 * it was for.
 *
 * <p>Both kinds resolve through one entry point rather than two commands, because "give me my
 * dragon" and "give me my sword" are the same sentence to the child saying them, and a route that
 * makes him know which registry his thing lives in has moved our filing problem onto him. Items are
 * searched first and creatures second; a query matching both is reported as ambiguous with the
 * names, exactly as two items sharing a name already are — see {@link NameMatch} for why the tiers
 * do not merge.
 *
 * <h2>Why it refuses instead of guessing</h2>
 *
 * <p>A catalogue key is {@code id:<feature>} for anything made and
 * {@code name#icon#nbt} for a keepsake made before it, so a child cannot type one — and two of his
 * things may legitimately share a name. {@link WishItemCatalogue#resolve} returns every match at the
 * tightest tier that matched, and an ambiguous answer is reported with the names rather than
 * resolved by picking one. Handing back the wrong sword silently is the failure worth avoiding.
 */
public final class WishGiveAgain {

    private WishGiveAgain() {
    }

    /**
     * The command a give-back link runs: {@code /moddy give} (it was {@code /moddy give}).
     * Built by {@link ChildCommands}, the one place a child-facing command's name is written — a
     * chat link that names a command nobody registered fails <i>silently</i> when a child clicks it.
     */
    public static final String COMMAND = "/" + ChildCommands.MODDY + " " + ChildCommands.GIVE;

    /** What happened, so a caller can report it without re-deriving it. */
    /** {@code NOT_HERE}: this world does not make that thing here, or not yet (#14); said, nothing charged or given. */
    public enum Result { GAVE, UNKNOWN, AMBIGUOUS, BROKEN, NOTHING_REMEMBERED, BANK_EMPTY, NOT_HERE }

    /**
     * Hand back one of the things this child has already been given.
     *
     * <p>Must be called on the server thread.
     *
     * @param keyOrName a catalogue key (what the chat link sends) or a name (what a child types)
     */
    public static Result give(ServerPlayer player, String keyOrName) {
        if (player == null) {
            return Result.UNKNOWN;
        }
        // BOTH registries, and this line was wrong for about a minute in the writing of it: with
        // creatures added, `count() == 0` alone tells a child with three dragons and no items that
        // "Moddy hasn't made you anything yet". A guard that predates a second source is the
        // commonest way a correct message becomes a lie.
        if (WishItemCatalogue.count() == 0 && SpeciesRegistry.count() == 0) {
            player.sendSystemMessage(say("✨ Moddy hasn't made you anything yet!",
                    ChatFormatting.GRAY));
            return Result.NOTHING_REMEMBERED;
        }
        List<WishItemCatalogue.Entry> found = WishItemCatalogue.resolve(keyOrName);
        List<Species> creatures = creatures(keyOrName);
        if (found.isEmpty() && creatures.isEmpty()) {
            player.sendSystemMessage(say("🤔 Moddy doesn't know a '" + keyOrName.trim()
                    + "'. Open Moddy to see everything you've made!", ChatFormatting.GRAY));
            return Result.UNKNOWN;
        }
        if (found.isEmpty() && creatures.size() == 1) {
            return giveEgg(player, creatures.get(0));
        }
        if (found.size() + creatures.size() > 1) {
            // NAMED, not counted. "You have 3 of those" tells a child nothing he can act on; the
            // names are what let him ask again for the right one.
            // A PICK PAGE, not chat links — a chest of the candidates on Java, a one-tap grid on a
            // phone. Each tile gives by its exact key, so pressing one can never be ambiguous again.
            List<com.moddingfromamod.ui.PickPage.Tile> tiles = new java.util.ArrayList<>();
            for (WishItemCatalogue.Entry e : found) {
                tiles.add(new com.moddingfromamod.ui.PickPage.Tile(e.key(), iconOf(e, player), "🎁 " + e.name(),
                        List.of("Press to get this one"), false));
            }
            for (Species sp : creatures) {
                tiles.add(new com.moddingfromamod.ui.PickPage.Tile(sp.id(), "minecraft:egg", "🥚 " + sp.name(),
                        List.of("Press to get its egg"), false));
            }
            if (tiles.size() > com.moddingfromamod.ui.PickPage.MAX_TILES) {
                tiles = tiles.subList(0, com.moddingfromamod.ui.PickPage.MAX_TILES);
            }
            player.sendSystemMessage(say("🤔 You've got more than one of those! Which do you mean?",
                    ChatFormatting.GRAY));
            com.moddingfromamod.ui.MenuKit.pick(player, "give_which",
                    com.moddingfromamod.ui.PickPage.of("🎁 Which one?", tiles, false),
                    (sp, key) -> {
                        sp.closeContainer();
                        give(sp, key);
                    });
            return Result.AMBIGUOUS;
        }
        WishItemCatalogue.Entry entry = found.get(0);
        ItemStack stack = WishItemCatalogue.stack(entry, player.registryAccess());
        if (stack.isEmpty()) {
            // The catalogue holds a row it can no longer decode -- a mod that supplied the base
            // item is gone, or the record is damaged. Say so rather than reporting success over an
            // empty hand, which is the shape this repo keeps writing up.
            player.sendSystemMessage(say("😕 Moddy remembers your " + entry.name()
                    + " but can't rebuild it any more.", ChatFormatting.GRAY));
            return Result.BROKEN;
        }
        return handOver(player, stack, "🎁 Here's your " + entry.name() + "! ", entry.key(), entry.name());
    }

    /**
     * Pay (on a world with a wish bank), then hand over through the ONE delivery path: hotbar, then
     * his Wish Bag — and he is told which, then what is left in his bank.
     */
    private static Result handOver(ServerPlayer player, ItemStack stack, String said, String key, String name) {
        // the world's view first (#14): a thing it hides or keeps locked here is not handed over, and nothing is charged
        WishAsk asked = WishAsk.replay(player.getUUID(), key).withText(name);
        if (MakeGate.anyWorld()) {
            String kind = SpeciesRegistry.get(key) != null ? "creature" : "item";
            var facts = MakeFactsLookup.forThing(player.level().getServer(), kind, key);
            MakeVerdict v = MakeGate.ask(facts, asked);
            if (v.makes() && !com.moddingfromamod.wishbuilder.MakeFit.satisfies(facts, v.limits())) {
                v = v.tooMuch();   // one of a kept thing: nothing of it can be made smaller here
            }
            if (!v.makes()) {
                MakeGate.say(player, v);
                return Result.NOT_HERE;
            }
        }
        // the charge is told which thing: a replay of the key /moddy give takes (an item's, or a creature's for its egg)
        var paid = WishCharge.get().chargeAgain(player, WishCharge.Again.GIVE_ONE,
                WishAsk.replay(player.getUUID(), key).withText(name));
        if (paid.refused()) {
            return Result.BANK_EMPTY;
        }
        var landed = com.moddingfromamod.item.delivery.WishDelivery.give(player, stack, "",
                com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, false);
        if (landed.isEmpty()) {
            WishCharge.get().refundAgain(player, paid);
            return Result.BROKEN;
        }
        player.sendSystemMessage(say(said
                + com.moddingfromamod.item.delivery.DeliveryWords.where(landed), ChatFormatting.LIGHT_PURPLE));
        WishCharge.get().afterAgain(player, paid);
        return Result.GAVE;
    }

    /**
     * The creatures this child could mean, by the same rule his items are matched with.
     *
     * <p>Deliberately {@link SpeciesRegistry} rather than "creatures that have been spawned": the
     * register's own point is that a thing he made is his whether or not one is standing there, and
     * {@code /wishmob egg} already resolves a sculpted-but-never-spawned creature for the same
     * reason.
     */
    private static List<Species> creatures(String keyOrName) {
        List<Species> all = SpeciesRegistry.all();
        List<NameMatch.Candidate> candidates = new ArrayList<>(all.size());
        for (Species sp : all) {
            candidates.add(new NameMatch.Candidate(sp.id(), sp.name()));
        }
        List<Species> out = new ArrayList<>();
        for (int i : NameMatch.resolve(candidates, keyOrName)) {
            out.add(all.get(i));
        }
        return out;
    }

    /**
     * Hand back a creature's egg — the same stack {@code /wishmob egg} hands a grown-up, at
     * permission 0 and reachable from the panel.
     *
     * <p>Through {@link HandOver}, never {@code Inventory.add}'s boolean: an earlier issue's egg was discarded
     * for a Creative player with a full bag while the message still said <i>"Here's your egg!"</i>.
     */
    private static Result giveEgg(ServerPlayer player, Species species) {
        ItemStack egg = WishEggItem.of(ModItems.WISH_EGG.get(), species.id());
        if (egg.isEmpty()) {
            player.sendSystemMessage(say("😕 Moddy remembers your " + species.name()
                    + " but can't make you an egg for it any more.", ChatFormatting.GRAY));
            return Result.BROKEN;
        }
        return handOver(player, egg, "🥚 Here's your " + species.name() + " egg! ", species.id(), species.name());
    }

    /** A candidate's tile picture: the thing's own item. */
    private static String iconOf(WishItemCatalogue.Entry e, ServerPlayer player) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(WishItemCatalogue.stack(e, player.registryAccess()).getItem()).toString();
        } catch (RuntimeException ex) {
            return "minecraft:chest";
        }
    }

    private static Component say(String text, ChatFormatting colour) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(colour));
    }
}
