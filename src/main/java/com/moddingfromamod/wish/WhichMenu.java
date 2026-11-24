package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.marshal.WatchRow;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * <b>"Which one do you mean?" for a client with no mod</b> ({@code ask_which}) — one row
 * of a vanilla chest, one slot per thing, drawn with the same icons the watch panel draws them
 * with, and a grey "something else" at the end.
 *
 * <p>A modded client gets {@code WishPickScreen}'s photographs instead (the {@code AskWhichPayload}
 * upgrade); a stock client cannot run that screen, and before this its only way to answer was a
 * clickable name in chat. Every press runs the same answer as the chat link,
 * {@link WhichQuestion#answer}. Like {@link WatchMenu}, every click on a panel slot is consumed as
 * a press and nothing can be picked up, cloned, thrown or swapped.
 */
public final class WhichMenu extends ChestMenu {

    /** The last slot of the row: "it was none of these". */
    static final int NONE_SLOT = 8;

    private final List<String> ids;

    /** The question this chest asks — never "whatever is open for him now" (see {@link #removed}). */
    private final WhichQuestion.Open asked;

    private WhichMenu(int containerId, Inventory inv, SimpleContainer container, List<String> ids,
                      WhichQuestion.Open asked) {
        super(MenuType.GENERIC_9x1, containerId, inv, container, 1);
        this.ids = List.copyOf(ids);
        this.asked = asked;
    }

    /** Open it for him. Server thread only. */
    public static void open(ServerPlayer player, List<String> ids, String question,
                            WhichQuestion.Open asked) {
        if (player == null || ids == null || ids.isEmpty()) {
            return;
        }
        List<String> shown = ids.size() > NONE_SLOT ? ids.subList(0, NONE_SLOT) : ids;
        java.util.Map<String, WatchRow.Creation> rows = new java.util.HashMap<>();
        try {
            for (String row : WatchSnapshot.forPlayer(player).creations()) {
                WatchRow.Creation c = WatchRow.Creation.decode(row);
                rows.put(c.id().toLowerCase(java.util.Locale.ROOT), c);
            }
        } catch (RuntimeException e) {
            // Icons are a nicety: a slot with a plain icon is still a slot he can press.
        }
        SimpleContainer container = new SimpleContainer(9);
        for (int i = 0; i < shown.size(); i++) {
            String id = shown.get(i);
            WatchRow.Creation c = rows.get(id.toLowerCase(java.util.Locale.ROOT));
            ItemStack icon = c == null || c.icon() == null || c.icon().isBlank()
                    ? new ItemStack(Items.BOOK) : WatchMenu.iconStack(c.icon());
            String name = c == null ? id.replace('_', ' ') : c.name();
            icon.set(DataComponents.CUSTOM_NAME, Component.literal(name)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withItalic(false)));
            container.setItem(i, icon);
        }
        ItemStack none = new ItemStack(Items.GRAY_DYE);
        none.set(DataComponents.CUSTOM_NAME, Component.literal("Something else")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)));
        container.setItem(NONE_SLOT, none);
        String title = question == null || question.isBlank() ? "🤔 Which one do you mean?"
                : "🤔 " + question.strip();
        List<String> offered = new ArrayList<>(shown);
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new WhichMenu(id, inv, container, offered, asked),
                // A BUTTON chest (ui/MenuKit's rule): a Pocket player gets the one-tap grid.
                com.moddingfromamod.net.ButtonChest.title(title.length() > 40 ? title.substring(0, 39) + "…" : title, player)));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput type, Player player) {
        boolean panelSlot = slotId >= 0 && slotId < 9;
        if (type == ContainerInput.QUICK_CRAFT || panelSlot) {
            if (player instanceof ServerPlayer sp) {
                sp.containerMenu.sendAllDataToRemote();
                if (panelSlot && type != ContainerInput.QUICK_CRAFT) {
                    press(sp, slotId);
                }
            }
            return;
        }
        super.clicked(slotId, button, type, player);
    }

    private void press(ServerPlayer sp, int slotId) {
        String picked = slotId == NONE_SLOT ? WhichQuestion.NONE
                : slotId < ids.size() ? ids.get(slotId) : null;
        if (picked == null) {
            return;
        }
        // ANSWER FIRST, then close: closing without a pick is "something else" (removed, below),
        // and the open question must already be answered by the time that runs.
        WhichQuestion.answer(sp.getUUID(), asked, picked);
        sp.closeContainer();
    }

    /**
     * Closed without pressing anything (Esc): that is "none of these", so the asker stops waiting —
     * but only THIS chest's asker. A chest is also closed when a newer question's chest opens over
     * it, and that close must not answer the newer question (it used to, matched by ids:).
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer sp) {
            WhichQuestion.answer(sp.getUUID(), asked, WhichQuestion.NONE);
        }
    }
}
