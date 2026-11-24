package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B: a long wish goes through {@code /wish} in chat, so {@code /wish} must answer
 * every sentence as a WISH, and the only other command a child sees is {@code /moddy}.
 *
 * <p>Parse-level on the real server's dispatcher, the one a stock client's chat reaches: which
 * node a line lands on is exactly the question, and parsing does not submit, so nothing here needs
 * an agent or touches the wish book.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishChatGameTest {

    /**
     * Sentences a child really opens a wish with, whose first word was a {@code /wish} literal
     * — {@code no} rejected his last wish, {@code stop}/{@code undo} stopped it, the
     * rest were errors — plus {@code go}/{@code give}, the reasons those verbs never lived there.
     */
    static final List<String> SENTENCES = List.of(
            "no more rain please",
            "stop the rain",
            "stop",
            "undo my house",
            "again a dragon",
            "status of my dragon",
            "redirect the river to my house",
            "go to the moon",
            "give me a dragon",
            "back to my house");

    @GameTest(template = "empty_test")
    public static void everySentenceAfterSlashWishIsAWish(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> d = dispatcher(helper);
        CommandSourceStack child = childSource(helper);
        for (String sentence : SENTENCES) {
            ParseResults<CommandSourceStack> parse = d.parse("wish " + sentence, child);
            helper.assertTrue(parse.getExceptions().isEmpty() && !parse.getReader().canRead(),
                    Component.literal("'/wish " + sentence + "' does not parse whole: " + parse.getExceptions()));
            CommandContextBuilder<CommandSourceStack> ctx = parse.getContext();
            List<ParsedCommandNode<CommandSourceStack>> nodes = ctx.getNodes();
            String last = nodes.get(nodes.size() - 1).getNode().getName();
            helper.assertTrue("text".equals(last),
                    Component.literal("'/wish " + sentence + "' lands on '" + last + "', not the wish text"));
            helper.assertTrue(sentence.equals(ctx.getArguments().get("text").getResult()),
                    Component.literal("'/wish " + sentence + "' carries the text '"
                            + ctx.getArguments().get("text").getResult() + "'"));
            helper.assertTrue(ctx.getCommand() != null, Component.literal("'/wish " + sentence + "' runs nothing"));
        }
        helper.assertTrue(d.getRoot().getChild(ChildCommands.WISH).getChildren().size() == 1,
                Component.literal("/wish must hold ONLY its text argument; it has "
                        + d.getRoot().getChild(ChildCommands.WISH).getChildren().stream()
                        .map(n -> n.getName()).toList()));
        helper.succeed();
    }

    /** Every line a chat link runs parses to a real command a child is allowed to run. */
    @GameTest(template = "empty_test")
    public static void everyLinkAChildClicksRuns(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> d = dispatcher(helper);
        CommandSourceStack child = childSource(helper);
        List<String> links = List.of(
                ChildCommands.again("some_recipe"),
                ChildCommands.stop(""), ChildCommands.stop("ab12cd34"),
                ChildCommands.undo(""), ChildCommands.undo("ab12cd34"),
                ChildCommands.redirectPrompt("ab12cd34") + "the red one",
                ChildCommands.go(""), ChildCommands.go("ab12cd34"),
                ChildCommands.back(),
                ChildCommands.give("Laser Bow"),
                "/" + ChildCommands.MODDY + " " + ChildCommands.NO,
                "/" + ChildCommands.MODDY + " " + ChildCommands.NO + " it was too small",
                "/" + ChildCommands.MODDY + " " + ChildCommands.STATUS,
                "/" + ChildCommands.MODDY,
                "/" + ChildCommands.WISH);
        for (String link : links) {
            ParseResults<CommandSourceStack> parse = d.parse(link.substring(1), child);
            helper.assertTrue(parse.getExceptions().isEmpty() && !parse.getReader().canRead()
                            && parse.getContext().getCommand() != null,
                    Component.literal("'" + link + "' is not a complete command a child can run: "
                            + parse.getExceptions()));
        }
        for (String verb : ChildCommands.VERBS) {
            helper.assertTrue(d.getRoot().getChild(ChildCommands.MODDY).getChild(verb) != null,
                    Component.literal("/moddy " + verb + " is not registered"));
        }
        helper.succeed();
    }

    /**
     * The child's tab list is {@code /wish} and {@code /moddy}: the replay commands are the stored
     * form of every button and run at the executor's permission 2, so a child need not — and must
     * not — see them ({@code /wishdatapack}'s completions listed every child's things).
     */
    @GameTest(template = "empty_test")
    public static void aChildSeesOnlyWishAndModdy(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> d = dispatcher(helper);
        CommandSourceStack child = childSource(helper);
        CommandSourceStack executor = child.withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
        for (String name : ChildCommands.REPLAY_COMMANDS) {
            var node = d.getRoot().getChild(name);
            helper.assertTrue(node != null, Component.literal("/" + name + " is gone — every saved button needs it"));
            helper.assertFalse(node.canUse(child), Component.literal("a child can run /" + name));
            helper.assertTrue(node.canUse(executor),
                    Component.literal("/" + name + " is not runnable at the executor's permission 2, so buttons break"));
        }
        for (String name : ChildCommands.RETIRED) {
            helper.assertTrue(d.getRoot().getChild(name) == null,
                    Component.literal("/" + name + " is still registered; it is /moddy now"));
        }
        helper.assertTrue(d.getRoot().getChild(ChildCommands.WISH).canUse(child), Component.literal("/wish"));
        helper.assertTrue(d.getRoot().getChild(ChildCommands.MODDY).canUse(child), Component.literal("/moddy"));
        // ONLY: every root a permission-0 player can use is /wish, /moddy or vanilla's own. Before this loop the
        // test checked the replay commands were hidden and never that nothing ELSE was visible, which is how a
        // top-level /builder shipped to every child's tab list (moved under /moddy, 2026-11-09). A new
        // child verb goes under /moddy (ChildCommands), an operator one under /moddyop.
        java.util.List<String> extra = new java.util.ArrayList<>();
        for (var node : d.getRoot().getChildren()) {
            if (node.canUse(child) && !ChildCommands.WISH.equals(node.getName())
                    && !ChildCommands.MODDY.equals(node.getName()) && !VANILLA_PLAYER_COMMANDS.contains(node.getName())) {
                extra.add("/" + node.getName());
            }
        }
        java.util.Collections.sort(extra);
        helper.assertTrue(extra.isEmpty(), Component.literal("a child can see " + extra
                + " — a child's commands are /wish and /moddy only (#1107): put a child's verb under /moddy, an operator's under /moddyop"));
        helper.succeed();
    }

    /**
     * Vanilla's (and NeoForge's) own permission-0 commands — not ours to move. A name is added here only for a
     * command Moddy did not register.
     */
    static final java.util.Set<String> VANILLA_PLAYER_COMMANDS = java.util.Set.of(
            "help", "list", "me", "msg", "tell", "w", "teammsg", "tm", "trigger", "random", "seed", "version",
            "neoforge", "config");

    private static CommandDispatcher<CommandSourceStack> dispatcher(GameTestHelper helper) {
        return helper.getLevel().getServer().getCommands().getDispatcher();
    }

    /** Permission 0, which is what every child on the server has; parsing needs no body. */
    private static CommandSourceStack childSource(GameTestHelper helper) {
        return helper.getLevel().getServer().createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.ALL);
    }
}
