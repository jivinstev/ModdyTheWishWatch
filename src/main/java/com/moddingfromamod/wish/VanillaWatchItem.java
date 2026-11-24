package com.moddingfromamod.wish;

import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.item.WishItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

// ── TRANSITIONAL — watch spike. Not production. See docs/VANILLA_WATCH_SPIKE_RESULTS.md

/**
 * <b>Moddy, as something a vanilla client can hold and right-click.</b>
 *
 * <p>{@code WishWatchItem} is {@code moddingfromamod:wish_watch}: an id a vanilla client does not
 * have, whose {@code use} opens a screen <b>client-side only</b>, so on a client with no mod it is
 * both unrenderable and inert. Both halves have to be replaced, and the item spike already built
 * the machinery for both — so this <b>rides the existing carrier rather than inventing a second
 * one</b> ({@code docs/VANILLA_CARRIER_TRANSITIONAL.md}):
 *
 * <ul>
 *   <li>the ID and the ARTWORK come from {@link VanillaCarrier} plus {@code custom_model_data},
 *       which the items spike measured as byte-identical to the mod's own icon on a client fed the
 *       pushed pack;</li>
 *   <li>the RIGHT-CLICK comes from {@code VanillaCarrierAbilities}' existing
 *       {@code PlayerInteractEvent.RightClickItem} bridge, which fires server-side for any carrier
 *       before the item's own code — the same hook the {@code held} shape already uses.</li>
 * </ul>
 *
 * <p>So the watch needs no new carrier, no new bridge and no new artwork pipeline. What it adds is
 * one mark and one branch.
 *
 * <h2>Why it is marked, and not detected by shape</h2>
 *
 * <p>The item spike's deepest bug, five times over, was a predicate that could not tell <i>"we
 * substituted this"</i> from <i>"the child asked for exactly that"</i> — and the fix was to stamp
 * the intent at the one moment it is known (§4, RESOLVE LAST). A watch is the same problem: a child
 * could perfectly well wish for a magic horse armour called Moddy, and nothing about the components
 * would separate it from ours. {@link WishItem#WATCH} is stamped here, at mint time, and read
 * nowhere else.
 *
 * <h2>How he gets one</h2>
 *
 * <p>Given on join, once, and only while the route is on. That is not a flourish: the panel is the
 * zero-typing path, and a child who cannot type cannot type {@code /wish} to reach it either — so a
 * watch he has to be handed by a grown-up is not a zero-install path, it is a supervised one.
 */
@EventBusSubscriber
public final class VanillaWatchItem {

    /**
     * <b>WHITE, and that is not a colour choice — it is what makes the artwork survive.</b>
     *
     * <p>Vanilla's leather handler is {@code tintIndex > 0 ? -1 : DyedItemColor.getOrDefault(stack,
     * -6265536)}, so <b>layer 0 is always multiplied by {@code dyed_color}</b> and there is no way
     * to opt out — an absent component just means the default brown. The wish icon library is drawn
     * FOR that (greyscale body on layer 0, outline on layer 1), so a wished cup wants a real colour.
     * Moddy's sprite is finished full-colour art on layer 0, and any tint but white would repaint
     * it — which is exactly the failure that would look deliberate.
     *
     * <p><b>⚠ RE-DECIDED, and the premise this comment used to rest on was false.</b> It
     * said the unpacked case was "a fallback nobody should reach — the route pushes the pack as
     * required", and neither half was true: <b>nothing pushed an item pack at all</b> until
     * {@code vanilla/ItemPackService}, and the one push that did happen (the rig pack) passed
     * {@code required=false} at the time. So the fallback nobody should reach is what every stock
     * child reached, every time, and the colour had been chosen against a guarantee that was never
     * wired.
     *
     * <p>The pack is delivered now, and the answer is still white — but for a reason rather than
     * for an assumption, and the alternative was measured rather than waved away:
     *
     * <ul>
     *   <li><b>The choice is CONSTRAINED, not free.</b> A tint multiplies layer 0 and Moddy's
     *       sprite is finished full-colour art on layer 0, so <i>every</i> non-white tint repaints
     *       him in the packed case — which is now the ordinary case. Trading the normal case for
     *       the degraded one is the wrong way round.</li>
     *   <li><b>White is not the least identifiable option, which is the thing to check before
     *       "improving" it.</b> Vanilla's default for an untinted leather item is
     *       {@code -6265536}, i.e. the brown every real horse armour in a child's world already is
     *       ({@code RigTint.LEATHER_DEFAULT}). White is a colour no dungeon drop has.</li>
     *   <li><b>And the colour is not what makes him findable when unpacked anyway.</b> The custom
     *       name, the aqua styling, the lore line and the enchantment glint below all travel as
     *       components and render with no pack at all. Unpacked he is a shimmering white thing
     *       called "Moddy" — recognisable by every channel except the picture.</li>
     * </ul>
     *
     * <p><b>Both halves of the old premise are true now, and it is worth being exact about which
     * half.</b> An item pack IS pushed, and it is pushed as REQUIRED
     * ({@code PackDelivery.REQUIRED}) — so the unpacked case can no longer be reached by a child
     * DECLINING, which is the way it was reached in practice. It is still reachable by a pack that
     * cannot be FETCHED, because vanilla's disconnect is gated on {@code DECLINED} and not on
     * {@code FAILED_DOWNLOAD} — deliberately, so an unanswered hosting question (see
     * {@code PackHostConfig}) degrades to a wrong picture rather than to a child who cannot play.
     *
     * <p>So the degraded case is now rare rather than universal, and it is still improved by
     * delivery and not by the tint. Left white deliberately; do not "fix" it to blue without
     * re-reading the first bullet.
     */
    private static final int TINT = 0xFFFFFF;

