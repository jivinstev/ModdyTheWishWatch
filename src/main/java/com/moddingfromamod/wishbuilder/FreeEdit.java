package com.moddingfromamod.wishbuilder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>The Apprentice's free change</b>. An Apprentice tile makes its thing straight away, with no
 * tune dialog in between, so a guess that is not quite right is fixed AFTER: the thing his LAST Apprentice make made
 * may be changed in the Builder once for nothing. It is a one-off courtesy for that one thing, so it ends at the first
 * of these:
 * <ul>
 *   <li>he uses it (the change itself);</li>
 *   <li>he makes another wish of any kind (a typed wish, a watch button, a Builder make or change, another Apprentice
 *       make): every wish that is actually created calls {@link #anotherWish};</li>
 *   <li>he leaves, or the server stops. It lives in memory only and is never saved, so it is not on the shelf that
 *       follows him between servers either.</li>
 * </ul>
 * A change to anything else is priced as it always was.
 *
 * <p>A make is in flight for some seconds, and he may wish again before it stands. So a grant names the wish that
 * made it, and is refused when another wish was created after that one: {@link #starting} notes the make's own wish,
 * and {@link #anotherWish} for any other wish clears it. Minecraft-free: {@code FreeEditTest}.
 */
public final class FreeEdit {

    private FreeEdit() {
    }

    /** The thing he may change for free, per player. */
    private static final Map<UUID, String> FREE = new ConcurrentHashMap<>();
    /** The Apprentice make in flight, per player: its wish id, until it stands or another wish comes. */
    private static final Map<UUID, UUID> STARTING = new ConcurrentHashMap<>();

    /** An Apprentice make's wish has just been created: when it stands, its thing may be free to change. */
    public static void starting(UUID player, UUID wish) {
        FREE.remove(player);
        STARTING.put(player, wish);
    }

    /**
     * Any wish was created for {@code player} (the one choke point every wish passes, the wish book). Ends the free
     * change, unless it is the Apprentice make's own wish.
     */
    public static void anotherWish(UUID player, UUID wish) {
        if (player == null) {
            return;
        }
        if (wish != null && wish.equals(STARTING.get(player))) {
            return;
        }
        FREE.remove(player);
        STARTING.remove(player);
    }

    /**
     * The Apprentice make of {@code wish} stands and made {@code thing}: it is now the one free to change. False (and
     * nothing granted) when another wish came after it.
     */
    public static boolean stood(UUID player, UUID wish, String thing) {
        if (thing == null || thing.isBlank() || wish == null || !STARTING.remove(player, wish)) {
            return false;
        }
        FREE.put(player, thing);
        return true;
    }

    /** The Apprentice make did not stand: nothing is free. */
    public static void failed(UUID player, UUID wish) {
        if (wish != null) {
            STARTING.remove(player, wish);
        }
    }

    /** Is a change to {@code thing} free for {@code player} right now? */
    public static boolean isFree(UUID player, String thing) {
        return player != null && thing != null && thing.equals(FREE.get(player));
    }

    /** Use the free change on {@code thing}: true (and spent) when it was free. */
    public static boolean take(UUID player, String thing) {
        return player != null && thing != null && FREE.remove(player, thing);
    }

    /** He left: nothing carries over. */
    public static void forget(UUID player) {
        FREE.remove(player);
        STARTING.remove(player);
    }

    /** The server stopped. */
    public static void clearAll() {
        FREE.clear();
        STARTING.clear();
    }
}
