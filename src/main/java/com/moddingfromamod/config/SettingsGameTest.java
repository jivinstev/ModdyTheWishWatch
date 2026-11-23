package com.moddingfromamod.config;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>A setting changes only through the action, and the action enforces the lock</b>. Each test types the
 * real command, {@code /moddy setting agent.model opus}, through the server's dispatcher as the player — no menu in
 * the way, which is the path a stale menu, a typed command or a crafted click all reach.
 *
 * <p>Two batches, because the second hides any plug-in's override ({@code Plugins.hideForTest}, the public mod's case)
 * and the first needs it in place.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SettingsGameTest {

    private static final String COMMAND = "moddy " + SettingsCommand.VERB + " " + SettingsCatalog.MODEL + " opus";

    private static void type(GameTestHelper helper, ServerPlayer p, String command) {
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
    }

    /**
     * An override that locks the model and sets nothing, for a run with no plug-in that locks it (the public mod,
     *): the lock is the public code's, so it is tested there with this stand-in for a server's plug-in.
     */
    private static final SettingsOverride LOCKS_THE_MODEL = new SettingsOverride() {
        @Override
        public java.util.Set<String> keys() {
            return java.util.Set.of(SettingsCatalog.MODEL);
        }

        @Override
        public java.util.Optional<String> value(String key) {
            return java.util.Optional.empty();
        }
    };

    /**
     * With an override that locks the model (a server's override plug-in, or {@link #LOCKS_THE_MODEL} where none is
     * loaded), the model is LOCKED: the action refuses it.
     */
    @GameTest(template = "empty_test", batch = "settings_locked", timeoutTicks = 100)
    public static void aLockedModelIsRefusedByTheActionItself(GameTestHelper helper) {
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "SettingsHost");
        boolean standIn = !ModdySettings.shared().locked(SettingsCatalog.MODEL);
        if (standIn) {
            com.moddingfromamod.plugin.Plugins.shared().replaceForTest(SettingsOverride.class, LOCKS_THE_MODEL);
        }
        try {
            SettingsCommand.hostForTest(kid.getUUID());    // even the host may not change a locked setting
            helper.assertTrue(ModdySettings.shared().locked(SettingsCatalog.MODEL),
                    Component.literal("the override does not lock the model"));
            var before = ModdySettings.get(SettingsCatalog.MODEL);
            type(helper, kid, COMMAND);
            List<String> chat = MockPlayerNetwork.chat(kid);
            helper.assertTrue(chat.stream().anyMatch(l -> l.contains("This server chooses the model")),
                    Component.literal("the locked change was not refused in words: " + chat));
            helper.assertTrue(PlayerSettings.shared().get(SettingsCatalog.MODEL).isEmpty()
                            && ModdySettings.get(SettingsCatalog.MODEL).equals(before),
                    Component.literal("a locked setting changed: " + ModdySettings.get(SettingsCatalog.MODEL)));
            helper.assertTrue(!ModdySettings.shared().changeable(SettingsCatalog.MODEL,
                            SettingsCommand.who(kid)), Component.literal("a menu would offer the locked model"));
        } finally {
            if (standIn) {
                com.moddingfromamod.plugin.Plugins.shared().replaceForTest(SettingsOverride.class, null);
            }
            SettingsCommand.hostForTest(null);
            MockPlayerNetwork.remove(kid);
        }
        helper.succeed();
    }

    /** The public mod (no override): the host changes the model through the action; a LAN guest is refused (D18). */
    @GameTest(template = "empty_test", batch = "settings_public", timeoutTicks = 100)
    public static void withNoOverrideTheHostChangesItAndAGuestCannot(GameTestHelper helper) {
        ServerPlayer host = MockPlayerNetwork.inLevel(helper, "SettingsHost");
        ServerPlayer guest = MockPlayerNetwork.inLevel(helper, "SettingsGuest");
        try {
            com.moddingfromamod.plugin.Plugins.shared().hideForTest(SettingsOverride.class, true);
            PlayerSettings.shared().clearForTest();
            SettingsCommand.hostForTest(host.getUUID());
            type(helper, guest, COMMAND);
            helper.assertTrue(MockPlayerNetwork.chat(guest).stream().anyMatch(l -> l.contains(ModdySettings.HOST_ONLY)),
                    Component.literal("a guest's change was not refused: " + MockPlayerNetwork.chat(guest)));
            helper.assertTrue(PlayerSettings.shared().get(SettingsCatalog.MODEL).isEmpty(),
                    Component.literal("a guest changed the host's model"));
            type(helper, host, COMMAND);
            helper.assertTrue(ModdySettings.get(SettingsCatalog.MODEL).equals(java.util.Optional.of("opus")),
                    Component.literal("the host's change did not take: " + MockPlayerNetwork.chat(host)));
            type(helper, host, "moddy " + SettingsCommand.VERB + " " + SettingsCatalog.SAFETY + " off");
            helper.assertTrue(ModdySettings.get(SettingsCatalog.SAFETY).isEmpty()
                            && MockPlayerNetwork.chat(host).stream().anyMatch(l -> l.contains("can't change")),
                    Component.literal("the safety layer took a player's change: " + MockPlayerNetwork.chat(host)));
        } finally {
            com.moddingfromamod.plugin.Plugins.shared().hideForTest(SettingsOverride.class, false);
            PlayerSettings.shared().clearForTest();
            SettingsCommand.hostForTest(null);
            MockPlayerNetwork.remove(host);
            MockPlayerNetwork.remove(guest);
        }
        helper.succeed();
    }
}