    private VanillaWatchItem() {
    }

    /** A watch, minted as a carrier. */
    public static ItemStack stack() {
        ItemStack s = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getValue(net.minecraft.resources.Identifier.parse(VanillaCarrier.ITEM_ID)));
        // HIS OWN ARTWORK, not a nearest miss out of the wish library. This used to be the
        // library's `wand`, which is a picture of a magic wand — a perfectly good icon for a wish
        // and not a picture of Moddy. The number is reserved on the carrier and the model behind it
        // is models/item/wish_watch.json, the same file the modded client renders, so the two
        // routes show a child the same object.
        // And on 1.21.4 his own item_model too, so the picture no longer depends on the
        // carrier's number table; the number stays for everything that keys on it.
        com.moddingfromamod.compat.ModelData.stamp(s, VanillaCarrier.WATCH_MODEL_DATA);
        com.moddingfromamod.compat.ItemComponents.dyeHidden(s, TINT);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Moddy")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withItalic(false)));
        s.set(DataComponents.LORE, new ItemLore(java.util.List.of(
                Component.literal("Use it to make a wish!")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)))));
        // IT SHIMMERS. WishWatchItem overrides isFoil() -- item CODE, which a vanilla client does
        // not have -- and the substitute for it is a data component, which travels for free.
        // Found by filling the parity inventory rather than by the axis checklist: that list
        // enumerated what a CHEST MENU natively means and never asked what the WATCH ITEM already
        // meant, which is §3.B.10's own failure shape in a costume I did not expect.
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        // 🔴 AND IT IS NOT ARMOUR. The carrier is leather horse armour, which contributes +3 armor
        // on the BODY slot with show_in_tooltip=true — so without this Moddy's tooltip reads
        // "When equipped: +3 Armor" under "Wish for something new!", both in the panel and in the
        // child's own hotbar. Exactly the axis the methodology's §3.B.10 checklist names, and the
        // exact bug the item route already fixed for wished things; the watch is a carrier too and
        // nothing carried the fix across. Found by LOOKING AT THE PHOTOGRAPH — every gate was green
        // over it, because "the cell is labelled" and "the cell says nothing untrue" are different
        // questions.
        //
        // ⚠ NON-EMPTY, and that is the whole trick, read out of NeoForge rather than assumed:
        // IItemStackExtension.getAttributeModifiers does
        //     if (component.modifiers().isEmpty()) component = item.getDefaultAttributeModifiers()
        // so an EMPTY component falls straight through to the carrier's +3 armor. One inert entry
        // (amount 0, on a slot a player cannot use) is what makes the component win. Same shape as
        // MakeItemTool's `carrier_inert`, deliberately — one trick, written twice because the two
        // routes build their stacks in different languages, and pinned by a gate on both.
        s.set(DataComponents.ATTRIBUTE_MODIFIERS, inertArmour("watch_inert"));
        com.moddingfromamod.compat.ItemComponents.hideLine(s, DataComponents.ATTRIBUTE_MODIFIERS);
        // ONE CLICK, ONE HAND: without this a stock client's right-click on Moddy
        // also used his OFFHAND — a backpack emptied onto the ground. See
        // Consumables.stopsTheClick for why a never-ending, silent "consumable" is the stop.
        com.moddingfromamod.compat.Consumables.stopsTheClick(s);
        CompoundTag tag = new CompoundTag();
        // Both marks. CARRIER is what the existing bridge gates on and what stops it being put on a
        // horse; WATCH is what says this particular carrier opens the panel.
        tag.putBoolean(WishItem.CARRIER, true);
        tag.putBoolean(WishItem.WATCH, true);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    /**
     * One inert armour entry, so a carrier's tooltip stops saying "+3 Armor" — see the note in
     * {@link #stack}: an EMPTY component would fall through to the carrier's default.
     */
    public static ItemAttributeModifiers inertArmour(String id) {
        return new ItemAttributeModifiers(
                java.util.List.of(new ItemAttributeModifiers.Entry(
                        net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,
                        new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                                net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                        com.moddingfromamod.ModdingFromAModMod.MODID, id),
                                0.0D,
                                net.minecraft.world.entity.ai.attributes.AttributeModifier
                                        .Operation.ADD_VALUE),
                        net.minecraft.world.entity.EquipmentSlotGroup.BODY)));
    }

    /**
     * True if this stack is a watch we minted.
     *
     * <p>Asked of 41 slots per child per tick since the pin holds him to slot 1, so it answers the
     * common case — no mark at all — with {@code contains}, and only copies the tag for a stack
     * that carries the key. The copy is what {@code copyTag()} costs: a whole CompoundTag, per slot,
     * per tick.
     */
    public static boolean isWatch(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(WishItem.WATCH)
                && data.copyTag().getBooleanOr(WishItem.WATCH, false);
    }

    /**
     * Hand him one on join, if the route is on and he has not got one.
     *
     * <p>Checked rather than counted: a second Moddy in the inventory is clutter, and a child who
     * threw his away and rejoined should get another. Inert with the route off, which is what
     * {@code VanillaWatchTest} pins.
     */
    @SubscribeEvent
    public static void onJoin(net.neoforged.neoforge.event.entity.player.PlayerEvent
            .PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (has(player)) {
            return;
        }
        // ⚠ THROUGH HandOver, which asks for room FIRST. The obvious line -- add-and-drop-if-it-
        // returned-false -- is a recorded defect in this repo, and the sweep caught my copy of it:
        // for a CREATIVE player, the only mode he plays in, that call returns TRUE having moved
        // nothing, so the fallback never fires and Moddy silently does not exist. On a full hotbar
        // that would have shut the only zero-typing door on exactly the child this route is for,
        // with every gate green.
        //
        // (And then the sweep flagged this comment, because it is a text match and my first
        // wording quoted the banned token verbatim. Recorded rather than tidied away: a sweep that
        // reads comments is a sweep that cannot be talked out of, which is the right trade.)
        com.moddingfromamod.item.HandOver.toPlayer(player, stack());
    }

    /**
     * <b>AND ON RESPAWN, which is the case a login hook cannot reach.</b>
     *
     * <p>A child who dies drops everything, and {@code PlayerLoggedInEvent} does not fire when he
     * comes back — so without this the ONE door to the zero-typing path is gone until he next
     * restarts the game, which is not a thing a seven-year-old will connect to having died. This is
     * the same check on the other lifecycle edge rather than a second policy.
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent
            .PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!has(player)) {
            com.moddingfromamod.item.HandOver.toPlayer(player, stack());
        }
    }

    /**
     * <b>AND ON A TIMER, because losing him is the case a lifecycle hook cannot reach at all.</b>
     *
     * <p>Join and respawn cover the two moments the game hands a player back to himself. They do
     * not cover the middle of an afternoon: dropped in lava, thrown off a cliff, tidied into a
     * chest, lost in a full inventory. Before this the answer to all four was {@code /moddy} — i.e.
     * <b>typing</b>, on the one door whose entire reason for existing is that a child who cannot
     * type can still make a wish. A zero-typing path with a typed recovery step is not a
     * zero-typing path.
     *
     * <p>So Moddy is <b>topped up</b>: every {@link #TOP_UP_TICKS} ticks, any player on the route
     * without one gets one. That makes him a TOOL rather than LOOT — the same promise the recipe
     * book makes, and the same promise the panel makes, which is that the way in is always there.
     *
     * <p>⚠ <b>SINCE THIS IS THE BACKSTOP, NOT THE MECHANISM.</b> {@link
     * WatchPinService} pins him to hotbar slot 1 every tick, cancels a toss, refuses a loose watch,
     * leaves him out of death drops and takes him back out of a closed container, so the two
     * consequences below no longer happen to a child. They are kept as the record of what this
     * alone did, and this alone is still what runs for anyone the pin does not apply to.
     *
     * <p>Two consequences, both deliberate:
     *
     * <ul>
     *   <li><b>You cannot store him in a chest.</b> {@link #has} reads the player, so a Moddy put
     *       away is a Moddy you no longer have and another arrives. That is the right trade: a
     *       child who wanted him out of the way has one extra item, and a child who lost him has
     *       the door back.</li>
     *   <li><b>Dropping one on the ground makes a duplicate</b> for as long as the dropped stack
     *       lives. Bounded — {@code has} stops a second reaching the inventory — and self-clearing,
     *       because a dropped item despawns.</li>
     * </ul>
     *
     * <p>Five seconds rather than every tick: it is a scan of at most 41 slots per player, so the
     * cost is nothing either way, and the number that matters is that a child never sits looking
     * at an empty hand wondering what to do. {@code /moddy} still exists and is now a convenience
     * for a grown-up rather than the mechanism.
     */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        // ⚠ THE HANDLER'S OWN COUNTER, not the server's tick count. `getTickCount() % 100` reads
        // as the same thing and is not: it couples the cadence to a global PHASE, so calling this
        // handler N times in a row (which is exactly what a gate does — a GameTest cannot advance
        // the server's clock from inside a test) fires it either every time or never, depending on
        // where the server happened to be. Measured: the first cut of the gate for this called it
        // 101 times and got zero top-ups, and read as "the feature does not work".
        if (++ticks % TOP_UP_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!topUpApplies(player)) {
                continue;
            }
            // holdsOne, not has: a watch on his cursor or in the chest he is looking into is not
            // lost, and minting another there is a duplicate.
            if (!WatchPinService.holdsOne(player)) {
                com.moddingfromamod.item.HandOver.toPlayer(player, stack());
            }
        }
    }

    /**
     * Does the top-up apply to this player? Every real player, with no opt-in. A gate mock only when
     * a test opts it in (here, or by pinning it — {@link WatchPinService#pinMockForTest}), exactly as
     * the pin does: a mock is a real ServerPlayer in the player list, so without this every gate mock
     * on the server was handed a watch every five seconds, into whatever window its own test was
     * measuring. {@code LoadAndPourGameTest} cleared a mock, ran an undo and asserted the inventory
     * empty, and failed once in ~125 runs because a top-up landed in those few ticks — read as "the
     * undo handed a machine back", in a test whose undo was fine.
     */
    static boolean topUpApplies(ServerPlayer p) {
        return !com.moddingfromamod.seam.MockPlayers.is(p)
                || TOP_UP_MOCKS.contains(p.getUUID()) || WatchPinService.isPinnedMock(p);
    }

    /** Gate mocks the top-up applies to — see {@link #topUpApplies}. */
    private static final java.util.Set<java.util.UUID> TOP_UP_MOCKS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** For a GameTest: top this mock up as a real child is topped up. */
    public static void topUpMockForTest(ServerPlayer mock) {
        TOP_UP_MOCKS.add(mock.getUUID());
    }

    public static void stopTopUpForTest(ServerPlayer mock) {
        TOP_UP_MOCKS.remove(mock.getUUID());
    }

    /** How often the top-up looks — five seconds at 20 tps. */
    public static final int TOP_UP_TICKS = 100;

    /** Ticks seen by {@link #onServerTick}. Server-thread only, so a plain int is enough. */
    private static int ticks;

    /**
     * Does he have one anywhere?
     *
     * <p>⚠ {@code getInventory().items} is the MAIN inventory only. A Moddy in the offhand, or worn
     * in a slot, is not in that list — so scanning it alone would hand out a second one to a child
     * who was holding the first, and duplicates are how a panel of "things you made" starts lying
     * about how many you have.
     */
    public static boolean has(ServerPlayer player) {
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (isWatch(s)) {
                return true;
            }
        }
        for (ItemStack s : java.util.List.of(player.getOffhandItem())) {
            if (isWatch(s)) {
                return true;
            }
        }
        for (ItemStack s : com.moddingfromamod.compat.Equipment.armour(player)) {
            if (isWatch(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>Give him one now.</b> Backs {@code /moddy}, which is the answer to "I threw it in the lava".
     *
     * @return false when he already had one — the caller says so rather than silently duplicating
     */
    public static boolean give(ServerPlayer player) {
        if (has(player)) {
            return false;
        }
        com.moddingfromamod.item.HandOver.toPlayer(player, stack());
        // Moddy himself rides the same carrier, so a world whose watches were handed out on
        // the substituted path holds leather horse armour in its saved inventories. Recorded here
        // because `give` is the mint -- this is the moment a path-specific item enters the world.
        // TRUE, not `VanillaWatch.enabled()`. This class only ever mints the carrier -- `stack()`
        // builds a leather horse armour unconditionally -- so reading the flag here could record
        // MOD for an item that is demonstrably a carrier. The mark says what the world CONTAINS,
        // and what it contains at this line is a carrier. (There is no mod-path counterpart to
        // stamp: `ModItems.WISH_WATCH` is only ever taken out of the creative menu by hand.)
        com.moddingfromamod.lite.MintStore.stamp(player.level().getServer(), "watch", true);
        return true;
    }
}
