package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: the command table against the commands this server really has.</b>
 *
 * <p>{@link CommandGuard}'s table is the one place a command is decided. Its unit test can only check
 * the rows it has; this walks the LIVE dispatcher — vanilla's commands, NeoForge's and this mod's —
 * and fails on any root with no row, so a new Minecraft version or a new mod command is decided in
 * the table instead of refused (or, worse, allowed) by omission. That is how {@code locate} came to
 * be refused for three live "take me to a jungle" wishes without anybody ever deciding it should be.
 *
 * <p>And {@code locate} itself, through the real {@code run_command}: answered with coordinates and
 * the ground height, never dispatched.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class CommandPolicyGameTest {

    private static final String BATCH = "commandpolicy";

    @GameTest(template = "empty_test", batch = BATCH)
    public static void everyCommandThisServerHasIsDecidedInTheTable(GameTestHelper helper) {
        var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
        List<String> undecided = new ArrayList<>();
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            if (CommandGuard.rule(child.getName()) == null) {
                undecided.add(child.getName());
            }
        }
        undecided.sort(null);
        helper.assertTrue(undecided.isEmpty(), Component.literal("these commands are registered but have no row in "
                + "CommandGuard's table — decide each one there (#1602): " + undecided));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void runCommandAnswersLocateWithWhereToStand(GameTestHelper helper) {
        AtomicReference<ToolResult> biome = new AtomicReference<>();
        AtomicReference<ToolResult> unknown = new AtomicReference<>();
        AtomicReference<ToolResult> mixed = new AtomicReference<>();
        // run_command hops to the server thread, so it is driven from another one, as the MCP does.
        Thread t = new Thread(() -> {
            RunCommandTool tool = new RunCommandTool();
            biome.set(tool.call(call("locate biome minecraft:plains")));
            unknown.set(tool.call(call("locate biome minecraft:not_a_biome_anywhere")));
            mixed.set(tool.call(call("locate biome minecraft:plains", "say hi")));
        }, "commandpolicy-gametest");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(mixed.get() != null, Component.literal("waiting for run_command"));
            String b = biome.get().text();
            helper.assertFalse(biome.get().isError(), Component.literal("locate biome was refused or failed: " + b));
            helper.assertTrue(b.startsWith("The nearest minecraft:plains is about")
                            || b.startsWith("No minecraft:plains within reach"),
                    Component.literal("locate must be ANSWERED (coordinates or 'none'), never dispatched: " + b));
            if (b.startsWith("The nearest")) {
                helper.assertTrue(b.contains("`tp @s "), Component.literal("the answer says where to stand: " + b));
            }
            helper.assertTrue(unknown.get().isError() && unknown.get().text().contains("no biome called"),
                    Component.literal("an id this world does not have is named, not searched for: " + unknown.get().text()));
            helper.assertTrue(mixed.get().isError() && mixed.get().text().contains("call of its own"),
                    Component.literal("locate in a batch is refused before anything runs: " + mixed.get().text()));
        });
    }

    private static JsonObject call(String... commands) {
        JsonObject args = new JsonObject();
        JsonArray list = new JsonArray();
        for (String c : commands) {
            list.add(c);
        }
        args.add("command", list);
        return args;
    }
}
