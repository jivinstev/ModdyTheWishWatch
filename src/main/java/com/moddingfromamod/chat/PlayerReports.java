package com.moddingfromamod.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.safety.ChatWords;
import com.moddingfromamod.world.WorldFacts;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>{@code /moddy report} — saving it</b>. Appends one {@link PlayerReport#record} to
 * {@code $MODDY_HOME/player-reports.jsonl}; an operator's own tools
 * read it from there. The reporter is thanked; nothing is done to the reported player.
 *
 * <p>Every world has the command; it matters most on a world where strangers meet.
 */
public final class PlayerReports {

    private PlayerReports() {
    }

    /** The file an operator's tools read. */
    public static final String FILE = "player-reports.jsonl";

    static final PlayerReport LIMITS = new PlayerReport();

    public static Path file() {
        return RuntimeConfig.home().resolve(FILE);
    }

    /** Run {@code /moddy report <name> [note]} for {@code reporter}. True when a report was saved. */
    public static boolean report(ServerPlayer reporter, String name, String note) {
        long now = System.currentTimeMillis();
        String me = reporter.getGameProfile().name();
        PlayerReport.Verdict v = LIMITS.check(reporter.getUUID(), me, name, now);
        switch (v) {
            case BAD_NAME, SELF -> {
                tell(reporter, ChatWords.REPORT_HOW, ChatFormatting.AQUA);
                return false;
            }
            case TOO_MANY -> {
                tell(reporter, ChatWords.REPORT_SLOW_DOWN, ChatFormatting.LIGHT_PURPLE);
                return false;
            }
            default -> { }
        }
        ServerPlayer target = reporter.level().getServer().getPlayerList().getPlayerByName(name);
        List<ChatHistory.Line> lines = ChatGuard.HISTORY.linesFor(name, me, now);
        JsonObject rec = PlayerReport.record(UUID.randomUUID().toString(), now, WorldFacts.manifestId(),
                reporter.getUUID(), me, target != null ? target.getGameProfile().name() : name,
                target != null ? target.getUUID() : null, target != null, note, lines, ChatGuard.flagged(lines),
                com.moddingfromamod.RunContext.isTestRun());
        if (!append(file(), rec)) {
            tell(reporter, ChatWords.REPORT_FAILED, ChatFormatting.GRAY);
            return false;
        }
        tell(reporter, ChatWords.sayReportThanks(target != null ? target.getGameProfile().name() : name),
                ChatFormatting.LIGHT_PURPLE);
        return true;
    }

    /** One line, appended; owner-only permissions on a new file (it carries players' words). */
    static synchronized boolean append(Path f, JsonObject rec) {
        try {
            Files.createDirectories(f.getParent());
            boolean fresh = !Files.exists(f);
            Files.writeString(f, rec + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) {
                try {
                    Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException | IOException ignored) {
                    // not POSIX (a Windows dev box): the directory's own permissions stand
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void tell(ServerPlayer p, String s, ChatFormatting colour) {
        p.sendSystemMessage(Component.literal(s).withStyle(colour));
    }
}
