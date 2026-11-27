package com.moddingfromamod.wish;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.moddingfromamod.plugin.Plugins;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>The optional wish-charge hook</b> (epic "§4 decided"): what a wish COSTS, if anything.
 *
 * <p>The public mod grants every wish for free and says nothing about prices, credits or budgets. A private
 * plug-in (the wish bank) registers one implementation with {@link Plugins#add}; the core asks
 * {@link #get()} at the few places a wish is priced (the box, the watch, the guide), checked as affordable
 * (submit), charged (pickup, a recipe, a Builder make, an "again") and settled (the book's one status move).
 *
 * <p>Every method's DEFAULT is the public mod's behaviour: free, and silent. So with nothing registered
 * {@link #get()} answers {@link #NONE} and every call is a no-op that returns "free".
 *
 * <p>Thread notes are the bank's: submit, settle and the "again" calls are on the server thread; the pickup
 * charge may be on an agent thread (an implementation must be safe there).
 *
 * <p><b>A documented extension point</b>: {@code docs/EXTENDING.md} → "WishCharge".
 */
public interface WishCharge {

    /** No charge in force: the public single-player mod. */
    WishCharge NONE = new WishCharge() {
    };

    /**
     * This world's charge: the running world module's own ({@code WorldModule.charge()},: a level prices only
     * its own worlds), else the registered one, else {@link #NONE}.
     */
    static WishCharge get() {
        com.moddingfromamod.world.WorldModule m = com.moddingfromamod.world.WorldModuleHost.liveModule();
        WishCharge own = m == null ? null : m.charge();
        WishCharge world = own != null ? own : Plugins.shared().first(WishCharge.class).orElse(NONE);
        // #182: a region the player stands in (or the wish was made in) answers first; none registered, nothing changes
        return com.moddingfromamod.world.Regions.any() ? new RegionalCharge(world) : world;
    }

    /** The online player with this id, or null (no server, or he left). */
    private static ServerPlayer online(UUID id) {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null || id == null ? null : server.getPlayerList().getPlayer(id);
    }

    /** True when a plug-in has registered a charge (whether or not THIS world prices wishes). */
    static boolean registered() {
        return get() != NONE;
    }

    /** The three "again" presses: a copy of a thing, one part re-wished, the whole wish re-wished. */
    enum Again {
        GIVE_ONE, PART, WHOLE;

        /** Which kind a {@code /moddy again} recipe id is: a {@code <wish>@<part>} id is a PART. */
        public static Again ofRecipe(boolean isPart) {
            return isPart ? PART : WHOLE;
        }
    }

    /**
     * What a charge did, as far as the core needs to know: refused (nothing to pay with; nothing was spent and the
     * caller must start nothing), with the line to say to the child, computed when asked.
     */
    record Paid(boolean refused, Supplier<String> refusalLine) {

        /** Free: nothing charged, nothing refused. */
        public static final Paid FREE = new Paid(false, () -> "");

        /** The refusal line, or {@code ""}. */
        public String line() {
            String s = refusalLine == null ? null : refusalLine.get();
            return s == null ? "" : s;
        }
    }

    /**
     * An "again" press charged BEFORE anything is handed over. {@code token} is the implementation's own record of
     * it, handed back to {@link #afterAgain} or {@link #refundAgain}; the core never reads it.
     */
    record AgainPaid(boolean refused, Object token) {

        /** Free: nothing charged, nothing refused. */
        public static final AgainPaid FREE = new AgainPaid(false, null);
    }

    // ── what is in force: the words a menu or a book shows ──────────────────────────────────────

    /** Does this world price wishes for this player? False in the public mod. */
    default boolean priced(UUID player) {
        return false;
    }

    /** The price of one wish in whole wishes, for the guide page; {@code 0} when wishes are free. */
    default int wishPrice() {
        return 0;
    }

    /** Placeholder values for the world's guide book (its bank page), or an empty map: the page is left out. */
    default Map<String, String> guideValues() {
        return Map.of();
    }

    /** The watch panel's title for this player: {@code base}, or with a balance added. */
    default String panelTitle(String base, UUID player) {
        return base;
    }

    /** The wish box's title for this player: {@code base}, or with a balance added. */
    default String composeTitle(String base, UUID player) {
        return base;
    }

    /** {@code /moddy wishes}: what to say. */
    default List<String> wishesCommand(UUID player) {
        return List.of("✨ Wish away!");
    }

    /** Bring this player's standing display of the balance up to date (his rank changed, say). */
    default void showBalance(ServerPlayer player) {
    }

    /** The price added to a button's name before he presses {@code kind} (e.g. {@code " · uses ½ ✨"}), or {@code ""}. */
    default String preview(Again kind) {
        return "";
    }

    /** The same price as a tooltip line of its own (e.g. {@code "Uses ½ ✨"}), or {@code ""}. */
    default String loreLine(Again kind) {
        return "";
    }

    // ── submit ──────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>May this wish start?</b> Asked as a wish starts, before anything runs or opens: typed words before the
     * Apprentice's page or the re-wish reader, a Builder or Apprentice make before it is built, a recipe press, a
     * "Make another one" and a hotbar fire before the rate limiter. {@link Optional#empty()} lets it start; a value
     * refuses it, and is <b>the line the player reads</b>, said once by the core (after the safety text sweep: a line
     * that does not pass is replaced by a fixed one). {@code Optional.of("")} refuses and says nothing more: the charge
     * said its own words, as {@link #refusesAtSubmit} does, and a Builder make adds the core's default line.
     *
     * <p>{@code ask} names whose wish, where it came from, its recipe id, the kind of magic that will grant it, its
     * words.
     *
     * <p><b>Cheap, and free of side effects</b>: answering must never charge, start a rest or change anything. The core
     * may ask it more than once for one wish (again when what answers turns out different from what it first knew), and
     * a menu will ask it while it draws, once per tile per draw. Do the bookkeeping in {@link #charge(WishAsk, UUID)},
     * which runs only once the wish really starts, after the rate limiter. Server thread.
     *
     * <p>The default asks {@link #refusesAtSubmit} and maps {@code true} to {@code Optional.of("")}, so a charge
     * written before this hook behaves as it always did. A new charge implements this one.
     */
    default Optional<String> refuses(WishAsk ask) {
        ServerPlayer player = online(ask.player());
        if (player == null) {
            return Optional.empty();
        }
        return refusesAtSubmit(player, false, ask.text(), ask.namedRecipe())
                ? Optional.of("") : Optional.empty();
    }

    /**
     * At submit: refuse (and say why) when there is nothing to pay with. {@code recipe} is a wish served with no
     * model (a button, an "again", a Builder make). False = go on.
     *
     * <p>Still supported, and asked by the default {@link #refuses}; a new charge implements {@link #refuses} instead,
     * which is told what is being wished and whose answer is the line the player reads.
     *
     * <p>{@code distress} is <b>always {@code false}</b>: the core no longer reads a wish as "a child in distress".
     * The parameter stays so a charge compiled against an older release still overrides this method.
     */
    default boolean refusesAtSubmit(ServerPlayer player, boolean distress, String text, boolean recipe) {
        return false;
    }

    /**
     * <b>Removed; kept so older world jars still load; deleted in a later release.</b> The core never calls it: it
     * was asked after a wish from "a child in distress" went through on nothing, and the core no longer reads a wish
     * that way. A charge that overrides it still loads; its override simply never runs. See {@code docs/EXTENDING.md}
     * ("Removed, kept for older world jars") for when it goes.
     */
    @Deprecated(forRemoval = true)
    default void waiveIfEmpty(ServerPlayer player, UUID wishId) {
    }

    // ── rest: how long a hotbar wish waits ──────────────────────────────────────────────────────

    /**
     * How many ticks this wish ({@link WishAsk#recipe}, the id {@code /moddy again} takes) still rests for
     * {@link WishAsk#player}, counted from now; {@code 0} when it is ready; {@code -1} (the default) when this charge
     * does not say. <b>A query with no side effects</b>: answering must never start, restart or change a rest (the core
     * asks it again whenever it likes, and may ask it with no wish in flight: a proxy landing in his inventory asks about
     * the wish a fire of it would make, {@link WishAsk#replay}). The charge keeps its own rest, and still refuses a fire
     * through its other hooks.
     *
     * <p>Asked by the hotbar ({@code WishHotbar}) right after each fire of a proxy, whatever came of it, and the proxy's
     * cooldown sweep is set to the answer ({@code -1} keeps the core's one second); and whenever a proxy is put into
     * his inventory (added, given back after a knock-out, at login, after a respawn or a change of dimension), where an
     * answer above {@code 0} puts the sweep back at what is left. Server thread.
     */
    default int restLeft(WishAsk ask) {
        return -1;
    }

    // ── charge ──────────────────────────────────────────────────────────────────────────────────

    /** At the agent's pickup, after the safety and world-rules gates. */
    default Paid atPickup(Wish wish) {
        return Paid.FREE;
    }

    /** Charge a wish: {@code agent} true for one the agent grants, false for one served with no model. */
    default Paid charge(UUID player, UUID wishId, boolean agent) {
        return Paid.FREE;
    }

    /**
     * Charge a wish served with no model as it starts: a recipe, a hotbar fire, a "Make another one", a Builder or
     * Apprentice make. Asked only once nothing else can refuse it (after the rate limiter), so this is where a charge
     * does its bookkeeping: take the price, start the rest of {@link WishAsk#recipe}. The default asks
     * {@link #charge(UUID, UUID, boolean)}, so an older charge is unchanged. Server thread.
     */
    default Paid charge(WishAsk ask, UUID wishId) {
        return charge(ask.player(), wishId, ask.magic() == WishAsk.Magic.MAGICIAN);
    }

    /** Charge one "again" press that is not a wish, BEFORE anything is handed over. Server thread. */
    default AgainPaid chargeAgain(ServerPlayer player, Again kind) {
        return AgainPaid.FREE;
    }

    /**
     * {@link #chargeAgain(ServerPlayer, Again)}, told which thing: {@code ask} is "Give me one"'s
     * {@link WishAsk#replay} of the key {@code /moddy give} takes (an item's, or a creature's for its egg). The default
     * asks the method without it, so an older charge is unchanged. Server thread.
     */
    default AgainPaid chargeAgain(ServerPlayer player, Again kind, WishAsk ask) {
        return chargeAgain(player, kind);
    }

    /** After an "again" hand-over landed: say what is left. */
    default void afterAgain(ServerPlayer player, AgainPaid paid) {
    }

    /** Give an "again" charge back: the hand-over landed nothing. */
    default void refundAgain(ServerPlayer player, AgainPaid paid) {
    }

    // ── settle ──────────────────────────────────────────────────────────────────────────────────

    /**
     * 👎 undid a wish: give it back, at most once. Returns the line that says the new balance (said inside the
     * undo line), or null when nothing was given back. Call BEFORE the book moves the wish.
     */
    default String refundUndone(ServerPlayer player, Wish wish) {
        return null;
    }

    /** From the wish book's one status move (server thread): refund an ungranted charge, say what is left. */
    default void onStatus(Wish before, Wish after) {
    }
}
