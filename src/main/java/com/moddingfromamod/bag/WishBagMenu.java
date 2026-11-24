package com.moddingfromamod.bag;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.item.delivery.WishedItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * <b>The Wish Bag as a vanilla chest — paged, newest first</b>.
 *
 * <p>The watch's pattern ({@code WatchMenu}): a {@link MenuType#GENERIC_9x6} chest is plain vanilla
 * protocol, so a stock client draws it with no idea a mod is involved. The difference is that the
 * watch's cells are BUTTONS and the bag's are his THINGS — so the top five rows are real slots he
 * can take from, and only the bottom row is buttons.
 *
 * <h2>Dressed, not drawn (option B)</h2>
 *
 * <p>Nothing here needs a pack: the title is a styled component ({@link WishBagWords#TITLE}, light
 * purple, and it states the rule), and the bottom row is the bag's own trim — "◀ Older wishes" and
 * "Newer wishes ▶" arrows when there is a page that way, a nether star counting the pages ("Page 2
 * of 7 · 300 things") and purple stained glass between, none of which can be picked up
 * ({@link WishBagPages#bottomRow}). Opening it chimes and sparkles for him (option C,
 * {@link #openFeel}).
 *
 * <h2>How a click becomes a change to his bag</h2>
 *
 * <ul>
 *   <li><b>Every vanilla click type runs as vanilla runs it</b> — pick up, half, place, number-key
 *       swap, shift-move, double-click gather, drag, throw — against a page of real slots. Then
 *       {@link WishBagPages#reconcile} reads what the page looks like now and writes the bag. A
 *       hand-rolled table of eight click types is how an item leaks one day; this cannot forget one.</li>
 *   <li><b>Only a wished thing goes in</b>: a bag slot's {@code mayPlace} is
 *       {@link WishedItems#isWished}, which vanilla consults on every path that places. When he tries
 *       anyway he is told why, in his words ({@link #REFUSAL}). Taking out always works.</li>
 *   <li><b>Shift-click from his own inventory</b> puts a wished thing at the FRONT of the bag, on
 *       any page — never only "wherever there is a free cell on this page".</li>
 *   <li><b>Middle-click clones in Creative</b>: refused on a bag cell, because a bag is not a
 *       creative menu and a cloned wished thing is a copy nobody made.</li>
 *   <li><b>The bottom row</b> is the watch's: a press, never a slot move, and every refusal resyncs
 *       the client, which predicted the move.</li>
 * </ul>
 */
public final class WishBagMenu extends ChestMenu {

    /** What he reads when an ordinary item will not go in. */
    public static final String REFUSAL = WishBagWords.REFUSAL;

    private static final int SIZE = WishBagPages.SLOTS;

    private final ServerPlayer owner;
    private int page;
    /** The page's cells as last laid, to see what a click changed. */
    private List<ItemStack> shown = new ArrayList<>();

    private WishBagMenu(int containerId, Inventory inv, Container container, ServerPlayer owner, int page) {
        super(MenuType.GENERIC_9x6, containerId, inv, container, 6);
        this.owner = owner;
        this.page = page;
        // Replace the chest's 54 plain slots with ours, keeping index and position.
        for (int i = 0; i < SIZE; i++) {
            Slot old = this.slots.get(i);
            Slot mine = i < WishBagPages.PER_PAGE
                    ? new BagSlot(container, i, old.x, old.y)
                    : new ButtonSlot(container, i, old.x, old.y);
            mine.index = i;
            this.slots.set(i, mine);
        }
    }

    /** Open his bag at page 1. Server thread only. */
    public static boolean open(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        SimpleContainer container = new SimpleContainer(SIZE);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            WishBagMenu m = new WishBagMenu(id, inv, container, player, 0);
            m.lay();
            return m;
        }, title()));
        openFeel(player);
        return true;
    }

    /** The chest's title: the bag's name and its rule, in light purple. */
    public static Component title() {
        return Component.literal(WishBagWords.TITLE)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false));
    }

    /**
     * Option C: an amethyst chime only he hears, and a short burst of purple sparkles at
     * his eye. The chime is {@code playNotifySound}, sent to him alone, so a friend beside him is
     * not rung every time he looks in his bag.
     */
    private static void openFeel(ServerPlayer player) {
        player.playNotifySound(net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.2F);
        if (player.level() instanceof net.minecraft.server.level.ServerLevel level) {
            var look = player.getLookAngle();
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.WITCH,
                    player.getX() + look.x * 0.8, player.getEyeY() - 0.3 + look.y * 0.8,
                    player.getZ() + look.z * 0.8, 14, 0.35, 0.25, 0.35, 0.02);
        }
    }

    /** The page on screen (0-based). */
    public int page() {
        return page;
    }

    /** The server this player is on. */
    private static MinecraftServer serverOf(ServerPlayer player) {
        return ((ServerLevel) player.level()).getServer();
    }

    /** Lay the current page into the chest. */
    private void lay() {
        var server = serverOf(owner);
        List<ItemStack> all = WishBagService.contents(server, owner.getUUID());
        page = WishBagPages.clampPage(page, all.size());
        List<ItemStack> cells = WishBagPages.page(all, page);
        Container c = getContainer();
        shown = new ArrayList<>();
        for (int i = 0; i < WishBagPages.PER_PAGE; i++) {
            ItemStack s = i < cells.size() ? cells.get(i) : ItemStack.EMPTY;
            c.setItem(i, s.copy());
            shown.add(s.copy());
        }
        List<WishBagPages.Trim> row = WishBagPages.bottomRow(page, all.size());
        for (int i = 0; i < row.size(); i++) {
            c.setItem(WishBagPages.PER_PAGE + i, trim(row.get(i), page, all.size()));
        }
    }

    /** One cell of the bottom row, as an item. */
    static ItemStack trim(WishBagPages.Trim cell, int page, int size) {
        return switch (cell) {
            case OLDER -> button(Items.ARROW, WishBagWords.OLDER, ChatFormatting.AQUA);
            case NEWER -> button(Items.ARROW, WishBagWords.NEWER, ChatFormatting.AQUA);
            case COUNTER -> button(Items.NETHER_STAR, WishBagWords.counter(page, size), ChatFormatting.LIGHT_PURPLE);
            case GLASS -> glass();
        };
    }

    private static ItemStack button(net.minecraft.world.item.Item item, String label, ChatFormatting colour) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(label)
                .setStyle(Style.EMPTY.withColor(colour).withItalic(false)));
        return s;
    }

    /** Purple trim, with no tooltip at all: hovering it should not say "Purple Stained Glass Pane". */
    private static ItemStack glass() {
        ItemStack s = new ItemStack(Items.PURPLE_STAINED_GLASS_PANE);
        com.moddingfromamod.compat.ItemComponents.hideTooltip(s);
        return s;
    }

    /** Turn to page {@code to}: the same chest, re-laid. Public so a gate can drive it. */
    public void showPage(int to) {
        page = to;
        lay();
        broadcastChanges();
        sendAllDataToRemote();
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player player) {
        if (slotId >= WishBagPages.PER_PAGE && slotId < SIZE) {
            press(slotId);
            return;
        }
        boolean bagCell = slotId >= 0 && slotId < WishBagPages.PER_PAGE;
        if (bagCell && type == ClickType.CLONE) {
            refuse();
            return;
        }
        ItemStack refused = wouldRefuse(slotId, button, type, player);
        super.clicked(slotId, button, type, player);
        if (!refused.isEmpty()) {
            tell(refused);
            sendAllDataToRemote();
        }
        sync();
    }

    /**
     * What an ordinary item this click is trying to put in, or EMPTY — only so he can be TOLD;
     * the refusal itself is the slots' {@code mayPlace}, which vanilla honours on every path.
     */
    private ItemStack wouldRefuse(int slotId, int button, ClickType type, Player player) {
        boolean bagCell = slotId >= 0 && slotId < WishBagPages.PER_PAGE;
        ItemStack candidate = ItemStack.EMPTY;
        if (bagCell && type == ClickType.PICKUP) {
            candidate = getCarried();
        } else if (bagCell && type == ClickType.SWAP) {
            candidate = button == 40 ? player.getOffhandItem()
                    : button >= 0 && button < 9 ? player.getInventory().getItem(button) : ItemStack.EMPTY;
        } else if (!bagCell && slotId >= SIZE && slotId < this.slots.size() && type == ClickType.QUICK_MOVE) {
            candidate = this.slots.get(slotId).getItem();
        }
        return !candidate.isEmpty() && !WishedItems.isWished(candidate) && !isModdysOwn(candidate)
                ? candidate.copy() : ItemStack.EMPTY;
    }

    private static boolean isModdysOwn(ItemStack s) {
        return WishBagItem.isBag(s) || com.moddingfromamod.wish.VanillaWatchItem.isWatch(s);
    }

    private void tell(ItemStack refused) {
        boolean block = refused.getItem() instanceof net.minecraft.world.item.BlockItem;
        owner.sendSystemMessage(Component.literal("✨ " + WishBagWords.refusal(block))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    /** Write what the click did to the page back into his bag, then re-lay it. */
    private void sync() {
        List<ItemStack> now = new ArrayList<>();
        for (int i = 0; i < WishBagPages.PER_PAGE; i++) {
            now.add(getContainer().getItem(i).copy());
        }
        List<ItemStack> before = shown;
        boolean changed = false;
        for (int i = 0; i < WishBagPages.PER_PAGE; i++) {
            if (!ItemStack.matches(before.get(i), now.get(i))) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            return;
        }
        List<ItemStack> all = WishBagService.contents(serverOf(owner), owner.getUUID());
        List<ItemStack> next = WishBagPages.reconcile(all, page, nullEmpty(before), nullEmpty(now),
                ItemStack::isSameItemSameComponents, ItemStack::getCount);
        WishBagService.replace(serverOf(owner), owner.getUUID(), next);
        lay();
        broadcastChanges();
    }

    private static List<ItemStack> nullEmpty(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : in) {
            out.add(s.isEmpty() ? null : s);
        }
        return out;
    }

    private void press(int slotId) {
        refuse();
        int size = WishBagService.size(serverOf(owner), owner.getUUID());
        int to = WishBagPages.turn(slotId, page, size);
        if (to != page) {
            showPage(to);
        }
    }

    /** The client predicted a move the server did not make: send it the truth. */
    private void refuse() {
        sendAllDataToRemote();
    }

    /**
     * Shift-click. From a bag cell: into his inventory, as vanilla would. From his inventory: a
     * wished thing goes to the FRONT of the bag (any page), and nothing else moves.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= this.slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        if (index < WishBagPages.PER_PAGE) {
            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();
            if (!this.moveItemStackTo(stack, SIZE, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
            slot.setChanged();
            return copy;
        }
        if (index < SIZE) {
            return ItemStack.EMPTY;            // a button
        }
        ItemStack stack = slot.getItem();
        if (!WishedItems.isWished(stack)) {
            return ItemStack.EMPTY;
        }
        ItemStack moving = stack.copy();
        int in = WishBagService.add(serverOf(owner), owner.getUUID(), moving);
        if (in > 0) {
            stack.shrink(in);
            slot.setChanged();
            lay();
            broadcastChanges();
        }
        return ItemStack.EMPTY;               // one move per click; never loop
    }

    /** A cell of his bag: takes only wished things. */
    private static final class BagSlot extends Slot {
        BagSlot(Container c, int i, int x, int y) {
            super(c, i, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return WishedItems.isWished(stack);
        }
    }

    /** The bottom row: buttons, never a slot anything moves in or out of. */
    private static final class ButtonSlot extends Slot {
        ButtonSlot(Container c, int i, int x, int y) {
            super(c, i, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
