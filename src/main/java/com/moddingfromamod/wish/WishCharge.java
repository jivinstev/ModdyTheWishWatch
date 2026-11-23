package com.moddingfromamod.wish;

import java.util.List;
import java.util.Map;
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
        return own != null ? own : Plugins.shared().first(WishCharge.class).orElse(NONE);
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
     * At submit: refuse (and say why) when there is nothing to pay with. {@code recipe} is a wish served with no
     * model (a button, an "again", a Builder make). Never refuses a child in {@code distress}. False = go on.
     */
    default boolean refusesAtSubmit(ServerPlayer player, boolean distress, String text, boolean recipe) {
        return false;
    }

    /** A wish that went through on nothing because the child was in distress: never charge it. */
    default void waiveIfEmpty(ServerPlayer player, UUID wishId) {
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

    /** Charge one "again" press that is not a wish, BEFORE anything is handed over. Server thread. */
    default AgainPaid chargeAgain(ServerPlayer player, Again kind) {
        return AgainPaid.FREE;
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
