package com.moddingfromamod.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.moddingfromamod.marshal.GuidePanel;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>"What do I do now?", answered from his own goal list</b> — the game half of
 * {@link NextGoal}. Reads the server's advancement tree under the world's {@code guide.goals.root}
 * (for example SkyBlock Advancements' {@code skyvoid_advancements:overworld/root}: the tab a child
 * opens with L) and the player's OWN progress, and hands both to {@link NextGoal}. It reads only:
 * it never grants, revokes or changes an advancement — the goal list is his to earn.
 *
 * <p>Titles and descriptions are the advancements' own components resolved on the server
 * ({@code getString}): a vanilla key resolves through the server's bundled {@code en_us}, and every
 * SkyBlock Advancements key carries an English {@code fallback} — so the words match what L shows.
 * The world's own hint ({@code guide.goals.help}) wins where it names one: a description such as the
 * cobblestone generator's carries a diagram drawn in a resource-pack font that a chest tooltip
 * cannot show.
 */
public final class GoalGuide {

    private GoalGuide() {
    }

    /**
     * What the guide says. {@code tree} is false when the world names no goal tree OR this server does
     * not have it loaded (the pack missing): the caller shows the "be creative" page instead.
     */
    public record Answer(boolean tree, GuidePanel.Goal goal) {

        static final Answer NO_TREE = new Answer(false, null);
    }

    /** His next goal on this world's tree. Server thread. */
    public static Answer next(ServerPlayer player, WorldGuide guide) {
        if (player == null || guide == null || !guide.hasGoals()) {
            return Answer.NO_TREE;
        }
        Identifier rootId = Identifier.tryParse(guide.goals().root());
        if (rootId == null) {
            return Answer.NO_TREE;
        }
        AdvancementNode root = player.level().getServer().getAdvancements().tree().get(rootId);
        if (root == null) {
            return Answer.NO_TREE;
        }
        Map<String, String> parentOf = new HashMap<>();
        Set<String> done = new HashSet<>();
        List<String> skip = new ArrayList<>(guide.goals().skip());
        Map<String, AdvancementHolder> holders = new HashMap<>();
        Deque<AdvancementNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AdvancementNode n = queue.poll();
            AdvancementHolder h = n.holder();
            String id = h.id().toString();
            if (holders.putIfAbsent(id, h) != null) {
                continue;
            }
            if (n.parent() != null) {
                parentOf.put(id, n.parent().holder().id().toString());
            }
            Optional<DisplayInfo> display = h.value().display();
            // A node with no display is a technical check; a hidden one is a surprise. Neither is a goal.
            if (display.isEmpty() || display.get().isHidden()) {
                skip.add(id);
            }
            if (player.getAdvancements().getOrStartProgress(h).isDone()) {
                done.add(id);
            }
            for (AdvancementNode c : n.children()) {
                queue.add(c);
            }
        }
        Optional<String> next = NextGoal.next(rootId.toString(), parentOf, done, guide.goals().order(), skip);
        if (next.isEmpty()) {
            return new Answer(true, null);
        }
        AdvancementHolder h = holders.get(next.get());
        DisplayInfo d = h.value().display().orElse(null);
        String title = d == null ? next.get() : d.getTitle().getString();
        WorldGuide.Help help = guide.goals().helpFor(next.get());
        String hint = !help.hint().isEmpty() ? help.hint()
                : d == null ? "" : d.getDescription().getString().replaceAll("\\s+", " ").strip();
        return new Answer(true, new GuidePanel.Goal(next.get(), title, hint, NextGoal.helpWish(help, title)));
    }

    /** One goal on the list: its title, what to do, whether he has done it, and its own icon. */
    public record Line(String title, String hint, boolean done, String icon) {
    }

    /**
     * <b>Every goal on this world's tree</b>, in the tree's own order, done or not (🏆 My goals):
     * what L shows on Java, for an edition with no L. Empty when the world has no tree. Reads only.
     */
    public static List<Line> all(ServerPlayer player, WorldGuide guide) {
        List<Line> out = new ArrayList<>();
        if (player == null || guide == null || !guide.hasGoals()) {
            return out;
        }
        Identifier rootId = Identifier.tryParse(guide.goals().root());
        AdvancementNode root = rootId == null ? null : player.level().getServer().getAdvancements().tree().get(rootId);
        if (root == null) {
            return out;
        }
        Set<String> skip = new HashSet<>(guide.goals().skip());
        Set<String> seen = new HashSet<>();
        Deque<AdvancementNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AdvancementNode n = queue.poll();
            AdvancementHolder h = n.holder();
            String id = h.id().toString();
            if (!seen.add(id)) {
                continue;
            }
            n.children().forEach(queue::add);
            Optional<DisplayInfo> display = h.value().display();
            if (display.isEmpty() || display.get().isHidden() || skip.contains(id) || n.parent() == null) {
                continue;
            }
            DisplayInfo d = display.get();
            WorldGuide.Help help = guide.goals().helpFor(id);
            String hint = !help.hint().isEmpty() ? help.hint()
                    : d.getDescription().getString().replaceAll("\\s+", " ").strip();
            String icon = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(d.getIcon().item().value()).toString();
            out.add(new Line(d.getTitle().getString(), hint,
                    player.getAdvancements().getOrStartProgress(h).isDone(), icon));
        }
        return out;
    }
}
