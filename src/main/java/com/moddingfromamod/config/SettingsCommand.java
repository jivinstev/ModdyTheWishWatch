package com.moddingfromamod.config;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>{@code /moddy setting [key] [value]}</b>: the one in-game action that changes a setting, and the one a
 * settings menu's buttons run. Bare, it lists the settings and who sets each; with a key and a value it asks
 * {@link ModdySettings#change}, which refuses a locked setting, any change on a dedicated server and any change from
 * a LAN guest — however the command arrived.
 */
public final class SettingsCommand {

    public static final String VERB = "setting";

    /** A test's stand-in for "this player is the host" (a GameTest's mock player is never the owner). */
    private static volatile java.util.UUID hostForTest;

    private SettingsCommand() {
    }

    public static void hostForTest(java.util.UUID id) {
        hostForTest = id;
    }

    /** Who {@code p} is, to the rules. */
    public static ModdySettings.Who who(ServerPlayer p) {
        var server = p.level().getServer();
        boolean dedicated = server != null && server.isDedicatedServer();
        boolean host = server != null && (server.isSingleplayerOwner(p.nameAndId())
                || p.getUUID().equals(hostForTest));
        return new ModdySettings.Who(p.getGameProfile().name(), host, dedicated);
    }

    /** Change a setting for {@code p}, and tell them what happened. The path the branch and a menu run. */
    public static boolean change(ServerPlayer p, String key, String value) {
        ModdySettings.Change c = ModdySettings.shared().change(key, value, who(p));
        p.sendSystemMessage(Component.literal(c.line())
                .withStyle(c.done() ? ChatFormatting.AQUA : ChatFormatting.YELLOW));
        if (c.done()) {
            org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID)
                    .info("settings: {} set {} to {}", p.getGameProfile().name(), key, value);
        }
        return c.done();
    }

    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal(VERB)
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ModdySettings s = ModdySettings.shared();
                    for (Setting st : SettingsCatalog.ALL) {
                        String v = s.value(st.key()).orElse("(default)");
                        String how = s.locked(st.key()) ? " — this server chooses"
                                : s.changeable(st.key(), who(p)) ? "" : " — not yours to change";
                        p.sendSystemMessage(Component.literal(st.key() + " = " + v + how));
                    }
                    return 1;
                })
                .then(Commands.argument("key", StringArgumentType.word())
                        .then(Commands.argument("value", StringArgumentType.word())
                                .executes(ctx -> change(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "key"),
                                        StringArgumentType.getString(ctx, "value")) ? 1 : 0)));
    }
}
