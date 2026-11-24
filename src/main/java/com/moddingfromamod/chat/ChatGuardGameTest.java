package com.moddingfromamod.chat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.ParseResults;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.safety.ChatWords;
import com.moddingfromamod.world.WorldPeople;

import net.minecraft.commands.CommandSourceStack;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B: the chat sweep is WIRED — a real {@link ServerChatEvent} and a real {@link CommandEvent}
 * posted on the game bus reach {@link ChatGuard} (registered in the {@code @Mod} class), a held line is
 * cancelled and its sender told, a {@code safety} record with no words is written, the
 * default (off) cancels nothing, and {@code /moddy report} saves a record and thanks the reporter.
 *
 * <p>One named batch: the sweep's mode is a JVM property here, and nothing else may chat under it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ChatGuardGameTest {

    private static ServerPlayer player(GameTestHelper helper) {
        com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
        return com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
    }

    private static void tidy(ServerPlayer p) {
        System.clearProperty(WorldPeople.SWEEP_PROPERTY);
        ChatGuard.STRIKES.clear();
        if (p != null) {
            com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
        }
    }

    private static boolean said(ServerPlayer p, String line) {
        return com.moddingfromamod.testing.MockPlayerNetwork.chat(p).stream().anyMatch(s -> s.contains(line));
    }

    /** The chat-layer {@code safety} records for this player, newest last. */
    private static List<JsonObject> chatRecords(ServerPlayer p) throws java.io.IOException {
        Path f = ActionLog.path();
        if (!Files.exists(f)) {
            return List.of();
        }
        String id = p.getUUID().toString();
        return Files.readAllLines(f, StandardCharsets.UTF_8).stream()
                .filter(l -> l.contains("\"chat\"") && l.contains(id))
                .map(l -> JsonParser.parseString(l).getAsJsonObject())
                .filter(o -> "safety".equals(o.get("kind").getAsString()) && "chat".equals(o.get("layer").getAsString()))
                .toList();
    }

    @GameTest(template = "empty_test", batch = "chat_sweep")
    public static void withTheSweepOnAHeldLineIsCancelledToldAndLoggedWithoutItsWords(GameTestHelper helper) {
        ServerPlayer p = null;
        try {
            System.setProperty(WorldPeople.SWEEP_PROPERTY, "on");
            p = player(helper);
            String raw = "look at this porn " + java.util.UUID.randomUUID().toString().substring(0, 6);
            ServerChatEvent chat = NeoForge.EVENT_BUS.post(new ServerChatEvent(p, raw, Component.literal(raw)));
            helper.assertTrue(chat.isCanceled(), Component.literal("a CLEARLY chat line went out on a swept world"));
            helper.assertTrue(said(p, ChatWords.HELD), Component.literal("the sender was not told: "
                    + com.moddingfromamod.testing.MockPlayerNetwork.chat(p)));
            List<JsonObject> recs = chatRecords(p);
            helper.assertTrue(!recs.isEmpty(), Component.literal("no safety record with layer chat for " + p.getUUID()));
            JsonObject last = recs.get(recs.size() - 1);
            helper.assertTrue("BLOCK".equals(last.get("action").getAsString()) && "chat.chat".equals(last.get("tool").getAsString()),
                    Component.literal("the record: " + last));
            helper.assertTrue(!last.toString().contains("porn"), Component.literal("the record carries the words: " + last));

            ParseResults<CommandSourceStack> parse = helper.getLevel().getServer().getCommands().getDispatcher()
                    .parse("me shares some porn", p.createCommandSourceStack());
            CommandEvent cmd = NeoForge.EVENT_BUS.post(new CommandEvent(parse));
            helper.assertTrue(cmd.isCanceled(), Component.literal("/me with a CLEARLY line was not cancelled"));
            ParseResults<CommandSourceStack> fine = helper.getLevel().getServer().getCommands().getDispatcher()
                    .parse("me waves hello", p.createCommandSourceStack());
            helper.assertTrue(!NeoForge.EVENT_BUS.post(new CommandEvent(fine)).isCanceled(), Component.literal("a friendly /me was cancelled"));
            helper.succeed();
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not read the action log: " + e));
        } finally {
            tidy(p);
        }
    }

    @GameTest(template = "empty_test", batch = "chat_sweep")
    public static void withTheSweepOffNothingIsCancelled(GameTestHelper helper) {
        ServerPlayer p = null;
        try {
            System.setProperty(WorldPeople.SWEEP_PROPERTY, "off");
            p = player(helper);
            ServerChatEvent chat = NeoForge.EVENT_BUS.post(new ServerChatEvent(p, "porn", Component.literal("porn")));
            helper.assertTrue(!chat.isCanceled(), Component.literal("chat was swept on a world whose sweep is off"));
            helper.succeed();
        } finally {
            tidy(p);
        }
    }

    @GameTest(template = "empty_test", batch = "chat_sweep")
    public static void aReportIsSavedWithTheReportedPlayersLinesAndTheReporterIsThanked(GameTestHelper helper) {
        ServerPlayer p = null;
        try {
            p = player(helper);
            String name = "Rpt" + java.util.UUID.randomUUID().toString().substring(0, 8).replace("-", "");
            ChatGuard.HISTORY.said(name, "chat.chat", "you are slow", "", System.currentTimeMillis());
            boolean saved = PlayerReports.report(p, name, "kept following me");
            helper.assertTrue(saved, Component.literal("the report was not saved"));
            helper.assertTrue(said(p, ChatWords.sayReportThanks(name)), Component.literal("the reporter was not thanked: "
                    + com.moddingfromamod.testing.MockPlayerNetwork.chat(p)));
            Path f = PlayerReports.file();
            JsonObject rec = Files.readAllLines(f, StandardCharsets.UTF_8).stream()
                    .filter(l -> l.contains(name)).map(l -> JsonParser.parseString(l).getAsJsonObject())
                    .reduce((a, b) -> b).orElse(null);
            helper.assertTrue(rec != null, Component.literal("no record in " + f));
            helper.assertTrue(rec.getAsJsonArray("lines").size() == 1
                            && "kept following me".equals(rec.get("note").getAsString())
                            && rec.getAsJsonObject("reporter").get("id").getAsString().equals(p.getUUID().toString()),
                    Component.literal("the record: " + rec));
            helper.assertTrue(!PlayerReports.report(p, name, "again"), Component.literal("the same player twice in ten minutes"));
            helper.succeed();
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not read the reports: " + e));
        } finally {
            PlayerReports.LIMITS.clear();
            tidy(p);
        }
    }
}
