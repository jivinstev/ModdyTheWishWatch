package com.moddingfromamod.mob;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.moddingfromamod.item.delivery.DeliveryPlan.Kind;

/**
 * <b>"This creature is joining because a wish made it"</b> — the mark the world guard
 * ({@code ops.WishMobLimit}) reads at {@code EntityJoinLevelEvent}, where nothing else says why an
 * entity appeared. Pure: no Minecraft import.
 *
 * <p>Opened around the places wished things are made — every wish command
 * ({@code WishExecutor.runOne}: {@code run_command}, a button or template replay, a world rule),
 * an event's spawns, a datapack function (a mixin on the function manager) — and read on the same
 * thread when the entity joins, which is the server thread in every case. A scope that is already
 * open is reused, so a command run inside a wish keeps the wish's key.
 *
 * <p>It also carries the per-wish tally ({@link #madeBy}), so a wish that summons across four
 * {@code run_command} calls meets ONE wave — of its creatures still standing ({@link #released}), and it collects what was turned away so the command's
 * result can say so in true words ({@link #lines}).
 *
 * <p><b>And WHY a give inside it is happening</b> ({@link #kind}) — the same entry points
 * are where a {@code /give} comes from, so the scope is where it is stamped, never guessed from
 * the stack. {@link Kind#HANDOVER}: the granting of a wish — the agent's {@code run_command}
 * ({@code WishExecutor.runOne} opens one by default), a served wish, a button or template replay,
 * a stock wish, a press that waited for its pack. {@link Kind#BEHAVIOUR}: the item's or the
 * world's own doing — every datapack function nothing else scoped (an item's use, a machine's
 * output or refund, a creature's ability, a clock; {@code FunctionSpawnScopeMixin}), a world rule
 * ({@link #own}), an event. {@code item.delivery.WishDelivery} reads it.
 */
public final class SpawnScope {

    /** The key of a spawn with no wish behind it: a datapack ticking on its own. World ceiling only. */
    public static final String NO_WISH = "";

    private static final ThreadLocal<SpawnScope> CURRENT = new ThreadLocal<>();

