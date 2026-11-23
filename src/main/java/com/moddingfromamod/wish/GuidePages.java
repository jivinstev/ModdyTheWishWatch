package com.moddingfromamod.wish;

import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.world.GoalGuide;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The two pages the 📖 guide opens that no edition had a screen for on BOTH (2026-11-10, a
 * Bedrock design decision): <b>🏆 My goals</b> — what L shows on Java, for an edition with no L — and
 * <b>💡 Help &amp; links</b>, the addresses written out, because a Bedrock form cannot open a web page
 *. Both are {@link PickPage}s or plain chat, so neither forks by edition.
 */
public final class GuidePages {

    private GuidePages() {
    }

    /** The goal list as tiles: done ones glint and say so; the hint is the lore (and said on a press). */
    public static List<PickPage.Tile> goalTiles(List<GoalGuide.Line> goals) {
        List<PickPage.Tile> out = new ArrayList<>();
        for (int i = 0; i < goals.size(); i++) {
            GoalGuide.Line g = goals.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(g.done() ? "✔ Done!" : "Still to do");
            if (!g.hint().isBlank()) {
                lore.add(g.hint().length() > 60 ? g.hint().substring(0, 57) + "…" : g.hint());
            }
            out.add(new PickPage.Tile("g" + i, g.icon(), (g.done() ? "✔ " : "🎯 ") + g.title(), lore, g.done()));
        }
        return out;
    }

    /** Open 🏆 My goals on {@code page}; ◀ Back returns to the guide. */
    public static void openGoals(ServerPlayer sp, int page) {
        var world = com.moddingfromamod.world.WorldFacts.current();
        List<GoalGuide.Line> goals = GoalGuide.all(sp, world.guide());
        List<List<PickPage.Tile>> pages = PickPage.pages(goalTiles(goals));
        int p = Math.max(0, Math.min(page, pages.size() - 1));
        long done = goals.stream().filter(GoalGuide.Line::done).count();
        PickPage pick = new PickPage("🏆 My goals · " + done + "/" + goals.size(),
                pages.isEmpty() ? List.of() : pages.get(p), true, p + 1 < pages.size(), "Next ▶");
        MenuKit.pick(sp, "goals", pick, (who, id) -> {
            if (PickPage.BACK_ID.equals(id)) {
                if (p > 0) {
                    openGoals(who, p - 1);
                } else if (WatchMenu.open(who) && who.containerMenu instanceof WatchMenu w) {
                    w.showGuide(who);
                }
            } else if (PickPage.NEXT_ID.equals(id)) {
                openGoals(who, p + 1);
            } else if (id.startsWith("g")) {
                // A phone shows no tooltip, so a press SAYS the goal.
                GoalGuide.Line g = goals.get(Integer.parseInt(id.substring(1)));   // ids are list indexes
                who.sendSystemMessage(Component.literal((g.done() ? "✔ " : "🎯 ") + g.title()
                        + (g.hint().isBlank() ? "" : ": " + g.hint())).withStyle(ChatFormatting.AQUA));
            }
        });
    }

    /** What 💡 Help &amp; links says: every address written out, no link to tap. */
    public static List<String> linkLines() {
        return List.of(
                "💡 Help & links",
                "   Become a Wish Magician (grown-ups): " + CrafterService.signupShown(),
                "   Trouble joining? " + com.moddingfromamod.net.JoinWords.DEFAULT_JOIN_URL,
                "   Something not right? Open your wish watch and press " + WatchHome.UNDO + ".");
    }

    public static void sayLinks(ServerPlayer sp) {
        List<String> lines = linkLines();
        for (int i = 0; i < lines.size(); i++) {
            sp.sendSystemMessage(Component.literal(lines.get(i))
                    .withStyle(i == 0 ? ChatFormatting.GOLD : ChatFormatting.AQUA));
        }
        com.moddingfromamod.world.WorldModuleHost.sayHelp(sp);                  //: a world module's "start over"
    }
}
