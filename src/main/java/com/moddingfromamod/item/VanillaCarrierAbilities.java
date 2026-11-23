package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.VanillaCarrier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

// ── TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
// The interaction bridge and the catalogue sweep. If the route is abandoned this whole file goes; if it ships, the
// MOD ITEM becomes the legacy path instead. Gated.

/**
 * <b>Gives a VANILLA item the abilities a wished item has — for the spike only.</b>
 *
 * <p>Off unless {@link VanillaCarrier} is enabled. With it off this class subscribes, looks at each
 * interaction, and returns immediately, so the shipped mod is unchanged — the spike is additive
 * (see {@code docs/VANILLA_ITEM_SPIKE.md}).
 *
 * <h2>Why this can exist at all, and why the first answer was wrong</h2>
 *
 * <p>The obvious route for a vanilla client is a datapack advancement trigger, and on that route
 * the placeable shape is simply dead: {@code ServerPlayerGameMode.useItemOn} fires
 * {@code ITEM_USED_ON_BLOCK} only when the item's own {@code useOn} <b>consumed</b> the action, and
 * an inert carrier returns {@code PASS}. That is measured in
 * {@code VanillaCarrierBehaviourGameTest} and it stands.
 *
 * <p>But the Lite server <b>runs the mod</b>; only the client is vanilla. And NeoForge's own hooks
 * sit at the TOP of both interaction paths, before the item's code and unconditionally —
 * {@code CommonHooks.onItemRightClick(player, hand)} in {@code useItem}, and
 * {@code CommonHooks.onRightClickBlock(...)} as the first statement of {@code useItemOn}, which
 * also carries the block position and the clicked face. So a server-side listener sees every
 * interaction with an inert carrier, and no advancement is needed.
 *
 * <p><b>It performs no behaviour of its own.</b> Everything routes into {@link WishItemActions},
 * the same code {@link WishItem} runs, so an A/B between the two arms measures the carrier rather
 * than measuring how faithfully the behaviour was retyped.
 *
 * <h2>The two ways this goes wrong, both guarded and both tested</h2>
 *
 * <ol>
 *   <li><b>Firing twice on the mod item.</b> These events fire for {@code wish_item} too, and it
 *       already handles itself in {@code use}/{@code useOn} — so without a skip, one right-click
 *       would throw two snowballs and run the function twice. Asserted, because a doubled effect
 *       looks like an over-eager wish rather than like a bug.</li>
 *   <li><b>Cancelling an innocent interaction.</b> A player right-clicks a chest holding some
 *       ordinary item thousands of times a session. The tag is checked BEFORE calling in, and the
 *       event is cancelled only when the action was actually consumed — otherwise vanilla carries
 *       on exactly as it would have.</li>
 * </ol>
 *
 * <h2>One click, one hand </h2>
 *
 * <p>A stock client predicts every carrier's own right-click as PASS (horse armour does nothing for
 * a player), so vanilla's {@code startUseItem} goes on and uses the OFFHAND in the same click — the
 * owner's backpack emptied onto the ground every time he opened Moddy's panel. <b>When the server
 * handled the main-hand carrier's use on a click (cancelled or consumed it), the offhand half of
 * that click is refused and the client is resynced; a carrier whose use did nothing on the server
 * leaves the offhand alone. Moddy in the main hand always counts as handled.</b>
 * {@link #onOffhandUse} has the reasons, {@link #resync} what the client must be told, and
 * {@code docs/OFFHAND_CLICK_MATRIX.md} the measured table the rule was designed from.
 * An entity click a carrier refuses is resynced the same way ({@link #onEntityInteract}), because
 * the client has already put the carrier on a tamed horse.
 *
 * <p>{@code on_hit} is deliberately absent: {@link WishMeleeHit} already reads its instructions off
 * the <b>stack</b> and never asks what item it is, so it works on any carrier unchanged. Adding a
 * branch here would have been a second handler for one behaviour.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class VanillaCarrierAbilities {

    private VanillaCarrierAbilities() {
    }

    /**
     * Whether this stack is one the mod item already serves.
     *
     * <p>Asked of the ITEM rather than of the components on purpose: the components are identical
     * between the two arms — that is the whole point of the spike — so they cannot tell the arms
     * apart, and a components test here would skip both or neither.
     */
    static boolean handledByTheModItem(ItemStack stack) {
        return stack.getItem() instanceof WishItem;
    }

    /**
     * <b>Is this particular stack a wished thing riding on a vanilla item?</b>
     *
     * <p>This is the production switch, and it is deliberately a property of the STACK rather than
     * a flag on the server. With the route enabled, every leather horse armour in the world is
     * potentially ours, and wish data alone cannot say which: a wish the child BUILT ON horse
     * armour carries the same data. What separates our substitution is the {@link WishItem#CARRIER}
     * mark, stamped at give time.
     *
     * <p>Four conditions, and each one is a bug if dropped: the route must be on (or the shipped
     * mod changes behaviour), it must not be the mod item (which handles itself), it must carry
     * the mark (or his own horse armour wish is mistaken for ours), and it must carry wish data
     * (or a keepsake with no behaviour answers clicks). A wish on any OTHER real item, horse
     * armour he chose included, is {@link #isWishOnARealItem}.
     */
    public static boolean isCarrier(ItemStack stack) {
        if (!VanillaCarrier.enabled() || handledByTheModItem(stack)) {
            return false;
        }
        // THE MARK, not the item id. A wish the child built ON horse armour is the same ITEM as
        // our substitution and carries the same behaviour tags, so the id cannot tell them apart —
        // and running his horse armour's abilities here while an identical golden-axe wish does
        // nothing is a rule nobody could predict. WishItem.CARRIER is stamped at give time, which
        // is the only moment the intent is known.
        if (!WishItem.isOurCarrier(stack)) {
            return false;
        }
        // TRANSITIONAL — watch spike. Moddy carries no ability tag of its own: its whole
        // behaviour is "open the panel", which is a branch below rather than a datapack function.
        // Named here so it also inherits the entity-interact refusal — without this line a child
        // right-clicking a horse while holding Moddy puts Moddy on the horse and loses it, which
        // is the same axis the items spike found and fixed for a wished cup.
        if (com.moddingfromamod.wish.VanillaWatchItem.isWatch(stack)
                || com.moddingfromamod.bag.WishBagItem.isBag(stack)) {
            return true;                       // the Wish Bag too: it opens, never runs
        }
        return !WishItem.onUse(stack).isEmpty()
                || !WishItem.throwTag(stack).isEmpty()
                || !WishItem.placeTag(stack).isEmpty()
                || !WishItem.onHit(stack).isEmpty()
                || !WishItem.wishFeature(stack).isEmpty();
    }

    /**
     * <b>A wish built ON a real item that does something when used.</b> "A shovel that
     * turns dirt into grass" is {@code base_item minecraft:iron_shovel} with a held effect: not our
     * item and not the carrier, so until this nothing ran it — the child right-clicked and, on
     * dirt, got the shovel's own path instead. Its wish data is what marks it: a shovel found in a
     * chest carries no use function, so it is never touched.
     */
    static boolean isWishOnARealItem(ItemStack stack) {
        return VanillaCarrier.enabled() && !handledByTheModItem(stack) && !WishItem.isOurCarrier(stack)
                && (!WishItem.onUse(stack).isEmpty() || !WishItem.throwTag(stack).isEmpty());
    }

    /**
     * <b>Does this real item do something of its own when used</b> — draw, eat, drink, block,
     * throw? Then a wish on it must never take that away: the laser bow's wish
     * cancelled the draw, and a magic cookie could not be eaten. Read from
     * {@link com.moddingfromamod.datapack.ItemTriggers}, the one table make_item and E1 read too.
     */
    static boolean hasItsOwnUse(ItemStack stack) {
        return !com.moddingfromamod.datapack.ItemTriggers.NONE.equals(
                com.moddingfromamod.datapack.ItemTriggers.actionOf(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
    }

    /**
     * <b>One click, one hand: when the server handled the MAIN hand's carrier, the OFFHAND does
     * nothing.</b>
     *
     * <p>The bug, as reported 2026-09-30: <i>"when I right click with Moddy now it both throws the wish
     * watch AND wishes"</i> — with a backpack (a real bundle) in his offhand. A stock client asks
     * its OWN copy of a carrier what a right-click does, and leather horse armour does nothing for
     * a player ({@code Equippable.swapWithEquipmentSlot} → {@code canUseSlot(BODY)} / the horse-only
     * allowed entities), so its use returns PASS and {@code Minecraft.startUseItem} goes on to the
     * next hand, sending a second use for OFF_HAND in the same click. The server handled the first
     * (the panel, a wished item's ability) and then obeyed the second: a bundle's use empties it
     * onto the ground, a snowball is thrown, food is eaten. The carrier cannot make the client stop
     * at the main hand, so the server refuses the second half here, at the top of both use paths.
     *
     * <p><b>The rule, for EVERY carrier:</b> the offhand is refused when, on this click, the server
     * actually handled the main-hand carrier's use — cancelled or consumed it
     * ({@link #markHandled}, set by {@link #onRightClickItem} and {@link #onRightClickBlock}). A
     * carrier whose use did nothing on the server (a keepsake with no ability) leaves the offhand
     * to act as it always did. The packets of one click arrive together, so "this click" is its
     * main half's server tick or the next one, and ends when the next click's main half arrives
     * after this one's offhand half ({@link #refuseOffhand}).
     *
     * <p>Measured before it was written (docs/OFFHAND_CLICK_MATRIX.md): nine kinds of wished item
     * against six offhands, in a GameTest and filmed on stock 26.2 and 1.21.4. The wrong cells were
     * exactly the rows whose click the server handles while the client predicts PASS — a held wish
     * on the carrier or on a real inert item, a thrown one, a placed one at a block.
     *
     * <p><b>Moddy counts as handled whenever he is in the main hand</b>, because a click while his
     * 10-tick cooldown runs never reaches {@link #onRightClickItem} at all (vanilla checks the
     * cooldown first) — the panel's own debounce, still a click on Moddy — and the client falls
     * through to the offhand all the same. The price: nothing in the offhand is right-click-usable
     * while he HOLDS Moddy; he selects another slot to eat or to block.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onOffhandUse(PlayerInteractEvent.RightClickItem event) {
        if (refuseOffhand(event.getEntity(), event.getHand())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            resync(event.getEntity());
        }
    }

    /** The same second half, aimed at a block (a torch placed, a bundle emptied at a wall). */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onOffhandUseOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (refuseOffhand(event.getEntity(), event.getHand())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            resync(event.getEntity());
        }
    }

    /**
     * One click as the server sees it: the tick its main-hand half arrived, whether the server
     * HANDLED that half, and whether its offhand half has begun. A stock click is two packets in the
     * air (use MAIN, use OFF) and four at a block (use-on MAIN, use MAIN, use-on OFF, use OFF), sent
     * in one client tick; the next click is at least four ticks later ({@code rightClickDelay}).
     */
    private record Click(int tick, boolean handled, boolean offBegun) {
    }

    /** Player → his current click. */
    private static final java.util.Map<java.util.UUID, Click> CLICKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Called first (HIGHEST) for every use and use-on of either hand: a MAIN half after the last
     * click's offhand half — or more than a tick after it — starts a new click; an OFF half is
     * refused when this click's main half was handled.
     */
    static boolean refuseOffhand(net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand) {
        if (player.level().isClientSide() || player.getServer() == null) {
            return false;
        }
        int now = player.getServer().getTickCount();
        java.util.UUID id = player.getUUID();
        Click c = CLICKS.get(id);
        if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
            if (c == null || c.offBegun() || now - c.tick() > 1) {
                CLICKS.put(id, new Click(now, false, false));
            }
            return false;
        }
        boolean handled = c != null && c.handled() && now - c.tick() <= 1;
        if (c != null) {
            CLICKS.put(id, new Click(c.tick(), c.handled(), true));
        }
        return handled
                || com.moddingfromamod.wish.VanillaWatchItem.isWatch(player.getMainHandItem())
                || com.moddingfromamod.bag.WishBagItem.isBag(player.getMainHandItem())
                || player.containerMenu instanceof com.moddingfromamod.wish.WatchMenu
                || player.containerMenu instanceof com.moddingfromamod.bag.WishBagMenu;
    }

    /** The server handled this main-hand carrier use: the offhand half of the same click is refused. */
    static void markHandled(net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand) {
        if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && player.getServer() != null) {
            int now = player.getServer().getTickCount();
            Click c = CLICKS.get(player.getUUID());
            CLICKS.put(player.getUUID(), new Click(c == null ? now : c.tick(), true, false));
        }
    }

    /** A player who leaves takes his click with him. */
    @SubscribeEvent
    public static void onLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        CLICKS.remove(event.getEntity().getUUID());
    }

    /**
     * <b>Undo what the stock client already predicted.</b> It changed its own inventory (a snowball
     * gone, a bundle's first item out, an item on a horse) and, for an item with a use duration,
     * started USING it — a bundle keeps emptying itself on the client every other tick until told
     * to stop. The server's copy never changed, so nothing is dirty and nothing would be sent: send
     * the inventory and the using-flag anyway.
     */
    static void resync(net.minecraft.world.entity.player.Player player) {
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.moddingfromamod.compat.LivingFlags.resend(sp);
            sp.inventoryMenu.sendAllDataToRemote();
            if (sp.containerMenu != sp.inventoryMenu) {
                sp.containerMenu.sendAllDataToRemote();
            }
        }
    }

    /** Right-click in the air — the {@code held} and {@code thrown} shapes. */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        if (!isCarrier(stack) && !isWishOnARealItem(stack)) {
            return;
        }
        // TRANSITIONAL — watch spike. Moddy opens the panel instead of running a wish's function.
        // First, because a watch has no function to run and would otherwise fall through to a
        // WishItemActions.use that has nothing to do.
        if (com.moddingfromamod.wish.VanillaWatchItem.isWatch(stack)) {
            // THE SWIPE. WishWatchItem adds a 10-tick cooldown so the vanilla swing animation is
            // instant feedback and button-mashing hits it before the rate limiter has to say no.
            // Cooldowns are server-driven, so this one costs a vanilla client nothing to receive —
            // another axis the watch item already meant, found by the inventory rather than the list.
            //
            // NOT where the watch carries the one-click stamp. A stock client on
            // cooldown skips the watch's own use and goes on to the OFFHAND — filmed: open, close,
            // click again inside the 10 ticks, and his backpack emptied onto the ground. The stamp
            // already stops the click at the main hand (no swing to debounce), and a second panel
            // simply replaces the first.
            if (!com.moddingfromamod.compat.Consumables.STAMPS) {
                com.moddingfromamod.compat.Cooldowns.add(event.getEntity(), stack, 10);
            }
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp
                    && com.moddingfromamod.wish.WatchHome.openWatch(sp)) {
                event.setCancellationResult(com.moddingfromamod.compat.Interactions.success(
                        event.getLevel().isClientSide));
                event.setCanceled(true);
                markHandled(event.getEntity(), event.getHand());
            }
            return;
        }
        // THE WISH BAG opens its chest, exactly as the watch opens the panel.
        if (com.moddingfromamod.bag.WishBagItem.isBag(stack)) {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp
                    && com.moddingfromamod.bag.WishBagMenu.open(sp)) {
                event.setCancellationResult(com.moddingfromamod.compat.Interactions.success(
                        event.getLevel().isClientSide));
                event.setCanceled(true);
                markHandled(event.getEntity(), event.getHand());
            }
            return;
        }
        // THE BACKSTOP: a real item with a use of its own keeps it. make_item no longer
        // builds a click-taking wish on one, but a stack given before that still exists: its held
        // wish runs and the vanilla use CARRIES ON (never cancelled), and a thrown tag stands down
        // so the item is thrown once, as itself, rather than twice.
        if (!isCarrier(stack) && hasItsOwnUse(stack)) {
            String fn = WishItem.onUse(stack);
            if (!fn.isEmpty() && event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                WishItemActions.runFunction(sl, event.getEntity(), fn);
            }
            return;
        }
        if (WishItemActions.use(event.getLevel(), event.getEntity(), event.getHand(), stack)) {
            event.setCancellationResult(com.moddingfromamod.compat.Interactions.success(
                    event.getLevel().isClientSide));
            event.setCanceled(true);
            markHandled(event.getEntity(), event.getHand());
        }
    }

    /**
     * <b>Right-click an ENTITY: never the carrier's own vanilla meaning.</b>
     *
     * <p>The carrier is body armour, and {@code AbstractHorse.mobInteract} equips body armour on
     * right-click — so without this a child right-clicking a horse while holding his Hot Coffee
     * puts the coffee on the horse and loses it. A wished thing has no entity interaction of its
     * own, so the whole event is simply refused for one.
     *
     * <p>Refused for EVERY carrier stack, not only ones with an ability: a {@code plain} keepsake
     * is exactly as losable. And it is scoped to carriers by {@link #isCarrier}, so an ordinary
     * leather horse armour still goes on a horse the way it always did.
     */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!isCarrier(event.getItemStack())) {
            return;
        }
        event.setCancellationResult(InteractionResult.PASS);
        event.setCanceled(true);
        undoEntityPrediction(event.getEntity(), event.getTarget());
    }

    /**
     * <b>Refusing is not enough: the client already did it.</b> A stock client runs the
     * horse's own {@code mobInteract} on its side first, and for a TAMED horse that is
     * {@code equipBodyArmor}: the carrier leaves his hand and appears on the horse, and nothing the
     * server does afterwards changes a value it holds, so nothing is ever sent back — filmed, slot 1
     * stayed empty on the client, with Moddy on the horse, for as long as the film ran. Send his
     * inventory, and the target's REAL body slot.
     */
    public static void undoEntityPrediction(net.minecraft.world.entity.player.Player player,
            net.minecraft.world.entity.Entity target) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
            return;
        }
        resync(sp);
        if (target instanceof net.minecraft.world.entity.LivingEntity living) {
            sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
                    living.getId(), java.util.List.of(com.mojang.datafixers.util.Pair.of(
                            net.minecraft.world.entity.EquipmentSlot.BODY,
                            living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.BODY).copy()))));
        }
    }

    /**
     * The same, for the aimed-at-a-part variant — a horse is one entity but vanilla fires this one
     * when the click lands on a specific point. Missing it would leave the bug reachable from the
     * exact interaction a child actually performs.
     */
    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!isCarrier(event.getItemStack())) {
            return;
        }
        event.setCancellationResult(InteractionResult.PASS);
        event.setCanceled(true);
        undoEntityPrediction(event.getEntity(), event.getTarget());
    }

    /** Right-click a block — the {@code placed} and {@code machine} shapes. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        // A WISH ON A REAL ITEM, CLICKED AT A BLOCK: the wish is the right-click, so it
        // runs here and the item's own use-on-a-block (a shovel's path, a hoe's farmland) does not.
        // Without this the real item consumed the click and the air-use that runs the wish never
        // came. The carrier needs no such branch: horse armour does nothing on a block, so its
        // air-use always follows.
        if (isWishOnARealItem(stack)) {
            // A real item with its own use (a water bucket, a bow): the block click is its own, and
            // the air-use that follows is where the wish rides (onRightClickItem). Never cancelled.
            if (hasItsOwnUse(stack)) {
                return;
            }
            if (WishItemActions.use(event.getLevel(), event.getEntity(), event.getHand(), stack)) {
                event.setCancellationResult(com.moddingfromamod.compat.Interactions.success(
                        event.getLevel().isClientSide));
                event.setCanceled(true);
                markHandled(event.getEntity(), event.getHand());
            }
            return;
        }
        if (!isCarrier(stack) || WishItem.placeTag(stack).isEmpty()) {
            // No place tag: leave the world alone. WishItemActions.useOn deliberately does NOT
            // check this — the mod item places an anchor whether or not there is a tag, and that
            // behaviour must not change — so the check belongs here, where the question is
            // "is this interaction ours at all".
            return;
        }
        UseOnContext context =
                new UseOnContext(event.getEntity(), event.getHand(), event.getHitVec());
        InteractionResult result = WishItemActions.useOn(context);
        if (result.consumesAction()) {
            event.setCancellationResult(result);
            event.setCanceled(true);
            markHandled(event.getEntity(), event.getHand());
        }
    }

    /**
     * <b>Notice a wished thing on a carrier, so the agent's own tools can still find it.</b>
     *
     * <p>The mod item does this in {@code WishItem.inventoryTick}, an override it gets for free by
     * being a registered {@code Item}. A leather horse armour ticks vanilla's, so on this route
     * {@link WishItemCatalogue} was never written to at all.
     *
     * <p><b>This is not only about the creative menu.</b> The menu half genuinely cannot be
     * replicated on a vanilla client (that is the one impossibility, and it is creative-only), but
     * the catalogue has two other readers and both are server-side and both matter in survival:
     * {@code RemoveItemTool} sweeps the child's inventory with {@code knows(stack)} to take a
     * wished thing away, and {@code MakeItemTool.clearRetired} does the same for variants a
     * refinement retired. With an empty catalogue neither finds anything, and neither says so —
     * {@code RemoveItemTool} still deletes the design and forgets the name, so it reports
     * <i>"Took away 'Hot Coffee'"</i> while the coffee is still in his hotbar.
     *
     * <p><b>Gated on the carrier ITEM, deliberately not on {@link #isCarrier}.</b> That predicate
     * additionally demands wish data, which is right for an interaction — an untagged horse armour
     * must behave like horse armour — and wrong here: a {@code plain} keepsake ("a trophy that says
     * CHAMPION") carries no behaviour tag at all, and arm A remembers it, because
     * {@code WishItem.inventoryTick} hands every stack to {@code keyOf} and lets keyOf refuse the
     * unnamed ones. Reusing {@code isCarrier} would have dropped exactly the keepsakes from the
     * catalogue — the same bug again, one shape quieter.
     *
     * <p>Cadence and guard copied from {@code WishItem.inventoryTick} rather than re-chosen: once a
     * second, {@code knows} first, so an item sitting in an inventory costs a map lookup.
     *
     * <p><b>On the SERVER tick, not the player tick, and that is a deliberate choice made from
     * measurement.</b> The obvious mirror of {@code inventoryTick} is a per-player hook — but
     * {@code Player.tick()} (which is what runs {@code Inventory.tick()}, which is what runs
     * {@code inventoryTick}) is reached only through {@code ServerPlayer.doTick()}, and
     * <i>that</i> is called by {@code ServerGamePacketListenerImpl.tick()} — i.e. it is driven by
     * the player's NETWORK CONNECTION, not by the level ticking the entity. A GameTest's mock
     * player has an {@code EmbeddedChannel} that the server's connection listener never ticks, so
     * anything hung off the player tick is <b>unreachable by Gate B</b> — measured, and it cost
     * three runs reading as a broken production path. {@code ServerTickEvent.Post} comes from
     * {@code MinecraftServer.tickServer}, which runs regardless, so this is both testable and
     * independent of whether one player's connection is healthy.
     */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        // CHEAPEST TEST FIRST, and it is not micro-optimisation: VanillaCarrier.enabled() ends in
        // System.getProperty, i.e. a synchronised Hashtable lookup, and this handler runs on EVERY
        // server tick of the shipped mod whether or not the route exists. Asking the modulo first
        // makes that 20x rarer, and with the route off this whole handler is then one long
        // comparison per tick. A spike must not make the path a child already plays slower.
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server.overworld().getGameTime() % 20L != 0L || !VanillaCarrier.enabled()) {
            return;
        }
        boolean added = false;
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.isEmpty() || handledByTheModItem(stack)
                        || com.moddingfromamod.bag.WishBagItem.isBag(stack)
                        || com.moddingfromamod.wish.VanillaWatchItem.isWatch(stack)) {
                    continue;                  // the Wish Bag and the watch are Moddy's, never a
                }                              // wished thing
                // Same mark, same reason: only a stack we minted is ours to record.
                if (!WishItem.isOurCarrier(stack) || WishItemCatalogue.knows(stack)) {
                    continue;
                }
                // his bag, his thing (or its design's owner) — not "the wish in flight".
                added |= WishItemCatalogue.remember(server, stack, player.getStringUUID());
            }
        }
        if (added) {
            // Send.all, NOT PacketDistributor.sendToAllPlayers — the latter THROWS for a player
            // with no channel rather than skipping them, and on this route that player is a
            // VANILLA CLIENT, which is the whole point of the carrier. See Send.all.
            com.moddingfromamod.net.ModdedClient.broadcastWishItems(server);
        }
    }
}