    /** Wishes remembered for their tally; the oldest is forgotten past this. */
    static final int REMEMBERED = 256;
    private static final Map<String, int[]> MADE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
            return size() > REMEMBERED;
        }
    };

    private final String wishKey;
    private final Kind kind;
    /** What this scope admitted and dropped, per (kind, type), in the order first seen. */
    private final Map<String, int[]> made = new LinkedHashMap<>();
    private final Map<String, SpawnBudget.Stop> stopped = new LinkedHashMap<>();
    private final Map<String, SpawnBudget.Kind> kinds = new LinkedHashMap<>();

    private SpawnScope(String wishKey, Kind kind) {
        this.wishKey = wishKey == null ? NO_WISH : wishKey;
        this.kind = kind == null ? Kind.BEHAVIOUR : kind;
    }

    /**
     * Run {@code body} as wish {@code wishKey}'s doing ({@link #NO_WISH} for a datapack), its gives
     * being {@code kind}. Reuses the scope already open on this thread, if there is one — its key
     * AND its kind: a made item's {@code wishdatapack <id> give} run by a wish is that wish's
     * hand-over, though the function manager it passes through opens a behaviour scope.
     */
    public static <T> T during(String wishKey, Kind kind, Supplier<T> body) {
        SpawnScope open = CURRENT.get();
        if (open != null) {
            return body.get();
        }
        SpawnScope scope = new SpawnScope(wishKey, kind);
        CURRENT.set(scope);
        try {
            return body.get();
        } finally {
            CURRENT.remove();
        }
    }

    /**
     * Run {@code body} in a scope of its OWN, even inside another, and put the outer one back
     * after: for a world rule, which is the world's doing whatever happens to be running when it
     * fires (a wish's {@code kill} setting off an "on death" rule must not make the rule's give
     * that wish's hand-over).
     */
    public static <T> T own(String wishKey, Kind kind, Supplier<T> body) {
        SpawnScope outer = CURRENT.get();
        CURRENT.set(new SpawnScope(wishKey, kind));
        try {
            return body.get();
        } finally {
            if (outer == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(outer);
            }
        }
    }

    /** Open a scope the caller closes with {@link #close} — for a mixin's HEAD/RETURN pair. Nests. */
    public static boolean open(String wishKey, Kind kind) {
        if (CURRENT.get() != null) {
            return false;
        }
        CURRENT.set(new SpawnScope(wishKey, kind));
        return true;
    }

    /** Close what {@link #open} opened; {@code opened} is its return value. */
    public static void close(boolean opened) {
        if (opened) {
            CURRENT.remove();
        }
    }

    /** The scope open on this thread, or {@code null}: then a joining creature is nobody's wish. */
    public static SpawnScope current() {
        return CURRENT.get();
    }

    public String wishKey() {
        return wishKey;
    }

    /** Why a give inside this scope is happening: a wish's hand-over, or a thing's own behaviour. */
    public Kind kind() {
        return kind;
    }

    /** How many of {@code k} this scope's wish has made so far, or -1 when no wish is behind it. */
    public int madeByThisWish(SpawnBudget.Kind k) {
        return wishKey.isEmpty() ? -1 : madeBy(wishKey, k);
    }

    /** Record one admitted creature against this scope and its wish. */
    public void admitted(SpawnBudget.Kind k, String typeId) {
        made.computeIfAbsent(key(k, typeId), x -> new int[1])[0]++;
        kinds.putIfAbsent(key(k, typeId), k);
        if (!wishKey.isEmpty()) {
            synchronized (MADE) {
                MADE.computeIfAbsent(wishKey, x -> new int[SpawnBudget.Kind.values().length])[k.ordinal()]++;
            }
        }
    }

    /** Record one creature turned away, and why. */
    public void dropped(SpawnBudget.Kind k, String typeId, SpawnBudget.Stop why) {
        made.computeIfAbsent(key(k, typeId), x -> new int[1]);
        kinds.putIfAbsent(key(k, typeId), k);
        stopped.merge(key(k, typeId), why, (a, b) -> a == SpawnBudget.Stop.WORLD ? a : b);
    }

    /** Was anything turned away in this scope? */
    public boolean droppedAny() {
        return !stopped.isEmpty();
    }

    /** One true sentence for the child per kind of creature that was turned away. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, SpawnBudget.Stop> e : stopped.entrySet()) {
            String typeId = e.getKey().substring(e.getKey().indexOf('|') + 1);
            SpawnBudget.Kind k = kinds.get(e.getKey());
            int n = made.get(e.getKey())[0];
            out.add(e.getValue() == SpawnBudget.Stop.WORLD && n == 0
                    ? SpawnBudget.worldLine(k, typeId)
                    : SpawnBudget.waveLine(k, typeId, n));
        }
        return out;
    }

    /**
     * One creature wish {@code wishKey} made is GONE — killed, or discarded by the game (a warden
     * that burrowed, a mob that despawned) — so its wave has room for one more. The wave counts the
     * wish's LIVING creatures: without this, a boss that vanished seconds after it was summoned still
     * filled the wish's one boss slot, and the retry was refused as a full wave with nothing standing.
     * Never below zero, and a wish already forgotten stays forgotten.
     */
    public static void released(String wishKey, SpawnBudget.Kind k) {
        if (wishKey == null || wishKey.isEmpty() || k == null) {
            return;
        }
        synchronized (MADE) {
            int[] n = MADE.get(wishKey);
            if (n != null && n[k.ordinal()] > 0) {
                n[k.ordinal()]--;
            }
        }
    }

    /** How many of {@code k} wish {@code wishKey} has made, across every scope it opened. */
    public static int madeBy(String wishKey, SpawnBudget.Kind k) {
        synchronized (MADE) {
            int[] n = MADE.get(wishKey);
            return n == null ? 0 : n[k.ordinal()];
        }
    }

    /** For a test: forget every wish's tally. */
    public static void forgetAll() {
        synchronized (MADE) {
            MADE.clear();
        }
    }

    private static String key(SpawnBudget.Kind k, String typeId) {
        return k.name() + "|" + typeId;
    }
}
