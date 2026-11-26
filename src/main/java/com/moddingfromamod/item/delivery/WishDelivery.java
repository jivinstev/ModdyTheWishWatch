package com.moddingfromamod.item.delivery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moddingfromamod.bag.WishBagService;
import com.moddingfromamod.item.delivery.DeliveryPlan.Kind;
import com.moddingfromamod.item.delivery.DeliveryWords.Landed;
import com.moddingfromamod.item.delivery.DeliveryWords.Where;
import com.moddingfromamod.wish.WatchPin;
import com.moddingfromamod.wish.WatchPinService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>ONE delivery path for everything a wish gives</b>, <b>and two places it can
 * put it</b>.
 *
 * <h2>Why there has to be one</h2>
 *
 * <p>On 2026-10-03 the oasis wish's drinks, bar machine and ingredients never reached the child,
 * and on 10-02 a tracker went the same way. The path that lost them was the DESIGNED one:
 * {@code make_item} hands over with {@code wishdatapack <id> give}, which runs the pack's own
 * {@code /give} with its output suppressed — and vanilla {@code /give} into a full inventory, for a
 * Creative player, DISCARDS the stack and still reports success ({@code item/HandOver}). Raw
 * {@code give} in a wish's commands, buttons, template replays and a machine's output and refund
 * all reached vanilla {@code /give} too; only {@code give_items}, eggs and {@code /moddy give} went
 * anywhere careful. So the fix could not live in any one tool.
 *
 * <h2>The rule: if Moddy says it, it can go in the Wish Bag; nothing else can</h2>
 *
 * <p>Every give is one of two {@link Kind}s, and the kind decides where it lands
 * ({@link DeliveryPlan}) and whether Moddy says so:
 *
 * <ul>
 *   <li><b>{@link Kind#HANDOVER}</b> — the granting of a wish: {@code give_items}, {@code make_item}'s
 *       {@code wishdatapack <id> give} while the wish is in flight, the item reveal's hand-back,
 *       {@code /moddy give} and the egg, and every replay he asked Moddy for ("Give me one", a stock
 *       wish, a {@code reuse_action: give} button). The hotbar (onto a matching stack first), then
 *       the Wish Bag — never the 27 main-inventory slots. Announced; recorded for the wish's undo.
 *       The floor is reached only at the bag's 1000-page backstop, loudly.</li>
 *   <li><b>{@link Kind#BEHAVIOUR}</b> — the item's or the world's own doing: a {@code rich} or
 *       {@code convert} use (a vending machine's pull, a coffee maker), a machine's output and its
 *       refund, a world rule, a creature's ability, a datapack clock. The hotbar, then the main
 *       inventory, then the floor at his feet ({@link #dropAtHisFeet} — our own drop, his alone,
 *       no pickup delay, and a Creative pickup cannot void it). Never the bag, never a Moddy line
 *       (the item's own {@code GIVE_MESSAGE} and sound are its datapack's and stay), never taken
 *       back by an undo: a cookie the vending machine made is his, as in vanilla. This is what
 *       keeps a runaway rule from growing the bag without bound: it fills his inventory and then
 *       the floor, where vanilla merges and despawns.</li>
 * </ul>
 *
 * <p>The kind is stamped where a give ENTERS, on {@code mob.SpawnScope}, never guessed from the
 * stack: {@code WishExecutor.runOne} (the agent's {@code run_command}), a served wish's job and
 * a waiting press open a hand-over scope; {@code FunctionSpawnScopeMixin} opens a behaviour scope
 * for every datapack function nothing else scoped (an item use, a placed machine, a clock, an
 * ability); {@code rules.WorldRules} runs each rule in a behaviour scope of its own. A function
 * run INSIDE a hand-over (the made item's {@code wishdatapack <id> give}) keeps the hand-over.
 *
 * <h2>Where it sits</h2>
 *
 * <ul>
 *   <li><b>At {@code /give} itself</b> — {@code mixin/GiveCommandMixin} redirects the one
 *       {@code Inventory.add} inside {@code GiveCommand.giveItem} to {@link #give} whenever a scope
 *       is open, with the scope's kind. A grown-up's own typed {@code /give} is untouched.</li>
 *   <li><b>Called directly</b> by {@code give_items}, the egg, {@code /moddy give}, "Give me one"
 *       and the item reveal's hand-back, which build their stacks themselves — all hand-overs.</li>
 * </ul>
 *
 * <h2>What it says</h2>
 *
 * <p>Every hand-over is said ONCE, at the end of the tick, per child — so a wish that hands over
 * six things in four commands is one line, {@link DeliveryWords#forChild}. A caller with its own
 * greeting takes the words itself ({@code announce=false}). The agent's tool result carries the
 * same facts ({@link #collect}), in place of the {@code (no output)} that hid every loss.
 *
 * <h2>What undo takes back</h2>
 *
 * <p>Each hand-over made under a wish is recorded against that wish ({@link #takeBack}), and an
 * undo removes exactly that many of each thing from wherever he now has it — his hands, anywhere
 * in his inventory, the bag. A behaviour give is never recorded.
 */
@EventBusSubscriber
public final class WishDelivery {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-delivery");

    /**
     * One hand-over remembered for an undo: who, what (count 1), and how many landed in his
     * inventory and in his bag. WHERE matters: identical things are indistinguishable, so an undo
     * takes back from where the wish put them first — otherwise ten diamonds the wish put in the
     * bag would be "taken back" out of his own three in his inventory.
     */
    private record Given(UUID player, ItemStack like, int inInventory, int inBag) {
        int count() {
            return inInventory + inBag;
        }
    }

    /** Wishes remembered for an undo; the oldest is forgotten past this. */
    static final int REMEMBERED = 256;

    private static final Map<String, List<Given>> LEDGER = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Given>> eldest) {
            return size() > REMEMBERED;
        }
    };

    /** What is waiting to be said, per child, and which wish it belongs to. */
    private record Waiting(String wishKey, Landed landed) {
    }

    private static final Map<UUID, List<Waiting>> SAY = new LinkedHashMap<>();

    /**
     * When each child was last told about a hand-over NO wish key names (a reveal's hand-back, a
     * press with no wish behind it), by what was said — so the same things to the same places are
     * announced once every {@link #QUIET_TICKS}, not every time. (A machine's own output never
     * reaches here at all: it is a behaviour give, and those are never said.)
     */
    private static final Map<UUID, Map<String, Long>> SAID_UNPROMPTED = new LinkedHashMap<>();

    /** 30 seconds. */
    static final long QUIET_TICKS = 600;

    /** Wishes whose landings are said later, by whoever held them (the item reveal). */
    private static final Set<String> HELD = new HashSet<>();

    /** Open collectors on this thread (nested: each sees every landing inside it). */
    private static final ThreadLocal<List<List<Landed>>> COLLECTING = ThreadLocal.withInitial(ArrayList::new);

    /** Every landing ever, for a GameTest that wants to know what happened. Bounded. */
    private static final List<Landed> RECENT = new ArrayList<>();

    private WishDelivery() {
    }

    // ── handing over ────────────────────────────────────────────────────────────────────────

    /** Whose wish {@code wishKey} is: its record's player, else the one it is handed to. */
    static java.util.UUID makerOf(ServerPlayer player, String wishKey) {
        try {
            var wish = com.moddingfromamod.wish.WishBook.get(player.level().getServer())
                    .byId(java.util.UUID.fromString(wishKey));
            if (wish.isPresent() && wish.get().playerId() != null) {
                return wish.get().playerId();
            }
        } catch (RuntimeException notAWish) {
            // a key that is no wish's id (a test's, an event's): the one it is handed to made it
        }
        return player.getUUID();
    }

    /** Hand {@code stack} over as the running wish's: a {@link Kind#HANDOVER}, said at the end of the tick. */
    public static List<Landed> give(ServerPlayer player, ItemStack stack) {
        return give(player, stack, currentWishKey(), Kind.HANDOVER, true);
    }

    /**
     * Give {@code stack} to him. The stack is emptied by what landed.
     *
     * <ul>
     *   <li>{@link Kind#HANDOVER} — the hotbar, then the Wish Bag; recorded for the wish's undo;
     *       said at the end of the tick unless the caller says it itself.</li>
     *   <li>{@link Kind#BEHAVIOUR} — the hotbar, then his main inventory, then on the floor at his
     *       feet ({@link #dropAtHisFeet}); never the bag, never recorded for an undo, never said
     *       ({@code announce} is ignored): if Moddy does not say it, it cannot go in the bag.</li>
     * </ul>
     *
     * @param wishKey  the wish this belongs to, for its undo ({@code ""} for none)
     * @param kind     why it is arriving — from the open {@code SpawnScope}, or the caller's own
     *                 knowledge for a direct call (every direct caller is a hand-over)
     * @param announce false when the caller says it itself (use {@link DeliveryWords#where})
     * @return where each part landed
     */
    public static List<Landed> give(ServerPlayer player, ItemStack stack, String wishKey, Kind kind,
                                    boolean announce) {
        if (player == null || stack == null || stack.isEmpty()) {
            return new ArrayList<>();
        }
        com.moddingfromamod.wish.LiveFit.markEgg(stack, player);   // #174: what it hatches is held to the limits
        if (kind == Kind.BEHAVIOUR) {
            return behave(player, stack);
        }
        List<Landed> out = new ArrayList<>();
        WishedItems.markIfPlain(stack);
        // (D) a made thing says which wish made it, and whose: ids only, never his words (WishedItem). A plain gift is
        // left unmarked unless the owner's switch marks it above, so it keeps stacking with his own.
        if (wishKey != null && !wishKey.isEmpty() && WishedItems.isWished(stack)) {
            com.moddingfromamod.world.WishedItem.stamp(stack, wishKey, makerOf(player, wishKey));
        }
        ItemStack like = stack.copyWithCount(1);
        String name = stack.getHoverName().getString();
        int total = stack.getCount();
        Inventory inv = player.getInventory();

        // 1. THE HOTBAR — onto a matching stack, then a free slot; never Moddy's pinned slots.
        var plan = DeliveryPlan.plan(cells(inv, stack, 0, DeliveryPlan.HOTBAR), reserved(player),
                stack.getCount(), stack.getMaxStackSize());
        place(inv, stack, plan.hotbar(), Where.HOTBAR, name, out);
        // 2. THE WISH BAG — everything else.
        if (!stack.isEmpty()) {
            int in = WishBagService.add(player.getServer(), player.getUUID(), stack);
            if (in > 0) {
                out.add(new Landed(name, in, Where.BAG, 0));
            }
        }
        // 3. ONLY AT THE BACKSTOP (or a bag whose file was refused): at his feet, loudly, never lost.
        if (!stack.isEmpty()) {
            int n = stack.getCount();
            LOG.error("MODDY-ALERT {} of {} for {} went on the ground: his Wish Bag would not take them",
                    n, name, player.getScoreboardName());
            dropAtHisFeet(player, stack);
            out.add(new Landed(name, n, Where.FEET, 0));
        }
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();

        remember(player, like, out);
        if (wishKey != null && !wishKey.isEmpty()) {
            synchronized (LEDGER) {
                int bagged = 0;
                for (Landed l : out) {
                    if (l.where() == Where.BAG) {
                        bagged += l.count();
                    }
                }
                LEDGER.computeIfAbsent(wishKey, k -> new ArrayList<>())
                        .add(new Given(player.getUUID(), like, total - bagged, bagged));
            }
        }
        landed(out);
        if (announce) {
            synchronized (SAY) {
                List<Waiting> w = SAY.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
                for (Landed l : out) {
                    w.add(new Waiting(wishKey == null ? "" : wishKey, l));
                }
            }
        }
        if (wishKey == null || wishKey.isEmpty()) {
            LOG.debug("delivered to {} (no wish): {}", player.getScoreboardName(), DeliveryWords.forAgent(out));
        } else {
            LOG.info("delivered to {} (wish {}): {}", player.getScoreboardName(), wishKey,
                    DeliveryWords.forAgent(out));
        }
        return out;
    }

    /**
     * A {@link Kind#BEHAVIOUR} give: a vending machine's pull, a coffee maker's coffee, a
     * machine's refund, a rule, a creature's ability, a clock. The hotbar (Moddy's pins kept), then
     * the 27 main-inventory slots, then the floor at his feet — and nothing else: no bag, no line,
     * no undo record. The item's own {@code GIVE_MESSAGE} and sound are its datapack's, and stay.
     */
    private static List<Landed> behave(ServerPlayer player, ItemStack stack) {
        List<Landed> out = new ArrayList<>();
        String name = stack.getHoverName().getString();
        Inventory inv = player.getInventory();
        var plan = DeliveryPlan.planBehaviour(cells(inv, stack, 0, DeliveryPlan.HOTBAR),
                cells(inv, stack, DeliveryPlan.HOTBAR, DeliveryPlan.MAIN), reserved(player),
                stack.getCount(), stack.getMaxStackSize());
        place(inv, stack, plan.hotbar(), Where.HOTBAR, name, out);
        place(inv, stack, plan.inventory(), Where.INVENTORY, name, out);
        if (!stack.isEmpty()) {
            int n = stack.getCount();
            dropAtHisFeet(player, stack);
            out.add(new Landed(name, n, Where.FLOOR, 0));
        }
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        landed(out);
        // A machine outputs every few seconds and a rule may give every tick: never a line per item.
        LOG.debug("given to {} by a thing's own behaviour: {}", player.getScoreboardName(),
                DeliveryWords.forAgent(out));
        return out;
    }

    /** {@code count} slots of his inventory from {@code base}, as the planner sees them for {@code stack}. */
    private static DeliveryPlan.Cell[] cells(Inventory inv, ItemStack stack, int base, int count) {
        DeliveryPlan.Cell[] c = new DeliveryPlan.Cell[count];
        for (int i = 0; i < count; i++) {
            ItemStack held = inv.getItem(base + i);
            c[i] = held.isEmpty() ? DeliveryPlan.Cell.free()
                    : ItemStack.isSameItemSameComponents(held, stack)
                    ? DeliveryPlan.Cell.holding(held.getCount()) : DeliveryPlan.Cell.other();
        }
        return c;
    }

    /** Split {@code stack} into the slots the plan chose, recording each part as landed at {@code where}. */
    private static void place(Inventory inv, ItemStack stack, List<DeliveryPlan.Into> into, Where where,
                              String name, List<Landed> out) {
        for (DeliveryPlan.Into i : into) {
            ItemStack part = stack.split(i.count());
            if (i.merge()) {
                inv.getItem(i.slot()).grow(part.getCount());
            } else {
                inv.setItem(i.slot(), part);
            }
            out.add(new Landed(name, i.count(), where, where == Where.HOTBAR ? i.slot() + 1 : 0));
        }
    }

    /** Every landing, to whoever is collecting (the agent's tool result) and to the test record. */
    private static void landed(List<Landed> out) {
        for (List<Landed> c : COLLECTING.get()) {
            c.addAll(out);
        }
        synchronized (RECENT) {
            RECENT.addAll(out);
            while (RECENT.size() > 512) {
                RECENT.remove(0);
            }
        }
    }

    /** Marks an item entity this path put on the floor, so a Creative pickup cannot void it. */
    static final String DROPPED = "MfamDelivered";

    /**
     * OUR OWN floor drop: at his feet, his alone ({@code target}, as vanilla's own
     * {@code /give} overflow does), and with NO pickup delay — so it comes straight back to him the
     * moment he has room. Never {@code player.drop}, which throws it in front of him with a
     * two-second delay as if he had thrown it himself. The stack is emptied.
     */
    static void dropAtHisFeet(ServerPlayer player, ItemStack stack) {
        ItemEntity e = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(),
                stack.copy());
        e.setNoPickUpDelay();
        e.setTarget(player.getUUID());
        e.getPersistentData().putBoolean(DROPPED, true);
        player.level().addFreshEntity(e);
        stack.setCount(0);
    }

    /**
     * Vanilla's Creative pickup DELETES what it cannot fit: {@code Inventory.add} for a player with
     * infinite materials empties the stack and reports success, so an item at a Creative child's
     * feet with a full inventory would vanish the tick he touched it — the very loss fixed
     * for {@code /give}. Our own drops wait on the floor instead until he has room (or vanilla's
     * five-minute despawn takes them, which is what bounds a runaway rule). Only OUR drops:
     * everything else on the floor is vanilla's business.
     */
    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
        ItemEntity e = event.getItemEntity();
        if (!e.getPersistentData().getBooleanOr(DROPPED, false) || !event.getPlayer().hasInfiniteMaterials()) {
            return;
        }
        Inventory inv = event.getPlayer().getInventory();
        if (inv.getFreeSlot() < 0 && inv.getSlotWithRemainingSpace(e.getItem()) < 0) {
            event.setCanPickup(TriState.FALSE);
        }
    }

    /** The slots no wished thing may take: Moddy's pins, for a child they apply to. */
    static Set<Integer> reserved(ServerPlayer player) {
        Set<Integer> r = new HashSet<>();
        if (WatchPinService.applies(player)) {
            r.add(WatchPin.HOME);
        }
        if (WatchPinService.bagApplies(player)) {
            r.add(WatchPin.BAG_HOME);
        }
        return r;
    }

    /**
     * A made item that went straight into the bag never sits in his inventory, so the catalogue's
     * once-a-second inventory scan (which is how "give it to me again" and the panel learn a made
     * item) would never see it. Remembered here instead, for anything that landed in the bag.
     */
    private static void remember(ServerPlayer player, ItemStack like, List<Landed> out) {
        boolean bagged = out.stream().anyMatch(l -> l.where() == Where.BAG);
        if (!bagged || !com.moddingfromamod.item.WishItem.isOurCarrier(like)
                || com.moddingfromamod.item.WishItemCatalogue.knows(like)) {
            return;
        }
        if (com.moddingfromamod.item.WishItemCatalogue.remember(player.getServer(), like)) {
            com.moddingfromamod.net.ModdedClient.broadcastWishItems(player.getServer());
        }
    }

    /** The wish behind the command running now: the open scope's, else the bound worker's. */
    public static String currentWishKey() {
        var scope = com.moddingfromamod.mob.SpawnScope.current();
        if (scope != null) {
            return scope.wishKey();
        }
        return com.moddingfromamod.wish.WishExecutor.wishKeyOrNone();
    }

    // ── telling the agent ──────────────────────────────────────────────────────────────────

    /** Run {@code body}, collecting every landing inside it (on this thread) into {@code into}. */
    public static <T> T collect(List<Landed> into, java.util.function.Supplier<T> body) {
        List<List<Landed>> open = COLLECTING.get();
        open.add(into);
        try {
            return body.get();
        } finally {
            open.remove(open.size() - 1);
        }
    }

    // ── telling the child ──────────────────────────────────────────────────────────────────

    /** Hold this wish's landings: they are said when {@link #release} is called, not at tick end. */
    public static void hold(String wishKey) {
        if (wishKey != null && !wishKey.isEmpty()) {
            synchronized (SAY) {
                HELD.add(wishKey);
            }
        }
    }

    /** Say what was held for this wish now (on the next flush). */
    public static void release(String wishKey) {
        synchronized (SAY) {
            HELD.remove(wishKey);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        flush(event.getServer());
    }

    /** Say everything waiting and not held, one line per child. Public for a GameTest. */
    public static void flush(MinecraftServer server) {
        Map<UUID, List<Landed>> due = new LinkedHashMap<>();
        Map<UUID, Boolean> fromAWish = new LinkedHashMap<>();
        synchronized (SAY) {
            if (SAY.isEmpty()) {
                return;
            }
            for (var e : SAY.entrySet()) {
                List<Waiting> keep = new ArrayList<>();
                for (Waiting w : e.getValue()) {
                    if (HELD.contains(w.wishKey())) {
                        keep.add(w);
                    } else {
                        due.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(w.landed());
                        fromAWish.merge(e.getKey(), !w.wishKey().isEmpty(), Boolean::logicalOr);
                    }
                }
                e.setValue(keep);
            }
            SAY.values().removeIf(List::isEmpty);
        }
        long now = server == null ? 0L : server.getTickCount();
        for (var e : due.entrySet()) {
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(e.getKey());
            boolean wished = Boolean.TRUE.equals(fromAWish.get(e.getKey()));
            String line = DeliveryWords.forChild(e.getValue(), wished ? "your wish" : null);
            if (!wished && !line.isEmpty()) {
                // The same things to the same places with no wish behind them: once per QUIET_TICKS.
                StringBuilder what = new StringBuilder();
                for (Landed l : e.getValue()) {
                    what.append(l.name()).append('@').append(l.where()).append(l.slot()).append(';');
                }
                Map<String, Long> said = SAID_UNPROMPTED.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
                Long last = said.get(what.toString());
                if (last != null && now - last < QUIET_TICKS) {
                    continue;
                }
                said.put(what.toString(), now);
                if (said.size() > 64) {
                    said.remove(said.keySet().iterator().next());
                }
            }
            if (p != null && !line.isEmpty()) {
                p.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        }
    }

    // ── undo ────────────────────────────────────────────────────────────────────────────────

    /**
     * Take back everything wish {@code wishKey} handed over: from his hands, anywhere in his
     * inventory (cursor included), then his Wish Bag. Returns how many items came back.
     */
    public static int takeBack(MinecraftServer server, String wishKey) {
        List<Given> given;
        synchronized (LEDGER) {
            given = LEDGER.remove(wishKey);
        }
        if (given == null || server == null) {
            return 0;
        }
        int taken = 0;
        for (Given g : given) {
            ServerPlayer p = server.getPlayerList().getPlayer(g.player());
            // From where the wish put each part first; then the other place, for what he moved.
            int fromBag = WishBagService.take(server, g.player(), g.like(), g.inBag());
            int fromInv = p == null ? 0 : takeFromInventory(p, g.like(), g.inInventory());
            int left = g.count() - fromBag - fromInv;
            if (left > 0 && p != null) {
                left -= takeFromInventory(p, g.like(), left);
            }
            if (left > 0) {
                left -= WishBagService.take(server, g.player(), g.like(), left);
            }
            taken += g.count() - left;
            if (p != null) {
                p.inventoryMenu.broadcastChanges();
                p.containerMenu.broadcastChanges();
            }
        }
        LOG.info("undo: wish {} took back {} item(s) it had handed over", wishKey, taken);
        return taken;
    }

    /** True when this wish handed anything over (what an undo would take back). */
    public static boolean handedOver(String wishKey) {
        synchronized (LEDGER) {
            List<Given> g = LEDGER.get(wishKey);
            return g != null && !g.isEmpty();
        }
    }

    /** One thing a wish handed over, for a claim check: to whom, what (count 1), and how many. */
    public record Handed(UUID player, ItemStack like, int count) {
    }

    /**
     * What wish {@code wishKey} handed over, from the same record an undo reads — so "does he have
     * the thing THIS wish gave him" ({@code wish/ClaimWorld}) is asked of the hand-over itself,
     * never of whatever in his inventory happens to share a name. Copies; the record is not touched.
     */
    public static List<Handed> handedOverBy(String wishKey) {
        List<Handed> out = new ArrayList<>();
        synchronized (LEDGER) {
            List<Given> g = wishKey == null ? null : LEDGER.get(wishKey);
            if (g != null) {
                for (Given x : g) {
                    out.add(new Handed(x.player(), x.like().copy(), x.count()));
                }
            }
        }
        return out;
    }

    private static int takeFromInventory(ServerPlayer p, ItemStack like, int count) {
        int left = count;
        Inventory inv = p.getInventory();
        // His hand first, then everywhere else, then the cursor.
        List<Integer> order = new ArrayList<>();
        order.add(inv.getSelectedSlot());
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i != inv.getSelectedSlot()) {
                order.add(i);
            }
        }
        for (int i : order) {
            if (left <= 0) {
                break;
            }
            ItemStack s = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(s, like)) {
                int n = Math.min(left, s.getCount());
                s.shrink(n);
                left -= n;
            }
        }
        ItemStack carried = p.containerMenu.getCarried();
        if (left > 0 && ItemStack.isSameItemSameComponents(carried, like)) {
            int n = Math.min(left, carried.getCount());
            carried.shrink(n);
            left -= n;
        }
        return count - left;
    }

    // ── tests ───────────────────────────────────────────────────────────────────────────────

    /** The most recent landings (newest last), for a GameTest. */
    public static List<Landed> recentForTest() {
        synchronized (RECENT) {
            return List.copyOf(RECENT);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        synchronized (SAY) {
            SAY.clear();
            HELD.clear();
            SAID_UNPROMPTED.clear();
        }
        synchronized (LEDGER) {
            LEDGER.clear();
        }
    }
}
