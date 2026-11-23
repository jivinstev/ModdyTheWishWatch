package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * <b>The world's guide book, as a vanilla written book</b> — the game half of
 * {@link GuideBook}. A {@code minecraft:written_book} with a {@code written_book_content} component
 * is plain vanilla, so a STOCK client opens and reads it with nothing installed; the component and its
 * record are the same on 1.21.1 and 1.21.4 (read from both decompiled jars), so there never
 * was a 1.21.1 twin (that build is parked).
 *
 * <p>Given ONCE, on his first arrival in the world, right after the intro ({@link JoinIntroService},
 * so it rides the intro's once-per-world record and needs none of its own), and again whenever he
 * asks ({@code /moddy guide}, the watch's guide page). The pages are the manifest's, with the wish
 * bank's numbers filled in from the rules in force when it is given.
 */
public final class GuideBookService {

    private GuideBookService() {
    }

    /** This world's guide book, or {@link ItemStack#EMPTY} when the world has none. */
    public static ItemStack stack(WorldFacts world) {
        WorldGuide g = world == null ? WorldGuide.NONE : world.guide();
        if (!g.hasBook()) {
            return ItemStack.EMPTY;
        }
        // a world that prices wishes fills its bank page from the charge plug-in; with none the page is left out.
        Map<String, String> values = com.moddingfromamod.wish.WishCharge.get().guideValues();
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String page : GuideBook.pages(g.book(), values)) {
            pages.add(Filterable.passThrough(Component.literal(page)));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        // resolved = true: the pages are plain literals, nothing for the client to resolve.
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(g.book().title()), g.book().author(), 0, pages, true));
        return book;
    }

    /** Give him this world's guide book. False (and nothing given) when the world has none. */
    /**
     * a guide book a player already holds still has the words of the day it was given, so when the
     * world's book is reworded (its "Press L" became the watch's 📖 page) the copies in players' bags would
     * say the old thing for ever. On a returning join, every held copy of THIS world's book (same title
     * and author) whose pages differ from today's is rewritten in place. Other written books are never
     * touched. Returns how many copies it rewrote.
     */
    public static int refreshHeld(ServerPlayer player, WorldFacts world) {
        ItemStack fresh = stack(world);
        if (player == null || fresh.isEmpty()) {
            return 0;
        }
        WrittenBookContent now = fresh.get(DataComponents.WRITTEN_BOOK_CONTENT);
        int changed = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.is(Items.WRITTEN_BOOK)) {
                continue;
            }
            WrittenBookContent old = st.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (old != null && now != null && old.title().raw().equals(now.title().raw())
                    && old.author().equals(now.author()) && !old.pages().equals(now.pages())) {
                st.set(DataComponents.WRITTEN_BOOK_CONTENT, now);
                changed++;
            }
        }
        return changed;
    }

    public static boolean give(ServerPlayer player, WorldFacts world) {
        ItemStack book = stack(world);
        if (player == null || book.isEmpty()) {
            return false;
        }
        String title = world.guide().book().title();
        boolean bag = com.moddingfromamod.item.HandOver.toPlayer(player, book);
        player.sendSystemMessage(Component.literal(bag
                        ? "📖 Here is your " + title + " book. Open it any time!"
                        : "📖 Your " + title + " book is on the ground next to you. Pick it up!")
                .withStyle(ChatFormatting.AQUA));
        return true;
    }

    /** {@code /moddy guide}: a fresh copy, or a kind word on a world with no guide. */
    public static void command(ServerPlayer player) {
        if (!give(player, WorldFacts.current())) {
            player.sendSystemMessage(Component.literal("This world has no guide book. Type /moddy help instead!")
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
